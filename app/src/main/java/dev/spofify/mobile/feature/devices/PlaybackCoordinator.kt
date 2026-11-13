package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.LibraryPlaybackExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Presentation only, never output authority. */
enum class PlaybackConnectionRecovery { NONE, VERIFICATION_NEEDED, AUTH_REQUIRED, OWNER_ENDED, UNSUPPORTED }
enum class PlaybackDevicesMessagePurpose { GUIDANCE, PROGRESS, INFORMATION }
data class PlaybackDeviceSelection(val deviceId: String, val token: Long)

/** One live socket incarnation plus its structural state. Never persist this action witness. */
data class LibraryPlaybackActionBinding internal constructor(
    val sessionId: String, val connectionId: String, val stateVersion: Long, val outputVersion: Long,
    internal val incarnation: Long,
    internal val selectionRevision: Long = 0,
)

data class PlaybackDevicesState(
    val deviceId: String = "", val connectionId: String = "",
    val snapshot: LibraryPlaybackSnapshot? = null,
    val connected: Boolean = false, val connecting: Boolean = false,
    val needsTap: Boolean = false, val message: String = "",
    val recovery: PlaybackConnectionRecovery = PlaybackConnectionRecovery.NONE,
    val pendingSelection: PlaybackDeviceSelection? = null,
    val messagePurpose: PlaybackDevicesMessagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE,
) {
    val selectedDeviceId: String get() = snapshot?.selectedDeviceId.orEmpty()
    val devices: List<LibraryPlaybackDevice> get() = snapshot?.devices.orEmpty()
    val retryAvailable: Boolean get() = recovery == PlaybackConnectionRecovery.VERIFICATION_NEEDED && !connecting
    val canQueueIntent: Boolean get() = connected && snapshot != null
    /** Logical group equality is NOT local execution authority. */
    val isSelectedConnection: Boolean get() = connected && connectionId.isNotEmpty() && snapshot?.selectedConnectionId == connectionId
}

/**
 * The authenticated application owns exactly one coordinator. All entry points run on its
 * serial dispatcher. The server owns the online queue; this class never resolves, uploads,
 * restores or locally edits it. Socket loss stops execution before any reconnect admission.
 */
