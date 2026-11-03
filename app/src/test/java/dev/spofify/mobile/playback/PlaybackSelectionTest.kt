package dev.spofify.mobile.playback

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTest {
    @Test fun taskRemovalKeepsActiveBufferingButNotPausedEndedOrFailedPlayback() {
        assertTrue(keepPlaybackAfterTaskRemoved(true, true, Player.STATE_BUFFERING, false))
        assertTrue(keepPlaybackAfterTaskRemoved(true, true, Player.STATE_READY, false))
        assertFalse(keepPlaybackAfterTaskRemoved(false, true, Player.STATE_READY, false))
        assertFalse(keepPlaybackAfterTaskRemoved(true, true, Player.STATE_ENDED, false))
        assertFalse(keepPlaybackAfterTaskRemoved(true, true, Player.STATE_IDLE, true))
        assertFalse(keepPlaybackAfterTaskRemoved(true, false, Player.STATE_BUFFERING, false))
    }
}
