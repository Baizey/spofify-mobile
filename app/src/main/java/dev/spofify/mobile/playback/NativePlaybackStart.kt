package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.AudioResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal const val NATIVE_PLAYBACK_START_TIMEOUT_MS = 5_000L

internal enum class NativeStartStatus { WAITING, SUPPRESSED, ADVANCING, ENDED, REFUSED, FAILED }

/** `play()` masks playWhenReady/isPlaying before Media3 requests focus on its playback looper. */
internal suspend fun awaitNativePlaybackStart(
    current: () -> Boolean,
    status: () -> NativeStartStatus,
): AudioResult = withTimeoutOrNull(NATIVE_PLAYBACK_START_TIMEOUT_MS) {
    var result: AudioResult? = null
    while (result == null) {
        if (!current()) return@withTimeoutOrNull AudioResult.REJECTED
        result = when (status()) {
            NativeStartStatus.WAITING, NativeStartStatus.SUPPRESSED -> null
            NativeStartStatus.ADVANCING, NativeStartStatus.ENDED -> AudioResult.OK
            NativeStartStatus.REFUSED -> AudioResult.BLOCKED
            NativeStartStatus.FAILED -> AudioResult.MEDIA
        }
        if (result == null) delay(50)
    }
    result
} ?: when {
    !current() -> AudioResult.REJECTED
    status() == NativeStartStatus.SUPPRESSED -> AudioResult.BLOCKED
    else -> AudioResult.MEDIA
}
