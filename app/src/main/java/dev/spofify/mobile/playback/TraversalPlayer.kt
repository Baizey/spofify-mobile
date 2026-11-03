package dev.spofify.mobile.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Semantic restarts are observed at execution time; ordinary scrubbing is not a traversal. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class TraversalPlayer(private val output: Player, private val onRestart: () -> Unit) : ForwardingPlayer(output) {
    override fun seekToPrevious() {
        if (output.currentPosition > output.maxSeekToPreviousPosition || !output.hasPreviousMediaItem()) onRestart()
        super.seekToPrevious()
    }

    override fun seekToDefaultPosition() {
        if (output.playbackState == Player.STATE_ENDED) onRestart()
        super.seekToDefaultPosition()
    }

    override fun play() {
        if (output.playbackState == Player.STATE_ENDED) onRestart()
        super.play()
    }
}
