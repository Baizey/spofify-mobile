package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackView

internal fun playbackNotice(view: PlaybackView): String? = when (view.connection) {
    PlaybackConnectionState.CONNECTING -> "Connecting playback"
    PlaybackConnectionState.DISCONNECTED -> "Reconnecting playback"
    PlaybackConnectionState.CLOSED -> "Playback unavailable"
    PlaybackConnectionState.READY -> playbackFailureNotice(view.playback?.error) ?: view.error
}

internal fun playbackFailureNotice(error: String?): String? = when (error) {
    "blocked" -> "Audio output unavailable"
    "media", "unavailable" -> "Audio unavailable"
    else -> null
}
