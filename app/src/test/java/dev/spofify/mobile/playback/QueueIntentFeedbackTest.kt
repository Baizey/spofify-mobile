package dev.spofify.mobile.playback

import dev.spofify.mobile.core.QueueIntentResult
import org.junit.Assert.*
import org.junit.Test

class QueueIntentFeedbackTest {
    @Test fun pausedAWithPendingH1AndAddedLowBReportsOnlyQueueAcceptance() {
        val detail = QueueIntentResult("queued", 2, 32, "low", "unchanged", null, "")
        val result = queueIntentActionResult(detail, PlaybackListAction.QUEUE_LOW)
        assertSame(detail, result.queueIntentResult)
        assertEquals("2 tracks queued", result.userMessage)
        assertFalse(result.userMessage.contains("Playing"))
        assertFalse(result.userMessage.contains("started"))
    }
    @Test fun acceptedBlockedAndMediaReceiptsKeepActualCountAndAttemptKey() {
        for (effect in listOf("blocked", "media")) {
            val detail = QueueIntentResult(effect, 2, 32, "replace", effect, 105, "")
            val result = queueIntentActionResult(detail, PlaybackListAction.PLAY_NOW)
            assertEquals(2, result.acceptedCount)
            assertSame(detail, result.queueIntentResult)
            assertEquals(105L, result.queueIntentResult!!.playbackKey)
            assertTrue(result.userMessage.contains("Selection accepted"))
            assertFalse(result.userMessage.contains("Playback started"))
        }
    }
    @Test fun supersessionRetainsOwnCommitAndUsesSelectionVersusQueueWording() {
        val detail = QueueIntentResult("queued", 2, 21, "replace", "superseded", null, "")
        for (action in listOf(PlaybackListAction.PLAY_NOW, PlaybackListAction.QUEUE_HIGH)) {
            val result = queueIntentActionResult(detail, action)
            assertEquals(21L, result.queueIntentResult!!.commitRevision)
            assertTrue(result.userMessage.startsWith(if (action == PlaybackListAction.PLAY_NOW) "Selection accepted" else "Queue change accepted"))
            assertTrue(result.userMessage.contains("superseded by a newer action"))
        }
    }
    @Test fun startedReceiptNeverGuessesRequestedItemFromCurrentDisplay() {
        val result = queueIntentActionResult(QueueIntentResult("playing", 2, 32, "high", "started", 105, ""), PlaybackListAction.QUEUE_HIGH)
        assertEquals("Playing · 2 tracks queued", result.userMessage)
        assertEquals(2, result.acceptedCount)
        assertFalse(result.userMessage.contains("105"))
    }
    @Test fun unsupportedAsksForUpdateAndUncertainCompletionDoesNotReportQueued() {
        val unsupported = queueIntentActionResult(QueueIntentResult("unsupported", 0, null, "none", "unchanged", null,
            "UNSUPPORTED_RECEIVER"), PlaybackListAction.QUEUE_HIGH)
        assertTrue(unsupported.userMessage.contains("Update"))
        val uncertain = queueIntentActionResult(null, PlaybackListAction.QUEUE_HIGH)
        assertEquals(0, uncertain.acceptedCount)
        assertNull(uncertain.queueIntentResult)
        assertFalse(uncertain.userMessage.contains("Queued"))
    }
}
