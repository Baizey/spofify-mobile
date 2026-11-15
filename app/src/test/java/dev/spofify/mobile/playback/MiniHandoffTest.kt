package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.PlaybackCommandResult
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackOccurrence
import dev.spofify.mobile.core.playback.PlaybackQueueState
import dev.spofify.mobile.core.playback.PlaybackSnapshot
import dev.spofify.mobile.core.playback.PlaybackStatus
import dev.spofify.mobile.core.playback.PlaybackTrack
import dev.spofify.mobile.core.playback.PlaybackView
import org.junit.Assert.*
import org.junit.Test

class MiniHandoffTest {
    private val a = track("A", 1)
    private val b = track("B", 2)
    private val c = track("C", 3)
    private val d = track("D", 4)

    @Test fun releasePresentsCapturedNeighbourUntilActualNewCurrentWinsNotItsNextNeighbour() {
        val source = view(listOf(a, b, c))
        assertEquals(a, miniPresentedTrack(source, null, 1_000))
        val pending = pending(source)
        assertEquals(b, miniPresentedTrack(source, pending, 1_001))
        val authoritative = view(listOf(a, b, c), position = 1, version = 2)
        assertNull(retainedMiniHandoff(pending, authoritative, 1_002))
        assertEquals(b, miniPresentedTrack(authoritative, pending, 1_002))
        assertNotEquals(c, miniPresentedTrack(authoritative, pending, 1_002))
    }

    @Test fun previousHandoffUsesTheGenuinePreviousOccurrence() {
        val source = view(listOf(a, b, c), position = 1)
        val pending = pending(source, step = -1)
        assertEquals(a, pending.incoming)
        assertEquals(a, miniPresentedTrack(source, pending, 1_001))
        val authoritative = view(listOf(a, b, c), position = 0, version = 2)
        assertEquals(a, miniPresentedTrack(authoritative, pending, 1_002))
    }

    @Test fun queueOnlyChangesCannotReplaceActualCenterOrRetargetCapturedPendingIdentity() {
        val source = view(listOf(a, b, c))
        val pending = pending(source)
        val changedQueue = view(listOf(a, d, c), version = 2)
        assertEquals(a, miniPresentedTrack(changedQueue, null, 1_001))
        assertEquals(b, miniPresentedTrack(changedQueue, pending, 1_001))
        assertSame(pending, retainedMiniHandoff(pending, changedQueue, 1_001))
        val actual = view(listOf(a, d, c), position = 1, version = 3)
        assertEquals(d, miniPresentedTrack(actual, pending, 1_002))
    }

    @Test fun correlatedRejectionClearsButAnUnrelatedResultDoesNot() {
        val source = view(listOf(a, b))
        val pending = pending(source)
        val unrelated = source.copy(latestResult = result("other", "rejected"))
        assertEquals(b, miniPresentedTrack(unrelated, pending, 1_001))
        val rejected = source.copy(latestResult = result("command", "rejected"))
        assertNull(retainedMiniHandoff(pending, rejected, 1_001))
        assertEquals(a, miniPresentedTrack(rejected, pending, 1_001))
    }

    @Test fun acceptedCommandIsNotProofOfNewCurrentAndStillHasABoundedLifetime() {
        val source = view(listOf(a, b)).copy(latestResult = result("command", "accepted", "confirmed"))
        val pending = pending(source)
        assertEquals(b, miniPresentedTrack(source, pending, 1_001))
        assertEquals(a, source.current)
        assertFalse(source.isPlaying)
        assertTrue(source.desiredPlaying)
        assertEquals(a, miniPresentedTrack(source, pending, 16_000))
    }

    @Test fun correlatedUncertainOrBlockedMediaResultClearsWithoutRetrying() {
        val source = view(listOf(a, b))
        val pending = pending(source)
        for (execution in listOf("uncertain", "blocked", "media", "superseded")) {
            val failed = source.copy(latestResult = result("command", "accepted", execution))
            assertNull(retainedMiniHandoff(pending, failed, 1_001))
            assertEquals(a, miniPresentedTrack(failed, pending, 1_001))
        }
    }

    @Test fun disconnectReconnectAndSessionRetirementClearPreview() {
        val source = view(listOf(a, b))
        val pending = pending(source)
        for (connection in listOf(PlaybackConnectionState.DISCONNECTED, PlaybackConnectionState.CONNECTING, PlaybackConnectionState.CLOSED)) {
            assertNull(retainedMiniHandoff(pending, source.copy(connection = connection), 1_001))
        }
        assertNull(retainedMiniHandoff(pending, source.copy(connectionId = "new connection"), 1_001))
        val otherAccount = source.copy(sessionId = "new session", snapshot = source.snapshot!!.copy(sessionId = "new session"))
        assertNull(retainedMiniHandoff(pending, otherAccount, 1_001))
    }

