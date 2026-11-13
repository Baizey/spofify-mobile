package dev.spofify.mobile.playback

import android.os.SystemClock
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
import dev.spofify.mobile.core.LocalPlaybackCommand
import dev.spofify.mobile.core.LibraryPlaybackCommand
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
 * The protocol has no atomic jump-and-seek. Absolute mode setters use typed server commands; unrepresentable requests
 * are ignored, not approximated with multiple commands against potentially changing authority.
 * Queue/speed edits are unsupported even if a stale controller still advertises their commands.
 * A non-null remote snapshot selects an in-memory, metadata-only view, even for an empty queue.
 * It never prepares media or grants authority. Audio/stream configuration remains local.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class CoordinatedSessionPlayer(
    private val output: Player,
    private val dispatch: (LocalPlaybackCommand) -> Unit,
    private val snapshot: () -> PlaybackSnapshot,
    private val keyAtMediaItemIndex: (Int) -> Long?,
) : ForwardingPlayer(output) {
    // Media3's resumption path also inspects the player, not just controller allowlists.
    // An explicit allowlist prevents future raw mutators from becoming session capabilities.
    override fun getAvailableCommands(): Player.Commands = remoteView()?.let(::remoteCommands)
        ?: sessionCommands(output.availableCommands)

    private var remoteSnapshotProvider: () -> PlaybackSnapshot? = { null }
    private var modeDispatcher: (LibraryPlaybackCommand) -> Unit = {}

    /** Same-process service ingress, still guarded by the normal controller admission. */
    fun setModeDispatcher(dispatch: (LibraryPlaybackCommand) -> Unit) { modeDispatcher = dispatch }
    private var cachedRemote: RemoteSessionView? = null
    private var cachedRemoteSource: PlaybackSnapshot? = null

    /** Install on the player's application thread; null means local, not an empty remote queue. */
    fun setRemoteSnapshotProvider(provider: () -> PlaybackSnapshot?) {
        remoteSnapshotProvider = provider
    }

    private fun remoteView(): RemoteSessionView? {
        val state = remoteSnapshotProvider() ?: return null
        // The service publishes immutable snapshots. Most getter calls see the same source;
        // avoid comparing the projection's copied queue/artist lists on every getter.
        if (cachedRemoteSource === state) return cachedRemote
        val view = cachedRemote?.takeIf { it.snapshot == state } ?: RemoteSessionView(state)
        cachedRemoteSource = state
        cachedRemote = view
        return view
    }

    private fun remoteCommands(view: RemoteSessionView): Player.Commands {
        val result = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
        )
        if (view.currentIndex != C.INDEX_UNSET) {
            result.addAll(Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_PREVIOUS)
            if (adjacentIndex(view, forward = true) != C.INDEX_UNSET)
                result.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (adjacentIndex(view, forward = false) != C.INDEX_UNSET)
                result.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return result.build()
    }

    // Explicit skips ignore repeat-one; the queue is already in realized traversal order.
    private fun adjacentIndex(view: RemoteSessionView, forward: Boolean): Int {
        if (view.currentIndex == C.INDEX_UNSET) return C.INDEX_UNSET
        val next = view.currentIndex + if (forward) 1 else -1
        return when {
            next in view.mediaItems.indices -> next
            view.snapshot.repeat == "all" -> if (forward) 0 else view.mediaItems.lastIndex
            else -> C.INDEX_UNSET
        }
    }

    private fun remotePlaybackState(view: RemoteSessionView): Int = when {
        view.currentIndex == C.INDEX_UNSET || view.snapshot.error.isNotEmpty() -> Player.STATE_IDLE
        view.snapshot.waiting || view.snapshot.loading -> Player.STATE_BUFFERING
        else -> Player.STATE_READY
    }

    private fun remoteReady(view: RemoteSessionView): Boolean =
        view.currentIndex != C.INDEX_UNSET && view.snapshot.error.isEmpty() && view.snapshot.playing

    private var lastErrorCode = ""
    private var lastRemoteError: PlaybackException? = null
    private fun remoteError(view: RemoteSessionView): PlaybackException? {
        if (lastErrorCode != view.snapshot.error) {
            lastErrorCode = view.snapshot.error
            lastRemoteError = if (lastErrorCode.isEmpty()) null else
                PlaybackException("Remote playback: $lastErrorCode", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }
        return lastRemoteError
    }

    override fun getCurrentTimeline(): Timeline = remoteView()?.timeline ?: super.getCurrentTimeline()
    override fun getMediaItemCount(): Int = remoteView()?.mediaItems?.size ?: super.getMediaItemCount()
    override fun getMediaItemAt(index: Int): MediaItem = remoteView()?.mediaItems?.get(index) ?: super.getMediaItemAt(index)
    override fun getCurrentMediaItem(): MediaItem? = remoteView().let { view ->
        if (view == null) super.getCurrentMediaItem() else view.mediaItems.getOrNull(view.currentIndex)
    }
    // Player uses prospective index zero for an empty timeline, unlike the wire's -1 sentinel.
    override fun getCurrentMediaItemIndex(): Int = remoteView()?.currentIndex?.coerceAtLeast(0) ?: super.getCurrentMediaItemIndex()
    override fun getCurrentPeriodIndex(): Int = remoteView()?.currentIndex?.coerceAtLeast(0) ?: super.getCurrentPeriodIndex()
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getCurrentWindowIndex(): Int = if (remoteView() == null) super.getCurrentWindowIndex() else currentMediaItemIndex
    override fun getNextMediaItemIndex(): Int = remoteView()?.let { adjacentIndex(it, true) } ?: super.getNextMediaItemIndex()
    override fun getPreviousMediaItemIndex(): Int = remoteView()?.let { adjacentIndex(it, false) } ?: super.getPreviousMediaItemIndex()
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getNextWindowIndex(): Int = if (remoteView() == null) super.getNextWindowIndex() else nextMediaItemIndex
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getPreviousWindowIndex(): Int = if (remoteView() == null) super.getPreviousWindowIndex() else previousMediaItemIndex
    override fun hasNextMediaItem(): Boolean = remoteView()?.let { adjacentIndex(it, true) != C.INDEX_UNSET } ?: super.hasNextMediaItem()
    override fun hasPreviousMediaItem(): Boolean = remoteView()?.let { adjacentIndex(it, false) != C.INDEX_UNSET } ?: super.hasPreviousMediaItem()
    override fun getMediaMetadata(): MediaMetadata = remoteView().let { view ->
        if (view == null) super.getMediaMetadata() else view.mediaItems.getOrNull(view.currentIndex)?.mediaMetadata ?: MediaMetadata.EMPTY
    }
    override fun getPlaylistMetadata(): MediaMetadata = if (remoteView() == null) super.getPlaylistMetadata() else MediaMetadata.EMPTY
    override fun getCurrentPosition(): Long = remoteView()?.positionMs ?: super.getCurrentPosition()
    override fun getDuration(): Long = remoteView()?.durationMs ?: super.getDuration()
    override fun getContentPosition(): Long = remoteView()?.positionMs ?: super.getContentPosition()
    override fun getContentDuration(): Long = remoteView()?.durationMs ?: super.getContentDuration()
    // The wire snapshot has no buffered range. Do not invent buffering/audible progress.
    override fun getBufferedPosition(): Long = remoteView()?.positionMs ?: super.getBufferedPosition()
    override fun getContentBufferedPosition(): Long = remoteView()?.positionMs ?: super.getContentBufferedPosition()
    override fun getTotalBufferedDuration(): Long = if (remoteView() == null) super.getTotalBufferedDuration() else 0L
    override fun getBufferedPercentage(): Int = if (remoteView() == null) super.getBufferedPercentage() else 0
    override fun getPlaybackState(): Int = remoteView()?.let(::remotePlaybackState) ?: super.getPlaybackState()
    override fun getPlayWhenReady(): Boolean = remoteView()?.let(::remoteReady) ?: super.getPlayWhenReady()
    override fun isPlaying(): Boolean = remoteView()?.let { remoteReady(it) && remotePlaybackState(it) == Player.STATE_READY } ?: super.isPlaying()
    override fun isLoading(): Boolean = remoteView()?.let { remotePlaybackState(it) == Player.STATE_BUFFERING } ?: super.isLoading()
    override fun getPlayerError(): PlaybackException? = remoteView().let { if (it == null) super.getPlayerError() else remoteError(it) }
    override fun getRepeatMode(): Int = remoteView()?.let { when (it.snapshot.repeat) {
        "all" -> Player.REPEAT_MODE_ALL
        "one" -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    } } ?: super.getRepeatMode()
    override fun getShuffleModeEnabled(): Boolean = remoteView()?.snapshot?.shuffle ?: super.getShuffleModeEnabled()
    override fun getPlaybackSuppressionReason(): Int = if (remoteView() == null) super.getPlaybackSuppressionReason() else Player.PLAYBACK_SUPPRESSION_REASON_NONE
    override fun getPlaybackParameters(): PlaybackParameters = if (remoteView() == null) super.getPlaybackParameters() else PlaybackParameters.DEFAULT
    override fun getCurrentTracks(): Tracks = if (remoteView() == null) super.getCurrentTracks() else Tracks.EMPTY
    override fun getCurrentManifest(): Any? = if (remoteView() == null) super.getCurrentManifest() else null
    override fun isPlayingAd(): Boolean = if (remoteView() == null) super.isPlayingAd() else false
    override fun getCurrentAdGroupIndex(): Int = if (remoteView() == null) super.getCurrentAdGroupIndex() else C.INDEX_UNSET
    override fun getCurrentAdIndexInAdGroup(): Int = if (remoteView() == null) super.getCurrentAdIndexInAdGroup() else C.INDEX_UNSET
    override fun isCurrentMediaItemSeekable(): Boolean = remoteView()?.let { it.currentIndex != C.INDEX_UNSET } ?: super.isCurrentMediaItemSeekable()
    override fun isCurrentMediaItemDynamic(): Boolean = if (remoteView() == null) super.isCurrentMediaItemDynamic() else false
    override fun isCurrentMediaItemLive(): Boolean = if (remoteView() == null) super.isCurrentMediaItemLive() else false
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun isCurrentWindowSeekable(): Boolean = if (remoteView() == null) super.isCurrentWindowSeekable() else isCurrentMediaItemSeekable
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun isCurrentWindowDynamic(): Boolean = if (remoteView() == null) super.isCurrentWindowDynamic() else false
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun isCurrentWindowLive(): Boolean = if (remoteView() == null) super.isCurrentWindowLive() else false
    override fun getCurrentLiveOffset(): Long = if (remoteView() == null) super.getCurrentLiveOffset() else C.TIME_UNSET

    /** Called by the service's existing main-thread tick; no polling, authority or output work. */
    fun refreshSessionState() {
        val view = remoteView()
        val listeners = synchronized(sessionListeners) { sessionListeners.values.toList() }
        listeners.forEach { it.publish(view) }
    }

    private fun sessionCommands(raw: Player.Commands): Player.Commands {
        val result = Player.Commands.Builder()
        for (index in 0 until SESSION_COMMANDS.size()) {
            val command = SESSION_COMMANDS[index]
            if (raw.contains(command)) result.add(command)
        }
        return result.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    private val sessionListeners = IdentityHashMap<Player.Listener, SessionListener>()

    override fun addListener(listener: Player.Listener) {
        synchronized(sessionListeners) {
            val filtered = sessionListeners.getOrPut(listener) { SessionListener(listener) }
            // Retain ForwardingPlayer's bookkeeping and wrapper identity (including duplicate add).
            super.addListener(filtered)
        }
    }

    override fun removeListener(listener: Player.Listener) {
        synchronized(sessionListeners) {
            super.removeListener(sessionListeners.remove(listener) ?: listener)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class SessionListener(private val listener: Player.Listener) : Player.Listener {
        private var lastCommands = availableCommands
        private var lastRemote = remoteView()
        private var lastPositionSampleTimeMs = SystemClock.elapsedRealtime()
        private var visibleCommandChange = false
        private inline fun local(callback: () -> Unit) { if (remoteView() == null) callback() }

        // Raw output state is not remote state. Only local audio/route callbacks pass remotely.
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = local { listener.onTimelineChanged(timeline, reason) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = local { listener.onMediaItemTransition(mediaItem, reason) }
        override fun onTracksChanged(tracks: Tracks) = local { listener.onTracksChanged(tracks) }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = local { listener.onMediaMetadataChanged(mediaMetadata) }
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = local { listener.onPlaylistMetadataChanged(mediaMetadata) }
        override fun onIsLoadingChanged(isLoading: Boolean) = local { listener.onIsLoadingChanged(isLoading) }
        override fun onLoadingChanged(isLoading: Boolean) = local { listener.onLoadingChanged(isLoading) }
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = local { listener.onTrackSelectionParametersChanged(parameters) }
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = local { listener.onPlayerStateChanged(playWhenReady, playbackState) }
        override fun onPlaybackStateChanged(playbackState: Int) = local { listener.onPlaybackStateChanged(playbackState) }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = local { listener.onPlayWhenReadyChanged(playWhenReady, reason) }
        override fun onPlaybackSuppressionReasonChanged(reason: Int) = local { listener.onPlaybackSuppressionReasonChanged(reason) }
        override fun onIsPlayingChanged(isPlaying: Boolean) = local { listener.onIsPlayingChanged(isPlaying) }
        override fun onRepeatModeChanged(repeatMode: Int) = local { listener.onRepeatModeChanged(repeatMode) }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) = local { listener.onShuffleModeEnabledChanged(enabled) }
        override fun onPlayerError(error: PlaybackException) = local { listener.onPlayerError(error) }
        override fun onPlayerErrorChanged(error: PlaybackException?) = local { listener.onPlayerErrorChanged(error) }
        override fun onPositionDiscontinuity(reason: Int) = local { listener.onPositionDiscontinuity(reason) }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = local { listener.onPositionDiscontinuity(oldPosition, newPosition, reason) }
        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = local { listener.onPlaybackParametersChanged(parameters) }
        override fun onSeekBackIncrementChanged(increment: Long) = local { listener.onSeekBackIncrementChanged(increment) }
        override fun onSeekForwardIncrementChanged(increment: Long) = local { listener.onSeekForwardIncrementChanged(increment) }
        override fun onMaxSeekToPreviousPositionChanged(position: Long) = local { listener.onMaxSeekToPreviousPositionChanged(position) }
        override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(enabled: Boolean) = local { listener.onSkipSilenceEnabledChanged(enabled) }
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = local { listener.onVideoSizeChanged(videoSize) }
        override fun onSurfaceSizeChanged(width: Int, height: Int) = local { listener.onSurfaceSizeChanged(width, height) }
        override fun onRenderedFirstFrame() = local { listener.onRenderedFirstFrame() }
        override fun onCues(cues: List<Cue>) = local { listener.onCues(cues) }
        override fun onCues(cueGroup: CueGroup) = local { listener.onCues(cueGroup) }
        override fun onMetadata(metadata: Metadata) = local { listener.onMetadata(metadata) }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            if (remoteView() != null) return
            val filtered = sessionCommands(availableCommands)
            if (filtered != lastCommands) {
                lastCommands = filtered
                visibleCommandChange = true
                listener.onAvailableCommandsChanged(filtered)
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val remote = remoteView() != null
            val remaining = FlagSet.Builder()
            for (index in 0 until events.size()) {
                val event = events[index]
                if (if (remote) event in LOCAL_OUTPUT_EVENTS else
                    event != Player.EVENT_AVAILABLE_COMMANDS_CHANGED || visibleCommandChange) remaining.add(event)
            }
            visibleCommandChange = false
            val filtered = Player.Events(remaining.build())
            if (filtered.size() > 0) listener.onEvents(this@CoordinatedSessionPlayer, filtered)
        }

        fun publish(view: RemoteSessionView?) {
            val previous = lastRemote
            val switched = (view == null) != (previous == null)
            lastRemote = view
            val events = FlagSet.Builder()
            val commands = view?.let(::remoteCommands) ?: sessionCommands(output.availableCommands)
            if (commands != lastCommands) {
                lastCommands = commands
                listener.onAvailableCommandsChanged(commands)
                events.add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
            }
            if (view != null || switched) {
                fun changed(event: Int, differs: Boolean, callback: () -> Unit) {
                    if (switched || differs) { callback(); events.add(event) }
                }
                val old = previous?.snapshot
                val state = view?.snapshot
                val currentChanged = previous?.keyAtMediaItemIndex(previous.currentIndex) != view?.keyAtMediaItemIndex(view.currentIndex)
                changed(Player.EVENT_TIMELINE_CHANGED,
                    old?.queue != state?.queue || previous?.durationMs != view?.durationMs || old?.position != state?.position) {
                    listener.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                }
                changed(Player.EVENT_MEDIA_ITEM_TRANSITION, currentChanged) {
                    listener.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                }
                changed(Player.EVENT_MEDIA_METADATA_CHANGED,
                    previous?.mediaItems?.getOrNull(previous.currentIndex)?.mediaMetadata != view?.mediaItems?.getOrNull(view.currentIndex)?.mediaMetadata) {
                    listener.onMediaMetadataChanged(mediaMetadata)
                }
                changed(Player.EVENT_PLAYBACK_STATE_CHANGED,
                    previous?.let(::remotePlaybackState) != view?.let(::remotePlaybackState)) {
                    listener.onPlaybackStateChanged(playbackState)
                }
                changed(Player.EVENT_PLAY_WHEN_READY_CHANGED, previous?.let(::remoteReady) != view?.let(::remoteReady)) {
                    listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                }
                if (switched || previous?.let(::remoteReady) != view?.let(::remoteReady) ||
                    previous?.let(::remotePlaybackState) != view?.let(::remotePlaybackState))
                    listener.onPlayerStateChanged(playWhenReady, playbackState)
                changed(Player.EVENT_IS_PLAYING_CHANGED,
                    previous?.let { remoteReady(it) && remotePlaybackState(it) == Player.STATE_READY } !=
                        view?.let { remoteReady(it) && remotePlaybackState(it) == Player.STATE_READY }) {
                    listener.onIsPlayingChanged(isPlaying)
                }
                changed(Player.EVENT_IS_LOADING_CHANGED,
                    previous?.let { remotePlaybackState(it) == Player.STATE_BUFFERING } != view?.let { remotePlaybackState(it) == Player.STATE_BUFFERING }) {
                    listener.onIsLoadingChanged(isLoading)
                    listener.onLoadingChanged(isLoading)
                }
                changed(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, old?.shuffle != state?.shuffle) {
                    listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
                }
                changed(Player.EVENT_REPEAT_MODE_CHANGED, old?.repeat != state?.repeat) {
                    listener.onRepeatModeChanged(repeatMode)
                }
                changed(Player.EVENT_PLAYER_ERROR, old?.error != state?.error) {
                    val error = playerError
                    listener.onPlayerErrorChanged(error)
                    if (error != null) listener.onPlayerError(error)
                }
                if (switched) {
                    listener.onTracksChanged(currentTracks)
                    listener.onPlaylistMetadataChanged(playlistMetadata)
                    listener.onPlaybackParametersChanged(playbackParameters)
                    listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
                    events.addAll(Player.EVENT_TRACKS_CHANGED, Player.EVENT_PLAYLIST_METADATA_CHANGED,
                        Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
                }
                // Compare against time since the last changed position, not the 100ms service
                // tick: a remote snapshot may stay unchanged between slower progress polls.
                // The wire carries no seek reason; tolerate two seconds of sampling/network
                // jitter, but publish forward jumps that cannot be ordinary wall-clock progress.
                val nowMs = SystemClock.elapsedRealtime()
                val elapsedSincePositionMs = (nowMs - lastPositionSampleTimeMs).coerceAtLeast(0)
                val positionChanged = previous != null && view != null && previous.positionMs != view.positionMs &&
                    (!remoteReady(previous) || !remoteReady(view) || view.positionMs < previous.positionMs ||
                        view.positionMs - previous.positionMs > elapsedSincePositionMs + 2_000L)
                if (switched || currentChanged || previous?.positionMs != view?.positionMs ||
                    previous?.let(::remoteReady) != view?.let(::remoteReady)) lastPositionSampleTimeMs = nowMs
                if ((currentChanged || positionChanged) && previous != null && view != null) {
                    val reason = if (currentChanged) Player.DISCONTINUITY_REASON_AUTO_TRANSITION else Player.DISCONTINUITY_REASON_INTERNAL
                    listener.onPositionDiscontinuity(reason)
                    listener.onPositionDiscontinuity(positionInfo(previous), positionInfo(view), reason)
                    events.add(Player.EVENT_POSITION_DISCONTINUITY)
                }
            }
            val batch = Player.Events(events.build())
            if (batch.size() > 0) listener.onEvents(this@CoordinatedSessionPlayer, batch)
        }
    }

    private fun positionInfo(view: RemoteSessionView): Player.PositionInfo {
        val key = view.keyAtMediaItemIndex(view.currentIndex)
        return Player.PositionInfo(key, view.currentIndex.coerceAtLeast(0), view.mediaItems.getOrNull(view.currentIndex),
            key, view.currentIndex.coerceAtLeast(0), view.positionMs, view.positionMs, C.INDEX_UNSET, C.INDEX_UNSET)
    }

    // A stale prepare must never open a source/start buffered audio without current authority.
    override fun prepare() = Unit
    override fun play() = transport("resume")
    override fun pause() = transport("pause")
    override fun setPlayWhenReady(playWhenReady: Boolean) = transport(if (playWhenReady) "resume" else "pause")
    // System Stop is a pause intent, not a coordinator close or service lifetime operation.
    override fun stop() = transport("pause")
    override fun release() = Unit // Only the service owns/releases the actual output.

    private fun skip(type: String, command: Int) {
        val view = remoteView()
        if (view == null || remoteCommands(view).contains(command)) transport(type)
    }

    override fun seekToNext() = skip("next", Player.COMMAND_SEEK_TO_NEXT)
    override fun seekToNextMediaItem() = skip("next", Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    override fun seekToPrevious() = skip("previous", Player.COMMAND_SEEK_TO_PREVIOUS)
    override fun seekToPreviousMediaItem() = skip("previous", Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

    // Default position is a semantic restart; seek(0) is only an ordinary scrub.
    override fun seekToDefaultPosition() {
        if (remoteView() != null) return
        val state = snapshot()
        state.queue.getOrNull(state.position)?.let { jump(it.key) }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (remoteView() != null) return
        resolvedKey(mediaItemIndex, snapshot())?.let(::jump)
    }

    override fun seekTo(positionMs: Long) {
        val view = remoteView()
        if (view != null) {
            if (view.currentIndex == C.INDEX_UNSET || positionMs < 0 || positionMs > MAX_SEEK_SECONDS * 1000) return
            val upper = view.durationMs.takeIf { it != C.TIME_UNSET } ?: (MAX_SEEK_SECONDS * 1000).toLong()
            seekSeconds(positionMs.coerceAtMost(upper) / 1000.0)
        } else if (positionMs == C.TIME_UNSET) seekToDefaultPosition() else seekSeconds(positionMs / 1000.0)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        // Indices are not fenced occurrence identities; stale controller indices fail closed remotely.
        if (remoteView() != null) return
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
        val view = remoteView()
        if (view != null && view.currentIndex == C.INDEX_UNSET) return
        if (shuffleModeEnabled != (view?.snapshot ?: snapshot()).shuffle)
            modeDispatcher(LibraryPlaybackCommand.SetShuffle(shuffleModeEnabled))
    }

    override fun setRepeatMode(repeatMode: Int) {
        val desired = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> "off"
            Player.REPEAT_MODE_ALL -> "all"
            Player.REPEAT_MODE_ONE -> "one"
            else -> return
        }
        val view = remoteView()
        if (view != null && view.currentIndex == C.INDEX_UNSET) return
        if (desired != (view?.snapshot ?: snapshot()).repeat)
            modeDispatcher(LibraryPlaybackCommand.SetRepeat(desired))
    }

    private fun transport(type: String) = dispatch(LocalPlaybackCommand.transport(type))
    private fun jump(key: Long) = dispatch(LocalPlaybackCommand.queue("jump", key))

    private fun resolvedKey(index: Int, state: PlaybackSnapshot): Long? {
        if (index < 0 || index >= output.mediaItemCount) return null
        val key = keyAtMediaItemIndex(index) ?: return null
        return key.takeIf { candidate -> state.queue.any { it.key == candidate } }
    }

    private fun seekRelative(delta: Double) {
        val view = remoteView()
        val state = view?.snapshot ?: snapshot()
        if (state.queue.getOrNull(state.position) == null || !state.elapsed.isFinite()) return
        val upper = if (view != null) view.durationMs.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: MAX_SEEK_SECONDS
            else state.duration.takeIf { it.isFinite() && it > 0.0 } ?: MAX_SEEK_SECONDS
        val elapsed = view?.positionMs?.div(1000.0) ?: state.elapsed
        seekSeconds((elapsed + delta).coerceIn(0.0, upper.coerceAtMost(MAX_SEEK_SECONDS)))
    }

    private fun seekSeconds(seconds: Double) {
        if (seconds.isFinite() && seconds in 0.0..MAX_SEEK_SECONDS) {
            dispatch(LocalPlaybackCommand.seek(seconds))
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
        val LOCAL_OUTPUT_EVENTS = setOf(Player.EVENT_AUDIO_SESSION_ID, Player.EVENT_AUDIO_ATTRIBUTES_CHANGED,
            Player.EVENT_VOLUME_CHANGED, Player.EVENT_DEVICE_INFO_CHANGED, Player.EVENT_DEVICE_VOLUME_CHANGED)
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
