package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
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
    private val identity: (() -> PlaybackDeviceIdentityStore?)? = null,
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
    private var interactionRevision = 0L
    private data class InteractionBinding(
        val deviceId: String, val outputId: String, val epoch: Long,
        val queue: List<PlaybackTrack>?, val position: Int?, val context: PlaybackContext?, val shuffle: Boolean?,
        val available: Boolean, val transfer: PlaybackTransfer?, val quarantined: Boolean,
    )
    private var interactionBinding: InteractionBinding? = null

    private fun observeInteractionBinding(id: String, view: PlaybackDevicesView) {
        // Observe EVERY accepted publication, not just UI samples: A -> B -> A must
        // cancel a held gesture too. Deliberately exclude progress and lease metadata.
        val binding = InteractionBinding(id, view.activeDeviceId, view.epoch,
            view.state?.queue, view.state?.position, view.state?.context, view.state?.shuffle,
            view.devices.any { it.deviceId == view.activeDeviceId && it.available },
            view.transfer, view.quarantineMs > 0)
        if (binding != interactionBinding) {
            interactionBinding = binding
            interactionRevision++
        }
    }
    private var leaseDeadline = 0L
    private data class AuthorityPermit(val lifecycle: Long, val epoch: Long, val generation: Long, val deadlineMs: Long)
    private val authorityPermit = AtomicReference<AuthorityPermit?>(null)
    private var released = false
    private var activatedEpoch = 0L
    private var activating = false
    private var recoverPaused = true
    private var uncertainConnection = false
    private var heartbeat: Job? = null
    private var leaseTimer: Job? = null
    private var selectionJob: Job? = null
    private var commandJob: Job? = null
    private var endpointJob: Job? = null
    private var actionVersion = 0L
    private var nextRegistrationAt = 0L
    private var registrationBackoffMs = 8000L
    private var selectionVersion = 0L
    private var intent: Selection? = null
    private var pendingCommand: String? = null
    private var observedPendingCommand: String? = null
    private val deliveries = LinkedHashSet<String>()
    private val acks = ArrayList<PlaybackAck>()
    private var ackState: PlaybackSnapshot? = null
    private var pendingFinalClose: PlaybackFinalClose? = null
    private var finalCloseVersion = 0L
    private data class Selection(val target: String, val version: Long)

    // A sibling of work, NOT its completion handler: cancellation cleanup starts even if
    // another work child is stuck in NonCancellable. Uses the endpoint's serial dispatcher.
    private val cancellationGuard = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally {
            withContext(NonCancellable) {
                if (managedOutput) {
                    lifecycle++; generation++; leaseDeadline = 0; released = true
                    revokeAuthority()
                    endpoint.suspendOutput(); managedOutput = false; serviceActive = false
                    mutableState.value = state.value.copy(connected = false, exclusiveOwner = false,
                        message = "Playback service stopped. Reconnect and press Play explicitly.")
                }
            }
        }
    }

    /** Transient main-dispatcher identity for held UI interactions, not wire authority. */
    internal val interactionVersion: Triple<Long, Long, Long> get() = Triple(lifecycle, generation, interactionRevision)

    /** For endpoint/local Media3 guards. A remote display never grants this authority. */
    fun currentAuthority(): PlaybackAuthority? = if (activatedEpoch == epoch) executionAuthority() else null
    private fun executionAuthority(): PlaybackAuthority? {
        if (!authorized()) return null
        publishAuthority()
        val e = epoch; val g = generation; val life = lifecycle
        return PlaybackAuthority(e, g) {
            // Resolver workers must not read mutable main-dispatcher coordinator fields.
            val permit = authorityPermit.get()
            permit != null && permit.lifecycle == life && permit.epoch == e && permit.generation == g &&
                clockMs() < permit.deadlineMs && work.isActive
        }
    }
    private fun revokeAuthority() { authorityPermit.set(null) }
    private fun publishAuthority() {
        authorityPermit.set(if (authorized()) AuthorityPermit(lifecycle, epoch, generation, leaseDeadline) else null)
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
            // Binding is not a user cancellation: preserve a genuine pre-bind Play request.
            fenceLocal(invalidateActions = false)
            managedOutput = true
            heartbeat = work.launch {
                while (isActive && serviceActive && !closed) {
                    transport.withLock {
                        if (intent == null) exchange {
                            if (state.value.deviceId.isEmpty()) register() else poll()
                        }
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

    private fun fenceLocal(invalidateActions: Boolean = true) {
        generation++; leaseDeadline = 0; released = true; activatedEpoch = 0; recoverPaused = true
        revokeAuthority()
        if (invalidateActions) {
            actionVersion++
            endpointJob?.cancel()
        }
        leaseTimer?.cancel()
        // The very first registration has no live output to revoke. Suspending the real
        // endpoint here would invalidate the genuine pre-bind tap waiting behind register().
        // All uncertain reconnect/lease/owner changes still take the invalidating stop path.
        if (managedOutput && invalidateActions) endpoint.suspendOutput()
        mutableState.value = state.value.copy(exclusiveOwner = false)
    }

    /** Must run before waiting for transport, even during registration or an output switch. */
    private fun interruptLocal() {
        generation++; actionVersion++; revokeAuthority()
        commandJob?.cancel(); endpointJob?.cancel()
        // An explicit Pause also cancels a pre-bind request before this coordinator owns output.
        endpoint.suspendOutput()
        pendingCommand = null
        // Execution ACK and its final snapshot are one atomic protocol item. In particular,
        // a source already released cannot reconstruct state from live authority afterward.
        // A newer deliberate pause changes the retained intent, but must not detach the state.
        ackState = if (acks.isNotEmpty()) ackState?.paused() else null
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
        // Do not let a queued/suspended explicit local action follow a newer output choice.
        val pendingLocalExecution = commandJob?.isActive == true && endpointJob?.isActive == true
        commandJob?.cancel()
        // A fresh explicit output choice may authorize a NEW transfer, never an old reconnect.
        uncertainConnection = false
        intent = selection
        pendingCommand = null
        if (pendingLocalExecution || targetDeviceId != state.value.deviceId && activating) {
            // Cancel only unfinished endpoint work. A healthy source must remain able
            // to execute and ACK the server's release at its exact final position.
            generation++; actionVersion++; revokeAuthority(); endpointJob?.cancel(); endpoint.suspendOutput()
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
    fun remoteCommand(command: PlaybackDeviceCommand, isCurrent: () -> Boolean = { true }) = submitAction(command, isCurrent = isCurrent)

    /** Only a NEW explicit user action, never startup/restoration or deferred reconnect autoplay. */
    fun localAction(action: suspend (PlaybackAuthority) -> PlaybackEndpointResult) = submitAction(action = action)

    /** Existing prepared queue only: a cancelled edit must NEVER initialize/import output. */
    internal fun editPreparedQueue(isCurrent: () -> Boolean, action: suspend (PlaybackAuthority) -> PlaybackEndpointResult) =
        submitAction(action = action, isCurrent = isCurrent, initialize = false)

    private fun submitAction(
        command: PlaybackDeviceCommand? = null,
        action: (suspend (PlaybackAuthority) -> PlaybackEndpointResult)? = null,
        isCurrent: () -> Boolean = { true },
        initialize: Boolean = true,
    ) {
        if (closed || !isCurrent()) return
        val interrupt = command?.type in setOf("pause", "close")
        if (interrupt) interruptLocal()
        if (!serviceActive) { message("Connect the playback service and choose this phone before pressing Play."); return }
        if (!interrupt && selectionPending()) {
            message("Finish selecting a playback device before changing playback."); return
        }
        if (pendingCommand != null && pendingFinalClose == null) { message("An action is awaiting confirmation. Pause or Stop can interrupt it."); return }
        // A queued NEW intent only holds disposal. It may become stale/rejected while waiting;
        // job completion must return the cleanup obligation unless real replacement state won.
        val heldClose = pendingFinalClose?.also {
            it.holdForReplacement(); pendingFinalClose = null; pendingCommand = null
        }
        val id = nonce(); pendingCommand = id; observedPendingCommand = null
        val life = lifecycle; val version = selectionVersion; val revision = actionVersion
        // This waits once behind an in-flight initial registration, never across a failed reconnect.
        commandJob = work.launch {
            var awaitingRemoteOutcome = false
            try {
                transport.withLock {
                    if (life != lifecycle || version != selectionVersion || (!interrupt && revision != actionVersion) || pendingCommand != id || !isCurrent()) return@withLock
                    exchange {
                        // A prior directive ACK and its result snapshot are atomic. Publish that
                        // pair BEFORE a newer local result can replace ackState (blocked/media
                        // outcomes in particular must retain their matching error snapshot).
                        if (acks.isNotEmpty() && ackState != null) {
                            poll()
                            if (life != lifecycle || version != selectionVersion || (!interrupt && revision != actionVersion) || pendingCommand != id || !isCurrent()) return@exchange
                            if (acks.isNotEmpty() && ackState != null) {
                                message("A received playback action is awaiting acknowledgement. Try again explicitly.")
                                return@exchange
                            }
                        }
                        if (selectionPending()) {
                            message("Finish selecting a playback device before changing playback."); return@exchange
                        }
                        val authority = executionAuthority()
                        if (authority != null) {
                            val result = endpointAction {
                                if (!isCurrent()) return@endpointAction null
                                if (initialize) {
                                    if (!initializePaused(authority)) return@endpointAction null
                                } else if (activatedEpoch != epoch) return@endpointAction null
                                if (!authority.isValid() || !isCurrent()) return@endpointAction null
                                if (action != null) action(authority) else endpoint.execute(requireNotNull(command), authority)
                            } ?: return@exchange
                            if (result.finalClose != null) {
                                if (authority.isValid() && life == lifecycle && version == selectionVersion &&
                                    (interrupt || revision == actionVersion)) publishFinalClose(result)
                                else result.finalClose.dispose()
                                return@exchange
                            }
                            if (!authority.isValid() || life != lifecycle || version != selectionVersion || (!interrupt && revision != actionVersion)) return@exchange
                            ackState = result.snapshot.validated()
                            poll() // Publish the exact action result, not a later export or remote display.
                            if (life == lifecycle && revision == actionVersion) {
                                mutableState.value = state.value.copy(needsTap = result.status == "blocked")
                                if (result.status != "ok") message("Playback needs attention on this device. Retry explicitly.")
                            }
                        } else if (action == null && state.value.deviceId.isNotEmpty() &&
                            state.value.activeDeviceId.isNotEmpty() && state.value.activeDeviceId != state.value.deviceId) {
                            // Resolve the owner/epoch only AFTER waiting: a poll may have changed them.
                            if (!isCurrent()) return@exchange
                            request { api.command(state.value.deviceId, epoch, id, requireNotNull(command)) }
                            awaitingRemoteOutcome = true
                        } else {
                            message("Choose this phone as an available output before playing local music.")
                        }
                    }
                }
            } finally {
                if (!awaitingRemoteOutcome && pendingCommand == id) pendingCommand = null
            }
        }.also { task ->
            // Also runs if cancellation prevents the coroutine body from ever starting.
            task.invokeOnCompletion { heldClose?.finishReplacement() }
        }
    }

    private fun selectionPending() = intent != null || state.value.view?.transfer != null || state.value.quarantineMs > 0

    /** Cancel endpoint work independently: a Pause must not cancel its enclosing heartbeat/poll. */
    private suspend fun endpointAction(block: suspend () -> PlaybackEndpointResult?): PlaybackEndpointResult? = supervisorScope {
        val task = async(start = CoroutineStart.LAZY) { withTimeout(4000) { block() } }
        endpointJob = task
        task.start()
        try { task.await() }
        catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (error is TimeoutCancellationException) throw error
            null // Explicit local interruption; the poll/transport owner stays alive.
        } finally { if (endpointJob === task) endpointJob = null }
    }
    fun continuePlaybackHere() { if (state.value.needsTap) remoteCommand(PlaybackDeviceCommand.transport("retry")) }

    private suspend fun register() {
        // register(name) creates an ephemeral server ID and has no idempotency key.
        // A lost response may have succeeded: do not create an orphan every heartbeat/tap.
        if (clockMs() < nextRegistrationAt) return
        mutableState.value = state.value.copy(connecting = true)
        val life = lifecycle
        try {
            val deviceName = try { identity?.invoke()?.readOrCreate(name)?.name ?: name } catch (_: Exception) { name }
            request(registration = true) { api.register(deviceName) }
            if (life == lifecycle && state.value.deviceId.isNotEmpty()) {
                nextRegistrationAt = 0; registrationBackoffMs = 8000L
            }
        } finally {
            if (state.value.deviceId.isEmpty()) {
                nextRegistrationAt = clockMs() + registrationBackoffMs
                registrationBackoffMs = (registrationBackoffMs * 2).coerceAtMost(30000L)
            }
        }
    }
    private suspend fun poll() {
        val id = state.value.deviceId
        if (id.isEmpty()) return
        val pending = acks.toList(); acks.clear()
        val final = ackState; ackState = null
        val snapshot = if (state.value.activeDeviceId == id && (authorized() && activatedEpoch == epoch || final != null)) final ?: endpoint.exportSnapshot().validated() else null
        request(ownerPoll = true) { api.poll(id, epoch, if (snapshot != null) ++sequence else null, snapshot, pending) }
    }
    /** One bounded final publication, NOT request(): its response must never activate/start audio. */
    private suspend fun publishFinalClose(result: PlaybackEndpointResult, ack: PlaybackAck? = null) {
        val completion = requireNotNull(result.finalClose)
        finalCloseVersion++
        pendingFinalClose = completion
        try {
            val id = state.value.deviceId
            val pending = acks.toList() + listOfNotNull(ack)
            acks.clear(); ackState = null
            if (id.isNotEmpty() && serviceActive && !closed) {
                val closingEpoch = epoch
                val closingSequence = ++sequence
                val snapshot = result.snapshot.validated()
                withTimeout(4000) { api.poll(id, closingEpoch, closingSequence, snapshot, pending) }
            }
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
        } catch (_: Exception) {
            // Unknown close outcome is never replayed or fed into conflict reconciliation.
        } finally {
            // Includes timeout, cancellation, auth loss and unknown outcome. Never retry a close.
            if (pendingFinalClose === completion) pendingFinalClose = null
            completion.dispose()
        }
    }

    private suspend fun request(registration: Boolean = false, ownerPoll: Boolean = false, call: suspend () -> PlaybackDevicesView) {
        if (closed || !serviceActive) return
        val life = lifecycle; val sent = clockMs(); val revision = actionVersion
        val view = withTimeout(4000) { call() }.validated()
        if (life != lifecycle || closed || !serviceActive || !work.isActive) return
        consume(view, sent, registration, startsAllowed = revision == actionVersion,
            grantAuthority = registration || ownerPoll || state.value.connected)
    }
    private suspend fun consume(
        view: PlaybackDevicesView,
        sent: Long,
        registration: Boolean = false,
        startsAllowed: Boolean = true,
        grantAuthority: Boolean = true,
    ) {
        if (!registration && view.epoch < epoch) return
        if (!registration && view.epoch == epoch && view.stateSeq < (state.value.view?.stateSeq ?: 0)) return
        val id = if (registration) view.deviceId else state.value.deviceId
        require(validPlaybackProtocolId(id) && view.devices.any { it.deviceId == id })
        val previous = state.value.view
        val changed = view.epoch != epoch
        val ownerChanged = previous?.activeDeviceId != view.activeDeviceId
        // A stable REMOTE owner's heartbeat grants no local authority, but is not a
        // new cancellation either. Repeated suspension would invalidate every async
        // queue preparation that happens to overlap the one-second polling cadence.
        if (changed || view.activeDeviceId != id && (ownerChanged || !released || activatedEpoch != 0L)) {
            fenceLocal(invalidateActions = !registration || uncertainConnection)
            acks.clear(); ackState = null
        }
        if (registration) {
            deliveries.clear(); acks.clear(); ackState = null
            sequence = 0; activatedEpoch = 0; released = true; recoverPaused = true
        }
        epoch = view.epoch
        sequence = maxOf(if (changed) 0 else sequence, view.stateSeq)
        // Only owner polling/registration/activation grants authority; GET/rename/command responses
        // may expose remaining lease but never extend it beyond the request's conservative sent time.
        leaseDeadline = if (grantAuthority && view.activeDeviceId == id) sent + (view.leaseMs - 2000).coerceAtLeast(0) else 0
        if (grantAuthority && view.activeDeviceId == id && (changed || registration || !state.value.connected) && view.directives.none { it.type == "release" }) released = false
        // Names may be changed by another controller. Only accepted own-device names persist.
        try { identity?.invoke()?.confirmName(view.devices.first { it.deviceId == id }.name, name) }
        catch (_: Exception) { /* A storage failure never grants authority or stops ordinary playback. */ }
        val outcome = view.commands.find { it.commandId == pendingCommand }
        val outcomeForgotten = outcome == null && pendingCommand != null && observedPendingCommand == pendingCommand
        if (outcome?.status == "pending") observedPendingCommand = outcome.commandId
        if (outcome != null && outcome.status != "pending" || outcomeForgotten) {
            pendingCommand = null; observedPendingCommand = null
        }
        val connectedMessage = when {
            view.quarantineMs > 0 -> "Waiting for previous playback permission to expire. The selected output will be prepared paused."
            view.transfer != null -> "Switching playback; waiting for the players to confirm."
            view.activeDeviceId.isEmpty() -> "Choose an output. Reconnecting never starts audio automatically."
            outcomeForgotten || outcome?.status in setOf("timeout", "cancelled", "rejected", "media") -> "The action could not be confirmed. It was not replayed."
            else -> ""
        }
        observeInteractionBinding(id, view)
        mutableState.value = PlaybackDevicesState(id, view,
            intent?.target ?: view.transfer?.targetDeviceId.orEmpty(), intent != null, grantAuthority, false,
            authorizedFor(id, view), view.activeDeviceId == id && view.state?.error == "blocked", connectedMessage)
        publishAuthority()
        leaseTimer?.cancel()
        if (grantAuthority && view.activeDeviceId == id) leaseTimer = work.launch {
            while (clockMs() < leaseDeadline) delay((leaseDeadline - clockMs()).coerceAtLeast(1))
            if (epoch == view.epoch && state.value.activeDeviceId == id) {
                uncertainConnection = true
                fenceLocal()
                mutableState.value = state.value.copy(connected = false, message = "Playback permission expired. Reconnect and press Play explicitly.")
            }
        }
        // A non-directive recovery NEVER imports playing state. It stays silent until explicit Play.
        if (previous != null && changed && view.directives.none { it.type == "activate" }) recoverPaused = true
        val directiveRevision = actionVersion
        val closeVersion = finalCloseVersion
        if (grantAuthority) for (directive in view.directives) {
            runDirective(directive, startsAllowed && directiveRevision == actionVersion)
            if (closeVersion != finalCloseVersion) return // Never execute directives after a final close.
        }
    }
    private fun authorizedFor(id: String, view: PlaybackDevicesView) = view.activeDeviceId == id && !released && clockMs() < leaseDeadline && activatedEpoch == view.epoch

    private suspend fun runDirective(d: PlaybackDirective, startsAllowed: Boolean) {
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
                generation++; actionVersion++; revokeAuthority(); endpointJob?.cancel()
                endpoint.suspendOutput(); released = true; activatedEpoch = 0
                PlaybackEndpointResult("ok", final)
            }
            "activate" -> {
                if (!startsAllowed || intent != null && intent?.target != state.value.deviceId) {
                    endpoint.suspendOutput(); PlaybackEndpointResult("rejected", endpoint.exportSnapshot().paused())
                } else {
                    val snapshot = (d.state ?: PlaybackSnapshot()).validated()
                    // A normal handoff has an explicit source release. Recovery/no source is paused by server.
                    val safe = if (uncertainConnection || recoverPaused && state.value.view?.transfer?.sourceDeviceId.isNullOrEmpty()) snapshot.paused() else snapshot
                    activating = true
                    val activated = try { endpointAction { endpoint.activate(safe, authority) } }
                        finally { activating = false }
                    if (activated == null || !authority.isValid()) return
                    activatedEpoch = epoch; recoverPaused = false
                    activated
                }
            }
            "command" -> {
                val incoming = PlaybackDeviceCommand.fromJson(requireNotNull(d.command))
                // Legacy web toggle is interpreted only by the actual output owner, never by
                // view.state (which can lag local buffering/transport). Resolve BEFORE pausing.
                val command = if (incoming.type == "toggle") PlaybackDeviceCommand.transport(
                    if (endpoint.exportSnapshot().playing) "pause" else "resume") else incoming
                if (!startsAllowed && command.type !in setOf("pause", "close")) {
                    PlaybackEndpointResult("rejected", endpoint.exportSnapshot().paused())
                } else {
                    if (command.type in setOf("pause", "close")) { generation++; actionVersion++; revokeAuthority(); endpoint.suspendOutput() }
                    val current = executionAuthority() ?: return
                    val executed = endpointAction {
                        if (!initializePaused(current)) return@endpointAction null
                        endpoint.execute(command, current)
                    }
                    if (executed == null) return
                    if (!current.isValid()) { executed.finalClose?.dispose(); return }
                    executed
                }
            }
            else -> return
        }
        if (result.finalClose != null) {
            publishFinalClose(result, PlaybackAck(d.deliveryId, d.epoch, result.status))
            return
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
        fenceLocal(); acks.clear(); ackState = null; pendingCommand = null
        mutableState.value = state.value.copy(connected = false, connecting = false, exclusiveOwner = false,
            message = "Connection or playback changed. The action was not replayed. Retry connection, choose an output, then press Play.")
        // Reconcile a device-scoped conflict through its validated safe view; never replay its action.
        if (error is ApiException && error.status == 401) { authLost(); return }
        if (error is ApiException && error.status == 404) {
            lifecycle++; mutableState.value = state.value.copy(deviceId = ""); return
        }
        if (error is ApiException && error.status == 409) {
            val life = lifecycle
            try {
                val sent = clockMs()
                val safe = try { error.responseBody?.let(PlaybackDevicesView::fromJson) } catch (_: IllegalArgumentException) { null }
                val view = (safe ?: withTimeout(4000) { api.view() }).validated().copy(directives = emptyList())
                if (life != lifecycle || closed || !serviceActive || !work.isActive) return
                consume(view, sent, startsAllowed = false, grantAuthority = false)
            } catch (_: Exception) { /* Existing local fence remains in force. */ }
        }
    }
    private fun message(value: String) { mutableState.value = state.value.copy(message = value) }
    private fun nonce() = UUID.randomUUID().toString()
}
