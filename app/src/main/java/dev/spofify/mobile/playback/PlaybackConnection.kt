package dev.spofify.mobile.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.OfflineEligibility
import dev.spofify.mobile.OfflineMediaReference
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToLong

/** Presentation only. Online music is controlled by the server; saved audio remains service-local. */
data class PlaybackState(
    val item: LibraryItem? = null, val isPlaying: Boolean = false, val isBuffering: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = 0, val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF, val error: String? = null,
    val queue: List<LibraryItem> = emptyList(), val queueIndex: Int = -1,
    val upcoming: List<QueueEntry> = emptyList(), val currentEntryId: String? = null,
    val context: LibraryItem? = null, val canUndoQueueRemoval: Boolean = false,
    val playWhenReady: Boolean = false, val notice: String? = null, val feedback: PlaybackFeedback? = null,
    val remoteSnapshot: PlaybackSnapshot? = null,
    val interactionKey: PlaybackInteractionKey? = null, val canMoveUpcomingAbsolutely: Boolean = false,
    val transportInteractionKey: PlaybackInteractionKey? = null,
    val canSkipNext: Boolean = false, val canSkipPrevious: Boolean = false,
    val queueIntentBinding: LibraryPlaybackActionBinding? = null, val canQueueIntent: Boolean = false,
)

