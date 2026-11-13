package dev.spofify.mobile.feature.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryPlaybackDevice
import dev.spofify.mobile.playback.testLibrarySnapshot
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presentation-only hosts, not native rendering or executor evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
class PlaybackDevicesSheetTest {
    @get:Rule val compose = createComposeRule()
    private val phone = LibraryPlaybackDevice("phone", "Phone", true, true)
    private val room = LibraryPlaybackDevice("room", "Living room 🎧", true, true)
    private class Calls {
        var dismiss = 0
        var retry = 0
        var continueHere = 0
        val selections = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        fun assertQuiet() {
            assertEquals(0, dismiss); assertEquals(0, retry); assertEquals(0, continueHere)
            assertTrue(selections.isEmpty()); assertTrue(renames.isEmpty())
        }
    }
    private fun state(devices: List<LibraryPlaybackDevice> = listOf(phone, room), selected: String = "phone") =
        PlaybackDevicesState(deviceId = "phone", connectionId = "socket-phone", connected = true,
            snapshot = testLibrarySnapshot(devices, selected))
    private fun content(initial: PlaybackDevicesState, calls: Calls) = mutableStateOf(initial).also { holder ->
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Column(Modifier.width(400.dp).height(800.dp)) {
                    PlaybackDevicesContent(holder.value, { calls.dismiss++ }, { calls.retry++ },
                        { calls.continueHere++ }, { calls.selections += it }, { id, name -> calls.renames += id to name })
                }
            }
        }
    }
    @Test fun displayAndPendingFramesNeverSelectRenameRetryOrStartMusic() {
        val calls = Calls()
        val initial = state()
        val holder = content(initial, calls)
        for (token in 1L..12L) compose.runOnIdle {
            holder.value = initial.copy(pendingSelection = PlaybackDeviceSelection("room", token),
                message = "Selecting output…", messagePurpose = PlaybackDevicesMessagePurpose.PROGRESS)
        }
        compose.onNodeWithText("Selecting output…").assertDoesNotExist()
        compose.onNodeWithTag("Pending output", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Play from here").assertDoesNotExist()
        compose.runOnIdle { calls.assertQuiet() }
    }
    @Test fun rowSelectAndRenameAreIndependentTargetsAndOnlyDispatchExplicitly() {
        val calls = Calls()
        content(state(), calls)
        val row = compose.onNodeWithContentDescription("Select output on Living room 🎧")
        val rename = compose.onNodeWithContentDescription("Rename Living room 🎧")
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val renameBounds = rename.fetchSemanticsNode().boundsInRoot
        assertTrue(rowBounds.height >= 48)
        assertTrue(renameBounds.width >= 48 && renameBounds.height >= 48)
        rename.performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Study 🎵")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertTrue(calls.selections.isEmpty())
            assertEquals(listOf("room" to "Study 🎵"), calls.renames)
        }
        row.performClick()
        compose.runOnIdle { assertEquals(listOf("room"), calls.selections) }
    }
    @Test fun pendingOutputDoesNotPretendToBeSelectedOrPlayingAndDoesNotMoveTargets() {
        val calls = Calls()
        val initial = state()
        val holder = content(initial, calls)
        val row = compose.onNodeWithContentDescription("Select output on Living room 🎧")
        val before = row.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { holder.value = initial.copy(pendingSelection = PlaybackDeviceSelection("room", 1)) }
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
        assertFalse(row.fetchSemanticsNode().config[SemanticsProperties.Selected])
        compose.onNodeWithText("Playing", substring = true).assertDoesNotExist()
        compose.runOnIdle { calls.assertQuiet() }
    }
    @Test fun pendingUpdatesPreserveRenameDraftAndFocus() {
        val calls = Calls()
        val initial = state()
        val holder = content(initial, calls)
        compose.onNodeWithContentDescription("Rename Phone").performClick()
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick().performTextReplacement("Keep this draft 🎧")
        for (token in 1L..8L) compose.runOnIdle {
            holder.value = initial.copy(pendingSelection = PlaybackDeviceSelection("room", token))
        }
        editor.assertIsFocused().assertTextContains("Keep this draft 🎧")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { calls.assertQuiet() }
    }
    @Test fun scrollOffsetSurvivesQuietAndPendingPublications() {
        val calls = Calls()
        val initial = state((0..39).map { LibraryPlaybackDevice("room-$it", "Room $it — 音楽 🎧", true, true) }, "")
        val holder = content(initial, calls)
        val list = compose.onNodeWithTag("Playback device list")
        list.performTouchInput { swipeUp(durationMillis = 700) }
        compose.waitForIdle()
        val before = list.fetchSemanticsNode()
        val offset = before.config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(offset > 0)
        for (token in 1L..8L) {
            compose.runOnIdle { holder.value = initial.copy(pendingSelection = PlaybackDeviceSelection("room-35", token)) }
            assertEquals(before.boundsInRoot, list.fetchSemanticsNode().boundsInRoot)
            assertEquals(offset, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        }
        compose.runOnIdle { calls.assertQuiet() }
    }
    @Test fun onlyConciseActionableRecoveryIsShown() {
        val calls = Calls()
        content(state().copy(needsTap = true, message = "Playback needs a tap."), calls)
        compose.onNodeWithText("Playback needs a tap.").assertExists()
        compose.onNodeWithText("Continue playback here").performClick()
        compose.runOnIdle { assertEquals(1, calls.continueHere); assertTrue(calls.selections.isEmpty()) }
    }
}
