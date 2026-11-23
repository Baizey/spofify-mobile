package dev.spofify.mobile.core.playback

import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val FIRST_PREVIEW_KEY = 1L shl 52
internal const val MAX_SAFE_INTEGER = (1L shl 53) - 1
fun isPublishedQueueKey(key: Long): Boolean = key in 1L until FIRST_PREVIEW_KEY
internal fun addressableAccountChoice(action: JsonObject): Boolean {
    if (action.kind !in setOf("jump", "remove", "move")) return true
    if (!isPublishedQueueKey(action.long("key"))) return false
    if (action.kind != "move") return true
    val after = action.long("after")
    return after == 0L || isPublishedQueueKey(after)
}

internal val JsonObject.kind get() = getValue("type").jsonPrimitive.content
internal fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long
internal fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
/** Canonical nonnegative playback position used by v3 and the native seek API. */
fun playbackPositionMs(seconds: Double): Long = if (seconds.isNaN()) 0L
    else (seconds * 1_000).roundToLong().coerceIn(0L, MAX_SAFE_INTEGER)
internal fun secondsMs(seconds: Double): Long = playbackPositionMs(seconds)
internal fun Long.seconds() = this / 1_000.0

@Serializable internal data class AccountOwner(val generation: Long, val deviceId: String? = null)
@Serializable internal data class AccountDevice(val deviceId: String, val name: String, val canPlay: Boolean)
@Serializable internal data class AccountCursor(val key: Long, val occurrence: String? = null,
    val positionMs: Long, val desiredPlaying: Boolean) {
    fun sameVisit(other: AccountCursor) = key == other.key && occurrence == other.occurrence
    companion object { val Empty = AccountCursor(0, positionMs = 0, desiredPlaying = false) }
}
@Serializable internal data class AccountState(val queue: List<PlaybackTrack>, val cursor: AccountCursor,
    val repeat: String, val shuffle: Boolean, val collectionFirst: Long = 0) {
    fun presentation(fact: AccountObservation? = null, elapsedMs: Long = fact?.positionMs ?: cursor.positionMs): PlaybackQueueState {
        val pos = queue.indexOfFirst { it.key == cursor.key }
        require((pos == -1) == queue.isEmpty())
        val row = queue.getOrNull(pos)
        val duration = row?.duration ?: 0.0
        return PlaybackQueueState(queue = queue, position = pos, context = row?.playbackContext, repeat = repeat,
            shuffle = shuffle, elapsed = elapsedMs.seconds().let { if (duration > 0) minOf(it, duration) else it },
            desiredPlaying = cursor.desiredPlaying, isPlaying = fact?.status == "playing",
            isBuffering = fact?.status == "buffering", isLoading = fact?.status == "loading", duration = duration,
            error = if (fact?.status == "failed") "media" else "",
            highKeys = queue.drop(pos + 1).filter { it.intent != "low" }.map { it.key },
            occurrence = row?.let { PlaybackOccurrence(it.key, it.id, requireNotNull(cursor.occurrence), 0.0, fact != null) })
    }

    fun changed(delta: AccountDelta): AccountState = copy(queue = delta.queue?.apply(queue) ?: queue,
        cursor = delta.cursor ?: cursor, repeat = delta.repeat ?: repeat, shuffle = delta.shuffle ?: shuffle,
        collectionFirst = if (delta.queue == null) collectionFirst else delta.queue.collectionFirst)

    fun valid(): AccountState {
        require(cursor.positionMs in 0..MAX_SAFE_INTEGER && repeat in setOf("off", "one", "all"))
        require(queue.all { isPublishedQueueKey(it.key) } && queue.map { it.key }.toHashSet().size == queue.size)
        require(if (queue.isEmpty()) cursor.key == 0L && cursor.occurrence == null
            else !cursor.occurrence.isNullOrEmpty() && queue.any { it.key == cursor.key })
        return this
    }

    companion object {
        val Empty = AccountState(emptyList(), AccountCursor.Empty, "off", false)
        fun from(q: PlaybackQueueState) = AccountState(q.queue, q.current?.let {
            AccountCursor(it.key, requireNotNull(q.occurrence?.eventId), secondsMs(q.elapsed), q.desiredPlaying)
        } ?: AccountCursor.Empty, q.repeat, q.shuffle)
    }
}
@Serializable internal data class AccountQueueEdit(val kind: String, val rows: List<PlaybackTrack> = emptyList(),
    val keys: List<Long> = emptyList(), val before: Long = 0, val rebaseAfter: Long = 0, val collectionFirst: Long = 0) {
    fun apply(queue: List<PlaybackTrack>): List<PlaybackTrack> = when (kind) {
        "replace" -> rows
        "insert" -> {
            val i = if (before == 0L) queue.size else queue.indexOfFirst { it.key == before }
            require(i >= 0)
            queue.take(i) + rows + queue.drop(i)
        }
        "remove" -> keys.toHashSet().let { gone -> queue.filterNot { it.key in gone } }
        "order" -> queue.associateBy { it.key }.let { byKey ->
            require(keys.size == queue.size && keys.toSet().size == keys.size)
            val pos = keys.indexOf(rebaseAfter)
            require(rebaseAfter == 0L || pos >= 0)
            keys.mapIndexed { index, key -> byKey.getValue(key).let {
                if (pos >= 0) it.copy(intent = if (index > pos) "high" else it.intent, order = index + 1L) else it
            } }
        }
        else -> error("Unknown queue change")
    }
}
@Serializable internal data class AccountDelta(val queue: AccountQueueEdit? = null, val cursor: AccountCursor? = null,
    val repeat: String? = null, val shuffle: Boolean? = null)
