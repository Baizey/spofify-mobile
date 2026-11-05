package dev.spofify.mobile.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PreparedPlaybackAuthority
import dev.spofify.mobile.core.SpofifyClient
import java.util.concurrent.Executor
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A UI snapshot; the service is the authority, including after activity recreation. */
data class PlaybackState(
    val item: LibraryItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    val queue: List<LibraryItem> = emptyList(),
    val queueIndex: Int = -1,
    /** Actual future traversal, excluding played/current entries even in shuffle/repeat. */
    val upcoming: List<QueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val context: LibraryItem? = null,
    val canUndoQueueRemoval: Boolean = false,
    val playWhenReady: Boolean = false,
    val notice: String? = null,
    /** Exact remote wire metadata, including each occurrence's playbackContext and baseline order.
     * LibraryItem is only a display projection; this snapshot is never imported into local audio. */
    val remoteSnapshot: PlaybackSnapshot? = null,
    /** Transient queue/client/session/output identity. Never save or persist this key. */
    val interactionKey: PlaybackInteractionKey? = null,
    /** Absolute-index drop is local only. Unknown implementations fail closed. */
    val canMoveUpcomingAbsolutely: Boolean = false,
    /** Connection-lifetime transport identity. Capture this, not the generic queue/action key,
     * for keyed Next/Previous. Null for empty/unavailable output; never save or persist. */
    val transportInteractionKey: PlaybackInteractionKey? = null,
    /** Guarded semantic transport, not inferred from the displayed upcoming list. */
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
)

