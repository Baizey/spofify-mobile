package dev.spofify.mobile.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Only a MediaSession controller request is a musical intent; framework cleanup is not. */
@UnstableApi
internal class RoutingPlayer(
    player: Player,
    private val controller: AppPlayback,
    private val isControllerRequest: () -> Boolean,
) : ForwardingPlayer(player) {
    private fun dispatch(command: () -> Unit) {
        if (!isControllerRequest()) return
        command()
    }

    override fun play() { dispatch { controller.resume() } }
    override fun pause() { dispatch { controller.pause() } }
    override fun stop() { dispatch { controller.pause() } }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        dispatch { if (playWhenReady) controller.resume() else controller.pause() }
    }
    override fun seekTo(positionMs: Long) { dispatch { controller.seek(positionMs.coerceAtLeast(0) / 1000.0) } }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { seekTo(positionMs) }
    override fun seekToDefaultPosition() { dispatch { controller.seek(0.0) } }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { seekToDefaultPosition() }
    override fun seekToNext() { dispatch { controller.next() } }
    override fun seekToNextMediaItem() { seekToNext() }
    override fun seekToPrevious() { dispatch { controller.previous() } }
    override fun seekToPreviousMediaItem() { seekToPrevious() }
    override fun seekBack() {
        dispatch { controller.seek((controller.presentation.elapsed - 10).coerceAtLeast(0.0)) }
    }
    override fun seekForward() {
        dispatch { controller.seek(controller.presentation.elapsed + 10) }
    }
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) { dispatch { controller.setShuffle(shuffleModeEnabled) } }
    override fun setRepeatMode(repeatMode: Int) {
        dispatch {
            controller.setRepeat(when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "one"
                Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            })
        }
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
