package dev.spofify.mobile.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The documented server contract still renews HTTP sessions through ordinary GET.
 * Transient maintenance failure is not socket loss or authoritative revocation.
 * SessionRepository owns 401/account fencing; no WebSocket renewal frame is invented.
 */
internal class PlaybackSessionMaintenance(
    private val scope: CoroutineScope,
    private val isSessionCurrent: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val renew: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private var timer: Job? = null
    private var request: Job? = null
    private var generation = 0L
    private var retired = false
    private var started = false
    private var renewing = false

    fun start() {
        if (retired || started) return
        started = true
        timer = scope.launch {
            while (isSessionCurrent()) {
                delay(60_000)
                if (isSessionCurrent() && isConnected()) refreshNow()
            }
        }
    }

    fun refreshNow() {
        if (retired || renewing || !isSessionCurrent()) return
        renewing = true
        val life = generation
        request = scope.launch {
            try {
                renew()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (life == generation && isSessionCurrent()) onFailure(failure)
            } finally {
                if (life == generation) renewing = false
            }
        }
    }

    fun close() {
        if (retired) return
        retired = true
        generation++
        timer?.cancel()
        request?.cancel()
    }
}