    @Test fun selectedOutputAndOutputLeaseRetirementClearPreviewEvenWhenTrackIsUnchanged() {
        val source = view(listOf(a, b))
        val pending = pending(source)
        val snapshot = source.snapshot!!
        for (retired in listOf(
            snapshot.copy(selectedDeviceId = "other device"),
            snapshot.copy(selectedConnectionId = "other output connection"),
            snapshot.copy(outputVersion = 2),
        )) assertNull(retainedMiniHandoff(pending, source.copy(snapshot = retired), 1_001))
    }

    @Test fun anotherOccurrenceOfTheSameTrackImmediatelyWinsOverPreview() {
        val source = view(listOf(a, b))
        val pending = pending(source)
        val snapshot = requireNotNull(source.snapshot)
        val sameTrackNewOccurrence = source.copy(snapshot = snapshot.copy(
            state = snapshot.state.copy(occurrence = snapshot.state.occurrence!!.copy(eventId = "new event"))))
        assertEquals(a, miniPresentedTrack(sameTrackNewOccurrence, pending, 1_001))
        assertNull(retainedMiniHandoff(pending, sameTrackNewOccurrence, 1_001))
        val sameTrackNewKey = a.copy(key = 42)
        assertNull(retainedMiniHandoff(pending, view(listOf(sameTrackNewKey, b)), 1_001))
    }

    @Test fun timeoutBoundaryAndClockRetirementNeverReplayOrRetarget() {
        val source = view(listOf(a, b))
        val pending = pending(source)
        assertEquals(b, miniPresentedTrack(source, pending, 15_999))
        assertEquals(a, miniPresentedTrack(source, pending, 16_000))
        assertNull(retainedMiniHandoff(pending, source, 999))
    }

    @Test fun queuedNeighbourChangeBeforeDispatchInvalidatesCapturedSwipeSelection() {
        val source = view(listOf(a, b, c))
        val selection = miniSwipeSelection(source, 1)!!
        assertTrue(selection.isCurrent(source))
        val queueChanged = view(listOf(a, d, c), version = 2)
        assertFalse(selection.isCurrent(queueChanged))
        assertEquals(b, selection.incoming)
        assertEquals(d, miniSwipeSelection(queueChanged, 1)?.incoming)
    }

    @Test fun absentOrUnconfirmedNeighboursCannotCreateSelections() {
        val source = view(listOf(a))
        assertNull(miniSwipeSelection(source, -1))
        assertNull(miniSwipeSelection(source, 1))
        assertNull(miniSwipeSelection(source, 0))
        assertNull(miniSwipeSelection(source.copy(connection = PlaybackConnectionState.DISCONNECTED), 1))
        val inconsistent = view(listOf(a, b)).let { view ->
            val snapshot = requireNotNull(view.snapshot)
            view.copy(snapshot = snapshot.copy(state = snapshot.state.copy(
                occurrence = snapshot.state.occurrence!!.copy(trackId = "not current"))))
        }
        assertNull(miniSwipeSelection(inconsistent, 1))
    }

    private fun pending(source: PlaybackView, step: Int = 1): MiniHandoff {
        val selection = requireNotNull(miniSwipeSelection(source, step))
        return MiniHandoff(selection.source, selection.incoming, "command", 1_000)
    }

    private fun result(id: String, outcome: String, execution: String = "unchanged") =
        PlaybackCommandResult(id, outcome, "test", 2, 0, "none", execution, true)

    private fun view(queue: List<PlaybackTrack>, position: Int = 0, version: Long = 1): PlaybackView {
        val current = queue[position]
        val occurrence = PlaybackOccurrence(current.key, current.id, "event-${current.key}", 0.0, false)
        val status = PlaybackStatus(1, version, 1, occurrence.eventId, false, 0.0, 60.0, false, false, false, "")
        val snapshot = PlaybackSnapshot("session", version, 1,
            PlaybackQueueState(queue = queue, position = position, desiredPlaying = true, occurrence = occurrence),
            status, emptyList(), "device", "output connection", null, 0)
        return PlaybackView(PlaybackConnectionState.READY, "session", "connection", "phone", snapshot, status)
    }

    private fun track(id: String, key: Long) = PlaybackTrack(id, id, emptyList(), "Album", "album", 60.0, key, key, "now")
}
