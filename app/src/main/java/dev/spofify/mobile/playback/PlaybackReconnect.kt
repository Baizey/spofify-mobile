package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackView
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Application/account lifetime, not Activity or service lifetime. The only action
 * is a fresh connection: no receipt, queue, selection or musical intent is replayed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaybackReconnect(
    private val scope: CoroutineScope,
    private val views: StateFlow<PlaybackView>,
    private val isSessionCurrent: () -> Boolean,
    private val connectFresh: () -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val jitter: (Long) -> Long = { base ->
        val low = (base * .8).toLong()
        val high = (base * 1.2).toLong().coerceAtMost(MAX_RETRY_MS)
        Random.nextLong(low, high + 1)
    },
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var observer: Job? = null
    private var generation = 0L
    private var retired = false
    private var started = false
    private var expedited = false

    fun start() {
        if (retired || started || !isSessionCurrent()) return
        started = true
        val life = generation
        observer = scope.launch {
            views.map { it.connection }.distinctUntilChanged().collect { wake.trySend(Unit) }
        }
        worker = scope.launch {
            var previous: PlaybackConnectionState? = null
            var readySince: Long? = null
            var failures = 0
            var lastAttempt: Long? = null
            var retryAt = nowMs()
            try {
                while (life == generation && !retired) {
                    if (!isSessionCurrent()) {
                        close()
                        return@launch
                    }
                    val connection = views.value.connection
                    val now = nowMs()
                    if (connection != previous) {
                        if (previous == PlaybackConnectionState.READY && readySince?.let { now - it >= STABLE_MS } == true) failures = 0
                        readySince = if (connection == PlaybackConnectionState.READY) now else null
                        if (connection == PlaybackConnectionState.DISCONNECTED) {
                            retryAt = if (lastAttempt == null) now else now + retryDelay(failures)
                        }
                        previous = connection
                    }
                    when (connection) {
                        PlaybackConnectionState.CLOSED -> {
                            close()
                            return@launch
                        }
                        PlaybackConnectionState.READY -> {
                            expedited = false // Foreground never resets a healthy connection.
                            val stableAt = requireNotNull(readySince) + STABLE_MS
                            if (now >= stableAt) {
                                failures = 0
                                awaitWake(null)
                            } else awaitWake(stableAt - now)
                        }
                        PlaybackConnectionState.CONNECTING -> {
                            expedited = false // One Core-owned admission/snapshot wait at a time.
                            awaitWake(null)
                        }
                        PlaybackConnectionState.DISCONNECTED -> {
                            if (expedited) {
                                retryAt = minOf(retryAt, now)
                                expedited = false
                            }
                            val due = maxOf(retryAt, lastAttempt?.plus(MIN_RETRY_MS) ?: now)
                            if (now < due) {
                                awaitWake(due - now)
                                continue
                            }
                            // Recheck immediately before enqueueing a fresh admission. Core
                            // obtains new credentials and starts again at its first snapshot.
                            if (life != generation || !isSessionCurrent() || views.value.connection != PlaybackConnectionState.DISCONNECTED) continue
                            lastAttempt = now
                            failures = (failures + 1).coerceAtMost(6)
                            retryAt = now + retryDelay(failures)
                            try {
                                connectFresh()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // A synchronous admission failure follows the same bounded pace.
                            }
                        }
                    }
                }
            } finally { observer?.cancel() }
        }
    }

    /** May shorten a retry wait, but cannot bypass minimum spacing or parallelize admission. */
    fun expedite() {
        if (retired || !isSessionCurrent()) return
        expedited = true
        start()
        wake.trySend(Unit)
    }

    fun close() {
        if (retired) return
        retired = true
        generation++
        worker?.cancel()
        observer?.cancel()
        wake.close()
    }

    private fun retryDelay(failures: Int): Long {
        var base = MIN_RETRY_MS
        repeat((failures - 1).coerceIn(0, 5)) { base = (base * 2).coerceAtMost(MAX_RETRY_MS) }
        return jitter(base).coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)
    }

    private suspend fun awaitWake(waitMs: Long?) {
        if (waitMs == null) wake.receive()
        else select<Unit> {
            wake.onReceive { }
            onTimeout(waitMs.coerceAtLeast(1)) { }
        }
    }

    private companion object {
        const val MIN_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
        const val STABLE_MS = 30_000L
    }
}