class PlaybackCoordinator(
    private val client: SpofifyClient,
    endpoint: NativePlaybackEndpoint,
    scope: CoroutineScope,
    private val name: String = "Android phone",
    private val identity: (() -> PlaybackDeviceIdentityStore?)? = null,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val mediaEventUptimeMs: () -> Long = { android.os.SystemClock.uptimeMillis() },
    private val openTransport: suspend (CoroutineScope, String, String, Boolean) -> LibraryPlaybackTransport = { owner, displayName, id, capable ->
        client.openLibraryPlayback(owner, displayName, id, capable)
    },
    private val authenticationIdentity: () -> Any? = { client.validatedSessionIdentity() },
    private val deviceIdentity: (() -> Pair<String, String>?)? = null,
) {
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(PlaybackDevicesState())
    val state: StateFlow<PlaybackDevicesState> = mutableState.asStateFlow()
    private var socket: LibraryPlaybackTransport? = null
    private var connectionJob: Job? = null
    private var closed = false
    private var incarnation = 0L
    private var acceptedAuthentication: Any? = null
    private var selectionToken = 0L
    private var structuralRevision = 0L
    private var selectionRevision = 0L
    private var playbackHighWater = -1L
    private var futurePlayback: LibraryPlaybackStatus? = null
    private val pending = linkedMapOf<String, Pending>()
    private data class Pending(val result: (LibraryPlaybackResult?) -> Unit, val timeout: Job,
        val userAction: LibraryPlaybackUserAction?, var acceptedPending: Boolean = false)

    private val executor = LibraryPlaybackExecutor(endpoint, work, clockMs,
        send = { value -> if (connectionCurrent()) socket?.send(value) else null },
        isCurrent = { connectionCurrent() })
    private val cancellationGuard = work.coroutineContext[Job]!!.invokeOnCompletion {
        socket?.close()
        executor.disconnected()
    }

    internal val interactionVersion: Triple<Long, Long, Long> get() = Triple(incarnation, structuralRevision, selectionRevision)
    internal val transportSelectionVersion: Long get() = selectionRevision
    internal var transportCapabilityVersion: Long = 0
        private set
    internal var interactionChangedUptime: Long = mediaEventUptimeMs()
        private set

    fun captureBinding(): LibraryPlaybackActionBinding? {
        val value = state.value
        val snapshot = value.snapshot ?: return null
        if (!connectionCurrent()) return null
        return LibraryPlaybackActionBinding(snapshot.sessionId, value.connectionId, snapshot.stateVersion, snapshot.outputVersion, incarnation, selectionRevision)
    }

    fun isCurrent(binding: LibraryPlaybackActionBinding): Boolean = captureBinding() == binding

    /** Service destruction is authority loss, not an implicit future resume request. */
    fun setServiceActive(active: Boolean) {
        if (closed) return
        if (active) retry()
        else disconnect(PlaybackConnectionRecovery.VERIFICATION_NEEDED, "Playback disconnected.")
    }

    /** Admission/reconnection has no musical side effect and never replays a command. */
    fun retry() {
        if (closed || !work.isActive || connectionJob?.isActive == true || state.value.connected) return
        val auth = authenticationIdentity()
        if (auth == null || client.isOffline) {
            mutableState.value = state.value.copy(recovery = PlaybackConnectionRecovery.AUTH_REQUIRED, message = "Sign in to connect playback.")
            return
        }
        val device = try {
            deviceIdentity?.invoke() ?: identity?.invoke()?.readOrCreate(name)?.let { it.stableDeviceId to it.name }
        } catch (_: Exception) { null }
        if (device == null) {
            mutableState.value = state.value.copy(recovery = PlaybackConnectionRecovery.VERIFICATION_NEEDED, message = "Playback identity is unavailable.")
            return
        }
        val life = ++incarnation
        acceptedAuthentication = auth
        mutableState.value = PlaybackDevicesState(deviceId = device.first, connecting = true)
        connectionJob = work.launch {
            var opened: LibraryPlaybackTransport? = null
            try {
                opened = withTimeout(15_000) { openTransport(work, device.second, device.first, true) }
                if (!admissionCurrent(life, auth)) return@launch
                socket = opened
                // receiveAsFlow is a single-consumer channel: first() consumes exactly one
                // frame, leaving any immediately queued instruction for the same owner.
                when (val first = withTimeout(10_000) { opened.events.first() }) {
                    is LibraryPlaybackEvent.Message -> {
                        if (!admissionCurrent(life, auth) || !opened.isCurrent) throw CancellationException()
                        require(first.message.type == "snapshot")
                        acceptFirst(first.message, device.first)
                    }
                    is LibraryPlaybackEvent.Closed -> throw SocketEnded(first.reason)
                }
                opened.events.collect { event ->
                    if (!admissionCurrent(life, auth)) throw CancellationException()
                    when (event) {
                        is LibraryPlaybackEvent.Message -> accept(event.message)
                        is LibraryPlaybackEvent.Closed -> throw SocketEnded(event.reason)
                    }
                }
                if (admissionCurrent(life, auth)) disconnect(PlaybackConnectionRecovery.VERIFICATION_NEEDED, "Playback disconnected.", cancelCollector = false)
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) failed(life, error) else throw error
            } catch (error: Exception) { failed(life, error) }
            finally {
                opened?.close()
                if (life == incarnation) {
                    val authRequired = authenticationIdentity() != auth
                    disconnect(if (authRequired) PlaybackConnectionRecovery.AUTH_REQUIRED else PlaybackConnectionRecovery.VERIFICATION_NEEDED,
                        if (authRequired) "Sign in to connect playback." else "Playback disconnected.", cancelCollector = false)
                }
            }
        }
    }

    private class SocketEnded(val reason: LibraryPlaybackEvent.Reason) : RuntimeException()

    private fun admissionCurrent(life: Long, auth: Any): Boolean = !closed && work.isActive &&
        life == incarnation && !client.isOffline && authenticationIdentity() == auth

    private fun connectionCurrent(): Boolean = state.value.connected && socket?.isCurrent == true && acceptedAuthentication?.let {
        admissionCurrent(incarnation, it)
    } == true

    private fun acceptFirst(message: LibraryPlaybackServerMessage, requestedId: String) {
        val snapshot = requireNotNull(message.snapshot)
        val connection = requireNotNull(message.connectionId)
        require(message.deviceId == requestedId && message.sessionId == snapshot.sessionId && connection.isNotEmpty())
        playbackHighWater = snapshot.playback.playbackSequence
        futurePlayback = null
        executor.connected(message.sessionId, connection)
        mutableState.value = PlaybackDevicesState(deviceId = requestedId, connectionId = connection, snapshot = snapshot, connected = true)
        changedStructure(null, snapshot)
        executor.update(snapshot)
        persistOwnName(snapshot)
    }

    private fun accept(message: LibraryPlaybackServerMessage) {
        if (!connectionCurrent()) throw SocketEnded(LibraryPlaybackEvent.Reason.AUTHENTICATION)
        val old = state.value.snapshot ?: error("Missing admission")
        require(message.sessionId == old.sessionId && message.type != "snapshot")
        when (message.type) {
            "state" -> {
                val next = requireNotNull(message.snapshot)
                require(next.sessionId == old.sessionId && next.stateVersion >= old.stateVersion && next.outputVersion >= old.outputVersion)
                if (next.stateVersion == old.stateVersion) require(structure(old) == structure(next))
                // A retained older scalar must not replace a newer quiet observation.
                val anchor = listOfNotNull(futurePlayback, old.playback).filter {
                    it.stateVersion == next.stateVersion && it.outputVersion == next.outputVersion &&
                        it.eventId == next.state.occurrence?.eventId.orEmpty()
                }.maxByOrNull { it.playbackSequence }
                val current = if (anchor != null && anchor.playbackSequence > next.playback.playbackSequence)
                    next.copy(playback = anchor) else next
                playbackHighWater = maxOf(playbackHighWater, current.playback.playbackSequence)
                if (futurePlayback?.stateVersion?.let { it <= next.stateVersion } == true) futurePlayback = null
                changedStructure(old, current)
                mutableState.value = state.value.copy(snapshot = current, needsTap = current.playback.error == "blocked", message = "", recovery = PlaybackConnectionRecovery.NONE)
                executor.update(current)
                persistOwnName(current)
            }
            "playback" -> applyPlayback(requireNotNull(message.playback))
            "instruction" -> {
                val instruction = requireNotNull(message.instruction)
                val request = instruction.commandId?.let(pending::get)
                // IDs are connection-scoped, not globally unique. Only THIS socket's
                // accepted/pending result witnesses a local action. The server orders it
                // before its instructions and supersession before replacement work.
                if (request?.acceptedPending == true) {
                    val life = incarnation
                    request.userAction?.bind(instruction) {
                        life == incarnation && connectionCurrent() &&
                            pending[instruction.commandId] === request
                    }
                }
                executor.instruction(instruction)
            }
            "lease" -> executor.lease(requireNotNull(message.lease))
            "result" -> {
                val result = requireNotNull(message.result)
                pending[result.id]?.let { request ->
                    request.acceptedPending = result.outcome == "accepted" && result.execution == "pending" && !result.final
                    if (result.final) pending.remove(result.id)?.let { it.timeout.cancel(); it.result(result) }
                }
            }
            "error" -> {
                // Fixed UI text only. The remote message is never rendered/logged.
                mutableState.value = state.value.copy(message = "Playback request was refused.")
            }
            else -> error("Unknown playback envelope")
        }
    }

    private fun applyPlayback(value: LibraryPlaybackStatus) {
        if (value.playbackSequence <= playbackHighWater) return
        val snapshot = state.value.snapshot ?: return
        if (value.stateVersion < snapshot.stateVersion || value.outputVersion < snapshot.outputVersion) return
        playbackHighWater = value.playbackSequence
        if (value.stateVersion > snapshot.stateVersion || value.outputVersion > snapshot.outputVersion) {
            // One bounded future scalar, never an optimistic queue/current or output grant.
            futurePlayback = value
            return
        }
        if (value.outputVersion != snapshot.outputVersion || value.eventId != snapshot.state.occurrence?.eventId.orEmpty()) return
        mutableState.value = state.value.copy(snapshot = snapshot.copy(playback = value), needsTap = value.error == "blocked")
    }

    /** Scalar observation fields may change without a structural version, identities may not. */
    private fun structure(snapshot: LibraryPlaybackSnapshot): LibraryPlaybackSnapshot = snapshot.copy(
        state = snapshot.state.copy(elapsed = 0.0, duration = 0.0, isPlaying = false, isBuffering = false,
            isLoading = false, error = "", occurrence = snapshot.state.occurrence?.copy(progress = 0.0, reported = false)),
        playback = LibraryPlaybackStatus(snapshot.outputVersion, snapshot.stateVersion, 0,
            snapshot.state.occurrence?.eventId.orEmpty(), false, 0.0, 0.0, false, false, false, ""),
    )

    private fun changedStructure(before: LibraryPlaybackSnapshot?, after: LibraryPlaybackSnapshot) {
        if (before == null || before.stateVersion != after.stateVersion || before.outputVersion != after.outputVersion) {
            structuralRevision++
            interactionChangedUptime = mediaEventUptimeMs()
        }
        if (before == null || before.selectedConnectionId != after.selectedConnectionId || before.transfer != after.transfer) selectionRevision++
        val previous = before?.state
        if (previous == null || previous.position != after.state.position || previous.repeat != after.state.repeat || previous.queue != after.state.queue) transportCapabilityVersion++
    }

    private fun persistOwnName(snapshot: LibraryPlaybackSnapshot) {
        snapshot.devices.firstOrNull { it.deviceId == state.value.deviceId }?.let { own ->
            try { identity?.invoke()?.confirmName(own.name, name) } catch (_: Exception) { /* Playback does not depend on preference persistence. */ }
        }
    }

    /** A request is enqueued at most once. An unknown outcome is completed as unknown, never retried. */
    fun submit(
        command: LibraryPlaybackCommand,
        expectedStateVersion: Long? = state.value.snapshot?.stateVersion,
        isCurrent: () -> Boolean = { true },
        userAction: LibraryPlaybackUserAction? = null,
        onResult: (LibraryPlaybackResult?) -> Unit = {},
    ): Boolean {
        if (!isCurrent()) { onResult(null); return false }
        val admitted = connectionCurrent() && pending.size < 128
        if (command == LibraryPlaybackCommand.Pause || command == LibraryPlaybackCommand.Close) {
            // Local cancellation precedes the network wait but never invents a release ACK.
            executor.interruptForStopIntent()
            selectionRevision++
            interactionChangedUptime = mediaEventUptimeMs()
        }
        // Stop/new execution/output intents revoke an old OS allowance even when
        // admission is full. Queue-only edits must preserve a still-current Resume:
        // any actual server supersession (including a replaced transfer) retires its
        // pending request in socket order instead. Staged-next invalidation is separate.
        val supersedesAction = when (command) {
            is LibraryPlaybackCommand.QueueIntent -> command.intent == "now"
            is LibraryPlaybackCommand.Seek, is LibraryPlaybackCommand.Jump, is LibraryPlaybackCommand.Select,
            LibraryPlaybackCommand.Deselect, LibraryPlaybackCommand.Pause, LibraryPlaybackCommand.Close,
            LibraryPlaybackCommand.Resume, LibraryPlaybackCommand.Retry, LibraryPlaybackCommand.Next,
            LibraryPlaybackCommand.Previous -> true
            else -> false
        }
        if (supersedesAction) pending.values.forEach { it.userAction?.revoke() }
        if (!admitted) { onResult(null); return false }
        if (command !is LibraryPlaybackCommand.Rename) executor.discardStagedNext()
        val receipt = socket?.send(LibraryPlaybackClientMessage.Command(command, expectedStateVersion))
        val id = receipt?.commandId
        if (id == null) { onResult(null); return false }
        val life = incarnation
        val timeout = work.launch {
            delay(20_000)
            if (life == incarnation) pending.remove(id)?.result?.invoke(null)
        }
        pending[id] = Pending(onResult, timeout, userAction)
        return true
    }

    fun queueIntent(intent: String, selection: LibraryPlaybackSelection, shuffle: Boolean? = null,
        isCurrent: () -> Boolean = { true }, onResult: (LibraryPlaybackResult?) -> Unit = {}): Boolean =
        submit(LibraryPlaybackCommand.QueueIntent(intent, selection, shuffle), isCurrent = isCurrent, onResult = onResult)

    fun select(deviceId: String) {
        if (state.value.devices.none { it.deviceId == deviceId && it.available }) return
        val selection = PlaybackDeviceSelection(deviceId, ++selectionToken)
        selectionRevision++ // Same-target ABA is still an explicit target change.
        interactionChangedUptime = mediaEventUptimeMs()
        mutableState.value = state.value.copy(pendingSelection = selection)
        submit(LibraryPlaybackCommand.Select(deviceId), expectedStateVersion = null) {
            if (state.value.pendingSelection == selection) mutableState.value = state.value.copy(pendingSelection = null)
        }
    }

    fun rename(deviceId: String, name: String) {
        submit(LibraryPlaybackCommand.Rename(name, deviceId), expectedStateVersion = null)
    }

    fun continuePlaybackHere() {
        // Retry targets existing selection; it does not steal another device's output.
        if (state.value.isSelectedConnection && state.value.needsTap) submit(LibraryPlaybackCommand.Retry)
    }

    private fun failed(life: Long, error: Exception) {
        if (life != incarnation || closed) return
        val authRequired = authenticationIdentity() == null || error is SocketEnded && error.reason == LibraryPlaybackEvent.Reason.AUTHENTICATION
        disconnect(if (authRequired) PlaybackConnectionRecovery.AUTH_REQUIRED else PlaybackConnectionRecovery.VERIFICATION_NEEDED,
            if (authRequired) "Sign in to connect playback." else "Playback disconnected.", cancelCollector = false)
    }

    private fun disconnect(recovery: PlaybackConnectionRecovery, message: String, cancelCollector: Boolean = true) {
        ++incarnation
        acceptedAuthentication = null
        executor.disconnected() // Pause/cancel synchronously, before transport cleanup or callbacks.
        socket?.close(); socket = null
        if (cancelCollector) connectionJob?.cancel()
        connectionJob = null
        futurePlayback = null; playbackHighWater = -1
        val callbacks = pending.values.toList(); pending.clear()
        callbacks.forEach { it.timeout.cancel(); it.result(null) }
        structuralRevision++; selectionRevision++
        interactionChangedUptime = mediaEventUptimeMs()
        mutableState.value = PlaybackDevicesState(message = message, recovery = recovery)
    }

    fun authLost() = disconnect(PlaybackConnectionRecovery.AUTH_REQUIRED, "Sign in to connect playback.")

    fun close() {
        if (!closed) {
            disconnect(PlaybackConnectionRecovery.OWNER_ENDED, "")
            closed = true
            cancellationGuard.dispose()
            work.cancel()
        }
        // Socket close is not release proof; the server owns any finite lease drain.
    }
}
