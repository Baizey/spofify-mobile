package dev.spofify.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deterministic shell composition/inset checks, not a replacement for native screenshot review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ListeningChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactNavigationMeasures56dpWithoutInsetsAndReselectDeliversClicks() {
        var density = 0f
        var taps = 0
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Box(Modifier.width(280.dp)) {
                    CompactNavigation(Modifier.testTag("compact-nav"), WindowInsets(0)) {
                        listOf("Home", "Search", "Playlists").forEach { label ->
                            CompactNavigationItem(label == "Search", { taps++ }, label,
                                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                                modifier = Modifier.testTag("nav-$label"))
                        }
                    }
                }
            }
        }
        val bar = bounds("compact-nav")
        assertEquals(56f * density, bar.height, 0.5f)
        var precedingRight = bar.left
        for (label in listOf("Home", "Search", "Playlists")) {
            val target = bounds("nav-$label")
            assertTrue(target.width >= 48f * density)
            assertEquals(bar.height, target.height, 0.5f)
            assertEquals(precedingRight, target.left, 0.5f)
            precedingRight = target.right
        }
        assertEquals(bar.right, precedingRight, 0.5f)
        compose.onNodeWithTag("nav-Search").assertIsSelected().performClick().performClick()
        compose.onNodeWithTag("nav-Home").assertIsNotSelected()
        compose.runOnIdle { assertEquals(2, taps) }
    }

    @Test fun compactNavigationGrowsForLargeFontsAndCountsLandscapeInsetsOnce() {
        var density = 0f
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val actual = LocalDensity.current
            density = actual.density
            CompositionLocalProvider(LocalDensity provides Density(actual.density, fontScale.value)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Box(Modifier.width(320.dp).testTag("nav-host")) {
                        CompactNavigation(Modifier.testTag("compact-nav"), WindowInsets(left = 36.dp, bottom = 24.dp, right = 12.dp)) {
                            listOf("Home", "Search", "Playlists").forEach { label ->
                                CompactNavigationItem(label == "Search", {}, label,
                                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    modifier = Modifier.testTag("nav-$label"))
                            }
                        }
                    }
                }
            }
        }
        val defaultHeight = bounds("compact-nav").height
        assertEquals(80f * density, defaultHeight, 0.5f)
        assertEquals(36f * density, bounds("nav-Home").left - bounds("nav-host").left, 0.5f)
        assertEquals(12f * density, bounds("nav-host").right - bounds("nav-Playlists").right, 0.5f)
        assertEquals(24f * density, bounds("compact-nav").bottom - bounds("nav-Search").bottom, 0.5f)
        compose.runOnIdle { fontScale.value = 2f }
        val largeBar = bounds("compact-nav")
        assertTrue("Large labels must grow, not clip", largeBar.height > defaultHeight)
        assertEquals(24f * density, largeBar.bottom - bounds("nav-Search").bottom, 0.5f)
        for (label in listOf("Home", "Search", "Playlists")) {
            compose.onNodeWithText(label).assertIsDisplayed()
            val target = bounds("nav-$label")
            assertTrue(target.height >= 48f * density)
            assertTrue(target.width >= 48f * density)
            assertEquals(largeBar.height - 24f * density, target.height, 0.5f)
            val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Label must remain inside its target",
                text.top >= target.top && text.bottom <= target.bottom && text.left >= target.left && text.right <= target.right)
        }
    }

    @Test fun productionContainersUseExistingMintTonesWithReadableForegrounds() {
        val colors = listeningColorScheme()
        val mint = Color(0xFF78E0AC)
        val tonalContainer = Color(0xFF294034)
        assertEquals(mint, colors.primary)
        assertEquals(mint, colors.secondary)
        for ((container, foreground) in listOf(
            colors.primaryContainer to colors.onPrimaryContainer,
            colors.secondaryContainer to colors.onSecondaryContainer,
        )) {
            assertEquals(tonalContainer, container)
            assertEquals(mint, foreground)
            assertEquals(1f, container.alpha, 0f)
            assertEquals(1f, foreground.alpha, 0f)
            val contrast = (foreground.luminance() + .05f) / (container.luminance() + .05f)
            assertTrue("Production container text must meet 4.5:1 contrast (was $contrast)", contrast >= 4.5f)
        }
    }

    @Test fun onlySearchFocusOrItsImeCollapsesChrome() {
        assertFalse(hideListeningChrome(true, false, false))
        assertTrue(hideListeningChrome(true, true, false)) // Hardware keyboard / pre-IME focus.
        assertTrue(hideListeningChrome(true, false, true)) // IME dismissal animation.
        assertTrue(hideListeningChrome(true, true, true))
        for (editing in listOf(false, true)) for (ime in listOf(false, true)) {
            assertFalse(hideListeningChrome(false, editing, ime))
        }
    }

    @Test fun leavingSearchOrChangingClientOrAccountCannotRetainEditingFocus() {
        val searchPage = mutableStateOf(true)
        val client = mutableStateOf(syntheticClient())
        val account = mutableStateOf("account-a")
        lateinit var editing: MutableState<Boolean>
        compose.setContent {
            editing = rememberSearchEditing(client.value, account.value, searchPage.value)
            Text(if (hideListeningChrome(searchPage.value, editing.value, false)) "Hidden" else "Visible")
        }
        compose.runOnIdle { editing.value = true }
        compose.onNodeWithText("Hidden").assertIsDisplayed()
        compose.runOnIdle { searchPage.value = false }
        compose.onNodeWithText("Visible").assertIsDisplayed()
        compose.runOnIdle { assertFalse(editing.value); searchPage.value = true }
        compose.runOnIdle { assertFalse(editing.value); editing.value = true }
        compose.runOnIdle { account.value = "account-b" }
        compose.runOnIdle { assertFalse(editing.value); editing.value = true }
        compose.runOnIdle { client.value = syntheticClient() }
        compose.runOnIdle { assertFalse(editing.value) }
        compose.onNodeWithText("Visible").assertIsDisplayed()
    }

    @Test fun editingCollapsesBothBarsAndRestoresActionsWithoutRemountingMusicOrResults() {
        val editing = mutableStateOf(false)
        val ime = mutableStateOf(false)
        var contentMounts = 0
        var playerMounts = 0
        var contentDisposals = 0
        var playerDisposals = 0
        var accountOpens = 0
        var deviceOpens = 0
        lateinit var scroll: LazyListState
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 640.dp)) {
                    ListeningChrome(
                        chromeHidden = hideListeningChrome(true, editing.value, ime.value),
                        connectionActions = {
                            TextButton(onClick = { deviceOpens++ }) { Text("Devices") }
                            TextButton(onClick = { accountOpens++ }) { Text("Account") }
                        },
                        miniPlayer = {
                            DisposableEffect(Unit) { playerMounts++; onDispose { playerDisposals++ } }
                            Box(Modifier.fillMaxWidth().height(66.dp).testTag("mini"))
                        },
                        navigation = { TestNavigation(Modifier.testTag("primary-navigation"), WindowInsets(0)) },
                        snackbarHost = {},
                    ) { padding ->
                        DisposableEffect(Unit) { contentMounts++; onDispose { contentDisposals++ } }
                        val query = remember { "Billie" }
                        scroll = rememberLazyListState()
                        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                            Text(query)
                            LazyColumn(Modifier.weight(1f).testTag("results"), state = scroll) {
                                items(100) { Text("Result $it", Modifier.height(48.dp)) }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("results").performScrollToIndex(20)
        compose.onNodeWithTag("app-actions").assertIsDisplayed()
        compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
        compose.runOnIdle { editing.value = true }
        compose.onNodeWithTag("app-actions").assertDoesNotExist()
        compose.onNodeWithTag("primary-navigation").assertDoesNotExist()
        compose.onNodeWithTag("mini").assertIsDisplayed()
        compose.onNodeWithText("Billie").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(20, scroll.firstVisibleItemIndex)
            editing.value = false
            ime.value = true
        }
        compose.onNodeWithTag("primary-navigation").assertDoesNotExist()
        compose.runOnIdle { ime.value = false }
        compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
        compose.onNodeWithText("Devices").performClick()
        compose.onNodeWithText("Account").performClick()
        compose.runOnIdle {
            assertEquals(20, scroll.firstVisibleItemIndex)
            assertEquals(1, contentMounts)
            assertEquals(1, playerMounts)
            assertEquals(0, contentDisposals)
            assertEquals(0, playerDisposals)
            assertEquals(1, accountOpens)
            assertEquals(1, deviceOpens)
        }
    }

    @Test fun visibleCompactChromeReservesItsInsetsOnceAndLastResultRemainsReachable() {
        var density = 0f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.size(280.dp, 640.dp).testTag("host")) {
                    ListeningChrome(
                        chromeHidden = false,
                        connectionActions = {},
                        miniPlayer = { Box(Modifier.fillMaxWidth().height(66.dp).testTag("mini")) },
                        navigation = { TestNavigation(Modifier.testTag("primary-navigation"), WindowInsets(bottom = 24.dp)) },
                        snackbarHost = {},
                        systemInsets = WindowInsets(top = 24.dp, bottom = 24.dp),
                        navigationInsets = WindowInsets(bottom = 24.dp),
                    ) { padding ->
                        LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().testTag("results")) {
                            items(100) { Text("Result $it", Modifier.fillMaxWidth().height(48.dp).testTag("result-$it")) }
                        }
                    }
                }
            }
        }
        val host = bounds("host")
        val bar = bounds("primary-navigation")
        val mini = bounds("mini")
        assertEquals(80f * density, bar.height, 0.5f)
        assertEquals(host.bottom, bar.bottom, 0.5f)
        assertEquals(bar.top, mini.bottom, 0.5f)
        assertEquals(host.left, mini.left, 0.5f)
        assertEquals(host.right, mini.right, 0.5f)
        compose.onNodeWithTag("results").performScrollToIndex(99)
        compose.onNodeWithTag("result-99").assertIsDisplayed()
        assertEquals(mini.top, bounds("results").bottom, 0.5f)
        assertTrue(bounds("result-99").bottom <= mini.top)
    }

    @Test fun hiddenChromeKeepsStatusAndSystemNavigationSpaceWithFullWidthMini() {
        val keyboard = mutableStateOf(false)
        var pixelsPerDp = 0f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            GeometryShell(keyboard = keyboard.value, mini = true)
        }
        val host = bounds("host")
        val content = bounds("content")
        val mini = bounds("mini")
        assertEquals(24f * pixelsPerDp, content.top - host.top, 0.5f)
        assertEquals(24f * pixelsPerDp, host.bottom - mini.bottom, 0.5f)
        assertEquals(mini.top, content.bottom, 0.5f)
        assertEquals(host.left, mini.left, 0.5f)
        assertEquals(host.right, mini.right, 0.5f)

        compose.runOnIdle { keyboard.value = true }
        val imeMini = bounds("mini")
        val imeContent = bounds("content")
        // The IME already consumes the 24dp navigation inset; do not insert it again.
        assertEquals(240f * pixelsPerDp, host.bottom - imeMini.bottom, 0.5f)
        assertEquals(imeMini.top, imeContent.bottom, 0.5f)
        assertEquals(content.top, imeContent.top, 0.5f)
        compose.runOnIdle { keyboard.value = false }
        assertEquals(mini, bounds("mini"))
    }

    @Test fun hiddenChromeWithoutAPlayerStillReservesOnlyTheUnconsumedSystemInset() {
        val keyboard = mutableStateOf(false)
        var pixelsPerDp = 0f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            GeometryShell(keyboard = keyboard.value, mini = false)
        }
        val host = bounds("host")
        assertEquals(24f * pixelsPerDp, bounds("content").top - host.top, 0.5f)
        assertEquals(24f * pixelsPerDp, host.bottom - bounds("content").bottom, 0.5f)
        compose.runOnIdle { keyboard.value = true }
        assertEquals(240f * pixelsPerDp, host.bottom - bounds("content").bottom, 0.5f)
    }

    @Test fun landscapeCutoutsProtectMiniContentWhileItsDockStillFillsTheDisplay() {
        val hidden = mutableStateOf(true)
        var pixelsPerDp = 0f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            GeometryShell(keyboard = false, mini = true, hidden = hidden.value, leftCutout = 36.dp, rightCutout = 12.dp)
        }
        fun checkCutouts() {
            val host = bounds("host")
            val mini = bounds("mini")
            val dock = bounds("mini-player-dock")
            val content = bounds("content")
            assertEquals(36f * pixelsPerDp, mini.left - host.left, 0.5f)
            assertEquals(12f * pixelsPerDp, host.right - mini.right, 0.5f)
            assertEquals(mini.left, content.left, 0.5f)
            assertEquals(mini.right, content.right, 0.5f)
            assertEquals(host.left, dock.left, 0.5f)
            assertEquals(host.right, dock.right, 0.5f)
        }
        checkCutouts()
        compose.runOnIdle { hidden.value = false }
        checkCutouts()
    }

    @Test fun landscapeSideNavigationDoesNotNarrowTheDockBackground() {
        var pixelsPerDp = 0f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            GeometryShell(keyboard = false, mini = true, navigationLeft = 36.dp, navigationBottom = 0.dp)
        }
        val host = bounds("host")
        val dock = bounds("mini-player-dock")
        val mini = bounds("mini")
        assertEquals(host.left, dock.left, 0.5f)
        assertEquals(host.right, dock.right, 0.5f)
        assertEquals(36f * pixelsPerDp, mini.left - host.left, 0.5f)
        assertEquals(host.right, mini.right, 0.5f)
        assertEquals(host.bottom, mini.bottom, 0.5f)
        assertEquals(mini.left, bounds("content").left, 0.5f)
    }

    @Composable
    private fun GeometryShell(
        keyboard: Boolean,
        mini: Boolean,
        hidden: Boolean = true,
        leftCutout: Dp = 0.dp,
        rightCutout: Dp = 0.dp,
        navigationLeft: Dp = 0.dp,
        navigationBottom: Dp = 24.dp,
    ) {
        val navigation = WindowInsets(left = navigationLeft, bottom = navigationBottom)
        MaterialTheme {
            Box(Modifier.size(320.dp, 640.dp).testTag("host")) {
                // Same consumption contract as platform imePadding, with deterministic test insets.
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets(bottom = if (keyboard) 240.dp else 0.dp))) {
                    ListeningChrome(
                        chromeHidden = hidden,
                        connectionActions = {},
                        miniPlayer = { if (mini) Box(Modifier.fillMaxWidth().height(66.dp).testTag("mini")) },
                        navigation = { TestNavigation(windowInsets = WindowInsets(
                            left = maxOf(leftCutout, navigationLeft), right = rightCutout, bottom = navigationBottom)) },
                        snackbarHost = {},
                        modifier = Modifier.fillMaxSize(),
                        systemInsets = WindowInsets(left = maxOf(leftCutout, navigationLeft), top = 24.dp,
                            right = rightCutout, bottom = navigationBottom),
                        navigationInsets = navigation,
                    ) { padding ->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().testTag("content"))
                    }
                }
            }
        }
    }

    @Composable
    private fun TestNavigation(modifier: Modifier = Modifier, windowInsets: WindowInsets) {
        CompactNavigation(modifier, windowInsets) {
            listOf("Home", "Search", "Playlists").forEach { label ->
                CompactNavigationItem(label == "Search", {}, label,
                    icon = { Icon(Icons.Default.Search, contentDescription = null) })
            }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun syntheticClient() = SpofifyClient(ServerAddress.parse("https://music.example.test"), object : SessionStore {
        override fun read(): StoredSession? = null
        override fun write(value: StoredSession?) = Unit
    })
}
