package dev.spofify.mobile.playback

import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackQueueCommandsTest {
    @Test fun guardedLocalRemovalPreservesPausedCurrentOtherDuplicateAndRejectsStaleKey() {
        LocalQueueServiceFixture().use { fixture ->
            fixture.replace(listOf("first", "duplicate", "last").map { fixture.row(it, id = "same") })
            fixture.output.seekTo(17_250)
            assertTrue(PlaybackService.localTransport(LocalPlaybackCommand.transport("pause")))
            val before = fixture.snapshot()
            val future = PlaybackService.upcomingEntries()
            val key = requireNotNull(PlaybackService.interactionKey())
            PlaybackService.removeQueued(PlaybackInteractionKey(), future.last().occurrenceId) { true }
            PlaybackService.removeQueued(key, future.last().occurrenceId) { false }
            PlaybackService.removeQueued(key, requireNotNull(PlaybackMedia.occurrence(fixture.output.currentMediaItem))) { true }
            assertEquals(before, fixture.snapshot())
            PlaybackService.removeQueued(key, future.last().occurrenceId) { true }
            val after = fixture.snapshot()
            assertEquals(before.queue.dropLast(1), after.queue)
            assertEquals(before.queue[before.position], after.queue[after.position])
            assertEquals(before.elapsed, after.elapsed, 0.0)
            assertEquals(before.occurrence, after.occurrence)
            fixture.assertSilent()
        }
    }

    @Test fun localUndoRestoresExactDuplicateOccurrenceAndOriginalTraversalWithoutStarting() {
        LocalQueueServiceFixture().use { fixture ->
            fixture.replace(listOf("first", "duplicate", "last").map { fixture.row(it, id = "same") })
            assertTrue(PlaybackService.localTransport(LocalPlaybackCommand.transport("pause")))
            val before = fixture.snapshot()
            val upcoming = PlaybackService.upcomingEntries().map { it.occurrenceId }
            PlaybackService.removeQueued(requireNotNull(PlaybackService.interactionKey()), upcoming.last()) { true }
            assertEquals(upcoming.dropLast(1), PlaybackService.upcomingEntries().map { it.occurrenceId })
            assertTrue(PlaybackService.canUndoRemoval())
            PlaybackService.undoQueuedRemoval()
            assertEquals(upcoming, PlaybackService.upcomingEntries().map { it.occurrenceId })
            assertEquals(before.queue, fixture.snapshot().queue)
            assertEquals(before.position, fixture.snapshot().position)
            assertEquals(before.elapsed, fixture.snapshot().elapsed, 0.0)
            assertEquals(before.occurrence, fixture.snapshot().occurrence)
            fixture.assertSilent()
        }
    }

    private fun OnlineQueueConnectionFixture.next(results: MutableList<PlaybackActionResult>,
        current: () -> Boolean = { true }) = connection.queueIntent(
        LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef("remote-next")), client,
        PlaybackListAction.PLAY_NEXT, connection.state.value.interactionKey, owner.captureBinding(),
        isCurrent = current, onResult = results::add)

    @Test fun explicitRemotePlayNextIsOneIdentifierCommandAndWaitsForFinalReceipt() = runTest {
        OnlineQueueConnectionFixture(this).use { fixture ->
            val before = fixture.snapshot
            val results = mutableListOf<PlaybackActionResult>()
            fixture.next(results)
            assertEquals(1, fixture.commands.size)
            val sent = fixture.commands.single()
            val command = sent.command as LibraryPlaybackCommand.QueueIntent
            assertEquals("next", command.intent)
            assertEquals("remote-next", (command.selection as LibraryPlaybackSelection.Track).track.id)
            assertEquals(before.stateVersion, sent.expectedStateVersion)
            assertTrue(results.isEmpty())
            fixture.result(outcome = "resolving", placement = "none", count = 0, final = false)
            assertTrue(results.isEmpty())
            fixture.result()
            assertEquals(PlaybackActionStatus.ADDED, results.single().status)
            assertEquals(1, results.single().acceptedCount)
            assertEquals(before, fixture.snapshot)
            fixture.result() // Duplicate receipt cannot invoke the callback twice.
            advanceTimeBy(20_001); runCurrent()
            assertEquals(1, results.size)
            assertEquals(1, fixture.commands.size)
            fixture.assertNoHttpPlayback()
        }
    }

    @Test fun rejectedOrUnknownRemoteOutcomeNeverReportsAddedAndNeverRetries() = runTest {
        OnlineQueueConnectionFixture(this).use { fixture ->
            val rejected = mutableListOf<PlaybackActionResult>()
            fixture.next(rejected)
            fixture.result(outcome = "rejected", code = "unavailable", count = 0, placement = "none")
            assertEquals(PlaybackActionStatus.UNAVAILABLE, rejected.single().status)
            val unknown = mutableListOf<PlaybackActionResult>()
            fixture.next(unknown)
            advanceTimeBy(19_999); runCurrent()
            assertTrue(unknown.isEmpty())
            advanceTimeBy(1); runCurrent()
            assertEquals(PlaybackActionStatus.UNAVAILABLE, unknown.single().status)
            fixture.result() // Late acceptance is not a new dispatch or a successor callback.
            assertEquals(1, unknown.size)
            assertEquals(2, fixture.commands.size)
            fixture.assertNoHttpPlayback()
        }
    }

    @Test fun socketLossCompletesOldObserverOnceAndCannotAuthorizeSuccessorCallback() = runTest {
        OnlineQueueConnectionFixture(this).use { fixture ->
            val first = mutableListOf<PlaybackActionResult>()
            fixture.next(first)
            fixture.owner.authLost(); runCurrent()
            assertEquals(PlaybackActionStatus.UNAVAILABLE, first.single().status)
            advanceTimeBy(20_001); runCurrent()
            assertEquals(1, first.size)
            val second = mutableListOf<PlaybackActionResult>()
            fixture.next(second)
            assertEquals(PlaybackActionStatus.STALE, second.single().status)
            assertEquals(1, fixture.commands.size)
        }
    }

    @Test fun staleOriginOrStructuralBindingIsRefusedRatherThanRefreshedBeforeDispatch() = runTest {
        OnlineQueueConnectionFixture(this).use { fixture ->
            val results = mutableListOf<PlaybackActionResult>()
            fixture.next(results) { false }
            assertEquals(PlaybackActionStatus.STALE, results.single().status)
            val binding = requireNotNull(fixture.owner.captureBinding())
            val key = fixture.connection.state.value.interactionKey
            fixture.publish(fixture.snapshot.copy(stateVersion = 2,
                playback = fixture.snapshot.playback.copy(stateVersion = 2)))
            fixture.connection.queueIntent(LibraryPlaybackSelection.Occurrence(91), fixture.client,
                PlaybackListAction.PLAY_NEXT, key, binding, onResult = results::add)
            assertEquals(PlaybackActionStatus.STALE, results.last().status)
            assertTrue(fixture.commands.isEmpty())
            fixture.next(results)
            fixture.result()
            assertEquals(PlaybackActionStatus.ADDED, results.last().status)
            assertEquals(1, fixture.commands.size)
        }
    }

    @Test fun exactRemoteOccurrenceNextUsesServerKeyAndRetainsCurrentUntilAuthoritativeState() = runTest {
        OnlineQueueConnectionFixture(this).use { fixture ->
            val before = fixture.snapshot
            val key = requireNotNull(fixture.connection.state.value.interactionKey)
            val selected = before.state.queue.last()
            val results = mutableListOf<PlaybackActionResult>()
            fixture.connection.playNextOccurrence(key, "wire-${selected.key}", onResult = results::add)
            assertEquals(LibraryPlaybackCommand.QueueIntent("next", LibraryPlaybackSelection.Occurrence(91)),
                fixture.commands.single().command)
            assertTrue(results.isEmpty())
            fixture.result()
            assertEquals(PlaybackActionStatus.ADDED, results.single().status)
            assertEquals(before, fixture.snapshot)
            val reordered = listOf(before.state.queue[0], selected, before.state.queue[1])
            fixture.publish(before.copy(stateVersion = 2, state = before.state.copy(queue = reordered, highKeys = listOf(91)),
                playback = before.playback.copy(stateVersion = 2)))
            assertEquals(7L, fixture.snapshot.state.queue[fixture.snapshot.state.position].key)
            assertEquals(before.state.occurrence, fixture.snapshot.state.occurrence)
            assertEquals(before.playback.elapsed, fixture.snapshot.playback.elapsed, 0.0)
            assertFalse(fixture.snapshot.playback.isPlaying)
            fixture.connection.playNextOccurrence(key, "wire-${selected.key}", onResult = results::add)
            assertEquals(PlaybackActionStatus.STALE, results.last().status)
            assertEquals(1, fixture.commands.size)
            fixture.assertNoHttpPlayback()
        }
    }

    @Test fun highOnUnselectedOutputSubmitsOnlyQueueIntentNeverSelectOrStart() = runTest {
        OnlineQueueConnectionFixture(this).use { fixture ->
            fixture.publish(fixture.snapshot.copy(selectedDeviceId = "", selectedConnectionId = ""))
            val results = mutableListOf<PlaybackActionResult>()
            fixture.connection.queueIntent(LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef("track")), fixture.client,
                PlaybackListAction.QUEUE_HIGH, fixture.connection.state.value.interactionKey, fixture.owner.captureBinding(),
                onResult = results::add)
            assertEquals("high", (fixture.commands.single().command as LibraryPlaybackCommand.QueueIntent).intent)
            fixture.result(placement = "high")
            assertEquals(PlaybackActionStatus.ADDED, results.single().status)
            assertEquals("", fixture.snapshot.selectedConnectionId)
            assertFalse(fixture.snapshot.playback.isPlaying)
            assertEquals(1, fixture.commands.size)
        }
    }
}