@Serializable internal data class AccountObservation(val key: Long, val occurrence: String? = null,
    val positionMs: Long, val sampledAtMs: Long, val status: String) {
    fun matches(cursor: AccountCursor) = key == cursor.key && occurrence == cursor.occurrence
}
@Serializable internal data class AccountBasis(val sessionId: String, val generation: Long, val revision: Long,
    val occurrence: String? = null, val key: Long? = null, val successorKey: Long? = null)
@Serializable internal data class AccountCommand(val id: String, val issuedAtMs: Long, val basis: AccountBasis, val action: JsonObject,
    val type: String = "command")
@Serializable internal data class AccountSettlement(val id: String, val status: String, val cursor: AccountCursor? = null)
@Serializable internal data class AccountAdvance(val fromOccurrence: String, val fromKey: Long, val toKey: Long,
    val toOccurrence: String? = null, val endedPositionMs: Long)

/** Display-only prediction. The native engine never consumes this state. */
internal class AccountProjection(private val library: LocalPlaybackLibrary) {
    private var complete = emptyList<PlaybackTrack>()
    internal data class Pending(val command: AccountCommand, val rows: List<PlaybackTrack>?, var reflected: Boolean = false,
        var accepted: Boolean = false, var terminalAt: Long? = null)
    private var nextKey = FIRST_PREVIEW_KEY

    fun resetCollections() { complete = emptyList() }
    fun rememberCollection(rows: List<PlaybackTrack>, first: Long) {
        if (first == 0L) return
        val ordered = rows.sortedBy { it.order }
        val i = ordered.indexOfFirst { it.key == first }
        if (i >= 0) complete = ordered.drop(i) + ordered.take(i)
    }

    fun prepare(command: AccountCommand, projected: AccountState): Pending {
        val a = command.action
        if (a.kind != "play" && a.kind != "add") return Pending(command, null)
        val selection = a.getValue("selection").jsonObject
        val ref = selection["track"]?.let { playbackJson.decodeFromJsonElement<PlaybackTrackReference>(it) }
        val ctx = selection["context"]?.let { playbackJson.decodeFromJsonElement<PlaybackContext>(it) }
        fun sameContext(a: PlaybackContext?, b: PlaybackContext?) = a?.kind == b?.kind && a?.id == b?.id
        val full = complete.takeIf { ctx != null && it.isNotEmpty() && sameContext(it.first().playbackContext, ctx) }
        val resolved = library.resolve(ref, ctx)
        val candidates = full ?: resolved?.tracks ?: projected.queue
        val index = if (ref == null) 0 else candidates.indexOfFirst {
            it.id == ref.id && (ctx == null || sameContext(it.playbackContext, ctx)) &&
                (ref.entryId == null || ref.entryId == it.entryId) && (ref.targetId == null || ref.targetId == it.targetId) &&
                (ref.occurrenceId == null || ref.occurrenceId == it.occurrenceId)
        }
        if (ref == null && full == null && resolved == null) return Pending(command, null)
        val selected = when {
            index < 0 && ref != null -> listOf(PlaybackTrack(ref.id, ref.id, emptyList(), "", "", 0.0, 0, 0, "low",
                ref.entryId, ref.targetId, ref.occurrenceId, ctx))
            ref == null -> candidates
            a.kind == "add" || ctx == null || full == null && resolved == null -> listOf(candidates[index])
            a["shuffle"]?.jsonPrimitive?.booleanOrNull == true -> candidates
            else -> candidates.drop(index) + candidates.take(index)
        }
        val rows = selected.map { it.copy(key = nextKey++, intent = if (a.kind == "add") a.string("placement") else "low",
            playbackContext = if (ctx == null) null else it.playbackContext ?: ctx, entryId = it.entryId.takeIf { ctx != null },
            targetId = it.targetId.takeIf { ctx != null }, occurrenceId = it.occurrenceId.takeIf { ctx != null }) }
        return Pending(command, rows)
    }

