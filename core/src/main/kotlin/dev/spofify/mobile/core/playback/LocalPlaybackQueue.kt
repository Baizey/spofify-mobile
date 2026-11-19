package dev.spofify.mobile.core.playback

import kotlin.random.Random

/** Order and identity belong to queue occurrences, not to the underlying audio blobs. */
internal object LocalPlaybackQueue {
    fun select(state: PlaybackQueueState, position: Int): PlaybackQueueState {
        val upcomingKeys = state.queue.drop(position + 1).mapTo(HashSet()) { it.key }
        return state.copy(
            position = position,
            desiredPlaying = true,
            elapsed = 0.0,
            duration = state.queue[position].duration.takeIf { it.isFinite() && it > 0 } ?: 0.0,
            isPlaying = false,
            error = "",
            highKeys = state.highKeys.filter { it in upcomingKeys },
        )
    }

    fun successor(state: PlaybackQueueState, naturalEnd: Boolean): Int? = when {
        state.current == null -> null
        naturalEnd && state.repeat == "one" -> state.position
        state.position < state.queue.lastIndex -> state.position + 1
        state.repeat == "all" -> 0
        else -> null
    }

    fun predecessor(state: PlaybackQueueState, elapsed: Double): Int = when {
        elapsed > 3.0 -> state.position
        state.position > 0 -> state.position - 1
        state.repeat == "all" -> state.queue.lastIndex
        else -> state.position
    }

    fun insert(state: PlaybackQueueState, items: List<PlaybackTrack>, next: Boolean): PlaybackQueueState {
        val at = if (next) state.position + 1 else state.queue.size
        return state.copy(
            queue = state.queue.take(at) + items + state.queue.drop(at),
            highKeys = if (next) items.map { it.key } + state.highKeys else state.highKeys,
        )
    }

    fun shuffle(state: PlaybackQueueState, enabled: Boolean, random: Random): PlaybackQueueState {
        if (state.shuffle == enabled) return state
        val high = state.highKeys.toHashSet()
        val (priority, regular) = state.queue.drop(state.position + 1).partition { it.key in high }
        return state.copy(
            shuffle = enabled,
            queue = state.queue.take(state.position + 1) + priority +
                if (enabled) regular.shuffled(random) else regular.sortedBy { it.order },
        )
    }

    fun move(state: PlaybackQueueState, key: Long, afterKey: Long?): PlaybackQueueState {
        val from = state.queue.indexOfFirst { it.key == key }
        val after = if (afterKey == null) state.position else state.queue.indexOfFirst { it.key == afterKey }
        if (from <= state.position || afterKey != null && after <= state.position || from == after) return state
        val to = if (from < after) after else after + 1
        if (from == to) return state
        val order = state.queue.map { it.order }.sorted()
        val items = state.queue.toMutableList().apply { add(to, removeAt(from)) }
        return state.copy(
            queue = items.mapIndexed { index, track -> track.copy(order = order[index]) },
            highKeys = items.drop(state.position + 1).map { it.key },
            shuffle = false,
        )
    }

    fun remove(state: PlaybackQueueState, key: Long): PlaybackQueueState {
        if (state.queue.indexOfFirst { it.key == key } <= state.position) return state
        return state.copy(queue = state.queue.filterNot { it.key == key }, highKeys = state.highKeys - key)
    }

    fun clearUpcoming(state: PlaybackQueueState): PlaybackQueueState =
        state.copy(queue = state.queue.take(state.position + 1), highKeys = emptyList())
}
