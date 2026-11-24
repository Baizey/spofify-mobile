package dev.spofify.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import dev.spofify.mobile.core.playback.AudioResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** The existing execution check, plus a cancellation fence for a scheduled HTTP reopen. */
internal class AudioLoadScope(
    val trackId: String,
    private val nowNanos: () -> Long = System::nanoTime,
    private val authorized: () -> Boolean,
) {
    private val startedNanos = nowNanos()
    // A failed runBlocking HTTP open is a child failure, not retirement of its owner.
    val job = SupervisorJob()
    @Volatile var playing = false
    fun isCurrent(): Boolean = job.isActive && authorized() && job.isActive

    val retryStarted = CompletableDeferred<Unit>()

    // A sequence of successful reads can still fail to produce a playable sample. Bound the
    // whole preparation as well as the shorter, existing deadline for retry/reopen work.
    suspend fun awaitPreparation(ready: Deferred<AudioResult>): AudioResult =
        withTimeoutOrNull(AUDIO_PREPARATION_TIMEOUT_MS) {
            select<AudioResult?> {
                ready.onAwait { it }
                retryStarted.onAwait { null }
            } ?: withTimeoutOrNull(remainingPrepareRetryMs() ?: error("Still preparing")) { ready.await() }
        } ?: AudioResult.MEDIA

    // Never grant a new window per reopen, or change Core's authority deadline.
    fun remainingPrepareRetryMs(): Long? = if (playing) null else
        4_000L - (nowNanos() - startedNanos) / 1_000_000
}

internal const val AUDIO_PREPARATION_TIMEOUT_MS = 20_000L

/** One installed progressive source. Ordinary buffered work survives healthy in-place seeks;
 * once an interruption belongs to a binding, never hand its period/task to another binding. */
internal class AudioSourceOwner(val key: String, val trackId: String) {
    val job = SupervisorJob()
    @Volatile var usingNetwork = false
    @Volatile var drainOnLoss = false
    val networkJob = SupervisorJob(job)
    val networkStopped get() = !networkJob.isActive
    fun stopNetwork(drain: Boolean) { drainOnLoss = drain; networkJob.cancel() }
    private var binding: AudioLoadScope? = null
    private val interruptions = mutableSetOf<AudioLoadRetry>()

    @Synchronized fun bind(next: AudioLoadScope): Boolean {
        if (trackId != next.trackId || interruptions.isNotEmpty() || !job.isActive) return false
        binding = next
        return true
    }

    @Synchronized fun unbind() { binding = null }

    @Synchronized fun interrupted(retry: AudioLoadRetry): AudioLoadScope? {
        interruptions.add(retry)
        return binding
    }

    @Synchronized fun concluded(retry: AudioLoadRetry) { interruptions.remove(retry) }
}

/** DataSpec.key identifies the installation, not just the opaque track or the account. */
internal fun AudioSourceOwner?.forRange(track: String, sourceKey: String?): AudioSourceOwner =
    this?.takeIf { it.job.isActive && it.trackId == track && it.key == sourceKey }
        ?: throw java.io.InterruptedIOException("Audio source retired")

internal class AudioLoadRetry(val source: AudioSourceOwner) {
    val pending = AtomicReference<AudioLoadScope?>()
}

internal class RetryableAudioLoad(
    val scope: AudioLoadScope,
    val retry: AudioLoadRetry,
    cause: IOException,
) : IOException("Audio transfer interrupted", cause)

/** Only Media3 retries. Count per load task, since Media3 can reset errorCount on sample progress. */
@UnstableApi
internal class AudioLoadRecovery : DefaultLoadErrorHandlingPolicy(4) {
    private class Attempt(val retry: AudioLoadRetry, val scope: AudioLoadScope, var count: Int = 0, var terminal: Boolean = false)
    private val attempts = mutableMapOf<Long, Attempt>() // Media3 playback looper only

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long =
        retryDelay(loadErrorInfo.loadEventInfo.loadTaskId, loadErrorInfo.exception)

    internal fun retryDelay(task: Long, error: IOException): Long {
        // A fatal conclusion can precede playerError delivery to the application looper.
        attempts[task]?.terminal = true
        if (error !is RetryableAudioLoad || !error.scope.isCurrent()) return C.TIME_UNSET
        val attempt = attempts.getOrPut(task) { Attempt(error.retry, error.scope) }
        // The same task cannot borrow a replacement execution's retry budget or authority.
        if (attempt.scope !== error.scope || attempt.retry !== error.retry) return C.TIME_UNSET
        val delays = if (error.scope.playing) PLAYBACK_DELAYS else PREPARE_DELAYS
        val delay = delays.getOrNull(attempt.count) ?: return C.TIME_UNSET
        if (error.scope.remainingPrepareRetryMs()?.let { delay >= it } == true) return C.TIME_UNSET
        attempt.terminal = false
        attempt.count++
        error.scope.retryStarted.complete(Unit)
        error.retry.pending.set(error.scope)
        return delay
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        attempts.remove(loadTaskId)?.let {
            it.retry.pending.compareAndSet(it.scope, null)
            // Loader has already closed this task's transfer. Healthy buffered Pause/seek can
            // reuse its source again; a fatal period must be replaced even before notification.
            if (!it.terminal) it.retry.source.concluded(it.retry)
        }
    }

    private companion object {
        // Stopped preparation (including Pause setup) never adds attempts or backoff to Media3's default.
        val PREPARE_DELAYS = longArrayOf(0, 1_000, 2_000)
        // Give a brief outage one more chance without holding or flushing Media3's buffered samples.
        val PLAYBACK_DELAYS = longArrayOf(0, 250, 1_000, 3_000)
    }
}
