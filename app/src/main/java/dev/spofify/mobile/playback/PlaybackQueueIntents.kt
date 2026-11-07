package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import kotlinx.serialization.json.*

/** Pure prospective transaction. No live key counters, media, history, or network mutations. */
internal object PlaybackQueueIntents {
    const val MAX_SAFE = 9007199254740991L
    data class Metadata(val origins: Map<Long, String>, val high: Set<Long>, val provenance: String)
    data class Plan(val snapshot: PlaybackSnapshot, val metadata: Metadata, val count: Int,
        val placement: String, val limited: Boolean, val newTracks: List<PlaybackTrack>)

    /** Same semantic projection for revision tracking and fresh retained-output admission.
     * Comparison only: never import this stripped snapshot or discard live progress/error. */
    fun semanticSnapshot(snapshot: PlaybackSnapshot): PlaybackSnapshot = snapshot.copy(
        elapsed = 0.0, duration = 0.0, playing = false, waiting = false, loading = false, error = "",
        occurrence = snapshot.occurrence?.copy(progress = 0.0, reported = false))

    fun metadata(state: QueueIntentState): Metadata = Metadata(state.origins.associate { it.key to it.intent },
        state.highKeys.toSet(), state.provenance)

    fun state(receiver: String, revision: Long, snapshot: PlaybackSnapshot, metadata: Metadata): QueueIntentState =
        QueueIntentState(receiver, revision, metadata.provenance,
            snapshot.queue.map { QueueIntentOrigin(it.key, metadata.origins[it.key] ?: "legacy") },
            snapshot.queue.drop(snapshot.position + 1).filter { it.key in metadata.high }.map { it.key })

    /** Complete sidecar alignment is checked without silently discarding unknown versions/keys. */
    fun validate(snapshot: PlaybackSnapshot, state: QueueIntentState) {
        state.validated(snapshot)
    }

    /** Reserve full escaped poll envelope/detail growth in addition to musical+metadata bytes. */
    fun publication(snapshot: PlaybackSnapshot, state: QueueIntentState) {
        snapshot.publicationValidated(); state.validated(snapshot)
        // Maximum protocol identities/scalars; reserve covers up to sixteen terminal details,
        // a newly created history identity and escaped envelope/context growth.
        PlaybackPublication.validatePoll("R".repeat(128), MAX_SAFE, MAX_SAFE, snapshot, emptyList(), state)
        val body = buildJsonObject {
            put("state", snapshot.toJson()); put("queueIntentState", state.toJson())
        }.toString().toByteArray(Charsets.UTF_8).size
        require(body <= 2 * 1024 * 1024 - 32 * 1024) { "Queue intent publication exceeds limit" }
    }