/** Activity/controller release never stops the application's service or socket. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackConnection(context: Context) : PlaybackControls {
    private companion object { val latestLocalPlay = java.util.concurrent.atomic.AtomicLong() }
    private val context = context.applicationContext
    private val graph get() = (context as SpofifyApplication).graph
    private val coordinator get() = graph.playbackCoordinator
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var generation = 0L
    private var observing = false
    private var releaseRequested = false
    private var explicitPlayOrigin: (() -> (() -> Boolean))? = null
    private var localRequest: (() -> Unit)? = null
    private var connectionError: String? = null
    private val actionKeys = PlaybackInteractionKeys()
    private val transportKeys = PlaybackInteractionKeys()
    private var feedback: PlaybackFeedback? = null
    private var feedbackCurrent: (() -> Boolean)? = null
    private val local get() = graph.offlineMode.value || PlaybackService.isLocalOnlyQueue()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
    }
    private val sampler = object : Runnable {
        override fun run() {
            if (!observing) return
            publish()
            handler.postDelayed(this, 250)
        }
    }

    override fun bindExplicitPlayOrigin(capture: () -> (() -> Boolean)) { explicitPlayOrigin = capture }
    override fun consumeFeedback(id: Long) = onMain {
        if (feedback?.id == id) { feedback = null; feedbackCurrent = null; publish() }
    }
    override fun connect() = onMain { connectInternal() }
    private fun connectInternal() {
        releaseRequested = false
        observing = true
        handler.removeCallbacks(sampler)
        handler.post(sampler)
        if (controller != null || future != null) return
        connectionError = null
        val incarnation = ++generation
        val pending = try {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .setListener(object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        if (this@PlaybackConnection.controller !== controller) return
                        this@PlaybackConnection.controller = null
                        controller.removeListener(listener)
                        controller.release()
                        localRequest = null
                        connectionError = "Playback unavailable."
                        actionKeys.invalidate(); transportKeys.invalidate(); publish()
                    }
                }).buildAsync()
        } catch (_: RuntimeException) {
            localRequest = null; connectionError = "Playback unavailable."; publish(); return
        }
        future = pending
        pending.addListener({
            if (incarnation != generation) return@addListener
            future = null
            try {
                controller = pending.get().also { it.addListener(listener) }
                val request = localRequest
                localRequest = null
                request?.invoke()
                publish()
                if (releaseRequested) release()
            } catch (_: Exception) {
                localRequest = null; connectionError = "Playback unavailable."; publish()
            }
        }, executor)
    }
    override fun release() = onMain {
        observing = false
        if (localRequest != null && future != null) {
            releaseRequested = true
            handler.removeCallbacks(sampler)
            return@onMain
        }
        releaseRequested = false
        ++generation
        localRequest = null
        handler.removeCallbacks(sampler)
        controller?.let { it.removeListener(listener); it.release() }
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
        actionKeys.invalidate(); transportKeys.invalidate()
        mutableState.value = mutableState.value.copy(interactionKey = null, transportInteractionKey = null,
            canSkipNext = false, canSkipPrevious = false, canMoveUpcomingAbsolutely = false)
    }
    override fun modeChanged() = onMain {
        ++generation
        future?.let(MediaController::releaseFuture)
        future = null
        localRequest = null
        feedback = null; feedbackCurrent = null; connectionError = null
        actionKeys.invalidate(); transportKeys.invalidate()
        mutableState.value = PlaybackState()
        publish()
    }

    override fun playLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, startEntryId: String?, shuffle: Boolean,
        context: LibraryItem?, isCurrent: () -> Boolean) {
        val retained = items.map { it.copy(artists = it.artists.toList()) }
        val references = mediaReferences.toMap()
        val serial = latestLocalPlay.incrementAndGet()
        val epoch = PlaybackRequests.epoch
        fun current() = serial == latestLocalPlay.get() && epoch == PlaybackRequests.epoch &&
            graph.localPlaybackIsCurrent(eligibility) && isCurrent()
        onMain {
            if (!current()) return@onMain
            if (retained.isEmpty() || retained.any { it.entryId.isNullOrEmpty() || it.entryId !in references } ||
                startEntryId != null && retained.count { it.entryId == startEntryId } != 1) {
                connectionError = "This saved selection is unavailable. Choose Play available."; publish(); return@onMain
            }
            val start = startEntryId?.let { id -> retained.indexOfFirst { it.entryId == id } } ?: 0
            val request = {
                if (current()) connectionError = PlaybackService.replaceLocal(retained, eligibility, references,
                    start, shuffle, context, ::current)
                publish()
            }
            if (controller == null) { localRequest = request; connectInternal() } else request()
        }
    }
    override fun enqueueLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, playNext: Boolean, isCurrent: () -> Boolean) =
        enqueueLocal(items, eligibility, mediaReferences, if (playNext) QueueIntentIntent.NEXT else QueueIntentIntent.LOW, isCurrent)
    override fun enqueueLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, intent: QueueIntentIntent, isCurrent: () -> Boolean) {
        val retained = items.map { it.copy(artists = it.artists.toList()) }
        val references = mediaReferences.toMap()
        val epoch = PlaybackRequests.epoch
        val queueEpoch = PlaybackRequests.queueEpoch
        onMain {
            val current = { epoch == PlaybackRequests.epoch && queueEpoch == PlaybackRequests.queueEpoch &&
                graph.localPlaybackIsCurrent(eligibility) && isCurrent() }
            if (!current()) return@onMain
            connectionError = PlaybackService.enqueueLocal(retained, eligibility, references, intent, current)
            if (connectionError == null) {
                feedbackCurrent = current
                feedback = PlaybackFeedback.from(PlaybackActionResult(PlaybackActionStatus.ADDED, retained.size,
                    requestedAction = PlaybackListAction.QUEUE_LOW), current)
            }
            publish()
        }
    }

    private fun binding(): LibraryPlaybackActionBinding? = coordinator?.captureBinding()
    private fun onlineKey(): PlaybackInteractionKey? {
        val owner = coordinator
        val client = graph.client
        val session = client?.currentSession()
        return actionKeys.current(if (!local && observing && owner != null && client != null && session != null)
            binding()?.let { listOf(PlaybackReference(owner), PlaybackReference(client), PlaybackReference(session), it, PlaybackRequests.epoch, generation) }
            else null)
    }
    override fun queueIntent(selection: LibraryPlaybackSelection, client: SpofifyClient,
        action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, expectedBinding: LibraryPlaybackActionBinding?,
        shuffle: Boolean?, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
        val owner = coordinator
        val session = client.currentSession()
        val captured = expectedBinding ?: binding()
        val epoch = PlaybackRequests.epoch
        val incarnation = generation
        val origin: () -> Boolean = if (action == PlaybackListAction.PLAY_NOW) explicitPlayOrigin?.invoke() ?: { true } else ({ true })
        fun current() = !local && isCurrent() && origin() && epoch == PlaybackRequests.epoch && incarnation == generation &&
            owner != null && owner === coordinator && client === graph.client && session != null && client.currentSession() === session &&
            captured != null && captured == binding() && (expectedKey == null || expectedKey === onlineKey())
        onMain {
            if (!current()) { onResult(PlaybackActionResult(PlaybackActionStatus.STALE)); return@onMain }
            val intent = when (action) {
                PlaybackListAction.PLAY_NOW -> "now"
                PlaybackListAction.PLAY_NEXT -> "next"
                PlaybackListAction.QUEUE_LOW -> "low"
                else -> "high"
            }
            if (shuffle != null && (intent != "now" || selection !is LibraryPlaybackSelection.Collection)) {
                onResult(PlaybackActionResult(PlaybackActionStatus.UNAVAILABLE)); return@onMain
            }
            owner!!.submit(LibraryPlaybackCommand.QueueIntent(intent, selection, shuffle),
                expectedStateVersion = captured!!.stateVersion, isCurrent = ::current, onResult = { receipt ->
                    onResult(libraryPlaybackActionResult(receipt, action))
                })
        }
    }
    override fun queueIntentOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
        action: PlaybackListAction, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
        if (local) {
            if (!isCurrent() || PlaybackService.interactionKey() !== expectedKey) {
                onResult(PlaybackActionResult(PlaybackActionStatus.STALE)); return
            }
            PlaybackService.localOccurrence(expectedKey, occurrenceId, action, isCurrent, onResult)
            publish(); return
        }
        val client = graph.client
        val key = remoteProjection()?.let { remoteUpcomingKey(it, occurrenceId) }
        if (client == null || key == null) { onResult(PlaybackActionResult(PlaybackActionStatus.STALE)); return }
        queueIntent(LibraryPlaybackSelection.Occurrence(key), client, action, expectedKey,
            state.value.queueIntentBinding, isCurrent = isCurrent, onResult = onResult)
    }
    override fun contextualOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
        isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) =
        queueIntentOccurrence(expectedKey, occurrenceId, PlaybackListAction.QUEUE_HIGH, isCurrent, onResult)
    override fun playNextOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
        isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) =
        queueIntentOccurrence(expectedKey, occurrenceId, PlaybackListAction.PLAY_NEXT, isCurrent, onResult)

    private fun submit(command: LibraryPlaybackCommand, expectedKey: PlaybackInteractionKey? = state.value.interactionKey,
        isCurrent: () -> Boolean = { true }): Boolean {
        val owner = coordinator ?: return false
        val captured = binding() ?: return false
        val epoch = PlaybackRequests.epoch
        val incarnation = generation
        return owner.submit(command, expectedStateVersion = captured.stateVersion, isCurrent = {
            !local && observing && isCurrent() && owner === coordinator && epoch == PlaybackRequests.epoch &&
                incarnation == generation && captured == binding() && (expectedKey == null || expectedKey === onlineKey())
        })
    }
    override fun removeUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, isCurrent: () -> Boolean) = onMainQueue {
        // Validate the rendered witness against live authority, not the last sampled UI state.
        if (!isCurrent() || expectedKey !== (if (local) PlaybackService.interactionKey() else onlineKey())) return@onMainQueue
        if (local) PlaybackService.removeQueued(expectedKey, occurrenceId, isCurrent)
        else remoteProjection()?.let { remoteUpcomingKey(it, occurrenceId) }?.let { submit(LibraryPlaybackCommand.Remove(it), expectedKey, isCurrent) }
        publish()
    }
    override fun moveUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, toIndex: Int,
        isCurrent: () -> Boolean) = onMainQueue {
        if (!isCurrent() || expectedKey !== (if (local) PlaybackService.interactionKey() else onlineKey())) return@onMainQueue
        if (local) PlaybackService.moveQueued(expectedKey, occurrenceId, toIndex)
        else remoteProjection()?.let { remoteMoveCommand(it, occurrenceId, toIndex) }?.let { submit(it, expectedKey, isCurrent) }
        publish()
    }
    override fun clearUpcoming() = onMainQueue {
        if (local) PlaybackService.clearQueued() else submit(LibraryPlaybackCommand.Clear)
        publish()
    }
    override fun undoQueueRemoval() = onMainQueue { if (local) PlaybackService.undoQueuedRemoval(); publish() }
    override fun selectOccurrence(occurrenceId: String) = onMainQueue {
        if (local) PlaybackService.queuedKey(occurrenceId)?.let { PlaybackService.localTransport(LocalPlaybackCommand.queue("jump", it)) }
        else remoteProjection()?.let { remoteUpcomingKey(it, occurrenceId) }?.let { submit(LibraryPlaybackCommand.Jump(it)) }
        publish()
    }
    override fun select(index: Int) = onMainQueue {
        if (local) PlaybackService.timelineKey(index)?.let { PlaybackService.localTransport(LocalPlaybackCommand.queue("jump", it)) }
        else remoteProjection()?.let { snapshot -> snapshot.queue.getOrNull(index)?.takeIf { index > snapshot.position } }
            ?.let { submit(LibraryPlaybackCommand.Jump(it.key)) }
        publish()
    }
    override fun toggle() {
        val origin = explicitPlayOrigin?.invoke() ?: { false }
        onMainQueue {
            if (!origin()) return@onMainQueue
            if (local) {
                latestLocalPlay.incrementAndGet()
                if (localRequest != null) {
                    localRequest = null
                    PlaybackService.localTransport(LocalPlaybackCommand.transport("pause"))
                    publish()
                    return@onMainQueue
                }
                if (PlaybackService.displayLocalSnapshot().playing) PlaybackService.localTransport(LocalPlaybackCommand.transport("pause"))
                else if (!PlaybackService.localTransport(LocalPlaybackCommand.transport("resume"), origin)) connectionError = "Playback unavailable."
            } else {
                val current = coordinator?.state?.value?.snapshot ?: return@onMainQueue
                submit(if (current.state.desiredPlaying) LibraryPlaybackCommand.Pause else LibraryPlaybackCommand.Resume, isCurrent = origin)
            }
            publish()
        }
    }
    override fun next() = transport("next", LibraryPlaybackCommand.Next)
    override fun previous() = transport("previous", LibraryPlaybackCommand.Previous)
    override fun next(expectedKey: PlaybackInteractionKey): Boolean = skip(expectedKey, true)
    override fun previous(expectedKey: PlaybackInteractionKey): Boolean = skip(expectedKey, false)
    private fun currentLocalTransportKey(): PlaybackInteractionKey? {
        val raw = PlaybackService.interactionKey()
        val connected = controller
        return transportKeys.current(if (observing && raw != null && connected != null)
            listOf(raw, generation, PlaybackReference(connected), PlaybackService.transportCapabilityRevision()) else null)
    }
    private fun skip(expectedKey: PlaybackInteractionKey, forward: Boolean): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || !observing || state.value.transportInteractionKey !== expectedKey) return false
        if (local) {
            if (currentLocalTransportKey() !== expectedKey) return false
            return PlaybackService.interactionKey()?.let { raw -> PlaybackService.skipPrepared(raw, forward) {
                currentLocalTransportKey() === expectedKey
            } } == true
        }
        return submit(if (forward) LibraryPlaybackCommand.Next else LibraryPlaybackCommand.Previous)
    }
    private fun transport(localType: String, command: LibraryPlaybackCommand) = onMainQueue {
        if (local) PlaybackService.localTransport(LocalPlaybackCommand.transport(localType)) else submit(command)
        publish()
    }
    override fun seekTo(positionMs: Long) = onMainQueue {
        val duration = state.value.durationMs.takeIf { it > 0 } ?: 604_800_000L
        val target = positionMs.coerceIn(0, minOf(duration, 604_800_000L)) / 1000.0
        if (local) PlaybackService.localTransport(LocalPlaybackCommand.seek(target)) else submit(LibraryPlaybackCommand.Seek(target))
        publish()
    }
    override fun toggleShuffle() = onMainQueue {
        if (local) PlaybackService.localTransport(LocalPlaybackCommand.transport("shuffle"))
        else coordinator?.state?.value?.snapshot?.state?.let { submit(LibraryPlaybackCommand.SetShuffle(!it.shuffle)) }
        publish()
    }
    override fun cycleRepeat() = onMainQueue {
        if (local) PlaybackService.localTransport(LocalPlaybackCommand.transport("repeat"))
        else coordinator?.state?.value?.snapshot?.state?.let { submit(LibraryPlaybackCommand.SetRepeat(when (it.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" })) }
        publish()
    }
    override fun stop() {
        PlaybackRequests.invalidate()
        onMain {
            localRequest = null
            release()
            PlaybackService.stopActivePlayback()
            context.stopService(Intent(context, PlaybackService::class.java))
            mutableState.value = PlaybackState()
        }
    }
    private fun remoteProjection(): PlaybackSnapshot? = coordinator?.state?.value?.snapshot?.displayProjection()
    private fun publish() {
        if (feedbackCurrent?.invoke() != true) { feedback = null; feedbackCurrent = null }
        if (!local) {
            val snapshot = coordinator?.state?.value?.snapshot
            val projected = snapshot?.displayProjection()
            val key = onlineKey()
            val transport = transportKeys.current(if (observing && projected?.queue?.getOrNull(projected.position) != null) key else null)
            val directions = projected?.let(::remoteSkipCapabilities) ?: (false to false)
            mutableState.value = (projected?.let { remotePlaybackState(it, connectionError) } ?: PlaybackState(error = connectionError)).copy(
                playWhenReady = snapshot?.state?.desiredPlaying == true, interactionKey = key, transportInteractionKey = transport,
                canSkipNext = transport != null && directions.first, canSkipPrevious = transport != null && directions.second,
                canMoveUpcomingAbsolutely = key != null, queueIntentBinding = binding(), canQueueIntent = binding() != null, feedback = feedback)
            return
        }
        actionKeys.invalidate()
        val connected = controller ?: return
        val items = (0 until connected.mediaItemCount).mapNotNull { PlaybackMedia.libraryItem(connected.getMediaItemAt(it)) }
        val item = connected.currentMediaItem?.let(PlaybackMedia::libraryItem)
        val rawKey = PlaybackService.interactionKey()
        val transport = currentLocalTransportKey()
        mutableState.value = PlaybackState(item = item, isPlaying = connected.isPlaying,
            playWhenReady = connected.playWhenReady, isBuffering = connected.playbackState == Player.STATE_BUFFERING,
            positionMs = connected.currentPosition.coerceAtLeast(0),
            durationMs = if (connected.duration == C.TIME_UNSET) item?.durationMs ?: 0 else connected.duration.coerceAtLeast(0),
            shuffle = PlaybackService.displayLocalSnapshot().shuffle, repeatMode = connected.repeatMode,
            error = connectionError ?: connected.playerError?.let { "Saved audio is unavailable or could not be played." },
            queue = items, queueIndex = if (items.isEmpty()) -1 else connected.currentMediaItemIndex,
            upcoming = PlaybackService.upcomingEntries(), currentEntryId = PlaybackMedia.occurrence(connected.currentMediaItem),
            context = PlaybackService.queueContext(), canUndoQueueRemoval = PlaybackService.canUndoRemoval(),
            notice = PlaybackService.queueNotice(), feedback = feedback, interactionKey = rawKey,
            transportInteractionKey = transport, canMoveUpcomingAbsolutely = rawKey != null,
            canSkipNext = observing && PlaybackService.canSkipPrepared(true), canSkipPrevious = observing && PlaybackService.canSkipPrepared(false))
    }
    /** Capture before posting: queued callbacks cannot borrow a successor socket, queue or mode. */
    private fun onMainQueue(action: () -> Unit) {
        val wasLocal = local
        val captured = if (wasLocal) PlaybackService.interactionKey() else null
        val owner = coordinator
        val witness = if (wasLocal) null else binding()
        onMain {
            if (wasLocal != local) return@onMain
            if (wasLocal && PlaybackService.interactionKey() !== captured) return@onMain
            if (!wasLocal && (owner !== coordinator || witness != binding())) return@onMain
            action()
        }
    }
    private fun onMain(action: () -> Unit) {
        val epoch = PlaybackRequests.epoch
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else handler.post { if (epoch == PlaybackRequests.epoch) action() }
    }
}

