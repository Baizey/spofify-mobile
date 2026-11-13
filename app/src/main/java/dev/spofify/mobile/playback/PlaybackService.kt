package dev.spofify.mobile.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import dev.spofify.mobile.OfflineEligibility
import dev.spofify.mobile.OfflineMediaReference
import dev.spofify.mobile.playback.cache.SongCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackOccurrence
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.LocalPlaybackCommand
import dev.spofify.mobile.core.QueueIntentIntent
import dev.spofify.mobile.core.LibraryPlaybackInstruction
import dev.spofify.mobile.core.LibraryPlaybackSnapshot
import dev.spofify.mobile.core.LibraryPlaybackTrack
import dev.spofify.mobile.core.LibraryPlaybackCommand
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.feature.devices.LibraryPlaybackUserAction
import dev.spofify.mobile.feature.devices.LibraryPlaybackAuthority
import dev.spofify.mobile.feature.devices.LibraryPlaybackCacheLineage
import dev.spofify.mobile.feature.devices.LibraryPlaybackObservation
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding
import dev.spofify.mobile.feature.devices.PlaybackEndpointResult
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        private const val ACTION_TRANSPORT = "dev.spofify.mobile.playback.TRANSPORT"
        private const val ACTION_KEEP_ALIVE = "dev.spofify.mobile.playback.KEEP_ALIVE"
        private const val USER_ACTION_START_WINDOW_MS = 5_000L
        private const val FOREGROUND_REPLACEMENT_WINDOW_MS = 3_500L
        private var active = WeakReference<PlaybackService>(null)

        fun isActive(): Boolean = active.get()?.player != null
        fun stopActivePlayback() { active.get()?.shutdownPlayback() }
        fun enterOfflineMode() { active.get()?.let { service ->
            service.localOnlyQueue = true
            if (service.localQueueEligibility == null)
                service.localQueueEligibility = (service.application as SpofifyApplication).graph.localEligibility
            service.localAuthority = null
            service.suspendBufferedOutput()
        } }
        fun retireLocalEligibility(expected: OfflineEligibility? = null) { active.get()?.let { service ->
            if (expected != null && service.localQueueEligibility !== expected) return@let
            service.localAuthority = null
            if (service.localOnlyQueue) service.suspendBufferedOutput()
            if (expected == null && !(service.application as SpofifyApplication).graph.offlineMode.value) {
                // Explicit mode exit retires local transport/output authority, not saved
                // media's namespace. Keep that identity so a later mode entry cannot
                // relabel dormant items for a successor account/incarnation.
                service.dormantLocalMedia = true
                service.localOnlyQueue = false
                service.libraryOwner = null
                service.libraryStartAllowance = null
                service.invalidateLibraryMedia()
            }
        } }
        fun isLocalOnlyQueue(): Boolean = active.get()?.localOnlyQueue == true
        fun localTransport(command: LocalPlaybackCommand, isCurrent: () -> Boolean = { true }): Boolean =
            active.get()?.dispatchLocalTransport(command, isCurrent) == true
        fun localOccurrence(expectedKey: PlaybackInteractionKey, id: String, action: PlaybackListAction,
            isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            val service = active.get()
            if (service == null) onResult(PlaybackActionResult(PlaybackActionStatus.UNAVAILABLE))
            else service.editLocalOccurrence(expectedKey, id, action, isCurrent, onResult)
        }
        fun replaceLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
            mediaReferences: Map<String, OfflineMediaReference>, start: Int, shuffle: Boolean,
            context: LibraryItem? = null, isCurrent: () -> Boolean = { true }): String? =
            active.get()?.requestLocalReplacement(items, eligibility, mediaReferences, start, shuffle, context, isCurrent)
                ?: "Playback is unavailable. Choose saved music again."
        fun enqueueLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
            mediaReferences: Map<String, OfflineMediaReference>, first: Boolean, isCurrent: () -> Boolean): String? =
            enqueueLocal(items, eligibility, mediaReferences, if (first) QueueIntentIntent.NEXT else QueueIntentIntent.LOW, isCurrent)
        fun enqueueLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
            mediaReferences: Map<String, OfflineMediaReference>, intent: QueueIntentIntent, isCurrent: () -> Boolean): String? =
            active.get()?.requestLocalEnqueue(items, eligibility, mediaReferences, intent, isCurrent)
                ?: "Choose saved music before adding to its queue."
        fun localQueueReplacementFence(): () -> Boolean {
            val service = active.get()
            val eligibility = service?.localQueueEligibility
            val permit = service?.localAuthority
            val request = PlaybackRequests.epoch
            val queue = PlaybackRequests.queueEpoch
            return { service != null && active.get() === service && service.localOnlyQueue &&
                service.localQueueEligibility === eligibility && service.localAuthority === permit &&
                PlaybackRequests.epoch == request && PlaybackRequests.queueEpoch == queue }
        }
        fun interactionKey(): PlaybackInteractionKey? = active.get()?.currentInteractionKey()
        internal fun queueOriginFence(): () -> Boolean {
            val service = active.get()
            val key = service?.currentInteractionKey()
            return { service != null && active.get() === service && service.currentInteractionKey() === key }
        }
        fun canSkipPrepared(forward: Boolean): Boolean = active.get()?.canSkip(forward) == true
        fun transportCapabilityRevision(): Long = active.get()?.transportCapabilityRevision ?: 0L
        fun skipPrepared(expectedKey: PlaybackInteractionKey, forward: Boolean, originCurrent: () -> Boolean): Boolean {
            val service = active.get() ?: return false
            if (!originCurrent() || service.currentInteractionKey() !== expectedKey || !service.canSkip(forward)) return false
            return service.dispatchLocalTransport(LocalPlaybackCommand.transport(if (forward) "next" else "previous"), originCurrent)
        }
        fun removeQueued(id: String) { active.get()?.localEdit { removeUpcoming(id) } }
        fun removeQueued(expectedKey: PlaybackInteractionKey, id: String, isCurrent: () -> Boolean) {
            active.get()?.localEdit { if (isCurrent() && currentInteractionKey() === expectedKey) removeUpcoming(id) }
        }
        fun moveQueued(id: String, to: Int) { active.get()?.localEdit { moveUpcoming(id, to) } }
        fun moveQueued(expectedKey: PlaybackInteractionKey, id: String, to: Int) {
            active.get()?.localEdit { if (currentInteractionKey() === expectedKey) moveUpcoming(id, to) }
        }
        fun clearQueued() { active.get()?.localEdit { clearUpcoming() } }
        fun undoQueuedRemoval() { active.get()?.localEdit { undoRemoval() } }
        fun queuedKey(id: String): Long? = active.get()?.takeIf { it.localOnlyQueue }?.wireKeys?.get(id)
        fun timelineKey(index: Int): Long? = active.get()?.takeIf { it.localOnlyQueue }?.let { service ->
            service.player?.takeIf { index in 0 until it.mediaItemCount }?.getMediaItemAt(index)
                ?.let(PlaybackMedia::occurrence)?.let { service.wireKeys[it] }
        }
        fun upcomingEntries(): List<QueueEntry> = active.get()?.takeIf { it.localOnlyQueue && it.localNamespaceCurrent() }?.upcoming().orEmpty()
        fun canUndoRemoval(): Boolean = active.get()?.takeIf { it.localOnlyQueue && it.localNamespaceCurrent() }?.validRemoval() == true
        fun setQueueContext(value: LibraryItem?) { active.get()?.localEdit { contextIdentity = value?.copy(libraryPath = null); wireContext = wireContext(value) } }
        fun queueContext(): LibraryItem? = active.get()?.takeIf { it.localOnlyQueue && it.localNamespaceCurrent() }?.let { service ->
            (service.currentId()?.let { service.wireTracks[it]?.playbackContext } ?: service.wireContext)
                ?.let { LibraryItem(it.kind, it.id, it.name) } ?: service.contextIdentity
        }
        fun setQueueNotice(value: String?) { active.get()?.localEdit { queueMessage = value } }
        fun queueNotice(): String? = active.get()?.takeIf { it.localOnlyQueue && it.localNamespaceCurrent() }?.queueMessage
        fun restartCurrentTraversal() { active.get()?.localEdit { restartTraversal() } }
        fun displayLocalSnapshot(): PlaybackSnapshot = active.get()?.takeIf { it.localOnlyQueue && it.localNamespaceCurrent() }
            ?.exportPrepared() ?: PlaybackSnapshot()
        fun suspendLocalOutput() { active.get()?.suspendBufferedOutput() }
        suspend fun prepareLibrary(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean, owner: Any,
            observation: (String, Boolean) -> Unit): String =
            active.get()?.prepareLibraryOutput(instruction, isCurrent, owner, observation) ?: "blocked"
        fun releaseLibrary(owner: Any): Boolean = active.get()?.let {
            if (it.libraryOwner === owner) it.releaseLibraryOutput() else false
        } ?: true
        suspend fun executeLibrary(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority, owner: Any,
            observation: (String, Boolean) -> Unit): String =
            active.get()?.takeIf { it.libraryOwner === owner }?.executeLibraryOutput(instruction, authority, observation) ?: "rejected"
        fun observeLibrary(owner: Any): LibraryPlaybackObservation = active.get()?.takeIf { it.libraryOwner === owner }
            ?.observeLibraryOutput() ?: LibraryPlaybackObservation()
        fun parkLibraryCacheReads(owner: Any, lineage: LibraryPlaybackCacheLineage) {
            active.get()?.takeIf { it.libraryOwner === owner && !it.localOnlyQueue && it.librarySessionCurrent() && lineage.isValid() }?.let {
                it.parkedCacheLineage = lineage
                it.prefetchAllowed = true
                it.scheduleCachePlan()
            }
        }
        fun suspendLibrary(owner: Any) {
            active.get()?.takeIf { !it.localOnlyQueue && it.libraryOwner === owner }?.suspendBufferedOutput()
        }
        fun suspendLibraryForReplacement(owner: Any) {
            active.get()?.takeIf { !it.localOnlyQueue && it.libraryOwner === owner }
                ?.suspendBufferedOutput(retainForeground = true)
        }
        var onServiceActiveChanged: ((Boolean) -> Unit)? = null
    }

    @Volatile private var player: ExoPlayer? = null
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
    private var mediaButtonBoundaryUptime = SystemClock.uptimeMillis()
    private data class NotificationBinding(
        val owner: PlaybackCoordinator?, val version: Any?, val localCurrent: PlaybackInteractionKey?,
    )
    private var notificationBinding: NotificationBinding? = null
    private var notificationBindingSerial = 0L
    private data class Admission(val command: Int, val requestEpoch: Long)
    private val admissions = mutableMapOf<MediaSession.ControllerInfo, Admission>()
    private data class MediaButtonDown(val key: Int, val device: Int, val source: Int, val down: Long, val time: Long)
    private val mediaButtonDowns = linkedSetOf<MediaButtonDown>() // Bounded exact-event dedup, not tap debounce.
    // Queue provenance outlives local access and mode changes. It is NEVER a receiver lease.
    @Volatile private var localOnlyQueue = false
    // Installed saved media may remain dormant after mode exit, but is neither a
    // local transport grant nor online prepared media. Do not rearm it from a tick.
    private var dormantLocalMedia = false
    private var foregroundTransitionDeadline = 0L
    private var libraryStartAllowance: Pair<String, () -> Boolean>? = null
    @Volatile private var localQueueEligibility: OfflineEligibility? = null
    private fun localNamespaceCurrent(): Boolean {
        if (!localOnlyQueue) return true
        val expected = localQueueEligibility ?: return player?.mediaItemCount == 0
        val graph = (application as SpofifyApplication).graph
        return graph.localEligibility === expected && graph.client?.server?.origin == expected.origin
    }
    private class LocalAuthority(val eligibility: OfflineEligibility, @Volatile var epoch: Long, sources: Map<String, OfflineMediaReference>) {
        val sources = ConcurrentHashMap(sources)
    }
    @Volatile private var localAuthority: LocalAuthority? = null
    private var localCommandSerial = 0L
    private fun hasLocalAuthority(): Boolean = localAuthority?.let {
        localOnlyQueue && localQueueEligibility === it.eligibility && active.get() === this && it.epoch == PlaybackRequests.epoch &&
            (application as SpofifyApplication).graph.localPlaybackIsCurrent(it.eligibility)
    } == true
    @Volatile private var client: SpofifyClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var authority: LibraryPlaybackAuthority? = null
    private var libraryOwner: Any? = null
    @Volatile private var preparingRead: (() -> Boolean)? = null
    @Volatile private var libraryGeneration = 0L
    private var libraryListener: Player.Listener? = null
    private var libraryPending = false
    private var libraryFailureGeneration = -1L
    private var libraryFailure = ""
    private var libraryPreparedTrack: LibraryPlaybackTrack? = null
    private var libraryPreparedEvent: String? = null
    private var libraryPreparedMediaIdentity: Any? = null
    private var libraryPreparedClient: SpofifyClient? = null
    private var libraryPreparedSession: Session? = null
    @Volatile private var authenticatedSession: Session? = null
    private var audioCache: SongCache.SessionCache? = null
    @Volatile private var onlineSources: DataSource.Factory? = null
    @Volatile private var onlineUpstream: DataSource.Factory? = null

    /** Refresh only at service construction or a fresh validated ordinary online Play.
     * The unchanged MediaSourceFactory reads this binding dynamically; no second player. */
    private fun bindOnlineAudio(source: SpofifyClient, cache: SongCache.SessionCache?) {
        val upstream = OkHttpDataSource.Factory(source.httpClient)
        audioCache = cache
        onlineUpstream = upstream
        onlineSources = SongCache.authorizedFactory(cache, upstream, { spec -> spec.key },
            ::authorizeAudio, ::authorizeNetworkAudio, ::onDemandAudioNetworkChanged)
    }

    private fun onDemandAudioNetworkChanged(active: Boolean) {
        demandAudioOperations.addAndGet(if (active) 1 else -1)
        // Prioritize actual opens/reads, not idle streams. Aggregate concurrent operations
        // and coalesce completions; keep player-state sampling on main.
        if (active) savedDemandPublication.active { demanded ->
            songPrefetch?.setDemandActive(demanded)
            publishSavedPlaybackDemand(savedDemandBinding, demanded)
        } else if (cachePriorityPosted.compareAndSet(false, true)) handler.post(updateCachePriority)
    }

    private class PrefetchAuthorization(
        val coordinator: PlaybackCoordinator, val binding: LibraryPlaybackActionBinding,
        val source: SpofifyClient, val login: Session, val generation: Long,
        val permit: LibraryPlaybackAuthority?, val parked: LibraryPlaybackCacheLineage?, val cache: SongCache.SessionCache,
        val upstream: DataSource.Factory, val plan: SongPrefetchPlan, val current: () -> Boolean,
    )
    private data class PrefetchRequest(val trackId: String, val spec: DataSpec, val authorization: PrefetchAuthorization)
    @Volatile private var prefetchAuthorization: PrefetchAuthorization? = null
    @Volatile private var parkedCacheLineage: LibraryPlaybackCacheLineage? = null
    private var songPrefetch: SongPrefetchScheduler<PrefetchRequest>? = null
    private var prefetchAllowed = false // Explicit local action only; never passive recovery/import.
    private val demandAudioOperations = AtomicInteger()
    private val savedDemandPublication = SavedDemandPublication(demandAudioOperations)
    private val cachePriorityPosted = AtomicBoolean()
    private data class SavedDemandBinding(val client: SpofifyClient,
        val handle: dev.spofify.mobile.feature.playlists.SavedPlaybackDemand)
    @Volatile private var savedDemandBinding: SavedDemandBinding? = null
    private val updateCachePriority = Runnable {
        cachePriorityPosted.set(false)
        if (active.get() === this) updatePrefetchPriority()
    }
    // One captured client/service handle. Online factory rearming retains this binding.
    private fun bindSavedPlaybackDemand(capturedClient: SpofifyClient): SavedDemandBinding? {
        val graph = (application as SpofifyApplication).graph
        if (active.get() !== this || graph.client !== capturedClient) return null
        val next = SavedDemandBinding(capturedClient, graph.savedDownloads.playbackDemand(capturedClient))
        val previous = savedDemandBinding
        savedDemandBinding = next
        previous?.handle?.close()
        updatePrefetchPriority()
        return next
    }
    private fun publishSavedPlaybackDemand(binding: SavedDemandBinding?, demanded: Boolean) {
        if (binding != null && active.get() === this && savedDemandBinding === binding &&
            (application as SpofifyApplication).graph.client === binding.client) binding.handle.setActive(demanded)
    }
    private fun updatePrefetchPriority() {
        val playerDemanded = player?.isLoading == true || player?.playbackState == Player.STATE_BUFFERING
        savedDemandPublication.aggregate(playerDemanded) { demanded ->
            songPrefetch?.setDemandActive(demanded)
            publishSavedPlaybackDemand(savedDemandBinding, demanded)
        }
    }
    private var promotionPending: Any? = null
    private var promotionOccurrence: String? = null
    private val reconcileCache = Runnable { reconcileSongPrefetch() }

    private fun scheduleCachePlan() {
        handler.removeCallbacks(reconcileCache)
        handler.post(reconcileCache)
    }

    private fun enableSongPrefetch() {
        if (!hasAuthority()) return
        prefetchAllowed = true
        scheduleCachePlan()
    }

    private fun retirePrefetchPlan() {
        prefetchAuthorization = null
        songPrefetch?.cancel()
    }

    private fun cancelSongPrefetch() {
        prefetchAllowed = false
        parkedCacheLineage = null
        retirePrefetchPlan()
        // Control cancellation/protection never waits for speculative storage or its cleanup.
        audioCache?.protectPlayback(null)
    }

    private fun authorizeAudio(spec: DataSpec) {
        val source = client
        val login = authenticatedSession
        val id = spec.key
        if (localOnlyQueue || (application as SpofifyApplication).graph.offlineMode.value ||
            source == null || login == null || source !== (application as SpofifyApplication).graph.client ||
            !source.hasValidSession(login) || (!hasOutputPermission() && preparingRead?.invoke() != true) || spec.uri.toString() !in allowedAudioUrls ||
            id == null || source.audioUrl(LibraryItem("track", id, "", hasAudio = true)) != spec.uri.toString()) {
            throw IOException("Playback source is not in the authorized queue")
        }
    }

    private fun authorizeNetworkAudio(spec: DataSpec) {
        authorizeAudio(spec)
        if (!hasAuthority() && preparingRead?.invoke() != true) throw IOException("Playback authority changed.")
    }

    private fun reconcileSongPrefetch() {
        val current = player ?: return
        val parked = parkedCacheLineage?.takeIf { it.isValid() }
        val live = hasAuthority()
        if (!prefetchAllowed || importing || (!live && parked == null) || current.currentMediaItem == null) {
            cancelSongPrefetch()
            return
        }
        // The server's canonical traversal is read-only cache demand, never a Media3
        // playlist or progression authority. Bound occurrences BEFORE track-ID deduplication.
        val coordinator = (application as SpofifyApplication).graph.playbackCoordinator
        val binding = coordinator?.captureBinding()
        val devices = coordinator?.state?.value
        val state = devices?.snapshot?.state
        val permit = authority?.takeIf { live }
        val source = client
        val login = authenticatedSession
        val cache = audioCache
        val upstream = onlineUpstream
        if (coordinator == null || binding == null || devices == null || state == null || (permit == null && parked == null) || source == null || login == null ||
            cache == null || upstream == null || !devices.isSelectedConnection || devices.snapshot?.transfer != null ||
            state.queue.getOrNull(state.position)?.key != libraryPreparedTrack?.key ||
            state.queue.getOrNull(state.position)?.id != current.currentMediaItem?.mediaId ||
            state.occurrence?.eventId != libraryPreparedEvent) {
            retirePrefetchPlan() // retain eligibility to replan after an admitted-next confirmation
            return
        }
        val future = sequence {
            var index = state.position
            repeat(5) {
                if (state.repeat != "one") {
                    index++
                    if (index >= state.queue.size) {
                        if (state.repeat != "all") return@sequence
                        index = 0
                    }
                }
                yield(state.queue[index].id)
            }
        }
        val plan = SongPrefetch.plan(current.currentMediaItem?.mediaId, future)
        val nativeGeneration = libraryGeneration
        val event = libraryPreparedEvent
        val previous = prefetchAuthorization
        val authorization = previous?.takeIf { it.coordinator === coordinator && it.binding == binding && it.source === source &&
            it.login === login && it.generation == nativeGeneration && it.permit === permit && it.parked === parked && it.cache === cache &&
            it.upstream === upstream && it.plan == plan } ?: run {
            retirePrefetchPlan() // same track IDs under a new queue/output fence need new read permits
            val dataDeadline = System.nanoTime() + SongPrefetchScheduler.DEFAULT_ATTEMPT_TIMEOUT_MS * 1_000_000L
            PrefetchAuthorization(coordinator, binding, source, login, nativeGeneration, permit, parked, cache, upstream, plan) {
                val graph = (application as SpofifyApplication).graph
                val latest = coordinator.state.value
                active.get() === this && libraryGeneration == nativeGeneration && System.nanoTime() < dataDeadline &&
                    (if (permit != null) authority === permit && permit.isValid()
                        else parked != null && parkedCacheLineage === parked && parked.isValid()) &&
                    !localOnlyQueue && !graph.offlineMode.value && graph.client === source && client === source &&
                    authenticatedSession === login && source.hasValidSession(login) && graph.playbackCoordinator === coordinator &&
                    coordinator.isCurrent(binding) && latest.isSelectedConnection && latest.snapshot?.transfer == null &&
                    latest.connectionId == binding.connectionId && latest.snapshot?.state?.occurrence?.eventId == event
            }.also { prefetchAuthorization = it }
        }
        val requests = plan.trackIds.map { id ->
            PrefetchRequest(id, DataSpec.Builder().setUri(source.audioUrl(LibraryItem("track", id, "", hasAudio = true)))
                .setKey(id).build(), authorization)
        }
        updatePrefetchPriority()
        songPrefetch?.reconcile(plan, requests)
    }

    /** Speculative cache reads are not player URL admission, a queue, or an audible lease. */
    private fun authorizeSpeculativeAudio(request: PrefetchRequest, spec: DataSpec) {
        val authorization = request.authorization
        if (prefetchAuthorization !== authorization || !authorization.current() ||
            request.trackId !in authorization.plan.trackIds || spec.key != request.trackId || spec.uri != request.spec.uri ||
            authorization.source.audioUrl(LibraryItem("track", request.trackId, "", hasAudio = true)) != spec.uri.toString())
            throw IOException("Speculative audio plan changed.")
    }

    private fun promoteSuccessfulPlayback(startSucceeded: Boolean = false) {
        if (localOnlyQueue) return
        if (promotionPending == null || (!startSucceeded && promotionPending === startAttempt) ||
            player?.isPlaying != true || !hasOutputPermission() || audioFocus?.isGranted != true) return
        promotionPending = null
        player?.currentMediaItem?.mediaId?.let {
            audioCache?.protectPlayback(it)
            audioCache?.promote(it)
        }
    }

    private val interactionKeys = PlaybackInteractionKeys()
    private var transportCapabilityRevision = 0L
    private fun skipCapabilities(): Pair<Boolean, Boolean> {
        val current = player ?: return false to false
        if (current.currentMediaItem == null) return false to false
        return (current.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && current.hasNextMediaItem()) to
            current.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)
    }
    private fun canSkip(forward: Boolean): Boolean = localOnlyQueue && hasLocalAuthority() &&
        skipCapabilities().let { if (forward) it.first else it.second }
    private fun currentInteractionKey(): PlaybackInteractionKey? {
        if (!localOnlyQueue) return null
        observePreparedSemantics(preparedSemanticSnapshot())
        return interactionKeys.current(localAuthority?.takeIf { hasLocalAuthority() }?.let {
            listOf(PlaybackReference(it), PlaybackRequests.epoch, PlaybackRequests.queueEpoch,
                currentId(), traversalIds(), intentRevision.revision)
        })
    }
    private var pendingStart: Any? = null
    private var pendingStartIsCurrent: (() -> Boolean)? = null
    private var upcomingStartPreservation: (() -> Boolean)? = null
    private var startAttempt: Any? = null // Actual resource/cleanup owner until drained or explicit successor takeover.
    private var startAttemptValidity: Any? = null // Semantic cancellation does NOT transfer resource ownership.
    private data class StartHistoryObservation(
        val token: Any, val current: String?, var before: PlaybackSnapshot,
        var metadata: LocalMetadata, var revision: Long,
        var createdEventId: String? = null,
    )
    private var startHistoryObservation: StartHistoryObservation? = null

    private fun historyCreationSemantics(snapshot: PlaybackSnapshot): PlaybackSnapshot = snapshot.copy(
        elapsed = 0.0, duration = 0.0, playing = false, waiting = false, loading = false, error = "", occurrence = null)
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
    // Local presentation only: never part of the musical snapshot, history or semantic binding.
    private data class TransportNotice(val message: String, val isCurrent: () -> Boolean)
    private var transportNotice: TransportNotice? = null
    private fun currentTransportNotice(): String? {
        val notice = transportNotice ?: return null
        if (notice.isCurrent()) return notice.message
        if (transportNotice === notice) transportNotice = null
        return null
    }
    private var removed: Pair<PlaybackQueue.Removal, MediaItem>? = null
    private var removedTrack: PlaybackTrack? = null
    private var removedWasManual = false
    private val manualTier = PlaybackManualTier()
    private data class LocalMetadata(val origins: Map<Long, String>, val high: Set<Long>, val provenance: String)
    private class LocalQueueRevision {
        var revision = 1L
            private set
        private var last: Pair<PlaybackSnapshot, LocalMetadata>? = null
        fun observe(snapshot: PlaybackSnapshot, metadata: LocalMetadata) {
            val semantic = snapshot.copy(elapsed = 0.0, duration = 0.0, playing = false, waiting = false, loading = false,
                error = "", occurrence = snapshot.occurrence?.copy(progress = 0.0, reported = false)) to metadata
            if (last != null && last != semantic) { check(revision < 9007199254740991L); revision++ }
            last = semantic
        }
    }
    private val intentRevision = LocalQueueRevision()
    private var removedOrigin = "legacy"
    private fun intentMetadata(snapshot: PlaybackSnapshot): LocalMetadata {
        val ids = wireKeys.entries.associate { it.value to it.key }
        val order = snapshot.queue.map { ids.getValue(it.key) }
        manualTier.prune(order, snapshot.queue.getOrNull(snapshot.position)?.let { ids[it.key] })
        return LocalMetadata(snapshot.queue.associate { it.key to manualTier.origin(ids.getValue(it.key)) },
            snapshot.queue.filter { manualTier.contains(ids.getValue(it.key)) }.map { it.key }.toSet(), manualTier.provenance)
    }
    private fun frozenResult(status: String, snapshot: PlaybackSnapshot = exportPrepared()): PlaybackEndpointResult =
        PlaybackEndpointResult(status, snapshot)
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
        wireTracks.keys.retainAll(activeOccurrences)
        progressRemainders.keys.retainAll(activeOccurrences)
        manualTier.prune(traversalIds(), currentId())
        allowedAudioUrls.retainAll(activeUrls + pendingAudioUrls)
        val undoUrl = removed?.second?.localConfiguration?.uri?.toString()
        localAuthority?.sources?.keys?.retainAll(activeUrls + listOfNotNull(undoUrl))
        scheduleCachePlan()
    }

    private fun schedulePrune() {
        handler.removeCallbacks(pruneQueueState)
        // Finish the old item's discontinuity/history callbacks before discarding its gate.
        handler.post(pruneQueueState)
    }

    private val historyTick = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (foregroundTransitionDeadline != 0L && SystemClock.elapsedRealtime() >= foregroundTransitionDeadline)
                suspendBufferedOutput()
            // Parent auth callbacks may call stopService while a UI remains bound. Also
            // observe the shared session so already-buffered audio cannot outlive logout.
            if (!localNamespaceCurrent() || (!localOnlyQueue && (client !== (application as SpofifyApplication).graph.client ||
                (!dormantLocalMedia && current.mediaItemCount > 0 && client?.currentSession() == null)))) {
                shutdownPlayback()
                return
            }
            if (!hasOutputPermission()) {
                if (current.playWhenReady || current.isPlaying) suspendBufferedOutput()
                else if (parkedCacheLineage?.isValid() != true) cancelSongPrefetch()
            }
            sampleHistory(current.currentMediaItem, current.currentPosition, current.isPlaying)
            if (prefetchAllowed && (hasAuthority() || parkedCacheLineage?.isValid() == true)) scheduleCachePlan()
            // Refresh the read-only selected-output projection on the existing main tick.
            // No second player/poller, output start or local history credit is introduced.
            (session?.player as? CoordinatedSessionPlayer)?.refreshSessionState()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val graph = (application as SpofifyApplication).graph
        val sharedClient = graph.client
        localOnlyQueue = graph.offlineMode.value
        localQueueEligibility = if (localOnlyQueue) graph.localEligibility else null
        client = sharedClient
        authenticatedSession = sharedClient?.currentSession()
        if (authenticatedSession == null && !(localOnlyQueue && graph.localEligibility?.let(graph::localPlaybackIsCurrent) == true)) {
            stopSelf(); return
        }
        active = WeakReference(this)
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                if (!localOnlyQueue && libraryPending) {
                    libraryFailureGeneration = libraryGeneration
                    libraryFailure = "blocked"
                }
                outputError = "blocked"
                suspendBufferedOutput()
            }
        })
        // Shared exact-origin authenticated upstream for all audio/ranges. Private audio
        // retention is separate from HTTP caching; authority checks surround even cache hits.
        audioCache = if (localOnlyQueue || sharedClient == null) null else authenticatedSession?.let { graph.audioCache(sharedClient, it) }
        if (sharedClient != null) bindOnlineAudio(sharedClient, audioCache)
        songPrefetch = SongPrefetchScheduler(historyScope) { request, cancellation ->
            val storeCancellation = SongCache.Cancellation()
            cancellation.onCancel { storeCancellation.cancel() }
            cancellation.ensureActive()
            val authorization = request.authorization
            authorization.cache.prefetch(request.trackId, request.spec, authorization.upstream,
                { spec -> authorizeSpeculativeAudio(request, spec) }, storeCancellation)
        }
        sharedClient?.let { bindSavedPlaybackDemand(it) }
        val dataSources = DataSource.Factory {
            val online = onlineSources
            if (localOnlyQueue || graph.offlineMode.value || online == null) {
                val permit = localAuthority
                VerifiedLocalPlaybackSource(
                    isCurrent = { permit != null && localAuthority === permit && hasLocalAuthority() },
                    referenceFor = { spec -> permit?.sources?.get(spec.uri.toString())?.takeIf {
                        spec.key == spec.uri.toString() && spec.uri.toString() in allowedAudioUrls
                    } },
                    opener = { reference -> permit?.let { graph.openOfflineMedia(it.eligibility, reference) } },
                )
            } else online.createDataSource()
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
        // A bounded start can time out before Media3 exhausts loader retries. Keep the real
        // transport cause diagnostic even when no terminal onPlayerError reaches a controller.
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                val failure = PlaybackException(null, error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                android.util.Log.w("SpofifyPlayback", "load canceled=$wasCanceled ${playbackFailureDiagnostic(failure)}")
            }
        })
        audioFocus = PlaybackAudioFocus(getSystemService(AudioManager::class.java), ::interruptForSystemPause)
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
        exo.addListener(object : Player.Listener {
            private var directions = false to false
            private fun observeDirections() {
                val next = skipCapabilities()
                if (directions != next) { directions = next; transportCapabilityRevision++ }
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { observeDirections() }
            override fun onRepeatModeChanged(repeatMode: Int) { observeDirections(); if (!importing && !arranging) exportPrepared() }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // SOURCE_UPDATE also reports duration/seekability/period discovery. Those
                // are live facts, but a source may genuinely replace items/order too.
                // Classify on the callback boundary, never by exporting at poll time.
                val structure = syncTimelineStructure(timeline)
                if (structure != syncObservedTimeline) {
                    syncObservedTimeline = structure
                    schedulePrune()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (arranging || importing || !shuffleModeEnabled) return
                // Shuffle only the future; played/current retain their positions.
                val ids = physicalIds()
                val at = exo.currentMediaItemIndex.coerceAtLeast(0)
                val (manual, contextual) = manualTier.partitionFuture(ids, currentId())
                setTraversal(ids.take(at + 1) + manual + contextual.shuffled())
                removed = null
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Record transitions even between UI samples (including A -> B -> A).
                interactionKeys.invalidate()
                mediaButtonBoundaryUptime = SystemClock.uptimeMillis()
                if (importing || !localOnlyQueue) return
                val occurrence = PlaybackMedia.occurrence(mediaItem)
                if (occurrence != promotionOccurrence || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    audioCache?.protectPlayback(null)
                    promotionOccurrence = occurrence
                    promotionPending = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) Any() else null
                }
                // Explicit seeks/starts promote only after their own guarded successful result.
                promoteSuccessfulPlayback()
                // Retire consumed/skipped manual occurrences at every transition, including
                // A -> manual -> A before a deferred prune or another planner can sample it.
                manualTier.prune(traversalIds(), PlaybackMedia.occurrence(mediaItem))
                if (!hasOutputPermission() && exo.playWhenReady) suspendBufferedOutput()
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
                exportPrepared() // Synchronous traversal/history ABA fencing, not a sampled hash.
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (importing) return
                if (isPlaying && !hasOutputPermission()) { suspendBufferedOutput(); return }
                if (isPlaying) promoteSuccessfulPlayback()
                scheduleCachePlan()
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
                if (oldPosition.mediaItem?.let(PlaybackMedia::occurrence) != newPosition.mediaItem?.let(PlaybackMedia::occurrence))
                    interactionKeys.invalidate()
                if (importing) return
                importedElapsed = null; importedPositionMs = null
                // oldPosition is before a seek, never the artificial forward jump.
                sampleHistory(oldPosition.mediaItem, oldPosition.positionMs, historyWasPlaying)
                resetHistoryAnchor(oldPosition.mediaItem)
                resetHistoryAnchor(newPosition.mediaItem)
                sampleHistory(newPosition.mediaItem, newPosition.positionMs, exo.isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePrefetchPriority()
                if (localOnlyQueue && !importing && playbackState == Player.STATE_ENDED) settleTerminalOutput()
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                updatePrefetchPriority()
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("SpofifyPlayback", playbackFailureDiagnostic(error))
                // Online errors are actual facts, not permission to stop/reconnect the coordinator.
                if (localOnlyQueue) settleTerminalOutput()
            }
        })
        // Intercept semantic restart actions AT execution, not during command authorization:
        // controller batches may seek before Previous while the authorization callbacks still
        // observe the old position. Ordinary seekTo/scrubbing never resets a traversal.
        val sessionPlayer = CoordinatedSessionPlayer(exo, ::routeSessionCommand, ::sessionTransportSnapshot) { index ->
            // The local physical timeline is not an index-addressable remote queue. The
            // read-only remote projection owns its own exact wire-key mapping.
            if (remoteSessionSnapshot() != null) null
            else if (index in 0 until exo.mediaItemCount) PlaybackMedia.occurrence(exo.getMediaItemAt(index))?.let { wireKeys[it] } else null
        }.apply {
            setRemoteSnapshotProvider(::remoteSessionSnapshot)
            setModeDispatcher(::routeSessionMode)
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

    private fun notificationBindingId(): Long {
        val owner = (application as SpofifyApplication).graph.playbackCoordinator
        val devices = owner?.state?.value
        val remote = !localOnlyQueue && devices?.snapshot != null
        // Local identity follows the actual output, not the later server echo of its result.
        // Otherwise a correctly posted local token can expire as that echo is accepted.
        val binding = NotificationBinding(owner, if (remote) owner?.captureBinding() else null,
            if (remote) null else currentInteractionKey())
        if (notificationBinding != binding) {
            notificationBinding = binding
            notificationBindingSerial++
        }
        return notificationBindingSerial
    }

    private fun notificationIntent(type: String): PendingIntent {
        // Auth expiry can invalidate off-main: use one captured capability in identity AND extras.
        val epoch = PlaybackRequests.epoch
        val queueEpoch = PlaybackRequests.queueEpoch
        val binding = notificationBindingId()
        return PendingIntent.getService(this, type.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(ACTION_TRANSPORT)
                // PendingIntent equality ignores extras. Scope its IDENTITY too, otherwise
                // UPDATE_CURRENT silently upgrades a retained old Resume token to a new epoch.
                .setData(Uri.Builder().scheme("spofify-playback").authority(serviceNonce)
                    .appendPath(epoch.toString()).appendPath(queueEpoch.toString()).appendPath(binding.toString()).appendPath(type).build())
                .putExtra("command", type).putExtra("service", serviceNonce).putExtra("generation", epoch)
                .putExtra("queueGeneration", queueEpoch).putExtra("binding", binding),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRANSPORT) {
            if (intent.getStringExtra("service") == serviceNonce && intent.getLongExtra("generation", -1) == PlaybackRequests.epoch &&
                intent.getLongExtra("queueGeneration", -1) == PlaybackRequests.queueEpoch &&
                intent.getLongExtra("binding", -1) == notificationBindingId()) {
                intent.getStringExtra("command")?.takeIf { it in setOf("resume", "pause", "next", "previous") }
                    ?.let { routeCommand(LocalPlaybackCommand.transport(it), userInteraction = true) }
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
            if (admission.requestEpoch == PlaybackRequests.epoch && active.get() === this &&
                (hasLocalAuthority() || (client != null && client === (application as SpofifyApplication).graph.client &&
                    authenticatedSession != null && client?.currentSession() === authenticatedSession))) {
                routeCommand(LocalPlaybackCommand.transport("resume"), userInteraction = true)
            }
            // Media3 catches ordinary notification-future failure and resolves Play admission
            // false. Do not use its FGS-denied exception (that would revoke our routed action).
            return Futures.immediateFailedFuture(PlayContinuationSuppressed())
        }
        val current = player
        val remote = remoteSessionSnapshot()
        val track = if (remote != null) remotePlaybackState(remote).item else current?.currentMediaItem?.let(PlaybackMedia::libraryItem)
        // Buffering retains Pause/ongoing only for a valid, error-free remote playback intent.
        val playing = if (remote != null) track != null && remote.error.isEmpty() && remote.playing
            else hasOutputPermission() && current?.playWhenReady == true
        // Remote display/control state may update metadata, never promote local output/FGS.
        foreground?.update(mediaSession, track, playing, currentTransportNotice())
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
        libraryGeneration++
        preparingRead = null
        libraryListener?.let { player?.removeListener(it) }
        libraryListener = null
        val retiredDemand = savedDemandBinding
        savedDemandBinding = null
        retiredDemand?.handle?.close() // Idempotent token release cannot clear a successor service.
        if (active.get() === this) {
            onServiceActiveChanged?.invoke(false)
            active.clear()
        }
        cancelSongPrefetch()
        songPrefetch?.close(); songPrefetch = null
        audioCache = null
        onlineSources = null
        onlineUpstream = null
        promotionPending = null; promotionOccurrence = null
        handler.removeCallbacksAndMessages(null)
        admissions.clear()
        mediaButtonDowns.clear()
        startAttempt = null
        startAttemptValidity = null
        startHistoryObservation = null
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
        libraryOwner = null
        libraryStartAllowance = null
        foregroundTransitionDeadline = 0L
        invalidateLibraryMedia()
        dormantLocalMedia = false
        localAuthority = null
        localOnlyQueue = false
        localQueueEligibility = null
        authenticatedSession = null
        history.clear()
        manualTier.reset()
        historyNonces.clear()
        wireTracks.clear()
        wireContext = null
        wireKeys.clear()
        mediaProjectionSource = null; mediaProjection = null
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

    // Online listening history belongs exclusively to the server. Offline playback grants no history credit.
    private fun sampleHistory(media: MediaItem?, position: Long, playing: Boolean) = Unit

    private fun settleTerminalOutput() {
        cancelSongPrefetch()
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
        if (localOnlyQueue) { pauseLocalOutput(); return }
        suspendBufferedOutput()
        val owner = (application as SpofifyApplication).graph.playbackCoordinator ?: return
        val state = owner.state.value
        if (state.connectionId.isNotEmpty() && state.snapshot?.selectedConnectionId == state.connectionId)
            owner.submit(LibraryPlaybackCommand.Pause)
    }

    private fun suspendBufferedOutput(retainForeground: Boolean = false) {
        // This retains only an already-realized Android FGS while old audio/work drains.
        // It is not release proof, a lease extension or permission for a background start.
        val retaining = retainForeground && foreground?.foregroundConfirmed == true &&
            (foregroundTransitionDeadline == 0L || SystemClock.elapsedRealtime() < foregroundTransitionDeadline)
        if (!retaining) foregroundTransitionDeadline = 0L
        else if (foregroundTransitionDeadline == 0L) {
            val due = SystemClock.elapsedRealtime() + FOREGROUND_REPLACEMENT_WINDOW_MS
            foregroundTransitionDeadline = due
            handler.postDelayed({
                if (foregroundTransitionDeadline == due) suspendBufferedOutput()
            }, FOREGROUND_REPLACEMENT_WINDOW_MS)
        }
        libraryGeneration++
        preparingRead = null
        libraryPending = false
        libraryListener?.let { player?.removeListener(it) }
        libraryListener = null
        cancelSongPrefetch()
        promotionPending = null
        startAttempt = null
        startAttemptValidity = null
        startHistoryObservation = null
        interactionKeys.invalidate()
        authority = null
        admissions.clear()
        lastSuspendedUptime = SystemClock.uptimeMillis()
        historyScope.coroutineContext.cancelChildren()
        audioFocus?.abandon()
        val current = player ?: return
        current.pause()
        current.stop() // Discard buffered output without clearing prepared items/history/position.
        historyWasPlaying = false
        resetHistoryAnchor(current.currentMediaItem)
        if (!retaining) foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem))
    }

    /** Pure prepared identity read. Authority predicates must never recurse through an
     * export that samples history or evaluates that same authority again. */
    private fun preparedTracks(): List<PlaybackTrack> {
        val current = player ?: return emptyList()
        val physicalIndex = physicalIds().withIndex().associate { it.value to it.index }
        return traversalIds().map { id ->
            val index = physicalIndex.getValue(id)
            val item = requireNotNull(PlaybackMedia.libraryItem(current.getMediaItemAt(index)))
            val key = wireKeys.getOrPut(id) { ++wireSerial }
            wireTracks[id] ?: PlaybackSnapshotCodec.track(item, key, index.toLong())
        }
    }

    private fun preparedHistoryEventId(): String? = currentId()?.takeIf { it in history }?.let(historyNonces::get)

    /** Full ledger projection without output/history/authority sampling (safe inside guards). */
    private fun preparedSemanticSnapshot(): PlaybackSnapshot {
        val current = player ?: return PlaybackSnapshot()
        val queue = preparedTracks()
        val position = queue.indexOfFirst { it.key == wireKeys[currentId()] }
        val occurrence = preparedHistoryEventId()?.let { event ->
            queue.getOrNull(position)?.let { PlaybackOccurrence(it.key, it.id, event, 0.0, false) }
        }
        return PlaybackSnapshot(queue = queue, position = position,
            context = wireContext ?: contextIdentity?.let { PlaybackContext(it.kind, it.id, it.name) },
            shuffle = importedShuffle ?: current.shuffleModeEnabled,
            repeat = when (current.repeatMode) { Player.REPEAT_MODE_ALL -> "all"; Player.REPEAT_MODE_ONE -> "one"; else -> "off" },
            occurrence = occurrence, truncated = queueTruncated, notice = queueNoticeCode)
    }

    private fun observePreparedSemantics(snapshot: PlaybackSnapshot) {
        val beforeRevision = intentRevision.revision
        val metadata = intentMetadata(snapshot)
        intentRevision.observe(snapshot, metadata)
        if (intentRevision.revision != beforeRevision) {
            val own = startHistoryObservation
            val ownAudibleHistoryCreation = own != null && own.token === startAttempt && own.token === startAttemptValidity &&
                own.current == currentId() && player?.isPlaying == true && own.before.occurrence == null &&
                own.createdEventId != null && own.createdEventId == snapshot.occurrence?.eventId &&
                snapshot.occurrence?.key == wireKeys[currentId()] &&
                beforeRevision == own.revision && intentRevision.revision == own.revision + 1 &&
                own.metadata == metadata && historyCreationSemantics(own.before) == historyCreationSemantics(snapshot)
            // Retire permission to complete, NOT the obligation to drain this attempt's
            // output/focus/FGS. Only explicit successor installation transfers that owner.
            val preservedUpcomingEdit = upcomingStartPreservation?.invoke() == true
            if (!ownAudibleHistoryCreation && !preservedUpcomingEdit) startAttemptValidity = null
            if (preservedUpcomingEdit && !ownAudibleHistoryCreation) {
                // Only the attempt's own first-audible-history baseline follows this edit.
                // Captured action/read/eligibility guards and their ABA identities never do.
                own?.let { it.before = snapshot; it.metadata = metadata; it.revision = intentRevision.revision }
            } else startHistoryObservation = null
        }
    }

    /** A synchronous, sanctioned future-only edit can retain an ALREADY valid pending
     * start. Install this witness BEFORE touching Media3: listeners may export inline.
     * Never restore a cleared validity token or replace the start's original predicate. */
    private fun <T> preservingUpcomingStart(edit: () -> T): T {
        if (upcomingStartPreservation != null) return edit()
        val attempt = pendingStart ?: return edit()
        val originalGuard = pendingStartIsCurrent ?: return edit()
        exportPrepared() // Observe any pre-existing semantic invalidation, never forgive it.
        if (startAttempt !== attempt || startAttemptValidity !== attempt || !originalGuard()) return edit()
        val current = player ?: return edit()
        val media = current.currentMediaItem ?: return edit()
        val before = preparedSemanticSnapshot()
        val permit = authority
        val localPermit = localAuthority
        val request = PlaybackRequests.epoch
        val command = localCommandSerial
        val local = localOnlyQueue
        val wantsPlay = current.playWhenReady
        val error = outputError
        val mediaError = current.playerError
        val witness = {
            val now = preparedSemanticSnapshot()
            active.get() === this && player === current && pendingStart === attempt &&
                startAttempt === attempt && startAttemptValidity === attempt && originalGuard() && hasOutputPermission() &&
                authority === permit && localAuthority === localPermit &&
                PlaybackRequests.epoch == request && localCommandSerial == command && localOnlyQueue == local &&
                current.currentMediaItem == media && current.playWhenReady == wantsPlay &&
                outputError == error && current.playerError === mediaError &&
                now.queue.getOrNull(now.position)?.copy(order = 0) == before.queue.getOrNull(before.position)?.copy(order = 0) &&
                now.context == before.context && now.shuffle == before.shuffle && now.repeat == before.repeat &&
                now.occurrence == before.occurrence && now.truncated == before.truncated && now.notice == before.notice
        }
        upcomingStartPreservation = witness
        try { return edit() }
        finally {
            // Publish while the witness is still active, including unsampled final changes.
            try { observePreparedSemantics(preparedSemanticSnapshot()) }
            finally { upcomingStartPreservation = null }
        }
    }

    private data class SyncTimelineStructure(val items: List<MediaItem>, val first: Int, val next: List<Int>)
    private var syncObservedTimeline: SyncTimelineStructure? = null
    private fun syncTimelineStructure(timeline: Timeline): SyncTimelineStructure {
        val window = Timeline.Window()
        // Window/period UIDs may rotate during source preparation without changing music.
        // Physical items (including occurrence/metadata) and shuffle traversal are the
        // structural identities. Duration, dynamic/seekable flags and periods are not.
        val items = (0 until timeline.windowCount).map { timeline.getWindow(it, window).mediaItem }
        val next = (0 until timeline.windowCount).map { timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true) }
        return SyncTimelineStructure(items, timeline.getFirstWindowIndex(true), next)
    }
    private fun exportPrepared(): PlaybackSnapshot {
        val current = player ?: return PlaybackSnapshot()
        sampleHistory(current.currentMediaItem, current.currentPosition, current.isPlaying)
        val queue = preparedTracks()
        val currentId = currentId()
        val position = queue.indexOfFirst { it.key == wireKeys[currentId] }
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
            playing = hasOutputPermission() && current.playWhenReady && current.playbackState != Player.STATE_ENDED && current.playerError == null && outputError.isEmpty(),
            waiting = hasOutputPermission() && current.playbackState == Player.STATE_BUFFERING,
            // A newer owned start failure must match its terminal ACK even if ExoPlayer
            // retains the old media error until a permitted prepare can clear it.
            error = outputError.ifEmpty { if (current.playerError != null) "media" else "" }, occurrence = occurrence,
            truncated = queueTruncated,
            notice = queueNoticeCode,
        ).validated().also { observePreparedSemantics(it); scheduleCachePlan() }
    }

    private fun hasOutputPermission(): Boolean = hasLocalAuthority() || hasAuthority()
    private fun hasAuthority(): Boolean = !localOnlyQueue && !(application as SpofifyApplication).graph.offlineMode.value &&
        authority?.isValid() == true && active.get() === this &&
        client === (application as SpofifyApplication).graph.client &&
        authenticatedSession?.let { client?.hasValidSession(it) } == true

    private fun remoteSessionSnapshot(): PlaybackSnapshot? {
        if (localOnlyQueue || (application as SpofifyApplication).graph.offlineMode.value) return null
        return (application as SpofifyApplication).graph.playbackCoordinator?.state?.value?.snapshot?.let(::presentationSnapshot)
            ?: PlaybackSnapshot() // online/disconnected never exposes a dormant local queue
    }
    private fun sessionTransportSnapshot(): PlaybackSnapshot =
        if (!localNamespaceCurrent()) PlaybackSnapshot() else if (localOnlyQueue) exportPrepared()
        else remoteSessionSnapshot() ?: PlaybackSnapshot()

    private fun routeCommand(command: LocalPlaybackCommand, userInteraction: Boolean = false, userActionUptimeMs: Long? = null) {
        if (dispatchLocalTransport(command)) return
        val owner = (application as SpofifyApplication).graph.playbackCoordinator ?: return
        val state = owner.state.value.snapshot?.state ?: return
        val value: LibraryPlaybackCommand = when (command.type) {
            "resume" -> LibraryPlaybackCommand.Resume
            "pause" -> LibraryPlaybackCommand.Pause
            "retry" -> LibraryPlaybackCommand.Retry
            "close" -> LibraryPlaybackCommand.Close
            "clear" -> LibraryPlaybackCommand.Clear
            "next" -> LibraryPlaybackCommand.Next
            "previous" -> LibraryPlaybackCommand.Previous
            "seek" -> LibraryPlaybackCommand.Seek(command.json.getValue("value").jsonPrimitive.double)
            "jump" -> LibraryPlaybackCommand.Jump(command.json.getValue("key").jsonPrimitive.long)
            "shuffle" -> LibraryPlaybackCommand.SetShuffle(!state.shuffle)
            "repeat" -> LibraryPlaybackCommand.SetRepeat(when (state.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" })
            else -> return
        }
        val action = if (userInteraction) captureLibraryUserAction(owner, userActionUptimeMs) else null
        owner.submit(value, userAction = action)
    }

    private fun captureLibraryUserAction(owner: PlaybackCoordinator, eventUptimeMs: Long?): LibraryPlaybackUserAction? {
        val graph = (application as SpofifyApplication).graph
        val source = graph.client ?: return null
        val login = source.currentSession() ?: return null
        if (graph.offlineMode.value || !source.hasValidSession(login) || owner.captureBinding() == null) return null
        val age = eventUptimeMs?.let { SystemClock.uptimeMillis() - it } ?: 0L
        if (age !in 0..USER_ACTION_START_WINDOW_MS) return null
        val captured = SystemClock.elapsedRealtime() - age // A delayed headset event cannot renew freshness.
        val connection = owner.state.value.connectionId
        return LibraryPlaybackUserAction { instruction, acceptedCurrent ->
            libraryStartAllowance = instruction.id to {
                val graph = (application as SpofifyApplication).graph
                SystemClock.elapsedRealtime() - captured in 0..USER_ACTION_START_WINDOW_MS &&
                    active.get() === this && graph.client === source && client === source &&
                    authenticatedSession === login && source.currentSession() === login && graph.playbackCoordinator === owner && owner.state.value.connectionId == connection &&
                    librarySessionCurrent() && acceptedCurrent()
            }
        }
    }

    private fun libraryItem(track: LibraryPlaybackTrack): LibraryItem = LibraryItem(
        kind = "track", id = track.id, name = track.name,
        artists = track.artists.map { ArtistCredit(it.id.orEmpty(), it.name) },
        albumName = track.albumName, albumId = track.albumId,
        durationMs = (track.duration * 1000).toLong(), hasAudio = true, entryId = track.entryId,
    )

    private var mediaProjectionSource: LibraryPlaybackSnapshot? = null
    private var mediaProjection: PlaybackSnapshot? = null

    /** Read-only projection; quiet facts reuse the canonical queue without copying it. */
    private fun presentationSnapshot(value: LibraryPlaybackSnapshot): PlaybackSnapshot {
        if (mediaProjectionSource === value) return requireNotNull(mediaProjection)
        val prior = mediaProjection
        val facts = value.playback.takeIf { it.hasReportedPlayback && it.stateVersion == value.stateVersion &&
            it.outputVersion == value.outputVersion && it.eventId == value.state.occurrence?.eventId.orEmpty() }
        val projected = if (prior != null && mediaProjectionSource?.state === value.state) prior.copy(
            elapsed = facts?.elapsed ?: value.state.elapsed, duration = facts?.duration ?: value.state.duration,
            playing = facts?.isPlaying == true, waiting = facts?.isBuffering == true, loading = facts?.isLoading == true,
            error = facts?.error ?: value.state.error,
        ) else value.displayProjection()
        mediaProjectionSource = value; mediaProjection = projected
        return projected
    }

    private fun librarySessionCurrent(): Boolean {
        val graph = (application as SpofifyApplication).graph
        return active.get() === this && !graph.offlineMode.value && client === graph.client &&
            authenticatedSession?.let { client?.hasValidSession(it) } == true
    }

    private fun bindLibraryListener(instruction: LibraryPlaybackInstruction, token: Long, observation: (String, Boolean) -> Unit) {
        libraryListener?.let { player?.removeListener(it) }
        libraryListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if ((0 until events.size()).none { events[it] in setOf(Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_IS_LOADING_CHANGED, Player.EVENT_PLAYER_ERROR,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED) }) return
                if (libraryGeneration != token || localOnlyQueue || !librarySessionCurrent()) return
                val ended = events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED
                if (player.isPlaying && !hasAuthority()) { suspendBufferedOutput(); return }
                if (player.playerError != null) {
                    // The pending operation owns its bounded media/blocked outcome and
                    // cancellation proof; do not turn that failure into a stale rejection.
                    if (libraryPending) return
                    suspendBufferedOutput()
                    outputError = "media"
                }
                observation(instruction.id, ended)
            }
        }.also { player?.addListener(it) }
    }

    private fun invalidateLibraryMedia() {
        libraryPreparedTrack = null; libraryPreparedEvent = null; libraryPreparedMediaIdentity = null
        libraryPreparedClient = null; libraryPreparedSession = null
    }

    private fun libraryMediaMatches(instruction: LibraryPlaybackInstruction): Boolean {
        val current = player ?: return false
        val track = instruction.track ?: return false
        val media = current.currentMediaItem ?: return false
        val source = client ?: return false
        // Media3 may legitimately copy metadata/MediaItem. The private install tag survives
        // such copies, unlike an object-address comparison, but saved/foreign media has none.
        return current.mediaItemCount == 1 && libraryPreparedMediaIdentity != null &&
            media.localConfiguration?.tag === libraryPreparedMediaIdentity &&
            libraryPreparedClient === source && libraryPreparedSession === authenticatedSession &&
            libraryPreparedTrack == track && libraryPreparedEvent == instruction.eventId &&
            media.mediaId == track.id && PlaybackMedia.occurrence(media) == "library-${track.key}" &&
            media.localConfiguration?.uri?.toString() == source.audioUrl(libraryItem(track))
    }

    private fun installLibraryMedia(instruction: LibraryPlaybackInstruction) {
        val current = requireNotNull(player)
        val source = requireNotNull(client)
        val track = instruction.track
        importing = true
        try {
            current.pause()
            current.shuffleModeEnabled = false
            current.repeatMode = Player.REPEAT_MODE_OFF // the server alone decides repeat/next
            if (track == null) {
                invalidateLibraryMedia()
                current.clearMediaItems(); allowedAudioUrls.clear()
            } else if (!libraryMediaMatches(instruction)) {
                val identity = Any()
                val media = PlaybackMedia.create(libraryItem(track), source, "library-${track.key}")
                    .buildUpon().setTag(identity).build()
                allowedAudioUrls.clear()
                allowedAudioUrls.add(requireNotNull(media.localConfiguration).uri.toString())
                invalidateLibraryMedia()
                current.setMediaItem(media, (instruction.elapsed * 1000).toLong())
                libraryPreparedMediaIdentity = identity
            } else current.seekTo((instruction.elapsed * 1000).toLong())
            dormantLocalMedia = false
            libraryPreparedTrack = track; libraryPreparedEvent = instruction.eventId
            libraryPreparedClient = source; libraryPreparedSession = authenticatedSession
        } finally { importing = false }
    }

    private suspend fun prepareLibraryOutput(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean, owner: Any,
        observation: (String, Boolean) -> Unit): String {
        val graph = (application as SpofifyApplication).graph
        val source = graph.client ?: return "rejected"
        val login = source.currentSession() ?: return "rejected"
        if (!isCurrent() || active.get() !== this || graph.offlineMode.value || !source.hasValidSession(login)) return "rejected"
        // A targeted server preparation is the only online/offline boundary here. It never
        // publishes the retired local queue or lends its authority to online execution.
        suspendBufferedOutput()
        localAuthority = null; localQueueEligibility = null; localOnlyQueue = false
        libraryOwner = owner
        client = source; authenticatedSession = login
        bindOnlineAudio(source, graph.audioCache(source, login))
        bindSavedPlaybackDemand(source)
        val token = libraryGeneration
        fun valid() = token == libraryGeneration && librarySessionCurrent() && isCurrent()
        preparingRead = { valid() }
        libraryPending = true
        bindLibraryListener(instruction, token, observation)
        var result = "rejected"
        try {
            outputError = ""
            installLibraryMedia(instruction)
            if (instruction.track == null) { result = "ok"; return result }
            val current = player ?: return "blocked"
            current.playWhenReady = false
            current.prepare() // silent: no focus, foreground promotion or play()
            val ready = withTimeoutOrNull(3_000) {
                while (valid() && current.playerError == null && current.playbackState != Player.STATE_READY) delay(20)
                valid() && current.playerError == null && current.playbackState == Player.STATE_READY
            } == true
            result = if (!valid()) "rejected" else if (ready) "ok" else "media"
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { result = "media"; return result }
        finally {
            if (token == libraryGeneration) {
                preparingRead = null
                libraryPending = false
                // Stop cancels native loading while preserving the prepared media/position.
                player?.pause(); player?.stop()
                if (result != "ok") { outputError = if (result == "media") "media" else ""; suspendBufferedOutput() }
            }
        }
    }

    private fun releaseLibraryOutput(): Boolean {
        if (localOnlyQueue) return true // deliberate offline output is not a socket executor
        suspendBufferedOutput()
        val current = player ?: return true
        return !current.isPlaying && !current.playWhenReady && pendingStart == null && preparingRead == null
    }

    private suspend fun executeLibraryOutput(instruction: LibraryPlaybackInstruction, permit: LibraryPlaybackAuthority,
        observation: (String, Boolean) -> Unit): String {
        val userAction = libraryStartAllowance?.takeIf { it.first == instruction.id }?.second
        libraryStartAllowance = null // One matching execution attempt, never admitted-next/reconnect.
        if (!permit.isValid() || !librarySessionCurrent() || localOnlyQueue) return "rejected"
        val existing = player
        val continuing = instruction.type == "execute" && instruction.desiredPlaying && !libraryPending &&
            existing != null && existing.mediaItemCount == 1 && existing.playWhenReady && existing.playerError == null &&
            libraryMediaMatches(instruction) && audioFocus?.isGranted == true && foreground?.foregroundConfirmed == true
        if (continuing && existing != null) {
            // Replace the old asynchronous/listener identity FIRST, but keep the realized
            // same-occurrence decoder, focus and foreground ownership through a seek/rebuffer.
            val token = ++libraryGeneration
            preparingRead = null
            libraryListener?.let(existing::removeListener)
            libraryListener = null
            cancelSongPrefetch()
            promotionPending = null
            authority = permit
            outputError = ""
            bindLibraryListener(instruction, token, observation)
            try {
                existing.seekTo((instruction.elapsed * 1000).toLong())
                if (libraryGeneration != token || authority !== permit || !permit.isValid() || !librarySessionCurrent()) {
                    if (libraryGeneration == token) suspendBufferedOutput()
                    return "rejected"
                }
                enableSongPrefetch()
                return "ok" // no prepare/play/focus request or FIFO promotion
            } catch (_: RuntimeException) {
                if (libraryGeneration == token) { suspendBufferedOutput(); outputError = "media" }
                return "media"
            }
        }
        suspendBufferedOutput(retainForeground = instruction.desiredPlaying)
        val token = libraryGeneration
        authority = permit
        libraryPending = true
        libraryFailureGeneration = -1L; libraryFailure = ""
        var requiresUserAction = false
        fun valid() = libraryGeneration == token && authority === permit && permit.isValid() && librarySessionCurrent() &&
            (!requiresUserAction || userAction?.invoke() == true)
        fun invalidStatus() = if (libraryFailureGeneration == token) libraryFailure else "rejected"
        bindLibraryListener(instruction, token, observation)
        var completed = false
        var status = "rejected"
        try {
            outputError = ""
            installLibraryMedia(instruction)
            val current = player ?: return "blocked"
            if (!instruction.desiredPlaying) {
                current.pause(); current.stop(); completed = true; status = "ok"; return status
            }
            if (instruction.track == null) return "rejected"
            requiresUserAction = !mayStartForegroundPlayback()
            if (requiresUserAction && userAction?.invoke() != true || session?.let {
                    foreground?.promote(it, current.currentMediaItem?.let(PlaybackMedia::libraryItem), true)
                } != true || audioFocus?.requestForExplicitStart() != true) { status = "blocked"; return status }
            if (!valid()) return invalidStatus()
            try { startService(Intent(this, PlaybackService::class.java).setAction(ACTION_KEEP_ALIVE)) }
            catch (_: RuntimeException) { status = "blocked"; return status }
            current.playWhenReady = false
            current.prepare()
            val ready = withTimeoutOrNull(3_000) {
                while (valid() && current.playerError == null && current.playbackState != Player.STATE_READY) delay(20)
                valid() && current.playerError == null && current.playbackState == Player.STATE_READY
            } == true
            if (!valid()) return invalidStatus()
            if (!ready) { status = "media"; return status }
            current.play()
            val started = withTimeoutOrNull(500) {
                while (valid() && !current.isPlaying && current.playerError == null) delay(10)
                valid() && current.isPlaying && current.playerError == null
            } == true
            if (!valid()) return invalidStatus()
            if (!started) { status = if (current.playerError == null) "blocked" else "media"; return status }
            completed = true; status = "ok"
            foregroundTransitionDeadline = 0L
            promotionPending = Any(); promoteSuccessfulPlayback(startSucceeded = true)
            enableSongPrefetch()
            return status
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { status = "media"; return status }
        finally {
            // Obsolete cleanup cannot stop a successor. Failure proof cancels all work
            // before the executor sends blocked/media/rejected or successful stopped ACK.
            if (libraryGeneration == token) {
                libraryPending = false
                if (!completed || !instruction.desiredPlaying) {
                    suspendBufferedOutput()
                    outputError = if (status in setOf("blocked", "media")) status else ""
                }
            }
        }
    }

    private fun observeLibraryOutput(): LibraryPlaybackObservation {
        if (localOnlyQueue) return LibraryPlaybackObservation()
        val current = player ?: return LibraryPlaybackObservation()
        return LibraryPlaybackObservation(
            elapsed = (current.currentPosition.coerceAtLeast(0) / 1000.0).coerceAtMost(604800.0),
            duration = if (current.duration == C.TIME_UNSET) 0.0 else (current.duration.coerceAtLeast(0) / 1000.0).coerceAtMost(604800.0),
            isPlaying = current.isPlaying && current.playerError == null && outputError.isEmpty(),
            isBuffering = current.playbackState == Player.STATE_BUFFERING,
            isLoading = current.isLoading,
            error = outputError.ifEmpty { if (current.playerError != null) "media" else "" },
        )
    }

    private fun localReferencesMatch(eligibility: OfflineEligibility, references: List<OfflineMediaReference>): Boolean {
        val graph = (application as SpofifyApplication).graph
        val owner = graph.offlineLibrary ?: return false
        return graph.localPlaybackIsCurrent(eligibility) && references.all {
            it.owner === owner && it.origin == eligibility.origin && it.accountId == eligibility.accountId
        }
    }

    private fun requestLocalReplacement(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, start: Int,
        shuffle: Boolean, context: LibraryItem?, isCurrent: () -> Boolean): String? {
        val graph = (application as SpofifyApplication).graph
        if (!isCurrent() || active.get() !== this || !graph.localPlaybackIsCurrent(eligibility))
            return "Saved playback access changed. Choose music again."
        val current = player ?: return "Playback is unavailable. Try again."
        val media: List<MediaItem>
        val tracks: List<PlaybackTrack>
        val references: List<OfflineMediaReference>
        try {
            require(items.isNotEmpty() && start in items.indices)
            references = items.map { requireNotNull(mediaReferences[requireNotNull(it.entryId)]) }
            require(wireSerial <= 9007199254740991L - items.size)
            val queueId = UUID.randomUUID().toString()
            media = items.map { PlaybackMedia.createLocal(it, queueId) }
            tracks = items.mapIndexed { index, item -> PlaybackSnapshotCodec.track(item, wireSerial + index + 1L, index.toLong()) }
            PlaybackSnapshot(queue = tracks, position = start, context = wireContext(context)).validated()
            // Structural admission only: NEVER wait for the owner's suspend factory on main.
            // Every selected row must carry its exact owner-scoped immutable reference. Actual
            // verified availability is checked during Media3 open, including deletion races.
            require(localReferencesMatch(eligibility, references))
        } catch (_: Exception) { return "Saved audio is unavailable or incomplete. Choose music again." }
        if (!isCurrent() || !localReferencesMatch(eligibility, references)) return "Saved playback access changed. Choose music again."
        localOnlyQueue = true
        localQueueEligibility = eligibility
        localAuthority = null
        suspendBufferedOutput()
        PlaybackRequests.queueChanged()
        val permit = LocalAuthority(eligibility, PlaybackRequests.epoch,
            media.mapIndexed { index, item -> requireNotNull(item.localConfiguration).uri.toString() to references[index] }.toMap())
        importing = true
        try {
            history.clear(); historyNonces.clear(); progressRemainders.clear(); wireKeys.clear(); wireTracks.clear()
            allowedAudioUrls.clear(); pendingAudioUrls.clear(); manualTier.reset()
            removed = null; removedTrack = null; removedWasManual = false
            wireContext = wireContext(context); contextIdentity = context?.copy(libraryPath = null)
            queueMessage = null; queueTruncated = false; queueNoticeCode = ""
            importedDuration = null; importedElapsed = null; importedPositionMs = null; importedShuffle = shuffle
            outputError = ""; historyWasPlaying = false
            media.forEachIndexed { index, item ->
                val id = requireNotNull(PlaybackMedia.occurrence(item))
                wireKeys[id] = tracks[index].key; wireTracks[id] = tracks[index]
                allowedAudioUrls.add(requireNotNull(item.localConfiguration).uri.toString())
            }
            wireSerial += tracks.size
            localAuthority = permit
            mediaButtonBoundaryUptime = SystemClock.uptimeMillis()
            current.shuffleModeEnabled = false
            current.repeatMode = Player.REPEAT_MODE_OFF
            invalidateLibraryMedia()
            dormantLocalMedia = false
            libraryStartAllowance = null
            current.setMediaItems(media, start, 0)
            if (shuffle) {
                current.shuffleModeEnabled = true
                val ids = physicalIds()
                setTraversal(listOf(ids[start]) + ids.filterIndexed { index, _ -> index != start }.shuffled())
            }
        } finally { importing = false }
        schedulePrune()
        val serial = ++localCommandSerial
        historyScope.launch {
            val valid = { localAuthority === permit && localCommandSerial == serial && hasLocalAuthority() && isCurrent() }
            if (!valid()) return@launch
            val result = startLocalOutput(valid)
            if (localAuthority === permit && localCommandSerial == serial && result.status != "ok" && result.status != "rejected")
                queueMessage = if (result.status == "blocked") "Playback needs a tap in the app." else "Saved audio could not be played."
        }
        return null
    }

    private fun requestLocalEnqueue(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, intent: QueueIntentIntent, isCurrent: () -> Boolean): String? =
        preservingUpcomingStart { enqueueLocalSelection(items, eligibility, mediaReferences, intent, isCurrent) }

    private fun enqueueLocalSelection(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, intent: QueueIntentIntent, isCurrent: () -> Boolean): String? {
        if (intent == QueueIntentIntent.NOW) return "Choose Play for a new saved selection."
        val permit = localAuthority
        val current = player ?: return "Choose saved music before adding to its queue."
        fun valid() = isCurrent() && permit != null && localAuthority === permit && permit.eligibility === eligibility && hasLocalAuthority()
        if (!valid() || current.currentMediaItem == null) return "Choose saved music before adding to its queue."
        val media: List<MediaItem>
        val tracks: List<PlaybackTrack>
        val references: List<OfflineMediaReference>
        try {
            require(items.isNotEmpty() && wireSerial <= 9007199254740991L - items.size)
            references = items.map { requireNotNull(mediaReferences[requireNotNull(it.entryId)]) }
            val queueId = UUID.randomUUID().toString()
            media = items.map { PlaybackMedia.createLocal(it, queueId) }
            val nextOrder = (wireTracks.values.maxOfOrNull { it.order } ?: -1L) + 1L
            tracks = items.mapIndexed { index, item -> PlaybackSnapshotCodec.track(item, wireSerial + index + 1L, nextOrder + index) }
            val before = exportPrepared()
            before.copy(queue = before.queue + tracks).validated()
            require(localReferencesMatch(eligibility, references))
        } catch (_: Exception) { return "Saved audio is unavailable or incomplete." }
        if (!valid() || !localReferencesMatch(eligibility, references)) return "Saved playback access changed. Choose music again."
        val previous = traversalIds()
        val insertion = when (intent) {
            QueueIntentIntent.LOW -> previous.size
            QueueIntentIntent.HIGH -> manualTier.insertionIndex(previous, currentId())
            QueueIntentIntent.NEXT -> previous.indexOf(currentId()) + 1
            QueueIntentIntent.NOW -> return "Choose Play for a new saved selection."
        }
        val ids = media.map { requireNotNull(PlaybackMedia.occurrence(it)) }
        media.forEachIndexed { index, item ->
            val id = ids[index]
            val uri = requireNotNull(item.localConfiguration).uri.toString()
            wireKeys[id] = tracks[index].key; wireTracks[id] = tracks[index]
            requireNotNull(permit).sources[uri] = references[index]
            allowedAudioUrls.add(uri)
        }
        wireSerial += tracks.size
        val order = previous.toMutableList().apply { addAll(insertion, ids) }
        manualTier.tag(ids, intent.wire, intent != QueueIntentIntent.LOW)
        PlaybackRequests.queueChanged()
        current.addMediaItems(media)
        preserveTraversal(order)
        removed = null
        schedulePrune()
        // No prepare/focus/start: the exact current occurrence, elapsed and pause/error survive.
        return null
    }

    /** Cancel deferred gestures globally while retaining ONLY this prepared local queue's
     * access. No pending start may borrow the renewed cancellation epoch after the pause. */
    private fun pauseLocalOutput() {
        val permit = localAuthority
        val retain = hasLocalAuthority()
        localCommandSerial++
        PlaybackRequests.invalidate()
        if (retain && localAuthority === permit) permit?.epoch = PlaybackRequests.epoch
        suspendBufferedOutput()
    }

    private fun editLocalOccurrence(expectedKey: PlaybackInteractionKey, id: String, action: PlaybackListAction,
        isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
        if (!isCurrent() || !hasLocalAuthority() || currentInteractionKey() !== expectedKey ||
            id !in PlaybackQueue.upcoming(traversalIds(), currentId())) {
            onResult(PlaybackActionResult(PlaybackActionStatus.STALE)); return
        }
        if (action == PlaybackListAction.PLAY_NOW) {
            val key = wireKeys[id] ?: run { onResult(PlaybackActionResult(PlaybackActionStatus.STALE)); return }
            dispatchLocalTransport(LocalPlaybackCommand.queue("jump", key), isCurrent, onResult)
            return
        }
        val before = traversalIds()
        preservingUpcomingStart { when (action) {
            PlaybackListAction.PLAY_NOW -> Unit // Handled above, outside future-only preservation.
            PlaybackListAction.PLAY_NEXT -> moveUpcoming(id, 0, playNext = true)
            PlaybackListAction.QUEUE_LOW, PlaybackListAction.ADD_TO_QUEUE -> {
                manualTier.tag(listOf(id), "low", false)
                preserveTraversal(before.filterNot { it == id } + id)
            }
            PlaybackListAction.CONTEXTUAL, PlaybackListAction.QUEUE_HIGH -> {
                val promoted = requireNotNull(manualTier.promoted(before, currentId(), id))
                manualTier.tag(listOf(id), "high", true)
                preserveTraversal(promoted)
            }
        } }
        onResult(PlaybackActionResult(if (traversalIds() == before) PlaybackActionStatus.NO_OP else PlaybackActionStatus.ADDED, 1))
    }

    private suspend fun startLocalOutput(isCurrent: () -> Boolean): PlaybackEndpointResult = try {
        startOutput(true, isCurrent, mayStart = { mayStartForegroundPlayback() })
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) {
        if (isCurrent()) { outputError = "media"; frozenResult("media") } else frozenResult("rejected")
    }

    /** Consumes every local/offline command, even when access has expired: never fall through
     * to an online coordinator. Only fresh Play may change queue provenance back to online. */
    private fun dispatchLocalTransport(command: LocalPlaybackCommand, isCurrent: () -> Boolean = { true },
        onResult: (PlaybackActionResult) -> Unit = {}): Boolean {
        if (!localOnlyQueue && !(application as SpofifyApplication).graph.offlineMode.value) return false
        if (!isCurrent()) return true
        if (command.type in setOf("pause", "close")) {
            pauseLocalOutput()
            if (command.type == "close") {
                localAuthority = null
                PlaybackRequests.queueChanged()
                invalidateLibraryMedia()
                player?.clearMediaItems()
                history.clear(); historyNonces.clear(); wireKeys.clear(); wireTracks.clear(); progressRemainders.clear()
                allowedAudioUrls.clear(); pendingAudioUrls.clear(); manualTier.reset()
                removed = null; removedTrack = null; queueMessage = null
            }
            return true
        }
        if (!hasLocalAuthority()) return true
        val permit = localAuthority
        val current = player ?: return true
        when (command.type) {
            "seek" -> { current.seekTo((command.json.getValue("value").jsonPrimitive.double * 1000).toLong()); return true }
            "shuffle" -> { toggleOwnedShuffle(); return true }
            "repeat" -> {
                current.repeatMode = when (current.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                return true
            }
            "clear" -> { clearUpcoming(); return true }
            "remove", "move", "jump" -> {
                val key = command.json.getValue("key").jsonPrimitive.long
                val id = wireKeys.entries.firstOrNull { it.value == key }?.key ?: return true
                if (id !in PlaybackQueue.upcoming(traversalIds(), currentId())) return true
                when (command.type) {
                    "remove" -> { removeUpcoming(id); return true }
                    "move" -> {
                        val at = PlaybackQueue.upcoming(traversalIds(), currentId()).indexOf(id)
                        val direction = command.json["direction"]?.jsonPrimitive?.content
                        moveUpcoming(id, when (direction) { "next" -> 0; "up" -> at - 1; "down" -> at + 1; else -> return true }, direction == "next")
                        return true
                    }
                    else -> current.seekToDefaultPosition(physicalIds().indexOf(id))
                }
            }
            "next" -> if (current.hasNextMediaItem()) current.seekToNextMediaItem() else return true
            "previous" -> current.seekToPrevious()
            "resume", "retry" -> {
                if (current.currentMediaItem == null) return true
                if (current.playbackState == Player.STATE_ENDED) current.seekToDefaultPosition()
            }
            else -> return true
        }
        val serial = ++localCommandSerial
        historyScope.launch {
            val valid = { isCurrent() && localAuthority === permit && hasLocalAuthority() && localCommandSerial == serial }
            var completed = false
            try {
                if (!valid()) return@launch
                val result = startLocalOutput(valid)
                completed = true
                onResult(PlaybackActionResult(when (result.status) {
                    "ok" -> PlaybackActionStatus.PLAYING
                    "blocked" -> PlaybackActionStatus.BLOCKED
                    "rejected" -> PlaybackActionStatus.STALE
                    else -> PlaybackActionStatus.UNAVAILABLE
                }, if (result.status == "ok") 1 else 0))
            } finally {
                if (!completed) onResult(PlaybackActionResult(PlaybackActionStatus.STALE))
            }
        }
        return true
    }

    private fun routeSessionMode(command: LibraryPlaybackCommand) {
        val controller = session?.controllerForCurrentRequest ?: return
        val admission = admissions[controller] ?: return
        val expected = when (command) {
            is LibraryPlaybackCommand.SetShuffle -> Player.COMMAND_SET_SHUFFLE_MODE
            is LibraryPlaybackCommand.SetRepeat -> Player.COMMAND_SET_REPEAT_MODE
            else -> return
        }
        if (admission.command != expected || admission.requestEpoch != PlaybackRequests.epoch) return
        admissions.remove(controller)
        if (localOnlyQueue) {
            localEdit {
                when (command) {
                    is LibraryPlaybackCommand.SetShuffle -> if (command.shuffle != (importedShuffle ?: player?.shuffleModeEnabled)) toggleOwnedShuffle()
                    is LibraryPlaybackCommand.SetRepeat -> player?.repeatMode = when (command.repeat) {
                        "off" -> Player.REPEAT_MODE_OFF
                        "all" -> Player.REPEAT_MODE_ALL
                        "one" -> Player.REPEAT_MODE_ONE
                        else -> return@localEdit
                    }
                    else -> Unit
                }
            }
        } else if (librarySessionCurrent()) {
            // Preserve the controller's absolute requested value. Never recalculate a toggle
            // after a server update; the coordinator also binds the current structural version.
            (application as SpofifyApplication).graph.playbackCoordinator?.submit(command)
        }
    }

    private fun routeSessionCommand(command: LocalPlaybackCommand) {
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

    private var importedShuffle: Boolean? = null

    private suspend fun startOutput(play: Boolean, isCurrent: () -> Boolean, mayStart: () -> Boolean): PlaybackEndpointResult {
        val current = player ?: return frozenResult("rejected", snapshot = PlaybackSnapshot())
        if (!isCurrent()) return frozenResult("rejected")
        transportNotice = null // A newly admitted start retires earlier refusal presentation.
        // Observe the accepted installation before allocating the attempt cleanup token, but
        // do NOT manufacture a traversal for generic foreground/focus/media-denied starts.
        // Raw queue selection commits establish their own history separately. For a generic
        // start with no history, only genuinely observed audible playback may establish it.
        val installed = exportPrepared()
        if (play) cancelSongPrefetch()
        val attempt = Any()
        pendingStart = attempt
        pendingStartIsCurrent = isCurrent
        startAttempt = attempt
        startAttemptValidity = attempt
        if (play) promotionPending = attempt
        val occurrence = currentId()
        startHistoryObservation = if (play && installed.occurrence == null)
            StartHistoryObservation(attempt, occurrence, installed, intentMetadata(installed), intentRevision.revision)
        else null
        fun sameAttempt() = active.get() === this && player === current && startAttempt === attempt
        fun owned() = sameAttempt() && startAttemptValidity === attempt && currentId() == occurrence && isCurrent()
        fun valid() = owned() && audioFocus?.isGranted == true
        fun silenceAttempt() {
            // A rejected predicate is not just a paused copy of a possibly still-playing
            // snapshot. Drain OUR output and focus/FGS ownership, but never touch a successor.
            fun clean(action: () -> Unit) {
                if (!sameAttempt()) return
                try { action() } catch (_: RuntimeException) {
                    // A broken player/platform cleanup stage must not strand focus or FGS.
                    // Recheck identity before every stage in case a callback replaced us.
                }
            }
            clean { current.pause() }
            clean { current.stop() }
            clean { audioFocus?.abandon() }
            clean {
                historyWasPlaying = false
                resetHistoryAnchor(current.currentMediaItem)
            }
            clean { foreground?.demote(session, current.currentMediaItem?.let(PlaybackMedia::libraryItem)) }
            if (sameAttempt()) {
                startAttempt = null
                if (startAttemptValidity === attempt) startAttemptValidity = null
            }
            if (startHistoryObservation?.token === attempt) startHistoryObservation = null
            if (promotionPending === attempt) promotionPending = null
        }
        fun rejected(): PlaybackEndpointResult {
            silenceAttempt()
            return frozenResult("rejected")
        }
        fun failure(status: String): PlaybackEndpointResult {
            if (!sameAttempt()) return rejected()
            outputError = status
            silenceAttempt()
            return frozenResult(status)
        }
        var completed = false
        try {
            if (!owned()) return rejected()
            outputError = ""
            if (!play) { completed = true; return frozenResult("ok", snapshot = exportPrepared().paused()) }
            if (!mayStart() || session?.let { foreground?.promote(it, current.currentMediaItem?.let(PlaybackMedia::libraryItem), true) } != true)
                return failure("blocked")
            if (audioFocus?.requestForExplicitStart() != true) return failure("blocked")
            if (!valid()) return rejected()
            // Keep the one service started while its foreground notification owns audio lifetime.
            try { startService(Intent(this, PlaybackService::class.java).setAction(ACTION_KEEP_ALIVE)) }
            catch (_: RuntimeException) { return failure("blocked") }
            current.playWhenReady = false
            current.prepare()
            val ready = withTimeoutOrNull(3_000) {
                while (valid() && current.playerError == null && current.playbackState != Player.STATE_READY) delay(20)
                valid() && current.playerError == null && current.playbackState == Player.STATE_READY
            } == true
            if (!owned()) return rejected()
            if (!ready) return failure("media")
            if (!valid()) return rejected()
            current.play()
            val started = withTimeoutOrNull(500) {
                while (valid() && !current.isPlaying && current.playerError == null && outputError.isEmpty()) delay(10)
                valid() && current.isPlaying && current.playerError == null && outputError.isEmpty()
            } == true
            if (!owned()) return rejected()
            if (!started) return failure(if (current.playerError == null) "blocked" else "media")
            completed = true
            promotionPending = attempt
            promoteSuccessfulPlayback(startSucceeded = true)
            enableSongPrefetch()
            return frozenResult("ok")
        } finally {
            // Covers caller disposal, cancellation and thrown platform errors as well as every
            // early rejected return. A newer import/start/service changes the attempt identity.
            if (pendingStart === attempt) { pendingStart = null; pendingStartIsCurrent = null }
            if (!completed) silenceAttempt()
        }
    }

    private fun mayStartForegroundPlayback(): Boolean {
        if (foreground?.foregroundConfirmed == true) return true
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
    private fun wireContext(item: LibraryItem?): PlaybackContext? = item?.let { PlaybackContext(it.kind, it.id, it.name) }

    private fun preserveTraversal(ids: List<String>) {
        val current = player ?: return
        val shuffle = importedShuffle ?: current.shuffleModeEnabled
        arranging = true
        try { importedShuffle = shuffle; current.shuffleModeEnabled = true; setTraversal(ids) }
        finally { arranging = false }
    }

    private fun localEdit(edit: PlaybackService.() -> Unit) {
        if (localOnlyQueue && hasLocalAuthority()) edit()
    }

    private fun toggleOwnedShuffle() {
        val current = player ?: return
        val order = traversalIds(); val at = order.indexOf(currentId())
        val enabled = !(importedShuffle ?: current.shuffleModeEnabled)
        val (manual, contextual) = manualTier.partitionFuture(order, currentId())
        val future = manual + if (enabled) contextual.shuffled()
            else contextual.sortedBy { wireTracks[it]?.order ?: physicalIds().indexOf(it).toLong() }
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
        mediaButtonBoundaryUptime = SystemClock.uptimeMillis()
        PlaybackRequests.queueChanged()
        val indices = physical.withIndex().associate { it.value to it.index }
        current.setShuffleOrder(DefaultShuffleOrder(ids.map { indices.getValue(it) }.toIntArray(), System.nanoTime()))
        if (!importing) enableSongPrefetch()
    }

    private fun upcoming(): List<QueueEntry> {
        val current = player ?: return emptyList()
        val physical = physicalIds()
        return PlaybackQueue.upcoming(traversalIds(), currentId()).mapNotNull { id ->
            val index = physical.indexOf(id)
            PlaybackMedia.libraryItem(current.getMediaItemAt(index))?.let { QueueEntry(id, it, index) }
        }
    }

    /** Move existing MediaItems instead of resetting the playlist: current audio/position survives. */
    private fun adoptDisplayedOrder(ids: List<String>) {
        val current = player ?: return
        arranging = true
        mediaButtonBoundaryUptime = SystemClock.uptimeMillis()
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

    private fun removeUpcoming(id: String) = preservingUpcomingStart { removeUpcomingNow(id) }

    private fun removeUpcomingNow(id: String) {
        val current = player ?: return
        val order = traversalIds()
        val removal = PlaybackQueue.removal(order, currentId(), id) ?: return
        val index = physicalIds().indexOf(id)
        val media = current.getMediaItemAt(index)
        removedTrack = wireTracks[id]
        removedWasManual = manualTier.contains(id)
        removedOrigin = manualTier.origin(id)
        manualTier.remove(id)
        mediaButtonBoundaryUptime = SystemClock.uptimeMillis()
        PlaybackRequests.queueChanged()
        current.removeMediaItem(index)
        if (current.shuffleModeEnabled) setTraversal(removal.remaining)
        removed = removal to media
        enableSongPrefetch()
    }

    private fun validRemoval(): Boolean = removed?.first?.canUndo(traversalIds(), currentId()) == true

    private fun undoRemoval() = preservingUpcomingStart { undoRemovalNow() }

    private fun undoRemovalNow() {
        val current = player ?: return
        val (removal, media) = removed ?: return
        val restored = removal.undo(traversalIds(), currentId()) ?: run { removed = null; return }
        val before = exportPrepared()
        val restoredTracks = restored.map { id ->
            if (id == removal.occurrence) removedTrack ?: return
            else before.queue.first { it.key == wireKeys.getValue(id) }
        }.let { tracks -> if (current.shuffleModeEnabled) tracks else tracks.mapIndexed { index, track -> track.copy(order = index.toLong()) } }
        try {
            val prospective = before.copy(queue = restoredTracks, position = restored.indexOf(currentId()))
            prospective.validated()
        } catch (_: IllegalArgumentException) { return }
        removed = null
        removedTrack?.let { track -> wireKeys[removal.occurrence] = track.key; wireTracks[removal.occurrence] = track }
        removedTrack = null
        manualTier.tag(listOf(removal.occurrence), removedOrigin, removedWasManual)
        removedWasManual = false
        allowedAudioUrls.add(requireNotNull(media.localConfiguration).uri.toString())
        current.addMediaItem(media)
        if (current.shuffleModeEnabled) setTraversal(restored) else adoptDisplayedOrder(restored)
        schedulePrune()
    }

    private fun moveUpcoming(id: String, to: Int, playNext: Boolean = false) = preservingUpcomingStart {
        moveUpcomingNow(id, to, playNext)
    }

    private fun moveUpcomingNow(id: String, to: Int, playNext: Boolean) {
        val moved = PlaybackQueue.move(traversalIds(), currentId(), id, to)
        if (moved == null) {
            if (playNext && id in PlaybackQueue.upcoming(traversalIds(), currentId())) manualTier.add(listOf(id))
            return
        }
        removed = null
        if (playNext) manualTier.add(listOf(id)) else manualTier.adoptReorder(moved, currentId())
        adoptDisplayedOrder(moved)
    }

    private fun clearUpcoming() = preservingUpcomingStart { clearUpcomingNow() }

    private fun clearUpcomingNow() {
        val current = player ?: return
        val future = PlaybackQueue.upcoming(traversalIds(), currentId()).toSet()
        if (future.isEmpty()) return
        val remaining = traversalIds().filterNot { it in future }
        future.forEach(manualTier::remove); removedWasManual = false
        removed = null
        mediaButtonBoundaryUptime = SystemClock.uptimeMillis()
        PlaybackRequests.queueChanged()
        for (index in current.mediaItemCount - 1 downTo 0) {
            if (PlaybackMedia.occurrence(current.getMediaItemAt(index)) in future) current.removeMediaItem(index)
        }
        if (current.shuffleModeEnabled) setTraversal(remaining)
        enableSongPrefetch()
    }

    private inner class SessionCallback(private val sharedClient: SpofifyClient?) : MediaSession.Callback {
        private fun callerHasAccess(): Boolean = if (localOnlyQueue) hasLocalAuthority()
            else sharedClient != null && sharedClient === client && sharedClient === (application as SpofifyApplication).graph.client &&
                authenticatedSession != null && sharedClient.currentSession() === authenticatedSession
        override fun onPlayerCommandRequest(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            if (!trusted(controller) || !localNamespaceCurrent() || (!localOnlyQueue && !callerHasAccess()))
                return SessionResult.RESULT_ERROR_PERMISSION_DENIED
            if (playerCommand in setOf(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA))
                return SessionResult.RESULT_SUCCESS
            if (!callerHasAccess()) return SessionResult.RESULT_ERROR_PERMISSION_DENIED
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

        private fun canRouteLibraryMediaButton(): Boolean {
            val graph = (application as SpofifyApplication).graph
            val source = graph.client ?: return false
            val login = source.currentSession() ?: return false
            // Routing a fresh trusted action is not a raw-player admission or output grant.
            // A cold service can control retained server state; exact targeted preparation
            // must still bind this account before an action allowance can start audio.
            return !localOnlyQueue && !graph.offlineMode.value && sharedClient === source &&
                source.hasValidSession(login) && graph.playbackCoordinator?.captureBinding() != null
        }

        override fun onMediaButtonEvent(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaButtonIntent: Intent): Boolean {
            if (mediaSession !== session || active.get() !== this@PlaybackService ||
                !PlaybackMediaButtonTrust.accepts(this@PlaybackService, controller) ||
                (!callerHasAccess() && !canRouteLibraryMediaButton())) return true
            if (mediaButtonIntent.action != Intent.ACTION_MEDIA_BUTTON) return false
            val event = try {
                IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } catch (_: RuntimeException) { null } ?: return true
            val known = event.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)
            // Volume is an OS music-stream operation, not player gain or musical transport.
            // Delegate genuinely unmapped keys only after caller admission. Rejected callers
            // must never borrow Media3's notification-controller fallback identity.
            if (!known) return false
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
            val owner = (application as SpofifyApplication).graph.playbackCoordinator
            val devices = owner?.state?.value
            val remote = !localOnlyQueue && devices?.snapshot != null
            val boundary = maxOf(lastSuspendedUptime, mediaButtonBoundaryUptime,
                if (remote) owner?.interactionChangedUptime ?: 0L else 0L)
            // First delivery can itself be delayed across a queue/output/service replacement.
            // Do not turn an old physical press into a fresh intent for the replacement binding.
            val now = SystemClock.uptimeMillis()
            if (event.downTime <= boundary || event.eventTime < event.downTime ||
                event.eventTime > now || now - event.eventTime > USER_ACTION_START_WINDOW_MS) return true
            val identity = MediaButtonDown(event.keyCode, event.deviceId, event.source, event.downTime, event.eventTime)
            if (!mediaButtonDowns.add(identity)) return true
            if (mediaButtonDowns.size > 32) mediaButtonDowns.remove(mediaButtonDowns.first())
            val state = sessionTransportSnapshot()
            val type = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> "resume"
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> "pause"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (state.playing) "pause" else "resume"
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> "next"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> "previous"
                else -> null
            }
            if (type != null) routeCommand(LocalPlaybackCommand.transport(type), userInteraction = true, userActionUptimeMs = event.eventTime)
            else if (state.queue.getOrNull(state.position) != null && state.elapsed.isFinite()) {
                val increment = if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) player?.seekForwardIncrement
                    else player?.seekBackIncrement?.let { -it }
                val view = RemoteSessionView(state)
                val upper = view.durationMs.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: 604800.0
                increment?.let { routeCommand(LocalPlaybackCommand.seek((view.positionMs / 1000.0 + it / 1000.0).coerceIn(0.0, upper)),
                    userInteraction = true, userActionUptimeMs = event.eventTime) }
            }
            // Immediate single-press toggle policy. No default delayed single/double-tap task
            // may re-admit an old headset Play after cancellation; no universal multi-click claim.
            return true
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
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFailedFuture(IllegalArgumentException("Use the playback controller"))

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFailedFuture(IllegalArgumentException("Use the playback controller"))
    }
}
