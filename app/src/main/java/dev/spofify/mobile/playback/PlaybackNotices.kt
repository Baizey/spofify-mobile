package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.PlaybackCommandFailure
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackView

/** Literal safe protocol facts, not a guessed diagnosis such as an output failure. */
internal fun PlaybackCommandFailure.diagnosticText(): String = buildString {
    append("Playback ").append(command).append(": ").append(outcome)
    append(" · code: ").append(code.ifEmpty { "none" })
    append(" · execution: ").append(execution)
    transferPhase?.let { append(" · handoff at result: ").append(it) }
}

internal fun playbackNotice(view: PlaybackView): String? = when (view.connection) {
    PlaybackConnectionState.CONNECTING -> "Connecting playback"
    PlaybackConnectionState.DISCONNECTED -> "Reconnecting playback"
    PlaybackConnectionState.CLOSED -> "Playback unavailable"
    PlaybackConnectionState.READY -> view.commandFailure?.diagnosticText() ?: view.error
}
