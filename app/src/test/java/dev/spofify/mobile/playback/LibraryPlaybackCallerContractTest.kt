package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding
import org.junit.Assert.*
import org.junit.Test

class LibraryPlaybackCallerContractTest {
    private fun receipt(execution: String = "confirmed", placement: String = "replace", count: Int = 2) =
        LibraryPlaybackResult("1", "accepted", "", 8, count, placement, execution, true)

    @Test fun acceptedOrConfirmedSetupNeverClaimsPlaying() {
        for (execution in listOf("unchanged", "pending", "confirmed", "blocked", "media", "uncertain", "superseded")) {
            val result = libraryPlaybackActionResult(receipt(execution), PlaybackListAction.PLAY_NOW)
            assertNotEquals(PlaybackActionStatus.PLAYING, result.status)
            assertFalse(result.userMessage.startsWith("Playing"))
            assertNull(PlaybackFeedback.from(result))
        }
    }
    @Test fun queueAcceptanceUsesItsOwnReceiptCountEvenWhenExecutionIsBlocked() {
        val receipt = receipt("blocked", "high", 3)
        val result = libraryPlaybackActionResult(receipt, PlaybackListAction.QUEUE_HIGH)
        assertSame(receipt, result.serverResult)
        assertEquals("3 tracks queued", PlaybackFeedback.from(result)?.message)
        val rejected = receipt.copy(outcome = "rejected", code = "capacity", acceptedCount = 0, placement = "none")
        assertNull(PlaybackFeedback.from(libraryPlaybackActionResult(rejected, PlaybackListAction.QUEUE_HIGH)))
        assertNull(PlaybackFeedback.from(libraryPlaybackActionResult(null, PlaybackListAction.QUEUE_HIGH)))
    }
    @Test fun bindingIncludesSocketRoomAndUnobservableReconnectIncarnation() {
        val original = LibraryPlaybackActionBinding("room", "socket", 7, 2, 1)
        assertNotEquals(original, original.copy(incarnation = 2))
        assertNotEquals(original, original.copy(connectionId = "new-socket"))
        assertNotEquals(original, original.copy(sessionId = "new-room"))
        assertNotEquals(original, original.copy(stateVersion = 8))
        assertNotEquals(original, original.copy(outputVersion = 3))
    }
    @Test fun desiredPlayingAndAcknowledgedSetupAreNotObservedAudio() {
        val initial = testLibrarySnapshot(playing = true, reported = false)
        val desired = initial.copy(state = initial.state.copy(desiredPlaying = true))
        assertFalse(desired.displayProjection().playing)
        val observed = desired.copy(playback = desired.playback.copy(hasReportedPlayback = true))
        assertTrue(observed.displayProjection().playing)
        assertFalse(observed.copy(playback = observed.playback.copy(outputVersion = 0)).displayProjection().playing)
        assertFalse(observed.copy(playback = observed.playback.copy(stateVersion = 0)).displayProjection().playing)
    }
}
