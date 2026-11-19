package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackView

internal fun playbackNotice(view: PlaybackView): String? = when (view.connection) {
    PlaybackConnectionState.CONNECTING -> "Connecting playback"
    PlaybackConnectionState.DISCONNECTED -> "Reconnecting playback"
    PlaybackConnectionState.CLOSED -> "Playback unavailable"
    PlaybackConnectionState.READY -> view.error
}
