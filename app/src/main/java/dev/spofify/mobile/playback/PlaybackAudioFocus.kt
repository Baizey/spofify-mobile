package dev.spofify.mobile.playback

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Explicit audio-focus ownership for the one service player. All calls use the main looper.
 * ExoPlayer's `handleAudioFocus` MUST be false when this helper owns focus.
 *
 * Only an authorized explicit start may call [requestForExplicitStart]. Foreground promotion
 * must already have succeeded where required by Android; focus is not foreground/lease authority.
 * Check the result, [isGranted], and the service's session/start generation before starting output.
 * A denied/delayed request is a failure, never permission to wait for an automatic resume.
 *
 * [onFocusLost] runs synchronously inside the current request's loss callback, including ducking.
 * It MUST invalidate pending starts/authority and pause AND stop buffered output before returning.
 * This helper owns no player and cannot perform that service-level invalidation itself.
 * Call [abandon] on pause, revocation, failed start, or other output shutdown, and [release] on
 * service teardown. Neither focus gain nor any other callback ever starts/resumes playback.
 */
internal class PlaybackAudioFocus(
    private val audioManager: AudioManager,
    private val onFocusLost: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var request: AudioFocusRequest? = null
    private var released = false

    var isGranted: Boolean = false
        private set

    /** A fresh request/listener per explicit start fences callbacks queued by older requests. */
    fun requestForExplicitStart(): Boolean {
        checkMainThread()
        if (released) return false
        abandon()
        val requestedGeneration = generation
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAcceptsDelayedFocusGain(false)
            // Ask for a loss callback instead of letting the system silently duck music.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                checkMainThread()
                if (generation == requestedGeneration && request != null && change < 0) {
                    // Fence callbacks and clear the grant BEFORE calling Android or service code.
                    abandon()
                    onFocusLost()
                }
                // In particular, GAIN is never a new grant or a resume instruction.
            }, handler)
            .build()
        request = next
        val response = try {
            audioManager.requestAudioFocus(next)
        } catch (_: RuntimeException) {
            // Includes platform/security/background restrictions. Fail closed, without retry.
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        // Loss/teardown can happen reentrantly while requesting. Never resurrect that request
        // or abandon a newer one that service code may have installed during the callback.
        if (generation != requestedGeneration || request !== next) return false
        if (response != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            abandon()
            return false
        }
        isGranted = true
        return true
    }

    /** Idempotent local revocation, even if Android's best-effort abandonment throws. */
    fun abandon() {
        checkMainThread()
        val previous = request
        generation++
        request = null
        isGranted = false
        if (previous != null) {
            try {
                audioManager.abandonAudioFocusRequest(previous)
            } catch (_: RuntimeException) {
                // Teardown must still invalidate callbacks and let the service stop output.
            }
        }
    }

    /** Terminal: a destroyed service cannot acquire focus through a stale helper reference. */
    fun release() {
        checkMainThread()
        released = true
        abandon()
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Playback focus must use the main looper" }
    }
}
