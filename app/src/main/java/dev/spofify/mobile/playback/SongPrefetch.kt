package dev.spofify.mobile.playback

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The caller supplies actual next occurrence steps, including its realized repeat/shuffle rules. */
internal object SongPrefetch {
    const val NEXT_OCCURRENCES = 5

    fun plan(currentTrackId: String?, nextOccurrenceTrackIds: Iterable<String>): SongPrefetchPlan =
        plan(currentTrackId, nextOccurrenceTrackIds.asSequence())

    fun plan(currentTrackId: String?, nextOccurrenceTrackIds: Sequence<String>): SongPrefetchPlan {
        // Bound before deduplication: duplicates/current never earn another traversal step.
        val next = if (currentTrackId == null) emptyList() else nextOccurrenceTrackIds.take(NEXT_OCCURRENCES).toList()
        return SongPrefetchPlan(currentTrackId, next)
    }
}

internal class SongPrefetchPlan internal constructor(val currentTrackId: String?, next: List<String>) {
    val nextOccurrenceTrackIds: List<String> = Collections.unmodifiableList(ArrayList(next))
    val trackIds: List<String> = Collections.unmodifiableList(next.filter { it != currentTrackId }.distinct())

    override fun equals(other: Any?): Boolean = other is SongPrefetchPlan &&
        currentTrackId == other.currentTrackId && nextOccurrenceTrackIds == other.nextOccurrenceTrackIds
    override fun hashCode(): Int = 31 * (currentTrackId?.hashCode() ?: 0) + nextOccurrenceTrackIds.hashCode()
}

/** Cancellation registration is race-safe, including cancellation before the blocking open. */
internal class SongPrefetchCancellation {
    private val lock = Any()
    private var cancelled = false
    private val callbacks = mutableListOf<() -> Unit>()

    fun ensureActive() {
        synchronized(lock) { if (cancelled) throw CancellationException("Speculative transfer cancelled") }
    }

    /** Register e.g. CacheWriter.cancel/DataSource.close or SongCache.Cancellation.cancel. */
    fun onCancel(action: () -> Unit): AutoCloseable {
        val runNow = synchronized(lock) {
            if (cancelled) true else { callbacks.add(action); false }
        }
        if (runNow) safely(action)
        return AutoCloseable { synchronized(lock) { callbacks.remove(action) }; Unit }
    }

    fun cancel() {
        val actions = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            callbacks.toList().also { callbacks.clear() }
        }
        actions.forEach(::safely)
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (_: Exception) { /* Cancellation cannot become a playback error. */ }
    }
}

/**
 * One speculative blocking transfer, never current-track completion or a second player.
 * Requests must be immutable and correspond positionally to plan.trackIds. The transfer streams
 * through the store/data source and registers its close/cancel handle before opening the network.
 * Once admitted to IO, failed/cancelled attempts are skipped; heartbeats never retry them.
 * The service gates reconciliation on local authority and explicitly cancels on pause/session loss.
 */
internal class SongPrefetchScheduler<T>(
    private val scope: CoroutineScope,
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transfer: (T, SongPrefetchCancellation) -> Unit,
) : AutoCloseable {
    companion object { const val DEFAULT_ATTEMPT_TIMEOUT_MS = 30_000L }

    private class Pending<T>(val plan: SongPrefetchPlan, val requests: List<T>, var next: Int = 0)
    private class Attempt { lateinit var job: Job }
    private val lock = Any()
    private var pending: Pending<T>? = null
    private var active: Attempt? = null
    private var suspended = false
    private var demandActive = false
    private var closed = false

    init { require(attemptTimeoutMs > 0) }

    /** Explicit reconcile also re-arms after cancel, allowing a deliberate same-plan Resume. */
    fun reconcile(plan: SongPrefetchPlan, requests: List<T>) {
        require(requests.size == plan.trackIds.size)
        val obsolete = synchronized(lock) {
            if (closed) return
            suspended = false
            if (pending?.plan == plan) {
                pumpLocked()
                null
            } else {
                pending = Pending(plan, requests.toList())
                active?.job.also { if (it == null) pumpLocked() }
            }
        }
        obsolete?.cancel()
    }

    /** Demand network/loading owns priority; release only permits remaining unattempted requests. */
    fun setDemandActive(value: Boolean) {
        val yielded = synchronized(lock) {
            if (closed || demandActive == value) return
            demandActive = value
            if (value) active?.job else { pumpLocked(); null }
        }
        yielded?.cancel()
    }

    /** Discard this incarnation; only explicit reconcile re-arms (never demand callbacks). */
    fun cancel() {
        val job = synchronized(lock) { suspended = true; pending = null; active?.job }
        job?.cancel()
    }

    override fun close() {
        val job = synchronized(lock) { closed = true; pending = null; active?.job }
        job?.cancel()
    }

    private fun pumpLocked() {
        val work = pending ?: return
        if (closed || suspended || demandActive || active != null || !scope.isActive || work.next >= work.requests.size) return
        // Merely queueing a coroutine is not an attempt: demand may cancel it before IO starts.
        val request = work.requests[work.next]
        val attempt = Attempt()
        attempt.job = scope.launch(start = CoroutineStart.LAZY) {
            runAttempt(request) {
                synchronized(lock) {
                    if (closed || suspended || demandActive || pending !== work || active !== attempt || !attempt.job.isActive) {
                        false
                    } else {
                        // Admission is the point of no retry, even if the callback fails or is cancelled.
                        work.next++
                        true
                    }
                }
            }
        }
        active = attempt
        attempt.job.invokeOnCompletion {
            synchronized(lock) {
                // Completion includes draining the IO child. Also runs if cancellation beat
                // the LAZY body: relying on its finally would strand the pending successor.
                if (active === attempt) { active = null; pumpLocked() }
            }
        }
        attempt.job.start()
    }

    private suspend fun runAttempt(request: T, admit: () -> Boolean) = coroutineScope {
        val cancellation = SongPrefetchCancellation()
        val io = launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                cancellation.ensureActive()
                if (admit()) transfer(request, cancellation)
            } catch (_: Exception) {
                // Best effort only: no visible error and no unchanged-plan retry.
            }
        }
        try {
            withTimeoutOrNull(attemptTimeoutMs) {
                suspendCancellableCoroutine<Unit> { completion ->
                    completion.invokeOnCancellation {
                        cancellation.cancel() // Close even a held blocking open/read, not just its Job.
                        io.cancel()
                    }
                    io.invokeOnCompletion { completion.resume(Unit) }
                    io.start()
                }
            }
        } finally {
            if (!io.isCompleted) {
                cancellation.cancel()
                io.cancel()
            }
            // Cancellation is not proof the blocking callback stopped. A successor may not overlap.
            withContext(NonCancellable) { io.join() }
        }
    }
}
