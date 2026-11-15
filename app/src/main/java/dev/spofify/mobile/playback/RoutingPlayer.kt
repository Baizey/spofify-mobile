package dev.spofify.mobile.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.core.playback.PlaybackController

/** System/headset controls submit server intents, never bypass the executor lease. */
@UnstableApi
internal class RoutingPlayer(player: Player, private val controller: PlaybackController) : ForwardingPlayer(player) {
    override fun play() { controller.resume() }
    override fun pause() { controller.pause() }
    override fun stop() { controller.pause() }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) controller.resume() else controller.pause()
    }
    override fun seekTo(positionMs: Long) { controller.seek(positionMs.coerceAtLeast(0) / 1000.0) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { seekTo(positionMs) }
    override fun seekToDefaultPosition() { controller.seek(0.0) }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { controller.seek(0.0) }
    override fun seekToNext() { controller.next() }
    override fun seekToNextMediaItem() { controller.next() }
    override fun seekToPrevious() { controller.previous() }
    override fun seekToPreviousMediaItem() { controller.previous() }
    override fun seekBack() {
        controller.seek(((controller.state.value.playback?.elapsed ?: 0.0) - 10).coerceAtLeast(0.0))
    }
    override fun seekForward() {
        controller.seek((controller.state.value.playback?.elapsed ?: 0.0) + 10)
    }
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) { controller.setShuffle(shuffleModeEnabled) }
    override fun setRepeatMode(repeatMode: Int) {
        controller.setRepeat(when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "one"
            Player.REPEAT_MODE_ALL -> "all"
            else -> "off"
        })
    }

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .remove(Player.COMMAND_SET_MEDIA_ITEM)
        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        .remove(Player.COMMAND_PREPARE)
        .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_SET_SHUFFLE_MODE)
        .add(Player.COMMAND_SET_REPEAT_MODE)
        .build()
}
