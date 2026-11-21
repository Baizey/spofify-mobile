package dev.spofify.mobile.playback

import androidx.media3.common.FlagSet
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
    private val listeners = mutableMapOf<Player.Listener, Player.Listener>()

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
    override fun getSeekBackIncrement() = SEEK_INCREMENT_MS
    override fun getSeekForwardIncrement() = SEEK_INCREMENT_MS
    override fun seekBack() { seekBy(-seekBackIncrement) }
    override fun seekForward() { seekBy(seekForwardIncrement) }
    private fun seekBy(incrementMs: Long) {
        dispatch { controller.seek((controller.presentation.elapsed + incrementMs / 1000.0).coerceAtLeast(0.0)) }
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

    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    override fun getAvailableCommands(): Player.Commands = project(super.getAvailableCommands())

    override fun addListener(listener: Player.Listener) {
        if (listeners.containsKey(listener)) return
        val projected = object : Player.Listener by listener {
            private var commands = availableCommands
            private var commandsChanged = false

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                val updated = project(availableCommands)
                if (commands == updated) return
                commands = updated
                commandsChanged = true
                listener.onAvailableCommandsChanged(updated)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val changed = commandsChanged
                commandsChanged = false
                if (!events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED) || changed) {
                    listener.onEvents(player, events)
                    return
                }
                // A native-only seek capability can change without changing our exported commands.
                val flags = FlagSet.Builder()
                for (index in 0 until events.size()) {
                    val event = events[index]
                    if (event != Player.EVENT_AVAILABLE_COMMANDS_CHANGED) flags.add(event)
                }
                val filtered = Player.Events(flags.build())
                if (filtered.size() != 0) listener.onEvents(player, filtered)
            }
        }
        listeners[listener] = projected
        super.addListener(projected)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 10_000L

        fun project(native: Player.Commands): Player.Commands = native.buildUpon().apply {
            projectTransportCommands(add = { add(it) }, remove = { remove(it) })
        }.build()
    }
}

/** Independent of whether the current deck has a physical neighbor or can seek. */
@UnstableApi
internal fun projectTransportCommands(add: (Int) -> Unit, remove: (Int) -> Unit) {
    remove(Player.COMMAND_SET_MEDIA_ITEM)
    remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
    remove(Player.COMMAND_PREPARE)
    // At EOF Media3 otherwise sends both a default seek and Play. The app controller owns replay.
    remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
    remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
    remove(Player.COMMAND_SET_SPEED_AND_PITCH)
    add(Player.COMMAND_PLAY_PAUSE)
    add(Player.COMMAND_STOP)
    add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    // Hardware uses the generic commands; other controllers can also use the media-item forms.
    // The deprecated window constants already alias these media-item command IDs.
    add(Player.COMMAND_SEEK_TO_NEXT)
    add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    add(Player.COMMAND_SEEK_TO_PREVIOUS)
    add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    add(Player.COMMAND_SEEK_BACK)
    add(Player.COMMAND_SEEK_FORWARD)
    add(Player.COMMAND_SET_SHUFFLE_MODE)
    add(Player.COMMAND_SET_REPEAT_MODE)
}
