package dev.spofify.mobile.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import java.util.IdentityHashMap

/**
 * Read-through session view of the service output, with transport intent sent to its authority.
 *
 * [snapshot] is sampled at execution time, not controller authorization time. Its queue is in
 * traversal order, which need not match Media3's physical timeline. [keyAtMediaItemIndex] must
 * resolve a physical output index to that exact occurrence's wire key (or null if unavailable).
 * Never implement it as `snapshot().queue[index]` when the output uses a shuffle order.
 *
 * The protocol has no atomic jump-and-seek or arbitrary repeat setter. Unrepresentable requests
 * are ignored, not approximated with multiple commands against potentially changing authority.
 * Queue/speed edits are unsupported even if a stale controller still advertises their commands.
 * Local output configuration, listeners and getters retain ForwardingPlayer's normal behavior.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class CoordinatedSessionPlayer(
    private val output: Player,
    private val dispatch: (PlaybackDeviceCommand) -> Unit,
    private val snapshot: () -> PlaybackSnapshot,
    private val keyAtMediaItemIndex: (Int) -> Long?,
) : ForwardingPlayer(output) {
    // Media3's resumption path also inspects the player, not just controller allowlists.
    // An explicit allowlist prevents future raw mutators from becoming session capabilities.
    override fun getAvailableCommands(): Player.Commands = sessionCommands(output.availableCommands)

    private fun sessionCommands(raw: Player.Commands): Player.Commands {
        val result = Player.Commands.Builder()
        for (index in 0 until SESSION_COMMANDS.size()) {
            val command = SESSION_COMMANDS[index]
            if (raw.contains(command)) result.add(command)
        }
        return result.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    private val sessionListeners = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        synchronized(sessionListeners) {
            val filtered = sessionListeners.getOrPut(listener) {
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                object : Player.Listener {
                    private var lastCommands = availableCommands
                    private var visibleCommandChange = false

                    // Java default listener methods are not forwarded by Kotlin interface delegation.
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)
                    override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(mediaMetadata)
                    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)
                    override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)
                    override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)
                    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = listener.onTrackSelectionParametersChanged(parameters)
                    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = listener.onPlayerStateChanged(playWhenReady, playbackState)
                    override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)
                    override fun onPlaybackSuppressionReasonChanged(reason: Int) = listener.onPlaybackSuppressionReasonChanged(reason)
                    override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
                    override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)
                    override fun onShuffleModeEnabledChanged(enabled: Boolean) = listener.onShuffleModeEnabledChanged(enabled)
                    override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)
                    override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)
                    override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
                    override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = listener.onPlaybackParametersChanged(parameters)
                    override fun onSeekBackIncrementChanged(increment: Long) = listener.onSeekBackIncrementChanged(increment)
                    override fun onSeekForwardIncrementChanged(increment: Long) = listener.onSeekForwardIncrementChanged(increment)
                    override fun onMaxSeekToPreviousPositionChanged(position: Long) = listener.onMaxSeekToPreviousPositionChanged(position)
                    override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
                    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)
                    override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
                    override fun onSkipSilenceEnabledChanged(enabled: Boolean) = listener.onSkipSilenceEnabledChanged(enabled)
                    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
                    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
                    override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)
                    override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)
                    override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()
                    override fun onCues(cues: List<Cue>) = listener.onCues(cues)
                    override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)
                    override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)

                    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                        val filteredCommands = sessionCommands(availableCommands)
                        if (filteredCommands != lastCommands) {
                            lastCommands = filteredCommands
                            visibleCommandChange = true
                            listener.onAvailableCommandsChanged(filteredCommands)
                        }
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        val removeCommandEvent = events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED) && !visibleCommandChange
                        visibleCommandChange = false
                        if (!removeCommandEvent) {
                            listener.onEvents(this@CoordinatedSessionPlayer, events)
                            return
                        }
                        val remaining = FlagSet.Builder()
                        for (index in 0 until events.size()) {
                            val event = events[index]
                            if (event != Player.EVENT_AVAILABLE_COMMANDS_CHANGED) remaining.add(event)
                        }
                        val filteredEvents = Player.Events(remaining.build())
                        if (filteredEvents.size() > 0) listener.onEvents(this@CoordinatedSessionPlayer, filteredEvents)
                    }
                }
            }
            // Let ForwardingPlayer retain its listener bookkeeping and wrapper player identity.
            super.addListener(filtered)
        }
    }

    override fun removeListener(listener: Player.Listener) {
        synchronized(sessionListeners) {
            super.removeListener(sessionListeners.remove(listener) ?: listener)
        }
    }

    // A stale prepare must never open a source/start buffered audio without current authority.
    override fun prepare() = Unit
    override fun play() = transport("resume")
    override fun pause() = transport("pause")
    override fun setPlayWhenReady(playWhenReady: Boolean) = transport(if (playWhenReady) "resume" else "pause")
    // System Stop is a pause intent, not a coordinator close or service lifetime operation.
    override fun stop() = transport("pause")
    override fun release() = Unit // Only the service owns/releases the actual output.

    override fun seekToNext() = transport("next")
    override fun seekToNextMediaItem() = transport("next")
    override fun seekToPrevious() = transport("previous")
    override fun seekToPreviousMediaItem() = transport("previous")

    // Default position is a semantic restart; seek(0) is only an ordinary scrub.
    override fun seekToDefaultPosition() {
        val state = snapshot()
        state.queue.getOrNull(state.position)?.let { jump(it.key) }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        resolvedKey(mediaItemIndex, snapshot())?.let(::jump)
    }

    override fun seekTo(positionMs: Long) {
        if (positionMs == C.TIME_UNSET) seekToDefaultPosition() else seekSeconds(positionMs / 1000.0)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val state = snapshot()
        val key = resolvedKey(mediaItemIndex, state) ?: return
        when {
            positionMs == C.TIME_UNSET -> jump(key)
            key == state.queue.getOrNull(state.position)?.key -> seekSeconds(positionMs / 1000.0)
            positionMs == 0L -> jump(key)
            // A non-current item plus a nonzero offset cannot be one protocol command.
            else -> Unit
        }
    }

    override fun seekBack() = seekRelative(-output.seekBackIncrement / 1000.0)
    override fun seekForward() = seekRelative(output.seekForwardIncrement / 1000.0)

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled != snapshot().shuffle) transport("shuffle")
    }

    override fun setRepeatMode(repeatMode: Int) {
        val desired = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> "off"
            Player.REPEAT_MODE_ALL -> "all"
            Player.REPEAT_MODE_ONE -> "one"
            else -> return
        }
        val next = when (snapshot().repeat) {
            "off" -> "all"
            "all" -> "one"
            "one" -> "off"
            else -> return
        }
        if (desired == next) transport("repeat")
    }

    private fun transport(type: String) = dispatch(PlaybackDeviceCommand.transport(type))
    private fun jump(key: Long) = dispatch(PlaybackDeviceCommand.queue("jump", key))

    private fun resolvedKey(index: Int, state: PlaybackSnapshot): Long? {
        if (index < 0 || index >= output.mediaItemCount) return null
        val key = keyAtMediaItemIndex(index) ?: return null
        return key.takeIf { candidate -> state.queue.any { it.key == candidate } }
    }

    private fun seekRelative(delta: Double) {
        val state = snapshot()
        if (state.queue.getOrNull(state.position) == null || !state.elapsed.isFinite()) return
        val upper = state.duration.takeIf { it.isFinite() && it > 0.0 } ?: MAX_SEEK_SECONDS
        seekSeconds((state.elapsed + delta).coerceIn(0.0, upper.coerceAtMost(MAX_SEEK_SECONDS)))
    }

    private fun seekSeconds(seconds: Double) {
        if (seconds.isFinite() && seconds in 0.0..MAX_SEEK_SECONDS) {
            dispatch(PlaybackDeviceCommand.seek(seconds))
        }
    }

    private fun unsupported(): Unit = throw UnsupportedOperationException("Session queue and speed edits require playback authority")

    override fun setMediaItem(mediaItem: MediaItem) = unsupported()
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = unsupported()
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = unsupported()
    override fun setMediaItems(mediaItems: List<MediaItem>) = unsupported()
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = unsupported()
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = unsupported()
    override fun addMediaItem(mediaItem: MediaItem) = unsupported()
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = unsupported()
    override fun addMediaItems(mediaItems: List<MediaItem>) = unsupported()
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = unsupported()
    override fun removeMediaItem(index: Int) = unsupported()
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = unsupported()
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = unsupported()
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) = unsupported()
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) = unsupported()
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = unsupported()
    override fun clearMediaItems() = unsupported()
    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) = unsupported()
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = unsupported()
    override fun setPlaybackSpeed(speed: Float) = unsupported()

    private companion object {
        const val MAX_SEEK_SECONDS = 604800.0
        val SESSION_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAllReadOnlyCommands()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
            )
            .build()
    }
}
