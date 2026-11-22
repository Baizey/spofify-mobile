package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.AudioResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativePlaybackStartTest {
    @Test fun optimisticPlayWaitsForNativeAdvance() = runTest {
        var status = NativeStartStatus.WAITING
        val result = async { awaitNativePlaybackStart({ true }) { status } }
        advanceTimeBy(100)
        assertFalse(result.isCompleted)
        status = NativeStartStatus.ADVANCING
        runCurrent()
        assertEquals(AudioResult.OK, result.await())
    }

    @Test fun asynchronousFocusRefusalIsNotPlaying() = runTest {
        var status = NativeStartStatus.WAITING
        val result = async { awaitNativePlaybackStart({ true }) { status } }
        advanceTimeBy(100)
        status = NativeStartStatus.REFUSED
        assertEquals(AudioResult.BLOCKED, result.await())
    }

    @Test fun unknownStallAndSuppressionAreBounded() = runTest {
        for ((status, expected) in listOf(
            NativeStartStatus.WAITING to AudioResult.MEDIA,
            NativeStartStatus.SUPPRESSED to AudioResult.BLOCKED,
        )) {
            val start = testScheduler.currentTime
            assertEquals(expected, awaitNativePlaybackStart({ true }) { status })
            assertEquals(NATIVE_PLAYBACK_START_TIMEOUT_MS, testScheduler.currentTime - start)
        }
    }

    @Test fun revokedBindingCannotReadOrApproveSuccessorProgress() = runTest {
        var current = true
        var observedSuccessor = false
        val result = async {
            awaitNativePlaybackStart({ current }) {
                if (!current) observedSuccessor = true
                NativeStartStatus.WAITING
            }
        }
        advanceTimeBy(100)
        current = false
        assertEquals(AudioResult.REJECTED, result.await())
        assertFalse(observedSuccessor)
    }
}
