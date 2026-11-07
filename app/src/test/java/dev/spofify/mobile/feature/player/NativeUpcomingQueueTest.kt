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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackActionResult
import dev.spofify.mobile.playback.PlaybackActionStatus
import dev.spofify.mobile.playback.PlaybackListAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertFalse
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
                val actionIsCurrent = rememberUpcomingQueueActionIsCurrent(controls)
                LazyColumn(Modifier.size(400.dp, 500.dp).testTag("queue-parent"), state = parentState) {
                    item(key = "player-header") { Text("Pinned elsewhere", Modifier.height(96.dp)) }
                    nativeUpcomingQueue(state, controls, {}, { _, modifier -> Box(modifier) }, parentState,
                        actionIsCurrent = actionIsCurrent)
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
        val snapshot = mutableStateOf(PlaybackState(item = track, upcoming = entries, interactionKey = PlaybackInteractionKey()))
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
        compose.onAllNodesWithContentDescription("Play queued song Duplicate song").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[1].performClick()
        compose.onNodeWithText("Play now").performClick()
        compose.runOnIdle {
            assertEquals(listOf("second" to 0), controls.moves)
            assertEquals(listOf("second"), controls.selected)
            assertEquals(listOf("first", "second"), listState.layoutInfo.visibleItemsInfo.map { it.key })
            assertEquals(0, controls.playbackCalls)
        }
    }

    @Test fun bothUpcomingPresentationsOfferThreeExactKeyIntentsForDuplicateOccurrences() {
        val controls = QueueRecordingControls()
        val key = PlaybackInteractionKey()
        val compact = mutableStateOf(false)
        val state = PlaybackState(item = track, interactionKey = key,
            upcoming = listOf(QueueEntry("first", track, 1), QueueEntry("second", track, 2)))
        compose.setContent { MaterialTheme { Column(Modifier.size(400.dp, 600.dp)) {
            NativeUpcomingQueue(state, controls, {}, { _, modifier -> Box(modifier) }, compact = compact.value)
        } } }
        for (presentation in listOf(false, true)) {
            compose.runOnIdle { compact.value = presentation; controls.intents.clear() }
            for ((label, action) in listOf("Queue" to PlaybackListAction.QUEUE_HIGH, "Play next" to PlaybackListAction.PLAY_NEXT,
                "Play now" to PlaybackListAction.PLAY_NOW)) {
                compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[1].performClick()
                compose.onAllNodesWithText("Queue").assertCountEquals(1)
                compose.onNodeWithText("Queue Low").assertDoesNotExist()
                compose.onNodeWithText("Queue High").assertDoesNotExist()
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.runOnIdle { assertEquals(Triple(key, "second", action), controls.intents.last()) }
            }
            compose.runOnIdle { assertEquals(3, controls.intents.size); assertEquals(0, controls.playbackCalls) }
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

    @Test fun bothPresentationsDispatchExactOccurrenceOnceOnlyOnRightReleaseAndExposeMoreAlternative() {
        val controls = QueueRecordingControls()
        val compact = mutableStateOf(false)
        val key = PlaybackInteractionKey()
        val state = PlaybackState(item = track, isPlaying = true, interactionKey = key,
            upcoming = listOf(QueueEntry("exact-duplicate", track, 4)))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(400.dp, 500.dp)) {
                    NativeUpcomingQueue(state, controls, {}, { _, modifier -> Box(modifier) }, compact = compact.value)
                }
            }
        }
        for (presentation in listOf(false, true)) {
            compose.runOnIdle { compact.value = presentation; controls.playNexts.clear(); controls.contextual.clear() }
            compose.onAllNodesWithContentDescription("Play queued song Duplicate song").assertCountEquals(0)
            compose.onNodeWithText("Duplicate song").performTouchInput {
                // This is in the identity target, not More or the dedicated reorder handle.
                down(Offset(60f, 20f)); moveTo(Offset(150f, 20f))
            }
            compose.runOnIdle { assertTrue(controls.playNexts.isEmpty()) }
            compose.onNodeWithText("Duplicate song").performTouchInput { up() }
            compose.runOnIdle { assertEquals(listOf(key to "exact-duplicate"), controls.playNexts) }
            compose.onNodeWithContentDescription("Queue actions for Duplicate song").performClick()
            compose.onNodeWithText("Queue").performClick()
            compose.runOnIdle {
                assertEquals(listOf("exact-duplicate"), controls.contextual)
                assertEquals(listOf(key to "exact-duplicate"), controls.playNexts)
                assertTrue(controls.selected.isEmpty())
                assertEquals(0, controls.playbackCalls)
            }
        }
    }

    @Test fun bothPresentationsUseExplicitPlayNextForMoreAndAccessibilityWithoutReorderFallback() {
        val controls = QueueRecordingControls()
        val compact = mutableStateOf(false)
        val key = PlaybackInteractionKey()
        val snapshot = mutableStateOf(PlaybackState(item = track, interactionKey = key,
            upcoming = listOf(QueueEntry("exact-duplicate", track, 4))))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(400.dp, 500.dp)) {
                    NativeUpcomingQueue(snapshot.value, controls, {}, { _, modifier -> Box(modifier) }, compact = compact.value)
                }
            }
        }
        fun actions() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        for (presentation in listOf(false, true)) {
            compose.runOnIdle {
                compact.value = presentation
                snapshot.value = snapshot.value.copy(interactionKey = key,
                    upcoming = listOf(QueueEntry("exact-duplicate", track, 4)))
                controls.playNexts.clear()
            }
            // Index zero remains executable: promotion can matter without a displayed move.
            compose.onNodeWithContentDescription("Queue actions for Duplicate song").performClick()
            compose.onNodeWithText("Play next").assertIsEnabled().performClick()
            val accessible = actions().single { it.label == "Play next" }
            compose.runOnIdle {
                assertTrue(accessible.action())
                assertEquals(listOf(key to "exact-duplicate", key to "exact-duplicate"), controls.playNexts)
                assertTrue("Explicit Play next must not use arbitrary reorder", controls.moves.isEmpty())
                assertTrue(controls.selected.isEmpty())
                assertEquals(0, controls.playbackCalls)
                snapshot.value = snapshot.value.copy(interactionKey = null)
            }
            assertTrue(actions().none { it.label == "Play next" })
            compose.onNodeWithContentDescription("Queue actions for Duplicate song").performClick()
            compose.onNodeWithText("Play next").assertIsNotEnabled()
            // Opening a menu and losing availability cannot create a fallback mutation.
            compose.runOnIdle {
                snapshot.value = snapshot.value.copy(interactionKey = key,
                    upcoming = listOf(QueueEntry("exact-duplicate", track.copy(hasAudio = false), 4)))
            }
            compose.onNodeWithText("Play next").assertDoesNotExist() // Changed binding dismissed the old menu.
            compose.onNodeWithContentDescription("Queue actions for Duplicate song").performClick()
            compose.onNodeWithText("Play next").assertIsNotEnabled()
            compose.onNodeWithText("Remove from queue").performClick() // Dismiss without a playback action.
            assertTrue(actions().none { it.label == "Play next" })
            compose.runOnIdle { assertEquals(2, controls.playNexts.size); assertTrue(controls.moves.isEmpty()) }
        }
    }

    @Test fun bothPresentationsLeftRemovesOnlySecondDuplicateAndHeldStaleOrVerticalDoesNothing() {
        val controls = QueueRecordingControls()
        val compact = mutableStateOf(false)
        val key = PlaybackInteractionKey()
        val snapshot = mutableStateOf(PlaybackState(item = track, isPlaying = false,
            positionMs = 42_000, interactionKey = key,
            upcoming = listOf(QueueEntry("first", track, 4), QueueEntry("second", track, 7))))
        compose.setContent { MaterialTheme { Column(Modifier.size(400.dp, 500.dp)) {
            NativeUpcomingQueue(snapshot.value, controls, {}, { _, modifier -> Box(modifier) }, compact = compact.value)
        } } }
        for (presentation in listOf(false, true)) {
            compose.runOnIdle {
                compact.value = presentation
                snapshot.value = snapshot.value.copy(interactionKey = key)
                controls.removed.clear()
            }
            fun duplicate() = compose.onAllNodesWithText("Duplicate song")[1]
            duplicate().performTouchInput { down(Offset(140f, 20f)); moveTo(Offset(40f, 20f)) }
            compose.runOnIdle { assertTrue(controls.removed.isEmpty()) }
            duplicate().performTouchInput { up() }
            compose.runOnIdle {
                assertEquals(listOf(key to "second"), controls.removed)
                assertTrue(controls.playNexts.isEmpty())
                assertEquals(42_000L, snapshot.value.positionMs)
                assertFalse(snapshot.value.isPlaying)
                assertEquals(0, controls.playbackCalls)
                controls.removed.clear()
            }
            // Queue/session/output binding replacement while held cannot borrow the new key.
            duplicate().performTouchInput { down(Offset(140f, 20f)); moveTo(Offset(40f, 20f)) }
            compose.runOnIdle { snapshot.value = snapshot.value.copy(interactionKey = PlaybackInteractionKey()) }
            duplicate().performTouchInput { up() }
            duplicate().performTouchInput {
                down(Offset(140f, 20f)); moveBy(Offset(1f, -30f)); moveBy(Offset(-100f, 0f)); up()
            }
            duplicate().performTouchInput {
                down(Offset(140f, 20f)); moveBy(Offset(-90f, 0f))
                down(1, Offset(160f, 20f)); up(1); up()
            }
            compose.runOnIdle {
                assertTrue(controls.removed.isEmpty())
                assertTrue(controls.playNexts.isEmpty())
                assertEquals(0, controls.playbackCalls)
            }
        }
    }

    @Test fun normalMoreDismissesOnBindingReplacementRatherThanRebinding() = checkMenuBinding(compact = false)

    @Test fun compactMoreDismissesOnBindingReplacementRatherThanRebinding() = checkMenuBinding(compact = true)

    private fun checkMenuBinding(compact: Boolean) {
        val controls = QueueRecordingControls()
        val initialKey = PlaybackInteractionKey()
        val initial = PlaybackState(item = track, isPlaying = true, interactionKey = initialKey,
            upcoming = listOf(QueueEntry("same-occurrence", track, 4)))
        val snapshot = mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(400.dp, 500.dp)) {
                    NativeUpcomingQueue(snapshot.value, controls, {}, { _, modifier -> Box(modifier) }, compact = compact)
                }
            }
        }
        compose.onNodeWithContentDescription("Queue actions for Duplicate song").performClick()
        compose.runOnIdle { snapshot.value = initial.copy(positionMs = 1_000) }
        compose.onNodeWithText("Queue").assertIsDisplayed()
        compose.onNodeWithText("Play next").assertIsDisplayed()
        compose.runOnIdle { snapshot.value = initial.copy(interactionKey = PlaybackInteractionKey()) }
        compose.onNodeWithText("Queue").assertDoesNotExist()
        compose.onNodeWithText("Play next").assertDoesNotExist()
        // An equal-valued queue returning under another observed binding cannot revive More.
        compose.runOnIdle { snapshot.value = initial.copy(interactionKey = PlaybackInteractionKey()) }
        compose.onNodeWithText("Play next").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(controls.contextual.isEmpty())
            assertTrue(controls.playNexts.isEmpty())
            assertTrue(controls.selected.isEmpty())
            assertTrue(controls.moves.isEmpty())
        }
        // A deliberate reopen belongs to the new binding and remains executable.
        compose.onNodeWithContentDescription("Queue actions for Duplicate song").performClick()
        compose.onNodeWithText("Play next").performClick()
        compose.runOnIdle { assertEquals(snapshot.value.interactionKey to "same-occurrence", controls.playNexts.single()) }
    }

    @Test fun normalWrapperAcceptedIntentSurvivesSelfRowRemovalButNotQueueDisposal() =
        checkAcceptedIntentLifetime(compact = false, parentHost = false)

    @Test fun compactWrapperAcceptedIntentSurvivesSelfRowRemovalButNotQueueDisposal() =
        checkAcceptedIntentLifetime(compact = true, parentHost = false)

    @Test fun normalParentAcceptedIntentSurvivesSelfRowRemovalButNotQueueDisposal() =
        checkAcceptedIntentLifetime(compact = false, parentHost = true)

    @Test fun compactParentAcceptedIntentSurvivesSelfRowRemovalButNotQueueDisposal() =
        checkAcceptedIntentLifetime(compact = true, parentHost = true)

    private fun checkAcceptedIntentLifetime(compact: Boolean, parentHost: Boolean) {
        val controls = QueueRecordingControls()
        val key = PlaybackInteractionKey()
        val selected = track.copy(name = "Selected queued song")
        val shown = mutableStateOf(true)
        val snapshot = mutableStateOf(PlaybackState(item = track, isPlaying = false, playWhenReady = false,
            interactionKey = key, upcoming = listOf(QueueEntry("selected-occurrence", selected, 4))))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(400.dp, 500.dp)) {
                    if (shown.value) {
                        if (parentHost) {
                            // Lifetime belongs to the Compose queue host, outside the lazy builder
                            // and all lazy items, just as in the production sheet parent seam.
                            val actionIsCurrent = rememberUpcomingQueueActionIsCurrent(controls)
                            val listState = rememberLazyListState()
                            LazyColumn(Modifier.size(400.dp, 500.dp), state = listState) {
                                nativeUpcomingQueue(snapshot.value, controls, {}, { _, modifier -> Box(modifier) },
                                    listState, actionIsCurrent = actionIsCurrent, compact = compact)
                            }
                        } else {
                            NativeUpcomingQueue(snapshot.value, controls, {}, { _, modifier -> Box(modifier) }, compact = compact)
                        }
                    }
                }
            }
        }
        val stalePlayNext = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Play next" }
        compose.onNodeWithText("Selected queued song").performTouchInput {
            down(Offset(60f, 20f)); moveTo(Offset(150f, 20f))
        }
        compose.runOnIdle { assertTrue("Held idle swipe is not yet accepted", controls.playNextPredicates.isEmpty()) }
        compose.onNodeWithText("Selected queued song").performTouchInput { up() }
        lateinit var acceptedIsCurrent: () -> Boolean
        compose.runOnIdle {
            assertEquals(listOf(key to "selected-occurrence"), controls.playNexts)
            assertEquals(1, controls.playNextPredicates.size)
            acceptedIsCurrent = controls.playNextPredicates.single()
            assertTrue(acceptedIsCurrent())
            // A real idle select/seek removes this exact occurrence from upcoming and publishes
            // a new key. That accepted mutation must not invalidate the queue-owned predicate.
            snapshot.value = snapshot.value.copy(item = selected, upcoming = emptyList(),
                interactionKey = PlaybackInteractionKey())
        }
        compose.onNodeWithText("No upcoming songs").assertIsDisplayed()
        compose.onNodeWithText("Selected queued song").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("Accepted intent survives its own row disposal and key publication", acceptedIsCurrent())
            stalePlayNext.action()
            assertEquals("Disposed row accessibility must not invoke Play next", 1, controls.playNexts.size)
            assertEquals("No stale request may reach the fake boundary", 1, controls.playNextPredicates.size)
            assertTrue(controls.moves.isEmpty())
            assertTrue(controls.selected.isEmpty())
            assertEquals(0, controls.playbackCalls)
            shown.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("Disposing the queue/sheet invalidates accepted caller lifetime", acceptedIsCurrent()) }
    }

    private class QueueRecordingControls : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        var playbackCalls = 0
        var undos = 0
        val removed = mutableListOf<Pair<PlaybackInteractionKey, String>>()
        override fun removeUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, isCurrent: () -> Boolean) {
            if (isCurrent()) removed += expectedKey to occurrenceId
        }
        val moves = mutableListOf<Pair<String, Int>>()
        val selected = mutableListOf<String>()
        val contextual = mutableListOf<String>()
        val contextualPredicates = mutableListOf<() -> Boolean>()
        val playNexts = mutableListOf<Pair<PlaybackInteractionKey, String>>()
        val playNextPredicates = mutableListOf<() -> Boolean>()
        val intents = mutableListOf<Triple<PlaybackInteractionKey, String, PlaybackListAction>>()
        override fun queueIntentOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
            action: PlaybackListAction, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            intents += Triple(expectedKey, occurrenceId, action)
            when (action) {
                PlaybackListAction.PLAY_NEXT -> playNextOccurrence(expectedKey, occurrenceId, isCurrent, onResult)
                PlaybackListAction.PLAY_NOW -> { if (isCurrent()) selected += occurrenceId; onResult(PlaybackActionResult(PlaybackActionStatus.NO_OP)) }
                else -> contextualOccurrence(expectedKey, occurrenceId, isCurrent, onResult)
            }
        }
        override fun playNextOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
            isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            playNextPredicates += isCurrent
            assertTrue("Play next retains live queue-host authority", isCurrent())
            playNexts += expectedKey to occurrenceId
            onResult(PlaybackActionResult(PlaybackActionStatus.NO_OP))
        }
        override fun contextualOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
            isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            contextualPredicates += isCurrent
            if (isCurrent()) contextual += occurrenceId
            onResult(PlaybackActionResult(PlaybackActionStatus.NO_OP))
        }
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
