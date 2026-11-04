package dev.spofify.mobile.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackState
import dev.spofify.mobile.playback.QueueEntry
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Queue-only fixture ownership; expanded-host/Back tests remain with the sheet owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeUpcomingQueueTest {
    @get:Rule val compose = createComposeRule()
    private val track = LibraryItem("track", "duplicate", "Duplicate song", hasAudio = true)

    @Test fun parentSeamHasOneActiveScrollerAndKeepsOccurrenceIndicesIndependentOfHeaders() {
        val controls = QueueRecordingControls()
        val state = PlaybackState(item = track, upcoming = (0 until 30).map {
            QueueEntry("occurrence-$it", track.copy(name = "Queued $it"), 100 - it)
        })
        lateinit var parentState: LazyListState
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                parentState = rememberLazyListState()
                LazyColumn(Modifier.size(400.dp, 500.dp).testTag("queue-parent"), state = parentState) {
                    item(key = "player-header") { Text("Pinned elsewhere", Modifier.height(96.dp)) }
                    nativeUpcomingQueue(state, controls, {}, { _, modifier -> Box(modifier) }, parentState)
                }
            }
        }
        assertEquals(1, compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
        compose.onNodeWithText("Up next · 30").assertIsDisplayed()
        compose.onNodeWithTag("queue-parent").performScrollToIndex(19)
        compose.runOnIdle {
            // One player item + one queue header precede occurrence 17, in the SAME state.
            assertEquals(19, parentState.firstVisibleItemIndex)
            assertEquals("occurrence-17", parentState.layoutInfo.visibleItemsInfo.first().key)
        }
        compose.onNodeWithContentDescription("Queue actions for Queued 17").performClick()
        compose.onNodeWithText("Move up").performClick()
        compose.runOnIdle {
            assertEquals(listOf("occurrence-17" to 16), controls.moves)
            assertEquals(0, controls.playbackCalls)
        }
    }

    @Test fun boundedWrapperPreservesDuplicateActionsAndStableOccurrenceKeysAcrossPublication() {
        val controls = QueueRecordingControls()
        val entries = listOf(QueueEntry("first", track, 19), QueueEntry("second", track, 3))
        val snapshot = mutableStateOf(PlaybackState(item = track, upcoming = entries))
        lateinit var listState: LazyListState
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                listState = rememberLazyListState()
                Column(Modifier.size(400.dp, 500.dp)) {
                    NativeUpcomingQueue(snapshot.value, controls, {}, { _, modifier -> Box(modifier) }, listState = listState)
                }
            }
        }
        compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[1].performClick()
        // A progress-only publication must preserve the keyed occurrence's open menu.
        compose.runOnIdle { snapshot.value = snapshot.value.copy(positionMs = 12_000) }
        compose.onNodeWithText("Move up").performClick()
        compose.onAllNodesWithContentDescription("Play queued song Duplicate song")[1].performClick()
        compose.runOnIdle {
            assertEquals(listOf("second" to 0), controls.moves)
            assertEquals(listOf("second"), controls.selected)
            assertEquals(listOf("first", "second"), listState.layoutInfo.visibleItemsInfo.map { it.key })
            assertEquals(0, controls.playbackCalls)
        }
    }

    @Test fun emptyErrorAndCompactUndoCloseRetainAccessibleActionsWithoutPlayback() {
        val controls = QueueRecordingControls()
        var closes = 0
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Column(Modifier.size(320.dp, 320.dp)) {
                    NativeUpcomingQueue(PlaybackState(item = track, error = "Queue unavailable", canUndoQueueRemoval = true),
                        controls, {}, { _, modifier -> Box(modifier) }, compact = true, onClose = { closes++ })
                }
            }
        }
        compose.onNodeWithText("Queue unavailable").assertIsDisplayed()
        compose.onNodeWithText("No upcoming songs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Upcoming queue actions").performClick()
        compose.onNodeWithText("Reordering turns shuffle off").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Close upcoming queue").performClick()
        compose.runOnIdle {
            assertEquals(1, controls.undos)
            assertEquals(1, closes)
            assertEquals(0, controls.playbackCalls)
            assertTrue(controls.moves.isEmpty())
        }
    }

    private class QueueRecordingControls : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        var playbackCalls = 0
        var undos = 0
        val moves = mutableListOf<Pair<String, Int>>()
        val selected = mutableListOf<String>()
        override fun connect() { playbackCalls++ }
        override fun release() { playbackCalls++ }
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?, shuffle: Boolean,
            startEntryId: String?) { playbackCalls++ }
        override fun toggle() { playbackCalls++ }
        override fun next() { playbackCalls++ }
        override fun previous() { playbackCalls++ }
        override fun select(index: Int) { playbackCalls++ }
        override fun seekTo(positionMs: Long) { playbackCalls++ }
        override fun toggleShuffle() { playbackCalls++ }
        override fun cycleRepeat() { playbackCalls++ }
        override fun stop() { playbackCalls++ }
        override fun moveUpcoming(occurrenceId: String, toIndex: Int) { moves += occurrenceId to toIndex }
        override fun selectOccurrence(occurrenceId: String) { selected += occurrenceId }
        override fun undoQueueRemoval() { undos++ }
    }
}
