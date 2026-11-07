package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import dev.spofify.mobile.playback.PlaybackInteractionKey
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun songTapPlaysNowAndExplicitPlayAndMoreRemainSeparate() {
        val song = LibraryItem("track", "song", "Song", hasAudio = true)
        var opened = 0; var played = 0; var actions = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) { MusicRow(song, { opened++ }, { _, _ -> played++ }, { actions++ }, {},
                artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(360.dp)) }
        }
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { assertEquals(0, opened); assertEquals(1, played) }
        compose.onNodeWithContentDescription("Play Song").assertDoesNotExist()
        val play = compose.onNodeWithText("Song").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Play now" }
        compose.runOnIdle { assertTrue(play.action()) }
        compose.onNodeWithContentDescription("More actions for Song").performClick()
        compose.runOnIdle { assertEquals(2, played); assertEquals(0, opened); assertEquals(1, actions) }
        compose.onNodeWithText("Song").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(2, actions); assertEquals(2, played); assertEquals(0, opened) }
    }

    @Test fun unavailableRowsKeepCompactCreditsAndReservedActionsWithDistinctLinksInMore() {
        val song = LibraryItem("track", "song", "Missing song", hasAudio = false,
            artists = listOf(ArtistCredit("one", "First artist"), ArtistCredit("two", "Second artist")),
            albumId = "album", albumName = "The album")
        val opened = mutableListOf<String>()
        val actions = mutableStateOf(false)
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Column {
                    MusicRow(song, { opened += it.id }, { _, _ -> }, { actions.value = true }, {},
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(400.dp))
                    if (actions.value) MusicCredits(song, { opened += it.id })
                }
            }
        }
        compose.onNodeWithContentDescription("Play Missing song").assertDoesNotExist()
        val actionsForMissing = compose.onNodeWithText("Missing song").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actionsForMissing.none { it.label == "Play now" })
        assertTrue(actionsForMissing.any { it.label == "More actions" })
        compose.onNodeWithText("First artist, Second artist", substring = true).assertExists()
        compose.onNodeWithText("First artist").assertDoesNotExist()
        compose.onNodeWithText("Second artist").assertDoesNotExist()
        compose.onNodeWithContentDescription("More actions for Missing song")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("First artist").performClick()
        compose.onNodeWithText("Second artist").performClick()
        compose.onNodeWithText("The album").performClick()
        compose.runOnIdle { assertEquals(listOf("one", "two", "album"), opened) }
    }

    @Test fun shortHorizontalGestureQueuesOnceAndVerticalAndEdgeGesturesDoNot() {
        var queued = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) { MusicRow(LibraryItem("track", "song", "Song", hasAudio = true), {}, { _, _ -> }, {}, {},
                artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(360.dp).testTag("row"),
                playback = CatalogPlaybackFeedback(isPlaying = true, interactionKey = PlaybackInteractionKey(),
                    onContextual = { _, _, isCurrent -> if (isCurrent()) queued++ })) }
        }
        compose.onNodeWithTag("row").performTouchInput { swipe(start = center.copy(x = width * .3f), end = center.copy(x = width * .8f)) }
        compose.runOnIdle { assertEquals(1, queued) }
        compose.onNodeWithTag("row").performTouchInput { swipe(start = center, end = center.copy(y = height - 1f)) }
        compose.onNodeWithTag("row").performTouchInput { swipe(start = center.copy(x = 1f), end = center.copy(x = width * .8f)) }
        compose.runOnIdle { assertEquals(1, queued) }
    }

    @Test fun searchInputIsDockedInLowerThumbZoneWithResultsAboveIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val reader = object : CatalogReader {
            override suspend fun browse(request: CatalogRequest) = LibraryPage(emptyList(), 0)
            override suspend fun detail(kind: String, id: String) = LibraryItem(kind, id, id)
            override suspend fun revision() = 0L
        }
        val holder = CatalogStateHolder(reader, scope, initialKind = "all")
        try {
            compose.setContent {
                MaterialTheme(typography = AppTypography) {
                    val navigation by holder.state.collectAsState()
                    Box(Modifier.size(360.dp, 640.dp).testTag("viewport")) {
                        CatalogScreenContent(navigation, holder, {}, { _, _ -> }, {}, {},
                            artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val query = compose.onNodeWithTag("search-query").fetchSemanticsNode().boundsInRoot
            assertTrue(query.center.y > viewport.top + viewport.height * .65f)
            assertTrue(query.bottom <= viewport.bottom)
            compose.onNodeWithText("Find an artist, album or song in your saved music. Start typing to search.").assertDoesNotExist()
            compose.onNodeWithContentDescription("Search your music").assertExists()
        } finally { holder.close(); scope.cancel() }
    }

    @Test fun groupedSearchLoadsVisibleFrontierWithoutFilterOrPaginationKnobs() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val reader = object : CatalogReader {
            override suspend fun browse(request: CatalogRequest) = LibraryPage(
                List(minOf(request.limit, 20 - request.offset)) { index ->
                    LibraryItem(request.kind.removeSuffix("s"), "${request.kind}-${request.offset + index}",
                        "${request.kind} ${request.offset + index}")
                }, 20, request.limit, request.offset, revision = 1)
            override suspend fun detail(kind: String, id: String) = LibraryItem(kind, id, id)
            override suspend fun revision() = 1L
        }
        val holder = CatalogStateHolder(reader, scope, initialKind = "all", initialQuery = "music")
        try {
            compose.setContent {
                MaterialTheme(typography = AppTypography) {
                    val navigation by holder.state.collectAsState()
                    LaunchedEffect(holder) { holder.setVisible(true) }
                    CatalogScreenContent(navigation, holder, {}, { _, _ -> }, {}, {},
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.size(360.dp, 640.dp))
                }
            }
            compose.onNodeWithText("Artists").assertDoesNotExist()
            compose.onNodeWithText("Albums").assertDoesNotExist()
            compose.onNodeWithText("Songs").assertDoesNotExist()
            compose.onNodeWithText("20").assertDoesNotExist()
            compose.onNodeWithText("Include missing").assertDoesNotExist()
            compose.onNodeWithText("All").assertDoesNotExist()
            compose.onNodeWithText("Previous").assertDoesNotExist()
            compose.onNodeWithText("Next").assertDoesNotExist()
            compose.onNodeWithText("Back").assertDoesNotExist()
            compose.onNodeWithText("See more artists").assertDoesNotExist()
            // Grouped demand deliberately waits for its actual final row, not the old
            // three-row near-end window, so later result groups cannot be starved.
            compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("tracks 9"))
            compose.waitUntil(5_000) { compose.runOnIdle {
                holder.state.value.current.sections.getValue("tracks").items.size == 20
            } }
            compose.runOnIdle { assertTrue(holder.state.value.current.sections.getValue("tracks").ended) }
        } finally { holder.close(); scope.cancel() }
    }

    @Test fun actualSearchResultsKeepMoreWithoutInlinePlayOrAutoplay() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val song = LibraryItem("track", "search-song", "Search song", hasAudio = true)
        val reader = object : CatalogReader {
            override suspend fun browse(request: CatalogRequest) = LibraryPage(listOf(song), 1, request.limit, request.offset, revision = 1)
            override suspend fun detail(kind: String, id: String) = song
            override suspend fun revision() = 1L
        }
        val holder = CatalogStateHolder(reader, scope, initialKind = "tracks", initialQuery = "song")
        var plays = 0
        var more = 0
        var opened = 0
        try {
            compose.setContent {
                MaterialTheme(typography = AppTypography) {
                    val navigation by holder.state.collectAsState()
                    LaunchedEffect(holder) { holder.setVisible(true) }
                    CatalogScreenContent(navigation, holder, { opened++ }, { _, _ -> plays++ }, { more++ }, {},
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.size(360.dp, 640.dp))
                }
            }
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                compose.onAllNodesWithContentDescription("More actions for Search song").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Search song").assertIsDisplayed()
            compose.onNodeWithContentDescription("Play Search song").assertDoesNotExist()
            compose.onNodeWithContentDescription("More actions for Search song").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(1, more)
                assertEquals("Rendering and deliberate More must not start playback", 0, plays)
                assertEquals(0, opened)
            }
        } finally { holder.close(); scope.cancel() }
    }

    @Test fun searchClearAndRestoredQueryStayInSyncWithoutAutoplay() {
        val query = mutableStateOf("first")
        compose.setContent { MaterialTheme(typography = AppTypography) { CommittedSearchField(query.value) { value, _ -> query.value = value } } }
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle { assertEquals("", query.value); query.value = "restored" }
        compose.onNodeWithText("restored").assertExists()
        compose.onNodeWithContentDescription("Search your music").performTextReplacement("next")
        compose.runOnIdle { assertEquals("next", query.value) }
    }
}
