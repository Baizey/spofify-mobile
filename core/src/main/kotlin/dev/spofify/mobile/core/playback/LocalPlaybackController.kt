package dev.spofify.mobile.core.playback

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Canonical local rows in order, with the exact requested occurrence selected (not a track-ID match). */
data class LocalPlaybackRows(val tracks: List<PlaybackTrack>, val selectedIndex: Int = 0)

interface LocalPlaybackLibrary {
    /** Read a ready, account-scoped local index, without waiting or fetching. Exclude rows without local audio.
     * For a track request, return null if that exact reference cannot be resolved, including any selection token.
     * For a collection request track is null. Keep duplicates and preserve entry/target/occurrence metadata.
     */
    fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?): LocalPlaybackRows?

    /** Recheck local availability at time of use, without any network fallback. Honor authorization at each wait. */
    suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean): Boolean
}

/**
 * One manual-offline account/mode lifetime. Commands, the supplied scope, and audio calls must share the
 * player looper. [isOutputAllowed] is a thread-safe mode/account check (native loading may check off-looper).
 * The supplied audio executor MUST only open local sources for these operations. Use [retire] synchronously
 * before cancelling this scope, rebinding a prepared source, or handing audio to another owner.
 */
class LocalPlaybackController(
    private val audio: AudioExecutor,
    private val library: LocalPlaybackLibrary,
    private val scope: CoroutineScope,
    private val isOutputAllowed: () -> Boolean,
    private val random: Random = Random.Default,
    private val onNaturalEnd: ((PlaybackQueueState, AudioFacts) -> Unit)? = null,
    private val onObserved: ((AudioFacts) -> Unit)? = null,
    private val onFailure: ((String, AudioFacts?) -> Unit)? = null,
    private val allowPreview: (PlaybackTrack) -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow(PlaybackQueueState())
    val state: StateFlow<PlaybackQueueState> = mutableState.asStateFlow()
    private val owner = nextOwner.incrementAndGet()
    private var generation = 0L
    private var nextKey = 0L
    @Volatile private var retired = false
    @Volatile private var active: Operation? = null
    @Volatile private var promotingFrom: Operation? = null

    private class Operation(val binding: AudioBinding) {
        var job: Job? = null
        var ready = false
        var earlyEnd: AudioFacts? = null
    }

    private val observations = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        audio.observations.collect { observation ->
            val op = active ?: return@collect
            if (observation.binding != op.binding || observation.facts.binding != op.binding || !keep(op)) return@collect
            if (observation.facts.error == "blocked") {
                fail(op, "blocked", observation.facts)
            } else if (observation.facts.ended) {
                if (op.ready) ended(op, observation.facts) else op.earlyEnd = observation.facts
            } else if (op.ready) {
                sample(op, audio.facts())
            }
        }
    }
    private val sampler = scope.launch {
        while (isActive) {
            delay(250)
            val op = active ?: continue
            if (keep(op) && op.ready) sample(op, audio.facts())
        }
    }

    /** Service retention only, on the player looper. A pending local start is not evidence of sound. */
    val hasPendingStart: Boolean get() = active?.let { !it.ready && state.value.desiredPlaying && authorized(it) } == true

    fun playTrack(track: PlaybackTrackReference, context: PlaybackContext? = null): Boolean =
        replace(track, context, shuffle = false)

    fun playCollection(context: PlaybackContext, shuffle: Boolean = false): Boolean =
        replace(null, context, shuffle)

    fun queueTrack(track: PlaybackTrackReference, context: PlaybackContext? = null, next: Boolean = false): Boolean =
        enqueue(track, context, next)

    fun queueCollection(context: PlaybackContext, next: Boolean = false): Boolean = enqueue(null, context, next)

    fun pause() {
        val op = active
        val elapsed = currentElapsed()
        invalidate()
        if (op != null) mutableState.value = state.value.copy(desiredPlaying = false, isPlaying = false, elapsed = elapsed)
    }

    fun resume() {
        val previous = state.value
        if (previous.current == null || !allowed()) return
        start(previous.copy(desiredPlaying = true, elapsed = currentElapsed()))
    }

    fun toggle() { if (state.value.desiredPlaying) pause() else resume() }
    fun retry() = resume()

    fun stop() {
        invalidate()
        if (!retired) mutableState.value = PlaybackQueueState(repeat = state.value.repeat)
    }

    fun seek(elapsed: Double) {
        val previous = state.value
        if (previous.current != null && elapsed.isFinite() && elapsed >= 0 && allowed()) {
            start(previous.copy(elapsed = boundedElapsed(elapsed, previous.duration)))
        }
    }

    fun next() {
        if (!allowed()) return
        val previous = state.value
        LocalPlaybackQueue.successor(previous, naturalEnd = false)?.let(::select) ?: pause()
    }

    fun previous() {
        if (allowed() && state.value.current != null) select(LocalPlaybackQueue.predecessor(state.value, currentElapsed()))
    }

    fun jump(key: Long) {
        if (allowed()) state.value.queue.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let(::select)
    }

    fun setShuffle(enabled: Boolean) = edit { LocalPlaybackQueue.shuffle(it, enabled, random) }
    fun toggleShuffle() = setShuffle(!state.value.shuffle)

    fun setRepeat(repeat: String) {
        if (repeat == "off" || repeat == "all" || repeat == "one") edit { LocalPlaybackQueue.withRepeat(it, repeat) }
    }

    fun cycleRepeat() = setRepeat(LocalPlaybackQueue.nextRepeat(state.value.repeat))

    fun clearUpcoming() = edit(LocalPlaybackQueue::clearUpcoming)
    fun remove(key: Long) = edit { LocalPlaybackQueue.remove(it, key) }
    fun moveUpcoming(key: Long, afterKey: Long?) = edit { LocalPlaybackQueue.move(it, key, afterKey) }

    /** Irreversible: no queued work or current media operation may be inherited by the incoming mode. */
    fun retire() {
        if (retired) return
        retired = true
        invalidate()
        observations.cancel()
        sampler.cancel()
        mutableState.value = PlaybackQueueState()
    }

    private fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?): LocalPlaybackRows? {
        if (!allowed()) return null
        val rows = library.resolve(track, context) ?: return null
        val selected = rows.tracks.getOrNull(rows.selectedIndex) ?: return null
        if (!allowed()) return null
        if (track != null && (selected.id != track.id || track.entryId != null && selected.entryId != track.entryId ||
                track.targetId != null && selected.targetId != track.targetId ||
                track.occurrenceId != null && selected.occurrenceId != track.occurrenceId)) return null
        return rows
    }

    private fun replace(track: PlaybackTrackReference?, context: PlaybackContext?, shuffle: Boolean): Boolean {
        val rows = resolve(track, context) ?: return false
        val allocated = allocate(rows.tracks, context, "low")
        val order = if (shuffle) allocated.shuffled(random)
            else allocated.drop(rows.selectedIndex) + allocated.take(rows.selectedIndex)
        val next = PlaybackQueueState(queue = order, context = context, shuffle = shuffle, repeat = state.value.repeat)
        start(LocalPlaybackQueue.select(next, 0))
        return true
    }

    private fun enqueue(track: PlaybackTrackReference?, context: PlaybackContext?, next: Boolean): Boolean {
        val rows = resolve(track, context) ?: return false
        val items = if (track != null) listOf(rows.tracks[rows.selectedIndex]) else rows.tracks
        val previous = state.value
        val updated = LocalPlaybackQueue.insert(previous, allocate(items, context, if (next) "next" else "low"), next)
        if (previous.current == null) start(LocalPlaybackQueue.select(updated.copy(context = context), 0))
        else publishEdit(updated)
        return true
    }

    private fun allocate(tracks: List<PlaybackTrack>, context: PlaybackContext?, intent: String) = tracks.map { track ->
        val key = ++nextKey
        track.copy(key = key, order = key, intent = intent, playbackContext = track.playbackContext ?: context)
    }

    private inline fun edit(change: (PlaybackQueueState) -> PlaybackQueueState) {
        if (allowed()) publishEdit(change(state.value))
    }

    private fun publishEdit(next: PlaybackQueueState) {
        mutableState.value = next
        audio.recheckCrossfade()
    }

    // The online owner adopts an already-resolved traversal. A queue-only edit or
    // reconciliation of its current native binding must not restart that binding.
    internal fun adopt(next: PlaybackQueueState, execute: Boolean, natural: Boolean = false) {
        if (!allowed()) return
        if (execute && next.current != null) start(next, natural)
        else {
            if (execute) invalidate()
            publishEdit(next)
        }
    }

    /** The source already ended. Revoke it without opening another source until Play. */
    internal fun finishNaturalEnd(next: PlaybackQueueState) {
        if (!allowed()) return
        invalidate()
        mutableState.value = next.copy(desiredPlaying = false, isPlaying = false, elapsed = 0.0)
    }

    private fun select(position: Int) = start(LocalPlaybackQueue.select(state.value, position))

    private fun allowed(): Boolean = !retired && scope.isActive && isOutputAllowed()
    private fun authorized(op: Operation): Boolean = active === op && allowed()
    // Only loader/native handoff callbacks can bridge the outgoing operation; events recognize only active.
    private fun loaderAuthorized(op: Operation): Boolean = (active === op || promotingFrom === op) && allowed()

    private fun invalidate(pauseNative: Boolean = true) {
        val op = active ?: return
        active = null // Revoke the callback before any synchronous native or cancellation callback.
        if (pauseNative) audio.pause()
        op.job?.cancel()
    }

    private fun keep(op: Operation): Boolean {
        if (authorized(op)) return true
        if (active === op) pause()
        return false
    }

    private fun arm(op: Operation) {
        audio.armCrossfade(op.binding) {
            val q = state.value
            if (!authorized(op) || !q.desiredPlaying) null else
                LocalPlaybackQueue.successor(q, true)?.let(q.queue::get)
                    ?.takeIf { it.key != q.current?.key && allowPreview(it) }
        }
    }

    private fun start(next: PlaybackQueueState, natural: Boolean = false) {
        val track = next.current ?: return
        if (!allowed()) return
        val outgoing = active.takeIf { natural }
        if (outgoing == null) invalidate()
        val serial = ++generation
        val id = "local-$owner-$serial"
        val op = Operation(AudioBinding(id, serial, id, track.id))
        promotingFrom = outgoing
        active = op
        if (outgoing != null) {
            val promoted = try {
                audio.promoteCrossfade(outgoing.binding, op.binding, track) { loaderAuthorized(op) }
            } finally {
                promotingFrom = null
                outgoing.job?.cancel()
            }
            if (promoted) {
                mutableState.value = next
                op.ready = true
                arm(op)
                sample(op, audio.facts())
                return
            }
            audio.pause()
        }
        op.job = scope.launch(start = CoroutineStart.LAZY) {
            val permitted = { loaderAuthorized(op) }
            try {
                if (!keep(op)) return@launch
                val available = library.hasAudio(track, permitted)
                if (!keep(op)) return@launch
                if (!available) { fail(op, "unavailable"); return@launch }
                // execute also handles a paused seek; prepare() lacks a per-step authorization callback.
                val result = audio.execute(op.binding, track, next.elapsed, next.desiredPlaying, permitted)
                if (!keep(op)) return@launch
                when (result) {
                    AudioResult.OK -> {
                        op.ready = true
                        if (next.desiredPlaying) arm(op)
                        op.earlyEnd?.let { ended(op, it) } ?: sample(op, audio.facts())
                        op.earlyEnd = null
                    }
                    AudioResult.BLOCKED -> fail(op, "blocked")
                    AudioResult.MEDIA -> fail(op, "media")
                    AudioResult.REJECTED -> fail(op, "unavailable")
                }
            } catch (cancelled: CancellationException) {
                if (scope.isActive && keep(op)) fail(op, audio.facts().takeIf { it.binding == op.binding }?.error.orEmpty())
                throw cancelled
            } catch (_: Exception) {
                if (keep(op)) fail(op, "media")
            }
        }
        mutableState.value = next.copy(isPlaying = false, error = "")
        op.job?.start()
    }

    internal fun sampleBeforePause(): AudioFacts? {
        val op = active?.takeIf { it.ready && authorized(it) } ?: return null
        return audio.facts().takeIf { it.binding == op.binding && (it.isPlaying || state.value.isPlaying) && !it.isBuffering &&
            !it.ended && it.error.isEmpty() && safeSeconds(it.elapsed) }
    }

    private fun currentElapsed(): Double {
        val previous = state.value
        val op = active?.takeIf { it.ready } ?: return previous.elapsed
        // Metadata is display-only here; it is not a bound on the measured pause position.
        return audio.facts().takeIf { it.binding == op.binding && it.elapsed.isFinite() && it.elapsed >= 0 }
            ?.elapsed ?: previous.elapsed
    }

    private fun sample(op: Operation, facts: AudioFacts) {
        if (facts.binding != op.binding || !keep(op)) return
        val previous = state.value
        val duration = duration(facts, previous)
        val elapsed = facts.elapsed.takeIf { it.isFinite() && it >= 0 } ?: previous.elapsed
        if (facts.error.isNotEmpty()) {
            fail(op, facts.error, facts)
            return
        }
        // Local IO may have native buffering but we do not publish request/spinner state.
        mutableState.value = previous.copy(elapsed = boundedElapsed(elapsed, duration), duration = duration, isPlaying = facts.isPlaying)
        if (!facts.ended) onObserved?.invoke(facts)
    }

    private fun ended(op: Operation, facts: AudioFacts) {
        sample(op, facts)
        val previous = state.value
        if (!keep(op) || !previous.desiredPlaying || facts.error.isNotEmpty()) return
        if (onNaturalEnd != null) onNaturalEnd.invoke(previous, facts)
        else LocalPlaybackQueue.successor(previous, naturalEnd = true)
            ?.let { start(LocalPlaybackQueue.select(previous, it), natural = true) } ?: finishNaturalEnd(previous)
    }

    private fun fail(op: Operation, error: String, native: AudioFacts = audio.facts()) {
        if (active !== op) return
        val observed = native.takeIf { it.binding == op.binding }
        val stoppedByRefusal = observed?.let { error == "blocked" && it.error == "blocked" && it.stoppedByRefusal } == true
        val failure = when (error) {
            "unavailable", "blocked" -> error
            else -> "media"
        }
        invalidate(pauseNative = !stoppedByRefusal)
        val previous = state.value
        val duration = observed?.let { duration(it, previous) } ?: previous.duration
        val elapsed = observed?.elapsed?.takeIf(::safeSeconds) ?: previous.elapsed
        mutableState.value = previous.copy(desiredPlaying = false, isPlaying = false, error = failure,
            elapsed = boundedElapsed(elapsed, duration), duration = duration)
        onFailure?.invoke(failure, observed)
    }

    private fun duration(facts: AudioFacts, previous: PlaybackQueueState): Double =
        facts.duration.takeIf { it.isFinite() && it > 0 } ?: previous.duration

    private fun boundedElapsed(elapsed: Double, duration: Double): Double =
        if (duration > 0) elapsed.coerceAtMost(duration) else elapsed

    private companion object { val nextOwner = AtomicLong() }
}
