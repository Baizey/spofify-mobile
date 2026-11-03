package dev.spofify.mobile.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackOccurrence
import dev.spofify.mobile.core.PlaybackContext
import java.util.UUID
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.SpofifyClient
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sole owner of playback. Binding/unbinding a UI controller never owns this lifetime.
 * No playlist or playWhenReady is restored on process/service recreation.
 * MediaSessionService supplies the foreground notification and system media controls.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    internal companion object {
        private const val MAX_PENDING_AUDIO_URLS = 1_000
        private var active = WeakReference<PlaybackService>(null)

        /** Main-thread, same-process safety net: stopService alone cannot stop a bound service. */
        fun stopActivePlayback() {
            active.get()?.shutdownPlayback()
        }

        // Same-process calls only, always on the main looper. Service lifetime remains authoritative.
        fun replacePrepared(items: List<MediaItem>, client: SpofifyClient, start: Int, shuffle: Boolean, play: Boolean): String? {
            val service = active.get() ?: return "Playback is unavailable. Try again."
            return service.replace(items, client, start, shuffle, play)
        }
        fun enqueuePrepared(items: List<LibraryItem>, client: SpofifyClient, first: Boolean): String? {
            val service = active.get() ?: return "Unable to connect to playback. Try again."
            return service.enqueue(items, client, first)
        }
        fun removeQueued(id: String) { active.get()?.removeUpcoming(id) }
        fun moveQueued(id: String, to: Int) { active.get()?.moveUpcoming(id, to) }
        fun clearQueued() { active.get()?.clearUpcoming() }
        fun undoQueuedRemoval() { active.get()?.undoRemoval() }
        fun upcomingEntries(): List<QueueEntry> = active.get()?.upcoming().orEmpty()
        fun canUndoRemoval(): Boolean = active.get()?.validRemoval() == true
        fun setQueueContext(value: LibraryItem?) { active.get()?.contextIdentity = value?.copy(libraryPath = null) }
        fun queueContext(): LibraryItem? = active.get()?.contextIdentity
        fun setQueueNotice(value: String?) { active.get()?.queueMessage = value }
        fun queueNotice(): String? = active.get()?.queueMessage
        fun restartCurrentTraversal() { active.get()?.restartTraversal() }
        fun exportLocalSnapshot(): PlaybackSnapshot = active.get()?.exportPrepared() ?: PlaybackSnapshot()
        fun suspendLocalOutput() { active.get()?.suspendBufferedOutput() }
        /** App-owned callback, never installed by a composition. Wire before service startup. */
        var onServiceActiveChanged: ((Boolean) -> Unit)? = null
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var client: SpofifyClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val history = mutableMapOf<String, PlaybackProgress>()
    private val allowedAudioUrls = ConcurrentHashMap.newKeySet<String>()
    // Main-thread only. Approved callbacks can precede the corresponding timeline update.
    private val pendingAudioUrls = mutableSetOf<String>()
    private var historyWasPlaying = false
    private var contextIdentity: LibraryItem? = null
    private var queueMessage: String? = null
    private var removed: Pair<PlaybackQueue.Removal, MediaItem>? = null
    private var arranging = false
    private val historyNonces = mutableMapOf<String, String>()
    private val wireKeys = mutableMapOf<String, Long>()
    private var wireSerial = 0L

    private val pruneQueueState = Runnable {
        val current = player
        val activeUrls = mutableSetOf<String>()
        val activeOccurrences = mutableSetOf<String>()
        if (current != null) {
            for (index in 0 until current.mediaItemCount) {
                val media = current.getMediaItemAt(index)
                media.localConfiguration?.uri?.toString()?.let(activeUrls::add)
                PlaybackMedia.occurrence(media)?.let(activeOccurrences::add)
            }
        }
        pendingAudioUrls.removeAll(activeUrls)
        // No ever-growing history cache or URL allowlist: retain only the live queue
        // and URLs approved for a not-yet-applied session callback.
        history.keys.retainAll(activeOccurrences)
        historyNonces.keys.retainAll(activeOccurrences)
        wireKeys.keys.retainAll(activeOccurrences)
        allowedAudioUrls.retainAll(activeUrls + pendingAudioUrls)
    }

    private fun schedulePrune() {
        handler.removeCallbacks(pruneQueueState)
        // Finish the old item's discontinuity/history callbacks before discarding its gate.
        handler.post(pruneQueueState)
    }

    private val historyTick = object : Runnable {
        override fun run() {
            val current = player ?: return
            // Parent auth callbacks may call stopService while a UI remains bound. Also
            // observe the shared session so already-buffered audio cannot outlive logout.
            if (client !== (application as SpofifyApplication).graph.client ||
                (current.mediaItemCount > 0 && client?.currentSession() == null)) {
                shutdownPlayback()
                return
            }
            sampleHistory(current.currentMediaItem, current.currentPosition, current.isPlaying)
            handler.postDelayed(this, if (current.isPlaying) 200 else 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val sharedClient = (application as SpofifyApplication).graph.client
        if (sharedClient == null) {
            stopSelf()
            return
        }
        client = sharedClient
        active = WeakReference(this)
        // This exact instance supplies cookies and auth-expiry handling on EVERY open,
        // including seeks/range requests. No cache, redirect client, or token URL exists.
        val dataSources = ResolvingDataSource.Factory(
            OkHttpDataSource.Factory(sharedClient.httpClient),
        ) { spec ->
            if (sharedClient !== (application as SpofifyApplication).graph.client ||
                sharedClient.currentSession() == null || spec.uri.toString() !in allowedAudioUrls) {
                throw IOException("Playback source is not in the authorized queue")
            }
            spec
        }
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exo
        exo.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                schedulePrune()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (arranging || !shuffleModeEnabled) return
                // Shuffle only the future; played/current retain their positions.
                val ids = physicalIds()
                val at = exo.currentMediaItemIndex.coerceAtLeast(0)
                setTraversal(ids.take(at + 1) + ids.drop(at + 1).shuffled())
                removed = null
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Repeat/returning is a new traversal; playlist moves are not.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                    PlaybackMedia.occurrence(mediaItem) !in history) {
                    PlaybackMedia.occurrence(mediaItem)?.let { id ->
                        history[id] = PlaybackProgress()
                        historyNonces[id] = UUID.randomUUID().toString()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Close out the genuinely playing interval before pausing/buffering.
                sampleHistory(exo.currentMediaItem, exo.currentPosition, historyWasPlaying)
                historyWasPlaying = isPlaying
                resetHistoryAnchor(exo.currentMediaItem)
                sampleHistory(exo.currentMediaItem, exo.currentPosition, isPlaying)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // oldPosition is before a seek, never the artificial forward jump.
                sampleHistory(oldPosition.mediaItem, oldPosition.positionMs, historyWasPlaying)
                resetHistoryAnchor(oldPosition.mediaItem)
                resetHistoryAnchor(newPosition.mediaItem)
                sampleHistory(newPosition.mediaItem, newPosition.positionMs, exo.isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                // No retry or automatic skip: an explicit user action is required.
                exo.pause()
            }
        })
        // Intercept semantic restart actions AT execution, not during command authorization:
        // controller batches may seek before Previous while the authorization callbacks still
        // observe the old position. Ordinary seekTo/scrubbing never resets a traversal.
        val sessionPlayer = TraversalPlayer(exo, ::restartTraversal)
        val builder = MediaSession.Builder(this, sessionPlayer).setCallback(SessionCallback(sharedClient))
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            builder.setSessionActivity(PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        }
        session = builder.build()
        handler.post(historyTick)
        onServiceActiveChanged?.invoke(true)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (trusted(controllerInfo)) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !keepPlaybackAfterTaskRemoved(current.playWhenReady,
                current.mediaItemCount > 0, current.playbackState, current.playerError != null)) {
            shutdownPlayback()
        }
    }

    override fun onDestroy() {
        releasePlayback()
        super.onDestroy()
    }

    private fun shutdownPlayback() {
        PlaybackRequests.invalidate()
        releasePlayback()
        stopSelf()
    }

    private fun releasePlayback() {
        if (active.get() === this) {
            onServiceActiveChanged?.invoke(false)
            active.clear()
        }
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        player?.release()
        player = null
        historyScope.cancel()
        history.clear()
        historyNonces.clear()
        wireKeys.clear()
        removed = null
        contextIdentity = null
        queueMessage = null
        allowedAudioUrls.clear()
        pendingAudioUrls.clear()
        client = null
    }

    private fun trusted(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == Process.myUid() || controller.isTrusted

    private fun resetHistoryAnchor(media: MediaItem?) {
        PlaybackMedia.occurrence(media)?.let { history[it]?.discontinuity() }
    }

    private fun sampleHistory(media: MediaItem?, position: Long, playing: Boolean) {
        val current = media ?: return
        val eventId = PlaybackMedia.occurrence(current) ?: return
        val sharedClient = client ?: return
        val gate = history.getOrPut(eventId) { PlaybackProgress() }
        val nonce = historyNonces.getOrPut(eventId) { UUID.randomUUID().toString() }
        if (gate.sample(position, SystemClock.elapsedRealtime(), playing, player?.playbackParameters?.speed ?: 1f)) {
            historyScope.launch {
                try {
                    sharedClient.recordHistory(nonce, current.mediaId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Best effort only. No retry loop, UI failure, or duplicate event.
                }
            }
        }
    }

    private fun suspendBufferedOutput() {
        val current = player ?: return
        sampleHistory(current.currentMediaItem, current.currentPosition, historyWasPlaying)
        current.pause()
        current.stop() // Discard buffered output without clearing prepared items/history/position.
        historyWasPlaying = false
        resetHistoryAnchor(current.currentMediaItem)
    }

    private fun exportPrepared(): PlaybackSnapshot {
        val current = player ?: return PlaybackSnapshot()
        sampleHistory(current.currentMediaItem, current.currentPosition, current.isPlaying)
        val physical = physicalIds()
        val order = traversalIds()
        val queue = order.map { id ->
            val index = physical.indexOf(id)
            val item = requireNotNull(PlaybackMedia.libraryItem(current.getMediaItemAt(index)))
            val key = wireKeys.getOrPut(id) { ++wireSerial }
            PlaybackSnapshotCodec.track(item, key, index.toLong())
        }
        val currentId = currentId()
        val position = order.indexOf(currentId)
        val duration = if (current.duration == C.TIME_UNSET) current.currentMediaItem?.let(PlaybackMedia::libraryItem)?.durationMs ?: 0L else current.duration
        val occurrence = currentId?.let { id ->
            val item = current.currentMediaItem ?: return@let null
            val gate = history.getOrPut(id) { PlaybackProgress() }.snapshot()
            PlaybackOccurrence(wireKeys.getValue(id), item.mediaId,
                historyNonces.getOrPut(id) { UUID.randomUUID().toString() }, gate.accumulatedMs / 1000.0, gate.recorded)
        }
        return PlaybackSnapshot(queue = queue, position = position,
            context = contextIdentity?.let { PlaybackContext(it.kind, it.id, it.name) },
            shuffle = current.shuffleModeEnabled,
            repeat = when (current.repeatMode) { Player.REPEAT_MODE_ALL -> "all"; Player.REPEAT_MODE_ONE -> "one"; else -> "off" },
            elapsed = current.currentPosition.coerceAtLeast(0) / 1000.0,
            duration = duration.coerceAtLeast(0) / 1000.0,
            playing = current.isPlaying, waiting = current.playbackState == Player.STATE_BUFFERING,
            error = if (current.playerError == null) "" else "media", occurrence = occurrence,
            truncated = queueMessage != null,
            notice = if (queueMessage?.startsWith("This selection") == true) "CLICKED_OCCURRENCE_ONLY" else if (queueMessage != null) "PLAYBACK_LIMIT" else "",
        ).validated()
    }

    private fun restartTraversal() {
        val current = player ?: return
        sampleHistory(current.currentMediaItem, current.currentPosition, historyWasPlaying)
        currentId()?.let { id ->
            history[id] = PlaybackProgress()
            historyNonces[id] = UUID.randomUUID().toString()
        }
    }

    private fun physicalIds(): List<String> {
        val current = player ?: return emptyList()
        return (0 until current.mediaItemCount).map { requireNotNull(PlaybackMedia.occurrence(current.getMediaItemAt(it))) }
    }

    private fun traversalIds(): List<String> {
        val current = player ?: return emptyList()
        val timeline = current.currentTimeline
        if (timeline.isEmpty) return emptyList()
        return PlaybackQueue.traversal(current.mediaItemCount, timeline.getFirstWindowIndex(current.shuffleModeEnabled)) {
            timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, current.shuffleModeEnabled)
        }.map { requireNotNull(PlaybackMedia.occurrence(current.getMediaItemAt(it))) }
    }

    private fun currentId(): String? = PlaybackMedia.occurrence(player?.currentMediaItem)

    private fun setTraversal(ids: List<String>) {
        val current = player ?: return
        val physical = physicalIds()
        if (ids.size != physical.size || ids.toSet() != physical.toSet()) return
        current.setShuffleOrder(DefaultShuffleOrder(ids.map(physical::indexOf).toIntArray(), System.nanoTime()))
    }

    private fun upcoming(): List<QueueEntry> {
        val current = player ?: return emptyList()
        val physical = physicalIds()
        return PlaybackQueue.upcoming(traversalIds(), currentId()).mapNotNull { id ->
            val index = physical.indexOf(id)
            PlaybackMedia.libraryItem(current.getMediaItemAt(index))?.let { QueueEntry(id, it, index) }
        }
    }

    private fun replace(items: List<MediaItem>, requestedClient: SpofifyClient, start: Int, shuffle: Boolean, play: Boolean): String? {
        val current = player ?: return "Playback is unavailable. Try again."
        if (requestedClient !== client || client !== (application as SpofifyApplication).graph.client ||
            requestedClient.currentSession() == null) return "Sign in again before playing music."
        val safe = try {
            require(items.size in 1..PlaybackQueue.LIMIT && start in items.indices)
            items.map { PlaybackMedia.create(requireNotNull(PlaybackMedia.libraryItem(it)), requestedClient) }
        } catch (_: IllegalArgumentException) { return "This queue contains an invalid track." }
        // Validate the whole replacement before changing authorization, queue or current audio.
        safe.forEach { allowedAudioUrls.add(requireNotNull(it.localConfiguration).uri.toString()) }
        removed = null
        current.pause()
        current.shuffleModeEnabled = false
        current.setMediaItems(safe, start, 0L)
        current.shuffleModeEnabled = shuffle
        current.playWhenReady = play
        if (play) current.prepare()
        schedulePrune()
        return null
    }

    private fun enqueue(items: List<LibraryItem>, requestedClient: SpofifyClient, first: Boolean): String? {
        val current = player ?: return "Playback is unavailable. Try again."
        if (requestedClient !== client || client !== (application as SpofifyApplication).graph.client ||
            requestedClient.currentSession() == null) return "Sign in again before adding music."
        val tracks = items.filter { it.kind == "track" && it.playable }
        if (tracks.isEmpty()) return "No playable tracks to add."
        var order = traversalIds()
        val retained = PlaybackQueue.retainedForAdd(order, currentId(), tracks.size)
        val additions = try {
            tracks.take(PlaybackQueue.LIMIT - retained.size).map { PlaybackMedia.create(it, requestedClient) }
        } catch (_: IllegalArgumentException) { return "This queue contains an invalid track." }
        if (additions.isEmpty()) return "Queue is full (1,000 tracks). Remove upcoming music first."
        if (retained != order) {
            val retired = order.toSet() - retained.toSet()
            for (index in current.mediaItemCount - 1 downTo 0) {
                if (PlaybackMedia.occurrence(current.getMediaItemAt(index)) in retired) current.removeMediaItem(index)
            }
            order = retained
        }
        val insert = if (first && currentId() != null) order.indexOf(currentId()) + 1 else order.size
        val addedIds = additions.map { requireNotNull(PlaybackMedia.occurrence(it)) }
        val nextOrder = order.toMutableList().apply { addAll(insert, addedIds) }
        additions.forEach { allowedAudioUrls.add(requireNotNull(it.localConfiguration).uri.toString()) }
        // A finished/empty player's stale playWhenReady must not make Add autoplay.
        if (current.mediaItemCount == 0 || current.playbackState == Player.STATE_ENDED) current.pause()
        // Add directly to the service's player. No prepare/play: empty Add remains paused/idle.
        current.addMediaItems(additions)
        removed = null
        if (current.shuffleModeEnabled) setTraversal(nextOrder) else adoptDisplayedOrder(nextOrder)
        schedulePrune()
        if (additions.size < tracks.size) queueMessage = "Queue limited to 1,000 songs; some music was not added."
        return null
    }

    /** Move existing MediaItems instead of resetting the playlist: current audio/position survives. */
    private fun adoptDisplayedOrder(ids: List<String>) {
        val current = player ?: return
        arranging = true
        try {
            ids.forEachIndexed { target, id ->
                val from = physicalIds().indexOf(id)
                if (from >= 0 && from != target) current.moveMediaItem(from, target)
            }
            current.shuffleModeEnabled = false
        } finally { arranging = false }
    }

    private fun removeUpcoming(id: String) {
        val current = player ?: return
        val order = traversalIds()
        val removal = PlaybackQueue.removal(order, currentId(), id) ?: return
        val index = physicalIds().indexOf(id)
        val media = current.getMediaItemAt(index)
        current.removeMediaItem(index)
        if (current.shuffleModeEnabled) setTraversal(removal.remaining)
        removed = removal to media
    }

    private fun validRemoval(): Boolean = removed?.first?.canUndo(traversalIds(), currentId()) == true

    private fun undoRemoval() {
        val current = player ?: return
        val (removal, media) = removed ?: return
        val restored = removal.undo(traversalIds(), currentId()) ?: run { removed = null; return }
        removed = null
        allowedAudioUrls.add(requireNotNull(media.localConfiguration).uri.toString())
        current.addMediaItem(media)
        if (current.shuffleModeEnabled) setTraversal(restored) else adoptDisplayedOrder(restored)
        schedulePrune()
    }

    private fun moveUpcoming(id: String, to: Int) {
        val moved = PlaybackQueue.move(traversalIds(), currentId(), id, to) ?: return
        removed = null
        adoptDisplayedOrder(moved)
    }

    private fun clearUpcoming() {
        val current = player ?: return
        val future = PlaybackQueue.upcoming(traversalIds(), currentId()).toSet()
        if (future.isEmpty()) return
        val remaining = traversalIds().filterNot { it in future }
        removed = null
        for (index in current.mediaItemCount - 1 downTo 0) {
            if (PlaybackMedia.occurrence(current.getMediaItemAt(index)) in future) current.removeMediaItem(index)
        }
        if (current.shuffleModeEnabled) setTraversal(remaining)
    }

    private inner class SessionCallback(private val sharedClient: SpofifyClient) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (!trusted(controller)) return MediaSession.ConnectionResult.reject()
            val defaults = super.onConnect(session, controller)
            if (controller.uid == Process.myUid()) return defaults
            // Trusted system/notification controllers may operate only the existing queue.
            // In particular, no external set/add/replace-media or URI-bearing command.
            val transport = Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            ).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(defaults.availableSessionCommands)
                .setAvailablePlayerCommands(transport)
                .build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = validate(controller, mediaItems)

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return try {
                val safe = sanitized(controller, mediaItems)
                removed = null
                approve(safe, replace = true)
                Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(safe, startIndex, startPositionMs))
            } catch (_: IllegalArgumentException) {
                Futures.immediateFailedFuture(IllegalArgumentException("Invalid playback queue"))
            }
        }

        private fun validate(
            controller: MediaSession.ControllerInfo,
            items: List<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = try {
            require((player?.mediaItemCount ?: 0) + items.size <= PlaybackQueue.LIMIT)
            val safe = sanitized(controller, items)
            approve(safe, replace = false)
            Futures.immediateFuture(safe)
        } catch (_: IllegalArgumentException) {
            Futures.immediateFailedFuture(IllegalArgumentException("Invalid playback queue"))
        }

        private fun sanitized(controller: MediaSession.ControllerInfo, items: List<MediaItem>): MutableList<MediaItem> {
            require(controller.uid == Process.myUid())
            require(sharedClient === (application as SpofifyApplication).graph.client)
            require(sharedClient.currentSession() != null)
            require(items.size <= PlaybackQueue.LIMIT)
            return items.map { supplied ->
                val track = requireNotNull(PlaybackMedia.libraryItem(supplied))
                // Rebuild instead of trusting localConfiguration, URI, headers, DRM,
                // clipping, artwork, or a caller's history-event identity.
                PlaybackMedia.create(track, sharedClient)
            }.toMutableList()
        }

        private fun approve(items: List<MediaItem>, replace: Boolean) {
            // Commit authorization only after the ENTIRE queue has validated; a bad
            // final entry must not leave URLs from the rejected request authorized.
            val urls = items.map { requireNotNull(it.localConfiguration).uri.toString() }
            val pending = if (replace) urls.toSet() else pendingAudioUrls + urls
            require(pending.size <= MAX_PENDING_AUDIO_URLS)
            pendingAudioUrls.clear()
            pendingAudioUrls.addAll(pending)
            allowedAudioUrls.addAll(urls)
            schedulePrune()
        }
    }
}
