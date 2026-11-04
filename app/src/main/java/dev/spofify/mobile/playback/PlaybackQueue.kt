package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryItem

/** Identity belongs to a prepared occurrence, not a track or saved-playlist entry. */
data class QueueEntry(val occurrenceId: String, val item: LibraryItem, val timelineIndex: Int)

/** Pure, bounded queue-edit planning. All indices are realized/displayed traversal order. */
internal object PlaybackQueue {
    const val LIMIT = 1_000

    fun traversal(size: Int, first: Int, next: (Int) -> Int): List<Int> {
        val result = ArrayList<Int>(size)
        val seen = HashSet<Int>()
        var index = first
        while (index in 0 until size && seen.add(index) && result.size < LIMIT) {
            result.add(index)
            index = next(index)
        }
        return result
    }

    fun upcoming(order: List<String>, current: String?): List<String> {
        val index = order.indexOf(current)
        return if (index < 0) emptyList() else order.drop(index + 1)
    }

    fun move(order: List<String>, current: String?, occurrence: String, toUpcomingIndex: Int): List<String>? {
        val currentIndex = order.indexOf(current)
        val from = order.indexOf(occurrence)
        val count = order.size - currentIndex - 1
        if (currentIndex < 0 || from <= currentIndex || toUpcomingIndex !in 0 until count) return null
        val to = currentIndex + 1 + toUpcomingIndex
        if (from == to) return null
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    data class Removal(val occurrence: String, val index: Int, val current: String, val remaining: List<String>) {
        fun canUndo(order: List<String>, now: String?): Boolean =
            now == current && order == remaining && order.size < LIMIT && occurrence !in order
        fun undo(order: List<String>, now: String?): List<String>? =
            if (canUndo(order, now)) order.toMutableList().apply { add(index, occurrence) } else null
    }

    fun removal(order: List<String>, current: String?, occurrence: String): Removal? {
        val index = order.indexOf(occurrence)
        val currentIndex = order.indexOf(current)
        if (current == null || currentIndex < 0 || index <= currentIndex) return null
        return Removal(occurrence, index, current, order.filterNot { it == occurrence })
    }

    /** Admission never destroys history/current/future to make room. Report limited/full instead. */
    @Suppress("UNUSED_PARAMETER")
    fun retainedForAdd(order: List<String>, current: String?, additions: Int): List<String> = order
}
