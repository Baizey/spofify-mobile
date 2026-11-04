package dev.spofify.mobile.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackManualTierTest {
    private val tier = PlaybackManualTier()

    private fun add(order: List<String>, current: String?, id: String): List<String> {
        val insertion = tier.insertionIndex(order, current)
        tier.add(listOf(id))
        return order.toMutableList().apply { add(insertion, id) }
    }

    @Test fun manualRequestsAreFifoBeforeContextualTailWithoutChangingHistoryOrCurrent() {
        val initial = listOf("played", "A", "M1", "M2", "C1", "C2")
        tier.add(listOf("M1", "M2"))
        val x = add(initial, "A", "X")
        assertEquals(listOf("played", "A", "M1", "M2", "X", "C1", "C2"), x)
        assertEquals(listOf("played", "A", "M1", "M2", "X", "Y", "C1", "C2"), add(x, "A", "Y"))
    }

    @Test fun firstManualGoesBeforeContextAndEmptyAdmissionDoesNotInventCurrent() {
        assertEquals(listOf("A", "X", "C1", "C2"), add(listOf("A", "C1", "C2"), "A", "X"))
        tier.reset()
        assertEquals(listOf("X"), add(emptyList(), null, "X"))
    }

    @Test fun promotionMovesExactDuplicateOccurrenceWithoutCopyAndAlreadyLastIsNoop() {
        val initial = listOf("A", "same-track-manual", "same-track-context-1", "same-track-context-2")
        tier.add(listOf("same-track-manual"))
        val moved = requireNotNull(tier.promoted(initial, "A", "same-track-context-2"))
        assertEquals(listOf("A", "same-track-manual", "same-track-context-2", "same-track-context-1"), moved)
        assertEquals(initial.toSet(), moved.toSet())
        tier.add(listOf("same-track-context-2"))
        assertEquals(moved, tier.promoted(moved, "A", "same-track-context-2"))
        assertNull(tier.promoted(moved, "A", "A"))
        assertNull(tier.promoted(moved, "A", "missing"))
    }

    @Test fun progressionPrunesManualProvenanceEvenOnLaterRepeatTraversal() {
        tier.add(listOf("M1", "M2"))
        val order = listOf("A", "M1", "M2", "C1")
        tier.prune(order, "M1")
        assertFalse(tier.contains("M1"))
        assertTrue(tier.contains("M2"))
        assertEquals(listOf("M2") to listOf("C1"), tier.partitionFuture(order, "M1"))
        tier.prune(order, "M2")
        assertFalse(tier.contains("M2"))
        assertEquals(1, tier.insertionIndex(order, "A"))
    }

    @Test fun promotionNeverUsesStalePlayedManualAsInsertionAnchor() {
        tier.add(listOf("played-manual"))
        val order = listOf("played-manual", "A", "C1", "C2")
        assertEquals(listOf("played-manual", "A", "C2", "C1"), tier.promoted(order, "A", "C2"))
        assertFalse("Promotion also prunes progressed provenance before deferred service cleanup", tier.contains("played-manual"))
    }

    @Test fun removalUndoAndConservativeImportResetUseOccurrenceIdentityOnly() {
        tier.add(listOf("manual-1", "manual-2"))
        tier.remove("manual-1")
        assertFalse(tier.contains("manual-1"))
        assertTrue(tier.contains("manual-2"))
        tier.add(listOf("manual-1")) // Undo restores provenance captured by the service.
        assertTrue(tier.contains("manual-1"))
        tier.reset() // Import cannot infer preexisting provenance from order/key/context.
        assertEquals(1, tier.insertionIndex(listOf("A", "manual-1", "manual-2", "C"), "A"))
    }

    @Test fun arbitraryReorderAdoptsDisplayedFutureAndShufflePartitionKeepsItInOrder() {
        val reordered = listOf("played", "A", "C2", "M1", "C1")
        tier.adoptReorder(reordered, "A")
        assertEquals(listOf("C2", "M1", "C1") to emptyList<String>(), tier.partitionFuture(reordered, "A"))
        assertEquals(reordered + "X", add(reordered, "A", "X"))
    }

    @Test fun manualPartitionProtectsFifoAndKeepsContextBaselineAvailableForShuffleToggle() {
        tier.add(listOf("M1", "M2"))
        assertEquals(listOf("M1", "M2") to listOf("C2", "C1"),
            tier.partitionFuture(listOf("played", "A", "M1", "M2", "C2", "C1"), "A"))
    }

    @Test fun feedbackDistinguishesNoopFailureAndActualAcceptance() {
        assertEquals("Playing", PlaybackActionResult(PlaybackActionStatus.PLAYING).userMessage)
        assertEquals("Added to queue", PlaybackActionResult(PlaybackActionStatus.ADDED, 1).userMessage)
        assertFalse(PlaybackActionResult(PlaybackActionStatus.NO_OP).userMessage.startsWith("Added"))
        for (status in listOf(PlaybackActionStatus.STALE, PlaybackActionStatus.UNAVAILABLE,
            PlaybackActionStatus.UNSUPPORTED_REMOTE, PlaybackActionStatus.FULL, PlaybackActionStatus.BLOCKED)) {
            assertFalse(PlaybackActionResult(status).userMessage.startsWith("Added"))
            assertFalse(PlaybackActionResult(status).userMessage.startsWith("Playing"))
        }
    }
}