/**
 * An application-context-only UI connection. All calls are marshalled to main, and
 * [release] only releases this controller, NEVER the service/player. Call [stop]
 * before replacing graph.client on logout or server change.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackConnection(context: Context) : PlaybackControls {
    private companion object {
        // New explicit Play on another (including rotated-out) connection retires the old tap,
        // without revoking the healthy player's authority or resetting its queue.
        val latestPlay = java.util.concurrent.atomic.AtomicLong()
    }
    private var requestGeneration = 0L
    private var explicitPlayOrigin: (() -> (() -> Boolean))? = null

    override fun bindExplicitPlayOrigin(capture: () -> (() -> Boolean)) {
        explicitPlayOrigin = capture
    }
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var generation = 0
    private var interactionGeneration = 0L
    private var pendingPlay: PlayRequest? = null
    private var releaseAfterPending = false
    private var applyingPlay = false
    private var releaseTimeoutScheduled = false
    private val releaseTimeout = Runnable {
        releaseTimeoutScheduled = false
        if (releaseAfterPending) releaseInternal()
    }
    private var connectionError: String? = null
    private var actionNotice: String? = null
    private var actionNoticeIsCurrent: (() -> Boolean)? = null
    private var queueSnapshot: List<LibraryItem> = emptyList()
    private var requestedContext: LibraryItem? = null
    private var observing = false
    private var displayingRemote = false
    private val remoteInteractionKeys = PlaybackInteractionKeys()
    private val localTransportKeys = PlaybackInteractionKeys()
    private val remoteTransportKeys = PlaybackInteractionKeys()
    private data class TransportIdentity(
        val rawKey: PlaybackInteractionKey, val owner: PlaybackReference, val version: Triple<Long, Long, Long>,
        val controller: PlaybackReference, val bindingGeneration: Int, val interactionGeneration: Long,
        val capabilityRevision: Long, val selectionGeneration: Long,
    )

    private fun currentLocalTransportKey(rawKey: PlaybackInteractionKey? = PlaybackService.interactionKey()): PlaybackInteractionKey? {
        val binding = controller
        val owner = coordinator
        val available = observing && !releaseAfterPending && binding != null && !hasRemoteOutput() &&
            (PlaybackService.canSkipPrepared(true) || PlaybackService.canSkipPrepared(false))
        return localTransportKeys.current(if (available && rawKey != null && owner != null && binding != null)
            TransportIdentity(rawKey, PlaybackReference(owner), owner.interactionVersion, PlaybackReference(binding),
                generation, interactionGeneration, PlaybackService.transportCapabilityRevision(), owner.transportSelectionVersion) else null)
    }
    private data class RemoteInteractionIdentity(
        val client: PlaybackReference, val session: PlaybackReference, val coordinator: PlaybackReference,
        val deviceId: String, val outputId: String, val epoch: Long, val version: Triple<Long, Long, Long>, val requestEpoch: Long,
        val queue: List<dev.spofify.mobile.core.PlaybackTrack>, val position: Int,
        val context: dev.spofify.mobile.core.PlaybackContext?, val shuffle: Boolean,
    )
    private val coordinator get() = (context as SpofifyApplication).graph.playbackCoordinator

    // Always defer delivery: LibraryViewModel attaches setContext immediately AFTER play().
    private val deliverPlay = Runnable { deliverPendingPlay() }

    private data class PlayRequest(
        val items: List<MediaItem>,
        val startIndex: Int,
        val shuffle: Boolean,
        val client: SpofifyClient,
        val epoch: Long,
        val notice: String?,
        val shouldPlay: Boolean = true,
        val preparedState: PlaybackSnapshot? = null,
        val selectionAuthority: PreparedPlaybackAuthority? = null,
        val isCurrent: () -> Boolean,
        val admissionIsCurrent: () -> Boolean,
    )

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(refreshQueue = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_MEDIA_METADATA_CHANGED))
            maybeReleaseAfterPending()
        }
    }
    private val pollPosition = object : Runnable {
        override fun run() {
            if (!observing) return
            publish()
            handler.postDelayed(this, 250)
        }
    }

    /** Builds a MediaController asynchronously; safe to call more than once. */
    override fun connect() = onMain {
        cancelDeferredRelease()
        connectInternal()
    }

    private fun cancelDeferredRelease() {
        releaseAfterPending = false
        handler.removeCallbacks(releaseTimeout)
        releaseTimeoutScheduled = false
    }

    private fun connectInternal() {
        observing = true
        handler.removeCallbacks(pollPosition)
        handler.post(pollPosition)
        if (controller != null || controllerFuture != null) return
        connectionError = null
        val currentGeneration = ++generation
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = try { MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlaybackConnection.controller !== controller) return
                    handler.removeCallbacks(releaseTimeout)
                    releaseTimeoutScheduled = false
                    localTransportKeys.invalidate()
                    remoteTransportKeys.invalidate()
                    this@PlaybackConnection.controller = null
                    controllerFuture = null
                    controller.removeListener(listener)
                    controller.release()
                    retirePlayRequest()
                    connectionError = "Playback stopped. Sign in or reconnect to try again."
                    publishConnectionFailure()
                }
            })
            .buildAsync()
        } catch (_: RuntimeException) {
            retirePlayRequest()
            connectionError = "Unable to connect to playback. Sign in and try again."
            publishConnectionFailure()
            if (releaseAfterPending) releaseInternal()
            return
        }
        controllerFuture = future
        future.addListener({
            if (currentGeneration != generation) return@addListener
            controllerFuture = null
            try {
                val connected = future.get()
                controller = connected
                connected.addListener(listener)
                if (pendingPlay != null) applyPlay() else drainEnqueues()
                publish(refreshQueue = true)
                handler.removeCallbacks(pollPosition)
                handler.post(pollPosition)
                maybeReleaseAfterPending()
            } catch (_: Exception) {
                // An uncertain binding is not permission to replay a click on reconnect.
                retirePlayRequest()
                connectionError = "Unable to connect to playback. Sign in and try again."
                publishConnectionFailure()
                if (releaseAfterPending) releaseInternal()
            }
        }, mainExecutor)
    }

    override fun release() = onMain {
        ++interactionGeneration // Also fences a deferred release that keeps the binding alive.
        remoteTransportKeys.invalidate()
        localTransportKeys.invalidate()
        mutableState.value = mutableState.value.copy(transportInteractionKey = null,
            canSkipNext = false, canSkipPrevious = false)
        if ((pendingPlay != null && (controller != null || controllerFuture != null)) ||
            (pendingEnqueues.isNotEmpty() && controller == null && controllerFuture != null) ||
            (controller?.playbackState == Player.STATE_BUFFERING && controller?.playWhenReady == true)) {
            // Do not tear down the binding between an explicit tap and its delivery.
            // Keep only this application-context controller until the service settles.
            releaseAfterPending = true
            maybeReleaseAfterPending()
        } else {
            releaseInternal()
        }
    }

    private fun maybeReleaseAfterPending() {
        if (!releaseAfterPending || applyingPlay || pendingPlay != null || pendingEnqueues.isNotEmpty()) return
        val connected = controller ?: return
        if (connected.playbackState == Player.STATE_READY || connected.playbackState == Player.STATE_IDLE ||
            connected.playbackState == Player.STATE_ENDED || connected.playerError != null) {
            releaseInternal()
        } else if (!releaseTimeoutScheduled) {
            // Even a very slow network must not retain a detached UI connection forever.
            // Releasing here still never stops service-owned buffering/playback.
            releaseTimeoutScheduled = true
            handler.postDelayed(releaseTimeout, 30_000)
        }
    }

    private fun releaseInternal() {
        ++generation
        observing = false
        handler.removeCallbacks(deliverPlay)
        handler.removeCallbacks(pollPosition)
        handler.removeCallbacks(releaseTimeout)
        releaseTimeoutScheduled = false
        val connected = controller
        controller = null
        connected?.let {
            it.removeListener(listener)
            it.release()
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        releaseAfterPending = false
        remoteTransportKeys.invalidate()
        localTransportKeys.invalidate()
        remoteInteractionKeys.invalidate()
        mutableState.value = mutableState.value.copy(interactionKey = null, transportInteractionKey = null, canMoveUpcomingAbsolutely = false,
            canSkipNext = false, canSkipPrevious = false)
    }

    /** Latest explicit play wins if multiple taps arrive while connecting. */
    override fun play(
        items: List<LibraryItem>,
        client: SpofifyClient,
        startTrackId: String?,
        shuffle: Boolean,
        startEntryId: String?,
    ) {
        val session = client.currentSession()
        val serial = latestPlay.incrementAndGet()
        val failureGeneration = requestGeneration
        onMainWithEpoch { requestEpoch ->
            val lifetime = { session != null && client.currentSession() === session &&
                failureGeneration == requestGeneration && client === (context as SpofifyApplication).graph.client &&
                requestEpoch == PlaybackRequests.epoch }
            val live = { serial == latestPlay.get() && lifetime() }
            if (!live()) return@onMainWithEpoch
            val playable = items.filter { it.kind == "track" && it.playable }
            val chosen = playbackStartIndex(playable, startTrackId, startEntryId)
            // Never substitute a different first track when the explicitly selected entry is beyond the cap.
            val tracks = if (chosen >= PlaybackQueue.LIMIT) listOf(playable[chosen]) else playable.take(PlaybackQueue.LIMIT)
            if (tracks.isEmpty()) {
                mutableState.value = mutableState.value.copy(error = "No playable tracks in this queue.")
                return@onMainWithEpoch
            }
            val request = try {
                PlayRequest(
                    tracks.map { PlaybackMedia.create(it, client) },
                    playbackStartIndex(tracks, startTrackId, startEntryId),
                    shuffle,
                    client,
                    requestEpoch,
                    when {
                        chosen >= PlaybackQueue.LIMIT -> "This selection is beyond the 1,000-song limit. Only the selected occurrence is loaded."
                        playable.size > PlaybackQueue.LIMIT -> "First 1,000 playable songs loaded. This context has more music."
                        else -> null
                    },
                    isCurrent = live, admissionIsCurrent = lifetime,
                )
            } catch (_: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(error = "This queue contains an invalid track.")
                return@onMainWithEpoch
            }
            connectionError = null
            cancelDeferredRelease()
            // A later Play replaces any earlier not-yet-delivered Add actions.
            pendingEnqueues.clear()
            requestedContext = null
            pendingPlay = request
            // Keep the accepted queue visible while binding/permission/remote confirmation is pending.
            connectInternal()
            if (controller != null) applyPlay()
        }
    }

    override fun playPrepared(queue: dev.spofify.mobile.core.PlaybackQueue, client: SpofifyClient,
        startTrackId: String?, shuffle: Boolean, startEntryId: String?, context: LibraryItem?) =
        playPreparedGuarded(queue, client, startTrackId, shuffle, startEntryId, context, isCurrent = { true })

    override fun playPreparedGuarded(queue: dev.spofify.mobile.core.PlaybackQueue, client: SpofifyClient,
        startTrackId: String?, shuffle: Boolean, startEntryId: String?, context: LibraryItem?, isCurrent: () -> Boolean,
        admissionIsCurrent: () -> Boolean) {
        val session = client.currentSession()
        val serial = latestPlay.incrementAndGet()
        val failureGeneration = requestGeneration
        onMainWithEpoch { requestEpoch ->
            val lifetime = { admissionIsCurrent() && session != null && client.currentSession() === session &&
                failureGeneration == requestGeneration && client === (this.context as SpofifyApplication).graph.client &&
                requestEpoch == PlaybackRequests.epoch }
            val live = { isCurrent() && serial == latestPlay.get() && lifetime() }
            if (!live()) return@onMainWithEpoch
            val prepared = try {
                PlaybackSnapshotCodec.preparedQueue(queue, startTrackId, startEntryId, shuffle, context)
            } catch (_: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(error = "This prepared selection is unavailable. Reload the collection and try again.")
                return@onMainWithEpoch
            }
            if (prepared.context?.kind == "derived" && queue.selectionAuthority?.isValidFor(client, prepared) != true) {
                mutableState.value = mutableState.value.copy(error = "This prepared selection expired. Reload the collection and choose again.")
                return@onMainWithEpoch
            }
            val plan = PlaybackSnapshotCodec.plan(prepared)
            val media = try { plan.mediaItems(client) } catch (_: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(error = "Sign in again before playing this selection.")
                return@onMainWithEpoch
            }
            connectionError = null
            cancelDeferredRelease(); pendingEnqueues.clear(); requestedContext = null
            pendingPlay = PlayRequest(media, plan.currentIndex.coerceAtLeast(0), prepared.shuffle, client, requestEpoch,
                remotePlaybackState(prepared).notice, preparedState = prepared, selectionAuthority = queue.selectionAuthority,
                isCurrent = live, admissionIsCurrent = lifetime)
            connectInternal()
            if (controller != null) applyPlay()
        }
    }

    override fun performPreparedAction(queue: dev.spofify.mobile.core.PlaybackQueue, client: SpofifyClient,
        action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, startTrackId: String?,
        startEntryId: String?, context: LibraryItem?, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
        dispatchListAction(expectedKey, client, isCurrent, onResult, publishNotice = false,
            allowRemote = action == PlaybackListAction.PLAY_NEXT) { live, complete ->
            if (action == PlaybackListAction.CONTEXTUAL && expectedKey == null) {
                complete(PlaybackActionResult(PlaybackActionStatus.STALE))
            } else PlaybackService.performPreparedAction(queue, client, action, expectedKey,
                startTrackId, startEntryId, context, live,
                expectedKeyIsCurrent = { expectedKey == null ||
                    (if (hasRemoteOutput()) currentRemoteInteractionKey() else PlaybackService.interactionKey()) === expectedKey },
                onResult = complete)
        }
    }

    override fun contextualOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
        isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
        dispatchListAction(expectedKey, (this.context as SpofifyApplication).graph.client, isCurrent, onResult) { live, complete ->
            PlaybackService.contextualOccurrence(expectedKey, occurrenceId, live, complete)
        }
    }

    override fun playNextOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
        isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
        dispatchListAction(expectedKey, (context as SpofifyApplication).graph.client, isCurrent, onResult,
            allowRemote = true) { live, complete ->
            if (!hasRemoteOutput()) PlaybackService.playNextOccurrence(expectedKey, occurrenceId, live, complete)
            else {
                val owner = coordinator
                val snapshot = remoteSnapshot()
                val key = snapshot?.let { remoteUpcomingKey(it, occurrenceId) }
                if (owner == null || snapshot == null || key == null || currentRemoteInteractionKey() !== expectedKey) {
                    complete(PlaybackActionResult(PlaybackActionStatus.STALE))
                } else if (snapshot.queue.getOrNull(snapshot.position + 1)?.key == key) {
                    complete(PlaybackActionResult(PlaybackActionStatus.NO_OP))
                } else owner.remoteCommandWithResult(PlaybackDeviceCommand.queue("move", key, "next"),
                    isCurrent = { live() && owner === coordinator && currentRemoteInteractionKey() === expectedKey }) { status ->
                    complete(PlaybackActionResult(when (status) {
                        "ok" -> PlaybackActionStatus.ADDED
                        "blocked" -> PlaybackActionStatus.BLOCKED
                        else -> PlaybackActionStatus.UNAVAILABLE
                    }, if (status == "ok") 1 else 0))
                }
            }
        }
    }

    private fun dispatchListAction(expectedKey: PlaybackInteractionKey?, client: SpofifyClient?, isCurrent: () -> Boolean,
        onResult: (PlaybackActionResult) -> Unit, publishNotice: Boolean = true, allowRemote: Boolean = false,
        dispatch: (() -> Boolean, (PlaybackActionResult) -> Unit) -> Unit) {
        val epoch = PlaybackRequests.epoch
        val session = client?.currentSession()
        val owner = coordinator
        val output = owner?.state?.value?.activeDeviceId
        val authorityVersion = owner?.interactionVersion?.let { it.first to it.second }
        // Unlike legacy posted transport, rejected/cancelled calls must also complete exactly once.
        handler.post {
            var finished = false
            fun authorityCurrent() = epoch == PlaybackRequests.epoch && client != null &&
                client === (context as SpofifyApplication).graph.client && client.currentSession() === session &&
                session != null && owner === coordinator && output == owner?.state?.value?.activeDeviceId &&
                authorityVersion == owner?.interactionVersion?.let { it.first to it.second }
            fun current() = isCurrent() && authorityCurrent()
            val complete: (PlaybackActionResult) -> Unit = completion@{ result ->
                if (finished) return@completion
                finished = true
                val valid = current()
                val actual = if (valid) result else PlaybackActionResult(PlaybackActionStatus.STALE)
                // Never publish an old-account notice. A successful mutation naturally changes
                // its interaction key; the service checked the old key at actual dispatch.
                // Prepared host actions publish their route-fenced callback in the ViewModel;
                // only Up next needs an application-owned notice without host route plumbing.
                if (valid && publishNotice) {
                    actionNotice = actual.userMessage
                    actionNoticeIsCurrent = ::authorityCurrent
                    publish(refreshQueue = true)
                }
                onResult(actual)
            }
            when {
                !current() -> complete(PlaybackActionResult(PlaybackActionStatus.STALE))
                hasRemoteOutput() && !allowRemote -> complete(PlaybackActionResult(PlaybackActionStatus.UNSUPPORTED_REMOTE))
                controller == null -> complete(PlaybackActionResult(PlaybackActionStatus.UNAVAILABLE))
                expectedKey != null && (if (hasRemoteOutput()) currentRemoteInteractionKey() else PlaybackService.interactionKey()) !== expectedKey ->
                    complete(PlaybackActionResult(PlaybackActionStatus.STALE))
                else -> dispatch({ !finished && current() }, complete)
            }
        }
    }

    private fun applyPlay() {
        handler.removeCallbacks(deliverPlay)
        handler.post(deliverPlay)
    }

    private fun deliverPendingPlay() {
        if (controller == null) return
        val request = pendingPlay ?: return
        pendingPlay = null
        val requestContext = requestedContext
        // A rotated-out connection can finish binding AFTER a different connection
        // stopped playback or replaced the account. Never replay its stale tap.
        if (!request.isCurrent() || request.epoch != PlaybackRequests.epoch ||
            request.client !== (context as SpofifyApplication).graph.client ||
            request.client.currentSession() == null) {
            pendingEnqueues.clear()
            connectionError = "Playback request cancelled after the session changed."
            publish(refreshQueue = true)
            if (releaseAfterPending) releaseInternal()
            return
        }
        connectionError = null
        applyingPlay = true
        try {
            // The service owns acceptance and asynchronous coordinator authority. Never queue
            // a MediaController replacement or mutate queue context ahead of that acceptance.
            connectionError = PlaybackService.replacePrepared(request.items, request.client,
                request.startIndex, request.shuffle, request.shouldPlay,
                context = requestContext, notice = request.notice, requestEpoch = request.epoch,
                preparedState = request.preparedState, selectionAuthority = request.selectionAuthority,
                isCurrent = request.isCurrent, admissionIsCurrent = request.admissionIsCurrent)
        } finally {
            applyingPlay = false
        }
        drainEnqueues()
        publish(refreshQueue = true)
        maybeReleaseAfterPending()
    }

    private fun drainEnqueues() {
        val additions = pendingEnqueues.toList()
        pendingEnqueues.clear()
        additions.forEach { request ->
            if (request.epoch == PlaybackRequests.epoch &&
                request.client === (context as SpofifyApplication).graph.client && request.client.currentSession() != null) {
                applyEnqueue(request.items, request.client, request.first)
            }
        }
    }

    override fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean) = onMainWithEpoch { requestEpoch ->
        if (requestEpoch != PlaybackRequests.epoch ||
            client !== (context as SpofifyApplication).graph.client || client.currentSession() == null) return@onMainWithEpoch
        cancelDeferredRelease()
        // A service must exist before an empty Add, but no PlayRequest/autoplay is created.
        if (controller == null || pendingPlay != null) {
            val tracks = items.filter { it.kind == "track" && it.playable }
            val room = (PlaybackQueue.LIMIT - pendingEnqueues.sumOf { it.items.size }).coerceAtLeast(0)
            if (room == 0 || tracks.isEmpty()) {
                mutableState.value = mutableState.value.copy(error = if (tracks.isEmpty()) "No playable tracks to add." else "Queue is full (1,000 tracks).")
                return@onMainWithEpoch
            }
            pendingEnqueues.add(EnqueueRequest(tracks.take(room), client, playNext, requestEpoch))
            connectInternal()
        } else applyEnqueue(items, client, playNext)
    }

    private data class EnqueueRequest(val items: List<LibraryItem>, val client: SpofifyClient, val first: Boolean, val epoch: Long)
    private val pendingEnqueues = mutableListOf<EnqueueRequest>()

    private fun applyEnqueue(items: List<LibraryItem>, client: SpofifyClient, first: Boolean) {
        connectionError = PlaybackService.enqueuePrepared(items, client, first)
        publish(refreshQueue = true)
    }

    override fun removeUpcoming(occurrenceId: String) = onMainQueue {
        if (hasRemoteOutput()) {
            remoteSnapshot()?.let { snapshot ->
                remoteUpcomingKey(snapshot, occurrenceId)?.let { send(PlaybackDeviceCommand.queue("remove", it)) }
            }
        } else {
            connectionError = null
            PlaybackService.removeQueued(occurrenceId)
        }
        publish(true)
    }

    override fun moveUpcoming(occurrenceId: String, toIndex: Int) = onMainQueue {
        if (hasRemoteOutput()) {
            val snapshot = remoteSnapshot() ?: return@onMainQueue
            val future = snapshot.queue.drop(snapshot.position + 1)
            val from = future.indexOfFirst { PlaybackSnapshotCodec.occurrenceId(it) == occurrenceId }
            if (from < 0 || toIndex !in future.indices || from == toIndex) return@onMainQueue
            val direction = remoteMoveDirection(from, toIndex)
            if (direction == null) {
                connectionError = "Remote queue moves support one step up, one step down, or Play next only."
            } else send(PlaybackDeviceCommand.queue("move", future[from].key, direction))
        } else {
            connectionError = null
            PlaybackService.moveQueued(occurrenceId, toIndex)
        }
        publish(true)
    }

    override fun moveUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, toIndex: Int) = onMainQueue {
        if (hasRemoteOutput()) {
            val owner = coordinator ?: return@onMainQueue
            val snapshot = remoteSnapshot() ?: return@onMainQueue
            if (currentRemoteInteractionKey() !== expectedKey) return@onMainQueue
            val future = snapshot.queue.drop(snapshot.position + 1)
            val from = future.indexOfFirst { PlaybackSnapshotCodec.occurrenceId(it) == occurrenceId }
            if (from < 0 || toIndex !in future.indices || from == toIndex) return@onMainQueue
            val direction = remoteMoveDirection(from, toIndex) ?: return@onMainQueue
            val wireKey = future[from].key
            // One representable command only. Recheck after coordinator transport waits;
            // never retarget a held occurrence/index onto a new owner or replacement queue.
            owner.remoteCommand(PlaybackDeviceCommand.queue("move", wireKey, direction)) {
                owner === coordinator && hasRemoteOutput() && currentRemoteInteractionKey() === expectedKey
            }
        } else {
            if (controller == null) return@onMainQueue
            PlaybackService.moveQueued(expectedKey, occurrenceId, toIndex)
        }
        publish(true)
    }

    override fun clearUpcoming() = onMainQueue {
        if (hasRemoteOutput()) send(PlaybackDeviceCommand.transport("clear"))
        else { connectionError = null; PlaybackService.clearQueued() }
        publish(true)
    }

    override fun undoQueueRemoval() = onMainQueue {
        if (hasRemoteOutput()) connectionError = "Undo is not available for a remote queue."
        else { connectionError = null; PlaybackService.undoQueuedRemoval() }
        publish(true)
    }

    override fun setContext(context: LibraryItem?) = onMainQueue {
        // Request metadata only: acceptance by the service/coordinator owns the actual context.
        requestedContext = context?.copy(libraryPath = null)
    }

    override fun selectOccurrence(occurrenceId: String) = onMainQueue {
        val key = if (hasRemoteOutput()) remoteSnapshot()?.let { remoteUpcomingKey(it, occurrenceId) }
            else PlaybackService.queuedKey(occurrenceId)
        if (key != null) send(PlaybackDeviceCommand.queue("jump", key))
        publish()
    }

    override fun toggle() {
        // Capture BEFORE posting. A retained callback cannot acquire a fresh route/session
        // lifetime when the main queue eventually delivers it or a claim finishes.
        val origin = explicitPlayOrigin?.invoke() ?: { false }
        val source = (context as SpofifyApplication).graph.client
        val session = source?.currentSession()
        val failureGeneration = requestGeneration
        val requestEpoch = PlaybackRequests.epoch
        val serial = latestPlay.incrementAndGet()
        val lifetime = { origin() && source != null && session != null && source.currentSession() === session &&
            source === (context as SpofifyApplication).graph.client && failureGeneration == requestGeneration &&
            requestEpoch == PlaybackRequests.epoch }
        val current = { serial == latestPlay.get() && lifetime() }
        onMainQueue {
            // A disposed/stale callback must neither acquire output nor pause healthy audio
            // that completed before its host disappeared. Unknown origins fail closed.
            if (!current()) {
                connectionError = "Playback changed. Choose music again."
                publish()
                return@onMainQueue
            }
            // A pause before deferred Play delivery must also cancel that request's autoplay intent.
            val pending = pendingPlay
            val snapshot = if (hasRemoteOutput()) remoteSnapshot() else PlaybackService.exportLocalSnapshot()
            val playing = pending?.shouldPlay ?: (snapshot?.playing == true)
            if (playing) retirePlayRequest()
            if (!playing && !hasRemoteOutput()) {
                connectionError = PlaybackService.resumePrepared(isCurrent = current, admissionIsCurrent = lifetime)
            } else send(PlaybackDeviceCommand.transport(if (playing) "pause" else if (!snapshot?.error.isNullOrEmpty()) "retry" else "resume"))
            publish()
        }
    }

    override fun next() = command("next")
    override fun previous() = command("previous")

    override fun next(expectedKey: PlaybackInteractionKey): Boolean = guardedSkip(expectedKey, true)
    override fun previous(expectedKey: PlaybackInteractionKey): Boolean = guardedSkip(expectedKey, false)

    private fun guardedSkip(expectedKey: PlaybackInteractionKey, forward: Boolean): Boolean {
        // Never post a fresh intention, connect, or recover output to make a held swipe valid.
        if (Looper.myLooper() != Looper.getMainLooper() || !observing || controller == null || releaseAfterPending) return false
        val binding = controller
        val bindingGeneration = generation
        val interaction = interactionGeneration
        val originCurrent = {
            observing && !releaseAfterPending && controller === binding && generation == bindingGeneration &&
                interactionGeneration == interaction
        }
        if (!hasRemoteOutput()) {
            val rawKey = PlaybackService.interactionKey() ?: return false
            if (currentLocalTransportKey(rawKey) !== expectedKey) return false
            // Generic queue/action keys remain service-owned; transport alone projects the
            // originating connection lifetime. Do not compare the old projection after our seek.
            return PlaybackService.skipPrepared(rawKey, forward, originCurrent).also { publish() }
        }
        val owner = coordinator ?: return false
        fun current(): Boolean {
            if (!originCurrent() || owner !== coordinator || !hasRemoteOutput() ||
                currentRemoteTransportKey() !== expectedKey) return false
            val directions = remoteSnapshot()?.let(::remoteSkipCapabilities) ?: return false
            return if (forward) directions.first else directions.second
        }
        if (!current()) return false
        owner.preparedTransportCommand(PlaybackDeviceCommand.transport(if (forward) "next" else "previous"), ::current)
        publish()
        return true
    }

    override fun select(index: Int) = onMainQueue {
        val key = if (hasRemoteOutput()) remoteSnapshot()?.let { snapshot ->
            snapshot.queue.getOrNull(index)?.takeIf { index > snapshot.position }?.key
        } else PlaybackService.timelineKey(index)
        if (key != null) send(PlaybackDeviceCommand.queue("jump", key))
        publish()
    }

    override fun seekTo(positionMs: Long) = onMainQueue {
        val snapshot = if (hasRemoteOutput()) remoteSnapshot() else PlaybackService.exportLocalSnapshot()
        if (snapshot == null || snapshot.queue.isEmpty()) return@onMainQueue
        val duration = (snapshot.duration * 1000).roundToLong()
        val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        send(PlaybackDeviceCommand.seek(target.coerceAtMost(604_800_000) / 1000.0))
        publish()
    }

    override fun toggleShuffle() = command("shuffle")
    override fun cycleRepeat() = command("repeat")

    private fun command(type: String) = onMainQueue {
        send(PlaybackDeviceCommand.transport(type))
        publish()
    }

    private fun send(command: PlaybackDeviceCommand) {
        val owner = coordinator
        connectionError = if (owner == null) "Playback is unavailable. Sign in and reconnect." else null
        owner?.remoteCommand(command)
    }

    private fun hasRemoteOutput(): Boolean {
        val devices = coordinator?.state?.value ?: return false
        return devices.activeDeviceId.isNotEmpty() && devices.activeDeviceId != devices.deviceId
    }

    private fun remoteSnapshot(): PlaybackSnapshot? = coordinator?.state?.value?.view?.state

    private fun currentRemoteTransportKey(): PlaybackInteractionKey? {
        // Generic remote queue/context actions do not require an observing UI controller.
        // Mini transport additionally binds connection lifetime and EVERY accepted selection.
        val binding = controller
        val owner = coordinator
        val snapshot = remoteSnapshot()
        val rawKey = if (observing && !releaseAfterPending && binding != null && snapshot != null &&
            snapshot.queue.getOrNull(snapshot.position) != null) currentRemoteInteractionKey() else null
        return remoteTransportKeys.current(if (rawKey != null && binding != null && owner != null)
            TransportIdentity(rawKey, PlaybackReference(owner), owner.interactionVersion, PlaybackReference(binding),
                generation, interactionGeneration, owner.transportCapabilityVersion, owner.transportSelectionVersion) else null)
    }

    private fun currentRemoteInteractionKey(): PlaybackInteractionKey? {
        val owner = coordinator
        val devices = owner?.state?.value
        val graphClient = (context as SpofifyApplication).graph.client
        val session = graphClient?.currentSession()
        val snapshot = devices?.view?.state
        val available = devices != null && devices.connected && !devices.selectionUnconfirmed &&
            devices.deviceId.isNotEmpty() && devices.activeDeviceId.isNotEmpty() &&
            devices.activeDeviceId != devices.deviceId && devices.view?.transfer == null &&
            devices.quarantineMs == 0L && devices.devices.any { it.deviceId == devices.activeDeviceId && it.available }
        val identity = if (available && graphClient != null && session != null && snapshot != null) {
            RemoteInteractionIdentity(PlaybackReference(graphClient), PlaybackReference(session), PlaybackReference(owner),
                devices.deviceId, devices.activeDeviceId, devices.view.epoch, owner.interactionVersion, PlaybackRequests.epoch,
                snapshot.queue, snapshot.position, snapshot.context, snapshot.shuffle)
        } else null
        return remoteInteractionKeys.current(identity)
    }

    /** Explicit logout/server-switch stop, unlike release(). Works before connect completes. */
    override fun stop() {
        // Invalidate synchronously, even if called by an OkHttp auth callback off-main.
        // Every connection checks this epoch before applying a deferred request.
        PlaybackRequests.invalidate()
        onMain {
            retirePlayRequest()
            pendingEnqueues.clear()
            requestedContext = null
            actionNotice = null
            actionNoticeIsCurrent = null
            releaseInternal()
            PlaybackService.stopActivePlayback()
            context.stopService(Intent(context, PlaybackService::class.java))
            connectionError = null
            queueSnapshot = emptyList()
            displayingRemote = false
            mutableState.value = PlaybackState()
        }
    }

    private fun retirePlayRequest() {
        ++requestGeneration
        pendingPlay = null
        requestedContext = null
        handler.removeCallbacks(deliverPlay)
    }

    private fun publishConnectionFailure() {
        remoteTransportKeys.invalidate()
        localTransportKeys.invalidate()
        if (hasRemoteOutput()) publish()
        else mutableState.value = mutableState.value.copy(
            isPlaying = false, playWhenReady = false, isBuffering = false, error = connectionError,
            interactionKey = null, transportInteractionKey = null, canMoveUpcomingAbsolutely = false, canSkipNext = false, canSkipPrevious = false,
        )
    }

    private fun publish(refreshQueue: Boolean = false) {
        if (actionNoticeIsCurrent?.invoke() != true) {
            actionNotice = null
            actionNoticeIsCurrent = null
        }
        // Do not read the local queue/history at all while displaying another output.
        if (hasRemoteOutput()) {
            localTransportKeys.invalidate()
            displayingRemote = true
            val snapshot = remoteSnapshot() ?: PlaybackSnapshot()
            val remote = remotePlaybackState(snapshot, connectionError)
            val key = currentRemoteInteractionKey()
            val transportKey = currentRemoteTransportKey()
            val directions = remoteSkipCapabilities(snapshot)
            mutableState.value = remote.copy(
                interactionKey = key,
                transportInteractionKey = transportKey,
                canSkipNext = transportKey != null && directions.first,
                canSkipPrevious = transportKey != null && directions.second,
                notice = actionNotice ?: remote.notice,
            )
            return
        }
        remoteInteractionKeys.invalidate()
        remoteTransportKeys.invalidate()
        val wasRemote = displayingRemote
        displayingRemote = false
        val connected = controller ?: run {
            if (wasRemote) mutableState.value = PlaybackState(error = connectionError)
            return
        }
        if (refreshQueue || wasRemote) {
            queueSnapshot = (0 until connected.mediaItemCount).mapNotNull {
                PlaybackMedia.libraryItem(connected.getMediaItemAt(it))
            }
        }
        val duration = connected.duration
        val currentItem = connected.currentMediaItem?.let(PlaybackMedia::libraryItem)
        val interactive = observing && !releaseAfterPending
        val rawKey = PlaybackService.interactionKey()
        val transportKey = currentLocalTransportKey(rawKey)
        mutableState.value = PlaybackState(
            item = currentItem,
            isPlaying = connected.isPlaying,
            playWhenReady = connected.playWhenReady && connected.playbackState != Player.STATE_ENDED && connected.playerError == null,
            isBuffering = connected.playbackState == Player.STATE_BUFFERING,
            positionMs = connected.currentPosition.coerceAtLeast(0),
            durationMs = if (duration == C.TIME_UNSET) currentItem?.durationMs ?: 0 else duration.coerceAtLeast(0),
            shuffle = PlaybackService.exportLocalSnapshot().shuffle,
            repeatMode = connected.repeatMode,
            error = connected.playerError?.let(::playbackErrorMessage) ?: connectionError,
            queue = queueSnapshot,
            queueIndex = if (queueSnapshot.isEmpty()) -1 else connected.currentMediaItemIndex,
            upcoming = PlaybackService.upcomingEntries(),
            currentEntryId = PlaybackMedia.occurrence(connected.currentMediaItem),
            context = PlaybackService.queueContext(),
            canUndoQueueRemoval = PlaybackService.canUndoRemoval(),
            notice = actionNotice ?: PlaybackService.queueNotice(),
            interactionKey = rawKey,
            transportInteractionKey = transportKey,
            canMoveUpcomingAbsolutely = rawKey != null,
            canSkipNext = interactive && PlaybackService.canSkipPrepared(true),
            canSkipPrevious = interactive && PlaybackService.canSkipPrepared(false),
        )
    }

    private fun onMainQueue(action: () -> Unit) {
        val queueEpoch = PlaybackRequests.queueEpoch
        val remoteQueue = mutableState.value.remoteSnapshot?.queue
        onMain {
            if (queueEpoch != PlaybackRequests.queueEpoch) return@onMain
            if (remoteQueue != null && remoteQueue != remoteSnapshot()?.queue) return@onMain
            action()
        }
    }

    private fun onMainWithEpoch(action: (Long) -> Unit) {
        // Capture before posting so logout cannot turn an already queued call into a fresh request.
        val epoch = PlaybackRequests.epoch
        onMain { if (epoch == PlaybackRequests.epoch) action(epoch) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else {
            // Transport/queue calls posted before a pause, output switch or logout must not
            // become new authority-acquiring intentions when the main looper catches up.
            val epoch = PlaybackRequests.epoch
            handler.post { if (epoch == PlaybackRequests.epoch) action() }
        }
    }
}

