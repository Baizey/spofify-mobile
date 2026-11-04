package dev.spofify.mobile.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.net.Uri
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
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PreparedPlaybackAuthority
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import dev.spofify.mobile.feature.devices.PlaybackEndpointResult
import dev.spofify.mobile.feature.devices.PlaybackFinalClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelChildren
import kotlinx.serialization.json.*
import java.util.UUID
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import android.view.KeyEvent
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
        private const val ACTION_TRANSPORT = "dev.spofify.mobile.playback.TRANSPORT"
        private const val ACTION_KEEP_ALIVE = "dev.spofify.mobile.playback.KEEP_ALIVE"
        private var active = WeakReference<PlaybackService>(null)

        /** Main-thread, same-process safety net: stopService alone cannot stop a bound service. */
        fun stopActivePlayback() {
            active.get()?.shutdownPlayback()
        }

        // Same-process calls only, always on the main looper. Service lifetime remains authoritative.
        fun replacePrepared(items: List<MediaItem>, client: SpofifyClient, start: Int, shuffle: Boolean, play: Boolean,
            context: LibraryItem? = null, notice: String? = null, requestEpoch: Long = PlaybackRequests.epoch,
            preparedState: PlaybackSnapshot? = null, selectionAuthority: PreparedPlaybackAuthority? = null): String? {
            val service = active.get() ?: return "Playback is unavailable. Try again."
            return service.requestReplacement(items, client, start, shuffle, play, context, notice, requestEpoch, preparedState, selectionAuthority)
        }
        fun enqueuePrepared(items: List<LibraryItem>, client: SpofifyClient, first: Boolean): String? {
            val service = active.get() ?: return "Unable to connect to playback. Try again."
            return service.requestEnqueue(items, client, first)
        }
        fun removeQueued(id: String) { active.get()?.localEdit { removeUpcoming(id) } }
        fun moveQueued(id: String, to: Int) { active.get()?.localEdit { moveUpcoming(id, to) } }
        fun clearQueued() { active.get()?.routeCommand(PlaybackDeviceCommand.transport("clear")) }
        fun undoQueuedRemoval() { active.get()?.localEdit { undoRemoval() } }
        fun queuedKey(id: String): Long? = active.get()?.let { it.exportPrepared(); it.wireKeys[id] }
        fun timelineKey(index: Int): Long? = active.get()?.let { service ->
            service.exportPrepared()
            service.player?.takeIf { index in 0 until it.mediaItemCount }?.getMediaItemAt(index)?.let(PlaybackMedia::occurrence)?.let { service.wireKeys[it] }
        }
        fun upcomingEntries(): List<QueueEntry> = active.get()?.upcoming().orEmpty()
        fun canUndoRemoval(): Boolean = active.get()?.validRemoval() == true
        fun setQueueContext(value: LibraryItem?) { active.get()?.localEdit { contextIdentity = value?.copy(libraryPath = null); wireContext = wireContext(value) } }
        fun queueContext(): LibraryItem? = active.get()?.let { service ->
            (service.currentId()?.let { service.wireTracks[it]?.playbackContext } ?: service.wireContext)?.let { LibraryItem(it.kind, it.id, it.name) }
                ?: service.contextIdentity
        }
        fun setQueueNotice(value: String?) { active.get()?.localEdit { queueMessage = value } }
        fun queueNotice(): String? = active.get()?.queueMessage
        fun restartCurrentTraversal() { active.get()?.localEdit { restartTraversal() } }
        fun exportLocalSnapshot(): PlaybackSnapshot = active.get()?.exportPrepared() ?: PlaybackSnapshot()
        fun suspendLocalOutput() { active.get()?.suspendBufferedOutput() }
        suspend fun activateSnapshot(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult =
            active.get()?.importSnapshot(snapshot, authority) ?: PlaybackEndpointResult("rejected", PlaybackSnapshot())
        suspend fun executeCommand(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult =
            active.get()?.executeOwned(command, authority) ?: PlaybackEndpointResult("rejected", PlaybackSnapshot())
        /** App-owned callback, never installed by a composition. Wire before service startup. */
        var onServiceActiveChanged: ((Boolean) -> Unit)? = null
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val serviceNonce = UUID.randomUUID().toString()
    private var foreground: PlaybackForegroundNotification? = null
    private var audioFocus: PlaybackAudioFocus? = null
    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interruptForSystemPause()
        }
    }
    private var lastSuspendedUptime = 0L
    private data class Admission(val command: Int, val requestEpoch: Long)
    private val admissions = mutableMapOf<MediaSession.ControllerInfo, Admission>()
    private var client: SpofifyClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var authority: PlaybackAuthority? = null
    @Volatile private var authorityRequestEpoch = -1L
    private var authenticatedSession: Session? = null
    private var explicitBackgroundPermit: PlaybackAuthority? = null
    private var importing = false
    private var wireContext: PlaybackContext? = null
    private val wireTracks = mutableMapOf<String, PlaybackTrack>()
    private var outputError = ""
    private var queueTruncated = false
    private var queueNoticeCode = ""
    private var importedDuration: Double? = null
    // Media3 seeks at millisecond precision. Preserve exact wire elapsed while unchanged;
    // only an actual local seek/progression replaces it with the player's measured position.
    private var importedElapsed: Double? = null
    private var importedPositionMs: Long? = null
    private val progressRemainders = mutableMapOf<String, Double>()
    private val history = mutableMapOf<String, PlaybackProgress>()
    private val allowedAudioUrls = ConcurrentHashMap.newKeySet<String>()
    // Main-thread only. Approved callbacks can precede the corresponding timeline update.
    private val pendingAudioUrls = mutableSetOf<String>()
    private var historyWasPlaying = false
    private var contextIdentity: LibraryItem? = null
    private var queueMessage: String? = null
    private var removed: Pair<PlaybackQueue.Removal, MediaItem>? = null
    private var removedTrack: PlaybackTrack? = null
    private var arranging = false
    private val historyNonces = mutableMapOf<String, String>()
    private val wireKeys = mutableMapOf<String, Long>()
    private var wireSerial = 0L
    private var pendingCloseDisposal: Runnable? = null

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
        wireTracks.keys.retainAll(activeOccurrences)
        progressRemainders.keys.retainAll(activeOccurrences)
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
            if (!hasAuthority() && (current.playWhenReady || current.isPlaying)) suspendBufferedOutput()
            sampleHistory(current.currentMediaItem, current.currentPosition, current.isPlaying)
            handler.postDelayed(this, 100)
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
        authenticatedSession = sharedClient.currentSession()
        if (authenticatedSession == null) { stopSelf(); return }
        active = WeakReference(this)
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                outputError = "blocked"
                suspendBufferedOutput()
            }
        })
        // This exact instance supplies cookies and auth-expiry handling on EVERY open,
        // including seeks/range requests. No cache, redirect client, or token URL exists.
        val dataSources = ResolvingDataSource.Factory(
            OkHttpDataSource.Factory(sharedClient.httpClient),
        ) { spec ->
            if (sharedClient !== (application as SpofifyApplication).graph.client ||
                sharedClient.currentSession() !== authenticatedSession || !hasAuthority() || spec.uri.toString() !in allowedAudioUrls) {
                throw IOException("Playback source is not in the authorized queue")
            }
            spec
        }
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), false) // Never allow ExoPlayer's internal focus-gain auto-resume.
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exo
        audioFocus = PlaybackAudioFocus(getSystemService(AudioManager::class.java), ::interruptForSystemPause)
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
        exo.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                schedulePrune()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (arranging || importing || !shuffleModeEnabled) return
                // Shuffle only the future; played/current retain their positions.
                val ids = physicalIds()
                val at = exo.currentMediaItemIndex.coerceAtLeast(0)
                setTraversal(ids.take(at + 1) + ids.drop(at + 1).shuffled())
                removed = null
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (importing) return
                if (!hasAuthority() && exo.playWhenReady) suspendBufferedOutput()
                // Repeat/returning is a new traversal; playlist moves are not.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                    PlaybackMedia.occurrence(mediaItem) !in history) {
                    PlaybackMedia.occurrence(mediaItem)?.let { id ->
                        history[id] = PlaybackProgress()
                        progressRemainders.remove(id)
                        importedDuration = null
                        historyNonces[id] = UUID.randomUUID().toString()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (importing) return
                if (isPlaying && !hasAuthority()) { suspendBufferedOutput(); return }
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
                if (importing) return
                importedElapsed = null; importedPositionMs = null
                // oldPosition is before a seek, never the artificial forward jump.
                sampleHistory(oldPosition.mediaItem, oldPosition.positionMs, historyWasPlaying)
                resetHistoryAnchor(oldPosition.mediaItem)
                resetHistoryAnchor(newPosition.mediaItem)
                sampleHistory(newPosition.mediaItem, newPosition.positionMs, exo.isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!importing && playbackState == Player.STATE_ENDED) settleTerminalOutput()
            }

            override fun onPlayerError(error: PlaybackException) {
                // No retry or automatic skip, retained focus or foreground exemption.
                settleTerminalOutput()
            }
        })
        // Intercept semantic restart actions AT execution, not during command authorization:
        // controller batches may seek before Previous while the authorization callbacks still
        // observe the old position. Ordinary seekTo/scrubbing never resets a traversal.
        val sessionPlayer = CoordinatedSessionPlayer(exo, ::routeSessionCommand, ::exportPrepared) { index ->
            if (index in 0 until exo.mediaItemCount) PlaybackMedia.occurrence(exo.getMediaItemAt(index))?.let { wireKeys[it] } else null
        }
        val builder = MediaSession.Builder(this, sessionPlayer).setCallback(SessionCallback(sharedClient))
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            builder.setSessionActivity(PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        }
        session = builder.build()
        foreground = PlaybackForegroundNotification(this, ::notificationIntent)
        handler.post(historyTick)
        onServiceActiveChanged?.invoke(true)
    }

    private fun notificationIntent(type: String): PendingIntent {
        // Auth expiry can invalidate off-main: use one captured capability in identity AND extras.
        val epoch = PlaybackRequests.epoch
        val queueEpoch = PlaybackRequests.queueEpoch
        return PendingIntent.getService(this, type.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(ACTION_TRANSPORT)
                // PendingIntent equality ignores extras. Scope its IDENTITY too, otherwise
                // UPDATE_CURRENT silently upgrades a retained old Resume token to a new epoch.
                .setData(Uri.Builder().scheme("spofify-playback").authority(serviceNonce)
                    .appendPath(epoch.toString()).appendPath(queueEpoch.toString()).appendPath(type).build())
                .putExtra("command", type).putExtra("service", serviceNonce).putExtra("generation", epoch)
                .putExtra("queueGeneration", queueEpoch),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRANSPORT) {
            if (intent.getStringExtra("service") == serviceNonce && intent.getLongExtra("generation", -1) == PlaybackRequests.epoch &&
                intent.getLongExtra("queueGeneration", -1) == PlaybackRequests.queueEpoch) {
                intent.getStringExtra("command")?.takeIf { it in setOf("resume", "pause", "next", "previous") }
                    ?.let { routeCommand(PlaybackDeviceCommand.transport(it), userInteraction = true) }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_KEEP_ALIVE) return START_NOT_STICKY
        return super.onStartCommand(intent, flags, startId)
    }

    private class PlayContinuationSuppressed : Exception("Coordinated Play owns execution")

    override fun onUpdateNotificationAsync(mediaSession: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        // This service owns foreground promotion; never run Media3's second notification owner.
        if (mediaSession !== session) return Futures.immediateFuture(null)
        // Pinned Media3 1.11.1 on API31/32 asks for this future synchronously inside the
        // admitted controller Play, then loses controller scope across TWO async transforms.
        // Consume that EXACT admission now and suppress its unscoped raw continuation. Never
        // let an old continuation borrow a later Play's admission at wrapper execution time.
        val controller = mediaSession.controllerForCurrentRequest
        val admission = controller?.let(admissions::get)
        if (controller != null && android.os.Build.VERSION.SDK_INT in 31..32 && admission?.command == Player.COMMAND_PLAY_PAUSE) {
            if (admissions[controller] === admission) admissions.remove(controller)
            if (admission.requestEpoch == PlaybackRequests.epoch && client != null &&
                client === (application as SpofifyApplication).graph.client && authenticatedSession != null &&
                client?.currentSession() === authenticatedSession && active.get() === this) {
                routeCommand(PlaybackDeviceCommand.transport("resume"), userInteraction = true)
            }
            // Media3 catches ordinary notification-future failure and resolves Play admission
            // false. Do not use its FGS-denied exception (that would revoke our routed action).
            return Futures.immediateFailedFuture(PlayContinuationSuppressed())
        }
        val current = player
        val track = current?.currentMediaItem?.let(PlaybackMedia::libraryItem)
        foreground?.update(mediaSession, track, hasAuthority() && current?.playWhenReady == true)
        return Futures.immediateFuture(null)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (trusted(controllerInfo)) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (foreground?.foregroundConfirmed != true || current == null || !keepPlaybackAfterTaskRemoved(current.playWhenReady,
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
        cancelCloseDisposal()
        handler.removeCallbacksAndMessages(null)
        admissions.clear()
        foreground?.close()
        foreground = null
        if (noisyRegistered) { unregisterReceiver(noisyReceiver); noisyRegistered = false }
        audioFocus?.release(); audioFocus = null
        session?.release()
        session = null
        player?.release()
        player = null
        historyScope.cancel()
        authority = null
        authenticatedSession = null
        history.clear()
        historyNonces.clear()
        wireTracks.clear()
        wireContext = null
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
        if (importing || !hasAuthority()) return
        val current = media ?: return
        val eventId = PlaybackMedia.occurrence(current) ?: return
        val sharedClient = client ?: return
        if (!playing && eventId !in history) return
        val gate = history.getOrPut(eventId) { PlaybackProgress() }
        val nonce = historyNonces.getOrPut(eventId) { UUID.randomUUID().toString() }
        if (gate.sample(position, SystemClock.elapsedRealtime(), playing, player?.playbackParameters?.speed ?: 1f)) {
            val permitted = authority
            val requestEpoch = authorityRequestEpoch
            historyScope.launch {
                try {
                    if (permitted !== authority || requestEpoch != PlaybackRequests.epoch || !hasAuthority()) return@launch
                    sharedClient.recordHistory(nonce, current.mediaId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Best effort only. No retry loop, UI failure, or duplicate event.
                }
            }
        }
    }

    private fun settleTerminalOutput() {
        val current = player ?: return
        sampleHistory(current.currentMediaItem, current.currentPosition, historyWasPlaying)
        current.pause()
        audioFocus?.abandon()
        foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem))
        // A bound UI may retain the paused queue. Without bindings a terminal service is
        // disposable; it must not remain a started background service merely to keep polling.
        stopSelf()
    }

    private fun interruptForSystemPause() {
        if (active.get() !== this) return
        val owner = (application as SpofifyApplication).graph.playbackCoordinator
        // Both focus loss and noisy events cancel pending preparation as well as audible audio.
        // There is deliberately NO focus-gain path back into playback.
        val localOwner = owner?.state?.value?.let { it.deviceId.isNotEmpty() && it.activeDeviceId == it.deviceId } == true
        PlaybackRequests.invalidate()
        suspendBufferedOutput()
        // An unplugged headset on an idle/controller phone must not pause a different output.
        if (localOwner) owner?.remoteCommand(PlaybackDeviceCommand.transport("pause"))
    }

    private fun suspendBufferedOutput() {
        authority = null
        authorityRequestEpoch = -1
        admissions.clear()
        lastSuspendedUptime = SystemClock.uptimeMillis()
        historyScope.coroutineContext.cancelChildren()
        audioFocus?.abandon()
        val current = player ?: return
        current.pause()
        current.stop() // Discard buffered output without clearing prepared items/history/position.
        historyWasPlaying = false
        resetHistoryAnchor(current.currentMediaItem)
        foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem))
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
            wireTracks[id] ?: PlaybackSnapshotCodec.track(item, key, index.toLong())
        }
        val currentId = currentId()
        val position = order.indexOf(currentId)
        val duration = if (current.duration == C.TIME_UNSET) current.currentMediaItem?.let(PlaybackMedia::libraryItem)?.durationMs ?: 0L else current.duration
        val occurrence = currentId?.let { id ->
            val item = current.currentMediaItem ?: return@let null
            val gate = history[id]?.snapshot() ?: return@let null
            PlaybackOccurrence(wireKeys.getValue(id), item.mediaId,
                historyNonces.getOrPut(id) { UUID.randomUUID().toString() }, (gate.accumulatedMs / 1000.0 + (progressRemainders[id] ?: 0.0)).coerceAtMost(604800.0), gate.recorded)
        }
        return PlaybackSnapshot(queue = queue, position = position,
            context = wireContext ?: contextIdentity?.let { PlaybackContext(it.kind, it.id, it.name) },
            shuffle = importedShuffle ?: current.shuffleModeEnabled,
            repeat = when (current.repeatMode) { Player.REPEAT_MODE_ALL -> "all"; Player.REPEAT_MODE_ONE -> "one"; else -> "off" },
            elapsed = importedElapsed?.takeIf { current.currentPosition == importedPositionMs }
                ?: (current.currentPosition.coerceAtLeast(0) / 1000.0),
            duration = if (current.duration == C.TIME_UNSET) importedDuration ?: (duration.coerceAtLeast(0) / 1000.0) else duration.coerceAtLeast(0) / 1000.0,
            playing = hasAuthority() && current.playWhenReady && current.playbackState != Player.STATE_ENDED && current.playerError == null && outputError.isEmpty(),
            waiting = hasAuthority() && current.playbackState == Player.STATE_BUFFERING,
            error = if (current.playerError != null) "media" else outputError, occurrence = occurrence,
            truncated = queueTruncated,
            notice = queueNoticeCode,
        ).validated()
    }

    private fun hasAuthority(): Boolean = authority?.isValid() == true &&
        authorityRequestEpoch == PlaybackRequests.epoch && client != null &&
        client === (application as SpofifyApplication).graph.client &&
        authenticatedSession != null && client?.currentSession() === authenticatedSession

    private fun acceptAuthority(value: PlaybackAuthority): Boolean {
        if (!value.isValid() || client !== (application as SpofifyApplication).graph.client ||
            authenticatedSession == null || client?.currentSession() !== authenticatedSession) return false
        // Merely accepting authority over an empty queue does not establish replacement
        // playback: a queued action may still reject. Keep the close cleanup obligation.
        if (player?.mediaItemCount != 0) cancelCloseDisposal()
        authority = value
        authorityRequestEpoch = PlaybackRequests.epoch
        return hasAuthority()
    }

    private fun routeCommand(command: PlaybackDeviceCommand, userInteraction: Boolean = false) {
        if (client == null || authenticatedSession == null || client?.currentSession() !== authenticatedSession) return
        val owner = (application as SpofifyApplication).graph.playbackCoordinator ?: return
        val local = owner.state.value.let { it.deviceId.isNotEmpty() && it.activeDeviceId == it.deviceId }
        if (userInteraction && local && command.type in setOf("resume", "retry", "next", "previous", "jump")) {
            val request = PlaybackRequests.epoch
            val intended = exportPrepared()
            val restartId = currentId()?.takeIf { command.type == "jump" && command.json["key"]?.jsonPrimitive?.long == wireKeys[it] }
            owner.localAction { permit ->
                val currentState = exportPrepared()
                val sameQueue = currentState.queue == intended.queue &&
                    currentState.queue.getOrNull(currentState.position)?.key == intended.queue.getOrNull(intended.position)?.key &&
                    currentState.occurrence?.eventId == intended.occurrence?.eventId
                if (request != PlaybackRequests.epoch || !sameQueue) PlaybackEndpointResult("rejected", currentState)
                else {
                    // Only THIS fenced user action may ask Android to evaluate its notification/
                    // system-media FGS exemption. Remote activation cannot borrow the allowance.
                    explicitBackgroundPermit = permit
                    try {
                        // MediaSession default-position(current) is a semantic restart, unlike
                        // an ordinary scrub and unlike future-only wire jump/remove/move.
                        if (restartId != null) {
                            if (currentId() != restartId || !acceptAuthority(permit)) PlaybackEndpointResult("rejected", exportPrepared())
                            else { restartTraversal(); player?.seekToDefaultPosition(); startOwned(permit, true) }
                        } else executeOwned(command, permit)
                    }
                    finally { if (explicitBackgroundPermit === permit) explicitBackgroundPermit = null }
                }
            }
        } else owner.remoteCommand(command)
    }

    private fun routeSessionCommand(command: PlaybackDeviceCommand) {
        val mediaSession = session ?: return
        // Media3 1.11.1's delayed foreground/resumption Play continuation has NO scoped
        // controller. It must not turn an old request into a fresh coordinator intention.
        val controller = mediaSession.controllerForCurrentRequest ?: return
        val admitted = admissions[controller] ?: return
        if (admitted.requestEpoch != PlaybackRequests.epoch) { admissions.remove(controller); return }
        if (admitted.command == Player.COMMAND_PLAY_PAUSE && command.type !in setOf("resume", "pause")) return
        val matches = when (command.type) {
            "resume", "pause" -> admitted.command in setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP)
            "previous" -> admitted.command in setOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            "next" -> admitted.command in setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            "seek", "jump" -> admitted.command in setOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
            "shuffle" -> admitted.command == Player.COMMAND_SET_SHUFFLE_MODE
            "repeat" -> admitted.command == Player.COMMAND_SET_REPEAT_MODE
            else -> false
        }
        if (!matches) return
        admissions.remove(controller)
        routeCommand(command, userInteraction = true)
    }

    /** Activation is the only wire import. Validation/prepare never reaches this method. */
    private suspend fun importSnapshot(supplied: PlaybackSnapshot, value: PlaybackAuthority): PlaybackEndpointResult {
        val source = client ?: return PlaybackEndpointResult("rejected", exportPrepared())
        val plan = try { PlaybackSnapshotCodec.plan(supplied) }
            catch (_: IllegalArgumentException) { return PlaybackEndpointResult("rejected", exportPrepared()) }
        val snapshot = plan.snapshot
        val physical = plan.physicalTracks
        val media = try {
            plan.mediaItems(source)
        } catch (_: IllegalArgumentException) { return PlaybackEndpointResult("rejected", exportPrepared()) }
        if (!acceptAuthority(value)) return PlaybackEndpointResult("rejected", exportPrepared())
        val current = player ?: return PlaybackEndpointResult("rejected", PlaybackSnapshot())
        importing = true
        PlaybackRequests.queueChanged()
        try {
            current.pause()
            current.stop()
            audioFocus?.abandon()
            history.clear(); historyNonces.clear(); wireKeys.clear(); wireTracks.clear(); progressRemainders.clear()
            allowedAudioUrls.clear(); pendingAudioUrls.clear(); removed = null
            physical.forEachIndexed { index, track ->
                val id = "wire-${track.key}"
                wireKeys[id] = track.key; wireTracks[id] = track
                allowedAudioUrls.add(requireNotNull(media[index].localConfiguration).uri.toString())
            }
            wireSerial = maxOf(wireSerial, snapshot.queue.maxOfOrNull { it.key } ?: 0)
            wireContext = snapshot.context
            queueTruncated = snapshot.truncated
            queueNoticeCode = snapshot.notice
            importedDuration = snapshot.duration
            importedElapsed = snapshot.elapsed
            importedPositionMs = plan.positionMs
            contextIdentity = snapshot.context?.let { LibraryItem(it.kind, it.id, it.name) }
            queueMessage = when (snapshot.notice) {
                "CLICKED_OCCURRENCE_ONLY" -> "This selection is beyond the 1,000-song limit. Only the selected occurrence is loaded."
                "PLAYBACK_LIMIT" -> "Queue limited to 1,000 songs."
                else -> if (snapshot.truncated) "Queue limited to 1,000 songs." else null
            }
            current.shuffleModeEnabled = false
            val selected = snapshot.queue.getOrNull(snapshot.position)
            current.setMediaItems(media, if (selected == null) 0 else plan.currentIndex, plan.positionMs)
            current.setShuffleOrder(DefaultShuffleOrder(snapshot.queue.map { track -> physical.indexOfFirst { it.key == track.key } }.toIntArray(), 0))
            // Keep realized order even when the wire's shuffle flag is false: ordinary nonshuffled
            // queues normally match their baseline, but exact import must never silently reorder.
            current.shuffleModeEnabled = snapshot.shuffle || snapshot.queue != physical
            importedShuffle = snapshot.shuffle
            current.repeatMode = when (snapshot.repeat) { "all" -> Player.REPEAT_MODE_ALL; "one" -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
            snapshot.occurrence?.let { report ->
                val id = "wire-${report.key}"
                history[id] = PlaybackProgress().apply { restore(PlaybackProgress.Snapshot((report.progress * 1000).toLong(), report.reported)) }
                historyNonces[id] = report.eventId
                progressRemainders[id] = report.progress - (report.progress * 1000).toLong() / 1000.0
            }
            historyWasPlaying = false
            outputError = ""
        } finally { importing = false }
        if (current.mediaItemCount > 0) cancelCloseDisposal() // Actual replacement state, even if blocked/paused.
        schedulePrune()
        if (snapshot.queue.isEmpty()) return PlaybackEndpointResult("ok", exportPrepared())
        return startOwned(value, snapshot.playing)
    }

    private var importedShuffle: Boolean? = null

    /** Returns actual READY/playing or an honest failure; receiving a directive is not a success ACK. */
    private suspend fun startOwned(value: PlaybackAuthority, play: Boolean): PlaybackEndpointResult {
        val current = player ?: return PlaybackEndpointResult("rejected", PlaybackSnapshot())
        if (!acceptAuthority(value)) return PlaybackEndpointResult("rejected", exportPrepared())
        val requestEpoch = authorityRequestEpoch
        fun owned() = authority === value && authorityRequestEpoch == requestEpoch && hasAuthority()
        fun valid() = owned() && audioFocus?.isGranted == true
        outputError = ""
        if (!play) return PlaybackEndpointResult("ok", exportPrepared().paused())
        if (!mayStartForegroundPlayback(value) || session?.let { foreground?.promote(it, current.currentMediaItem?.let(PlaybackMedia::libraryItem), true) } != true) {
            outputError = "blocked"; current.pause(); current.stop(); audioFocus?.abandon()
            foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem))
            return PlaybackEndpointResult("blocked", exportPrepared().paused().copy(error = "blocked"))
        }
        if (audioFocus?.requestForExplicitStart() != true) {
            outputError = "blocked"; current.pause(); current.stop()
            foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem))
            return PlaybackEndpointResult("blocked", exportPrepared().paused().copy(error = "blocked"))
        }
        if (!valid()) return PlaybackEndpointResult("rejected", exportPrepared().paused())
        // Keep the one service started while its foreground notification owns audio lifetime.
        startService(Intent(this, PlaybackService::class.java).setAction(ACTION_KEEP_ALIVE))
        current.playWhenReady = false
        current.prepare()
        val ready = withTimeoutOrNull(3_000) {
            while (valid() && current.playerError == null && current.playbackState != Player.STATE_READY) delay(20)
            valid() && current.playerError == null && current.playbackState == Player.STATE_READY
        } == true
        if (!owned()) return PlaybackEndpointResult("rejected", exportPrepared().paused())
        if (!ready) {
            outputError = "media"; current.pause(); current.stop(); audioFocus?.abandon()
            foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem))
            return PlaybackEndpointResult("media", exportPrepared().paused().copy(error = "media"))
        }
        if (play) {
            if (!valid()) return PlaybackEndpointResult("rejected", exportPrepared().paused())
            current.play()
            val started = withTimeoutOrNull(500) {
                while (valid() && !current.isPlaying && current.playerError == null && outputError.isEmpty()) delay(10)
                valid() && current.isPlaying && outputError.isEmpty()
            } == true
            if (!owned()) return PlaybackEndpointResult("rejected", exportPrepared().paused())
            if (!started) {
                outputError = if (current.playerError == null) "blocked" else "media"
                current.pause(); current.stop(); audioFocus?.abandon()
                foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem))
                return PlaybackEndpointResult(outputError, exportPrepared().paused().copy(error = outputError))
            }
        }
        return PlaybackEndpointResult("ok", exportPrepared())
    }

    private fun mayStartForegroundPlayback(value: PlaybackAuthority): Boolean {
        // No claim to wake a killed/restricted app. A running FGS may continue; a visible app
        // can begin playback. A background-only idle receiver reports Needs a tap instead.
        if (foreground?.foregroundConfirmed == true) return true
        if (explicitBackgroundPermit === value && value.isValid()) return true
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private suspend fun executeOwned(command: PlaybackDeviceCommand, value: PlaybackAuthority): PlaybackEndpointResult {
        if (!acceptAuthority(value)) return PlaybackEndpointResult("rejected", exportPrepared())
        val current = player ?: return PlaybackEndpointResult("rejected", PlaybackSnapshot())
        // Resolve a legacy incoming toggle against the raw service player's current intent,
        // including buffering, not the remote UI snapshot. Native callers send explicit intent.
        if (command.type == "toggle") return executeOwned(
            PlaybackDeviceCommand.transport(if (current.playWhenReady) "pause" else "resume"), value)
        val json = command.json
        when (command.type) {
            "pause" -> { audioFocus?.abandon(); current.pause(); current.stop(); historyWasPlaying = false; resetHistoryAnchor(current.currentMediaItem); foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem)) }
            "close" -> {
                PlaybackRequests.invalidate(); PlaybackRequests.queueChanged()
                authorityRequestEpoch = PlaybackRequests.epoch
                admissions.clear(); historyScope.coroutineContext.cancelChildren()
                audioFocus?.abandon(); current.pause(); current.stop(); current.clearMediaItems()
                current.shuffleModeEnabled = false; current.repeatMode = Player.REPEAT_MODE_OFF
                history.clear(); historyNonces.clear(); progressRemainders.clear(); wireKeys.clear(); wireTracks.clear()
                allowedAudioUrls.clear(); pendingAudioUrls.clear(); removed = null; removedTrack = null
                wireContext = null; contextIdentity = null; queueMessage = null; queueTruncated = false; queueNoticeCode = ""
                importedDuration = null; importedElapsed = null; importedPositionMs = null; importedShuffle = null
                historyWasPlaying = false; outputError = ""
                foreground?.demote(session, null)
                // Output is already silent/empty and no longer foreground. Do not destroy the
                // coordinator until its exact empty result + ACK has had one bounded attempt.
                return PlaybackEndpointResult("ok", exportPrepared(), deferCloseDisposal())
            }
            "resume", "retry" -> {
                if (current.mediaItemCount == 0) return PlaybackEndpointResult("rejected", exportPrepared())
                if (current.playbackState == Player.STATE_ENDED) { restartTraversal(); current.seekToDefaultPosition() }
                return startOwned(value, true)
            }
            "seek" -> { current.seekTo((json.getValue("value").jsonPrimitive.double * 1000).toLong()); resetHistoryAnchor(current.currentMediaItem) }
            "next" -> { if (current.hasNextMediaItem()) { current.seekToNextMediaItem(); return startOwned(value, true) } }
            "previous" -> { if (current.currentPosition > current.maxSeekToPreviousPosition || !current.hasPreviousMediaItem()) restartTraversal(); current.seekToPrevious(); return startOwned(value, true) }
            "shuffle" -> toggleOwnedShuffle()
            "repeat" -> current.repeatMode = when (current.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
            "clear" -> clearUpcoming()
            "jump", "remove", "move" -> {
                val key = json.getValue("key").jsonPrimitive.long
                val id = wireKeys.entries.firstOrNull { it.value == key }?.key ?: return PlaybackEndpointResult("rejected", exportPrepared())
                val order = traversalIds(); val index = order.indexOf(id); val at = order.indexOf(currentId())
                if (index <= at) return PlaybackEndpointResult("rejected", exportPrepared())
                when (command.type) {
                    "jump" -> { current.seekToDefaultPosition(physicalIds().indexOf(id)); return startOwned(value, true) }
                    "remove" -> removeUpcoming(id)
                    "move" -> {
                        val target = when (json["direction"]?.jsonPrimitive?.content) { "next" -> 0; "up" -> index - at - 2; "down" -> index - at; else -> -1 }
                        moveUpcoming(id, target)
                    }
                }
            }
            else -> return executeMusical(command, value)
        }
        return PlaybackEndpointResult("ok", exportPrepared())
    }

    private fun cancelCloseDisposal() {
        pendingCloseDisposal?.let(handler::removeCallbacks)
        pendingCloseDisposal = null
    }

    private fun deferCloseDisposal(): PlaybackFinalClose {
        cancelCloseDisposal()
        val closingClient = client
        val closingSession = authenticatedSession
        var replacementHolds = 0
        var disposalRequested = false
        var disposalDeadline = SystemClock.uptimeMillis() + 4500
        lateinit var disposal: Runnable
        fun finish(dispose: Boolean) {
            if (pendingCloseDisposal !== disposal) return
            if (!dispose) {
                replacementHolds++
                // Bound both the older publication and the newer endpoint's preparation.
                // A queued/rejected replacement must not remove the only fail-safe forever.
                disposalDeadline = SystemClock.uptimeMillis() + 9000
                handler.removeCallbacks(disposal)
                handler.postDelayed(disposal, 9000)
                return
            }
            disposalRequested = true
            if (replacementHolds > 0) return
            cancelCloseDisposal()
            if (active.get() === this && client === closingClient &&
                authenticatedSession === closingSession && closingClient?.currentSession() === closingSession &&
                player?.mediaItemCount == 0) {
                // Explicit release also runs the app's destruction callback when Android keeps
                // a UI-bound service object alive. A stale completion can never release a successor.
                releasePlayback()
                stopSelf()
            }
        }
        disposal = Runnable {
            if (pendingCloseDisposal === disposal) {
                val remaining = disposalDeadline - SystemClock.uptimeMillis()
                if (remaining > 0) { handler.removeCallbacks(disposal); handler.postDelayed(disposal, remaining) }
                else {
                    replacementHolds = 0
                    // A stuck pending replacement has installed no output. Invalidate its
                    // continuations BEFORE disposal; the service callback also revokes owner
                    // authority and cancels queued jobs, so nothing can execute on a successor.
                    if (player?.mediaItemCount == 0) PlaybackRequests.invalidate()
                    finish(true)
                }
            }
        }
        pendingCloseDisposal = disposal
        // Covers cancellation between endpoint execution and delivery of its result to the owner.
        handler.postDelayed(disposal, 4500)
        return PlaybackFinalClose(::finish) {
            fun releaseReplacement() {
                if (pendingCloseDisposal !== disposal) return
                if (replacementHolds > 0) replacementHolds--
                if (replacementHolds == 0) {
                    if (disposalRequested) finish(true)
                    else {
                        disposalDeadline = SystemClock.uptimeMillis() + 4500
                        handler.removeCallbacks(disposal); handler.postDelayed(disposal, 4500)
                    }
                }
            }
            if (Looper.myLooper() == handler.looper) releaseReplacement()
            else handler.post { releaseReplacement() }
        }
    }

    private fun wireContext(item: LibraryItem?): PlaybackContext? = item?.let {
        PlaybackContext(it.kind, if (it.kind == "likes" && it.id.isEmpty()) "likes" else it.id, it.name)
    }

    private fun localEdit(edit: PlaybackService.() -> Unit) {
        val request = PlaybackRequests.epoch
        (application as SpofifyApplication).graph.playbackCoordinator?.localAction { permit ->
            if (request != PlaybackRequests.epoch || !acceptAuthority(permit)) PlaybackEndpointResult("rejected", exportPrepared())
            else { edit(); PlaybackEndpointResult("ok", exportPrepared()) }
        }
    }

    private fun requestReplacement(items: List<MediaItem>, requestedClient: SpofifyClient, start: Int,
        shuffle: Boolean, play: Boolean, context: LibraryItem?, notice: String?, requestEpoch: Long,
        preparedState: PlaybackSnapshot?, selectionAuthority: PreparedPlaybackAuthority?): String? {
        if (requestEpoch != PlaybackRequests.epoch || requestedClient !== client ||
            requestedClient.currentSession() !== authenticatedSession) return "Playback request cancelled after the session changed."
        val prepared = try {
            require(items.size in 1..PlaybackQueue.LIMIT && start in items.indices)
            require(wireSerial <= 9007199254740991L - items.size)
            // Keys remain unique across local replacements within the same owner epoch, so
            // a stale remote occurrence command cannot target a different new queue entry.
            if (preparedState != null) {
                val original = PlaybackSnapshotCodec.plan(preparedState)
                require(original.physicalTracks.size == items.size && original.currentIndex == start)
                require(original.physicalTracks.map { it.id to it.entryId } == items.map {
                    requireNotNull(PlaybackMedia.libraryItem(it)).let { track -> track.id to track.entryId.orEmpty() }
                })
                val keys = original.physicalTracks.mapIndexed { index, track -> track.key to (wireSerial + index + 1L) }.toMap()
                original.snapshot.copy(queue = original.snapshot.queue.map { it.copy(key = keys.getValue(it.key)) },
                    playing = play, waiting = false, loading = false, error = "", occurrence = null).publicationValidated()
            } else {
                val tracks = items.mapIndexed { index, media -> PlaybackSnapshotCodec.track(requireNotNull(PlaybackMedia.libraryItem(media)), wireSerial + index + 1L, index.toLong()) }
                val selected = tracks[start]
                val order = if (shuffle) listOf(selected) + tracks.filter { it.key != selected.key }.shuffled() else tracks
                PlaybackSnapshot(queue = order, position = order.indexOf(selected), context = wireContext(context), shuffle = shuffle,
                    playing = play, truncated = notice != null,
                    notice = if (notice?.startsWith("This selection") == true) "CLICKED_OCCURRENCE_ONLY" else if (notice != null) "PLAYBACK_LIMIT" else "").publicationValidated()
            }
        } catch (_: IllegalArgumentException) { return "This queue cannot be shared safely. Choose the collection again." }
        val derivedContext = prepared.queue.getOrNull(prepared.position)?.playbackContext?.takeIf { it.kind == "derived" }
            ?: prepared.context?.takeIf { it.kind == "derived" }
        if (derivedContext != null && selectionAuthority?.isValidFor(requestedClient, prepared) != true)
            return "This prepared selection expired. Reload the collection and choose again."
        val coordinator = (application as SpofifyApplication).graph.playbackCoordinator ?: return "Playback devices are unavailable."
        val device = coordinator.state.value
        if (device.activeDeviceId.isNotEmpty() && device.activeDeviceId != device.deviceId) {
            if (!play) return "Choose the output again before changing its prepared queue."
            val selected = prepared.queue[prepared.position]
            val command = if (derivedContext != null) {
                val token = selectionAuthority?.selectionTokenFor(requestedClient, prepared)
                if (token == null) PlaybackDeviceCommand.musical("playCollection", context = derivedContext, shuffle = shuffle)
                else PlaybackDeviceCommand.musical("play", track = prepared.toJson().getValue("queue").jsonArray[prepared.position].jsonObject,
                    context = derivedContext, entryId = selected.entryId.takeIf { it.isNotEmpty() },
                    occurrenceId = selected.occurrenceId, selectionToken = token)
            } else if (prepared.context != null) PlaybackDeviceCommand.musical("playCollection", context = prepared.context,
                shuffle = shuffle, trackId = selected.id, entryId = selected.entryId.takeIf { it.isNotEmpty() })
            else {
                // The protocol has no arbitrary prepared-queue replacement command. A new bare
                // track Play follows the same explicit musical selection contract as the web.
                if (items.size != 1) return "Choose a named collection to play this queue on another device."
                PlaybackDeviceCommand.musical("play", track = prepared.toJson().getValue("queue").jsonArray[prepared.position].jsonObject)
            }
            coordinator.remoteCommand(command) {
                requestEpoch == PlaybackRequests.epoch && requestedClient === client &&
                    requestedClient.currentSession() === authenticatedSession &&
                    (derivedContext == null || selectionAuthority?.isValidFor(requestedClient, prepared) == true)
            }
        } else coordinator.localAction { permit ->
            if (requestEpoch != PlaybackRequests.epoch || requestedClient !== client || requestedClient.currentSession() !== authenticatedSession ||
                derivedContext != null && selectionAuthority?.isValidFor(requestedClient, prepared) != true)
                PlaybackEndpointResult("rejected", exportPrepared())
            else importSnapshot(prepared, permit)
        }
        return null
    }

    private fun requestEnqueue(items: List<LibraryItem>, requestedClient: SpofifyClient, first: Boolean): String? {
        if (requestedClient !== client || requestedClient.currentSession() !== authenticatedSession) return "Sign in again before adding music."
        val coordinator = (application as SpofifyApplication).graph.playbackCoordinator ?: return "Playback devices are unavailable."
        val tracks = items.filter { it.kind == "track" && it.playable }
        if (tracks.isEmpty()) return "No playable tracks to add."
        val device = coordinator.state.value
        if (device.activeDeviceId.isNotEmpty() && device.activeDeviceId != device.deviceId) {
            if (tracks.size != 1) return "Adding a prepared collection remotely is unavailable; add a song or use the active device."
            val wire = PlaybackSnapshot(queue = listOf(PlaybackSnapshotCodec.track(tracks.single(), 1, 0)), position = 0).toJson()
            coordinator.remoteCommand(PlaybackDeviceCommand.musical("enqueue", track = wire.getValue("queue").jsonArray.single().jsonObject, first = first))
        } else {
            val request = PlaybackRequests.epoch
            coordinator.localAction { permit ->
                if (request != PlaybackRequests.epoch || !acceptAuthority(permit)) PlaybackEndpointResult("rejected", exportPrepared())
                else PlaybackEndpointResult(if (enqueue(tracks, requestedClient, first) == null) "ok" else "rejected", exportPrepared())
            }
        }
        return null
    }

    private suspend fun executeMusical(command: PlaybackDeviceCommand, value: PlaybackAuthority): PlaybackEndpointResult {
        val source = client ?: return PlaybackEndpointResult("rejected", exportPrepared())
        if (PlaybackCommandResolver.selectsCurrent(command, exportPrepared())) {
            val current = player ?: return PlaybackEndpointResult("rejected", PlaybackSnapshot())
            return if (current.playWhenReady && current.playerError == null) PlaybackEndpointResult("ok", exportPrepared())
                else executeOwned(PlaybackDeviceCommand.transport("resume"), value)
        }
        val request = authorityRequestEpoch
        val resolved = try { PlaybackCommandResolver.resolve(command, source) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return PlaybackEndpointResult("rejected", exportPrepared()) }
            ?: return PlaybackEndpointResult("rejected", exportPrepared())
        if (authority !== value || request != PlaybackRequests.epoch || !hasAuthority()) return PlaybackEndpointResult("rejected", exportPrepared())
        if (resolved.enqueue) {
            val result = enqueueWire(resolved.tracks, resolved.first)
            if (result == null && resolved.truncated) markQueueLimited()
            return PlaybackEndpointResult(if (result == null) "ok" else "rejected", exportPrepared())
        }
        if (wireSerial > 9007199254740991L - resolved.tracks.size) return PlaybackEndpointResult("rejected", exportPrepared())
        // Resolver keys are placeholders, not live occurrence identity. A NEW musical queue
        // must not reuse keys from a previous prepared queue in this same authority epoch.
        val tracks = resolved.tracks.mapIndexed { index, track -> track.copy(key = wireSerial + index + 1L) }
        val chosen = tracks[resolved.startIndex]
        val order = if (!resolved.shuffle) tracks else if (resolved.pinStart)
            listOf(chosen) + tracks.filter { it.key != chosen.key }.shuffled() else tracks.shuffled()
        return importSnapshot(PlaybackSnapshot(queue = order, position = if (resolved.pinStart) order.indexOf(chosen) else 0,
            context = resolved.context, shuffle = resolved.shuffle, playing = true, truncated = resolved.truncated, notice = resolved.notice), value)
    }

    private fun enqueueWire(tracks: List<PlaybackTrack>, first: Boolean): String? {
        val source = client ?: return "Playback is unavailable."
        val current = player ?: return "Playback is unavailable."
        if (!hasAuthority()) return "Playback permission expired."
        // Only derived occurrence identity deduplicates; ordinary duplicate songs remain distinct.
        val additions = tracks.filter { track -> track.targetId.isEmpty() || wireTracks.values.none {
            it.targetId == track.targetId && it.occurrenceId == track.occurrenceId
        } }
        if (additions.isEmpty()) return null
        var order = traversalIds()
        val retained = PlaybackQueue.retainedForAdd(order, currentId(), additions.size)
        val room = PlaybackQueue.LIMIT - retained.size
        if (room <= 0) return "Queue is full."
        val baselineMax = wireTracks.values.maxOfOrNull { it.order } ?: physicalIds().size.toLong()
        if (wireSerial > 9007199254740991L - additions.size || baselineMax > 9007199254740991L - additions.size) return "Queue identifiers are exhausted. Choose music again."
        val accepted = additions.take(room).mapIndexed { index, track -> track.copy(key = wireSerial + index + 1, order = baselineMax + index + 1) }
        // Validate the COMBINED exact prospective queue before retiring entries, allocating
        // live keys, replacing history/metadata or adding media. Individually safe batches
        // can exceed the 2MiB publication envelope when joined.
        val before = exportPrepared()
        val insertion = if (first && currentId() != null) retained.indexOf(currentId()) + 1 else retained.size
        val retainedTracks = retained.map { id -> before.queue.first { it.key == wireKeys.getValue(id) } }
        val prospective = retainedTracks.toMutableList().apply { addAll(insertion, accepted) }.let { tracks ->
            if (importedShuffle ?: current.shuffleModeEnabled) tracks else tracks.mapIndexed { index, track -> track.copy(order = index.toLong()) }
        }
        try {
            before.copy(queue = prospective,
                position = before.queue.getOrNull(before.position)?.key?.let { key -> prospective.indexOfFirst { it.key == key } } ?: 0,
                truncated = before.truncated || accepted.size < additions.size,
                notice = if (accepted.size < additions.size) "PLAYBACK_LIMIT" else before.notice).publicationValidated()
        } catch (_: IllegalArgumentException) { return "This queue is too large to share safely." }
        val media = try { accepted.map { PlaybackMedia.create(PlaybackSnapshotCodec.libraryItem(it), source, "wire-${it.key}") } }
            catch (_: IllegalArgumentException) { return "Invalid queue entry." }
        wireSerial = accepted.last().key
        if (retained != order) {
            val retired = order.toSet() - retained.toSet()
            for (index in current.mediaItemCount - 1 downTo 0) if (PlaybackMedia.occurrence(current.getMediaItemAt(index)) in retired) current.removeMediaItem(index)
            order = retained
        }
        val added = accepted.map { "wire-${it.key}" }
        accepted.forEachIndexed { index, track ->
            wireKeys[added[index]] = track.key; wireTracks[added[index]] = track
            allowedAudioUrls.add(requireNotNull(media[index].localConfiguration).uri.toString())
        }
        val next = order.toMutableList().apply { addAll(insertion, added) }
        if (current.mediaItemCount == 0 || current.playbackState == Player.STATE_ENDED) current.pause()
        PlaybackRequests.queueChanged()
        current.addMediaItems(media)
        removed = null
        if (importedShuffle ?: current.shuffleModeEnabled) setTraversal(next) else adoptDisplayedOrder(next)
        if (accepted.size < additions.size) markQueueLimited()
        schedulePrune()
        return null
    }

    private fun markQueueLimited() {
        queueMessage = "Queue limited to 1,000 songs."
        queueTruncated = true
        queueNoticeCode = "PLAYBACK_LIMIT"
    }

    private fun toggleOwnedShuffle() {
        val current = player ?: return
        val order = traversalIds(); val at = order.indexOf(currentId())
        val enabled = !(importedShuffle ?: current.shuffleModeEnabled)
        val future = order.drop(at + 1).let { ids -> if (enabled) ids.shuffled() else ids.sortedBy { wireTracks[it]?.order ?: physicalIds().indexOf(it).toLong() } }
        arranging = true
        try { current.shuffleModeEnabled = true; setTraversal(order.take(at + 1) + future); importedShuffle = enabled }
        finally { arranging = false }
        removed = null
    }

    private fun restartTraversal() {
        val current = player ?: return
        sampleHistory(current.currentMediaItem, current.currentPosition, historyWasPlaying)
        currentId()?.let { id ->
            history[id] = PlaybackProgress()
            progressRemainders.remove(id)
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
        PlaybackRequests.queueChanged()
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

    private fun enqueue(items: List<LibraryItem>, requestedClient: SpofifyClient, first: Boolean): String? {
        if (!hasAuthority() || requestedClient !== client) return "Playback permission expired."
        val tracks = items.filter { it.kind == "track" && it.playable }
        if (tracks.isEmpty()) return "No playable tracks to add."
        return enqueueWire(tracks.mapIndexed { index, item -> PlaybackSnapshotCodec.track(item, index + 1L, index.toLong()) }, first)
    }

    /** Move existing MediaItems instead of resetting the playlist: current audio/position survives. */
    private fun adoptDisplayedOrder(ids: List<String>) {
        val current = player ?: return
        arranging = true
        PlaybackRequests.queueChanged()
        try {
            ids.forEachIndexed { target, id ->
                val from = physicalIds().indexOf(id)
                if (from >= 0 && from != target) current.moveMediaItem(from, target)
            }
            current.shuffleModeEnabled = false
            importedShuffle = false
            ids.forEachIndexed { index, id -> wireTracks[id]?.let { wireTracks[id] = it.copy(order = index.toLong()) } }
        } finally { arranging = false }
    }

    private fun removeUpcoming(id: String) {
        val current = player ?: return
        val order = traversalIds()
        val removal = PlaybackQueue.removal(order, currentId(), id) ?: return
        val index = physicalIds().indexOf(id)
        val media = current.getMediaItemAt(index)
        removedTrack = wireTracks[id]
        PlaybackRequests.queueChanged()
        current.removeMediaItem(index)
        if (current.shuffleModeEnabled) setTraversal(removal.remaining)
        removed = removal to media
    }

    private fun validRemoval(): Boolean = removed?.first?.canUndo(traversalIds(), currentId()) == true

    private fun undoRemoval() {
        val current = player ?: return
        val (removal, media) = removed ?: return
        val restored = removal.undo(traversalIds(), currentId()) ?: run { removed = null; return }
        val before = exportPrepared()
        val restoredTracks = restored.map { id ->
            if (id == removal.occurrence) removedTrack ?: return
            else before.queue.first { it.key == wireKeys.getValue(id) }
        }.let { tracks -> if (current.shuffleModeEnabled) tracks else tracks.mapIndexed { index, track -> track.copy(order = index.toLong()) } }
        try {
            before.copy(queue = restoredTracks, position = restored.indexOf(currentId())).publicationValidated()
        } catch (_: IllegalArgumentException) { return }
        removed = null
        removedTrack?.let { track -> wireKeys[removal.occurrence] = track.key; wireTracks[removal.occurrence] = track }
        removedTrack = null
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
        PlaybackRequests.queueChanged()
        for (index in current.mediaItemCount - 1 downTo 0) {
            if (PlaybackMedia.occurrence(current.getMediaItemAt(index)) in future) current.removeMediaItem(index)
        }
        if (current.shuffleModeEnabled) setTraversal(remaining)
    }

    private inner class SessionCallback(private val sharedClient: SpofifyClient) : MediaSession.Callback {
        override fun onPlayerCommandRequest(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            if (!trusted(controller) || sharedClient !== client || authenticatedSession == null || sharedClient.currentSession() !== authenticatedSession)
                return SessionResult.RESULT_ERROR_PERMISSION_DENIED
            if (playerCommand in setOf(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA))
                return SessionResult.RESULT_SUCCESS
            if (playerCommand == Player.COMMAND_PREPARE) return SessionResult.RESULT_ERROR_NOT_SUPPORTED
            if (admissions.containsKey(controller)) {
                // Do not overwrite a possibly pending Play with an uncorrelatable Play/Pause
                // family request. Silence locally; explicit app/media-button intents remain usable.
                PlaybackRequests.invalidate(); suspendBufferedOutput()
                return SessionResult.RESULT_ERROR_INVALID_STATE
            }
            admissions[controller] = Admission(playerCommand, PlaybackRequests.epoch)
            return SessionResult.RESULT_SUCCESS
        }

        override fun onPlayerInteractionFinished(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, playerCommands: Player.Commands) {
            admissions.remove(controller)
        }

        override fun onDisconnected(mediaSession: MediaSession, controller: MediaSession.ControllerInfo) { admissions.remove(controller) }

        override fun onMediaButtonEvent(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaButtonIntent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return true
            if (!trusted(controller) || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || event.eventTime < lastSuspendedUptime) return true
            val remote = (application as SpofifyApplication).graph.playbackCoordinator?.state?.value
            val playing = if (remote?.activeDeviceId != remote?.deviceId) remote?.view?.state?.playing == true else player?.playWhenReady == true
            val type = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> "resume"
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> "pause"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (playing) "pause" else "resume"
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> "next"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> "previous"
                else -> null
            }
            type?.let { routeCommand(PlaybackDeviceCommand.transport(it), userInteraction = true) }
            return true // Never leave a delayed headset single/double-tap start in Media3.
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (!trusted(controller)) return MediaSession.ConnectionResult.reject()
            val defaults = super.onConnect(session, controller)
            // ALL controllers, including same-process UI controllers, use the routed wrapper.
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
            // Queue replacement is only accepted through the service's authority-fenced bridge.
            require(false) { "Use the coordinated playback bridge" }
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