internal fun libraryPlaybackActionResult(result: LibraryPlaybackResult?, action: PlaybackListAction): PlaybackActionResult =
    PlaybackActionResult(when {
        result == null -> PlaybackActionStatus.UNAVAILABLE
        result.outcome == "accepted" && result.placement in setOf("low", "high", "next") -> PlaybackActionStatus.ADDED
        result.outcome == "accepted" -> PlaybackActionStatus.NO_OP
        result.code in setOf("stale_state_version", "stale_revision", "superseded") -> PlaybackActionStatus.STALE
        result.code == "capacity" -> PlaybackActionStatus.FULL
        else -> PlaybackActionStatus.UNAVAILABLE
    }, acceptedCount = result?.acceptedCount ?: 0, requestedAction = action, serverResult = result)

/** A single anchored move, relative to the immutable pre-move upcoming traversal. */
internal fun remoteMoveCommand(snapshot: PlaybackSnapshot, occurrenceId: String, toIndex: Int): LibraryPlaybackCommand.Move? {
    val upcoming = snapshot.queue.drop(snapshot.position + 1)
    val from = upcoming.indexOfFirst { PlaybackSnapshotCodec.occurrenceId(it) == occurrenceId }
    if (from < 0 || toIndex !in upcoming.indices || from == toIndex) return null
    val without = upcoming.filterIndexed { index, _ -> index != from }
    return LibraryPlaybackCommand.Move(upcoming[from].key, if (toIndex == 0) null else without[toIndex - 1].key)
}

