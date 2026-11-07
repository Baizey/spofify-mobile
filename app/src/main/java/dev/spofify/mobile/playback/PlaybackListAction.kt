package dev.spofify.mobile.playback

/** Queue placement is separate from playback: only a changed exact current may start. */
enum class PlaybackListAction { CONTEXTUAL, ADD_TO_QUEUE, PLAY_NEXT, QUEUE_LOW, QUEUE_HIGH, PLAY_NOW }

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
    val beforeBinding: dev.spofify.mobile.core.QueueIntentBinding? = null,
    val afterBinding: dev.spofify.mobile.core.QueueIntentBinding? = null,
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
    val queueIntentResult: dev.spofify.mobile.core.QueueIntentResult? = null,
    val requestedAction: PlaybackListAction? = null,
) {
    val userMessage: String get() {
        queueIntentResult?.let { return queueIntentMessage(it, requestedAction) }
        return when (status) {
        PlaybackActionStatus.PLAYING -> "Playing track"
        PlaybackActionStatus.ADDED -> if (acceptedCount > 1) "$acceptedCount tracks queued" else "Track queued"
        PlaybackActionStatus.LIMITED -> "The selected device accepted only $acceptedCount tracks."
        PlaybackActionStatus.FULL -> "The selected device could not accept this queue."
        PlaybackActionStatus.NO_OP -> "Already in this queue position."
        PlaybackActionStatus.UNAVAILABLE -> "This selection or playback output is unavailable."
        PlaybackActionStatus.BLOCKED -> "Playback needs a tap on this device."
        PlaybackActionStatus.STALE -> "Playback action cancelled after the selection or output changed."
        PlaybackActionStatus.UNSUPPORTED_REMOTE, PlaybackActionStatus.UNSUPPORTED -> "Update the selected playback device and server to use queue priorities."
        }
    }
}

/** Transient local provenance only. Imports cannot reconstruct a manual tier from v1 wire data. */
internal class PlaybackManualTier {
    private val occurrences = mutableSetOf<String>()
    private val origins = mutableMapOf<String, String>()
    var provenance: String = "tracked"
        private set

    fun reset() { occurrences.clear(); origins.clear(); provenance = "tracked" }
    /** Retirement affects membership only. Requested origins survive Previous/repeat. */
    fun prune(order: List<String>, current: String?) {
        occurrences.retainAll(PlaybackQueue.upcoming(order, current).toSet())
        origins.keys.retainAll(order.toSet())
        order.forEach { origins.putIfAbsent(it, "legacy") }
    }
    fun contains(id: String): Boolean = id in occurrences
    fun origin(id: String): String = origins[id] ?: "legacy"
    fun add(ids: Collection<String>) { occurrences.addAll(ids); ids.forEach { origins.putIfAbsent(it, "legacy") } }
    fun tag(ids: Collection<String>, intent: String, high: Boolean) {
        require(intent in setOf("low", "high", "next", "now", "legacy"))
        ids.forEach { origins[it] = intent; if (high) occurrences.add(it) else occurrences.remove(it) }
    }
    fun restore(order: List<String>, requestedOrigins: Map<String, String>?, high: Collection<String> = emptyList(),
        provenance: String = if (requestedOrigins == null) "legacy-reset" else "tracked") {
        reset(); this.provenance = provenance
        order.forEach { origins[it] = requestedOrigins?.get(it) ?: "legacy" }
        occurrences.addAll(high)
    }
    fun remove(id: String) { occurrences.remove(id); origins.remove(id) }

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
        occurrences.clear(); add(PlaybackQueue.upcoming(order, current))
    }

    fun partitionFuture(order: List<String>, current: String?): Pair<List<String>, List<String>> {
        prune(order, current)
        return PlaybackQueue.upcoming(order, current).partition { it in occurrences }
    }
}