    fun project(confirmed: AccountState, pending: Collection<Pending>): AccountState =
        pending.filterNot { it.reflected }.fold(confirmed, ::apply)

    private fun apply(s: AccountState, p: Pending): AccountState {
        val a = p.command.action
        val c = s.cursor
        val at = s.queue.indexOfFirst { it.key == c.key }
        val source = p.command.basis.occurrence
        fun select(key: Long) = s.copy(cursor = AccountCursor(key, p.command.id, 0, true))
        fun shuffle(rows: List<PlaybackTrack>): List<PlaybackTrack> {
            var n = a["seed"]?.jsonPrimitive?.longOrNull ?: 0
            val list = rows.toMutableList()
            for (i in list.lastIndex downTo 1) {
                n = (n * 1664525 + 1013904223) and 0xffffffffL
                val j = (n * (i + 1) ushr 32).toInt()
                val t = list[i]; list[i] = list[j]; list[j] = t
            }
            return list
        }
        return when (a.kind) {
            "pause", "resume" -> if (at < 0 || c.occurrence != source) s else s.copy(cursor = c.copy(desiredPlaying = a.kind == "resume"))
            "seek" -> if (at < 0 || c.occurrence != source) s else s.copy(cursor = c.copy(positionMs = a.long("positionMs")))
            "next" -> {
                val successor = LocalPlaybackQueue.successor(s.presentation(), naturalEnd = false)?.let { s.queue[it].key } ?: 0L
                if (at < 0 || c.occurrence != source || p.command.basis.successorKey != successor) s
                else if (successor == 0L) s.copy(cursor = c.copy(desiredPlaying = false))
                else select(successor)
            }
            "previous" -> if (at < 0 || c.occurrence != source) s else {
                val previous = LocalPlaybackQueue.predecessor(s.presentation(), c.positionMs.seconds())
                select(s.queue[previous].key)
            }
            "jump" -> a.long("key").let { if (s.queue.any { row -> row.key == it }) select(it) else s }
            "repeat" -> s.copy(repeat = a.string("repeat"))
            "close" -> AccountState.Empty.copy(repeat = s.repeat)
            "clear" -> s.copy(queue = s.queue.take(at + 1))
            "remove" -> s.copy(queue = LocalPlaybackQueue.remove(s.presentation(), a.long("key")).queue)
            "move" -> LocalPlaybackQueue.move(s.presentation(), a.long("key"), a.long("after").takeIf { it != 0L }).let {
                s.copy(queue = it.queue, shuffle = it.shuffle)
            }
            "shuffle" -> {
                val enabled = a.getValue("shuffle").jsonPrimitive.boolean
                if (enabled == s.shuffle) return s
                val (priority, regular) = s.queue.drop(at + 1).partition { it.intent != "low" }
                s.copy(queue = s.queue.take(at + 1) + priority + if (enabled) shuffle(regular) else regular.sortedBy { it.order },
                    shuffle = enabled)
            }
            "play", "add" -> {
                val isAdd = a.kind == "add"
                val order = if (isAdd) s.queue.maxOfOrNull { it.order } ?: 0 else 0
                val rows = p.rows?.mapIndexed { i, row -> row.copy(order = order + i + 1) }?.takeIf { it.isNotEmpty() } ?: return s
                if (isAdd) {
                    val i = if (a.string("placement") == "low") s.queue.size else at + 1
                    s.copy(queue = s.queue.take(i) + rows + s.queue.drop(i),
                        cursor = if (at < 0) AccountCursor(rows.first().key, p.command.id, 0, false) else c)
                } else {
                    val enabled = a["shuffle"]?.jsonPrimitive?.booleanOrNull == true
                    val traversal = if (enabled) shuffle(rows) else rows
                    s.copy(queue = traversal, cursor = AccountCursor(traversal.first().key, p.command.id, 0, true), shuffle = enabled)
                }
            }
            else -> s
        }
    }
}
