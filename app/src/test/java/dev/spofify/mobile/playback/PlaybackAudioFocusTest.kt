package dev.spofify.mobile.playback

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest as PlatformAudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager

/** Focus lifecycle/callback tests, not proof of audible output or a service's authority fencing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PlaybackAudioFocusTest.FocusAudioManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackAudioFocusTest {
    private lateinit var audio: FocusAudioManager
    private lateinit var helper: PlaybackAudioFocus
    private var losses = 0
    private var onLoss: () -> Unit = {}

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = app.getSystemService(AudioManager::class.java)
        audio = Shadow.extract(manager)
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        helper = PlaybackAudioFocus(manager) {
            losses++
            assertFalse("Clear the grant before service invalidation", helper.isGranted)
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            onLoss()
        }
    }

    @After fun tearDown() {
        if (::helper.isInitialized) helper.release()
    }

    @Test fun constructionDoesNotRequestFocusOrRestorePlayback() {
        assertFalse(helper.isGranted)
        assertEquals(0, audio.requests)
        assertEquals(0, audio.abandons)
        assertEquals(0, losses)
    }

    @Test fun explicitStartUsesActualMediaGainWithoutDelayedGrantOrAutomaticDucking() {
        assertTrue(helper.requestForExplicitStart())
        assertTrue(helper.isGranted)
        val request = lastRequest()
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, request.audioAttributes.contentType)
        assertFalse(request.acceptsDelayedFocusGain())
        assertTrue(request.willPauseWhenDucked())
        assertNotNull(listener(request))
        assertEquals(1, audio.requests)
        assertEquals(0, audio.abandons)
        assertEquals(0, losses)
        dispatch(request, AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(helper.isGranted)
        assertEquals(0, losses)
        assertEquals(1, audio.requests)
    }

    @Test fun failedDelayedAndUnexpectedResponsesAreNeverGrantsEvenAfterLateGain() {
        for (response in listOf(AudioManager.AUDIOFOCUS_REQUEST_FAILED, AudioManager.AUDIOFOCUS_REQUEST_DELAYED, 99)) {
            audio.setNextFocusRequestResponse(response)
            assertFalse(helper.requestForExplicitStart())
            assertFalse(helper.isGranted)
            val rejected = lastRequest()
            assertSame(rejected, audio.lastAbandonedAudioFocusRequest)
            dispatch(rejected, AudioManager.AUDIOFOCUS_GAIN)
            dispatch(rejected, AudioManager.AUDIOFOCUS_LOSS)
            assertFalse(helper.isGranted)
            assertEquals(0, losses)
        }
        assertEquals(3, audio.requests)
        assertEquals(3, audio.abandons)
    }

    @Test fun platformRequestExceptionFailsClosedAndAbandonsItsListener() {
        audio.requestFailure = SecurityException("Synthetic background focus rejection")
        assertFalse(helper.requestForExplicitStart())
        assertFalse(helper.isGranted)
        assertSame(lastRequest(), audio.lastAbandonedAudioFocusRequest)
        dispatch(lastRequest(), AudioManager.AUDIOFOCUS_GAIN)
        dispatch(lastRequest(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(helper.isGranted)
        assertEquals(0, losses)
        assertEquals(1, audio.requests)
        assertEquals(1, audio.abandons)
    }

    @Test fun everyLossSynchronouslyInvalidatesServiceStartsAndStopsOutputWithoutWaitingForGain() {
        var pendingStart = true
        var output = true
        var bufferedOutput = true
        onLoss = {
            assertSame(lastRequest(), audio.lastAbandonedAudioFocusRequest)
            pendingStart = false
            output = false
            bufferedOutput = false
        }
        val changes = listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        for ((index, change) in changes.withIndex()) {
            assertTrue(helper.requestForExplicitStart())
            val request = lastRequest()
            pendingStart = true
            output = true
            bufferedOutput = true
            dispatch(request, change)
            // Deliberately do NOT idle the looper: service cancellation must be synchronous.
            assertEquals(index + 1, losses)
            assertFalse(pendingStart)
            assertFalse(output)
            assertFalse(bufferedOutput)
            assertFalse(helper.isGranted)
            dispatch(request, AudioManager.AUDIOFOCUS_GAIN)
            dispatch(request, change)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(index + 1, losses)
            assertFalse(helper.isGranted)
            assertFalse(output)
            assertFalse(bufferedOutput)
        }
        assertEquals(3, audio.requests)
        assertEquals(3, audio.abandons)
    }

    @Test fun explicitPauseOrRevocationAbandonsIdempotentlyAndFencesQueuedCallbacks() {
        assertTrue(helper.requestForExplicitStart())
        val old = lastRequest()
        enqueue(old, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        enqueue(old, AudioManager.AUDIOFOCUS_GAIN)
        helper.abandon()
        helper.abandon()
        assertFalse(helper.isGranted)
        assertSame(old, audio.lastAbandonedAudioFocusRequest)
        assertEquals(1, audio.abandons)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, losses)
        assertFalse(helper.isGranted)
        assertEquals(1, audio.requests)
    }

    @Test fun queuedOldSessionLossCannotStopFreshExplicitStart() {
        assertTrue(helper.requestForExplicitStart())
        val old = lastRequest()
        enqueue(old, AudioManager.AUDIOFOCUS_LOSS)
        enqueue(old, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        enqueue(old, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        enqueue(old, AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(helper.requestForExplicitStart())
        val fresh = lastRequest()
        assertNotSame(old, fresh)
        assertNotSame(listener(old), listener(fresh))
        assertSame(old, audio.lastAbandonedAudioFocusRequest)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(helper.isGranted)
        assertEquals(0, losses)
        assertEquals(1, audio.abandons)
        // Fencing the old listener must not suppress the current listener.
        dispatch(fresh, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(helper.isGranted)
        assertEquals(1, losses)
        assertSame(fresh, audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun gainAfterLossCannotGrantFocusButFreshExplicitRequestCan() {
        assertTrue(helper.requestForExplicitStart())
        val old = lastRequest()
        dispatch(old, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        dispatch(old, AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(helper.isGranted)
        assertEquals(1, audio.requests)
        assertTrue(helper.requestForExplicitStart())
        assertTrue(helper.isGranted)
        dispatch(old, AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(helper.isGranted)
        assertEquals(1, losses)
        assertEquals(2, audio.requests)
    }

    @Test fun deniedReplacementRevokesOldGrantAndCannotBeResurrectedByEitherListener() {
        assertTrue(helper.requestForExplicitStart())
        val old = lastRequest()
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(helper.requestForExplicitStart())
        val denied = lastRequest()
        for (request in listOf(old, denied)) {
            dispatch(request, AudioManager.AUDIOFOCUS_GAIN)
            dispatch(request, AudioManager.AUDIOFOCUS_LOSS)
        }
        assertFalse(helper.isGranted)
        assertEquals(0, losses)
        assertEquals(2, audio.abandons)
    }

    @Test fun releaseAbandonsAndPermanentlyRejectsStaleStartReferences() {
        assertTrue(helper.requestForExplicitStart())
        val old = lastRequest()
        enqueue(old, AudioManager.AUDIOFOCUS_LOSS)
        enqueue(old, AudioManager.AUDIOFOCUS_GAIN)
        helper.release()
        helper.release()
        assertFalse(helper.requestForExplicitStart())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(helper.isGranted)
        assertEquals(0, losses)
        assertEquals(1, audio.requests)
        assertEquals(1, audio.abandons)
        assertSame(old, audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun abandonmentExceptionDoesNotKeepGrantOrPreventSynchronousLossCallback() {
        assertTrue(helper.requestForExplicitStart())
        val old = lastRequest()
        audio.abandonFailure = IllegalStateException("Synthetic audio service teardown")
        dispatch(old, AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(helper.isGranted)
        assertEquals(1, losses)
        dispatch(old, AudioManager.AUDIOFOCUS_GAIN)
        dispatch(old, AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(helper.isGranted)
        assertEquals(1, losses)
        helper.release()
        assertFalse(helper.requestForExplicitStart())
    }

    @Test fun synchronousLossDuringRequestCannotBeOverwrittenByGrantedReturnValue() {
        audio.duringRequest = { dispatch(it, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) }
        assertFalse(helper.requestForExplicitStart())
        assertFalse(helper.isGranted)
        assertEquals(1, losses)
        assertEquals(1, audio.abandons)
    }

    @Test fun reentrantFreshRequestFromLossCallbackIsNotAbandonedByOldRequestReturn() {
        audio.duringRequest = {
            audio.duringRequest = null
            dispatch(it, AudioManager.AUDIOFOCUS_LOSS)
        }
        onLoss = { assertTrue(helper.requestForExplicitStart()) }
        assertFalse(helper.requestForExplicitStart())
        assertTrue(helper.isGranted)
        assertEquals(1, losses)
        assertEquals(2, audio.requests)
        assertEquals(1, audio.abandons)
        assertNotSame(lastRequest(), audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun releaseWhileRequestIsInFlightCannotBeOverwrittenByGrantedReturnValue() {
        audio.duringRequest = { helper.release() }
        assertFalse(helper.requestForExplicitStart())
        assertFalse(helper.isGranted)
        assertFalse(helper.requestForExplicitStart())
        assertEquals(0, losses)
        assertEquals(1, audio.requests)
        assertEquals(1, audio.abandons)
    }

    private fun lastRequest(): PlatformAudioFocusRequest =
        requireNotNull(requireNotNull(audio.lastAudioFocusRequest).audioFocusRequest)

    private fun listener(request: PlatformAudioFocusRequest): AudioManager.OnAudioFocusChangeListener =
        requireNotNull(audio.listeners[request])

    private fun dispatch(request: PlatformAudioFocusRequest, change: Int) {
        listener(request).onAudioFocusChange(change)
    }

    private fun enqueue(request: PlatformAudioFocusRequest, change: Int) {
        Handler(Looper.getMainLooper()).post { dispatch(request, change) }
    }

    /** Keep Robolectric's real request recording; add only synthetic failures/reentrant edges. */
    @Implements(AudioManager::class)
    class FocusAudioManager : ShadowAudioManager() {
        var requests = 0
        var abandons = 0
        var requestFailure: RuntimeException? = null
        var abandonFailure: RuntimeException? = null
        var duringRequest: ((PlatformAudioFocusRequest) -> Unit)? = null
        val listeners = java.util.IdentityHashMap<PlatformAudioFocusRequest, AudioManager.OnAudioFocusChangeListener>()

        @Implementation
        override fun requestAudioFocus(request: PlatformAudioFocusRequest): Int {
            requests++
            val result = super.requestAudioFocus(request)
            // The platform listener getter is hidden from android.jar; use Robolectric's record.
            listeners[request] = requireNotNull(requireNotNull(lastAudioFocusRequest).listener)
            requestFailure?.let { throw it }
            duringRequest?.invoke(request)
            return result
        }

        @Implementation
        override fun abandonAudioFocusRequest(request: PlatformAudioFocusRequest): Int {
            abandons++
            val result = super.abandonAudioFocusRequest(request)
            abandonFailure?.let { throw it }
            return result
        }
    }
}
