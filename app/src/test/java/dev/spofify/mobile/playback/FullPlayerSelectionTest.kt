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

        assertEquals(PlayerPresentation.Online(view).trackSelection(), PlayerPresentation.Online(updated).trackSelection())
        assertEquals(PlayerPresentation.Local(state).trackSelection(), PlayerPresentation.Local(changed).trackSelection())
    }

    @Test fun sameSongOnANewOccurrenceOrOutputGetsNewMetadataBinding() {
        val view = view()
        val snapshot = requireNotNull(view.snapshot)
        val state = snapshot.state
        val initial = PlayerPresentation.Online(view).trackSelection()
        fun withSnapshot(changed: PlaybackSnapshot) = PlayerPresentation.Online(view.copy(snapshot = changed)).trackSelection()

        val rerun = state.copy(occurrence = requireNotNull(state.occurrence).copy(eventId = "another visit"))
        assertNotEquals(initial?.binding, withSnapshot(snapshot.copy(state = rerun))?.binding)
        assertNotEquals(initial?.binding, withSnapshot(snapshot.copy(outputVersion = 2))?.binding)
        assertNotEquals(initial?.binding, withSnapshot(snapshot.copy(selectedDeviceId = "other device"))?.binding)
        assertNull(withSnapshot(snapshot.copy(state = PlaybackQueueState())))
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
