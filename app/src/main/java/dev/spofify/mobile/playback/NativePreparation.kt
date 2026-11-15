package dev.spofify.mobile.playback

import androidx.media3.common.Player
import dev.spofify.mobile.core.playback.AudioBinding
import dev.spofify.mobile.core.playback.AudioResult
import kotlinx.coroutines.CompletableDeferred

/** One pending native setup; a later generation must never complete its predecessor. */
internal class NativePreparation(
    val generation: Long,
    val binding: AudioBinding,
    val trackId: String,
    val positionMs: Long,
    val playRequested: Boolean,
    val result: CompletableDeferred<AudioResult> = CompletableDeferred(),
) {
    fun matches(currentGeneration: Long, currentBinding: AudioBinding?, currentTrackId: String?): Boolean =
        generation == currentGeneration && binding == currentBinding && trackId == currentTrackId

    fun outcome(state: Int, hasError: Boolean, durationMs: Long): AudioResult? = when {
        hasError -> AudioResult.MEDIA
        state == Player.STATE_READY -> AudioResult.OK
        state == Player.STATE_ENDED -> {
            // A stopped EOF reload need not visit READY. Allow only a known EOF;
            // the 2 ms margin covers seconds-to-milliseconds floor/float rounding.
            if (durationMs > 0 && positionMs >= durationMs - 2) AudioResult.OK else AudioResult.MEDIA
        }
        else -> null
    }

    fun reportsNativeEnd(state: Int, hasError: Boolean, durationMs: Long): Boolean =
        playRequested && state == Player.STATE_ENDED && outcome(state, hasError, durationMs) == AudioResult.OK
}
