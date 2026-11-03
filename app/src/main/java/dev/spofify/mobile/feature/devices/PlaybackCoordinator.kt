package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Remote display is NOT a local audible traversal and must never feed local history. */
data class PlaybackDevicesState(
    val deviceId: String = "", val view: PlaybackDevicesView? = null,
    val selectedDeviceId: String = "", val selectionUnconfirmed: Boolean = false,
    val connected: Boolean = false, val connecting: Boolean = false,
    val exclusiveOwner: Boolean = false, val needsTap: Boolean = false,
    val message: String = "Connect the playback service to use this phone as an output.",
) {
    val activeDeviceId: String get() = view?.activeDeviceId.orEmpty()
    val devices: List<PlaybackDevice> get() = view?.devices.orEmpty()
    val quarantineMs: Long get() = view?.quarantineMs ?: 0
}

/**
 * One instance per authenticated application/service, never remember{} inside a screen.
 * Caller scope and all API invocations must use one serial dispatcher (normally Main.immediate).
 * The service must remain alive while active; setServiceActive(false) BEFORE destroying output.
 * UI presence alone is not evidence that a background phone can receive a handoff.
 */
class PlaybackCoordinator(
    private val api: PlaybackDevicesProtocol,
    private val endpoint: NativePlaybackEndpoint,
    private val scope: CoroutineScope,
    private val name: String = "Android phone",
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(PlaybackDevicesState())
    val state: StateFlow<PlaybackDevicesState> = mutableState.asStateFlow()
    private val transport = Mutex()
    private var serviceActive = false
    private var managedOutput = false
    private var closed = false
    private var lifecycle = 0L
    private var generation = 0L
    private var epoch = 0L
    private var sequence = 0L
    private var leaseDeadline = 0L
    private var released = false
    private var activatedEpoch = 0L
    private var activating = false
    private var recoverPaused = true
    private var uncertainConnection = false
    private var heartbeat: Job? = null
    private var leaseTimer: Job? = null
    private var selectionJob: Job? = null
    private var selectionVersion = 0L
    private var intent: Selection? = null
    private var pendingCommand: String? = null
    private var interruptPending = false
    private val deliveries = LinkedHashSet<String>()
    private val acks = ArrayList<PlaybackAck>()
    private var ackState: PlaybackSnapshot? = null
    private data class Selection(val target: String, val version: Long)

    // A sibling of work, NOT its completion handler: cancellation cleanup starts even if
    // another work child is stuck in NonCancellable. Uses the endpoint's serial dispatcher.
    private val cancellationGuard = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally {
            withContext(NonCancellable) {
                if (managedOutput) {
                    lifecycle++; generation++; leaseDeadline = 0; released = true
                    endpoint.suspendOutput(); managedOutput = false; serviceActive = false
                    mutableState.value = state.value.copy(connected = false, exclusiveOwner = false,
                        message = "Playback service stopped. Reconnect and press Play explicitly.")
                }
            }
        }
    }

    /** For endpoint/local Media3 guards. A remote display never grants this authority. */
    fun currentAuthority(): PlaybackAuthority? = if (activatedEpoch == epoch) executionAuthority() else null
    private fun executionAuthority(): PlaybackAuthority? {
        if (!authorized()) return null
        val e = epoch; val g = generation; val life = lifecycle
        return PlaybackAuthority(e, g) { life == lifecycle && e == epoch && g == generation && authorized() }
    }
    private fun authorized() = work.isActive && !closed && serviceActive && state.value.connected && !released &&
        state.value.deviceId.isNotEmpty() && state.value.activeDeviceId == state.value.deviceId && clockMs() < leaseDeadline

    fun setServiceActive(active: Boolean) {
        if (closed || active == serviceActive) return
        if (active && !work.isActive) { message("The playback owner scope has ended. Recreate the service connection."); return }
        if (active && !endpoint.supportsExactHandoff) {
            mutableState.value = state.value.copy(message = "This playback engine cannot yet transfer the complete prepared queue safely.")
            return
        }
        serviceActive = active
        if (active) {
            managedOutput = true
            fenceLocal()
            heartbeat = work.launch {
                transport.withLock { exchange { register() } }
                while (isActive && serviceActive && !closed) {
                    transport.withLock {
                        if (intent == null && state.value.deviceId.isNotEmpty()) exchange { poll() }
                    }
                    delay(1000)
                }
            }
        } else {
            val oldId = state.value.deviceId
            lifecycle++; fenceLocal(); managedOutput = false; work.coroutineContext.cancelChildren(); heartbeat = null
            selectionJob?.cancel(); intent = null; acks.clear(); ackState = null; deliveries.clear(); pendingCommand = null
            mutableState.value = PlaybackDevicesState(message = "Playback service is not connected. Reconnecting never starts audio automatically.")
            if (oldId.isNotEmpty()) scope.launch { transport.withLock { try { withTimeout(4000) { api.leave(oldId) } } catch (_: Exception) { /* Single attempt only. */ } } }
        }
    }

    private fun fenceLocal() {
        generation++; leaseDeadline = 0; released = true; activatedEpoch = 0; recoverPaused = true
        leaseTimer?.cancel(); if (managedOutput) endpoint.suspendOutput()
        mutableState.value = state.value.copy(exclusiveOwner = false)
    }

    /** On sign-out/account/session replacement call BEFORE installing a new coordinator/client. */
    fun authLost() {
        setServiceActive(false)
        lifecycle++; fenceLocal(); work.coroutineContext.cancelChildren()
        mutableState.value = PlaybackDevicesState(message = "Sign in again to connect your players.")
    }
    fun close() { if (!closed) { setServiceActive(false); closed = true; lifecycle++; work.cancel(); cancellationGuard.cancel(); fenceLocal() } }

    fun retry() {
        if (closed) return
        if (!serviceActive) {
            // Authenticated READ-ONLY controller: browsing does not advertise a physical phone.
            val life = lifecycle
            work.launch {
                transport.withLock {
                    try {
                        val view = withTimeout(4000) { api.view() }.validated().copy(directives = emptyList())
                        if (life == lifecycle && !closed && work.isActive && !serviceActive) mutableState.value = PlaybackDevicesState(
                            view = view, connected = true,
                            message = "Read-only devices. Native handoff requires an exact, authority-fenced playback service.")
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        if (life == lifecycle && !closed && work.isActive) message("Could not verify playback devices. Sign in or retry connection.")
                    }
                }
            }
            return
        }
        work.launch { transport.withLock { exchange { if (state.value.deviceId.isEmpty()) register() else poll() } } }
    }

    /** Always responsive: one in-flight selection plus ONLY the latest unsent choice. */
    fun select(targetDeviceId: String) {
        if (!serviceActive || closed || state.value.deviceId.isEmpty()) return
        if (state.value.devices.none { it.deviceId == targetDeviceId && it.available }) {
            message("That player is unavailable. Open Spofify on it and choose again."); return
        }
        val selection = Selection(targetDeviceId, ++selectionVersion)
        // A fresh explicit output choice may authorize a NEW transfer, never an old reconnect.
        uncertainConnection = false
        intent = selection
        pendingCommand = null; interruptPending = false
        if (targetDeviceId != state.value.deviceId && activating) {
            // Cancel only an unfinished destination start. A healthy source must remain able
            // to execute and ACK the server's release at its exact final position.
            generation++; endpoint.suspendOutput()
        }
        mutableState.value = state.value.copy(selectedDeviceId = targetDeviceId, selectionUnconfirmed = true,
            message = "Selecting output. You can choose another device while waiting.")
        if (selectionJob?.isActive == true) return
        selectionJob = work.launch {
            while (intent != null && serviceActive && !closed) {
                transport.withLock {
                    val latest = intent ?: return@withLock
                    val life = lifecycle
                    exchange {
                        request { api.transfer(state.value.deviceId, epoch, nonce(), latest.target, latest.version) }
                    }
                    if (life == lifecycle && intent == latest) {
                        intent = null
                        mutableState.value = state.value.copy(selectionUnconfirmed = false,
                            selectedDeviceId = state.value.view?.transfer?.targetDeviceId.orEmpty())
                    }
                }
            }
        }
    }

    fun rename(deviceId: String, name: String) {
        if (closed) return
        if (!validPlaybackDeviceName(name.trim())) { message("Use a device name of 1–64 characters."); return }
        if (!validPlaybackProtocolId(deviceId)) return
        if (!serviceActive) {
            // Rename is account-scoped presentation only; unlike controls it needs no fake receiver.
            val life = lifecycle
            work.launch { transport.withLock {
                try {
                    val view = withTimeout(4000) { api.rename(deviceId, name.trim()) }.validated().copy(directives = emptyList())
                    if (life == lifecycle && !closed && work.isActive && !serviceActive) mutableState.value = state.value.copy(view = view, message = "Device name saved. Native handoff remains unavailable on this phone.")
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    if (life == lifecycle && !closed && work.isActive) message("The name could not be confirmed. Refresh devices before trying again.")
                }
            } }
            return
        }
        work.launch { transport.withLock { exchange { request { api.rename(deviceId, name.trim()) } } } }
    }

    /** Route ALL app/notification actions here, not straight to a controller that bypasses ownership. */
    fun remoteCommand(command: PlaybackDeviceCommand) {
        if (!serviceActive || closed || state.value.deviceId.isEmpty()) return
        val interrupt = command.type in setOf("pause", "close")
        if (intent != null || state.value.view?.transfer != null || state.value.quarantineMs > 0) {
            message("Finish selecting a playback device before changing playback."); return
        }
        if (pendingCommand != null && (!interrupt || interruptPending)) { message("An action is awaiting confirmation. Pause or Stop can interrupt it."); return }
        if (interrupt) { generation++; endpoint.suspendOutput(); interruptPending = true }
        val id = nonce(); pendingCommand = id
        val version = selectionVersion
        work.launch {
            transport.withLock {
                if (version != selectionVersion) return@withLock
                exchange {
                    val authority = executionAuthority()
                    if (authority != null) {
                        if (!initializePaused(authority)) { pendingCommand = null; interruptPending = false; return@exchange }
                        val result = withTimeout(4000) { endpoint.execute(command, authority) }
                        if (!authority.isValid()) return@exchange
                        ackState = result.snapshot
                        if (result.status != "ok") message("Playback needs attention on this device. Retry explicitly.")
                        pendingCommand = null; interruptPending = false
                        poll()
                    } else if (state.value.activeDeviceId.isNotEmpty() && state.value.activeDeviceId != state.value.deviceId) {
                        request { api.command(state.value.deviceId, epoch, id, command) }
                    } else {
                        pendingCommand = null; message("Choose an available output before pressing Play.")
                    }
                }
            }
        }
    }
    fun continuePlaybackHere() { if (state.value.needsTap) remoteCommand(PlaybackDeviceCommand.transport("retry")) }

    private suspend fun register() {
        mutableState.value = state.value.copy(connecting = true)
        request(registration = true) { api.register(name) }
    }
    private suspend fun poll() {
        val id = state.value.deviceId
        if (id.isEmpty()) return
        val pending = acks.toList(); acks.clear()
        val final = ackState; ackState = null
        val snapshot = if (state.value.activeDeviceId == id && (authorized() && activatedEpoch == epoch || final != null)) final ?: endpoint.exportSnapshot().validated() else null
        request { api.poll(id, epoch, if (snapshot != null) ++sequence else null, snapshot, pending) }
    }
    private suspend fun request(registration: Boolean = false, call: suspend () -> PlaybackDevicesView) {
        if (closed || !serviceActive) return
        val life = lifecycle; val sent = clockMs()
        val view = withTimeout(4000) { call() }.validated()
        if (life != lifecycle || closed || !serviceActive || !work.isActive) return
        consume(view, sent, registration)
    }
    private suspend fun consume(view: PlaybackDevicesView, sent: Long, registration: Boolean = false) {
        if (!registration && view.epoch < epoch) return
        if (!registration && view.epoch == epoch && view.stateSeq < (state.value.view?.stateSeq ?: 0)) return
        val id = if (registration) view.deviceId else state.value.deviceId
        require(validPlaybackProtocolId(id) && view.devices.any { it.deviceId == id })
        val previous = state.value.view
        val changed = view.epoch != epoch
        if (changed || view.activeDeviceId != id) {
            fenceLocal()
            acks.clear(); ackState = null
        }
        if (registration) {
            deliveries.clear(); acks.clear(); ackState = null; selectionVersion = 0
            sequence = 0; activatedEpoch = 0; released = true; recoverPaused = true
        }
        epoch = view.epoch
        sequence = maxOf(if (changed) 0 else sequence, view.stateSeq)
        // Only owner polling/registration/activation grants authority; GET/rename/command responses
        // may expose remaining lease but never extend it beyond the request's conservative sent time.
        leaseDeadline = if (view.activeDeviceId == id) sent + (view.leaseMs - 2000).coerceAtLeast(0) else 0
        if (view.activeDeviceId == id && (changed || registration || !state.value.connected) && view.directives.none { it.type == "release" }) released = false
        val outcome = view.commands.find { it.commandId == pendingCommand }
        if (outcome != null && outcome.status != "pending") { pendingCommand = null; interruptPending = false }
        val connectedMessage = when {
            view.quarantineMs > 0 -> "Waiting for previous playback permission to expire. The selected output will be prepared paused."
            view.transfer != null -> "Switching playback; waiting for the players to confirm."
            view.activeDeviceId.isEmpty() -> "Choose an output. Reconnecting never starts audio automatically."
            outcome?.status in setOf("timeout", "cancelled", "rejected", "media") -> "The action could not be confirmed. It was not replayed."
            else -> ""
        }
        mutableState.value = PlaybackDevicesState(id, view,
            intent?.target ?: view.transfer?.targetDeviceId.orEmpty(), intent != null, true, false,
            authorizedFor(id, view), view.activeDeviceId == id && view.state?.error == "blocked", connectedMessage)
        leaseTimer?.cancel()
        if (view.activeDeviceId == id) leaseTimer = work.launch {
            while (clockMs() < leaseDeadline) delay((leaseDeadline - clockMs()).coerceAtLeast(1))
            if (epoch == view.epoch && state.value.activeDeviceId == id) {
                uncertainConnection = true
                fenceLocal()
                mutableState.value = state.value.copy(connected = false, message = "Playback permission expired. Reconnect and press Play explicitly.")
            }
        }
        // A non-directive recovery NEVER imports playing state. It stays silent until explicit Play.
        if (previous != null && changed && view.directives.none { it.type == "activate" }) recoverPaused = true
        for (directive in view.directives) runDirective(directive)
    }
    private fun authorizedFor(id: String, view: PlaybackDevicesView) = view.activeDeviceId == id && !released && clockMs() < leaseDeadline && activatedEpoch == view.epoch

    private suspend fun runDirective(d: PlaybackDirective) {
        if (!deliveries.add(d.deliveryId)) return
        while (deliveries.size > 256) deliveries.remove(deliveries.first())
        if (d.epoch != epoch) return
        if (d.type == "prepare") {
            val valid = try { withTimeout(4000) { endpoint.prepare((d.state ?: PlaybackSnapshot()).validated()) } } catch (_: Exception) { false }
            if (d.epoch == epoch) acks += PlaybackAck(d.deliveryId, d.epoch, if (valid) "ok" else "rejected")
            return
        }
        val authority = executionAuthority()
        if (authority == null) return // Cannot ACK execution without actual owner state. Server fences on timeout.
        val result = when (d.type) {
            "release" -> {
                // Capture final position/intent BEFORE pause. Preserve playing=true in the ACK snapshot
                // so the destination can resume intentional playback only after the acknowledged stop.
                val final = if (activatedEpoch == epoch) endpoint.exportSnapshot().validated()
                    else (state.value.view?.state ?: PlaybackSnapshot()).paused()
                generation++; endpoint.suspendOutput(); released = true; activatedEpoch = 0
                PlaybackEndpointResult("ok", final)
            }
            "activate" -> {
                if (intent != null && intent?.target != state.value.deviceId) {
                    endpoint.suspendOutput(); PlaybackEndpointResult("rejected", endpoint.exportSnapshot().paused())
                } else {
                    val snapshot = (d.state ?: PlaybackSnapshot()).validated()
                    // A normal handoff has an explicit source release. Recovery/no source is paused by server.
                    val safe = if (uncertainConnection || recoverPaused && state.value.view?.transfer?.sourceDeviceId.isNullOrEmpty()) snapshot.paused() else snapshot
                    activating = true
                    val activated = try { withTimeout(4000) { endpoint.activate(safe, authority) } }
                        finally { activating = false }
                    if (!authority.isValid()) return
                    activatedEpoch = epoch; recoverPaused = false
                    activated
                }
            }
            "command" -> {
                val command = PlaybackDeviceCommand.fromJson(requireNotNull(d.command))
                if (command.type in setOf("pause", "close")) { generation++; endpoint.suspendOutput() }
                val current = executionAuthority() ?: return
                if (!initializePaused(current)) return
                val executed = withTimeout(4000) { endpoint.execute(command, current) }
                if (!current.isValid()) return
                executed
            }
            else -> return
        }
        if (d.epoch != epoch) return
        acks += PlaybackAck(d.deliveryId, d.epoch, result.status); ackState = result.snapshot.validated()
        mutableState.value = state.value.copy(exclusiveOwner = authorizedFor(state.value.deviceId, state.value.view!!), needsTap = result.status == "blocked")
    }

    private suspend fun initializePaused(authority: PlaybackAuthority): Boolean {
        if (activatedEpoch == epoch) return true
        val recovered = withTimeout(4000) { endpoint.activate((state.value.view?.state ?: PlaybackSnapshot()).paused(), authority) }
        if (!authority.isValid()) return false
        require(!recovered.snapshot.playing) { "Paused recovery must remain silent" }
        if (recovered.status != "ok") { message("The accepted queue could not be prepared. Choose music explicitly."); return false }
        activatedEpoch = epoch
        return true
    }

    private suspend fun exchange(block: suspend () -> Unit) {
        if (closed || !serviceActive) return
        val life = lifecycle
        try { block() }
        catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            if (life == lifecycle && !closed && serviceActive) failed(error)
        }
        catch (error: Exception) { if (life == lifecycle && !closed && serviceActive) failed(error) }
    }
    private suspend fun failed(error: Exception) {
        // Unknown writes are NOT replayed. Stop buffered output immediately, not merely remote UI state.
        uncertainConnection = true
        fenceLocal(); acks.clear(); ackState = null; pendingCommand = null; interruptPending = false
        mutableState.value = state.value.copy(connected = false, connecting = false, exclusiveOwner = false,
            message = "Connection or playback changed. The action was not replayed. Retry connection, choose an output, then press Play.")
        // Reconcile a device-scoped conflict through its validated safe view; never replay its action.
        if (error is ApiException && error.status == 401) { authLost(); return }
        if (error is ApiException && error.status == 404) {
            lifecycle++; mutableState.value = state.value.copy(deviceId = ""); return
        }
        if (error is ApiException && error.status == 409) {
            try {
                val sent = clockMs()
                val safe = try { error.responseBody?.let(PlaybackDevicesView::fromJson) } catch (_: IllegalArgumentException) { null }
                val view = (safe ?: withTimeout(4000) { api.view() }).copy(directives = emptyList())
                consume(view, sent)
            } catch (_: Exception) { /* Existing local fence remains in force. */ }
        }
    }
    private fun message(value: String) { mutableState.value = state.value.copy(message = value) }
    private fun nonce() = UUID.randomUUID().toString()
}
