package dev.spofify.mobile.playback

import dev.spofify.mobile.core.QueueIntentResult
import org.junit.Assert.*
import org.junit.Test

class PlaybackFeedbackTest {
    private fun receipt(outcome: String = "queued", count: Int = 1, effect: String = "unchanged",
        action: PlaybackListAction = PlaybackListAction.QUEUE_HIGH, notice: String = "") = PlaybackFeedback.from(
        queueIntentActionResult(QueueIntentResult(outcome, count, 7, "high", effect, null, notice), action))

    @Test fun explicitQueueUsesAcceptedCountRegardlessOfPlaybackEffect() {
        for (outcome in listOf("queued", "playing", "limited")) {
            for (effect in listOf("started", "resumed", "unchanged", "blocked", "media", "superseded")) {
                for (action in listOf(PlaybackListAction.ADD_TO_QUEUE, PlaybackListAction.PLAY_NEXT,
                    PlaybackListAction.QUEUE_LOW, PlaybackListAction.QUEUE_HIGH)) {
                    assertEquals("Track queued", receipt(outcome, effect = effect, action = action)?.message)
                    val event = requireNotNull(receipt(outcome, 3, effect, action, "device needs attention"))
                    assertEquals("3 tracks queued", event.message)
                    assertEquals(1500L, event.baseTimeoutMillis)
                }
            }
        }
    }

    @Test fun playNowAndOrdinaryContextualStartsNeverProduceEvents() {
        for (action in listOf(PlaybackListAction.PLAY_NOW, PlaybackListAction.CONTEXTUAL)) {
            for (effect in listOf("started", "resumed", "unchanged", "blocked", "media", "superseded")) {
                for (outcome in listOf("playing", "queued", "limited")) {
                    assertNull(receipt(outcome, 3, effect, action))
                }
            }
        }
        for (status in PlaybackActionStatus.entries) {
            assertNull(PlaybackFeedback.from(PlaybackActionResult(status, 3)))
            assertNull(PlaybackFeedback.from(PlaybackActionResult(status, 3), action = PlaybackListAction.PLAY_NOW))
        }
    }

    @Test fun rejectedOrUncertainQueueAndZeroAcceptanceNeverProduceEvents() {
        for (outcome in listOf("full", "no_op", "stale", "unsupported", "unavailable", "unknown")) {
            assertNull(outcome, receipt(outcome, 3))
        }
        for (count in listOf(0, -1)) assertNull(receipt(count = count))
        assertNull(PlaybackFeedback.from(queueIntentActionResult(null, PlaybackListAction.QUEUE_LOW)))
        for (status in PlaybackActionStatus.entries) {
            val event = PlaybackFeedback.from(PlaybackActionResult(status, 2), action = PlaybackListAction.QUEUE_HIGH)
            if (status in setOf(PlaybackActionStatus.ADDED, PlaybackActionStatus.LIMITED))
                assertEquals("2 tracks queued", event?.message)
            else assertNull(status.name, event)
        }
    }

    @Test fun committedQueueAcceptanceSurvivesBlockedOrFailedPlayback() {
        for (effect in listOf("blocked", "media")) {
            for ((action, placement) in listOf(
                PlaybackListAction.QUEUE_HIGH to "high",
                PlaybackListAction.QUEUE_LOW to "low",
                PlaybackListAction.PLAY_NEXT to "next",
                PlaybackListAction.ADD_TO_QUEUE to "low")) {
                for (count in listOf(1, 3)) {
                    val receipt = QueueIntentResult(effect, count, 7, placement, effect, 105, "").validated()
                    val result = queueIntentActionResult(receipt, action).copy(acceptedCount = 99)
                    assertEquals(if (count == 1) "Track queued" else "3 tracks queued",
                        PlaybackFeedback.from(result)?.message)
                    for (silentAction in listOf(PlaybackListAction.PLAY_NOW, PlaybackListAction.CONTEXTUAL)) {
                        val selection = receipt.copy(placement = "replace").validated()
                        assertNull(PlaybackFeedback.from(queueIntentActionResult(selection, silentAction)))
                    }
                    for (unproven in listOf(receipt.copy(acceptedCount = 0),
                        receipt.copy(commitRevision = null), receipt.copy(placement = "none"))) {
                        assertNull(PlaybackFeedback.from(queueIntentActionResult(unproven, action)))
                    }
                }
            }
        }
    }

    @Test fun authoritativeReceiptNotOptimisticOuterCountControlsAcceptance() {
        val result = queueIntentActionResult(QueueIntentResult("limited", 2, 7, "high", "blocked", null, ""),
            PlaybackListAction.QUEUE_HIGH).copy(acceptedCount = 99)
        assertEquals("2 tracks queued", PlaybackFeedback.from(result)?.message)
    }

    @Test fun repeatedQueueMessagesHaveDistinctIdsAndSilentOutcomesCannotReplaceThem() {
        val first = requireNotNull(receipt())
        val repeat = requireNotNull(receipt())
        assertEquals(first.message, repeat.message)
        assertTrue(repeat.id > first.id)
        assertSame(repeat, nextPlaybackFeedback(first, repeat))
        assertSame(repeat, nextPlaybackFeedback(repeat, receipt("no_op")))
        assertNull(nextPlaybackFeedback(null, receipt("no_op")))
    }
}