/** Pure display conversion: never prepare MediaItems, import a queue, or sample local history. */
internal fun remotePlaybackState(snapshot: PlaybackSnapshot, error: String? = null): PlaybackState {
    val items = snapshot.queue.map(PlaybackSnapshotCodec::libraryItem)
    val current = snapshot.queue.getOrNull(snapshot.position)
    val context = current?.playbackContext ?: snapshot.context
    return PlaybackState(
        item = items.getOrNull(snapshot.position),
        isPlaying = snapshot.playing,
        playWhenReady = snapshot.playing,
        isBuffering = snapshot.waiting || snapshot.loading,
        positionMs = (snapshot.elapsed * 1000).roundToLong(),
        durationMs = (snapshot.duration * 1000).roundToLong(),
        shuffle = snapshot.shuffle,
        repeatMode = when (snapshot.repeat) {
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        },
        error = error ?: when (snapshot.error) {
            "blocked" -> "Playback needs a tap on the selected device."
            "media" -> "The selected device could not play this track. Retry explicitly."
            "queue" -> "The selected device could not load this queue. Choose music again."
            else -> null
        },
        queue = items,
        queueIndex = snapshot.position,
        upcoming = snapshot.queue.mapIndexedNotNull { index, track ->
            if (index <= snapshot.position) null
            else QueueEntry(PlaybackSnapshotCodec.occurrenceId(track), items[index], index)
        },
        currentEntryId = current?.let(PlaybackSnapshotCodec::occurrenceId),
        context = context?.let { LibraryItem(it.kind, it.id, it.name) },
        canUndoQueueRemoval = false,
        notice = when (snapshot.notice) {
            "CLICKED_OCCURRENCE_ONLY" -> "This selection is beyond the 1,000-song limit. Only the selected occurrence is loaded."
            "PLAYBACK_LIMIT" -> "Queue limited to 1,000 songs."
            else -> if (snapshot.truncated) "Queue limited to 1,000 songs." else null
        },
        remoteSnapshot = snapshot,
    )
}

/** Wire IDs identify occurrences, not duplicate track IDs or arbitrary occurrenceId metadata. */
internal fun remoteUpcomingKey(snapshot: PlaybackSnapshot, occurrenceId: String): Long? =
    snapshot.queue.drop(snapshot.position + 1)
        .firstOrNull { PlaybackSnapshotCodec.occurrenceId(it) == occurrenceId }?.key

/** A remote move is exactly one supported command, never an unacknowledged replay batch. */
internal fun remoteMoveDirection(from: Int, to: Int): String? = when {
    from < 0 || to < 0 || from == to -> null
    to == 0 -> "next"
    to == from - 1 -> "up"
    to == from + 1 -> "down"
    else -> null
}
