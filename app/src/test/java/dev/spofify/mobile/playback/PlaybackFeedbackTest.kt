package dev.spofify.mobile.playback

import dev.spofify.mobile.core.QueueIntentResult
import org.junit.Assert.*
import org.junit.Test

class PlaybackFeedbackTest {
    private fun receipt(outcome: String = "queued", count: Int = 1, effect: String = "unchanged",
        action: PlaybackListAction = PlaybackListAction.QUEUE_HIGH) = PlaybackFeedback.from(
        queueIntentActionResult(QueueIntentResult(outcome, count, 7, "high", effect, null, ""), action))

    @Test fun onlyTerminalRoutineResultsGetCompactPolicy() {
        assertEquals("Track queued", receipt().message)
        assertEquals("3 tracks queued", receipt(count = 3).message)
        for (effect in listOf("started", "resumed")) {
            val event = receipt("playing", effect = effect)
            assertEquals("Playing track", event.message)
            assertEquals(PlaybackFeedbackKind.SUCCESS, event.kind)
            assertEquals(1500L, event.baseTimeoutMillis)
        }
        for (effect in listOf("started", "resumed")) {
            for (outcome in listOf("playing", "queued")) {
                for (action in listOf(PlaybackListAction.PLAY_NOW, PlaybackListAction.QUEUE_HIGH)) {
                    val multi = receipt(outcome, count = 3, effect = effect, action = action)
                    assertEquals("Playing · 3 tracks queued", multi.message)
                    assertEquals(PlaybackFeedbackKind.SUCCESS, multi.kind)
                }
            }
        }
        assertEquals("Selection accepted", receipt(action = PlaybackListAction.PLAY_NOW).message)
        for (outcome in listOf("limited", "full", "no_op", "stale", "unsupported", "blocked")) {
            assertEquals(outcome, PlaybackFeedbackKind.GUIDANCE, receipt(outcome).kind)
        }
        for (effect in listOf("blocked", "media", "superseded")) {
            assertEquals(PlaybackFeedbackKind.GUIDANCE, receipt(effect = effect).kind)
        }
        assertEquals(PlaybackFeedbackKind.GUIDANCE,
            PlaybackFeedback.from(queueIntentActionResult(null, PlaybackListAction.QUEUE_LOW)).kind)
    }

    @Test fun repeatedMessagesHaveDistinctIdsAndErrorsCannotBeReplacedBySuccess() {
        val first = receipt()
        val repeat = receipt()
        assertEquals(first.message, repeat.message)
        assertTrue(repeat.id > first.id)
        assertSame(repeat, nextPlaybackFeedback(first, repeat))
        val error = PlaybackFeedback.guidance("Choose a playback output.")
        assertTrue(error.baseTimeoutMillis >= 10000L)
        assertSame(error, nextPlaybackFeedback(error, repeat))
    }

    @Test fun frozenReceiptIsNotReinterpretedAsPlayingForUnchangedCurrent() {
        val event = receipt(count = 2)
        assertEquals("2 tracks queued", event.message)
        assertFalse(event.message.contains("Playing"))
        val limited = receipt("limited", 2, "started")
        assertTrue(limited.message.contains("Queued 2; queue limited"))
        assertEquals(PlaybackFeedbackKind.GUIDANCE, limited.kind)
    }
}
