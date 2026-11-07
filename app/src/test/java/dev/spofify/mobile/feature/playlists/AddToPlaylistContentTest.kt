package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.capturePolishEvidence
import dev.spofify.mobile.core.PersonalPlaylistSummary
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Compose component frames, not installed-device or real keyboard acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddToPlaylistContentTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var viewport: LazyListState
    private fun target(id: String, name: String = "Playlist $id") = PersonalPlaylistSummary(
        id, name, trackCount = 12, availableCount = 12, revision = 7,
    )
    private fun normal() = AddPlaylistUi("Night song", trackCredit = "Nia · Evening sessions",
        targets = (0..8).map { target("p$it") }, selectedId = "p0", canAdd = true,
        canSelect = true, canCreate = true, searchable = true)

    private fun mount(ui: MutableState<AddPlaylistUi>, actions: AddPlaylistActions = AddPlaylistActions(),
                      scale: Float = 1f, rtl: Boolean = false, height: Int = 680) {
        RuntimeEnvironment.setFontScale(scale)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface(Modifier.width(320.dp).height(height.dp).testTag("picker")) {
                        val listState = rememberLazyListState()
                        viewport = listState
                        AddToPlaylistContent(ui.value, actions, listState, Modifier.fillMaxSize())
                    }
                }
            }
        }
        frame()
    }
    private fun frame() {
        repeat(3) {
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
        }
    }
    private fun capture(name: String) = capturePolishEvidence("picker-$name", compose.onNodeWithTag("picker"))
    private fun assertCleanCopy() {
        for (text in listOf("Manual playlists only", "occurrence", "duplicates", "End of", "Track count unavailable",
            "then choose", "Playlist created", "Nothing was resubmitted", "matching name", "revision", "API", "Verified")) {
            compose.onNodeWithText(text, substring = true, ignoreCase = true).assertDoesNotExist()
        }
    }

    @Test fun selectionHasOneRadioTargetAndOneExplicitAddAction() {
        val ui = mutableStateOf(normal())
        val selected = mutableListOf<String>(); var adds = 0; var creates = 0
        mount(ui, AddPlaylistActions(select = { selected += it; ui.value = ui.value.copy(selectedId = it) },
            add = { adds++ }, beginCreate = { creates++ }))
        compose.onNodeWithText("Night song").assertIsDisplayed()
        compose.onNodeWithText("Nia · Evening sessions").assertIsDisplayed()
        capture("normal-top")
        compose.onNodeWithText("Playlist p2").performScrollTo().assertHeightIsAtLeast(64.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("p2"), selected) }
        frame()
        compose.onNode(hasText("Playlist p2") and isSelected()).assertExists()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasText("Playlist p2"))).assertCountEquals(0)
        compose.onNodeWithText("Add").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, adds); assertEquals(0, creates) }
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        assertCleanCopy()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Playlist p8"))
        compose.onNodeWithText("Playlist p8").assertIsDisplayed()
        capture("normal-terminal")
    }

    @Test fun fontTwoRtlLongRowsAndPrimaryRemainReachable() {
        val ui = mutableStateOf(normal().copy(trackName = "夜の長い曲 — Quiet roads 🌙",
            targets = (0..8).map { target("p$it", "夜のプレイリスト $it — مزيج المساء الطويل") }))
        mount(ui, scale = 2f, rtl = true)
        compose.onNodeWithText("Add").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("rtl-font2-top")
        val last = ui.value.targets.last().name
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(last))
        compose.onNodeWithText(last).assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithText("Add").assertIsDisplayed()
        val row = compose.onNodeWithText(last).getUnclippedBoundsInRoot()
        val add = compose.onNodeWithText("Add").getUnclippedBoundsInRoot()
        assertTrue("Terminal row is not covered by the primary action", row.bottom <= add.top)
        capture("rtl-font2-terminal")
        assertCleanCopy()
    }

    @Test fun createIsSeparateFromSelectionAndAddAtShortImeSizedViewport() {
        val ui = mutableStateOf(normal().copy(creating = true, name = "新しい夜のミックス", description = "A short description"))
        var creates = 0; var adds = 0; var cancels = 0
        mount(ui, AddPlaylistActions(name = { ui.value = ui.value.copy(name = it) },
            description = { ui.value = ui.value.copy(description = it) }, create = { creates++ },
            add = { adds++ }, cancelCreate = { cancels++ }), scale = 2f, height = 360)
        compose.onNodeWithText("Add").assertDoesNotExist()
        compose.onNodeWithText("Playlist p0").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Name").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextInput(" draft")
        frame()
        compose.onNodeWithText("Description (optional)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Create").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("create-font2-short-focused")
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, creates); assertEquals(0, adds); assertEquals(1, cancels) }
        assertCleanCopy()
    }

    @Test fun searchCannotDeclareNoMatchesUntilAllPagesAreCovered() {
        val ui = mutableStateOf(normal().copy(query = "Far away", targets = emptyList(), searchIncomplete = true,
            canSearchMore = true, hiddenSelection = "Playlist p0"))
        var more = 0
        mount(ui, AddPlaylistActions(searchMore = { more++ }))
        compose.onNodeWithText("Search incomplete").assertIsDisplayed()
        compose.onNodeWithText("No matches").assertDoesNotExist()
        compose.onNodeWithText("Search more").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, more) }
        compose.onNodeWithText("Playlist p0").assertIsDisplayed()
        capture("search-incomplete")
        compose.runOnIdle { ui.value = ui.value.copy(searchIncomplete = false, canSearchMore = false) }; frame()
        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithText("Search more").assertDoesNotExist()
        assertCleanCopy()
    }

    @Test fun unconfirmedCreateKeepsOnlyReviewCloseAndReadOnlyDraftAndResults() {
        val ui = mutableStateOf(normal().copy(creating = true, blocked = true, name = "Retained draft",
            message = "Create unconfirmed.", reviewed = listOf(target("p1"))))
        var reviews = 0; var closes = 0; var writes = 0
        mount(ui, AddPlaylistActions(review = { reviews++ }, dismiss = { closes++ },
            create = { writes++ }, add = { writes++ }), scale = 2f)
        compose.onNodeWithText("Create").assertDoesNotExist()
        compose.onNodeWithText("Add").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Retained draft").assert(hasNoClickAction())
        compose.onNodeWithText("Review").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Close").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, reviews); assertEquals(1, closes); assertEquals(0, writes) }
        capture("create-unconfirmed-font2")
        compose.runOnIdle { ui.value = ui.value.copy(busy = true) }; frame()
        compose.onNodeWithText("Review").assertIsNotEnabled()
        compose.onNodeWithText("Close").assertIsEnabled() // Reads are not an irrevocable mutation.
        assertCleanCopy()
    }

    @Test fun targetAndReviewRetryKeepPendingTerminalAnchor() {
        val rows = (0..30).map { target("p$it") }
        val ui = mutableStateOf(normal().copy(targets = rows, targetError = "Could not load playlists."))
        val retry = { ui.value = ui.value.copy(busy = true, canAdd = false, canSelect = false) }
        mount(ui, AddPlaylistActions(retry = retry, retryReview = retry))
        for (review in listOf(false, true)) {
            if (review) {
                compose.runOnIdle { ui.value = normal().copy(blocked = true, message = "Create unconfirmed.",
                    reviewed = rows, reviewError = "Could not load playlists.") }; frame()
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Retry"))
            compose.onNodeWithText("Retry").assertIsDisplayed()
            val before = compose.runOnIdle { viewport.firstVisibleItemIndex to viewport.firstVisibleItemScrollOffset }
            compose.onNodeWithText("Retry").performClick(); frame()
            compose.onNodeWithText("Retry").assertIsDisplayed().assertIsNotEnabled()
            if (!review) {
                compose.onNodeWithText("Add").assertIsNotEnabled()
                compose.onNode(hasText("Playlist p30") and hasClickAction()).assertIsNotEnabled()
            }
            compose.runOnIdle { assertEquals(before, viewport.firstVisibleItemIndex to viewport.firstVisibleItemScrollOffset) }
            capture(if (review) "review-retry-pending" else "target-retry-pending")
        }
    }

    @Test fun adjacentEmptyLoadingErrorConflictAndUnconfirmedAddStatesStayCompact() {
        val ui = mutableStateOf(normal().copy(targets = emptyList(), canAdd = false, searchable = false))
        mount(ui)
        compose.onNodeWithText("No playlists").assertIsDisplayed()
        capture("empty")
        compose.runOnIdle { ui.value = ui.value.copy(busy = true) }; frame()
        compose.onNodeWithText("No playlists").assertDoesNotExist()
        capture("loading")
        compose.runOnIdle { ui.value = ui.value.copy(busy = false, targetError = "Permission denied.") }; frame()
        compose.onNodeWithText("Permission denied.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertHeightIsAtLeast(48.dp)
        capture("read-error")
        compose.runOnIdle { ui.value = normal().copy(stale = true, message = "Playlists changed.", canAdd = false, canSelect = false) }; frame()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Refresh playlists"))
        compose.onNodeWithText("Refresh playlists").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Add").assertIsNotEnabled()
        frame()
        capture("stale-terminal")
        compose.runOnIdle { ui.value = normal().copy(blocked = true, message = "Add unconfirmed.",
            reviewed = listOf(target("p0")), reviewCount = 2, reviewError = "Could not load playlists.") }; frame()
        compose.onNodeWithText("Add").assertDoesNotExist()
        compose.onNodeWithText("2 saved copies").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("add-unconfirmed-retry")
        assertCleanCopy()
    }
}
