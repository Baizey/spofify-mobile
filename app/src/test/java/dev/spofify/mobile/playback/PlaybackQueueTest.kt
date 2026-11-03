package dev.spofify.mobile.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    @Test fun traversalIsRealizedBoundedAndDoesNotLoopOnRepeat() {
        assertEquals(listOf(2, 0, 3, 1), PlaybackQueue.traversal(4, 2) { listOf(3, 2, 0, 1)[it] })
        assertEquals(listOf(1, 2), PlaybackQueue.traversal(4, 1) { if (it == 1) 2 else -1 })
        assertTrue(PlaybackQueue.traversal(0, -1) { -1 }.isEmpty())
    }

    @Test fun upNextExcludesPlayedAndCurrentInDisplayedShuffleOrder() {
        assertEquals(listOf("a2", "b"), PlaybackQueue.upcoming(listOf("played", "a1", "a2", "b"), "a1"))
        assertTrue(PlaybackQueue.upcoming(listOf("a"), null).isEmpty())
    }

    @Test fun duplicateTracksAreEditedByOccurrenceAndCurrentIsProtected() {
        val order = listOf("A-first", "B", "A-second", "A-third")
        val removal = requireNotNull(PlaybackQueue.removal(order, "A-first", "A-second"))
        assertEquals(listOf("A-first", "B", "A-third"), removal.remaining)
        assertNull(PlaybackQueue.removal(order, "B", "A-first"))
        assertNull(PlaybackQueue.removal(order, "B", "B"))
        assertNull(PlaybackQueue.removal(order, "B", "missing"))
    }

    @Test fun reorderAdoptsDisplayedBaselineWithoutTouchingPlayedCurrentPrefix() {
        val order = listOf("played", "current", "shuffled-c", "shuffled-a", "shuffled-b")
        assertEquals(listOf("played", "current", "shuffled-b", "shuffled-c", "shuffled-a"),
            PlaybackQueue.move(order, "current", "shuffled-b", 0))
        assertNull(PlaybackQueue.move(order, "current", "played", 0))
        assertNull(PlaybackQueue.move(order, "current", "shuffled-b", -1))
        assertNull(PlaybackQueue.move(order, "current", "shuffled-b", 3))
    }

    @Test fun undoOnlyAppliesToExactUntouchedRemainderAndSameCurrentOccurrence() {
        val order = listOf("played", "current", "dup-1", "dup-2")
        val removal = requireNotNull(PlaybackQueue.removal(order, "current", "dup-1"))
        assertEquals(order, removal.undo(removal.remaining, "current"))
        assertNull(removal.undo(removal.remaining, "dup-2"))
        assertNull(removal.undo(removal.remaining + "new", "current"))
        assertNull(removal.undo(listOf("current", "played", "dup-2"), "current"))
    }

    @Test fun clearRemainderPreservesCurrentAndPlayedAndNeverWrapsRepeat() {
        val order = listOf("played", "current", "a", "b")
        val future = PlaybackQueue.upcoming(order, "current").toSet()
        assertEquals(listOf("played", "current"), order.filterNot { it in future })
    }

    @Test fun capacityRetiresOnlyPlayedEntriesNeverCurrentOrUpcoming() {
        val order = (0 until PlaybackQueue.LIMIT).map(Int::toString)
        assertEquals(order.drop(700), PlaybackQueue.retainedForAdd(order, "700", 20))
        assertEquals(order, PlaybackQueue.retainedForAdd(order, "0", 20))
        assertEquals(order, PlaybackQueue.retainedForAdd(order, "700", 0))
    }
}
