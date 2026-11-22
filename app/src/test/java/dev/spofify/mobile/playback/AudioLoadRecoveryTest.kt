package dev.spofify.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.playback.AudioResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class AudioLoadRecoveryTest {
    private val policy = AudioLoadRecovery()
    private val scope = AudioLoadScope("fixture") { true }
    private val installed = AudioSourceOwner("old", "fixture")
    private val retry = AudioLoadRetry(installed)
    private val interrupted = RetryableAudioLoad(scope, retry, IOException("Transport interruption"))

    @Test fun activePlaybackGetsOneExtraChanceAndExhaustsEvenIfMedia3ResetsErrorCount() {
        scope.playing = true
        assertEquals(listOf(0L, 250L, 1_000L, 3_000L, C.TIME_UNSET, C.TIME_UNSET),
            List(6) { policy.retryDelay(1, interrupted) })
        assertSame(scope, retry.pending.get())
        policy.onLoadTaskConcluded(1)
        assertNull(retry.pending.get())
        assertEquals(0L, policy.retryDelay(2, interrupted))
    }

    @Test fun stoppedPreparationDoesNotExtendDefaultAttemptCountOrBackoff() {
        assertEquals(listOf(0L, 1_000L, 2_000L, C.TIME_UNSET), List(4) { policy.retryDelay(1, interrupted) })
    }

    @Test fun stoppedPreparationDoesNotScheduleBeyondItsLocalRetryWindow() {
        var elapsedMs = 0L
        val preparing = AudioLoadScope("fixture", nowNanos = { elapsedMs * 1_000_000 }) { true }
        val interruption = RetryableAudioLoad(preparing, retry, IOException())
        elapsedMs = 3_001
        assertEquals(0L, policy.retryDelay(1, interruption))
        assertEquals(999L, preparing.remainingPrepareRetryMs())
        assertEquals(C.TIME_UNSET, policy.retryDelay(1, interruption))
        elapsedMs = 4_000
        assertEquals(C.TIME_UNSET, policy.retryDelay(2, interruption))
    }

    @Test fun revokedAndReplacedScopesCannotSpendTheOldTasksRetryBudget() {
        val authorized = AtomicBoolean(true)
        val original = AudioLoadScope("fixture", authorized = authorized::get).also { it.playing = true }
        val error = RetryableAudioLoad(original, retry, IOException())
        assertEquals(0L, policy.retryDelay(1, error))
        authorized.set(false)
        assertFalse(retry.pending.get()!!.isCurrent())
        assertEquals(C.TIME_UNSET, policy.retryDelay(1, error))
        assertEquals(C.TIME_UNSET, policy.retryDelay(1, interrupted))
        policy.onLoadTaskConcluded(1)
        assertNull(retry.pending.get())
        assertEquals(0L, policy.retryDelay(2, interrupted))
        scope.job.cancel()
        assertFalse(retry.pending.get()!!.isCurrent())
        assertEquals(C.TIME_UNSET, policy.retryDelay(2, interrupted))
    }

    @Test fun queuedRetryMakesSameTrackSeekRequireANewPeriodEvenBeforePolicyCallback() {
        assertTrue(installed.bind(scope))
        assertSame(scope, installed.interrupted(retry)) // loader has thrown; policy has not run yet
        installed.unbind()
        scope.job.cancel()
        val seek = AudioLoadScope("fixture") { true }
        assertFalse(installed.bind(seek))
        assertEquals(C.TIME_UNSET, policy.retryDelay(1, interrupted))
        val replacement = AudioSourceOwner("new", "fixture")
        assertTrue(replacement.bind(seek))
        assertNull(installed.interrupted(retry))
        assertSame(seek, replacement.interrupted(AudioLoadRetry(replacement)))
    }

    @Test fun staleRangeCannotOpenOrSpendARetryOnASameTrackReplacement() {
        assertTrue(installed.bind(scope))
        val rangeKey = installed.key
        assertSame(installed, installed.forRange(scope.trackId, rangeKey))
        assertSame(scope, installed.interrupted(retry))
        assertEquals(0L, policy.retryDelay(11, interrupted))
        installed.unbind(); scope.job.cancel(); installed.job.cancel()
        val replacement = AudioSourceOwner("next", scope.trackId)
        assertTrue(replacement.bind(AudioLoadScope(scope.trackId) { true }))
        for (current in listOf(null, installed, replacement)) {
            assertThrows(InterruptedIOException::class.java) { current.forRange(scope.trackId, rangeKey) }
        }
        assertEquals(C.TIME_UNSET, policy.retryDelay(11, interrupted))
        assertSame(replacement, replacement.forRange(scope.trackId, replacement.key))
    }

    @Test fun healthyInBufferSeekCanReuseSourceAndOwnASubsequentInterruption() {
        assertTrue(installed.bind(scope))
        installed.unbind()
        scope.job.cancel()
        val seek = AudioLoadScope("fixture") { true }
        assertTrue(installed.bind(seek))
        assertSame(seek, installed.interrupted(retry))
        val error = RetryableAudioLoad(seek, retry, IOException())
        assertEquals(0L, policy.retryDelay(1, error))
        seek.job.cancel()
        assertFalse(retry.pending.get()!!.isCurrent())
        assertEquals(C.TIME_UNSET, policy.retryDelay(1, error))
    }

    @Test fun onlyNonfatalConclusionsMakeRecoveredBufferedSourceReusable() {
        for (fatal in listOf(false, true)) {
            assertTrue(installed.bind(scope))
            installed.interrupted(retry)
            assertEquals(0L, policy.retryDelay(1, interrupted))
            assertFalse(installed.bind(scope)) // retry queued; even same-track rebind needs a fresh period
            if (fatal) assertEquals(C.TIME_UNSET, policy.retryDelay(1, IOException("invalid range")))
            policy.onLoadTaskConcluded(1)
            assertEquals(!fatal, installed.bind(scope))
        }
    }

    @Test fun failedBlockingAcquisitionDoesNotCancelEitherOwnersRemainingAttempts() {
        for (job in listOf(AudioSourceOwner("period", "fixture").job, scope.job)) {
            assertThrows(IOException::class.java) { runBlocking(job) { throw IOException("interrupted headers") } }
            assertEquals("reopened", runBlocking(job) { "reopened" })
            job.cancel()
            assertThrows(java.util.concurrent.CancellationException::class.java) { runBlocking(job) { fail() } }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun retryCannotHoldStoppedPreparationPastItsOriginalDeadlineEvenWithOpenHeaders() = runTest {
        val preparing = AudioLoadScope("fixture", nowNanos = { testScheduler.currentTime * 1_000_000 }) { true }
        val waiting = async { preparing.awaitPreparation(CompletableDeferred()) }
        delay(2_500)
        assertEquals(0L, policy.retryDelay(1, RetryableAudioLoad(preparing, retry, IOException())))
        assertEquals(AudioResult.MEDIA, waiting.await())
        assertEquals(4_000L, testScheduler.currentTime)
    }

    @Test fun ordinaryPreparationHasMoreTimeThanTheShortRetryWindow() = runTest {
        val ready = CompletableDeferred<AudioResult>()
        val waiting = async { scope.awaitPreparation(ready) }
        delay(5_000)
        assertTrue(waiting.isActive)
        ready.complete(AudioResult.OK)
        assertEquals(AudioResult.OK, waiting.await())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun neverReadyWithoutARetryStillTerminates() = runTest {
        val waiting = async { scope.awaitPreparation(CompletableDeferred()) }
        assertEquals(AudioResult.MEDIA, waiting.await())
        assertEquals(AUDIO_PREPARATION_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test fun terminalErrorsAreNotRetried() {
        for (terminal in listOf(
            IOException("Invalid audio range"), AuthFailure(AuthFailure.Kind.UNAVAILABLE),
            InterruptedIOException("Cancelled"), ParserException.createForMalformedContainer("Bad media", null),
        )) assertEquals(C.TIME_UNSET, policy.retryDelay(1, terminal))
        assertNull(retry.pending.get())
    }
}
