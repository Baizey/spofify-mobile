package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.*
import dev.spofify.mobile.ui.theme.AppTypography
import dev.spofify.mobile.playback.PlaybackInteractionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real parent-list coverage: a pair of standalone MusicRows cannot detect Home's old 12dp gap.
 * Native screenshots are a separate, required acceptance gate, not produced by these tests.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h960dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedSongListRowsTest {
    // v2 supports an owned Standard dispatcher; no unconfined continuation/looper timing guesses.
    private val effects = StandardTestDispatcher()
    @get:Rule val compose = createComposeRule(effectContext = effects)
    private val reads = TestScope()
    private val scope = CoroutineScope(SupervisorJob() + reads.coroutineContext)
    private val songs = listOf(
        LibraryItem("track", "one", "First song 音楽 🌙", hasAudio = true,
            artists = listOf(ArtistCredit("artist-one", "Björk")), albumId = "album-one", albumName = "Album one"),
        LibraryItem("track", "two", "A long Unicode title العربية हिन्दी 音楽 🌙 which must ellipsize",
            hasAudio = true,
            artists = listOf(ArtistCredit("artist-two", "宇多田ヒカル 🌙"), ArtistCredit("artist-three", "Анна collaborator"),
                ArtistCredit("", "Credit without an ID")), albumId = "album-two", albumName = "Another album"),
        LibraryItem("track", "three", "Unavailable song", hasAudio = false),
    ) + (4..8).map { LibraryItem("track", "song-$it", "Song $it", hasAudio = true) }
    private var recentFailure = false
    private var artistFailure = false
    private val homeReads = mutableMapOf<String, Int>()
    private fun readShelf(shelf: String) { homeReads[shelf] = homeReads.getOrDefault(shelf, 0) + 1 }
    private val home = HomeStateHolder(object : HomeReader {
        override suspend fun recent(): List<LibraryItem> {
            readShelf("recent")
            if (recentFailure) throw java.io.IOException("Synthetic recent failure")
            return songs
        }
        override suspend fun likedCount(): Int { readShelf("likes"); return 3 }
        override suspend fun playlists(): List<PersonalPlaylistSummary> { readShelf("playlists"); return emptyList() }
    }, scope)
    private val catalog = CatalogStateHolder(object : CatalogReader {
        override suspend fun browse(request: CatalogRequest): LibraryPage {
            if (artistFailure && request.kind == "artists") throw java.io.IOException("Synthetic artist failure")
            return LibraryPage(if (request.kind == "tracks") songs else emptyList(),
                if (request.kind == "tracks") songs.size else 0, request.limit, request.offset, revision = 0)
        }
        override suspend fun detail(kind: String, id: String) = songs.single { it.id == id }
        override suspend fun revision() = 0L
    }, scope, initialKind = "all", initialQuery = "song")
    private val host = mutableStateOf("Home")
    private val width = mutableStateOf(360.dp)
    private val height = mutableStateOf(800.dp)
    private val scale = mutableStateOf(1f)
    private val interaction = PlaybackInteractionKey()
    private val baseFeedback = CatalogPlaybackFeedback(interactionKey = interaction, onContextual = { _, _, _ -> })
    private val feedback = mutableStateOf(baseFeedback)
    private val likes = mutableStateOf(mapOf("one" to CatalogLikeState(liked = true),
        "two" to CatalogLikeState(pending = true), "three" to CatalogLikeState(failed = true)))
    private val events = mutableListOf<Triple<String, String, LibraryItem>>()

    private fun mount() {
        println("SharedSongListRowsTest fork java.io.tmpdir=${System.getProperty("java.io.tmpdir")}")
        home.setVisible(true); catalog.setVisible(true); reads.runCurrent()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                MaterialTheme(typography = AppTypography) {
                    key(host.value, width.value, scale.value) {
                        val owner = host.value
                        val open: (LibraryItem) -> Unit = { events += Triple(owner, "open", it) }
                        val play: (LibraryItem, Boolean) -> Unit = { item, _ -> events += Triple(owner, "play", item) }
                        val more: (LibraryItem) -> Unit = { events += Triple(owner, "more", it) }
                        val queue: (LibraryItem) -> Unit = { events += Triple(owner, "queue", it) }
                        val like: (LibraryItem) -> Unit = { events += Triple(owner, "like", it) }
                        val art: @Composable (LibraryItem, Modifier) -> Unit = { item, modifier ->
                            Box(modifier.testTag("art:${item.id}"))
                        }
                        Box(Modifier.size(width.value, height.value).testTag("viewport")) {
                            if (owner == "Home") LibraryHome(home, open, play, more, queue, {}, {}, art,
                                playback = feedback.value, likes = likes.value, onToggleLike = like)
                            else {
                                val navigation by catalog.state.collectAsState()
                                CatalogScreenContent(navigation, catalog, open, play, more, queue, art,
                                    playback = feedback.value, likes = likes.value, onToggleLike = like)
                            }
                        }
                    }
                }
            }
        }
        drainReads()
    }

    private fun drainReads() {
        compose.runOnIdle { reads.runCurrent() }
    }
    private fun select(owner: String) {
        compose.runOnIdle { host.value = owner; if (owner == "Home") home.setVisible(true) }
        drainReads()
    }
    private fun row(song: LibraryItem) = compose.onNode(hasText(song.name) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
    private fun more(song: LibraryItem) = compose.onNodeWithContentDescription("More actions for ${song.name}")
    private fun scrollTo(song: LibraryItem): SemanticsNodeInteraction {
        // Lazy items outside the viewport may not exist in the semantics tree at all.
        // Search the actual parent list rather than querying a disposed child first.
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(
            hasText(song.name) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        return row(song)
    }
    private fun SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot
    private fun relative(bounds: Rect, row: Rect) = Rect(bounds.left - row.left, bounds.top - row.top,
        bounds.right - row.left, bounds.bottom - row.top)
    private fun assertRect(label: String, a: Rect, b: Rect) {
        assertEquals("$label left", a.left, b.left, 1f); assertEquals("$label top", a.top, b.top, 1f)
        assertEquals("$label width", a.width, b.width, 1f); assertEquals("$label height", a.height, b.height, 1f)
    }
    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().also {
            assertEquals(1, it.lineCount)
            // didOverflowHeight includes intended max-line ellipsis; inspect physical height.
            assertTrue(it.multiParagraph.height <= it.size.height + 1f)
            assertEquals(it.size.height.toFloat(), node.bounds().height, 1f)
            if (it.multiParagraph.maxIntrinsicWidth > it.multiParagraph.width + 1f) assertTrue(it.isLineEllipsized(0))
        }
    }
    private data class Geometry(val slots: Map<String, Rect>, val baselines: List<Float>, val actions: List<String>, val state: String?)
    private fun geometry(song: LibraryItem): Geometry {
        scrollTo(song)
        val bounds = row(song).bounds()
        val title = compose.onNodeWithText(song.name, useUnmergedTree = true)
        val subtitle = compactMusicSubtitle(song)
        val metadata = subtitle.takeIf { it.isNotBlank() }?.let { compose.onNodeWithText(it, useUnmergedTree = true) }
        val art = compose.onNodeWithTag("art:${song.id}", useUnmergedTree = true)
        more(song).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithContentDescription("Play ${song.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause ${song.name}").assertDoesNotExist()
        val rowMatcher = hasText(song.name) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(rowMatcher)).assertCountEquals(1)
        if (song == songs[1]) assertEquals("No reserved playback slot: title reaches More's copy inset",
            more(song).bounds().left - with(compose.density) { 10.dp.toPx() }, title.bounds().right, 1f)
        metadata?.assertHasNoClickAction()
        val slots = linkedMapOf("row" to Rect(0f, 0f, bounds.width, bounds.height),
            "art" to relative(art.bounds(), bounds), "title" to relative(title.bounds(), bounds),
            "more" to relative(more(song).bounds(), bounds))
        val baselines = mutableListOf(title.bounds().top - bounds.top + textLayout(title).firstBaseline)
        metadata?.let {
            slots["metadata"] = relative(it.bounds(), bounds)
            baselines += it.bounds().top - bounds.top + textLayout(it).firstBaseline
        }
        slots.values.forEach { slot -> assertTrue("Slot inside row: $slot / $bounds",
            slot.left >= -1 && slot.top >= -1 && slot.right <= bounds.width + 1 && slot.bottom <= bounds.height + 1) }
        val semantics = row(song).fetchSemanticsNode().config
        return Geometry(slots, baselines, semantics[SemanticsActions.CustomActions].map { it.label },
            semantics.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test fun realHostsSharePitchSlotsBaselinesCapabilitiesAndReachabilityAtNormalNarrowAndFont2() {
        try {
            mount()
            for (font in listOf(1f, 2f)) for (dpWidth in listOf(360.dp, 320.dp)) {
                compose.runOnIdle { width.value = dpWidth; scale.value = font }
                for (playback in listOf(baseFeedback,
                    baseFeedback.copy(trackId = "one", isPlaying = true), baseFeedback.copy(trackId = "one"),
                    baseFeedback.copy(trackId = "one", buffering = true, playWhenReady = true))) {
                    compose.runOnIdle { feedback.value = playback }
                    val byHost = mutableMapOf<String, List<Geometry>>()
                    for (owner in listOf("Home", "Search")) {
                        select(owner)
                        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
                        scrollTo(songs[0])
                        val first = row(songs[0]).bounds()
                        val second = row(songs[1]).bounds()
                        assertEquals("$owner must have zero consecutive song gap (old Home gap was 12dp)",
                            first.height, second.top - first.top, 1f)
                        byHost[owner] = songs.take(3).map(::geometry)
                        scrollTo(songs.last()).assertIsDisplayed()
                        more(songs.last()).assertIsDisplayed()
                        val viewport = compose.onNodeWithTag("viewport").bounds()
                        assertTrue(more(songs.last()).bounds().bottom <= viewport.bottom)
                        if (owner == "Home") {
                            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Your playlists"))
                            assertTrue("Shelf breathing room is not removed with song gap",
                                compose.onNodeWithText("Your playlists").bounds().top - row(songs.last()).bounds().bottom >=
                                    with(compose.density) { 12.dp.toPx() } - 1f)
                        }
                    }
                    byHost.getValue("Home").zip(byHost.getValue("Search")).forEachIndexed { index, (a, b) ->
                        a.slots.forEach { (slot, rect) -> assertRect("song=$index, width=$dpWidth font=$font $slot", rect, b.slots.getValue(slot)) }
                        a.baselines.zip(b.baselines).forEach { (x, y) -> assertEquals(x, y, 1f) }
                        assertEquals(a.actions, b.actions); assertEquals(a.state, b.state)
                    }
                }
            }
            assertTrue("Composition/refresh/scroll never plays", events.isEmpty())
        } finally { catalog.close(); home.setVisible(false); scope.cancel() }
    }

    @Test fun retainedHostFailuresKeepSongActionsAndRetriesScopedAndParentScrollReachesLastSong() {
        try {
            // Eight 72dp songs must genuinely overflow BOTH hosts, including Search's dock.
            height.value = 480.dp
            mount()
            val before = homeReads.toMap()
            compose.runOnIdle { recentFailure = true; home.invalidate("recent"); reads.runCurrent() }
            compose.onNodeWithText("Retry recently played").assertExists()
            row(songs.first()).assertExists()
            more(songs.first()).performClick()
            compose.runOnIdle {
                assertSame(songs.first(), events.single().third)
                assertEquals(before["likes"], homeReads["likes"])
                assertEquals(before["playlists"], homeReads["playlists"])
                recentFailure = false
            }
            compose.onNodeWithText("Retry recently played").performClick()
            drainReads()
            compose.onNodeWithText("Retry recently played").assertDoesNotExist()
            compose.runOnIdle { artistFailure = true; catalog.retry("artists"); reads.runCurrent() }
            select("Search")
            compose.onNodeWithText("Retry artists").assertExists()
            more(songs.first()).performClick()
            compose.runOnIdle { artistFailure = false }
            compose.onNodeWithText("Retry artists").performClick()
            drainReads()
            compose.onNodeWithText("Retry artists").assertDoesNotExist()
            for (owner in listOf("Home", "Search")) {
                select(owner)
                compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
                val firstTop = row(songs.first()).bounds().top
                compose.onAllNodes(hasScrollToIndexAction()).onFirst().performTouchInput { swipeUp() }
                val firstAfter = compose.onAllNodes(hasText(songs.first().name) and
                    SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes().singleOrNull()
                assertTrue("$owner actual parent touch scroll must move overflowing song content", firstAfter == null || firstAfter.boundsInRoot.top < firstTop)
                scrollTo(songs.last()).assertIsDisplayed()
                more(songs.last()).assertIsDisplayed()
            }
            compose.runOnIdle { assertFalse(events.any { it.second == "play" || it.second == "queue" }) }
        } finally { catalog.close(); home.setVisible(false); scope.cancel() }
    }

    @Test fun bothHostActionOwnersReceiveExactItemsAndChildMoreNeverNavigatesOrQueues() {
        try {
            mount()
            for (owner in listOf("Home", "Search")) {
                select(owner)
                compose.runOnIdle {
                    feedback.value = baseFeedback.copy(isPlaying = true, onContextual = { item, binding, isCurrent ->
                        assertSame(interaction, binding)
                        if (isCurrent()) events += Triple(owner, "queue", item)
                    })
                }
                val song = songs.first()
                scrollTo(song)
                compose.runOnIdle { events.clear() }
                compose.onNodeWithText(song.name).performClick()
                more(song).performClick()
                row(song).performTouchInput { longClick(center.copy(x = width * .35f)) }
                val custom = row(song).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                compose.runOnIdle {
                    assertTrue(custom.single { it.label == "More actions" }.action())
                    assertTrue(custom.single { it.label == "Queue" }.action())
                    assertTrue(custom.single { it.label == "Unlike song" }.action())
                    assertEquals(listOf("open", "more", "more", "more", "queue", "like"), events.map { it.second })
                    events.forEach { assertEquals(owner, it.first); assertSame(song, it.third) }
                    events.clear()
                }
                row(song).performTouchInput { swipe(start = center.copy(x = width * .3f), end = center.copy(x = width * .8f)) }
                compose.runOnIdle {
                    assertEquals(listOf(Triple(owner, "queue", song)), events)
                    events.clear()
                }
                row(song).performTouchInput { down(center.copy(x = width * .3f)); moveTo(center.copy(x = width * .6f)) }
                val previousFeedback = feedback.value
                compose.runOnIdle { feedback.value = previousFeedback.copy(interactionKey = PlaybackInteractionKey()) }
                row(song).performTouchInput { moveTo(center.copy(x = width * .8f)); up() }
                compose.runOnIdle {
                    assertTrue("Held action cannot rebind to a new interaction generation", events.isEmpty())
                    feedback.value = previousFeedback
                }
                row(song).performTouchInput { swipe(start = center, end = center.copy(x = center.x + 8)) }
                compose.runOnIdle {
                    // Below touch slop is still an identity tap, never an enqueue/play.
                    assertEquals(listOf(Triple(owner, "open", song)), events)
                    events.clear()
                }
                row(song).performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(8f, 0f)); cancel() }
                compose.runOnIdle { assertTrue("$owner cancelled gesture must not dispatch: $events", events.isEmpty()) }
                row(song).performTouchInput { swipe(start = center.copy(x = 1f), end = center.copy(x = width * .8f)) }
                compose.runOnIdle {
                    // This row-local injection has no Android Back interceptor. The excluded,
                    // unconsumed sequence can complete as identity click, but NEVER enqueue/play.
                    assertEquals(listOf(Triple(owner, "open", song)), events)
                    events.clear()
                }
                row(song).performTouchInput {
                    down(center.copy(x = 1f)); moveTo(center.copy(x = width * .8f)); cancel()
                }
                compose.runOnIdle {
                    assertTrue("$owner intercepted/cancelled edge must not dispatch: $events", events.isEmpty())
                }
                scrollTo(songs[1])
                assertFalse(row(songs[1]).fetchSemanticsNode().config[SemanticsActions.CustomActions].any { it.label.contains("Like") })
                scrollTo(songs[2])
                val unavailable = row(songs[2]).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                assertFalse(unavailable.any { it.label == "Queue" })
                more(songs[2]).performClick()
                compose.runOnIdle { assertEquals(listOf(Triple(owner, "more", songs[2])), events) }
            }
        } finally { catalog.close(); home.setVisible(false); scope.cancel() }
    }
}
