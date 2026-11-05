package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import dev.spofify.mobile.ui.theme.AppTypography
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production CatalogScreenContent + real lazy geometry. Not installed-native acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class CatalogContinuousHostTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var holder: CatalogStateHolder
    private class Reader(val total: Int = 30, val initialSize: Int = 10,
        val initialSizes: Map<String, Int> = emptyMap()) : CatalogReader {
        data class Pending(val request: CatalogRequest, val reply: CompletableDeferred<LibraryPage>)
        val requests = mutableListOf<CatalogRequest>()
        val pending = mutableListOf<Pending>()
        override suspend fun browse(request: CatalogRequest): LibraryPage {
            requests += request
            if (request.offset == 0) return page(request, initialSizes[request.kind] ?: initialSize)
            return CompletableDeferred<LibraryPage>().also { pending += Pending(request, it) }.await()
        }
        fun page(request: CatalogRequest, size: Int = request.limit) = LibraryPage(
            List(minOf(size, (total - request.offset).coerceAtLeast(0))) { index ->
                val at = request.offset + index
                val label = when (request.kind) { "artists" -> "Artist"; "albums" -> "Album"; else -> "Song" }
                LibraryItem(request.kind.removeSuffix("s"), "${request.kind}-$at", "$label $at", hasAudio = true)
            }, total, request.limit, request.offset, 1)
        override suspend fun detail(kind: String, id: String) = LibraryItem(kind, id, id)
        override suspend fun revision() = 1L
    }
    private fun mount(reader: Reader, kind: String = "tracks", query: String = "music", height: Int = 400) {
        holder = CatalogStateHolder(reader, scope, kind, query)
        compose.runOnIdle { holder.setVisible(true) }
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                val navigation by holder.state.collectAsState()
                CatalogScreenContent(navigation, holder, { holder.open(it) }, { _, _ -> }, {}, {},
                    artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.size(320.dp, height.dp))
            }
        }
        compose.waitForIdle()
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) { compose.runOnIdle(condition) }
    @After fun close() {
        compose.runOnIdle { if (::holder.isInitialized) holder.close(); scope.cancel() }
    }

    @Test fun visibleFrontierReservesOneBatchPreservesAnchorAndStopsAtEnd() {
        val reader = Reader(total = 20)
        mount(reader)
        compose.runOnIdle { assertEquals(listOf(0), reader.requests.map { it.offset }) }
        compose.onNodeWithTag("catalog-results").performScrollToIndex(7)
        await { reader.pending.size == 1 }
        val anchor = compose.runOnIdle { holder.state.value.current.let { it.scrollIndex to it.scrollOffset } }
        compose.onNodeWithContentDescription("Loading more songs").assertExists()
        compose.runOnIdle {
            repeat(30) { holder.more("tracks") }
            assertEquals(listOf(0, 10), reader.requests.map { it.offset })
            val pending = reader.pending.single()
            pending.reply.complete(reader.page(pending.request))
        }
        await { holder.state.value.current.sections.getValue("tracks").items.size == 20 }
        compose.runOnIdle {
            assertEquals(anchor, holder.state.value.current.let { it.scrollIndex to it.scrollOffset })
            assertTrue(holder.state.value.current.sections.getValue("tracks").ended)
        }
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("End of results"))
        compose.onNodeWithText("Song 19").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(0, 10), reader.requests.map { it.offset }) }
        compose.onNodeWithText("See more songs").assertDoesNotExist()
    }

    @Test fun songsVisibleNeverExpandOffscreenEarlierGroupsAndBackRestoresAnchor() {
        val reader = Reader(total = 40, initialSizes = mapOf("artists" to 1, "albums" to 1))
        mount(reader, kind = "all", height = 400)
        // The initial viewport contains the entire Artists/Albums frontier AND Songs.
        // Only the last visible row group is allowed to request: neither earlier tail loads.
        compose.onNodeWithText("Artist 0").assertIsDisplayed()
        compose.onNodeWithText("Song 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(3, reader.requests.size) }
        compose.onNodeWithTag("catalog-results").performScrollToIndex(9)
        await { reader.pending.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf("tracks"), reader.pending.map { it.request.kind })
            assertEquals(listOf(0), reader.requests.filter { it.kind == "artists" }.map { it.offset })
            assertEquals(listOf(0), reader.requests.filter { it.kind == "albums" }.map { it.offset })
            val pending = reader.pending.single()
            pending.reply.complete(reader.page(pending.request))
        }
        await { holder.state.value.current.sections.getValue("tracks").items.size == 20 }
        val saved = compose.runOnIdle { holder.state.value.current.let { it.scrollIndex to it.scrollOffset } }
        compose.onNodeWithText("Song 7").performClick()
        compose.runOnIdle { assertTrue(holder.back()) }
        compose.waitForIdle()
        compose.onNodeWithText("Song 7").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(saved, holder.state.value.current.let { it.scrollIndex to it.scrollOffset })
            assertEquals(4, reader.requests.size)
        }
        for (label in listOf("Artists", "Albums", "Songs", "Previous", "Next"))
            compose.onNodeWithText(label).assertDoesNotExist()
    }

    @Test fun failedAppendStaysLatchedUntilAccessibleRetryOfSameOffset() {
        val reader = Reader(total = 30)
        mount(reader)
        compose.onNodeWithTag("catalog-results").performScrollToIndex(7)
        await { reader.pending.size == 1 }
        compose.runOnIdle { reader.pending.single().reply.completeExceptionally(IOException()) }
        await { holder.state.value.current.sections.getValue("tracks").error != null }
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Retry songs"))
        compose.onNodeWithText("Retry songs").assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { holder.setVisible(false); holder.setVisible(true) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(0, 10), reader.requests.map { it.offset })
            assertEquals(10, holder.state.value.current.sections.getValue("tracks").items.size)
        }
        compose.onNodeWithText("Retry songs").performClick()
        await { reader.pending.size == 2 }
        compose.runOnIdle {
            assertEquals(listOf(0, 10, 10), reader.requests.map { it.offset })
            val retry = reader.pending.last()
            retry.reply.complete(reader.page(retry.request))
        }
        await { holder.state.value.current.sections.getValue("tracks").items.size == 20 }
    }

    @Test fun shortVisibleBatchCanFillButAppendAloneCannotDrainCatalog() {
        val reader = Reader(total = 500, initialSize = 1)
        mount(reader, height = 640)
        await { reader.pending.size == 1 }
        compose.runOnIdle {
            val next = reader.pending.last()
            next.reply.complete(reader.page(next.request, size = 1))
        }
        await { reader.pending.size == 2 }
        compose.runOnIdle {
            val next = reader.pending.last()
            next.reply.complete(reader.page(next.request, size = 1))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals(listOf(0, 1, 2), reader.requests.map { it.offset })
            assertEquals(3, holder.state.value.current.sections.getValue("tracks").items.size)
        }
    }

    @Test fun blankSearchHasNoAutomaticMatchAllReads() {
        val reader = Reader()
        mount(reader, kind = "all", query = " ")
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertTrue(reader.requests.isEmpty()) }
        compose.onNodeWithContentDescription("Search your music").assertExists()
    }
}
