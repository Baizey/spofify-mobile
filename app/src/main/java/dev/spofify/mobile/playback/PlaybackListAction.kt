package dev.spofify.mobile.playback

/** Explicit list intent. Add never starts; Play next is not contextual FIFO. */
enum class PlaybackListAction { CONTEXTUAL, ADD_TO_QUEUE, PLAY_NEXT }

enum class PlaybackActionStatus {
    PLAYING, ADDED, LIMITED, FULL, NO_OP, UNAVAILABLE, BLOCKED, STALE, UNSUPPORTED_REMOTE, UNSUPPORTED,
}

/** Exact local successor transition, issued only by the serialized list action. An accepted
 * insertion or successful initial contextual start may carry already-committed FIFO tickets.
 * This is not a refreshed gesture binding: only tickets committed before that mutation may
 * consume it; failed starts, generic replacements and intervening edits cannot supply one. */
class PlaybackAdditiveTransition internal constructor(
    internal val before: PlaybackInteractionKey,
    internal val after: PlaybackInteractionKey,
    internal val committedThrough: Long,
)

/** Process-local ordering only; never persisted or used as output/server authority. */
internal object PlaybackListActionTickets {
    private val serial = java.util.concurrent.atomic.AtomicLong()
    fun commit(): Long = serial.incrementAndGet()
    val committedThrough: Long get() = serial.get()
}

/** Acceptance from the authoritative mutation/start, never an optimistic preparation message. */
data class PlaybackActionResult(
    val status: PlaybackActionStatus,
    val acceptedCount: Int = 0,
    val additiveTransition: PlaybackAdditiveTransition? = null,
) {
    val userMessage: String get() = when (status) {
        PlaybackActionStatus.PLAYING -> "Playing"
        PlaybackActionStatus.ADDED -> "Added to queue"
        PlaybackActionStatus.LIMITED -> "Added $acceptedCount to queue; queue limited to 1,000 songs."
        PlaybackActionStatus.FULL -> "Queue is full (1,000 songs)."
        PlaybackActionStatus.NO_OP -> "Already in this queue position."
        PlaybackActionStatus.UNAVAILABLE -> "This selection or playback output is unavailable."
        PlaybackActionStatus.BLOCKED -> "Playback needs a tap on this device."
        PlaybackActionStatus.STALE -> "Playback action cancelled after the selection or output changed."
        PlaybackActionStatus.UNSUPPORTED_REMOTE -> "Contextual queue actions are unavailable on this remote output. Use its explicit controls."
        PlaybackActionStatus.UNSUPPORTED -> "This playback action is unavailable."
    }
}

/** Transient local provenance only. Imports cannot reconstruct a manual tier from v1 wire data. */
internal class PlaybackManualTier {
    private val occurrences = mutableSetOf<String>()

    fun reset() = occurrences.clear()
    fun prune(order: List<String>, current: String?) { occurrences.retainAll(PlaybackQueue.upcoming(order, current).toSet()) }
    fun contains(id: String): Boolean = id in occurrences
    fun add(ids: Collection<String>) { occurrences.addAll(ids) }
    fun remove(id: String) { occurrences.remove(id) }

    fun insertionIndex(order: List<String>, current: String?): Int {
        prune(order, current)
        val last = order.indexOfLast { it in occurrences }
        return if (last >= 0) last + 1 else (order.indexOf(current) + 1).coerceAtLeast(0)
    }

    /** Promote the exact existing occurrence, never create a duplicate or touch played history. */
    fun promoted(order: List<String>, current: String?, id: String): List<String>? {
        // Current progression may precede the deferred service prune callback. Never let a
        // previously-manual played occurrence pull this promotion behind the current item.
        prune(order, current)
        if (id !in PlaybackQueue.upcoming(order, current)) return null
        val without = order.filterNot { it == id }
        // Compute without the requested ID, including when it is already manual.
        val remainingManual = (occurrences - id).intersect(PlaybackQueue.upcoming(order, current).toSet())
        val last = without.indexOfLast { it in remainingManual }
        val insertion = if (last >= 0) last + 1 else without.indexOf(current) + 1
        return without.toMutableList().apply { add(insertion, id) }
    }

    /** Explicit arbitrary reorder adopts the displayed future as manual, preserving user order.
     * A later FIFO request follows that entire order; shuffle never undoes the explicit order. */
    fun adoptReorder(order: List<String>, current: String?) {
        reset(); add(PlaybackQueue.upcoming(order, current))
    }

    fun partitionFuture(order: List<String>, current: String?): Pair<List<String>, List<String>> {
        prune(order, current)
        return PlaybackQueue.upcoming(order, current).partition { it in occurrences }
    }
}
