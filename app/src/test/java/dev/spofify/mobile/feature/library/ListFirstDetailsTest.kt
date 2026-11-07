package dev.spofify.mobile.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.*
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production details and native-graphics host renders, not installed Android evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListFirstDetailsTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val holders = mutableListOf<CatalogStateHolder>()
    private val artist = LibraryItem("artist", "artist", "宇多田ヒカル 🌙 & Björk", coverage = Coverage(37, 30, 17))
    private val album = LibraryItem("album", "album", "音楽 🌙 — A Map of the Quiet City",
        artists = listOf(ArtistCredit("a", "宇多田ヒカル"), ArtistCredit("b", "Björk")),
        coverage = Coverage(4, 3), releaseDate = "2025-09", durationMs = 801_000)
    private class Reader(val detail: LibraryItem, val total: Int, val batch: Int = 40) : CatalogReader {
        data class Pending(val request: CatalogRequest, val reply: CompletableDeferred<LibraryPage>)
        val reads = mutableListOf<CatalogRequest>()
        val pending = mutableListOf<Pending>()
        override suspend fun browse(request: CatalogRequest): LibraryPage {
            reads += request
            if (request.offset == 0) return page(request)
            return CompletableDeferred<LibraryPage>().also { pending += Pending(request, it) }.await()
        }
        fun page(request: CatalogRequest): LibraryPage {
            val count = if (request.kind == "albums") total else if (detail.kind == "album") total else 0
            return LibraryPage(List(minOf(batch, (count - request.offset).coerceAtLeast(0))) { index ->
                val at = request.offset + index
                if (request.kind == "albums") LibraryItem("album", "release-$at", "Release $at",
                    coverage = Coverage(9, 8), releaseDate = "2025", albumType = "Album")
                else LibraryItem("track", "song-$at", "Song $at 音楽 🌙", artists = detail.artists,
                    hasAudio = at != 1, durationMs = 185_000)
            }, count, request.limit, request.offset, 1)
        }
        override suspend fun detail(kind: String, id: String) =
            if (kind == detail.kind && id == detail.id) detail else LibraryItem(kind, id, "Opened $id")
        override suspend fun revision() = 1L
    }
    private fun holder(reader: Reader) = CatalogStateHolder(reader, scope, initialItem = reader.detail).also {
        holders += it
        compose.runOnIdle { it.setVisible(true) }
    }
    @After fun close() { compose.runOnIdle { holders.forEach { it.close() }; scope.cancel() } }
    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) { compose.runOnIdle(condition) }
    private fun capture(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "list-details-renders").apply { mkdirs() }
        val file = File(directory, "$name.png")
        val bitmap = compose.onNodeWithTag("details-host").captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        println("Host native-graphics component render (not installed Android): $file")
    }
    @Composable private fun Host(holder: CatalogStateHolder, width: Int = 400, height: Int = 760,
        scale: Float = 1f, onPlay: (LibraryItem, Boolean) -> Unit = { _, _ -> }, onMore: (LibraryItem) -> Unit = {}) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.requiredSize(width.dp, height.dp).testTag("details-host"),
                    color = MaterialTheme.colorScheme.background) {
                    CatalogScreenContent(holder.state.collectAsState().value, holder, holder::open, onPlay, onMore, {},
                        artwork = { item, modifier -> Box(modifier.background(Color(0xFF31564A))) { Text(item.name.take(1)) } })
                }
            }
        }
    }

    @Test fun zeroOneTwoThreeAndManyReleasesUseOneRowMajorVerticalListAndResponsiveColumns() {
        val mounted = mutableStateOf<CatalogStateHolder?>(null)
        val width = mutableIntStateOf(400)
        val height = mutableIntStateOf(760)
        val scale = mutableFloatStateOf(1f)
        compose.setContent { mounted.value?.let { Host(it, width.intValue, height.intValue, scale.floatValue) } }
        for (count in listOf(0, 1, 2, 3, 47)) {
            val reader = Reader(artist, count)
            val current = holder(reader)
            compose.runOnIdle { mounted.value = current }
            await { current.state.value.current.sections["albums"]?.loaded == true }
            if (count == 0) compose.onNodeWithText("No saved albums in this group.").assertIsDisplayed()
            else {
                val first = compose.onNodeWithText("Release 0").fetchSemanticsNode().boundsInRoot
                if (count >= 2) {
                    val second = compose.onNodeWithText("Release 1").fetchSemanticsNode().boundsInRoot
                    assertEquals("Two columns, row-major", first.top, second.top, 1f)
                    assertTrue(second.left > first.left)
                }
                if (count >= 3) {
                    val third = compose.onNodeWithText("Release 2").fetchSemanticsNode().boundsInRoot
                    assertTrue("Third release is a second row, not capped", third.top > first.top)
                }
            }
            compose.runOnIdle { assertEquals("No offscreen fetch-all", 1, reader.reads.size) }
            if (count == 3) capture("artist-three-normal")
            compose.runOnIdle { current.setVisible(false) }
        }
        val current = holder(Reader(artist, 3))
        compose.runOnIdle { mounted.value = current; width.intValue = 320; scale.floatValue = 1f }
        await { current.state.value.current.sections["albums"]?.loaded == true }
        capture("artist-320")
        compose.runOnIdle { scale.floatValue = 2f }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("More actions for ${artist.name}")
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val first = compose.onNodeWithText("Release 0").fetchSemanticsNode().boundsInRoot
        // The second release can be below the finite viewport at font2; scroll the same list.
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Release 1"))
        assertEquals(1, artistReleaseColumns(288f, 2f))
        compose.onNodeWithContentDescription("More actions for Release 1").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertTrue(first.width > 0)
        capture("artist-320-font2-scrolled")
        compose.runOnIdle { width.intValue = 760; height.intValue = 360; scale.floatValue = 1f }
        compose.onNodeWithTag("catalog-results").performScrollToIndex(0)
        capture("artist-landscape")
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Release 2"))
        compose.onNodeWithText("Release 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test fun gridContinuationSingleFlightRetryAndAlbumBackKeepExactAnchorAndSource() {
        val reader = Reader(artist, 35, batch = 10)
        val current = holder(reader)
        var plays = 0
        var more: LibraryItem? = null
        compose.setContent { Host(current, onPlay = { _, _ -> plays++ }, onMore = { more = it }) }
        await { current.state.value.current.sections["albums"]?.loaded == true }
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Release 8"))
        await { reader.pending.size == 1 }
        val anchor = compose.runOnIdle { current.state.value.current.let { it.scrollIndex to it.scrollOffset } }
        compose.runOnIdle {
            repeat(30) { current.demandMore("albums") }
            assertEquals(listOf(0, 10), reader.reads.map { it.offset })
            reader.pending.single().reply.complete(reader.page(reader.pending.single().request))
        }
        await { current.state.value.current.sections.getValue("albums").items.size == 20 }
        compose.runOnIdle { assertEquals(anchor, current.state.value.current.let { it.scrollIndex to it.scrollOffset }) }
        compose.onNodeWithContentDescription("More actions for Release 8").performClick()
        compose.runOnIdle { assertEquals("release-8", more?.id); assertEquals(0, plays) }
        compose.onNodeWithText("Release 8").performClick()
        await { current.state.value.current.item?.id == "release-8" }
        compose.runOnIdle { assertTrue(current.back()) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(anchor, current.state.value.current.let { it.scrollIndex to it.scrollOffset }) }
        compose.onNodeWithText("Release 8").assertIsDisplayed()
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Release 18"))
        await { reader.pending.size == 2 }
        compose.runOnIdle { reader.pending.last().reply.completeExceptionally(IOException()) }
        await { current.state.value.current.sections.getValue("albums").error != null }
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Retry albums"))
        compose.runOnIdle { current.setVisible(false); current.setVisible(true) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(0, 10, 0, 20), reader.reads.map { it.offset }) }
        compose.onNodeWithText("Retry albums").performClick()
        await { reader.pending.size == 3 }
        compose.runOnIdle {
            assertEquals(20, reader.pending.last().request.offset)
            reader.pending.last().reply.complete(reader.page(reader.pending.last().request))
        }
        await { current.state.value.current.sections.getValue("albums").items.size == 30 }
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun oneReleaseBatchesAdvanceWithinTheSameGridRowWithoutAnEagerDrain() {
        val reader = Reader(artist, 9, batch = 1)
        val current = holder(reader)
        compose.setContent { Host(current) }
        await { reader.pending.size == 1 }
        compose.runOnIdle { reader.pending[0].reply.complete(reader.page(reader.pending[0].request)) }
        await { reader.pending.size == 2 }
        compose.runOnIdle { reader.pending[1].reply.complete(reader.page(reader.pending[1].request)) }
        await { current.state.value.current.sections.getValue("albums").items.size == 3 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Stationary viewport fills at most twice", listOf(0, 1, 2), reader.reads.map { it.offset }) }
        compose.onNodeWithTag("catalog-results").performTouchInput { swipeUp() }
        await { reader.pending.size == 3 }
        compose.runOnIdle {
            assertEquals(3, reader.pending.last().request.offset)
            reader.pending.last().reply.complete(reader.page(reader.pending.last().request))
        }
        await { current.state.value.current.sections.getValue("albums").items.size >= 4 }
    }

    @Test fun releaseIdentityAnchorsSurviveRegroupingAndAlbumBackAtChangedWidth() {
        val current = holder(Reader(artist, 20))
        val scale = mutableFloatStateOf(2f)
        compose.setContent { Host(current, width = 320, scale = scale.floatValue) }
        await { current.state.value.current.sections["albums"]?.loaded == true }
        // At a row boundary the preceding release can still be visible in content padding.
        compose.onNodeWithTag("catalog-results").performScrollToIndex(12)
        await { current.state.value.current.releaseAnchorId == "release-9" }
        compose.runOnIdle { assertEquals(0, current.state.value.current.scrollOffset); scale.floatValue = 1f }
        compose.waitForIdle()
        compose.onNodeWithText("Release 9").assertIsDisplayed()
        compose.runOnIdle { assertEquals(7, current.state.value.current.scrollIndex) }
        compose.runOnIdle { scale.floatValue = 2f }
        compose.onNodeWithTag("catalog-results").performScrollToIndex(12)
        await { current.state.value.current.releaseAnchorId == "release-9" }
        compose.onNodeWithText("Release 9").performClick()
        await { current.state.value.current.item?.id == "release-9" }
        compose.runOnIdle { scale.floatValue = 1f }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(current.back()) }
        compose.waitForIdle()
        compose.onNodeWithText("Release 9").assertIsDisplayed()
        compose.runOnIdle { assertEquals(7, current.state.value.current.scrollIndex) }
    }

    @Test fun albumUsesRealMetadataSharedSongRowsAndCompleteCollectionActionsAtEveryViewport() {
        val current = holder(Reader(album, 4))
        val width = mutableIntStateOf(400)
        val height = mutableIntStateOf(760)
        val scale = mutableFloatStateOf(1f)
        val plays = mutableListOf<LibraryItem>()
        val menus = mutableListOf<LibraryItem>()
        compose.setContent { Host(current, width.intValue, height.intValue, scale.floatValue,
            onPlay = { item, _ -> plays += item }, onMore = { menus += it }) }
        await { current.state.value.current.sections["tracks"]?.loaded == true }
        compose.onNodeWithText("2025 · 4 songs · 13:21").assertIsDisplayed()
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle { assertEquals(listOf(album), plays) }
        for ((name, dimensions) in listOf("normal" to Triple(400, 760, 1f), "320" to Triple(320, 760, 1f),
            "font2" to Triple(320, 760, 2f), "landscape" to Triple(760, 360, 1f))) {
            compose.runOnIdle { width.intValue = dimensions.first; height.intValue = dimensions.second; scale.floatValue = dimensions.third }
            compose.onNodeWithTag("catalog-results").performScrollToIndex(0)
            compose.onNodeWithContentDescription("More actions for ${album.name}")
                .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            capture("album-$name")
            compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Song 3 音楽 🌙"))
            compose.onNodeWithText("Song 3 音楽 🌙").assertIsDisplayed()
            compose.onNodeWithContentDescription("More actions for Song 3 音楽 🌙").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.runOnIdle {
            assertEquals("Browsing/resize/More never plays", 1, plays.size)
            assertEquals("Each More tap dispatches the exact collection once", List(4) { album }, menus)
        }
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithContentDescription("Download").assertDoesNotExist()
    }
}
