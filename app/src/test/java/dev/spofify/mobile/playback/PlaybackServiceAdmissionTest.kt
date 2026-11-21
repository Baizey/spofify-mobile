package dev.spofify.mobile.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceAdmissionTest {
    @Test fun initialPlayWaitsForPromotionNotJustServiceAttachment() = runTest {
        val promotion = CompletableDeferred<Boolean>()
        val play = async { awaitPlaybackServiceAdmission(promotion) { true } }
        runCurrent()
        assertFalse(play.isCompleted)
        promotion.complete(true)
        assertTrue(play.await())
    }

    @Test fun retiredOrDemotedAfterPromotionCannotConsumeTheAcknowledgment() = runTest {
        val promotion = CompletableDeferred<Boolean>()
        var stillAdmitted = true
        val play = async { awaitPlaybackServiceAdmission(promotion) { stillAdmitted } }
        runCurrent()
        promotion.complete(true)
        stillAdmitted = false // Intentional teardown wins before playback resumes on the looper.
        assertFalse(play.await())
    }

    @Test fun lateAcknowledgmentCannotAdmitTheNextPlayAfterCancellation() = runTest {
        val retired = CompletableDeferred<Boolean>()
        val abandonedPlay = async { awaitPlaybackServiceAdmission(retired) { error("Cancelled play ran") } }
        runCurrent()
        abandonedPlay.cancel()
        val successor = CompletableDeferred<Boolean>()
        val nextPlay = async { awaitPlaybackServiceAdmission(successor) { true } }
        runCurrent()
        retired.complete(true)
        runCurrent()
        assertFalse(nextPlay.isCompleted)
        successor.complete(true)
        assertTrue(nextPlay.await())
    }

    @Test fun refusalOrMissingNotificationNeverAdmitsPlay() = runTest {
        val refused = CompletableDeferred(false)
        assertFalse(awaitPlaybackServiceAdmission(refused) { error("No successful promotion to consume") })

        val neverPromoted = CompletableDeferred<Boolean>()
        val play = async { awaitPlaybackServiceAdmission(neverPromoted) { true } }
        runCurrent()
        advanceTimeBy(PLAYBACK_SERVICE_ADMISSION_TIMEOUT_MS)
        runCurrent()
        assertFalse(play.await())
    }
}
