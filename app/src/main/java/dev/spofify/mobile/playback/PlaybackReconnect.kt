package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One account-owned loop: bounded handshake bursts, then spaced recovery probes. */
internal class PlaybackReconnect(
    private val scope: CoroutineScope,
    private val views: StateFlow<PlaybackView>,
    private val allowed: () -> Boolean,
    private val connectFresh: () -> Job,
    private val onExhausted: suspend (stillCurrent: () -> Boolean) -> Unit,
) {
    private var batch: Job? = null
    private var epoch = 0L
    private var closed = false

    fun start(explicit: Boolean = false) {
        if (closed || !allowed() || !explicit && batch != null) return
        stop()
        val currentEpoch = epoch
        fun current() = !closed && epoch == currentEpoch && allowed()
        batch = scope.launch {
            var failures = 0
            while (current()) {
                val state = views.value
                if (state.connection == PlaybackConnectionState.CLOSED || state.superseded) return@launch
                if (state.connection == PlaybackConnectionState.READY) {
                    failures = 0
                    views.first { it.connection != PlaybackConnectionState.READY || it.superseded }
                    continue
                }
                if (state.localOutput && state.offline && state.isolatedPlaying()) {
                    // Rebinding to a separate shared queue must not replace active local output.
                    views.first { !it.isolatedPlaying() || it.superseded }
                    continue
                }
                try {
                    val attempt = connectFresh()
                    try { attempt.join() } catch (cancelled: CancellationException) {
                        attempt.cancel()
                        throw cancelled
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Synchronous admission refusal is also one failed attempt. */ }
                val latest = views.value
                if (!current() || latest.superseded || latest.connection == PlaybackConnectionState.CLOSED) return@launch
                if (latest.established != state.established) {
                    failures = 0 // Successful READY may have coalesced with immediate loss.
                    continue
                }
                if (++failures == 4) {
                    onExhausted { current() && !views.value.superseded }
                }
                kotlinx.coroutines.delay(when (failures) {
                    1 -> 1_000L
                    2 -> 2_000L
                    3 -> 4_000L
                    else -> 30_000L
                })
                failures = failures.coerceAtMost(4)
            }
        }
    }

    private fun PlaybackView.isolatedPlaying() = offline && localOutput && desiredPlaying

    fun stop() {
        epoch++
        batch?.cancel()
        batch = null
    }

    fun close() { closed = true; stop() }
}
