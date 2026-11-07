package dev.spofify.mobile.feature.devices

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.spofify.mobile.core.PlaybackDevice
import dev.spofify.mobile.core.PlaybackDevicesView
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Controlled production content/default modal host: no coordinator, protocol, network or polling.
 * Host geometry/semantics evidence only, NOT native rendered-app acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
class PlaybackDevicesRefreshTest {
    private val effects = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext =
        StandardTestDispatcher(effects, name = "PlaybackDevicesRefresh-effects"))
    private val phone = PlaybackDevice("phone", "Phone", true)
    private val room = PlaybackDevice("room", "Listening room", true)
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private val liveRegion = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
    private val sheet = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)
    private val list = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private val durations = listOf(16L, 250L, 500L, 1_000L)
    private fun healthy(devices: List<PlaybackDevice> = listOf(phone, room)) = PlaybackDevicesState(
        deviceId = "phone", connected = true, message = "",
        view = PlaybackDevicesView(deviceId = "phone", devices = devices, activeDeviceId = "",
            epoch = 1, leaseMs = 0, state = null, stateSeq = 0, stateAgeMs = 0,
            transfer = null, directives = emptyList(), commands = emptyList()),
    )

    private class Calls {
        var dismiss = 0; var retry = 0; var continueHere = 0
        val select = mutableListOf<String>()
        val rename = mutableListOf<Pair<String, String>>()
        fun assertQuiet() {
            assertEquals(0, dismiss); assertEquals(0, retry); assertEquals(0, continueHere)
            assertTrue(select.isEmpty())
            assertTrue(rename.isEmpty())
        }
    }

    private fun host(initial: PlaybackDevicesState, calls: Calls, fontScale: Float = 1f,
        retryPublication: ((MutableState<PlaybackDevicesState>) -> Unit)? = null,
    ): MutableState<PlaybackDevicesState> {
        val state = mutableStateOf(initial)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    // Deliberately use the production default sheet state/anchors/drag handle/insets.
                    // A fixed-height Column alone masks the content-driven modal regression.
                    ModalBottomSheet(onDismissRequest = { calls.dismiss++ }) {
                        PlaybackDevicesContent(state.value, { calls.dismiss++ },
                            { calls.retry++; retryPublication?.invoke(state) },
                            { calls.continueHere++ }, { calls.select += it },
                            { id, name -> calls.rename += id to name })
                    }
                }
            }
        }
        // Launch show(), run its finite entrance, then drain its completion. Never wall-clock poll.
        drain(1_000)
        return state
    }
    private fun drain(milliseconds: Long) {
        // With autoAdvance=false, v2 idling flushes snapshot notifications/current effects,
        // NOT pending frames. Flush first, deliver recomposition, then its follow-up effects/layout.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // Publication frames are setup, not the requested visible interval: 16 means 16ms AFTER them.
        compose.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
        compose.runOnUiThread { effects.runCurrent() }
        compose.waitForIdle()
    }
    private fun publish(holder: MutableState<PlaybackDevicesState>, value: PlaybackDevicesState,
        milliseconds: Long = 16,
    ) {
        compose.runOnIdle { holder.value = value }
        drain(milliseconds)
    }
    private fun layouts(node: LayoutInfo): List<LayoutInfo> = generateSequence(node) { it.parentInfo }.toList()
    private fun title(name: String) = compose.onNodeWithText(name, useUnmergedTree = true)
    private data class Geometry(val rectangles: Map<String, Rect>)
    private fun geometry(anchor: String? = null): Geometry {
        val header = title("Playback devices").fetchSemanticsNode()
        val lazy = compose.onNode(list).fetchSemanticsNode()
        // Nearest common layout is the production content Column, not our host or sheet root.
        val content = layouts(header.layoutInfo).first { candidate -> layouts(lazy.layoutInfo).any { it === candidate } }
        // Measure the direct content child containing the header, with or without a wrapper Row.
        val headerBranch = layouts(header.layoutInfo).first { it.parentInfo === content }
        val rectangles = linkedMapOf(
            "content" to content.coordinates.boundsInRoot(),
            "list" to lazy.boundsInRoot,
            "sheet" to compose.onNode(sheet).fetchSemanticsNode().boundsInRoot,
            "header branch" to headerBranch.coordinates.boundsInRoot(),
        )
        if (anchor != null) {
            val name = title(anchor).fetchSemanticsNode()
            // Include the whole keyed lazy item/card, not just its text or a copied card fixture.
            val card = layouts(name.layoutInfo).first { it.parentInfo === lazy.layoutInfo }
            rectangles["card $anchor"] = card.coordinates.boundsInRoot()
            rectangles["name $anchor"] = name.boundsInRoot
        }
        return Geometry(rectangles)
    }
    private fun assertGeometry(expected: Geometry, actual: Geometry, phase: String) {
        assertEquals(expected.rectangles.keys, actual.rectangles.keys)
        for ((name, before) in expected.rectangles) {
            val after = actual.rectangles.getValue(name)
            for ((edge, a, b) in listOf(Triple("left", before.left, after.left),
                Triple("top", before.top, after.top), Triple("right", before.right, after.right),
                Triple("bottom", before.bottom, after.bottom))) {
                assertEquals("$phase: $name $edge; before=$before after=$after", a, b, 0.5f)
            }
        }
    }
    private fun assertQuietSemantics() {
        compose.onNode(progress).assertDoesNotExist()
        // No per-heartbeat busy announcement; meaningful messages have their own tests below.
        compose.onNode(liveRegion).assertDoesNotExist()
        compose.onNodeWithText("Retry connection").assertDoesNotExist()
    }
    private fun busy(base: PlaybackDevicesState, projection: Int) = base.copy(
        connecting = projection != 1, verifyingConnection = projection != 0,
    )
    @Test fun healthyPopulatedRefreshKeepsGeometryAndSemanticsStable() {
        val calls = Calls()
        val populated = healthy()
        val holder = host(populated, calls)
        for (registered in listOf(true, false)) {
            val base = populated.copy(deviceId = if (registered) "phone" else "")
            publish(holder, base, 1_000)
            val settled = geometry("Phone")
            if (registered) {
                // A permanently reserved bar-sized strip is not a quiet-refresh fix either.
                assertEquals("No blank refresh strip between header and healthy list",
                    settled.rectangles.getValue("header branch").bottom,
                    settled.rectangles.getValue("list").top, 0.5f)
            }
            assertQuietSemantics()
            for (projection in 0..2) for (delay in durations) {
                val checking = busy(base, projection)
                assertSame(base.view, checking.view)
                publish(holder, checking, delay)
                // Bounds FIRST: capture causality before the expected spinner assertion fails.
                assertGeometry(settled, geometry("Phone"), "registered=$registered busy=$projection ${delay}ms")
                assertQuietSemantics()
                title("Phone").assertExists()
                title("Listening room").assertExists()
                if (!registered) compose.onNodeWithText("Rename").assertDoesNotExist()
                publish(holder, base, delay)
                assertGeometry(settled, geometry("Phone"), "settled after ${delay}ms")
            }
        }
        compose.runOnIdle { calls.assertQuiet() }
    }

    @Test fun knownEmptyRefreshKeepsGeometryAndEmptyStateStable() {
        val calls = Calls()
        val empty = healthy(emptyList())
        val holder = host(empty, calls)
        for (registered in listOf(true, false)) {
            val base = empty.copy(deviceId = if (registered) "phone" else "")
            publish(holder, base, 1_000)
            compose.onNodeWithText("No devices available.").assertIsDisplayed()
            val settled = geometry()
            val emptyBounds = title("No devices available.").fetchSemanticsNode().boundsInRoot
            for (projection in 0..2) for (delay in durations) {
                publish(holder, busy(base, projection), delay)
                assertGeometry(settled, geometry(), "known empty busy=$projection ${delay}ms")
                compose.onNodeWithText("No devices available.").assertIsDisplayed()
                assertEquals(emptyBounds, title("No devices available.").fetchSemanticsNode().boundsInRoot)
                assertQuietSemantics()
                publish(holder, base, delay)
                assertGeometry(settled, geometry(), "known empty settled")
            }
        }
        compose.runOnIdle { calls.assertQuiet() }
    }

    @Test fun firstLoadWithoutAViewShowsProgressAndNeverClaimsKnownEmptySuccess() {
        val calls = Calls()
        val initial = PlaybackDevicesState(message = "", connecting = true)
        val holder = host(initial, calls)
        for (projection in 0..2) {
            publish(holder, busy(initial, projection), 250)
            compose.onNode(progress).assertExists()
            compose.onNodeWithText("No devices available.").assertDoesNotExist()
            compose.onNodeWithText("Retry connection").assertDoesNotExist()
        }
        publish(holder, initial.copy(connecting = false))
        compose.onNode(progress).assertDoesNotExist()
        compose.onNodeWithText("No devices available.").assertDoesNotExist()
        val failure = initial.copy(connecting = false,
            recovery = PlaybackConnectionRecovery.VERIFICATION_NEEDED, message = "Could not verify devices.")
        publish(holder, failure)
        compose.onNodeWithText(failure.message).assertExists()
        compose.onNodeWithText("Retry connection").assertExists()
        compose.onNodeWithText("No devices available.").assertDoesNotExist()
        publish(holder, healthy(emptyList()))
        compose.onNode(progress).assertDoesNotExist()
        compose.onNodeWithText("No devices available.").assertExists()
        compose.runOnIdle { calls.assertQuiet() }
    }

    @Test fun cachedFailureAndExplicitRetryRemainTruthfulUntilSuccessfulPublication() {
        val calls = Calls()
        val healthy = healthy()
        val guidance = "Connection could not be verified. The previous action was not replayed."
        val failure = healthy.copy(recovery = PlaybackConnectionRecovery.VERIFICATION_NEEDED, message = guidance)
        val holder = host(failure, calls, retryPublication = { it.value = it.value.copy(verifyingConnection = true) })
        title("Phone").assertExists() // connected=true plus cached cards is not recovery success.
        compose.onNodeWithText(guidance).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
        compose.onNodeWithText("Retry connection").assertIsEnabled().performClick()
        drain(16)
        for (delay in durations) {
            drain(delay)
            compose.onNode(progress).assertExists()
            compose.onNodeWithText(guidance).assertExists()
            title("Phone").assertExists()
            compose.onNodeWithText("Retry connection").assertDoesNotExist()
            compose.runOnIdle { assertEquals("Pending retry cannot redispatch itself", 1, calls.retry) }
        }
        publish(holder, failure) // Failed completion preserves guidance and returns explicit recovery.
        compose.onNodeWithText("Retry connection").assertIsEnabled()
        compose.onNodeWithText(guidance).assertExists()
        compose.onNode(progress).assertDoesNotExist()
        publish(holder, healthy) // Only a successful state publication clears error/recovery.
        compose.onNodeWithText(guidance).assertDoesNotExist()
        assertQuietSemantics()
        for (reason in listOf(PlaybackConnectionRecovery.AUTH_REQUIRED, PlaybackConnectionRecovery.UNSUPPORTED,
            PlaybackConnectionRecovery.OWNER_ENDED)) {
            val message = "Synthetic safety guidance: $reason"
            publish(holder, healthy.copy(recovery = reason, message = message))
            compose.onNodeWithText(message).assertExists()
            compose.onNodeWithText("Retry connection").assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(1, calls.retry); assertEquals(0, calls.dismiss); assertEquals(0, calls.continueHere)
            assertTrue(calls.select.isEmpty()); assertTrue(calls.rename.isEmpty())
        }
    }

    @Test fun routineInformationIsHiddenButActualOutputAndFailuresRemain() {
        val calls = Calls()
        val base = healthy(listOf(phone, room.copy(available = false))).copy(
            message = "Device name saved. Native handoff remains unavailable on this phone.",
            messagePurpose = PlaybackDevicesMessagePurpose.INFORMATION,
            view = healthy(listOf(phone, room.copy(available = false))).view!!.copy(
                activeDeviceId = "phone", state = PlaybackSnapshot(playing = false)))
        val holder = host(base, calls)
        compose.onNodeWithText(base.message).assertDoesNotExist()
        compose.onNodeWithText("This device · Active output · Paused").assertExists()
        compose.onNodeWithText("Unavailable").assertExists()
        dev.spofify.mobile.capturePolishEvidence("devices-normal", compose.onRoot())
        val failure = "That player is unavailable. Open Spofify on it and choose again."
        publish(holder, base.copy(message = failure, messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE))
        compose.onNodeWithText(failure).assertExists()
        dev.spofify.mobile.capturePolishEvidence("devices-failure", compose.onRoot())
        compose.runOnIdle { calls.assertQuiet() }
    }

    @Test fun needsTapGuidanceAndContinueAreNotRoutineVerificationOrRetry() {
        val calls = Calls()
        val base = healthy(listOf(phone))
        val blocked = base.copy(needsTap = true, message = "Playback needs a tap. The previous action was not replayed.",
            view = base.view!!.copy(activeDeviceId = "phone", state = PlaybackSnapshot(error = "blocked")))
        val holder = host(blocked, calls)
        for (delay in durations) {
            publish(holder, blocked.copy(verifyingConnection = true), delay)
            compose.onNodeWithText(blocked.message).assertExists()
            compose.onNodeWithText("This device · Needs a tap").assertExists()
            compose.onNodeWithText("Continue playback here").assertIsEnabled()
            compose.onNodeWithText("Retry connection").assertDoesNotExist()
        }
        compose.runOnIdle { calls.assertQuiet() }
        compose.onNodeWithText("Continue playback here").performClick()
        drain(16)
        publish(holder, blocked.copy(deviceId = ""))
        compose.onNodeWithText(blocked.message).assertExists()
        compose.onNodeWithText("Continue playback here").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, calls.continueHere); assertEquals(0, calls.retry); assertEquals(0, calls.dismiss)
            assertTrue(calls.select.isEmpty()); assertTrue(calls.rename.isEmpty())
        }
    }
    private data class ScrollAnchor(val value: Float, val maximum: Float)
    private fun scrollAnchor(): ScrollAnchor {
        val range = compose.onNode(list).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return compose.runOnIdle { ScrollAnchor(range.value(), range.maxValue()) }
    }

    @Test fun overflowingListKeepsReleasedTerminalAnchorThroughManyRefreshCycles() {
        val devices = (0 until 24).map { PlaybackDevice("device-$it", "Room $it", true) }
        val base = healthy(devices)
        val calls = Calls()
        val holder = host(base, calls)
        val initial = scrollAnchor()
        // Actual touch input, including up/release. A finite count, not a timing/readiness loop.
        // Zero terminal velocity prevents a fling from being confused with refresh scroll theft.
        repeat(12) {
            compose.onNode(list).performTouchInput {
                swipeWithVelocity(Offset(centerX, bottom - 8f), Offset(centerX, top + 8f),
                    endVelocity = 0f, durationMillis = 500)
            }
            drain(250)
        }
        drain(1_000)
        val terminal = scrollAnchor()
        assertTrue("Fixture genuinely overflowed and touch input moved its anchor", terminal.value > initial.value)
        assertEquals("Touch scroll reached terminal boundary", terminal.maximum, terminal.value, 0.001f)
        title("Room 23").assertIsDisplayed()
        val settled = geometry("Room 23") // Never query the disposed first card at the bottom.
        for (cycle in 0 until 24) {
            val delay = durations[cycle % durations.size]
            publish(holder, busy(base, cycle % 3), delay)
            assertGeometry(settled, geometry("Room 23"), "released terminal cycle $cycle")
            assertEquals(terminal, scrollAnchor())
            title("Room 23").assertIsDisplayed()
            assertQuietSemantics()
            publish(holder, base, delay)
            assertGeometry(settled, geometry("Room 23"), "terminal idle $cycle")
            assertEquals(terminal, scrollAnchor())
        }
        compose.runOnIdle { calls.assertQuiet() }
    }
    private fun editor() = compose.onNode(hasSetTextAction())
    private fun selection(start: Int, end: Int) {
        editor().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(start, end, false)) }
        drain(16)
    }
    private fun assertEditor(id: Int, draft: String, range: TextRange) {
        editor().assertIsFocused()
        val node = editor().fetchSemanticsNode()
        assertEquals("Keyed editor must not be remounted", id, node.id)
        assertEquals(draft, node.config[SemanticsProperties.EditableText].text)
        assertEquals(range, node.config[SemanticsProperties.TextSelectionRange])
    }
    @Test fun renameTypingFocusAndSelectionSurviveRoutineRefreshWithoutDispatch() = checkRenameContinuity(1f)

    @Config(sdk = [35], qualifiers = "w320dp-h850dp-mdpi")
    @Test fun narrowFontTwoLongUnicodeRenameSurvivesRoutineRefreshWithoutBlankStrip() = checkRenameContinuity(2f)
    private fun checkRenameContinuity(fontScale: Float) {
        val name = "音楽の部屋 🎧 — Café e\u0301 — Wohnzimmer mit einem langen Namen"
        val base = healthy(listOf(phone.copy(name = name)))
        val calls = Calls()
        val holder = host(base, calls, fontScale)
        val quiet = geometry(name)
        assertEquals("No permanent blank strip at production typography/font scale",
            quiet.rectangles.getValue("header branch").bottom, quiet.rectangles.getValue("list").top, 0.5f)
        compose.onNodeWithContentDescription("Rename $name").performClick()
        drain(16) // Commit the editor before querying its semantics.
        editor().performClick()
        drain(16) // Establish focus/input session before the text edit.
        editor().performTextReplacement("Draft 音楽 🎧")
        drain(16)
        selection(1, 3)
        drain(1_000) // Settle the deliberate editor opening/bring-into-view before refresh measurements.
        val editorId = editor().fetchSemanticsNode().id
        var draft = "Draft 音楽 🎧"
        val editing = geometry(name)
        for (cycle in 0 until 8) {
            // Type at a non-terminal selection between busy publications, not just static text.
            editor().performTextInput("é")
            draft = draft.replaceRange(1, 3, "é")
            drain(16) // Commit typing before applying the next non-terminal selection.
            selection(1, 3)
            val range = TextRange(1, 3)
            publish(holder, busy(base, cycle % 3), durations[cycle % durations.size])
            assertGeometry(editing, geometry(name), "typing busy $cycle font=$fontScale")
            assertEditor(editorId, draft, range)
            publish(holder, base, 16)
            assertEditor(editorId, draft, range)
        }
        compose.runOnIdle { calls.assertQuiet() }
        compose.onNodeWithText("Save").performScrollTo().performClick()
        drain(16)
        editor().assertDoesNotExist()
        title(name).assertExists() // Rendering/save does not fabricate server name confirmation.
        compose.runOnIdle { assertEquals(listOf("phone" to draft), calls.rename) }
    }

    @Test fun keyedEditorSurvivesRealReorderWhileAddRemoveAndAcceptedNamesStillRender() {
        val calls = Calls()
        val base = healthy()
        val holder = host(base, calls)
        compose.onNodeWithContentDescription("Rename Phone").performClick()
        drain(16)
        editor().performClick()
        drain(16)
        editor().performTextReplacement("Unsaved 音楽")
        drain(16)
        selection(2, 5)
        val id = editor().fetchSemanticsNode().id
        val added = PlaybackDevice("new", "New output 🎧", true)
        val changed = base.copy(view = base.view!!.copy(devices = listOf(added, phone.copy(name = "Accepted phone name"))))
        publish(holder, changed, 1_000)
        title("Listening room").assertDoesNotExist()
        title("New output 🎧").assertExists()
        title("Accepted phone name").assertExists()
        assertEditor(id, "Unsaved 音楽", TextRange(2, 5))
        compose.runOnIdle { calls.assertQuiet() }
        compose.onNodeWithText("Save").performScrollTo().performClick()
        drain(16)
        compose.runOnIdle { assertEquals(listOf("phone" to "Unsaved 音楽"), calls.rename) }
        // Removal must still dispose the real card; quiet refresh must not freeze the device list.
        publish(holder, changed.copy(view = changed.view!!.copy(devices = listOf(added))), 1_000)
        title("Accepted phone name").assertDoesNotExist()
        title("New output 🎧").assertExists()
    }
}
