package dev.spofify.mobile.feature.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.PlaybackDevice
import dev.spofify.mobile.core.PlaybackDevicesView
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Static presentation hosts: no coordinator, protocol, automatic reads, or coroutine scheduler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
class PlaybackDevicesSheetTest {
    @get:Rule val compose = createComposeRule()

    private val phone = PlaybackDevice("phone", "Phone", true)
    private val longName = "A long listening room device name beyond the available width"
    private val unicodeName = "音楽の部屋 🎧 — Café e\u0301 — Wohnzimmer mit einem langen Namen"

    private fun state(
        device: PlaybackDevice = phone,
        active: String = "",
        selected: String = "",
        snapshot: PlaybackSnapshot? = null,
    ) = PlaybackDevicesState(
        deviceId = "phone", connected = true, selectedDeviceId = selected, message = "",
        view = PlaybackDevicesView(
            deviceId = "phone", devices = listOf(device), activeDeviceId = active,
            epoch = 1, leaseMs = if (active.isEmpty()) 0 else 8000, state = snapshot,
            stateSeq = 0, stateAgeMs = 0, transfer = null, directives = emptyList(), commands = emptyList(),
        ),
    )

    private class Calls {
        var dismiss = 0
        var retry = 0
        var continueHere = 0
        val selections = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        fun assertQuiet() {
            assertEquals(0, dismiss)
            assertEquals(0, retry)
            assertEquals(0, continueHere)
            assertTrue(selections.isEmpty())
            assertTrue(renames.isEmpty())
        }
    }

