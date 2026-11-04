package dev.spofify.mobile.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.spofify.mobile.MainActivity
import org.junit.Assert.*
import org.junit.BeforeClass
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
    companion object {
        // JUnit runs this before ActivityScenarioRule launches MainActivity (and before test writes).
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

    private val playerSheet = hasContentDescription("Playback position")

    @Test fun syntheticListenerJourneyAndRenderedEvidence() {
        VirtualAvdOnly.requireVerified()
        UiFixtureServer().use { fixture ->
            val originalFontSetting = shell("settings get system font_scale").trim()
            val originalFontScale = compose.activity.resources.configuration.fontScale
            check(originalFontSetting == "null" || originalFontSetting.toFloatOrNull()?.let { it.isFinite() && it > 0f } == true) {
                "Cannot safely restore the AVD's baseline font setting"
            }
            var fontChanged = false
            var journeyFailure: Throwable? = null
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
                compose.onNodeWithTag("search-query").assertIsDisplayed().assert(hasSetTextAction())
                assertEquals("Empty Search must not request a match-all catalog", 0, fixture.searchCalls.size)
                assertSearchInThumbZone()
                screenshot("03-search-empty")
                compose.onNodeWithTag("search-query").performClick()
                waitForIme(true)
                assertSearchInThumbZone(imeVisible = true)
                screenshot("04-search-keyboard")
                fixture.holdSearchResponses()
                try {
                    search("Orbit")
                    waitFor(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                    screenshot("04a-search-loading")
                } finally { fixture.releaseSearchResponses() }
                scrollToCatalogItem(UiFixtureServer.ALBUM_NAME)
                waitForIme(false)
                assertSearchInThumbZone()
                screenshot("05-search-results")
                assertGroupedSearchBounded(fixture, "Orbit")
                scrollToCatalogItem(UiFixtureServer.TRACK_NAMES.first())
                screenshot("05b-search-songs")
                scrollToText("See more songs")
                compose.onNodeWithText("See more songs").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
                compose.waitUntil(15_000) { fixture.searchCalls.any { it.query == "Orbit" && it.kind == "tracks" && it.offset == 10 } }
                scrollToCatalogItem(UiFixtureServer.TRACK_NAMES.last()).assertIsDisplayed()
                screenshot("05bb-search-see-more")

                search("unmatched-synthetic-query")
                waitFor(hasText("No artists match this search."))
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
                compose.onNodeWithText("Search").performClick()
                waitFor(hasTestTag("search-query"))
                compose.onNodeWithTag("search-query").assertTextContains("Orbit")
                assertRetainedSearchPosition(retainedAlbumTop)
                screenshot("06c-search-retained-after-back-and-tabs")
                assertNoAutoplayOnBrowse(fixture)
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
                screenshot("09-now-playing")
                compose.onNode(sheetTransport("Pause")).performScrollTo()
                screenshot("09b-now-playing-controls")
                compose.onNode(sheetTransport("Pause")).performClick()
                waitFor(pausedButton() and inPlayerSheet())
                compose.onNode(sheetTransport("Play")).performScrollTo().performClick()
                waitFor(playingButton() and inPlayerSheet())
                compose.waitUntil(10_000) { positionMs() > 0f }
                val positionBeforeCollapse = positionMs()
                compose.onNodeWithText("Clear upcoming").performScrollTo()
                compose.onNodeWithContentDescription("Upcoming queue").performScrollTo()
                waitFor(hasText("Up next · 5"))
                screenshot("10-queue")
                compose.onNodeWithContentDescription("Queue actions for ${UiFixtureServer.TRACK_NAMES[1]}").performScrollTo().performClick()
                compose.onNodeWithText("Remove from queue").performClick()
                waitUntilAbsent(hasText("Remove from queue") and hasClickAction())
                waitFor(hasText("Up next · 4"))
                compose.onNodeWithText("Undo").performScrollTo().performClick()
                waitFor(hasText("Up next · 5"))
                waitUntilAbsent(hasText("Undo") and hasClickAction())
                pressBack() // Dismiss the player sheet first; playlist stays beneath it.
                waitUntilAbsent(playerSheet)
                compose.onNode(expandPlayer).assertExists().assertTextContains(UiFixtureServer.TRACK_NAMES.first())
                waitFor(playingButton())
                compose.onNodeWithText("Night walk · edited").assertExists()
                screenshot("10b-player-collapsed")
                val density = compose.activity.resources.displayMetrics.density
                compose.onNode(expandPlayer).performTouchInput {
                    swipe(center, center.copy(y = center.y - 100f * density), durationMillis = 350)
                }
                waitFor(playerSheet)
                assertTrue("Collapsing must not restart the audible occurrence", positionMs() >= positionBeforeCollapse)
                waitFor(playingButton() and inPlayerSheet())
                compose.onNodeWithText("Clear upcoming").performScrollTo()
                compose.onNodeWithText("Up next · 5").assertExists()
                assertQueueIdentity()
                // Collapsing a paused player must not turn pause into resume either.
                compose.onNode(sheetTransport("Pause")).performScrollTo().performClick()
                waitFor(pausedButton() and inPlayerSheet())
                val pausedPosition = positionMs()
                pressBack()
                waitUntilAbsent(playerSheet)
                waitFor(pausedButton())
                compose.onNode(expandPlayer).performClick()
                waitFor(playerSheet)
                waitFor(pausedButton() and inPlayerSheet())
                assertTrue("Paused collapse must preserve position", kotlin.math.abs(positionMs() - pausedPosition) < 1_000f)
                compose.onNode(sheetTransport("Play")).performScrollTo().performClick()
                waitFor(playingButton() and inPlayerSheet())
                pressBack()
                waitUntilAbsent(playerSheet)
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
                waitFor(playerSheet)
                waitFor(playingButton() and inPlayerSheet())
                screenshot("13-large-font-now-playing")
                compose.onNodeWithText("Clear upcoming").performScrollTo()
                compose.onNodeWithContentDescription("Upcoming queue").performScrollTo()
                compose.onNodeWithText("Up next · 5").assertExists()
                screenshot("14-large-font-queue")
                assertEquals("No query-free catalog reads are allowed", 0, fixture.emptyGlobalBrowseCalls.get())
                assertEquals("Real auth cookie and CSRF must reach the fixture", 0, fixture.authenticationFailures.get())
                assertTrue("Unexpected fixture routes: ${fixture.unexpectedRoutes.joinToString()}", fixture.unexpectedRoutes.isEmpty())
                File(evidence, "fixture-summary.txt").writeText("tracks=16\nalbums=3\nartists=1\nmanualPlaylists=1\nderivedPlaylists=1\n" +
                    "audioRequests=${fixture.audioRequests.get()}\nplaybackPreparations=${fixture.playbackPreparations.get()}\nhistoryWrites=${fixture.historyWrites.get()}\nplaylistWrites=${fixture.playlistWrites.get()}\n" +
                    "emptyGlobalBrowseCalls=${fixture.emptyGlobalBrowseCalls.get()}\nauthenticationFailures=${fixture.authenticationFailures.get()}\nunexpectedRoutes=${fixture.unexpectedRoutes.size}\n")
            } catch (error: Throwable) {
                journeyFailure = error
                runCatching { screenshot("99-failure") }
                throw error
            } finally {
                if (fontChanged) {
                    try {
                        if (originalFontSetting == "null") shell("settings delete system font_scale")
                        else shell("settings put system font_scale $originalFontSetting")
                        waitForFont(originalFontScale)
                    } catch (restoreFailure: Throwable) {
                        val primary = journeyFailure
                        if (primary != null) primary.addSuppressed(restoreFailure) else throw restoreFailure
                    }
                }
            }
        }
    }

    private fun pressBack() {
        VirtualAvdOnly.requireVerified()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
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
    private fun assertRetainedSearchPosition(expectedTop: Float) {
        // Do not scroll here: doing so would hide a Back/tab restoration regression.
        compose.waitUntil(10_000) {
            val row = compose.onAllNodes(catalogItem(UiFixtureServer.ALBUM_NAME)).fetchSemanticsNodes().singleOrNull()
            row != null && kotlin.math.abs(row.boundsInRoot.top - expectedTop) <= 2f
        }
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
    private fun assertQueueIdentity() {
        val queue = compose.onNodeWithContentDescription("Upcoming queue")
        UiFixtureServer.TRACK_NAMES.subList(1, 6).forEach { name ->
            queue.performScrollToNode(hasContentDescription("Queue actions for $name"))
            compose.onNodeWithContentDescription("Queue actions for $name").assertExists()
        }
    }
    private fun exists(matcher: SemanticsMatcher): Boolean = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) { compose.waitUntil(20_000) { exists(matcher) } }
    private fun waitUntilAbsent(matcher: SemanticsMatcher) { compose.waitUntil(15_000) { !exists(matcher) } }
    private fun playingButton() = hasContentDescription("Pause") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing")
    private fun pausedButton() = hasContentDescription("Play") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused")
    private fun inPlayerSheet() = hasAnyAncestor(hasScrollAction())
    private fun sheetTransport(description: String) = hasContentDescription(description) and hasClickAction() and inPlayerSheet()
    private fun positionMs(): Float = compose.onNode(playerSheet).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun assertSearchInThumbZone(imeVisible: Boolean = false) {
        waitForIme(imeVisible)
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
        val navigation = compose.onNode(hasText("Search") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Search dock must stay above navigation", dock.bottom <= navigation.top + 1f)
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
        } finally { bitmap.recycle() }
    }
    private fun writeRunInfo() {
        val context = instrumentation.targetContext
        val metrics = context.resources.displayMetrics
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val revision = InstrumentationRegistry.getArguments().getString("apkRevision") ?: "not supplied"
        File(evidence, "run-info.txt").writeText("device=${Build.MODEL}\nhardware=${Build.HARDWARE}\nro.kernel.qemu=1\napi=${Build.VERSION.SDK_INT}\n" +
            "viewport=${metrics.widthPixels}x${metrics.heightPixels}\ndensity=${metrics.density}\n" +
            "fontScale=${context.resources.configuration.fontScale}\nlargeFontScale=1.3\n" +
            "apkRevision=$revision\npackage=${context.packageName}\nversionName=${pkg.versionName}\nlastUpdateTime=${pkg.lastUpdateTime}\n" +
            "data=synthetic on-device loopback fixture only\nscreenshotSource=UiAutomation.takeScreenshot\n" +
            "visualReview=required; screenshots are evidence, not automatic visual approval\n")
    }
}
