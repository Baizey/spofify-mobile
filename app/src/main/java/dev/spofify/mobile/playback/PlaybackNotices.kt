package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackView

internal fun playbackNotice(view: PlaybackView): String? = when {
    view.superseded -> "Playback connection replaced"
    view.offline -> "Offline"
    view.connection == PlaybackConnectionState.CLOSED -> "Playback unavailable"
    view.error == "failed" || view.error == "unavailable" -> "Playback action unavailable"
    else -> null
}

internal fun playbackFailureNotice(error: String?): String? = when (error) {
    "blocked" -> "Audio output unavailable"
    "connection" -> "Paused: connection lost"
    "media", "unavailable" -> "Audio unavailable"
    else -> null
}