    private fun content(initial: PlaybackDevicesState, calls: Calls, width: Int = 400) =
        mutableStateOf(initial).also { holder ->
            compose.setContent {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Column(Modifier.width(width.dp).height(800.dp)) {
                        PlaybackDevicesContent(holder.value, { calls.dismiss++ }, { calls.retry++ },
                            { calls.continueHere++ }, { calls.selections += it },
                            { id, name -> calls.renames += id to name })
                    }
                }
            }
        }

    private data class TextGeometry(val bounds: Rect, val layout: TextLayoutResult) {
        val start get() = bounds.left + layout.getLineLeft(0)
        val end get() = bounds.left + layout.getLineRight(0)
    }

    private fun text(label: String): TextGeometry {
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return TextGeometry(node.fetchSemanticsNode().boundsInRoot, layouts.single())
    }

    private fun assertCompleteOneLine(text: TextGeometry, label: String) {
        val layout = text.layout
        assertEquals(label, layout.layoutInput.text.text)
        assertEquals("$label line count", 1, layout.lineCount)
        assertFalse("$label must not ellipsize", layout.isLineEllipsized(0))
        assertFalse("$label must not exceed max lines", layout.multiParagraph.didExceedMaxLines)
        assertTrue("$label must fit horizontally", layout.getLineRight(0) <= layout.size.width + 1f)
        assertTrue("$label physical paragraph must fit vertically",
            ceil(layout.multiParagraph.height.toDouble()) <= layout.size.height)
        assertEquals("$label rendered height", layout.size.height.toFloat(), text.bounds.height, 1f)
        assertTrue("$label visible text bounds", text.bounds.width + 1f >= layout.getLineRight(0) - layout.getLineLeft(0))
    }

    private fun target(description: String): Rect =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.also {
            val minimum = with(compose.density) { 48.dp.toPx() }
            assertTrue("$description width ${it.width}", it.width >= minimum - 1f)
            assertTrue("$description height ${it.height}", it.height >= minimum - 1f)
        }

    private fun assertDisjoint(first: Rect, second: Rect) {
        assertTrue("Action targets must not overlap: $first / $second",
            first.right <= second.left || second.right <= first.left ||
                first.bottom <= second.top || second.bottom <= first.top)
    }

    // Native font metrics are necessary: legacy ShadowPaint cannot prove production-font fit.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun normalPhoneTextEdgesAndRenameSlotAreStableForShortLongAndUnicodeNames() {
        assertCardGeometry(width = 400, fontScale = 1f, stacked = false)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun narrowFontTwoKeepsCompleteActionsAndSeparateFortyEightDpTargets() {
        assertCardGeometry(width = 320, fontScale = 2f, stacked = true)
    }

    private fun assertCardGeometry(width: Int, fontScale: Float, stacked: Boolean) {
        val holder = mutableStateOf(state())
        val calls = Calls()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    // Match the production content's horizontal gutters, not an artificially wider card.
                    Column(Modifier.width(width.dp).padding(horizontal = 20.dp)) {
                        val current = holder.value
                        PlaybackDeviceCard(current, current.devices.single(), false, "", {},
                            { calls.selections += current.devices.single().deviceId }, {}, {}, {})
                    }
                }
            }
        }
        var renameEnd: Float? = null
        val tolerance = with(compose.density) { 1.dp.toPx() }
        // Include enabled, selected/disabled, unavailable, and pending/disabled presentations.
        for ((index, name) in listOf("Phone", longName, unicodeName, "Pending 🎧").withIndex()) {
            val device = phone.copy(name = name, available = index != 2)
            val selected = if (index == 1 || index == 3) "phone" else ""
            compose.runOnIdle { holder.value = state(device, selected = selected).copy(selectionUnconfirmed = index == 3) }
            val status = when (index) {
                1 -> "This device · Selected · Available"
                2 -> "This device · Unavailable"
                3 -> "This device · Selecting · Available"
                else -> "This device · Available"
            }
            val title = text(name)
            val statusText = text(status)
            val play = text("Play from here")
            val rename = text("Rename")
            assertEquals("Name/status content start ($name)", title.start, statusText.start, tolerance)
            assertEquals("Name/play content start ($name)", title.start, play.start, tolerance)
            assertCompleteOneLine(play, "Play from here")
            assertCompleteOneLine(rename, "Rename")
            renameEnd?.let { assertEquals("Rename end is independent of identity/state", it, rename.end, tolerance) }
            renameEnd = rename.end
            val playTarget = target("Play from here on $name")
            val renameTarget = target("Rename $name")
            assertDisjoint(playTarget, renameTarget)
            assertTrue("Targets remain in the phone viewport", playTarget.left >= 0f && renameTarget.right <= width + tolerance)
            assertTrue("Text is within its target", play.bounds.left >= playTarget.left - tolerance &&
                play.bounds.right <= playTarget.right + tolerance && rename.bounds.right <= renameTarget.right + tolerance)
            if (stacked) {
                assertTrue("At font2 playback yields a separate line", playTarget.top >= renameTarget.bottom)
                assertTrue("Long identity is bounded before Rename", title.bounds.right <= renameTarget.left + tolerance)
            } else {
                assertEquals("Normal-phone action row", playTarget.center.y, renameTarget.center.y, tolerance)
            }
        }
        compose.runOnIdle { calls.assertQuiet() }
    }

    @Test fun selectPreservesTheExactAdmissionMatrixAndDispatchesOnlyTheDeviceId() {
        val calls = Calls()
        val holder = content(state(), calls)
        data class Case(val available: Boolean, val active: Boolean, val selected: String, val pending: Boolean, val enabled: Boolean)
        val cases = listOf(
            Case(true, false, "", false, true),
            Case(false, false, "", false, false),
            Case(true, false, "phone", false, false),
            Case(true, true, "", false, false),
            Case(true, true, "other", false, true),
            Case(true, false, "other", true, true),
            Case(true, false, "phone", true, false),
        )
        var expectedCalls = 0
        for (case in cases) {
            compose.runOnIdle {
                val candidate = state(phone.copy(available = case.available),
                    active = if (case.active) "phone" else "", selected = case.selected)
                holder.value = candidate.copy(selectionUnconfirmed = case.pending,
                    view = candidate.view!!.copy(quarantineMs = 1500))
            }
            val action = compose.onNodeWithContentDescription("Play from here on Phone")
            if (case.enabled) action.assertIsEnabled() else action.assertIsNotEnabled()
            action.performClick()
            if (case.enabled) expectedCalls++
            compose.runOnIdle {
                assertEquals(List(expectedCalls) { "phone" }, calls.selections)
                assertEquals(0, calls.retry)
                assertEquals(0, calls.continueHere)
                assertTrue(calls.renames.isEmpty())
            }
        }
        compose.runOnIdle { holder.value = state().copy(deviceId = "") }
        compose.onNodeWithText("Play from here").assertDoesNotExist()
        compose.onNodeWithText("Rename").assertDoesNotExist()
        compose.onNodeWithText("View only · Playback transfer is unavailable on this phone.").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, calls.dismiss); assertEquals(expectedCalls, calls.selections.size) }
    }

    @Test fun retryIsOnlyExplicitRecoveryAndNeverAHealthyReadOnlyOrBusyCommand() {
        val calls = Calls()
        val healthy = state()
        val holder = content(healthy, calls)
        val quietStates = listOf(
            healthy,
            healthy.copy(deviceId = ""), // Successful authenticated view, not a registration failure.
            healthy.copy(view = healthy.view!!.copy(devices = emptyList())),
            PlaybackDevicesState(connecting = true, verifyingConnection = true, message = ""),
            healthy.copy(recovery = PlaybackConnectionRecovery.VERIFICATION_NEEDED, verifyingConnection = true),
            healthy.copy(needsTap = true, view = healthy.view!!.copy(state = PlaybackSnapshot(error = "blocked"))),
        ) + listOf(PlaybackConnectionRecovery.AUTH_REQUIRED, PlaybackConnectionRecovery.OWNER_ENDED,
            PlaybackConnectionRecovery.UNSUPPORTED).map { healthy.copy(recovery = it) }
        for (value in quietStates) {
            compose.runOnIdle { holder.value = value }
            compose.onNodeWithText("Retry connection").assertDoesNotExist()
            compose.onNodeWithText("Choose an output", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Reconnecting never starts audio automatically", substring = true).assertDoesNotExist()
            if (value.connecting || value.verifyingConnection) {
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
            }
        }
        compose.runOnIdle { calls.assertQuiet() }
        val guidance = "Connection could not be verified. The previous action was not replayed."
        // Cached connected=true cards do not hide an explicit verification-needed failure.
        val failure = healthy.copy(recovery = PlaybackConnectionRecovery.VERIFICATION_NEEDED, message = guidance)
        compose.runOnIdle { holder.value = failure }
        compose.onNodeWithText(guidance).assertExists()
        compose.onNodeWithText("Phone", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Retry connection").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, calls.retry)
            assertTrue(calls.selections.isEmpty())
            assertEquals(0, calls.continueHere)
            holder.value = failure.copy(verifyingConnection = true)
        }
        compose.onNodeWithText("Retry connection").assertDoesNotExist()
        compose.runOnIdle { holder.value = failure } // A completed failed verification exposes recovery again.
        compose.onNodeWithText("Retry connection").assertExists()
        compose.runOnIdle { holder.value = healthy } // Successful verification removes it, without another click.
        compose.onNodeWithText("Retry connection").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls.retry); assertTrue(calls.renames.isEmpty()) }
    }

    @Test fun meaningfulSafetyGuidanceSurvivesReadOnlyAndContinueIsASeparateAction() {
        val calls = Calls()
        val guidance = "Playback needs a tap. The previous action was not replayed."
        val holder = content(state(active = "phone", snapshot = PlaybackSnapshot(error = "blocked"))
            .copy(needsTap = true, message = guidance), calls)
        compose.onNodeWithText(guidance).assertExists()
        compose.onNodeWithText("This device · Needs a tap").assertExists()
        compose.onNodeWithText("Retry connection").assertDoesNotExist()
        compose.onNodeWithText("Continue playback here").performClick()
        compose.runOnIdle {
            assertEquals(1, calls.continueHere)
            assertTrue(calls.selections.isEmpty())
            assertEquals(0, calls.retry)
            holder.value = holder.value.copy(deviceId = "")
        }
        compose.onNodeWithText(guidance).assertExists()
        compose.onNodeWithText("Continue playback here").assertDoesNotExist()
        compose.onNodeWithText("View only · Playback transfer is unavailable on this phone.").assertExists()
    }

    @Test fun selectedPendingPlayingPausedAndUnavailableStatusesRemainTruthful() {
        val calls = Calls()
        val holder = content(state(), calls)
        val cases = listOf(
            state(active = "phone", snapshot = PlaybackSnapshot(playing = true)) to "This device · Playing",
            state(active = "phone", snapshot = PlaybackSnapshot()) to "This device · Active output · Paused",
            state(selected = "phone") to "This device · Selected · Available",
            state(selected = "phone").copy(selectionUnconfirmed = true) to "This device · Selecting · Available",
            state(phone.copy(available = false)) to "This device · Unavailable",
        )
        for ((value, status) in cases) {
            compose.runOnIdle { holder.value = value }
            compose.onNodeWithText(status).assertExists()
            compose.onNodeWithText("Retry connection").assertDoesNotExist()
        }
        compose.runOnIdle { calls.assertQuiet() }
    }

    @Test fun renameCancelDoesNotWriteAndSaveDispatchesOneDraftWithoutClaimingConfirmation() {
        val calls = Calls()
        val holder = content(state(), calls)
        compose.onNodeWithContentDescription("Rename Phone").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Discard this draft")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { calls.assertQuiet() }

        compose.onNodeWithContentDescription("Rename Phone").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("  音楽 🎧  ")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Phone", useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            // Validation/trimming and accepted-name persistence belong to the existing coordinator.
            assertEquals(listOf("phone" to "  音楽 🎧  "), calls.renames)
            assertEquals(0, calls.retry)
            assertTrue(calls.selections.isEmpty())
            holder.value = holder.value.copy(message = "Rename was not confirmed. Verify devices before trying again.")
        }
        compose.onNodeWithText("Rename was not confirmed. Verify devices before trying again.").assertExists()
        compose.onNodeWithText("Phone", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals(1, calls.renames.size) }
    }
}