/** One-way display projection only. Never serialized, uploaded, prepared or used for authority. */
internal fun LibraryPlaybackSnapshot.displayProjection(): PlaybackSnapshot {
    fun LibraryPlaybackContext.project() = PlaybackContext(kind, id, name, revision ?: 0, fence.orEmpty())
    val facts = playback.takeIf { it.hasReportedPlayback && it.stateVersion == stateVersion && it.outputVersion == outputVersion }
    return PlaybackSnapshot(queue = state.queue.map { track -> PlaybackTrack(track.id, track.name,
        track.artists.map { PlaybackArtist(it.id.orEmpty(), it.name) }, track.albumName, track.albumId, track.duration,
        track.entryId.orEmpty(), track.targetId.orEmpty(), track.occurrenceId.orEmpty(), track.playbackContext?.project(), track.key, track.order) },
        position = state.position, context = state.context?.project(), shuffle = state.shuffle, repeat = state.repeat,
        elapsed = facts?.elapsed ?: state.elapsed, duration = facts?.duration ?: state.duration,
        playing = facts?.isPlaying == true, waiting = facts?.isBuffering == true, loading = facts?.isLoading == true,
        error = facts?.error ?: state.error, notice = state.notice)
}

/** Pure display conversion; this does not import a remote queue into local audio. */
internal fun remotePlaybackState(snapshot: PlaybackSnapshot, error: String? = null): PlaybackState {
    val items = snapshot.queue.map { track ->
        val item = PlaybackSnapshotCodec.libraryItem(track)
        val context = track.playbackContext ?: snapshot.context
        // Display identity only: derived paths are not manual entries or queue command keys.
        if (context?.kind == "derived" && track.targetId == context.id && track.entryId.isEmpty() && track.occurrenceId.isNotEmpty())
            item.copy(entryId = track.occurrenceId) else item
    }
    val current = snapshot.queue.getOrNull(snapshot.position)
    val context = current?.playbackContext ?: snapshot.context
    return PlaybackState(item = items.getOrNull(snapshot.position), isPlaying = snapshot.playing,
        playWhenReady = snapshot.playing, isBuffering = snapshot.waiting || snapshot.loading,
        positionMs = (snapshot.elapsed * 1000).roundToLong(), durationMs = (snapshot.duration * 1000).roundToLong(),
        shuffle = snapshot.shuffle, repeatMode = when (snapshot.repeat) { "all" -> Player.REPEAT_MODE_ALL; "one" -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF },
        error = error ?: when (snapshot.error) { "blocked" -> "Playback needs a tap on the selected device."; "media" -> "The selected device could not play this track."; else -> null },
        queue = items, queueIndex = snapshot.position,
        upcoming = snapshot.queue.mapIndexedNotNull { index, track -> if (index <= snapshot.position) null else QueueEntry(PlaybackSnapshotCodec.occurrenceId(track), items[index], index) },
        currentEntryId = current?.let(PlaybackSnapshotCodec::occurrenceId), context = context?.let { LibraryItem(it.kind, it.id, it.name) }, remoteSnapshot = snapshot)
}
internal fun remoteUpcomingKey(snapshot: PlaybackSnapshot, occurrenceId: String): Long? =
    snapshot.queue.drop(snapshot.position + 1).firstOrNull { PlaybackSnapshotCodec.occurrenceId(it) == occurrenceId }?.key
