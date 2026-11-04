package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
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

    @Test fun songTapOpensDetailsAndExplicitPlayAndMoreAreSeparate() {
        val song = LibraryItem("track", "song", "Song", hasAudio = true)
        var opened = 0; var played = 0; var actions = 0
        compose.setContent {
            MaterialTheme { MusicRow(song, { opened++ }, { _, _ -> played++ }, { actions++ }, {},
                artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(360.dp)) }
        }
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(0, played) }
        compose.onNodeWithContentDescription("Play Song").performClick()
        compose.onNodeWithContentDescription("More actions for Song").performClick()
        compose.runOnIdle { assertEquals(1, played); assertEquals(1, actions) }
        compose.onNodeWithText("Song").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(2, actions) }
    }

    @Test fun unavailableRowsKeepDisabledPlaySpaceAndCreditsNavigateIndividually() {
        val song = LibraryItem("track", "song", "Missing song", hasAudio = false,
            artists = listOf(ArtistCredit("one", "First artist"), ArtistCredit("two", "Second artist")),
            albumId = "album", albumName = "The album")
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { MusicRow(song, { opened += it.id }, { _, _ -> }, {}, {},
                artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(400.dp)) }
        }
        compose.onNodeWithContentDescription("Play Missing song").assertIsNotEnabled()
        compose.onNodeWithText("Unavailable").assertExists()
        compose.onNodeWithText("First artist").performClick()
        compose.onNodeWithText("Second artist").performClick()
        compose.onNodeWithText("The album").performClick()
        compose.runOnIdle { assertEquals(listOf("one", "two", "album"), opened) }
    }

    @Test fun shortHorizontalGestureQueuesOnceAndVerticalAndEdgeGesturesDoNot() {
        var queued = 0
        compose.setContent {
            MaterialTheme { MusicRow(LibraryItem("track", "song", "Song", hasAudio = true), {}, { _, _ -> }, {}, { queued++ },
                artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(360.dp).testTag("row")) }
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
                MaterialTheme {
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
            compose.onNodeWithText("Find an artist, album or song in your saved music. Start typing to search.").assertExists()
        } finally { holder.close(); scope.cancel() }
    }

    @Test fun groupedSearchHasGenerousMoreActionsWithoutFilterOrNavigationKnobs() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val reader = object : CatalogReader {
            override suspend fun browse(request: CatalogRequest) = LibraryPage(
                listOf(LibraryItem(request.kind.removeSuffix("s"), "${request.kind}-${request.offset}", "Result")),
                20, request.limit, request.offset, revision = 1)
            override suspend fun detail(kind: String, id: String) = LibraryItem(kind, id, id)
            override suspend fun revision() = 1L
        }
        val holder = CatalogStateHolder(reader, scope, initialKind = "all", initialQuery = "music")
        try {
            compose.setContent {
                MaterialTheme {
                    val navigation by holder.state.collectAsState()
                    LaunchedEffect(holder) { holder.setVisible(true) }
                    CatalogScreenContent(navigation, holder, {}, { _, _ -> }, {}, {},
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.size(360.dp, 640.dp))
                }
            }
            compose.onNodeWithText("Include missing").assertDoesNotExist()
            compose.onNodeWithText("All").assertDoesNotExist()
            compose.onNodeWithText("Previous").assertDoesNotExist()
            compose.onNodeWithText("Next").assertDoesNotExist()
            compose.onNodeWithText("Back").assertDoesNotExist()
            val more = compose.onNodeWithText("See more artists").performScrollTo()
            more.assertHeightIsAtLeast(48.dp)
            more.performClick()
            compose.runOnIdle { assertEquals(2, holder.state.value.current.sections.getValue("artists").items.size) }
        } finally { holder.close(); scope.cancel() }
    }

    @Test fun searchClearAndRestoredQueryStayInSyncWithoutAutoplay() {
        val query = mutableStateOf("first")
        compose.setContent { MaterialTheme { CommittedSearchField(query.value) { value, _ -> query.value = value } } }
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle { assertEquals("", query.value); query.value = "restored" }
        compose.onNodeWithText("restored").assertExists()
        compose.onNodeWithContentDescription("Search your music").performTextReplacement("next")
        compose.runOnIdle { assertEquals("next", query.value) }
    }
}
