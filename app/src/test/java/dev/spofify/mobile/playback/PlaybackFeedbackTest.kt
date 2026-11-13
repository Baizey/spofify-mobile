package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryPlaybackResult
import org.junit.Assert.*
import org.junit.Test

class PlaybackFeedbackTest {
    private fun receipt(outcome: String = "accepted", count: Int = 1, execution: String = "unchanged",
        action: PlaybackListAction = PlaybackListAction.QUEUE_HIGH, placement: String = "high") = PlaybackFeedback.from(
        libraryPlaybackActionResult(LibraryPlaybackResult("1", outcome, "", 7, count, placement, execution, true), action))

    @Test fun explicitQueueUsesAcceptedCountRegardlessOfExecutionOutcome() {
        for (execution in listOf("unchanged", "pending", "confirmed", "blocked", "media", "uncertain", "superseded")) {
            for (action in listOf(PlaybackListAction.ADD_TO_QUEUE, PlaybackListAction.PLAY_NEXT,
                PlaybackListAction.QUEUE_LOW, PlaybackListAction.QUEUE_HIGH)) {
                assertEquals("Track queued", receipt(execution = execution, action = action)?.message)
                val event = requireNotNull(receipt(count = 3, execution = execution, action = action))
                assertEquals("3 tracks queued", event.message)
                assertEquals(1500L, event.baseTimeoutMillis)
            }
        }
    }

    @Test fun playNowAndOrdinaryContextualStartsNeverProduceEvents() {
        for (action in listOf(PlaybackListAction.PLAY_NOW, PlaybackListAction.CONTEXTUAL)) {
            for (execution in listOf("pending", "confirmed", "unchanged", "blocked", "media", "uncertain", "superseded")) {
                assertNull(receipt(count = 3, execution = execution, action = action, placement = "replace"))
            }
        }
        for (status in PlaybackActionStatus.entries) {
            assertNull(PlaybackFeedback.from(PlaybackActionResult(status, 3)))
            assertNull(PlaybackFeedback.from(PlaybackActionResult(status, 3), action = PlaybackListAction.PLAY_NOW))
        }
    }

    @Test fun rejectedOrResolvingQueueAndZeroAcceptanceNeverProduceEvents() {
        for (outcome in listOf("resolving", "rejected")) assertNull(outcome, receipt(outcome, 3))
        for (count in listOf(0, -1)) assertNull(receipt(count = count))
        assertNull(receipt(placement = "none"))
        assertNull(PlaybackFeedback.from(libraryPlaybackActionResult(null, PlaybackListAction.QUEUE_LOW)))
        for (status in PlaybackActionStatus.entries) {
            val event = PlaybackFeedback.from(PlaybackActionResult(status, 2), action = PlaybackListAction.QUEUE_HIGH)
            if (status in setOf(PlaybackActionStatus.ADDED, PlaybackActionStatus.LIMITED))
                assertEquals("2 tracks queued", event?.message)
            else assertNull(status.name, event)
        }
    }

    @Test fun committedQueueAcceptanceSurvivesBlockedOrFailedExecution() {
        for (execution in listOf("blocked", "media")) {
            for ((action, placement) in listOf(PlaybackListAction.QUEUE_HIGH to "high",
                PlaybackListAction.QUEUE_LOW to "low", PlaybackListAction.PLAY_NEXT to "next",
                PlaybackListAction.ADD_TO_QUEUE to "low")) {
                for (count in listOf(1, 3)) {
                    val receipt = LibraryPlaybackResult("1", "accepted", "", 7, count, placement, execution, true)
                    val result = libraryPlaybackActionResult(receipt, action).copy(acceptedCount = 99)
                    assertEquals(if (count == 1) "Track queued" else "3 tracks queued", PlaybackFeedback.from(result)?.message)
                    for (silentAction in listOf(PlaybackListAction.PLAY_NOW, PlaybackListAction.CONTEXTUAL)) {
                        assertNull(PlaybackFeedback.from(libraryPlaybackActionResult(receipt.copy(placement = "replace"), silentAction)))
                    }
                    for (unproven in listOf(receipt.copy(acceptedCount = 0), receipt.copy(outcome = "rejected"),
                        receipt.copy(placement = "none"))) {
                        assertNull(PlaybackFeedback.from(libraryPlaybackActionResult(unproven, action)))
                    }
                }
            }
        }
    }

    @Test fun authoritativeReceiptNotOptimisticOuterCountControlsAcceptance() {
        val result = libraryPlaybackActionResult(LibraryPlaybackResult("1", "accepted", "", 7, 2, "high", "blocked", true),
            PlaybackListAction.QUEUE_HIGH).copy(acceptedCount = 99)
        assertEquals("2 tracks queued", PlaybackFeedback.from(result)?.message)
    }

    @Test fun repeatedQueueMessagesHaveDistinctIdsAndSilentOutcomesCannotReplaceThem() {
        val first = requireNotNull(receipt())
        val repeat = requireNotNull(receipt())
        assertEquals(first.message, repeat.message)
        assertTrue(repeat.id > first.id)
        assertSame(repeat, nextPlaybackFeedback(first, repeat))
        assertSame(repeat, nextPlaybackFeedback(repeat, receipt(count = 0)))
        assertNull(nextPlaybackFeedback(null, receipt(count = 0)))
    }
}
