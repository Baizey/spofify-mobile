package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.*
import org.junit.Assert.*
import org.junit.Test

class FullPlayerSelectionTest {
    @Test fun clockSamplesAndTransportChangesLeaveTheSongAndContentSelectionAlone() {
        val view = view()
        val snapshot = requireNotNull(view.snapshot)
        val state = snapshot.state
        val clock = snapshot.playback.copy(elapsed = 15.5, playbackSequence = 12, isPlaying = true)
        val changed = state.copy(elapsed = 15.5, desiredPlaying = true, shuffle = true, repeat = "all")
        val updated = view.copy(snapshot = snapshot.copy(stateVersion = 2, state = changed, playback = clock), playback = clock)

        assertEquals(PlayerPresentation(view).trackSelection(), PlayerPresentation(updated).trackSelection())
        assertEquals(PlayerPresentation(view).gestureContext, PlayerPresentation(updated).gestureContext)
    }

    @Test fun sameSongOnANewOccurrenceOrOutputGetsNewMetadataBinding() {
        val view = view()
        val snapshot = requireNotNull(view.snapshot)
        val state = snapshot.state
        val initial = PlayerPresentation(view).trackSelection()
        fun withSnapshot(changed: PlaybackSnapshot) = PlayerPresentation(view.copy(snapshot = changed)).trackSelection()

        val rerun = state.copy(occurrence = requireNotNull(state.occurrence).copy(eventId = "another visit"))
        assertNotEquals(initial?.binding, withSnapshot(snapshot.copy(state = rerun))?.binding)
        assertNotEquals(initial?.binding, withSnapshot(snapshot.copy(outputVersion = 2))?.binding)
        assertNotEquals(initial?.binding, withSnapshot(snapshot.copy(selectedDeviceId = "other device"))?.binding)
        assertNull(withSnapshot(snapshot.copy(state = PlaybackQueueState())))
    }

    @Test fun oldSeekContextCannotCrossRebindModeOrRepeatedVisit() {
        val online = view()
        val context = PlayerPresentation(online).gestureContext
        assertNotEquals(context, PlayerPresentation(online.copy(connectionId = "rebound")).gestureContext)
        assertNotEquals(context, PlayerPresentation(online.copy(offline = true, localOutput = true)).gestureContext)
        val snapshot = requireNotNull(online.snapshot)
        val visit = requireNotNull(snapshot.state.occurrence)
        assertNotEquals(context, PlayerPresentation(online.copy(snapshot = snapshot.copy(
            state = snapshot.state.copy(occurrence = visit.copy(eventId = "repeat"))))).gestureContext)
    }

    @Test fun miniPlayerProofAndProgressAreScopedToGenerationAndOccurrence() {
        val view = view()
        val snapshot = requireNotNull(view.snapshot)
        val playing = snapshot.playback.copy(isPlaying = true, elapsed = 72.0)
        val confirmed = PlayerPresentation(view.copy(playback = playing))
        assertTrue(confirmed.isPlaying)
        assertEquals(60.0, confirmed.elapsed, 0.0)
        assertFalse(PlayerPresentation(view.copy(playback = playing.copy(outputVersion = 0))).isPlaying)
        assertFalse(PlayerPresentation(view.copy(playback = playing.copy(eventId = "first visit"))).isPlaying)
        val pending = PlayerPresentation(view.copy(snapshot = snapshot.copy(state = snapshot.state.copy(elapsed = 14.0)),
            playback = playing.copy(hasReportedPlayback = false)))
        assertFalse(pending.isPlaying)
        assertEquals(14.0, pending.elapsed, 0.0)
        assertEquals(60.0, pending.duration, 0.0)
    }

    private fun view(): PlaybackView {
        val track = PlaybackTrack(id = "song", name = "Song", artists = emptyList(), albumName = "Album", albumId = "album",
            duration = 60.0, key = 1, order = 1, intent = "explicit")
        val queue = PlaybackQueueState(queue = listOf(track), position = 0, duration = 60.0,
            occurrence = PlaybackOccurrence(1, track.id, "visit", 0.0, false))
        val status = PlaybackStatus(1, 1, 1, "visit", true, 0.0, 60.0, false, false, false, "")
        return PlaybackView(connection = PlaybackConnectionState.READY, sessionId = "session", deviceId = "device", playback = status,
            snapshot = PlaybackSnapshot("session", 1, 1, queue, status, emptyList(), "device", "connection", null, 0))
    }
}
