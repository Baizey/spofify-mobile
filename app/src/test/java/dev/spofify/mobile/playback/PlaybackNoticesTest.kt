package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackNoticesTest {
    @Test fun capacityIsShownLiterallyRatherThanMisdiagnosedAsAnOutputProblem() {
        val failure = PlaybackCommandFailure("queueIntent", "rejected", "capacity", "unchanged", "draining")
        assertEquals("Playback queueIntent: rejected · code: capacity · execution: unchanged · handoff at result: draining",
            playbackNotice(PlaybackView(connection = PlaybackConnectionState.READY, commandFailure = failure)))
    }

    @Test fun connectionRecoveryTakesPrecedenceOverAnOldFailure() {
        val failure = PlaybackCommandFailure("seek", "accepted", "", "uncertain", null)
        assertEquals("Reconnecting playback", playbackNotice(PlaybackView(commandFailure = failure)))
        assertNull(playbackNotice(PlaybackView(connection = PlaybackConnectionState.READY)))
    }
}
