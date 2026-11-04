package dev.spofify.mobile.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.spofify.mobile.LibraryViewModel
import dev.spofify.mobile.MainActivity
import dev.spofify.mobile.Page
import dev.spofify.mobile.Tab
import dev.spofify.mobile.playback.PlaybackState
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/**
 * Run only after pm clear on a fresh isolated test AVD. One journey intentionally owns one fixture
 * origin for the whole Application lifetime. Uses the real Activity, Keystore, networking and Media3.
 * Screenshots are full-device UiAutomation bitmaps, not Compose/Robolectric/source renders.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NativeUiSmokeTest {
    companion object {
        // Defense in depth after the runner's pre-Application guard, before Activity/test writes.
        @JvmStatic @BeforeClass fun requireVirtualAvdBeforeActivityLaunch() { VirtualAvdOnly.verify() }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence: File get() {
        VirtualAvdOnly.requireVerified()
        return File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "ui-evidence")
            .apply { check(isDirectory || mkdirs()) { "Cannot create native evidence directory" } }
    }
    private val expandPlayer = SemanticsMatcher("Expand Now Playing action") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Expand Now Playing"
    }

    private val playerSheet = hasTestTag("now-playing")
    private val playerPosition = hasContentDescription("Playback position") and inPlayerSheet()

    @Test fun syntheticListenerJourneyAndRenderedEvidence() {
        VirtualAvdOnly.requireVerified()
        UiFixtureServer().use { fixture ->
            val originalFontSetting = shell("settings get system font_scale").trim()
            val originalFontScale = compose.activity.resources.configuration.fontScale
            check(originalFontSetting == "null" || originalFontSetting.toFloatOrNull()?.let { it.isFinite() && it > 0f } == true) {
                "Cannot safely restore the AVD's baseline font setting"
            }
            val originalDisplaySize = shell("wm size")
            val originalSizeOverride = Regex("Override size: (\\d+x\\d+)").find(originalDisplaySize)?.groupValues?.get(1)
            val baselineDisplaySize = originalSizeOverride
                ?: checkNotNull(Regex("Physical size: (\\d+x\\d+)").find(originalDisplaySize)).groupValues[1]
            val baselineWidth = baselineDisplaySize.substringBefore('x').toInt()
            var sizeChanged = false
            var fontChanged = false
            var journeyFailure: Throwable? = null
            try {
                writeRunInfo()
                waitFor(hasText("Server URL"))
                screenshot("01-sign-in")
                compose.onNodeWithText("Font license").performScrollTo().performClick()
                val fontLicenseTitle = hasText("JetBrains Mono font license")
                waitFor(fontLicenseTitle)
                compose.onNode(fontLicenseTitle).assertIsDisplayed()
                val fullFontLicense = instrumentation.targetContext.assets.open("licenses/jetbrains-mono/OFL.txt")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                compose.onNodeWithText(fullFontLicense).assertExists()
                compose.onNodeWithText("Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono)", substring = true).assertExists()
                compose.onNodeWithText("SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007", substring = true).assertExists()
                compose.onNodeWithText("OTHER DEALINGS IN THE FONT SOFTWARE.", substring = true).assertExists()
                screenshot("00a-font-license")
                pressBack()
                waitUntilAbsent(fontLicenseTitle)
                compose.onNodeWithText("Connect & listen").assertExists()
                // Typography must also fit before authentication, including its offline dialog.
                sizeChanged = true
                val signInNarrowWidth = (320f * compose.activity.resources.displayMetrics.density).roundToInt()
                shell("wm size ${signInNarrowWidth}x${baselineDisplaySize.substringAfter('x')}")
                compose.waitUntil(20_000) { compose.activity.resources.displayMetrics.widthPixels == signInNarrowWidth }
                screenshot("01c-narrow-sign-in")
                fontChanged = true
                shell("settings put system font_scale 2.0")
                waitForFont(2f)
                compose.onNodeWithText("Connect & listen").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                screenshot("01d-font2-narrow-sign-in")
                compose.onNodeWithText("Font license").performScrollTo().performClick()
                waitFor(fontLicenseTitle)
                screenshot("00b-font2-narrow-font-license")
                compose.onNodeWithText("Done").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                pressBack()
                waitUntilAbsent(fontLicenseTitle)
                shell("settings put system font_scale $originalFontScale")
                waitForFont(originalFontScale)
                shell("wm size ${originalSizeOverride ?: "reset"}")
                compose.waitUntil(20_000) { compose.activity.resources.displayMetrics.widthPixels == baselineWidth }
                compose.onNodeWithText("Server URL").performScrollTo().performClick().performTextReplacement(fixture.origin)
                compose.onNodeWithText("Username").performScrollTo().performClick().performTextReplacement(UiFixtureServer.USERNAME)
                compose.onNodeWithText("Password").performScrollTo().performClick().performTextReplacement(UiFixtureServer.PASSWORD)
                compose.onNodeWithText("Connect & listen").performScrollTo().assertIsDisplayed().assertIsEnabled()
                waitForIme(true)
                screenshot("01b-sign-in-keyboard") // Synthetic password stays masked.
                pressBack() // Native Back dismisses the IME, not the sign-in screen.
                waitForIme(false)
                compose.onNodeWithText("Connect & listen").performScrollTo().performClick()

                waitFor(hasText("Recently played"))
                waitFor(hasText(UiFixtureServer.TRACK_NAMES.first()))
                screenshot("02-home")
                compose.onNodeWithText("Liked songs").performClick()
                waitFor(hasText("Liked songs") and !hasClickAction())
                screenshot("02b-liked-songs-root")
                pressBack()
                waitFor(hasText("Recently played"))
                assertEquals(0, fixture.emptyGlobalBrowseCalls.get())

                // The actual bottom-tab tap itself must focus the empty root and open the IME.
                // Do not tap the field afterward: that would conceal failed activation delivery.
                activateSearchAndAssertEmpty(fixture, "04-search-keyboard")
                dismissSearchImeAndAssertEmptyRoot()
                assertSearchInThumbZone()
                compose.onAllNodes(hasText("Start typing to search.", substring = true)).assertCountEquals(0)
                assertCompactSearchFieldTargets()
                assertCompactNavigationTargets()
                screenshot("03-search-empty")
                captureNarrowNavigation("03a-narrow-navigation")
                // Same-empty-query reselect still needs a fresh one-shot focus/IME request.
                activateSearchAndAssertEmpty(fixture, "04b-search-reselected-keyboard")
                dismissSearchImeAndAssertEmptyRoot()
                assertEquals(0, fixture.searchCalls.size)
                screenshot("04c-search-back-restored")
                val heldSearch = fixture.holdSearchResponses()
                try {
                    search("Orbit")
                    waitFor(hasContentDescription("Searching saved music"))
                    // Prove all three old grouped reads entered this exact fixture hold before
                    // resetting; UI loading alone can appear before any transport request starts.
                    compose.waitUntil(15_000) { heldSearch.enteredRequests == 3 }
                    activateSearchAndAssertEmpty(fixture, "04d-held-results-reset-keyboard")
                } finally { fixture.releaseSearchResponses() }
                // Observe fixture dispatch completion, not an arbitrary sleep or a claim that a
                // cancelled client accepted the responses. Non-cancellable arrivals have state tests.
                compose.waitUntil(15_000) { heldSearch.preparedResponses == heldSearch.enteredRequests }
                compose.waitForIdle()
                assertEmptySearchRoot()
                dismissSearchImeAndAssertEmptyRoot()
                compose.onNodeWithTag("search-query").performClick()
                waitForIme(true)
                assertListeningChrome(editing = true)
                fixture.holdSearchResponses()
                try {
                    search("Orbit")
                    waitFor(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                    screenshot("04a-search-loading")
                } finally { fixture.releaseSearchResponses() }
                scrollToCatalogItem(UiFixtureServer.ALBUM_NAME)
                waitForIme(false)
                assertSearchInThumbZone()
                assertGroupedSearchBounded(fixture, "Orbit")
                waitUntilAbsent(hasContentDescription("Searching saved music"))
                assertSearchGroupOrdering()
                screenshot("05-search-results")
                assertCompactSearchRows()
                screenshot("05b-search-songs")
                assertFourArtistCreditNavigation(fixture)
                scrollToText("See more songs")
                compose.onNodeWithText("See more songs").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
                compose.waitUntil(15_000) { fixture.searchCalls.any { it.query == "Orbit" && it.kind == "tracks" && it.offset == 10 } }
                scrollToCatalogItem(UiFixtureServer.TRACK_NAMES.last()).assertIsDisplayed()
                screenshot("05bb-search-see-more")
                assertSearchEditingContinuity(fixture, "05bd", playing = false)

                search("unmatched-synthetic-query")
                waitFor(hasText("No music matches this search."))
                screenshot("05c-search-no-results")
                search(UiFixtureServer.ERROR_QUERY)
                waitFor(hasText("Retry", substring = true))
                screenshot("05d-search-error")
                search("Orbit")
                scrollToCatalogItem(UiFixtureServer.ARTIST_NAME).performClick()
                waitFor(hasContentDescription("More actions for ${UiFixtureServer.ARTIST_NAME}"))
                screenshot("05e-artist-details")
                pressBack()
                waitFor(hasTestTag("search-query"))
                val albumResult = scrollToCatalogItem(UiFixtureServer.ALBUM_NAME)
                val retainedAlbumTop = albumResult.fetchSemanticsNode().boundsInRoot.top
                albumResult.performClick()
                waitFor(hasContentDescription("More actions for ${UiFixtureServer.ALBUM_NAME}"))
                screenshot("06-album-details")
                scrollToCatalogItem(UiFixtureServer.TRACK_NAMES.first()).performClick()
                waitFor(hasText("Like"))
                screenshot("06b-track-details")
                compose.onNodeWithText("Like").performScrollTo().performClick()
                waitFor(hasText("Unlike"))
                pressBack() // Song detail -> album detail, never a visible in-app Back control.
                waitFor(hasContentDescription("More actions for ${UiFixtureServer.ALBUM_NAME}"))
                pressBack() // Album detail -> retained Search results.
                waitFor(hasTestTag("search-query"))
                compose.onNodeWithTag("search-query").assertTextContains("Orbit")
                assertRetainedSearchPosition(retainedAlbumTop)
                compose.onNodeWithText("Home").performClick()
                waitFor(hasText("Recently played"))
                // Ordinary return is deliberately different from an explicit bottom Search tap.
                compose.runOnIdle {
                    ViewModelProvider(compose.activity)[LibraryViewModel::class.java].switchTab(Tab.SEARCH)
                }
                waitFor(hasTestTag("search-query"))
                compose.onNodeWithTag("search-query").assertTextContains("Orbit")
                assertListeningChrome(editing = false)
                waitForIme(false)
                assertRetainedSearchPosition(retainedAlbumTop)
                screenshot("06c-search-retained-after-back-and-programmatic-return")
                assertNoAutoplayOnBrowse(fixture)

                // A deliberate Search tap must discard the catalog's inner detail stack,
                // not merely restore the Search tab's old detail or retain its accepted query.
                scrollToCatalogItem(UiFixtureServer.ALBUM_NAME).performClick()
                waitFor(hasContentDescription("More actions for ${UiFixtureServer.ALBUM_NAME}"))
                activateSearchAndAssertEmpty(fixture, "06d-detail-to-empty-search-keyboard")
                dismissSearchImeAndAssertEmptyRoot()
                // Reset a populated, paged and scrolled root on actual nav reselect as well.
                search("Orbit")
                scrollToText("See more songs")
                compose.onNodeWithText("See more songs").performClick()
                scrollToCatalogItem(UiFixtureServer.TRACK_NAMES.last()).assertIsDisplayed()
                activateSearchAndAssertEmpty(fixture, "06e-paged-root-reselect-keyboard")
                dismissSearchImeAndAssertEmptyRoot()
                search("Orbit")
                assertSearchStartsAtFirstResult()
                // Search songs also use the catalog's inner stack. Home recents instead open
                // the actual host Page.Detail; explicitly cover Search activation from there.
                compose.onNodeWithText("Home").performClick()
                waitFor(hasText("Recently played"))
                scrollToCatalogItem(UiFixtureServer.TRACK_NAMES.first()).performClick()
                waitFor(hasText("Unlike"))
                compose.runOnIdle {
                    assertTrue("Home recent must open the host detail stack",
                        ViewModelProvider(compose.activity)[LibraryViewModel::class.java].state.value.page is Page.Detail)
                }
                activateSearchAndAssertEmpty(fixture, "06f-host-detail-to-empty-search-keyboard")
                dismissSearchImeAndAssertEmptyRoot()
                search("Orbit")
                assertSearchStartsAtFirstResult()
                compose.onNodeWithContentDescription("Clear search").performClick()
                compose.waitUntil(10_000) { queryText().isEmpty() }
                compose.onAllNodes(hasText("Start typing to search.", substring = true)).assertCountEquals(0)
                if (isImeVisible()) pressBack()
                waitForIme(false)
                assertListeningChrome(editing = false)
                assertEmptySearchRoot()
                assertEquals(0, fixture.emptyGlobalBrowseCalls.get())
                screenshot("03b-search-cleared")

                compose.onNodeWithText("Playlists").performClick()
                waitFor(hasText(UiFixtureServer.MANUAL_NAME))
                waitFor(hasText(UiFixtureServer.DERIVED_NAME))
                screenshot("07-playlists")
                compose.onNodeWithText(UiFixtureServer.DERIVED_NAME).performClick()
                waitFor(hasText("Complete result evaluated.", substring = true))
                screenshot("07b-rules-playlist")
                pressBack()
                waitFor(hasText(UiFixtureServer.MANUAL_NAME))
                compose.onNodeWithText(UiFixtureServer.MANUAL_NAME).performClick()
                waitFor(hasText("Reorder all occurrences"))
                screenshot("08-playlist-details")
                compose.onNodeWithText("Edit details").performScrollTo().performClick()
                waitFor(hasText("Name"))
                compose.onNodeWithText("Name").performClick().performTextReplacement("Night walk · edited")
                waitForIme(true)
                pressBack() // IME first: editor dialog must still be present.
                waitForIme(false)
                compose.onNodeWithText("Save").assertExists().performClick()
                waitUntilAbsent(hasText("Edit playlist"))
                waitFor(hasText("Night walk · edited"))
                compose.waitUntil(15_000) { fixture.playlistWrites.get() == 1 }
                screenshot("08b-playlist-edited")

                val devices = hasContentDescription("Playback devices · read-only")
                if (exists(devices)) {
                    compose.onNode(devices).performClick()
                    waitFor(hasText("Studio speaker"))
                    // Commands may be hidden rather than disabled. Never require unsupported actions.
                    val deviceCommand = hasText("Play on ", substring = true) or hasText("Rename") or
                        hasText("Continue playback here") or hasText("Save")
                    compose.onAllNodes(deviceCommand and hasClickAction())
                        .fetchSemanticsNodes().forEach { node ->
                            assertTrue("Device picker must be read-only: ${node.config}",
                                node.config.contains(SemanticsProperties.Disabled))
                        }
                    screenshot("08c-playback-devices-read-only")
                    pressBack()
                    waitFor(hasText("Night walk · edited"))
                }
                assertNoAutoplayOnBrowse(fixture)
                val playlistPlay = hasText("Play") and hasClickAction()
                compose.waitUntil(15_000) {
                    compose.onAllNodes(playlistPlay).fetchSemanticsNodes().firstOrNull()
                        ?.config?.contains(SemanticsProperties.Disabled) == false
                }
                compose.onAllNodes(playlistPlay).onFirst().assertIsEnabled().performClick()
                compose.waitUntil(20_000) { fixture.audioRequests.get() > 0 && exists(expandPlayer) }
                waitFor(playingButton())
                compose.onNode(expandPlayer).performClick()
                waitFor(playerSheet)
                assertPlayerPresentation()
                assertPlayerMenusPreservePlayback(fixture)
                screenshot("09-now-playing")
                compose.onNode(sheetTransport("Pause")).assertIsDisplayed()
                screenshot("09b-now-playing-controls")
                compose.onNode(sheetTransport("Pause")).performClick()
                waitFor(pausedButton() and inPlayerSheet())
                compose.onNode(sheetTransport("Play")).assertIsDisplayed().performClick()
                waitFor(playingButton() and inPlayerSheet())
                // An intentional seek gives reset detection a substantial baseline while the
                // fixture's WAV and metadata leave ten minutes for the rest of the journey.
                compose.onNode(playerPosition).performSemanticsAction(SemanticsActions.SetProgress) { action ->
                    assertTrue(action(30_000f))
                }
                compose.waitUntil(10_000) { positionMs() in 29_000f..33_000f }
                openQueueForInspection()
                waitFor(hasText("Up next · 5"))
                screenshot("10-queue")
                val originalQueue = captureQueueSnapshot()
                assertEquals(UiFixtureServer.TRACK_NAMES.subList(1, 6), originalQueue.map { it.title })
                assertOverflowingQueueBottomStable(originalQueue, fixture, "10a-queue-released-bottom")
                compose.onNodeWithContentDescription("Queue actions for ${UiFixtureServer.TRACK_NAMES[1]}").performScrollTo().performClick()
                compose.onNodeWithText("Remove from queue").performClick()
                waitUntilAbsent(hasText("Remove from queue") and hasClickAction())
                waitFor(hasText("Up next · 4"))
                if (exists(hasTestTag("now-playing-queue-view"))) {
                    compose.onNodeWithContentDescription("Upcoming queue actions").performClick()
                }
                compose.onNodeWithText("Undo").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                waitFor(hasText("Up next · 5"))
                waitUntilAbsent(hasText("Undo") and hasClickAction())
                assertQueueSnapshot(originalQueue)
                val beforeCollapse = capturePlaying(fixture)
                collapsePlayer() // Queue disclosure first if open, then player; playlist stays beneath it.
                waitUntilAbsent(playerSheet)
                assertPlayingTransition(beforeCollapse, fixture, "native Back collapse")
                compose.onNode(expandPlayer).assertExists().assertTextContains(UiFixtureServer.TRACK_NAMES.first())
                waitFor(playingButton())
                compose.onNodeWithText("Night walk · edited").assertExists()
                screenshot("10b-player-collapsed")
                val density = compose.activity.resources.displayMetrics.density
                val beforeGestureExpand = capturePlaying(fixture)
                compose.onNode(expandPlayer).performTouchInput {
                    swipe(center, center.copy(y = center.y - 100f * density), durationMillis = 350)
                }
                waitFor(playerSheet)
                assertPlayingTransition(beforeGestureExpand, fixture, "gesture expansion")
                assertPlayerPresentation()
                compose.onNodeWithText("Up next · 5").assertExists()
                assertQueueSnapshot(originalQueue)
                // Collapsing a paused player must not turn pause into resume either.
                compose.onNode(sheetTransport("Pause")).assertIsDisplayed().performClick()
                waitFor(pausedButton() and inPlayerSheet())
                val beforePausedCollapse = readPlaybackWitness(fixture)
                collapsePlayer()
                waitUntilAbsent(playerSheet)
                assertPausedTransition(beforePausedCollapse, fixture)
                val beforePausedExpand = readPlaybackWitness(fixture)
                compose.onNode(expandPlayer).performClick()
                waitFor(playerSheet)
                assertPausedTransition(beforePausedExpand, fixture)
                assertQueueSnapshot(originalQueue)
                compose.onNode(sheetTransport("Play")).assertIsDisplayed().performClick()
                waitFor(playingButton() and inPlayerSheet())
                val beforeResumedCollapse = capturePlaying(fixture)
                collapsePlayer()
                waitUntilAbsent(playerSheet)
                assertPlayingTransition(beforeResumedCollapse, fixture, "resumed player collapse")
                val beforePlaylistBack = capturePlaying(fixture)
                pressBack() // With the sheet gone, native Back now pops the playlist detail.
                waitFor(hasText(UiFixtureServer.DERIVED_NAME))
                assertPlayingTransition(beforePlaylistBack, fixture, "playlist Back navigation")

                // Editing Search must not affect an already audible occurrence or its queue.
                val beforeSearchTab = capturePlaying(fixture)
                activateSearchAndAssertEmpty(fixture, "10bb-playing-search-activation-keyboard")
                assertPlayingTransition(beforeSearchTab, fixture, "explicit Search activation")
                dismissSearchImeAndAssertEmptyRoot()
                assertQueuePreservedFromMini(originalQueue, fixture)
                val beforeQuerySubmit = capturePlaying(fixture)
                search("Orbit")
                scrollToText("See more songs")
                assertPlayingTransition(beforeQuerySubmit, fixture, "query submission")
                compose.onNodeWithText("See more songs").performClick()
                scrollToCatalogItem(UiFixtureServer.TRACK_NAMES.last())
                val preparationsBeforeSearchEditing = fixture.playbackPreparations.get()
                assertQueuePreservedFromMini(originalQueue, fixture)
                assertSearchEditingContinuity(fixture, "10c", playing = true, expectedQueue = originalQueue)
                assertEquals("Search focus/IME must not prepare another queue", preparationsBeforeSearchEditing, fixture.playbackPreparations.get())

                val beforePlayingReselect = capturePlaying(fixture)
                activateSearchAndAssertEmpty(fixture, "10cc-playing-paged-root-reselect-keyboard")
                assertPlayingTransition(beforePlayingReselect, fixture, "playing Search reselect resets only browsing")
                assertEquals("Search reset must preserve exact upcoming occurrences", originalQueue, upcomingOccurrences())
                dismissSearchImeAndAssertEmptyRoot()
                search("Orbit")
                assertSearchStartsAtFirstResult()

                // A real narrow window, not a cropped screenshot or altered Compose density.
                // Keep this viewport for font2 so combined narrow/large-font regressions surface.
                sizeChanged = true
                val narrowWidth = (320f * compose.activity.resources.displayMetrics.density).roundToInt()
                val beforeNarrowWindow = capturePlaying(fixture)
                shell("wm size ${narrowWidth}x${baselineDisplaySize.substringAfter('x')}")
                compose.waitUntil(20_000) { compose.activity.resources.displayMetrics.widthPixels == narrowWidth }
                waitFor(hasTestTag("search-query"))
                assertPlayingTransition(beforeNarrowWindow, fixture, "narrow-window configuration change")
                assertSearchInThumbZone()
                screenshot("10d-narrow-search-closed")
                compose.onNodeWithTag("search-query").performClick()
                waitForIme(true)
                assertSearchInThumbZone(imeVisible = true)
                screenshot("10e-narrow-search-keyboard")
                pressBack()
                waitForIme(false)
                assertListeningChrome(editing = false)
                val beforeNarrowRoots = capturePlaying(fixture)
                compose.onNodeWithText("Home").performClick()
                waitFor(hasText("Recently played"))
                screenshot("10f-narrow-home")
                compose.onNodeWithText("Playlists").performClick()
                waitFor(hasText(UiFixtureServer.DERIVED_NAME))
                screenshot("10g-narrow-playlists")
                activateSearchAndAssertEmpty(fixture, "10h-narrow-reselect-keyboard")
                dismissSearchImeAndAssertEmptyRoot()
                search("Orbit")
                assertSearchStartsAtFirstResult()
                assertPlayingTransition(beforeNarrowRoots, fixture, "narrow root navigation and fresh Search")
                assertEquals(originalQueue, upcomingOccurrences())
                assertQueuePreservedFromMini(originalQueue, fixture)
                compose.onNode(expandPlayer).performClick()
                waitFor(playerSheet)
                assertPlayerPresentation()
                screenshot("10h-narrow-now-playing")
                collapsePlayer()
                waitUntilAbsent(playerSheet)

                // Isolated AVD only. Restore both settings even if a font2 assertion fails.
                fontChanged = true
                val beforeFontScale = capturePlaying(fixture)
                shell("settings put system font_scale 2.0")
                waitForFont(2f)
                assertPlayingTransition(beforeFontScale, fixture, "font-scale configuration change")
                val beforeRecreation = capturePlaying(fixture)
                compose.activityRule.scenario.recreate()
                waitFor(hasText("Home"))
                assertPlayingTransition(beforeRecreation, fixture, "Activity recreation")
                assertQueuePreservedFromMini(originalQueue, fixture)
                val beforeHomeTab = capturePlaying(fixture)
                compose.onNodeWithText("Home").performClick()
                waitFor(hasText("Recently played"))
                assertPlayingTransition(beforeHomeTab, fixture, "large-font Home navigation")
                screenshot("11-large-font-home")
                val beforeLargePlaylistsTab = capturePlaying(fixture)
                compose.onNodeWithText("Playlists").performClick()
                waitFor(hasText(UiFixtureServer.DERIVED_NAME))
                assertPlayingTransition(beforeLargePlaylistsTab, fixture, "font2 Playlists navigation")
                screenshot("11b-font2-narrow-playlists")
                val beforeLargeSearchTab = capturePlaying(fixture)
                activateSearchAndAssertEmpty(fixture, "12a-font2-narrow-empty-search-keyboard")
                assertPlayingTransition(beforeLargeSearchTab, fixture, "font2 Search activation")
                dismissSearchImeAndAssertEmptyRoot()
                assertSearchInThumbZone()
                screenshot("12b-font2-narrow-empty-search-closed")
                search("Orbit")
                assertSearchStartsAtFirstResult()
                assertCompactSearchRows()
                assertCompactSearchFieldTargets()
                assertCompactNavigationTargets()
                screenshot("12-large-font-search")
                captureNarrowNavigation("12a-narrow-font2-navigation")
                compose.onNodeWithTag("search-query").performClick()
                waitForIme(true)
                assertSearchInThumbZone(imeVisible = true)
                assertCompactSearchFieldTargets()
                screenshot("12c-font2-narrow-results-keyboard")
                pressBack()
                waitForIme(false)
                assertListeningChrome(editing = false)
                waitFor(expandPlayer)
                val beforeLargePlayerExpand = capturePlaying(fixture)
                compose.onNode(expandPlayer).performClick()
                waitFor(playerSheet)
                assertPlayingTransition(beforeLargePlayerExpand, fixture, "large-font player expansion")
                assertEquals("Font recreation must not prepare another queue", preparationsBeforeSearchEditing, fixture.playbackPreparations.get())
                assertPlayerPresentation()
                screenshot("13-large-font-now-playing")
                openQueueForInspection()
                compose.onNodeWithText("Up next · 5").assertExists()
                assertQueueSnapshot(originalQueue)
                assertOverflowingQueueBottomStable(originalQueue, fixture, "14a-large-font-released-bottom")
                screenshot("14-large-font-queue")
                // Feature-owned physical gesture/More/queue coverage. All earlier continuity
                // checks are complete before deliberately changing the listening selection.
                assertContextualListPlayback(fixture)
                shell("settings put system font_scale 2.0")
                waitForFont(2.0f)
                assertContextualListFont2()
                assertEquals("No query-free catalog reads are allowed", 0, fixture.emptyGlobalBrowseCalls.get())
                assertEquals("Real auth cookie and CSRF must reach the fixture", 0, fixture.authenticationFailures.get())
                assertTrue("Unexpected fixture routes: ${fixture.unexpectedRoutes.joinToString()}", fixture.unexpectedRoutes.isEmpty())
                File(evidence, "fixture-summary.txt").writeText("tracks=16\nalbums=3\nartists=1\ncreditArtistAliases=3\nmanualPlaylists=1\nderivedPlaylists=1\n" +
                    "audioRequests=${fixture.audioRequests.get()}\nplaybackPreparations=${fixture.playbackPreparations.get()}\nhistoryWrites=${fixture.historyWrites.get()}\nplaylistWrites=${fixture.playlistWrites.get()}\n" +
                    "emptyGlobalBrowseCalls=${fixture.emptyGlobalBrowseCalls.get()}\nauthenticationFailures=${fixture.authenticationFailures.get()}\nunexpectedRoutes=${fixture.unexpectedRoutes.size}\n")
            } catch (error: Throwable) {
                journeyFailure = error
                runCatching { screenshot("99-failure") }
                throw error
            } finally {
                // Attempt both restorations independently, including after a failed assertion.
                var cleanupFailure: Throwable? = null
                if (fontChanged) runCatching {
                    if (originalFontSetting == "null") shell("settings delete system font_scale")
                    else shell("settings put system font_scale $originalFontSetting")
                    waitForFont(originalFontScale)
                }.exceptionOrNull()?.let { cleanupFailure = it }
                if (sizeChanged) runCatching {
                    shell("wm size ${originalSizeOverride ?: "reset"}")
                    compose.waitUntil(20_000) { compose.activity.resources.displayMetrics.widthPixels == baselineWidth }
                }.exceptionOrNull()?.let { error ->
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = error else first.addSuppressed(error)
                }
                cleanupFailure?.let { error ->
                    val primary = journeyFailure
                    if (primary != null) primary.addSuppressed(error) else throw error
                }
            }
        }
    }

    private fun assertContextualListPlayback(fixture: UiFixtureServer) {
        collapsePlayer()
        waitUntilAbsent(playerSheet)
        compose.onNodeWithText("Search").performClick()
        waitFor(hasTestTag("search-query"))
        search("Orbit")
        scrollToCatalogItem(UiFixtureServer.TRACK_NAMES[8])
        val before = readPlaybackState()
        val witness = capturePlaying(fixture)
        val initialFuture = before.upcoming.map { it.occurrenceId }
        val additions = UiFixtureServer.TRACK_NAMES.subList(6, 9)
        additions.take(2).forEachIndexed { index, name ->
            swipeCatalogRowRight(name)
            compose.waitUntil(15_000) { readPlaybackState().upcoming.size == initialFuture.size + index + 1 }
            assertPlayingTransition(witness, fixture, "contextual FIFO swipe $index")
        }
        val more = "More actions for ${additions.last()}"
        scrollToCatalogItem(additions.last())
        compose.onNodeWithContentDescription(more).performClick()
        waitFor(hasTestTag("music-actions"))
        screenshot("15-list-more")
        compose.onNodeWithText("Add to queue").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        waitUntilAbsent(hasTestTag("music-actions"))
        compose.waitUntil(15_000) { readPlaybackState().upcoming.size == initialFuture.size + 3 }
        val added = readPlaybackState()
        assertEquals(additions, added.upcoming.take(3).map { it.item.name })
        assertEquals("Manual FIFO must precede the untouched context tail", initialFuture,
            added.upcoming.drop(3).map { it.occurrenceId })
        assertPlayingTransition(witness, fixture, "explicit Add and FIFO")
        screenshot("15a-list-after-swipes")

        compose.onNode(expandPlayer).performClick()
        waitFor(playerSheet)
        openQueueForInspection()
        val queue = compose.onNodeWithTag("now-playing-queue")
        val selected = added.upcoming.last()
        val queueRow = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and
            hasAnyDescendant(hasText(selected.item.name)) and
            hasAnyDescendant(hasContentDescription("Queue actions for ${selected.item.name}"))
        queue.performScrollToNode(queueRow)
        compose.onNode(queueRow).performTouchInput {
            swipe(Offset(width * .4f, center.y), Offset(width - 60.dp.toPx(), center.y), 350)
        }
        val expectedPromotion = added.upcoming.take(3).map { it.occurrenceId } + selected.occurrenceId +
            added.upcoming.drop(3).filterNot { it.occurrenceId == selected.occurrenceId }.map { it.occurrenceId }
        compose.waitUntil(15_000) { readPlaybackState().upcoming.map { it.occurrenceId } == expectedPromotion }
        assertEquals("Upcoming promotion must not duplicate an occurrence", added.upcoming.size,
            readPlaybackState().upcoming.map { it.occurrenceId }.toSet().size)
        val handle = hasContentDescription("Reorder ${additions[1]}")
        queue.performScrollToNode(handle)
        val beforeDrag = readPlaybackState().upcoming.map { it.occurrenceId }
        compose.onNode(handle).performTouchInput {
            swipe(center, center.copy(y = center.y - 110.dp.toPx()), 500)
        }
        compose.waitUntil(15_000) { readPlaybackState().upcoming.map { it.occurrenceId } != beforeDrag }
        assertEquals(beforeDrag.toSet(), readPlaybackState().upcoming.map { it.occurrenceId }.toSet())
        assertPlayingTransition(witness, fixture, "upcoming promotion and handle drag")
        screenshot("16-queue-promoted-and-dragged")
        collapsePlayer()
        waitUntilAbsent(playerSheet)

        // Explicit Add remains nonstarting when paused; the subsequent physical-right
        // selection is deliberate Play now, not a resume of the previously audible song.
        compose.onNode(playingButton()).performClick()
        waitFor(pausedButton())
        val paused = readPlaybackState()
        scrollToCatalogItem(additions.last())
        compose.onNodeWithContentDescription(more).performClick()
        waitFor(hasTestTag("music-actions"))
        compose.onNodeWithText("Add to queue").performScrollTo().performClick()
        waitUntilAbsent(hasTestTag("music-actions"))
        compose.waitUntil(15_000) { readPlaybackState().upcoming.size == paused.upcoming.size + 1 }
        assertFalse(readPlaybackState().playWhenReady)
        assertEquals(paused.currentEntryId, readPlaybackState().currentEntryId)
        swipeCatalogRowRight(additions.first())
        compose.waitUntil(20_000) {
            val state = readPlaybackState()
            state.item?.name == additions.first() && state.isPlaying
        }
        screenshot("17-paused-swipe-starts-selected")
    }

    private fun assertContextualListFont2() {
        val before = readPlaybackState()
        val name = UiFixtureServer.TRACK_NAMES[7]
        assertCompactSearchRows()
        screenshot("18-font2-list")
        scrollToCatalogItem(name)
        compose.onNodeWithContentDescription("More actions for $name").performClick()
        waitFor(hasTestTag("music-actions"))
        compose.onNodeWithText("Add to queue").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        screenshot("18a-font2-list-more")
        pressBack()
        waitUntilAbsent(hasTestTag("music-actions"))
        compose.onNode(expandPlayer).performClick()
        waitFor(playerSheet)
        screenshot("19-font2-now-playing")
        openQueueForInspection()
        screenshot("19a-font2-queue")
        val after = readPlaybackState()
        assertEquals(before.currentEntryId, after.currentEntryId)
        assertEquals(before.upcoming.map { it.occurrenceId }, after.upcoming.map { it.occurrenceId })
        assertTrue("Font2 presentation/More/Back must not stop playback", after.playWhenReady)
    }

    private fun swipeCatalogRowRight(name: String) {
        scrollToCatalogItem(name).performTouchInput {
            // Avoid the Android Back edge and the independent trailing 48dp More target.
            swipe(Offset(width * .3f, center.y), Offset(width - 60.dp.toPx(), center.y), 350)
        }
    }

    private fun pressBack() {
        VirtualAvdOnly.requireVerified()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    private fun collapsePlayer() {
        if (exists(hasTestTag("now-playing-queue-view"))) {
            pressBack()
            waitUntilAbsent(hasTestTag("now-playing-queue-view"))
            compose.onNode(playerSheet).assertIsDisplayed()
        }
        pressBack()
    }
    private fun search(query: String) {
        val field = compose.onNodeWithTag("search-query")
        field.performTextReplacement(query)
        field.performImeAction()
    }
    private fun scrollToCatalogItem(name: String): SemanticsNodeInteraction {
        // Artist/album credits repeat entity names. Select the actual music row, not a credit.
        val row = catalogItem(name)
        scrollTo(row)
        return compose.onNode(row)
    }
    private fun catalogItem(name: String) = hasText(name) and hasAnyDescendant(hasContentDescription("More actions for $name"))
    private fun assertRetainedSearchPosition(expectedTop: Float, name: String = UiFixtureServer.ALBUM_NAME) {
        // Do not scroll here: doing so would hide a Back/tab restoration regression.
        compose.waitUntil(10_000) {
            val row = compose.onAllNodes(catalogItem(name)).fetchSemanticsNodes().singleOrNull()
            row != null && kotlin.math.abs(row.boundsInRoot.top - expectedTop) <= 2f
        }
    }
    private fun queryText() = compose.onNodeWithTag("search-query").fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text

    /** Drive the real navigation item, including reselect; never repair focus with a field tap. */
    private fun activateSearchAndAssertEmpty(fixture: UiFixtureServer, evidenceName: String) {
        val navigationSearch = hasText("Search") and hasAnyAncestor(hasTestTag("primary-navigation"))
        waitFor(navigationSearch)
        compose.onNode(navigationSearch).assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        waitFor(hasTestTag("search-query"))
        compose.waitUntil(10_000) { queryText().isEmpty() }
        waitForIme(true) // The isolated software-IME AVD must provide actual keyboard evidence.
        assertSearchInThumbZone(imeVisible = true)
        assertCompactSearchFieldTargets()
        assertEmptySearchRoot()
        assertEquals("Search activation must never issue match-all requests", 0, fixture.emptyGlobalBrowseCalls.get())
        screenshot(evidenceName)
    }

    private fun assertEmptySearchRoot() {
        compose.onNodeWithTag("search-query").assertIsDisplayed().assert(hasSetTextAction())
        assertEquals("Activation must clear accepted text and the visible local draft", "", queryText())
        compose.onAllNodes(hasContentDescription("More actions for ", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("See more ", substring = true)).assertCountEquals(0)
        // The empty field identifies Search; redundant empty-query prose stays absent.
        compose.onAllNodes(hasText("Start typing to search.", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("No music matches this search.")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Searching saved music")).assertCountEquals(0)
    }

    private fun dismissSearchImeAndAssertEmptyRoot() {
        pressBack()
        waitForIme(false)
        assertListeningChrome(editing = false)
        // This single Back dismisses editing only. It must not pop back to a saved detail/Home.
        assertEmptySearchRoot()
    }

    private fun assertSearchStartsAtFirstResult() {
        waitUntilAbsent(hasContentDescription("Searching saved music"))
        waitFor(catalogItem(UiFixtureServer.ARTIST_NAME))
        val list = compose.onAllNodes(hasScrollToIndexAction()).onFirst()
        val row = compose.onNode(catalogItem(UiFixtureServer.ARTIST_NAME)).assertIsDisplayed()
        val paddingAllowance = 16f * compose.activity.resources.displayMetrics.density
        assertTrue("Fresh Search must reset root scroll without a corrective test scroll",
            kotlin.math.abs(row.fetchSemanticsNode().boundsInRoot.top - list.fetchSemanticsNode().boundsInRoot.top) <= paddingAllowance)
    }

    private fun assertCompactSearchFieldTargets() {
        compose.onNodeWithTag("search-query").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onAllNodes(hasContentDescription("Clear search")).fetchSemanticsNodes().singleOrNull()?.let {
            compose.onNodeWithContentDescription("Clear search").assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        // The Search-only slice must work with the baseline Material navigation bar.
        // Font2 is allowed to grow naturally instead of being clipped to the default budget.
        if (compose.activity.resources.configuration.fontScale <= 1.01f) {
            val dock = compose.onNodeWithTag("search-dock").getUnclippedBoundsInRoot()
            assertTrue("Default Search dock must use the compact approximately 56dp budget",
                (dock.bottom - dock.top).value in 48f..60f)
        }
    }

    private fun captureNarrowNavigation(name: String) {
        // This test owns only the isolated AVD. Restore any prior override even on failure.
        val originalSize = shell("wm size").lineSequence()
            .firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val originalWidthDp = compose.activity.resources.configuration.screenWidthDp
        val metrics = compose.activity.resources.displayMetrics
        val widthPixels = (280f * metrics.density).toInt()
        val heightPixels = metrics.heightPixels
        try {
            shell("wm size ${widthPixels}x$heightPixels")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.screenWidthDp <= 282 }
            waitFor(hasTestTag("primary-navigation"))
            assertCompactNavigationTargets()
            assertCompactSearchFieldTargets()
            screenshot(name)
            compose.onNodeWithTag("search-query").performClick()
            waitForIme(true)
            assertSearchInThumbZone(imeVisible = true)
            assertCompactSearchFieldTargets()
            screenshot("$name-keyboard")
            pressBack()
            waitForIme(false)
            assertListeningChrome(editing = false)
        } finally {
            shell("wm size ${originalSize ?: "reset"}")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.screenWidthDp == originalWidthDp }
        }
    }

    private fun assertCompactNavigationTargets() {
        assertListeningChrome(editing = false)
        val targets = listOf("Home", "Search", "Playlists").map { label ->
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("primary-navigation")))
                .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                .getUnclippedBoundsInRoot()
        }
        targets.zipWithNext().forEach { (left, right) ->
            assertTrue("Navigation destinations must not overlap", left.right <= right.left)
        }
        // Actual targets exclude the outer bar's legitimate system bottom inset.
        // Large labels may grow instead of being clipped to the default budget.
        if (compose.activity.resources.configuration.fontScale <= 1.01f) {
            targets.forEach { bounds ->
                assertTrue("Default navigation content must be compact, not clipped 80dp Material content",
                    (bounds.bottom - bounds.top).value in 48f..60f)
            }
        }
    }

    private fun assertListeningChrome(editing: Boolean) {
        listOf("primary-navigation", "app-actions").forEach { tag ->
            if (editing) {
                waitUntilAbsent(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertDoesNotExist()
            } else {
                waitFor(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }
        }
        if (editing) compose.onNodeWithTag("search-query").assertIsFocused()
        else compose.onNodeWithTag("search-query").assertIsNotFocused()
    }

    private fun assertSearchGroupOrdering() {
        // With 1 artist + 3 albums, the first song is item four. No type/count rows belong
        // between those groups. Check actual native list placement, not network request order.
        val list = compose.onAllNodes(hasScrollToIndexAction()).onFirst()
        listOf(Triple(0, UiFixtureServer.ARTIST_NAME, "Artist"),
            Triple(1, UiFixtureServer.ALBUM_NAME, "Album"),
            Triple(4, UiFixtureServer.TRACK_NAMES.first(), "Song")).forEach { (index, name, type) ->
            list.performScrollToIndex(index)
            val row = compose.onNode(catalogItem(name)).assertIsDisplayed().assert(hasContentDescription(type))
            val top = row.fetchSemanticsNode().boundsInRoot.top
            val listTop = list.fetchSemanticsNode().boundsInRoot.top
            val paddingAllowance = 16f * compose.activity.resources.displayMetrics.density
            assertTrue("Expected $type at item $index, not a type/count header", kotlin.math.abs(top - listTop) <= paddingAllowance)
            compose.onAllNodes(hasText("Artists") or hasText("Albums") or hasText("Songs") or
                hasText("1") or hasText("3") or hasText("16")).assertCountEquals(0)
        }
        list.performScrollToIndex(0)
    }

    private fun assertCompactSearchRows() {
        val heights = listOf(UiFixtureServer.ARTIST_NAME, UiFixtureServer.ALBUM_NAME,
            "Orbit Afterglow", UiFixtureServer.TRACK_NAMES[0], UiFixtureServer.TRACK_NAMES[1]).map { name ->
            val row = scrollToCatalogItem(name)
            val bounds = row.getUnclippedBoundsInRoot()
            val height = (bounds.bottom - bounds.top).value
            // A's compact row retains only More beside its identity destination. Play now
            // remains intentional in More/custom accessibility actions, never an inline slot.
            assertEquals("Unexpected inline metadata/actions for $name", 1,
                compose.onAllNodes(hasClickAction() and hasAnyAncestor(catalogItem(name))).fetchSemanticsNodes().size)
            compose.onNodeWithContentDescription("More actions for $name")
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            height
        }
        assertTrue("One/four-artist rows must share pitch at the same font scale: $heights",
            heights.maxOrNull()!! - heights.minOrNull()!! <= 1f)
        compose.onNode(catalogItem(UiFixtureServer.TRACK_NAMES[1])).assert(
            hasText(UiFixtureServer.CREDIT_ARTISTS.joinToString(", ") { it.second }, substring = true))
    }

    private fun assertFourArtistCreditNavigation(fixture: UiFixtureServer) {
        val name = UiFixtureServer.TRACK_NAMES[1]
        val top = scrollToCatalogItem(name).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithContentDescription("More actions for $name").performClick()
        waitFor(hasText("Open details") and hasClickAction())
        compose.onNodeWithText("Open details").assertIsDisplayed().performClick()
        waitUntilAbsent(hasText("Open details") and hasClickAction())
        waitFor(hasContentDescription("More actions for $name"))
        UiFixtureServer.CREDIT_ARTISTS.forEach { (id, artist) ->
            compose.onNode(hasText(artist) and hasClickAction()).performScrollTo().performClick()
            waitFor(hasContentDescription("More actions for $artist"))
            compose.waitUntil(15_000) { fixture.catalogDetails.any { it.kind == "artist" && it.id == id } }
            if (id == UiFixtureServer.CREDIT_ARTISTS.last().first) screenshot("05bc-four-artist-credit-details")
            pressBack()
            waitFor(hasContentDescription("More actions for $name"))
        }
        pressBack()
        waitFor(hasTestTag("search-query"))
        assertEquals("Orbit", queryText())
        assertRetainedSearchPosition(top, name)
        assertNoAutoplayOnBrowse(fixture)
    }

    private fun assertSearchEditingContinuity(
        fixture: UiFixtureServer, evidencePrefix: String, playing: Boolean, expectedQueue: List<QueueOccurrence>? = null,
    ) {
        // Sixteen loaded songs leave a real middle anchor, not the first row or a clamped end.
        waitUntilAbsent(hasContentDescription("Searching saved music"))
        val anchor = UiFixtureServer.TRACK_NAMES[4]
        val list = compose.onAllNodes(hasScrollToIndexAction()).onFirst()
        list.performScrollToIndex(8) // 1 artist + 3 albums + the fifth song.
        val anchorTop = compose.onNode(catalogItem(anchor)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val contentTop = list.fetchSemanticsNode().boundsInRoot.top
        val query = queryText()
        val requests = fixture.searchCalls.size
        val preparations = fixture.playbackPreparations.get()
        listOf(false, true).forEach { submit ->
            val action = if (submit) "submit" else "back"
            val beforeFocus = if (playing) capturePlaying(fixture) else null
            compose.onNodeWithTag("search-query").performClick()
            waitForIme(true)
            assertSearchInThumbZone(imeVisible = true)
            assertTrue("Editing must remove reserved top-toolbar space", list.fetchSemanticsNode().boundsInRoot.top < contentTop)
            if (playing) {
                compose.onNode(expandPlayer).assertIsDisplayed().assertTextContains(UiFixtureServer.TRACK_NAMES.first())
                assertPlayingTransition(checkNotNull(beforeFocus), fixture, "Search focus/IME opening")
            }
            screenshot("$evidencePrefix-search-keyboard-$action")
            // Do not replace text here: unchanged-query IME submit must preserve loaded pages
            // and scroll by itself, not quietly turn this check into a brand-new search.
            val beforeEditingEnd = if (playing) capturePlaying(fixture) else null
            if (submit) compose.onNodeWithTag("search-query").performImeAction() else pressBack()
            waitForIme(false)
            assertListeningChrome(editing = false)
            assertEquals("Ending editing must retain the query", query, queryText())
            assertRetainedSearchPosition(anchorTop, anchor)
            assertEquals("Unchanged-query $action must retain loaded search pages", requests, fixture.searchCalls.size)
            assertEquals("Search editing must not prepare playback", preparations, fixture.playbackPreparations.get())
            if (playing) {
                assertPlayingTransition(checkNotNull(beforeEditingEnd), fixture, "Search IME $action")
                assertQueuePreservedFromMini(checkNotNull(expectedQueue), fixture)
                assertRetainedSearchPosition(anchorTop, anchor)
            } else assertNoAutoplayOnBrowse(fixture)
            screenshot("$evidencePrefix-search-$action-restored")
        }
        if (playing) {
            // Expand with the actual software IME still visible. A pre-dismissed keyboard
            // would not exercise the host's new focus/activation cancellation path.
            val beforeExpansion = capturePlaying(fixture)
            compose.onNodeWithTag("search-query").performClick()
            waitForIme(true)
            assertListeningChrome(editing = true)
            screenshot("$evidencePrefix-before-player-expansion-keyboard")
            compose.onNode(expandPlayer).performClick()
            waitFor(playerSheet)
            waitForIme(false)
            assertPlayingTransition(beforeExpansion, fixture, "player expansion dismisses Search IME")
            assertEquals(checkNotNull(expectedQueue), upcomingOccurrences())
            screenshot("$evidencePrefix-player-expanded-from-keyboard")
            collapsePlayer()
            waitUntilAbsent(playerSheet)
            waitForIme(false)
            assertListeningChrome(editing = false)
            assertEquals(query, queryText())
            assertRetainedSearchPosition(anchorTop, anchor)
            assertEquals(requests, fixture.searchCalls.size)
            assertEquals(preparations, fixture.playbackPreparations.get())
            assertEquals(expectedQueue, upcomingOccurrences())
            assertPlayingTransition(beforeExpansion, fixture, "player collapse must not refocus Search")
            screenshot("$evidencePrefix-search-after-player-collapse")
        }
        // Only after both restoration checks, scroll to prove the appended page itself survived.
        scrollToCatalogItem(UiFixtureServer.TRACK_NAMES.last()).assertIsDisplayed()
        assertEquals("The appended page must survive without another fetch", requests, fixture.searchCalls.size)
    }
    private fun scrollToText(text: String) = scrollTo(hasText(text))
    private fun scrollTo(matcher: SemanticsMatcher) {
        // Lazy semantics contain only composed rows. Wait for late response bodies while searching
        // the active screen's list; don't mistake a not-yet-composed row for a missing result.
        var lastAssertion: AssertionError? = null
        try {
            compose.waitUntil(20_000) {
                try {
                    compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(matcher)
                    compose.onNode(matcher).assertIsDisplayed()
                    true
                } catch (notReady: AssertionError) {
                    lastAssertion = notReady
                    false
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Could not reach ${matcher.description} within the bounded wait", timeout)
                .apply { lastAssertion?.let(::addSuppressed) }
        }
    }
    private fun assertNoAutoplayOnBrowse(fixture: UiFixtureServer) {
        assertEquals("Browsing must not prepare a playback queue", 0, fixture.playbackPreparations.get())
        assertEquals("Browsing and liking must not request audio", 0, fixture.audioRequests.get())
        assertEquals("Browsing must not report audible history", 0, fixture.historyWrites.get())
    }
    private fun assertPlayerPresentation() {
        val viewport = compose.onNode(playerSheet).assertIsDisplayed().fetchSemanticsNode()
        assertFalse("The whole player must not scroll", viewport.config.contains(SemanticsActions.ScrollBy))
        val scrollers = compose.onAllNodes(hasScrollAction() and inPlayerSheet()).fetchSemanticsNodes()
        val queueDisclosed = exists(hasTestTag("now-playing-show-queue"))
        assertEquals("Only an intentionally visible Up next scrolls inside the player", if (queueDisclosed) 0 else 1, scrollers.size)
        if (!queueDisclosed) assertEquals("The queue owns player scrolling", "now-playing-queue", scrollers.single().config[SemanticsProperties.TestTag])
        if (exists(playerPosition)) compose.onNode(playerPosition).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        else compose.onNode(hasContentDescription("Queue playback progress") and inPlayerSheet()).assertIsDisplayed()
        val bounds = viewport.boundsInRoot
        listOf("Previous song", if (exists(playingButton() and inPlayerSheet())) "Pause" else "Play", "Next song").forEach { label ->
            val control = compose.onNode(sheetTransport(label)).assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            assertTrue("Pinned $label must fit within the safe player viewport: $control in $bounds",
                control.left >= bounds.left && control.right <= bounds.right && control.top >= bounds.top && control.bottom <= bounds.bottom)
        }
    }

    private fun assertPlayerMenusPreservePlayback(fixture: UiFixtureServer) {
        val before = capturePlaying(fixture)
        val queue = upcomingOccurrences()
        val initiallyDisclosed = exists(hasTestTag("now-playing-show-queue"))
        compose.onNodeWithContentDescription("Now Playing actions").performClick()
        compose.onNodeWithText("Add to playlist").assertExists()
        compose.onNodeWithText("Shuffle:", substring = true).assertExists()
        compose.onNodeWithText("Repeat:", substring = true).assertExists()
        pressBack() // Dropdown first; not the fullscreen dialog and not the underlying route.
        compose.onNode(playerSheet).assertIsDisplayed()
        openQueueForInspection()
        compose.onNodeWithContentDescription("Upcoming queue actions").performClick()
        compose.onNodeWithText("Clear upcoming").assertIsDisplayed().assertIsEnabled()
        pressBack()
        if (initiallyDisclosed) {
            pressBack()
            waitUntilAbsent(hasTestTag("now-playing-queue-view"))
        }
        assertPlayerPresentation()
        assertEquals("Opening/dismissing secondary actions preserves exact occurrences", queue, upcomingOccurrences())
        assertPlayingTransition(before, fixture, "player action menus and native Back")
    }

    private fun openQueueForInspection() {
        if (exists(hasTestTag("now-playing-show-queue"))) {
            compose.onNodeWithTag("now-playing-show-queue").assertIsDisplayed().performClick()
            waitFor(hasTestTag("now-playing-queue-view"))
        }
        compose.onNodeWithTag("now-playing-queue").assertIsDisplayed()
    }

    /**
     * Isolated AVD setup through the EXISTING real controls, not a fixture-total change or a
     * second player. Keep the original five occurrence IDs and current audio intact; insert enough
     * real synthetic-track occurrences in the manual FIFO tier that even a tall viewport must
     * scroll, then remove only their fresh IDs. Original contextual occurrences need not be a
     * prefix: manual additions precede that tail. Repeating only the first upcoming/current songs
     * keeps the original terminal song's action label unique for the bottom-boundary assertion.
     */
    private fun assertOverflowingQueueBottomStable(
        original: List<QueueOccurrence>, fixture: UiFixtureServer, evidenceName: String,
    ) {
        assertEquals("Overflow setup starts with the original occurrences", original, upcomingOccurrences())
        openQueueForInspection()
        if (exists(hasTestTag("now-playing-queue-view"))) {
            val beforeQueueBack = capturePlaying(fixture)
            pressBack()
            waitUntilAbsent(hasTestTag("now-playing-queue-view"))
            compose.onNode(playerSheet).assertIsDisplayed()
            assertPlayerPresentation()
            assertPlayingTransition(beforeQueueBack, fixture, "queue Back dismissal without player collapse")
            openQueueForInspection()
            assertPlayingTransition(beforeQueueBack, fixture, "explicit queue redisclosure")
        }
        val before = capturePlaying(fixture)
        val addedCount = 30
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
            val state = model.playback.state.value
            val queuedTrack = state.upcoming.first().item.copy(entryId = null)
            val finalTrack = checkNotNull(state.item).copy(entryId = null)
            model.playback.enqueue(List(addedCount - 1) { queuedTrack } + finalTrack,
                checkNotNull(model.graph.client))
        }
        compose.waitUntil(10_000) { upcomingOccurrences().size == original.size + addedCount }
        val overflowing = upcomingOccurrences()
        val originalIds = original.map { it.id }.toSet()
        assertEquals("Enqueue preserves original upcoming identities and relative order", original,
            overflowing.filter { it.id in originalIds })
        val added = overflowing.filter { it.id !in originalIds }
        assertEquals("All and only the temporary occurrences are identified", addedCount, added.size)
        assertEquals("Each temporary occurrence has a fresh unique ID", addedCount, added.map { it.id }.toSet().size)
        assertEquals("The terminal action label remains unique", 1,
            overflowing.count { it.title == overflowing.last().title })
        assertPlayingTransition(before, fixture, "temporary overflow enqueue without preparation")
        assertReleasedQueueBottomStable(overflowing, fixture, evidenceName)
        compose.runOnIdle {
            val controls = ViewModelProvider(compose.activity)[LibraryViewModel::class.java].playback
            added.forEach { controls.removeUpcoming(it.id) }
        }
        compose.waitUntil(10_000) { upcomingOccurrences() == original }
        assertQueueSnapshot(original)
        assertPlayingTransition(before, fixture, "overflow regression and occurrence-only cleanup")
    }

    /** Future working-AVD regression for the reported persistent released-bottom oscillation. */
    private fun assertReleasedQueueBottomStable(
        expected: List<QueueOccurrence>, fixture: UiFixtureServer, evidenceName: String,
    ) {
        assertTrue("Bottom-boundary regression needs a populated queue", expected.isNotEmpty())
        val before = capturePlaying(fixture)
        val queue = compose.onNodeWithTag("now-playing-queue").assertIsDisplayed()
        queue.performScrollToIndex(0)
        val startRange = queue.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val startOffset = startRange.value()
        assertEquals("Begin genuine scroll at queue start", 0f, startOffset, .000001f)
        assertTrue("The regression requires a genuinely overflowing queue", startRange.maxValue() > 0f)
        queue.performTouchInput {
            swipe(Offset(width * .55f, height * .8f), Offset(width * .55f, height * .2f), durationMillis = 300)
        }
        compose.waitUntil(5_000) {
            queue.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > startOffset
        }
        val movedOffset = queue.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("A real touch scroll must change the visible-index/offset range", movedOffset > startOffset)
        queue.performScrollToIndex(expected.lastIndex)
        queue.performTouchInput {
            swipe(Offset(width * .55f, height * .8f), Offset(width * .55f, 2f), durationMillis = 300)
        }
        // Let the intentional fling finish, then observe untouched frames while real service
        // progress repeatedly publishes. Do not issue a corrective scroll inside this window.
        compose.waitUntil(5_000) {
            val range = queue.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            range.value() > 0f && kotlin.math.abs(range.maxValue() - range.value()) < .000001f
        }
        compose.waitForIdle()
        val queueBounds = queue.fetchSemanticsNode().boundsInRoot
        val initialOffset = queue.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Terminal visible-index/offset must differ from the initial viewport", initialOffset > startOffset)
        val lastOrdinal = hasContentDescription("Reorder ", substring = true) and
            hasAnyAncestor(hasTestTag("now-playing-queue")) and SemanticsMatcher("last upcoming ordinal") {
                it.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith("Position ${expected.size} of ${expected.size}.") == true
            }
        val lastPosition = compose.onNode(lastOrdinal).assertIsDisplayed().fetchSemanticsNode().positionInRoot
        val lastAction = compose.onNodeWithContentDescription("Queue actions for ${expected.last().title}")
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertTrue("Complete last queue action must be reachable", lastAction.top >= queueBounds.top && lastAction.bottom <= queueBounds.bottom)
        val pinned = listOf("Previous song", "Pause", "Next song").associateWith {
            compose.onNode(sheetTransport(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        val progress = mutableSetOf<Float>()
        repeat(90) { frame ->
            SystemClock.sleep(24)
            compose.waitForIdle()
            assertRectStable("Queue viewport after release frame $frame", queueBounds, queue.fetchSemanticsNode().boundsInRoot)
            assertEquals("No spontaneous queue offset after release frame $frame", initialOffset,
                queue.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .000001f)
            val last = compose.onNode(lastOrdinal).fetchSemanticsNode().positionInRoot
            assertEquals("Last occurrence x after release frame $frame", lastPosition.x, last.x, .5f)
            assertEquals("Last occurrence y after release frame $frame", lastPosition.y, last.y, .5f)
            pinned.forEach { (label, bounds) ->
                assertRectStable("Pinned $label after release frame $frame", bounds,
                    compose.onNode(sheetTransport(label)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
            }
            progress += positionMs()
            assertEquals("Exact occurrence order survives progress frames", expected, upcomingOccurrences())
            assertEquals("Bottom scrolling must not prepare playback", before.preparations, fixture.playbackPreparations.get())
        }
        assertTrue("Observe repeated real playback progress updates, not a frozen frame", progress.size >= 3)
        assertPlayingTransition(before, fixture, "released queue bottom stability")
        screenshot(evidenceName)
        queue.performScrollToIndex(0) // Explicit test cleanup only, after the stability window.
    }

    private fun assertRectStable(label: String, expected: Rect, actual: Rect) {
        assertEquals("$label left", expected.left, actual.left, .5f)
        assertEquals("$label top", expected.top, actual.top, .5f)
        assertEquals("$label right", expected.right, actual.right, .5f)
        assertEquals("$label bottom", expected.bottom, actual.bottom, .5f)
    }

    private data class QueueOccurrence(val id: String, val title: String)
    // Read the Activity's EXISTING UI-facing state on main; do not create a second connection,
    // export a handoff snapshot (which samples history), or send player/queue commands.
    private fun readPlaybackState(): PlaybackState = compose.runOnIdle {
        ViewModelProvider(compose.activity)[LibraryViewModel::class.java].playback.state.value
    }
    private fun upcomingOccurrences(): List<QueueOccurrence> = readPlaybackState().upcoming.map {
        QueueOccurrence(it.occurrenceId, it.item.name)
    }
    private fun captureQueueSnapshot(count: Int = 5): List<QueueOccurrence> {
        openQueueForInspection()
        compose.onNodeWithText("Up next · $count").assertExists()
        val expected = upcomingOccurrences()
        assertEquals("Actual upcoming occurrence count", count, expected.size)
        val queue = compose.onNodeWithTag("now-playing-queue").assertIsDisplayed()
        // The native queue is a bounded LazyColumn, NOT an eagerly composed Column. Inspect
        // actual list indices and each handle's native ordinal, never seek expected track names.
        val occurrences = (0 until count).map { index ->
            queue.performScrollToIndex(index)
            val handles = hasContentDescription("Reorder ", substring = true) and
                hasAnyAncestor(hasContentDescription("Upcoming queue"))
            val ordinal = SemanticsMatcher("native upcoming position ${index + 1} of $count") {
                it.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith("Position ${index + 1} of $count.") == true
            }
            val handle = compose.onNode(handles and ordinal).assertIsDisplayed()
            // Also verify that concurrently composed handles have ascending native ordinals
            // in their UNCLIPPED layout order (boundsInRoot can collapse off-screen nodes).
            val placed = compose.onAllNodes(handles).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }
            val positions = placed.map { node ->
                val state = node.config[SemanticsProperties.StateDescription]
                checkNotNull(Regex("^Position (\\d+) of $count\\.").find(state)).groupValues[1].toInt()
            }
            assertEquals("Queue geometry must agree with traversal ordinals", positions.sorted(), positions)
            val title = handle.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                .single { it.startsWith("Reorder ") }.removePrefix("Reorder ")
            assertEquals("Rendered queue ordinal must identify the actual occurrence", expected[index].title, title)
            expected[index]
        }
        assertEquals("Upcoming occurrence IDs must be unique", count, occurrences.map { it.id }.toSet().size)
        assertTrue("Upcoming occurrence IDs must not be blank", occurrences.all { it.id.isNotBlank() })
        assertEquals("Queue must not change while inspecting lazy indices", expected, upcomingOccurrences())
        queue.performScrollToIndex(0) // Restore only the viewport; no queue commands or reordering.
        return occurrences
    }
    private fun assertQueueSnapshot(expected: List<QueueOccurrence>) {
        assertEquals("Upcoming occurrence order/traversal changed", expected, captureQueueSnapshot(expected.size))
    }

    private data class PlaybackWitness(
        val positionMs: Float, val durationMs: Float, val sampledAtMs: Long,
        val track: String, val occurrenceId: String, val preparations: Int,
    )
    private fun activePlayingButton() = if (exists(playerSheet)) playingButton() and inPlayerSheet() else playingButton()
    private fun readPlaybackWitness(fixture: UiFixtureServer): PlaybackWitness {
        val state = readPlaybackState()
        val sampledAt = SystemClock.elapsedRealtime()
        val occurrenceId = checkNotNull(state.currentEntryId) { "Audible occurrence identity is missing" }
        assertTrue("Audible occurrence identity must not be blank", occurrenceId.isNotBlank())
        val titles = compose.onNode(expandPlayer).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        val track = checkNotNull(titles.singleOrNull { it in UiFixtureServer.TRACK_NAMES })
        assertEquals("The unique audible occurrence must remain selected", UiFixtureServer.TRACK_NAMES.first(), track)
        assertEquals("Rendered title must match the actual selected occurrence", state.item?.name, track)
        assertEquals("Actual player duration must agree with synthetic metadata/WAV", UiFixtureServer.AUDIO_DURATION_MS, state.durationMs)
        val position: Float
        val duration: Float
        if (exists(playerPosition)) {
            val range = compose.onNode(playerPosition).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            position = range.current
            duration = range.range.endInclusive
        } else {
            val progress = renderedProgress().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0f, progress.range.start, .001f)
            assertEquals(1f, progress.range.endInclusive, .001f)
            duration = state.durationMs.toFloat()
            position = progress.current * duration
        }
        assertEquals("Actual duration must agree with synthetic metadata/WAV", UiFixtureServer.AUDIO_DURATION_MS.toFloat(), duration, 1f)
        assertTrue("Fixture must not naturally finish during this journey", position + 90_000f < duration)
        return PlaybackWitness(position, duration, sampledAt, track, occurrenceId, fixture.playbackPreparations.get())
    }
    private fun capturePlaying(fixture: UiFixtureServer): PlaybackWitness {
        waitFor(activePlayingButton())
        return readPlaybackWitness(fixture)
    }
    private fun assertPlaybackTimeline(before: PlaybackWitness, after: PlaybackWitness, label: String) {
        assertEquals("$label must retain the audible track", before.track, after.track)
        assertEquals("$label must retain the exact prepared occurrence", before.occurrenceId, after.occurrenceId)
        assertEquals("$label must retain duration", before.durationMs, after.durationMs, 1f)
        assertEquals("$label must not prepare another queue", before.preparations, after.preparations)
        val elapsed = (after.sampledAtMs - before.sampledAtMs).toFloat()
        val expected = before.positionMs + elapsed
        // Media3 publishes on a UI polling cadence. Two seconds covers that sampling lag, not
        // an arbitrary rewind: comparison is against elapsed-time progress, not merely >= before.
        assertTrue("$label reset/froze/jumped playback: before=${before.positionMs}, after=${after.positionMs}, elapsed=$elapsed",
            after.positionMs in (expected - 2_000f)..(expected + 2_000f))
    }
    private fun assertPlayingTransition(before: PlaybackWitness, fixture: UiFixtureServer, label: String) {
        waitFor(activePlayingButton())
        val after = readPlaybackWitness(fixture)
        assertPlaybackTimeline(before, after, label)
        // A stale Playing flag and frozen position must not pass the timeline's poll tolerance.
        compose.waitUntil(5_000) {
            val advancing = readPlaybackWitness(fixture)
            assertPlaybackTimeline(after, advancing, "$label subsequent advancement")
            exists(activePlayingButton()) && advancing.positionMs >= after.positionMs + 250f
        }
    }
    private fun assertPausedTransition(before: PlaybackWitness, fixture: UiFixtureServer) {
        waitFor(if (exists(playerSheet)) pausedButton() and inPlayerSheet() else pausedButton())
        val after = readPlaybackWitness(fixture)
        assertEquals(before.track, after.track)
        assertEquals("Paused transition must retain the exact prepared occurrence", before.occurrenceId, after.occurrenceId)
        assertEquals(before.durationMs, after.durationMs, 1f)
        assertEquals("Paused collapse must not prepare another queue", before.preparations, after.preparations)
        assertEquals("Paused collapse must preserve position", before.positionMs, after.positionMs, 1_000f)
    }
    private fun assertQueuePreservedFromMini(expected: List<QueueOccurrence>, fixture: UiFixtureServer) {
        val beforeExpand = capturePlaying(fixture)
        compose.onNode(expandPlayer).performClick()
        waitFor(playerSheet)
        assertPlayingTransition(beforeExpand, fixture, "queue inspection expansion")
        assertQueueSnapshot(expected)
        val beforeCollapse = capturePlaying(fixture)
        collapsePlayer()
        waitUntilAbsent(playerSheet)
        assertPlayingTransition(beforeCollapse, fixture, "queue inspection collapse")
    }
    private fun exists(matcher: SemanticsMatcher): Boolean = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) { compose.waitUntil(20_000) { exists(matcher) } }
    private fun waitUntilAbsent(matcher: SemanticsMatcher) { compose.waitUntil(15_000) { !exists(matcher) } }
    private fun playingButton() = hasContentDescription("Pause") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing")
    private fun pausedButton() = hasContentDescription("Play") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused")
    private fun inPlayerSheet() = hasAnyAncestor(hasTestTag("now-playing"))
    private fun sheetTransport(description: String) = hasContentDescription(description) and hasClickAction() and
        hasAnyAncestor(hasTestTag("now-playing-controls"))
    private fun renderedProgress(): SemanticsNodeInteraction = if (exists(hasTestTag("now-playing-queue-view")))
        compose.onNode(hasContentDescription("Queue playback progress") and inPlayerSheet())
        else compose.onNodeWithContentDescription("Playback progress")
    private fun positionMs(): Float = if (exists(playerPosition)) compose.onNode(playerPosition).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current
        else renderedProgress().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current * readPlaybackState().durationMs

    private fun assertSearchInThumbZone(imeVisible: Boolean = false) {
        waitForIme(imeVisible)
        assertListeningChrome(editing = imeVisible)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("search-query").fetchSemanticsNode()
        val search = field.boundsInRoot
        val dock = compose.onNodeWithTag("search-dock").fetchSemanticsNode().boundsInRoot
        if (!imeVisible) {
            assertTrue("Search must live in the lower thumb zone with IME closed: search=$search root=$root",
                search.top > root.top + root.height / 2f)
        }
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Search must retain a 48dp touch target", search.height >= 48f * density - 1f)
        assertTrue("Search must stay inside its dock", search.top >= dock.top && search.bottom <= dock.bottom)
        if (!imeVisible) {
            val navigation = compose.onNodeWithTag("primary-navigation").fetchSemanticsNode().boundsInRoot
            assertTrue("Search dock must stay above navigation", dock.bottom <= navigation.top + 1f)
        }
        if (exists(expandPlayer)) {
            val miniPlayer = compose.onNode(expandPlayer).fetchSemanticsNode().boundsInRoot
            assertTrue("Search dock must stay above the mini-player", dock.bottom <= miniPlayer.top + 1f)
        }
        var imeTop = Float.MAX_VALUE
        instrumentation.runOnMainSync {
            val decor = compose.activity.window.decorView
            imeTop = (decor.height - (decor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0)).toFloat()
        }
        assertTrue("Search must stay above the IME: field=${field.boundsInWindow} imeTop=$imeTop",
            field.boundsInWindow.bottom <= imeTop + 1f)
        compose.onNodeWithTag("search-query").assertIsDisplayed()
    }
    private fun assertGroupedSearchBounded(fixture: UiFixtureServer, query: String) {
        compose.waitUntil(15_000) { fixture.searchCalls.filter { it.query == query }.map { it.kind }.toSet().containsAll(setOf("artists", "albums", "tracks")) }
        val calls = fixture.searchCalls.filter { it.query == query }
        assertTrue("Every independent group must be bounded at ten", calls.all { it.limit == 10 })
        assertEquals(setOf("artists", "albums", "tracks"), calls.map { it.kind }.toSet())
    }
    private fun isImeVisible(): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible = compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        return visible
    }
    private fun waitForIme(visible: Boolean) {
        compose.waitUntil(10_000) { isImeVisible() == visible }
    }
    private fun waitForFont(scale: Float) {
        compose.waitUntil(20_000) {
            runCatching { kotlin.math.abs(compose.activity.resources.configuration.fontScale - scale) < .02f }.getOrDefault(false)
        }
    }
    private fun shell(command: String): String {
        VirtualAvdOnly.requireVerified()
        return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
        try {
            File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            // Record each actual render's dimensions/font/chrome, not just startup configuration.
            // A captured image still requires human inspection; these are not visual pass labels.
            val metrics = compose.activity.resources.displayMetrics
            val chrome = listOf("app-actions", "primary-navigation", "search-dock", "search-query").joinToString("\n") { tag ->
                val bounds = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                "$tag=${bounds ?: "absent"}"
            }
            File(evidence, "$name.txt").writeText("bitmap=${bitmap.width}x${bitmap.height}\n" +
                "viewport=${metrics.widthPixels}x${metrics.heightPixels}\ndensity=${metrics.density}\n" +
                "fontScale=${compose.activity.resources.configuration.fontScale}\nimeVisible=${isImeVisible()}\n$chrome\n")
        } finally { bitmap.recycle() }
    }
    private fun writeRunInfo() {
        val context = instrumentation.targetContext
        val metrics = context.resources.displayMetrics
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val revision = InstrumentationRegistry.getArguments().getString("apkRevision") ?: "not supplied"
        File(evidence, "run-info.txt").writeText("device=${Build.MODEL}\nhardware=${Build.HARDWARE}\nro.kernel.qemu=1\napi=${Build.VERSION.SDK_INT}\n" +
            "viewport=${metrics.widthPixels}x${metrics.heightPixels}\ndensity=${metrics.density}\n" +
            "fontScale=${context.resources.configuration.fontScale}\nlargeFontScale=2.0\nnarrowWidthDp=320\n" +
            "apkRevision=$revision\npackage=${context.packageName}\nversionName=${pkg.versionName}\nlastUpdateTime=${pkg.lastUpdateTime}\n" +
            "data=synthetic on-device loopback fixture only\nscreenshotSource=UiAutomation.takeScreenshot\n" +
            "visualReview=required; screenshots are evidence, not automatic visual approval\n")
    }
}