    fun plan(before: PlaybackSnapshot, metadata: Metadata, intent: String, additions: List<PlaybackTrack>,
        selectionKey: Long?, serial: Long, context: PlaybackContext? = null, startIndex: Int = 0,
        truncated: Boolean = false, notice: String = ""): Plan {
        require(intent in setOf("low", "high", "next", "now"))
        val current = before.queue.getOrNull(before.position)?.key
        val upcoming = before.queue.drop(before.position + 1).map { it.key }.toSet()
        val origins = metadata.origins.toMutableMap()
        val high = metadata.high.intersect(upcoming).toMutableSet()
        var queue = before.queue
        var position = before.position
        var accepted: List<PlaybackTrack> = emptyList()
        var count = 0
        var limited = false
        val placement = if (intent == "now") if (selectionKey == null) "replace" else "existing" else intent
        if (selectionKey != null) {
            require(selectionKey in upcoming) { "Stale occurrence" }
            if (intent == "now") {
                position = queue.indexOfFirst { it.key == selectionKey }; origins[selectionKey] = "now"; count = 1
            } else {
                val selected = queue.first { it.key == selectionKey }
                val without = queue.filterNot { it.key == selectionKey }
                high.remove(selectionKey)
                val insertion = when (intent) {
                    "low" -> without.size
                    "next" -> without.indexOfFirst { it.key == current } + 1
                    else -> without.indexOfLast { it.key in high }.takeIf { it >= 0 }?.plus(1)
                        ?: (without.indexOfFirst { it.key == current } + 1)
                }
                queue = without.toMutableList().apply { add(insertion, selected) }
                origins[selectionKey] = intent
                if (intent != "low") high.add(selectionKey)
                if (queue != before.queue || origins != metadata.origins || high != metadata.high) count = 1
            }
        } else {
            val seen = before.queue.filter { it.targetId.isNotEmpty() }.map { it.targetId to it.occurrenceId }.toMutableSet()
            val distinct = if (intent == "now") additions else additions.filter {
                it.targetId.isEmpty() || seen.add(it.targetId to it.occurrenceId)
            }
            if (intent == "now") require(distinct.isNotEmpty())
            require(!truncated && notice.isEmpty()) { "Incomplete playback selection" }
            val retained = if (intent == "now") emptyList() else queue
            val selected = distinct
            require(retained.size.toLong() + selected.size <= Int.MAX_VALUE) { "Queue index overflow" }
            val baseline = if (intent == "now") -1 else retained.maxOfOrNull { it.order } ?: -1
            require(serial <= MAX_SAFE - selected.size && baseline <= MAX_SAFE - selected.size)
            accepted = selected.mapIndexed { index, track -> track.copy(key = serial + index + 1, order = baseline + index + 1) }
            if (intent == "now") {
                queue = accepted; position = startIndex; origins.clear(); high.clear()
                accepted.forEachIndexed { index, track -> origins[track.key] = if (index == position) "now" else "low" }
            } else {
                val insertion = when (intent) {
                    "low" -> retained.size
                    "next" -> retained.indexOfFirst { it.key == current } + 1
                    else -> retained.indexOfLast { it.key in high }.takeIf { it >= 0 }?.plus(1)
                        ?: (retained.indexOfFirst { it.key == current } + 1)
                }
                queue = retained.toMutableList().apply { addAll(insertion, accepted) }
                position = if (current == null) if (queue.isEmpty()) -1 else 0 else queue.indexOfFirst { it.key == current }
                accepted.forEach { origins[it.key] = intent; if (intent != "low") high.add(it.key) }
            }
            count = accepted.size
        }
        val retainedKeys = queue.map { it.key }.toSet()
        origins.keys.retainAll(retainedKeys)
        high.retainAll(queue.drop(position + 1).map { it.key }.toSet())
        val changedCurrent = queue.getOrNull(position)?.key != current
        val next = before.copy(queue = queue, position = position,
            context = if (intent == "now" && selectionKey == null) context else before.context,
            shuffle = if (intent == "now" && selectionKey == null) false else before.shuffle,
            elapsed = if (changedCurrent) 0.0 else before.elapsed,
            duration = if (changedCurrent) queue.getOrNull(position)?.duration ?: 0.0 else before.duration,
            playing = if (changedCurrent) false else before.playing,
            waiting = if (changedCurrent) false else before.waiting,
            loading = if (changedCurrent) false else before.loading,
            error = if (changedCurrent) "" else before.error,
            occurrence = if (changedCurrent) null else before.occurrence,
            truncated = if (intent == "now" && selectionKey == null) limited else before.truncated || limited,
            notice = if (limited) notice.ifEmpty { "PLAYBACK_LIMIT" } else if (intent == "now" && selectionKey == null) notice else before.notice)
        return Plan(next, Metadata(origins, high, if (intent == "now" && selectionKey == null) "tracked" else metadata.provenance),
            count, if (count == 0) "none" else placement, limited, accepted)
    }
}

/** Semantic projection excludes all transport/progress presentation; identity survives ABA. */
internal class PlaybackQueueIntentRevision(val receiverId: String = java.util.UUID.randomUUID().toString()) {
    var revision: Long = 1; private set
    private var last: Pair<PlaybackSnapshot, PlaybackQueueIntents.Metadata>? = null
    fun observe(snapshot: PlaybackSnapshot, metadata: PlaybackQueueIntents.Metadata): Long {
        val semantic = PlaybackQueueIntents.semanticSnapshot(snapshot) to metadata
        if (last != null && last != semantic) {
            check(revision < PlaybackQueueIntents.MAX_SAFE) { "Queue receiver revision exhausted" }
            revision++
        }
        last = semantic
        return revision
    }
}
