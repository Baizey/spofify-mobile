package dev.spofify.mobile.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.spofify.mobile.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Run only after pm clear on a fresh isolated test AVD. One journey intentionally owns one fixture
 * origin for the whole Application lifetime. Uses the real Activity, Keystore, networking and Media3.
 * Screenshots are full-device UiAutomation bitmaps, not Compose/Robolectric/source renders.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NativeUiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence: File get() = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
    private val expandPlayer = SemanticsMatcher("Expand Now Playing action") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Expand Now Playing"
    }

    @Test fun syntheticListenerJourneyAndRenderedEvidence() {
        UiFixtureServer().use { fixture ->
            var fontChanged = false
            try {
                writeRunInfo()
                waitFor(hasText("Server URL"))
                screenshot("01-sign-in")
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
                assertEquals(0, fixture.emptyGlobalBrowseCalls.get())

                compose.onNodeWithText("Search").performClick()
                waitFor(hasTestTag("search-query"))
                compose.onNodeWithTag("search-query").assertIsDisplayed().assertTextContains("Search your music")
                assertEquals("Empty Search must not request a match-all catalog", 0, fixture.searchCalls.size)
                assertSearchInThumbZone()
                screenshot("03-search-empty")
                compose.onNodeWithTag("search-query").performClick()
                waitForIme(true)
                screenshot("04-search-keyboard")
                fixture.searchBodyDelayMs = 1_500
                search("Orbit")
                waitFor(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                screenshot("04a-search-loading")
                waitFor(hasText(UiFixtureServer.ALBUM_NAME))
                fixture.searchBodyDelayMs = 0
                waitForIme(false)
                assertSearchInThumbZone()
                screenshot("05-search-results")
                assertGroupedSearchBounded(fixture, "Orbit")
                scrollToText("Songs")
                waitFor(hasText(UiFixtureServer.TRACK_NAMES.first()))
                screenshot("05b-search-songs")

                search("unmatched-synthetic-query")
                waitFor(hasText("No artists match this search."))
                screenshot("05c-search-no-results")
                search(UiFixtureServer.ERROR_QUERY)
                waitFor(hasText("Retry", substring = true))
                screenshot("05d-search-error")
                search("Orbit")
                waitFor(hasText(UiFixtureServer.ALBUM_NAME))
                scrollToText(UiFixtureServer.ALBUM_NAME)
                compose.onNodeWithText(UiFixtureServer.ALBUM_NAME).performClick()
                waitFor(hasContentDescription("More actions for ${UiFixtureServer.ALBUM_NAME}"))
                screenshot("06-album-details")
                waitFor(hasText(UiFixtureServer.TRACK_NAMES.first()))
                scrollToText(UiFixtureServer.TRACK_NAMES.first())
                compose.onNodeWithText(UiFixtureServer.TRACK_NAMES.first()).performClick()
                waitFor(hasText("Like"))
                screenshot("06b-track-details")
                compose.onNodeWithText("Like").performScrollTo().performClick()
                waitFor(hasText("Unlike"))
                pressBack() // Song detail -> album detail, never a visible in-app Back control.
                waitFor(hasContentDescription("More actions for ${UiFixtureServer.ALBUM_NAME}"))
                pressBack() // Album detail -> retained Search results.
                waitFor(hasTestTag("search-query"))
                compose.onNodeWithTag("search-query").assertTextContains("Orbit")
                assertEquals(0, fixture.audioRequests.get()) // Browsing and liking never autoplay.
                compose.onNodeWithContentDescription("Clear search").performClick()
                waitFor(hasText("Start typing to search.", substring = true))
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
                compose.onNodeWithText("Edit details").performClick()
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
                    compose.onNodeWithText("Play on Studio speaker").assertIsNotEnabled()
                    screenshot("08c-playback-devices-read-only")
                    pressBack()
                    waitFor(hasText("Night walk · edited"))
                }
                waitFor(hasText("Play") and hasClickAction() and isEnabled())
                compose.onAllNodes(hasText("Play") and hasClickAction()).onFirst().assertIsEnabled().performClick()
                compose.waitUntil(20_000) { fixture.audioRequests.get() > 0 && exists(expandPlayer) }
                waitFor(playingButton())
                compose.onNode(expandPlayer).performClick()
                waitFor(hasText("NOW PLAYING"))
                screenshot("09-now-playing")
                compose.onAllNodesWithContentDescription("Pause").onLast().performScrollTo()
                screenshot("09b-now-playing-controls")
                compose.onAllNodesWithContentDescription("Pause").onLast().performClick()
                waitFor(pausedButton())
                compose.onAllNodesWithContentDescription("Play").onLast().performScrollTo().performClick()
                waitFor(playingButton())
                compose.waitUntil(10_000) { positionMs() > 0f }
                val positionBeforeCollapse = positionMs()
                compose.onNodeWithText("Clear upcoming").performScrollTo()
                compose.onNodeWithContentDescription("Upcoming queue").performScrollTo()
                waitFor(hasText("Up next · 5"))
                screenshot("10-queue")
                compose.onNodeWithContentDescription("Queue actions for ${UiFixtureServer.TRACK_NAMES[1]}").performScrollTo().performClick()
                compose.onNodeWithText("Remove from queue").performClick()
                waitFor(hasText("Up next · 4"))
                compose.onNodeWithText("Undo").performScrollTo().performClick()
                waitFor(hasText("Up next · 5"))
                pressBack() // Dismiss the player sheet first; playlist stays beneath it.
                waitUntilAbsent(hasText("NOW PLAYING"))
                compose.onNode(expandPlayer).assertExists()
                waitFor(playingButton())
                compose.onNodeWithText("Night walk · edited").assertExists()
                screenshot("10b-player-collapsed")
                val density = compose.activity.resources.displayMetrics.density
                compose.onNode(expandPlayer).performTouchInput {
                    swipe(center, center.copy(y = center.y - 100f * density), durationMillis = 350)
                }
                waitFor(hasText("NOW PLAYING"))
                assertTrue("Collapsing must not restart the audible occurrence", positionMs() >= positionBeforeCollapse)
                compose.onNodeWithText("Clear upcoming").performScrollTo()
                compose.onNodeWithText("Up next · 5").assertExists()
                pressBack()
                waitUntilAbsent(hasText("NOW PLAYING"))
                pressBack() // With the sheet gone, native Back now pops the playlist detail.
                waitFor(hasText(UiFixtureServer.DERIVED_NAME))

                // Isolated AVD only. Restore the baseline even if a large-font assertion fails.
                fontChanged = true
                shell("settings put system font_scale 1.3")
                waitForFont(1.3f)
                compose.activityRule.scenario.recreate()
                waitFor(hasText("Home"))
                compose.onNodeWithText("Home").performClick()
                waitFor(hasText("Recently played"))
                screenshot("11-large-font-home")
                compose.onNodeWithText("Search").performClick()
                waitFor(hasTestTag("search-query"))
                waitForIme(false)
                assertSearchInThumbZone()
                screenshot("12-large-font-search")
                waitFor(expandPlayer)
                compose.onNode(expandPlayer).performClick()
                waitFor(hasText("NOW PLAYING"))
                screenshot("13-large-font-now-playing")
                compose.onNodeWithText("Clear upcoming").performScrollTo()
                compose.onNodeWithContentDescription("Upcoming queue").performScrollTo()
                compose.onNodeWithText("Up next · 5").assertExists()
                screenshot("14-large-font-queue")
                assertEquals("No query-free catalog reads are allowed", 0, fixture.emptyGlobalBrowseCalls.get())
                assertEquals("Real auth cookie and CSRF must reach the fixture", 0, fixture.authenticationFailures.get())
                assertTrue("Unexpected fixture routes: ${fixture.unexpectedRoutes.joinToString()}", fixture.unexpectedRoutes.isEmpty())
                File(evidence, "fixture-summary.txt").writeText("tracks=16\nalbums=3\nartists=1\nmanualPlaylists=1\nderivedPlaylists=1\n" +
                    "audioRequests=${fixture.audioRequests.get()}\nhistoryWrites=${fixture.historyWrites.get()}\nplaylistWrites=${fixture.playlistWrites.get()}\n" +
                    "emptyGlobalBrowseCalls=${fixture.emptyGlobalBrowseCalls.get()}\nunexpectedRoutes=${fixture.unexpectedRoutes.size}\n")
            } catch (error: Throwable) {
                runCatching { screenshot("99-failure") }
                throw error
            } finally {
                if (fontChanged) {
                    shell("settings put system font_scale 1.0")
                    waitForFont(1.0f)
                }
            }
        }
    }

    private fun search(query: String) {
        compose.onNodeWithTag("search-query").performTextReplacement(query).performImeAction()
    }
    private fun scrollToText(text: String) {
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text))
    }
    private fun exists(matcher: SemanticsMatcher): Boolean = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) { compose.waitUntil(20_000) { exists(matcher) } }
    private fun waitUntilAbsent(matcher: SemanticsMatcher) { compose.waitUntil(15_000) { !exists(matcher) } }
    private fun playingButton() = hasContentDescription("Pause") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing")
    private fun pausedButton() = hasContentDescription("Play") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused")
    private fun positionMs(): Float = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun assertSearchInThumbZone() {
        waitForIme(false)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("search-query").fetchSemanticsNode().boundsInRoot
        assertTrue("Search must live in the lower thumb zone with IME closed: search=$search root=$root", search.top > root.height / 2f)
        compose.onNodeWithTag("search-query").assertIsDisplayed()
    }
    private fun assertGroupedSearchBounded(fixture: UiFixtureServer, query: String) {
        compose.waitUntil(15_000) { fixture.searchCalls.filter { it.query == query }.map { it.kind }.toSet().containsAll(setOf("artists", "albums", "tracks")) }
        val calls = fixture.searchCalls.filter { it.query == query }
        assertTrue("Every independent group must be bounded at ten", calls.all { it.limit == 10 })
        assertEquals(setOf("artists", "albums", "tracks"), calls.map { it.kind }.toSet())
    }
    private fun waitForIme(visible: Boolean) {
        compose.waitUntil(10_000) {
            var actual = false
            instrumentation.runOnMainSync { actual = compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            actual == visible
        }
    }
    private fun waitForFont(scale: Float) {
        compose.waitUntil(20_000) {
            runCatching { kotlin.math.abs(compose.activity.resources.configuration.fontScale - scale) < .02f }.getOrDefault(false)
        }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
        File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun writeRunInfo() {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        File(evidence, "run-info.txt").writeText("device=${Build.MODEL}\napi=${Build.VERSION.SDK_INT}\n" +
            "viewport=${metrics.widthPixels}x${metrics.heightPixels}\ndensity=${metrics.density}\n" +
            "fontScale=${instrumentation.targetContext.resources.configuration.fontScale}\n" +
            "data=synthetic on-device loopback fixture only\nscreenshotSource=UiAutomation.takeScreenshot\n")
    }
}
