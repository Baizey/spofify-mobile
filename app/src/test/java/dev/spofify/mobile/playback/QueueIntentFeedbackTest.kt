package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryPlaybackResult
import org.junit.Assert.*
import org.junit.Test

/** Logical server acceptance never becomes an assertion of audible playback. */
class QueueIntentFeedbackTest {
    private fun receipt(execution: String = "unchanged", placement: String = "low") =
        LibraryPlaybackResult("4", "accepted", "", 32, 2, placement, execution, true)

    @Test fun pausedLowAcceptanceReportsOnlyItsOwnCountAndRevision() {
        val detail = receipt()
        val result = libraryPlaybackActionResult(detail, PlaybackListAction.QUEUE_LOW)
        assertSame(detail, result.serverResult)
        assertEquals(32L, result.serverResult!!.stateVersion)
        assertEquals("2 tracks queued", result.userMessage)
        assertEquals("2 tracks queued", queueAcceptanceMessage(result, PlaybackListAction.QUEUE_LOW))
        assertFalse(result.userMessage.contains("Playing"))
        assertFalse(result.userMessage.contains("started"))
    }

    @Test fun blockedAndMediaKeepLogicalAcceptanceWithoutInventingStartOrAnAttemptKey() {
        for (execution in listOf("blocked", "media")) {
            val detail = receipt(execution, "replace")
            val result = libraryPlaybackActionResult(detail, PlaybackListAction.PLAY_NOW)
            assertEquals(2, result.acceptedCount)
            assertSame(detail, result.serverResult)
            assertTrue(result.userMessage.contains(if (execution == "blocked") "needs a tap" else "could not play"))
            assertFalse(result.userMessage.contains("started"))
            assertNotEquals(PlaybackActionStatus.PLAYING, result.status)
        }
    }

    @Test fun supersededExecutionKeepsItsCommittedAcceptanceRatherThanReadingNewerDisplay() {
        val detail = receipt("superseded", "replace").copy(stateVersion = 21)
        for (action in listOf(PlaybackListAction.PLAY_NOW, PlaybackListAction.QUEUE_HIGH)) {
            val result = libraryPlaybackActionResult(detail, action)
            assertEquals(21L, result.serverResult!!.stateVersion)
            assertEquals(2, result.acceptedCount)
            assertSame(detail, result.serverResult)
            assertFalse(result.userMessage.contains("Playing"))
        }
    }

    @Test fun evenConfirmedExecutionDoesNotProveActualSound() {
        val result = libraryPlaybackActionResult(receipt("confirmed", "replace"), PlaybackListAction.PLAY_NOW)
        assertEquals("Selection accepted", result.userMessage)
        assertNotEquals(PlaybackActionStatus.PLAYING, result.status)
        assertEquals(2, result.acceptedCount)
    }

    @Test fun unknownAndRejectedCompletionNeverReportQueueAcceptance() {
        val uncertain = libraryPlaybackActionResult(null, PlaybackListAction.QUEUE_HIGH)
        assertEquals(PlaybackActionStatus.UNAVAILABLE, uncertain.status)
        assertEquals(0, uncertain.acceptedCount)
        assertNull(uncertain.serverResult)
        assertNull(queueAcceptanceMessage(uncertain, PlaybackListAction.QUEUE_HIGH))
        for ((code, status) in listOf("stale_state_version" to PlaybackActionStatus.STALE,
            "stale_revision" to PlaybackActionStatus.STALE, "capacity" to PlaybackActionStatus.FULL,
            "unavailable" to PlaybackActionStatus.UNAVAILABLE)) {
            val rejected = receipt().copy(outcome = "rejected", code = code, acceptedCount = 0, placement = "none")
            val result = libraryPlaybackActionResult(rejected, PlaybackListAction.QUEUE_HIGH)
            assertEquals(status, result.status)
            assertNull(queueAcceptanceMessage(result, PlaybackListAction.QUEUE_HIGH))
            assertFalse(result.userMessage.contains("queued"))
        }
    }
}
