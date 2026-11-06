package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.remoteSkipCapabilities
import dev.spofify.mobile.playback.PlaybackQueueOrigin
import dev.spofify.mobile.playback.PlaybackQueueIntents
import dev.spofify.mobile.playback.queueOriginIdentity
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Presentation only: never grants playback authority or schedules a retry. */
enum class PlaybackConnectionRecovery { NONE, VERIFICATION_NEEDED, AUTH_REQUIRED, OWNER_ENDED, UNSUPPORTED }

/** Only explicitly classified routine narration may be hidden by the picker. */
enum class PlaybackDevicesMessagePurpose { GUIDANCE, PROGRESS }

/** Opaque in-process presentation identity; never authority or a saved/wire token. */
data class PlaybackDeviceSelection(val deviceId: String, val token: Long)

/** Remote display is NOT a local audible traversal and must never feed local history. */
data class PlaybackDevicesState(
    val deviceId: String = "", val view: PlaybackDevicesView? = null,
    val selectedDeviceId: String = "", val selectionUnconfirmed: Boolean = false,
    val connected: Boolean = false, val connecting: Boolean = false,
    val exclusiveOwner: Boolean = false, val needsTap: Boolean = false,
    val message: String = "Connect the playback service to use this phone as an output.",
    val recovery: PlaybackConnectionRecovery = PlaybackConnectionRecovery.NONE,
    val verifyingConnection: Boolean = false,
    val messagePurpose: PlaybackDevicesMessagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE,
    val pendingSelection: PlaybackDeviceSelection? = null,
    /** Executable, session-registration-bound capability, never inferred from availability. */
    val queueIntentBinding: QueueIntentBinding? = null,
    val canQueueIntent: Boolean = false,
) {
    val retryAvailable: Boolean get() = recovery == PlaybackConnectionRecovery.VERIFICATION_NEEDED && !verifyingConnection
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
    // Production supplies Android uptime for KeyEvent timestamps; injected test clocks stay JVM-only.
    private val mediaEventUptimeMs: () -> Long = clockMs,
    // A retiring graph's bounded disposal, never a registration or authority to restore.
    private val predecessorDisposal: Job? = null,
    private val registrationPacing: PlaybackRegistrationPacing = PlaybackRegistrationPacing(),
) {
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(PlaybackDevicesState())
    val state: StateFlow<PlaybackDevicesState> = mutableState.asStateFlow()
    private val transport = Mutex()
    private var disposal: Job? = null
    private var serviceActive = false
    private var managedOutput = false
    private var closed = false
    private var lifecycle = 0L
    private var connectionChecks = 0
    private var connectionCheckVersion = 0L
    // Negotiation belongs to this authenticated coordinator, not an installation/name.
    private var queueIntentsNegotiated = false
    private var receiverRegistered = false
    private var registrationReceiverId = UUID.randomUUID().toString()
    private var ownRegistration: PlaybackRegistrationDevice? = null

    private suspend fun probeQueueIntents(): PlaybackDevicesView {
        val life = lifecycle
        val view = withTimeout(4000) { api.probeQueueIntents() }.validated()
        if (life == lifecycle && !closed && work.isActive) queueIntentsNegotiated = view.queueIntents != null
        return view
    }

    // Counts queued/active verification separately from registration and playback work.
    // Lifecycle resets invalidate old finally blocks, including NonCancellable completions.
    private fun clearConnectionChecks() {
        connectionCheckVersion++; connectionChecks = 0
        mutableState.value = state.value.copy(verifyingConnection = false, connecting = false)
    }
    private suspend fun <T> verifyConnection(block: suspend () -> T): T {
        val version = connectionCheckVersion
        connectionChecks++
        mutableState.value = state.value.copy(verifyingConnection = true)
        try { return block() }
        finally {
            if (version == connectionCheckVersion) {
                connectionChecks--
                mutableState.value = state.value.copy(verifyingConnection = connectionChecks > 0)
            }
        }
    }
    private var generation = 0L
    private var epoch = 0L
    private var sequence = 0L
    private var interactionRevision = 0L
    private var transportDirections = false to false
    /** Capability ABA is transport-only; generic queue/action receipts do not depend on it. */
    internal var transportCapabilityVersion = 0L
        private set
    /** Uptime boundary for delayed system keys, sampled at the actual accepted binding change. */
    internal var interactionChangedUptime = mediaEventUptimeMs()
        private set
    private data class InteractionBinding(
        val deviceId: String, val outputId: String, val epoch: Long,
        val queue: List<PlaybackTrack>?, val position: Int?, val context: PlaybackContext?, val shuffle: Boolean?,
        val available: Boolean, val transfer: PlaybackTransfer?, val quarantined: Boolean,
        val queueIntentBinding: QueueIntentBinding?,
    )
    private var interactionBinding: InteractionBinding? = null

    private fun observeInteractionBinding(id: String, view: PlaybackDevicesView) {
        val directions = view.state?.let(::remoteSkipCapabilities) ?: (false to false)
        if (directions != transportDirections) {
            transportDirections = directions
            transportCapabilityVersion++
        }
        // Observe EVERY accepted publication, not just UI samples: A -> B -> A must
        // cancel a held gesture too. Deliberately exclude progress and lease metadata.
        val binding = InteractionBinding(id, view.activeDeviceId, view.epoch,
            view.state?.queue, view.state?.position, view.state?.context, view.state?.shuffle,
            view.devices.any { it.deviceId == view.activeDeviceId && it.available },
            view.transfer, view.quarantineMs > 0, view.queueIntents?.binding)
        if (binding != interactionBinding) {
            interactionBinding = binding
            interactionRevision++
            interactionChangedUptime = mediaEventUptimeMs()
        }
    }
    private var leaseDeadline = 0L
    private data class AuthorityPermit(val lifecycle: Long, val epoch: Long, val generation: Long, val deadlineMs: Long)
    private val authorityPermit = AtomicReference<AuthorityPermit?>(null)
    private var released = false
    private var activatedEpoch = 0L
    // Prior actual activation lost to a failed exchange, not a pristine/server-display queue.
    // Eligibility only: fresh own authority and exact live preparation are still required.
    private var retainedPreparedEpoch = 0L
    private var activating = false
    private var recoverPaused = true
    private var uncertainConnection = false
    private var heartbeat: Job? = null
    private var leaseTimer: Job? = null
    private var selectionJob: Job? = null
    private var commandJob: Job? = null
    private var endpointJob: Job? = null
    private class QueueExecution(val lifecycle: Long, val epoch: Long, val generation: Long,
        val commandId: String, val deliveryId: String? = null, var frozen: Boolean = false)
    private var queueExecution: QueueExecution? = null
    private var actionVersion = 0L
    /** One memory-only explicit admission. Never a retry queue or server authority. */
    private class PlayAdmission(val id: String, val lifecycle: Long, val revision: Long,
        var choice: Long, val deadline: Long, var origin: () -> Boolean, val lifetime: () -> Boolean,
        var staging: PlaybackSnapshot, var payloadVersion: Long,
        var action: suspend (PlaybackAuthority) -> PlaybackEndpointResult, var onRejected: (() -> Unit)?,
        var onCompleted: ((PlaybackEndpointResult) -> Unit)?, val queueOrigin: PlaybackQueueOrigin? = null) {
        var claimEpoch: Long? = null
        var activatedEpoch: Long? = null
        var activationDelivery: String? = null
        var activationAckSent = false
        var stagedVersion: Long? = null
        val accepted = CompletableDeferred<Boolean>()
    }
    private var playAdmission: PlayAdmission? = null
    private var admissionJob: Job? = null
    private var explicitActionJob: Job? = null
    private val explicitExecutionVersion = java.util.concurrent.atomic.AtomicLong()
    private val abandonedClaims = LinkedHashSet<String>()
    private fun abandonAdmission() {
        val old = playAdmission ?: return
        playAdmission = null
        if (old.claimEpoch != null) {
            uncertainConnection = true
            abandonedClaims.add(old.id)
            while (abandonedClaims.size > 256) abandonedClaims.remove(abandonedClaims.first())
        }
        old.accepted.complete(false)
        admissionJob?.cancel()
    }
    private fun admissionCurrent(value: PlayAdmission) = playAdmission === value && !closed && serviceActive &&
        lifecycle == value.lifecycle && actionVersion == value.revision && selectionVersion == value.choice &&
        clockMs() < value.deadline && value.lifetime() && value.origin() && work.isActive
    private var selectionVersion = 0L
    private var intent: Selection? = null
    private var pendingCommand: String? = null
    private var observedPendingCommand: String? = null
    private data class CommandResult(val id: String, val callback: ((String) -> Unit)? = null,
        val detail: ((QueueIntentResult?) -> Unit)? = null)
    private var commandResult: CommandResult? = null
    private var commandResultTimeout: Job? = null
    private fun finishCommandResult(id: String?, status: String, detail: QueueIntentResult? = null) {
        val result = commandResult ?: return
        if (id != null && result.id != id) return
        commandResult = null
        commandResultTimeout?.cancel(); commandResultTimeout = null
        try { result.callback?.invoke(status); result.detail?.invoke(detail) }
        catch (_: Exception) { /* An observer must never interrupt authority/output cleanup. */ }
    }
    private val deliveries = LinkedHashMap<String, Pair<PlaybackDirective, QueueIntentState?>>()
    private val acks = ArrayList<PlaybackAck>()
    private var ackState: PlaybackSnapshot? = null
    /** Always captured synchronously with ackState; never paired with a later live export. */
    private var ackQueueIntentState: QueueIntentState? = null
    private var pendingFinalClose: PlaybackFinalClose? = null
    private var finalCloseVersion = 0L
    private data class Selection(val target: String, val version: Long, val registrationGeneration: String?)

    private enum class HandoffPhase { ACCEPTED, AWAITING_ACTIVATION, RETIRED }
    /** Memory-only correlation, never a lease or playback permission. After reconciliation
     * it can only CANCEL/match the future activation, not classify another failed request. */
    private data class HandoffWitness(val commandId: String, val source: String, val deviceId: String,
        val baseEpoch: Long, val lifecycle: Long, val choice: Long, val revision: Long,
        val phase: HandoffPhase = HandoffPhase.ACCEPTED)
    private var handoffWitness: HandoffWitness? = null
    private fun retireHandoff() {
        val old = handoffWitness ?: return
        if (old.phase == HandoffPhase.RETIRED) return
        handoffWitness = old.copy(phase = HandoffPhase.RETIRED)
        abandonedClaims.add(old.commandId)
        while (abandonedClaims.size > 256) abandonedClaims.remove(abandonedClaims.first())
        // Keep the one retired record pinned even if unrelated claim tombstones rotate.
        // Only a subsequently accepted, serialized transfer replaces it: no older poll
        // can still be suspended behind that response, and Go supersedes the old transfer.
    }
    private fun handoffCurrent(value: HandoffWitness) = !closed && serviceActive && work.isActive &&
        !uncertainConnection && value.lifecycle == lifecycle && value.choice == selectionVersion &&
        value.revision == actionVersion && value.deviceId == state.value.deviceId
    private fun handoffView(value: HandoffWitness, view: PlaybackDevicesView, advanced: Boolean): Boolean =
        view.deviceId == value.deviceId && view.epoch == value.baseEpoch + (if (advanced) 1L else 0L) &&
            view.activeDeviceId == (if (advanced) value.deviceId else value.source) && view.quarantineMs == 0L &&
            view.devices.any { it.deviceId == value.deviceId && it.available } &&
            view.transfer?.let { it.commandId == value.commandId && it.sourceDeviceId == value.source &&
                it.targetDeviceId == value.deviceId && (if (advanced) it.phase == "activating"
                else it.phase in setOf("preparing", "releasing")) } == true &&
            view.commands.singleOrNull { it.commandId == value.commandId }?.status == "pending"
    private fun rememberHandoff(selection: Selection, id: String, commandId: String, sentEpoch: Long,
        life: Long, revision: Long, view: PlaybackDevicesView) {
        val transfer = view.transfer ?: return
        // Restrict this exception to an accepted selection against the known epoch.
        // A sequenced selection accepted from a stale epoch still takes normal recovery.
        if (intent != selection || selection.target != id || view.epoch != sentEpoch || epoch != sentEpoch ||
            transfer.sourceDeviceId.isEmpty() || transfer.sourceDeviceId == id || view.directives.isNotEmpty() ||
            pendingCommand != null || playAdmission != null || queueExecution != null || pendingFinalClose != null) return
        val value = HandoffWitness(commandId, transfer.sourceDeviceId, id, view.epoch, life, selection.version, revision)
        if (handoffCurrent(value) && handoffView(value, view, advanced = false)) {
            requireHandoffSidecars(view)
            handoffWitness = value
        }
    }
    private fun observeHandoff(view: PlaybackDevicesView) {
        val value = handoffWitness ?: return
        if (value.phase == HandoffPhase.RETIRED) return
        if (!handoffCurrent(value)) { retireHandoff(); return }
        if (value.phase == HandoffPhase.ACCEPTED && handoffView(value, view, advanced = true)) {
            // Ordinary successful polling needs no rejected-poll exception. Preserve its
            // existing authority rules; the extra awaiting witness is only for our recovery.
            handoffWitness = null
        } else if (!handoffView(value, view, advanced = value.phase == HandoffPhase.AWAITING_ACTIVATION)) retireHandoff()
    }
    private suspend fun reconcileHandoffInbox(error: ApiException, value: HandoffWitness, sent: Long): Boolean {
        if (error.status != 409 || error.playbackConflictReason != PlaybackConflictReason.STALE_EPOCH ||
            handoffWitness !== value || value.phase != HandoffPhase.ACCEPTED || !handoffCurrent(value) ||
            pendingCommand != null || playAdmission != null || queueExecution != null || pendingFinalClose != null) return false
        val view = try { error.responseBody?.let { PlaybackDevicesView.fromJson(it) } }
            catch (_: IllegalArgumentException) { null } ?: return false
        if (view.directives.isNotEmpty() || view.queueIntents?.directives?.isNotEmpty() == true ||
            !handoffView(value, view, advanced = true)) return false
        requireHandoffSidecars(view) // Validate the original view, never a stripped substitute.
        val waiting = value.copy(phase = HandoffPhase.AWAITING_ACTIVATION)
        handoffWitness = waiting
        // No poll publication, ACK, heartbeat or delivery was accepted. Preserve an already
        // false uncertainty flag; never clear it, start output, grant a lease, or replay here.
        consume(view, sent, startsAllowed = false, grantAuthority = false, handoffReconciliation = waiting)
        return true
    }
    private fun handoffActivationCurrent(value: HandoffWitness, directive: PlaybackDirective) =
        handoffWitness === value && value.phase == HandoffPhase.AWAITING_ACTIVATION && handoffCurrent(value) &&
            directive.commandId == value.commandId && directive.epoch == value.baseEpoch + 1 &&
            state.value.view?.let { handoffView(value, it, advanced = true) } == true

    // Presentation only. Request identity, not target equality, fences same-target ABA.
    private data class SelectionFeedback(val presentation: PlaybackDeviceSelection,
        val version: Long? = null, val commandId: String? = null)
    private data class CommandFeedback(val commandId: String, val observedPending: Boolean)
    private var selectionFeedback: SelectionFeedback? = null
    private var feedbackToken = 0L
    private val retiredFeedbackTransfers = LinkedHashSet<String>()
    private fun retireFeedbackTransfer(id: String?) {
        if (id == null) return
        retiredFeedbackTransfers.add(id)
        while (retiredFeedbackTransfers.size > 256) retiredFeedbackTransfers.remove(retiredFeedbackTransfers.first())
    }
    private fun clearSelectionFeedback(expectedToken: Long? = selectionFeedback?.presentation?.token) {
        if (selectionFeedback?.presentation?.token != expectedToken) return
        retireFeedbackTransfer(selectionFeedback?.commandId)
        selectionFeedback = null
        mutableState.value = state.value.copy(pendingSelection = null)
    }
    private fun observeSelectionFeedback(view: PlaybackDevicesView, sentFeedback: SelectionFeedback?, grantAuthority: Boolean) {
        if (!grantAuthority) return
        val current = selectionFeedback
        if (current != null) {
            // An unsent choice cannot be completed by an unrelated poll/rename response.
            if (current.commandId == null || sentFeedback?.presentation?.token != current.presentation.token) return
            val outcome = view.commands.find { it.commandId == current.commandId }
            val matching = view.transfer?.takeIf { it.commandId == current.commandId && it.targetDeviceId == current.presentation.deviceId }
            if (outcome != null && outcome.status != "pending" || matching == null) clearSelectionFeedback(current.presentation.token)
        } else if (intent == null) {
            val transfer = view.transfer ?: return
            if (transfer.commandId in retiredFeedbackTransfers || view.commands.any { it.commandId == transfer.commandId && it.status != "pending" }) return
            selectionFeedback = SelectionFeedback(PlaybackDeviceSelection(transfer.targetDeviceId, ++feedbackToken), commandId = transfer.commandId)
        }
    }

    // A sibling of work, NOT its completion handler: cancellation cleanup starts even if
    // another work child is stuck in NonCancellable. Uses the endpoint's serial dispatcher.
    private val cancellationGuard = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally {
            withContext(NonCancellable) {
                finishCommandResult(null, "cancelled")
                if (managedOutput) {
                    retireHandoff()
                    lifecycle++; generation++; leaseDeadline = 0; released = true
                    revokeAuthority()
                    endpoint.suspendOutput(); managedOutput = false; serviceActive = false
                    mutableState.value = state.value.copy(connected = false, exclusiveOwner = false,
                        message = "Playback service stopped. Reconnect and press Play explicitly.",
                        messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE)
                }
                clearConnectionChecks()
                clearSelectionFeedback()
                mutableState.value = state.value.copy(recovery = PlaybackConnectionRecovery.OWNER_ENDED)
            }
        }
    }

    /** Transient main-dispatcher identity for held UI interactions, not wire authority. */
    internal val interactionVersion: Triple<Long, Long, Long> get() = Triple(lifecycle, generation, interactionRevision)
    /** Accepted output choices fence held transport even when the selected output is unchanged.
     * Kept separate from generic queue/action receipt identity. */
    internal val transportSelectionVersion: Long get() = selectionVersion

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
        if (active && !work.isActive) {
            mutableState.value = state.value.copy(recovery = PlaybackConnectionRecovery.OWNER_ENDED,
                message = "The playback owner scope has ended. Recreate the service connection.",
                messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE)
            return
        }
        if (active && !endpoint.supportsExactHandoff) {
            mutableState.value = state.value.copy(recovery = PlaybackConnectionRecovery.UNSUPPORTED,
                message = "This playback engine cannot yet transfer the complete prepared queue safely.",
                messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE)
            return
        }
        serviceActive = active
        retainedPreparedEpoch = 0L
        if (active) {
            // Binding is not a user cancellation: preserve a genuine pre-bind Play request.
            fenceLocal(invalidateActions = false)
            managedOutput = true
            registrationReceiverId = endpoint.exportQueueIntentState()?.receiverId ?: UUID.randomUUID().toString()
            ownRegistration = null
            heartbeat = work.launch {
                // UI reattachment never enters this branch. Actual disposal/recreation must
                // not race a still-running exact-ID leave with the next registration.
                predecessorDisposal?.join()
                disposal?.join()
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
            lifecycle++; fenceLocal(); managedOutput = false; receiverRegistered = false
            queueIntentsNegotiated = false
            work.coroutineContext.cancelChildren(); heartbeat = null
            selectionJob?.cancel(); intent = null; acks.clear(); ackState = null; ackQueueIntentState = null
            deliveries.clear(); pendingCommand = null
            clearConnectionChecks()
            clearSelectionFeedback()
            mutableState.value = PlaybackDevicesState(message = "Playback service is not connected. Reconnecting never starts audio automatically.",
                recovery = PlaybackConnectionRecovery.VERIFICATION_NEEDED)
            if (oldId.isNotEmpty()) disposal = disposeRegistration(oldId)
        }
    }

    private fun disposeRegistration(id: String): Job {
        // Capture only a one-shot exact-ID disposal before the graph retires ordinary HTTP
        // and auth. It cannot publish, poll, select or borrow a successor's credentials.
        val leave = api.prepareLeave(id)
        val owner = SupervisorJob()
        return scope.launch(owner) {
            try {
                // Include mutex waiting, not merely the HTTP exchange, in the lifetime bound.
                withTimeout(4000) { transport.withLock { leave() } }
            } catch (_: Exception) {
                // Unknown leave outcome is not retried. Broker expiry/drain remains necessary.
            } finally { owner.complete() }
        }
    }

    private fun fenceLocal(invalidateActions: Boolean = true, preserveHandoff: Boolean = false) {
        if (!preserveHandoff) retireHandoff()
        queueExecution = null
        generation++; leaseDeadline = 0; released = true; activatedEpoch = 0; recoverPaused = true
        retainedPreparedEpoch = 0L
        revokeAuthority()
        if (invalidateActions) {
            abandonAdmission()
            finishCommandResult(null, "cancelled")
            actionVersion++
            endpointJob?.cancel()
        }
        leaseTimer?.cancel()
        // The very first registration has no live output to revoke. Suspending the real
        // endpoint here would invalidate the genuine pre-bind tap waiting behind register().
        // All uncertain reconnect/lease/owner changes still take the invalidating stop path.
        if (managedOutput && invalidateActions) endpoint.suspendOutput()
        mutableState.value = state.value.copy(exclusiveOwner = false, canQueueIntent = false)
    }

    /** Must run before waiting for transport, even during registration or an output switch. */
    private fun interruptLocal(preserveQueueReceipt: Boolean = false): Boolean {
        retireHandoff() // Before the queue-receipt early return or any transport suspension.
        abandonAdmission()
        val queue = queueExecution
        if (preserveQueueReceipt && queue != null && queue.lifecycle == lifecycle && queue.epoch == epoch &&
            queue.generation == generation && receiverRegistered && authorized()) {
            // Same-owner Pause is not owner loss. The native transaction cancels only its
            // preparation/start token and returns either effect-free cancellation or its
            // captured accepted receipt + actual paused state. Never cancel that result job.
            // This hook also covers finalized-but-publication-pending work: pause the live
            // output without invalidating the frozen receipt or its origin/session lifetime.
            val handled = endpoint.interruptQueueIntentStart()
            if (handled) return true
        }
        queueExecution = null
        generation++; actionVersion++; revokeAuthority()
        commandJob?.cancel(); endpointJob?.cancel()
        // An explicit Pause also cancels a pre-bind request before this coordinator owns output.
        endpoint.suspendOutput()
        finishCommandResult(null, "cancelled")
        pendingCommand = null
        // Execution ACK and its final snapshot are one atomic protocol item. In particular,
        // a source already released cannot reconstruct state from live authority afterward.
        // A newer deliberate pause changes the retained intent, but must not detach the state.
        // A finalized detailed receipt is immutable. Publish its frozen state first; the
        // synchronous Pause is published afterward, never rewrite Playing into another result.
        if (acks.none { it.queueIntentResult != null })
            ackState = if (acks.isNotEmpty()) ackState?.paused() else null
        return false
    }

    /** On sign-out/account/session replacement call BEFORE installing a new coordinator/client. */
    fun authLost() {
        setServiceActive(false)
        lifecycle++; fenceLocal(); work.coroutineContext.cancelChildren()
        clearConnectionChecks()
        clearSelectionFeedback()
        mutableState.value = PlaybackDevicesState(message = "Sign in again to connect your players.", recovery = PlaybackConnectionRecovery.AUTH_REQUIRED)
    }
    fun close(): Job? {
        if (!closed) {
            setServiceActive(false); closed = true; lifecycle++; work.cancel(); cancellationGuard.cancel(); fenceLocal()
            clearConnectionChecks()
            clearSelectionFeedback()
            mutableState.value = state.value.copy(recovery = PlaybackConnectionRecovery.OWNER_ENDED)
        }
        return disposal ?: predecessorDisposal
    }

    fun retry() {
        if (closed) return
        if (!serviceActive) {
            // Authenticated READ-ONLY controller: browsing does not advertise a physical phone.
            val life = lifecycle
            work.launch { verifyConnection {
                transport.withLock {
                    try {
                        val view = probeQueueIntents().copy(directives = emptyList())
                        if (life == lifecycle && !closed && work.isActive && !serviceActive) mutableState.value = PlaybackDevicesState(
                            view = view, connected = true, verifyingConnection = connectionChecks > 0,
                            recovery = if (endpoint.supportsExactHandoff) PlaybackConnectionRecovery.NONE else PlaybackConnectionRecovery.UNSUPPORTED,
                            message = "") // The picker retains its explicit view-only capability label.
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        if (life == lifecycle && !closed && work.isActive && !serviceActive) readOnlyFailure(error,
                            "Could not verify playback devices. Sign in or retry connection.")
                    }
                }
            } }
            return
        }
        work.launch { verifyConnection { transport.withLock { exchange { if (state.value.deviceId.isEmpty()) register() else poll() } } } }
    }

    /** Always responsive: one in-flight selection plus ONLY the latest unsent choice. */
    fun select(targetDeviceId: String) {
        if (!serviceActive || closed || state.value.deviceId.isEmpty()) return
        if (state.value.devices.none { it.deviceId == targetDeviceId && it.available }) {
            message("That player is unavailable. Open Spofify on it and choose again."); return
        }
        abandonAdmission()
        retainedPreparedEpoch = 0L
        val pendingHandoffActivation = activating && handoffWitness?.phase == HandoffPhase.AWAITING_ACTIVATION
        retireHandoff() // Same-target selection and A -> B -> A also retire the old request.
        val selection = Selection(targetDeviceId, ++selectionVersion,
            state.value.view?.registration?.devices?.find { it.deviceId == targetDeviceId }?.registrationGeneration)
        // Do not let a queued/suspended explicit local action follow a newer output choice.
        val pendingLocalExecution = commandJob?.isActive == true && endpointJob?.isActive == true
        commandJob?.cancel()
        // A fresh explicit output choice may authorize a NEW transfer, never an old reconnect.
        uncertainConnection = false
        intent = selection
        pendingCommand = null
        // A remote pending write's coroutine may already be complete. Retire its observer
        // with the command identity, before an unchanged-epoch selection permits a successor.
        finishCommandResult(null, "cancelled")
        if (pendingLocalExecution || pendingHandoffActivation || targetDeviceId != state.value.deviceId && activating) {
            // Cancel only unfinished endpoint work. A healthy source must remain able
            // to execute and ACK the server's release at its exact final position.
            generation++; actionVersion++; revokeAuthority(); endpointJob?.cancel(); endpoint.suspendOutput()
        }
        retireFeedbackTransfer(selectionFeedback?.commandId)
        retireFeedbackTransfer(state.value.view?.transfer?.commandId)
        selectionFeedback = SelectionFeedback(PlaybackDeviceSelection(targetDeviceId, ++feedbackToken), selection.version)
        val retainedGuidance = state.value.message.isNotEmpty() && state.value.messagePurpose == PlaybackDevicesMessagePurpose.GUIDANCE
        mutableState.value = state.value.copy(selectedDeviceId = targetDeviceId, selectionUnconfirmed = true,
            pendingSelection = selectionFeedback?.presentation,
            message = if (retainedGuidance) state.value.message else "Selecting output. You can choose another device while waiting.",
            messagePurpose = if (retainedGuidance) PlaybackDevicesMessagePurpose.GUIDANCE else PlaybackDevicesMessagePurpose.PROGRESS)
        if (selectionJob?.isActive == true) return
        selectionJob = work.launch {
            while (intent != null && serviceActive && !closed) {
                transport.withLock {
                    val latest = intent ?: return@withLock
                    val life = lifecycle
                    exchange {
                        val commandId = nonce()
                        selectionFeedback?.takeIf { it.version == latest.version }?.let {
                            selectionFeedback = it.copy(commandId = commandId)
                        }
                        val id = state.value.deviceId; val sentEpoch = epoch; val revision = actionVersion
                        request {
                            api.transfer(id, sentEpoch, commandId, latest.target, latest.version, latest.registrationGeneration).validated().also {
                                rememberHandoff(latest, id, commandId, sentEpoch, life, revision, it)
                            }
                        }
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
                    if (life == lifecycle && !closed && work.isActive && !serviceActive) mutableState.value = state.value.copy(view = view, message = "Device name saved. Native handoff remains unavailable on this phone.", messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE)
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    if (life == lifecycle && !closed && work.isActive && !serviceActive) readOnlyFailure(error,
                        "The name could not be confirmed. Refresh devices before trying again.")
                }
            } }
            return
        }
        work.launch { transport.withLock { exchange(missingTarget = deviceId) { request { api.rename(deviceId, name.trim()) } } } }
    }

    /** Route ALL app/notification actions here, not straight to a controller that bypasses ownership. */
    fun remoteCommand(command: PlaybackDeviceCommand, isCurrent: () -> Boolean = { true }) = submitAction(command, isCurrent = isCurrent)

    /** One command, terminal acknowledgement only. Kept separate so legacy trailing-lambda
     * admission predicates can never silently bind to a new completion callback.
     */
    internal fun remoteCommandWithResult(command: PlaybackDeviceCommand, isCurrent: () -> Boolean,
        onResult: (String) -> Unit) = submitAction(command, isCurrent = isCurrent, onCommandResult = onResult)

    /** One selected-output transaction. expected is the caller's captured server binding;
     * waiting behind transport can only invalidate it, never refresh or retarget it. */
    fun queueIntent(intent: String, selection: QueueIntentSelection, expected: QueueIntentBinding,
        isCurrent: () -> Boolean, onResult: (QueueIntentResult?) -> Unit) {
        val operation = QueueIntentIntent.entries.firstOrNull { it.name.equals(intent, ignoreCase = true) }
        if (operation == null) { onResult(null); return }
        queueIntent(operation, expected, selection, isCurrent, onResult)
    }

    fun queueIntent(intent: QueueIntentIntent, expected: QueueIntentBinding?, selection: QueueIntentSelection,
        isCurrent: () -> Boolean, onResult: (QueueIntentResult?) -> Unit,
        onLocalCompleted: ((PlaybackEndpointResult) -> Unit)? = null,
        unselectedOrigin: PlaybackQueueOrigin? = null) {
        // Validate exact raw selection authority before any admission/claim side effect.
        try { selection.toJson(); expected?.validated() }
        catch (_: IllegalArgumentException) { onResult(null); return }
        if (expected == null) {
            // Null alone is never admission. High additionally requires its original typed,
            // cancellation-only unselected capture; selected receiver bindings never refresh.
            val high = intent == QueueIntentIntent.HIGH && unselectedOrigin?.owner === this &&
                unselectedOrigin.consumed
            val pristineSelf = state.value.activeDeviceId == state.value.deviceId &&
                state.value.deviceId.isNotEmpty() && executionAuthority() != null &&
                state.value.queueIntentBinding == null
            val pristineHigh = intent == QueueIntentIntent.HIGH && pristineSelf &&
                endpoint.exportSnapshot().queue.isEmpty() && state.value.view?.state?.queue.isNullOrEmpty()
            if (intent != QueueIntentIntent.NOW && !high && !pristineHigh || selection is QueueIntentSelection.Occurrence ||
                state.value.activeDeviceId.isNotEmpty() && !pristineSelf ||
                state.value.deviceId.isNotEmpty() && !receiverRegistered ||
                !endpoint.supportsQueueIntents || !isCurrent()) { onResult(null); return }
            val completed = java.util.concurrent.atomic.AtomicBoolean()
            fun finish(detail: QueueIntentResult?) { if (completed.compareAndSet(false, true)) onResult(detail) }
            explicitLocalPlay(isCurrent = isCurrent, onRejected = { finish(null) }, queueOrigin = unselectedOrigin,
                admissionIsCurrent = { isCurrent() && (state.value.deviceId.isEmpty() || receiverRegistered) },
                onCompleted = {
                    if (localPublicationCurrent(it)) onLocalCompleted?.invoke(it)
                    finish(it.queueIntentResult)
                }) { authority ->
                // This binding is OWNED, not a refreshed remote capture. Conditional claim
                // activation/ACK is complete before reaching this exact local execution.
                check(receiverRegistered && authority.isValid())
                val snapshot = endpoint.exportSnapshot().validated()
                val metadata = requireNotNull(endpoint.exportQueueIntentState())
                val owned = QueueIntentBinding(state.value.deviceId, metadata.receiverId, metadata.revision,
                    snapshot.queue.getOrNull(snapshot.position)?.key, snapshot.occurrence?.eventId)
                executeQueueIntent(PlaybackDeviceCommand.queueIntent(intent, owned, selection), authority,
                    requireNotNull(pendingCommand), isCurrent = isCurrent)
            }
            return
        }
        val life = lifecycle
        val choice = selectionVersion
        val bindingRevision = interactionRevision
        fun current() = life == lifecycle && choice == selectionVersion &&
            bindingRevision == interactionRevision && state.value.canQueueIntent &&
            state.value.queueIntentBinding == expected && isCurrent()
        if (!current()) { onResult(null); return }
        val command = PlaybackDeviceCommand.queueIntent(intent, expected, selection)
        submitAction(command = command, isCurrent = ::current, executionIsCurrent = isCurrent, initialize = false,
            onQueueIntentResult = onResult, onLocalCompleted = onLocalCompleted)
    }

    /** System transport can operate prepared output, never initialize/import a queue for Play.
     * Pause still interrupts synchronously before this prepared-only dispatch gate. Remote
     * transport retains its normal authority routing; it never initializes local output.
     */
    internal fun preparedTransportCommand(command: PlaybackDeviceCommand, isCurrent: () -> Boolean) =
        submitAction(command = command, isCurrent = isCurrent, initialize = false)

    /** Only a NEW explicit user action, never startup/restoration or deferred reconnect autoplay. */
    fun localAction(isCurrent: () -> Boolean = { true }, onRejected: (() -> Unit)? = null,
        onCompleted: ((PlaybackEndpointResult) -> Unit)? = null,
        action: suspend (PlaybackAuthority) -> PlaybackEndpointResult) =
        submitAction(action = action, isCurrent = isCurrent, onRejected = onRejected, onCompleted = onCompleted)

    /** A deliberate app Play may conditionally acquire a genuinely empty room. Generic edits,
     * system transport and recovery never call this path. The payload stays with its origin. */
    fun explicitLocalPlay(isCurrent: () -> Boolean = { true }, onRejected: (() -> Unit)? = null,
        preparedResume: PlaybackSnapshot? = null, admissionIsCurrent: () -> Boolean = isCurrent,
        queueOrigin: PlaybackQueueOrigin? = null,
        onCompleted: ((PlaybackEndpointResult) -> Unit)? = null,
        action: suspend (PlaybackAuthority) -> PlaybackEndpointResult) {
        val payloadVersion = explicitExecutionVersion.incrementAndGet()
        val staging = try { (preparedResume ?: PlaybackSnapshot()).paused().publicationValidated() }
            catch (_: IllegalArgumentException) { onRejected?.invoke(); return }
        val retained = playAdmission
        if (queueOrigin == null && retained?.queueOrigin == null && retained != null && retained.claimEpoch != null && retained.lifecycle == lifecycle &&
            retained.revision == actionVersion && retained.choice == selectionVersion &&
            clockMs() < retained.deadline && retained.lifetime() && admissionIsCurrent() && isCurrent() &&
            !uncertainConnection && serviceActive && !closed) {
            // The claim is still this exact attempt, but ONLY the newest unsent music survives.
            // Retaining cannot extend its deadline or retry a failed/uncertain claim.
            retained.onRejected?.invoke()
            retained.origin = isCurrent; retained.action = action; retained.onRejected = onRejected
            retained.onCompleted = onCompleted
            retained.staging = staging; retained.payloadVersion = payloadVersion
            return
        }
        abandonAdmission()
        // A new Play retires an older uninstalled start, not healthy audible output.
        explicitActionJob?.takeIf { it.isActive }?.let { old ->
            old.cancel()
            if (commandJob === old) { endpointJob?.cancel(); pendingCommand = null }
        }
        if (closed || !serviceActive || !isCurrent() || !admissionIsCurrent() || uncertainConnection ||
            state.value.recovery !in setOf(PlaybackConnectionRecovery.NONE) && state.value.deviceId.isNotEmpty()) {
            message("Reconnect playback and press Play again."); onRejected?.invoke(); return
        }
        if (selectionPending()) {
            message("Finish selecting a playback device, then press Play again."); onRejected?.invoke(); return
        }
        if (pendingCommand != null) {
            message("Confirming playback. Try Play again."); onRejected?.invoke(); return
        }
        val value = PlayAdmission(nonce(), lifecycle, actionVersion, selectionVersion, clockMs() + 12_000,
            isCurrent, admissionIsCurrent, staging, payloadVersion, action, onRejected, onCompleted, queueOrigin)
        playAdmission = value
        var dispatched = false
        admissionJob = work.launch {
            try {
                withTimeout(12_000) {
                    transport.withLock {
                        if (!admissionCurrent(value)) return@withLock
                        exchange {
                            // Initial service binding already owns the single registration. Never
                            // register/recover again to keep an old click alive.
                            if (!state.value.connected || state.value.deviceId.isEmpty()) return@exchange
                            val self = state.value.deviceId
                            if (state.value.activeDeviceId.isEmpty()) {
                                poll() // Device-scoped, serialized fresh observation, NOT cached UI emptiness.
                                if (!admissionCurrent(value)) return@exchange
                                val fresh = state.value.view ?: return@exchange
                                if (fresh.activeDeviceId.isNotEmpty() || fresh.transfer != null ||
                                    fresh.directives.isNotEmpty() || fresh.leaseMs != 0L || fresh.quarantineMs != 0L ||
                                    !state.value.connected || uncertainConnection) return@exchange
                                value.claimEpoch = epoch
                                value.choice = ++selectionVersion
                                request { api.claimUnselected(self, epoch, value.id, value.choice) }
                            } else if (state.value.activeDeviceId == self && executionAuthority() != null) {
                                value.accepted.complete(true) // Legitimate pristine idle registration/current owner.
                            }
                        }
                    }
                    if (!admissionCurrent(value)) return@withTimeout
                    // No mutex while waiting: heartbeat owns prepare/activate ACK progression.
                    if (value.claimEpoch == null && !value.accepted.isCompleted) return@withTimeout
                    if (!value.accepted.await() || !admissionCurrent(value)) return@withTimeout
                    if (value.queueOrigin != null) {
                        // A delayed accepted activation response is not a fresh observation
                        // of a competing selection made while it was in flight. No retry:
                        // observe once under the same admission before the ordinary High.
                        transport.withLock {
                            exchange { if (admissionCurrent(value)) poll() }
                        }
                        if (!admissionCurrent(value) || state.value.view?.transfer != null ||
                            state.value.activeDeviceId != state.value.deviceId) return@withTimeout
                    }
                    val claimed = value.claimEpoch != null
                    playAdmission = null
                    dispatched = true
                    val execution = value.payloadVersion
                    submitAction(action = { authority ->
                        // Only unfinished startup is attempt-scoped. Once terminally accepted,
                        // healthy output retains its normal ownership permit across a newer tap.
                        val installed = java.util.concurrent.atomic.AtomicBoolean()
                        val guarded = PlaybackAuthority(authority.epoch, authority.generation) {
                            authority.isValid() && (installed.get() || execution == explicitExecutionVersion.get() &&
                                clockMs() < value.deadline && value.lifetime() && value.origin())
                        }
                        val result = value.action(guarded)
                        if (execution == explicitExecutionVersion.get() && guarded.isValid()) installed.set(true)
                        result
                    }, isCurrent = {
                        execution == explicitExecutionVersion.get() && clockMs() < value.deadline && value.lifetime() && value.origin() &&
                            lifecycle == value.lifecycle && selectionVersion == value.choice &&
                            (!claimed || value.activatedEpoch == epoch)
                    }, initialize = !claimed && preparedResume == null, onRejected = value.onRejected,
                        onCompleted = value.onCompleted)
                    explicitActionJob = commandJob
                }
            } catch (error: TimeoutCancellationException) {
                message("Playback could not be confirmed. Check the output and press Play again.")
            } finally {
                if (playAdmission === value) {
                    // Retain only the bounded abandoned command identity, never its music.
                    playAdmission = null
                    if (value.claimEpoch != null) {
                        abandonedClaims.add(value.id)
                        while (abandonedClaims.size > 256) abandonedClaims.remove(abandonedClaims.first())
                    }
                    message("Playback could not be confirmed. Check the output and press Play again.")
                }
                if (!dispatched) value.onRejected?.invoke()
            }
        }
    }

    /** Existing prepared queue only: a cancelled edit must NEVER initialize/import output. */
    internal fun editPreparedQueue(isCurrent: () -> Boolean, onRejected: (() -> Unit)? = null,
        onCompleted: ((PlaybackEndpointResult) -> Unit)? = null,
        action: suspend (PlaybackAuthority) -> PlaybackEndpointResult) =
        submitAction(action = action, isCurrent = isCurrent, initialize = false, onRejected = onRejected,
            onCompleted = onCompleted)

    private fun submitAction(
        command: PlaybackDeviceCommand? = null,
        action: (suspend (PlaybackAuthority) -> PlaybackEndpointResult)? = null,
        isCurrent: () -> Boolean = { true },
        executionIsCurrent: () -> Boolean = isCurrent,
        initialize: Boolean = true,
        onRejected: (() -> Unit)? = null,
        onCommandResult: ((String) -> Unit)? = null,
        onCompleted: ((PlaybackEndpointResult) -> Unit)? = null,
        onQueueIntentResult: ((QueueIntentResult?) -> Unit)? = null,
        onLocalCompleted: ((PlaybackEndpointResult) -> Unit)? = null,
    ) {
        fun rejectEarly() { onRejected?.invoke(); onCommandResult?.invoke("rejected"); onQueueIntentResult?.invoke(null) }
        if (closed || !isCurrent()) { rejectEarly(); return }
        val interrupt = command?.type in setOf("pause", "close")
        if (interrupt && interruptLocal(preserveQueueReceipt = command?.type == "pause")) {
            // The synchronous native pause and the original queue ACK/state are the one
            // transaction. A second command would erase its pending admission/observer.
            onCommandResult?.invoke("ok")
            return
        }
        if (!serviceActive) { message("Connect the playback service and choose this phone before pressing Play."); rejectEarly(); return }
        if (!interrupt && selectionPending()) {
            message("Finish selecting a playback device before changing playback."); rejectEarly(); return
        }
        if (!interrupt && playAdmission != null) {
            message("An explicit Play is awaiting confirmation. Pause can cancel it."); rejectEarly(); return
        }
        if (pendingCommand != null && pendingFinalClose == null) { message("Confirming playback. Try again shortly."); rejectEarly(); return }
        // A queued NEW intent only holds disposal. It may become stale/rejected while waiting;
        // job completion must return the cleanup obligation unless real replacement state won.
        val heldClose = pendingFinalClose?.also {
            it.holdForReplacement(); pendingFinalClose = null; pendingCommand = null
        }
        val id = nonce(); pendingCommand = id; observedPendingCommand = null
        // No retired observer may be overwritten, including a held final-close replacement.
        finishCommandResult(null, "cancelled")
        if (onCommandResult != null || onQueueIntentResult != null)
            commandResult = CommandResult(id, onCommandResult, onQueueIntentResult)
        val life = lifecycle; val version = selectionVersion; val revision = actionVersion
        // This waits once behind an in-flight initial registration, never across a failed reconnect.
        var actionCompleted = false
        var endpointResult: PlaybackEndpointResult? = null
        var awaitingRemoteOutcome = false
        var resultConfirmed = false
        commandJob = work.launch {
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
                                } else if (activatedEpoch != epoch) {
                                    val expected = command?.takeIf { it.type == "queueIntent" }?.json?.get("expected")
                                        ?.let { QueueIntentBinding.fromJson(it as kotlinx.serialization.json.JsonObject) }
                                    if (acceptedEmptyQueueIntentBinding(expected)) {
                                        // Exact empty native state already exists under this lease;
                                        // this admits its first transaction without activate/import.
                                        activatedEpoch = epoch
                                    } else if (!activateRetainedPrepared(authority, expected, isCurrent)) {
                                        message("The prepared playback could not be verified. Choose music or an available output, then try again.")
                                        return@endpointAction null
                                    }
                                }
                                if (!authority.isValid() || !isCurrent()) return@endpointAction null
                                if (action != null) action(authority) else {
                                    // Binding is an admission/precommit fence, not durable audible
                                    // authority. Its own successful publication MUST NOT revoke
                                    // the service's lease permit or its exact successor receipt.
                                    if (command?.type == "queueIntent")
                                        executeQueueIntent(command, authority, id, isCurrent = executionIsCurrent)
                                    else endpoint.execute(requireNotNull(command), authority)
                                }
                            } ?: return@exchange
                            actionCompleted = true
                            endpointResult = result
                            if (result.finalClose != null) {
                                if (authority.isValid() && life == lifecycle && version == selectionVersion &&
                                    (interrupt || revision == actionVersion)) publishFinalClose(result)
                                else result.finalClose.dispose()
                                return@exchange
                            }
                            if (!authority.isValid() || life != lifecycle || version != selectionVersion || (!interrupt && revision != actionVersion)) return@exchange
                            retainPublication(result)
                            poll() // Publish the exact action result, not a later export or remote display.
                            resultConfirmed = authority.isValid() && life == lifecycle && version == selectionVersion &&
                                (interrupt || revision == actionVersion)
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
                            if (commandResult?.id == id) commandResultTimeout = work.launch {
                                delay(8_000)
                                // The write has already happened. Timeout reports uncertainty,
                                // never clears/replays it or permits a second pending mutation.
                                finishCommandResult(id, "timeout")
                            }
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
            task.invokeOnCompletion {
                // Cancellation can prevent the body (and its finally) from ever running.
                // Completion is an admission-readiness boundary, after releasing transport
                // and attempting exact result publication; never deliver it inside mutation.
                if (!awaitingRemoteOutcome && pendingCommand == id) pendingCommand = null
                heldClose?.finishReplacement()
                if (queueExecution?.commandId == id) queueExecution = null
                // Includes pre-dispatch cancellation, rejected authority/selection and errors
                // while waiting. Existing callers remain source compatible; list actions can
                // complete truthfully instead of announcing optimistic enqueue success.
                if (!actionCompleted) onRejected?.invoke()
                // Process-local additive proof precedes the public terminal detail, but
                // only its exact installed successor may release already-committed tickets.
                endpointResult?.takeIf { resultConfirmed && localPublicationCurrent(it) }?.let {
                    try { onLocalCompleted?.invoke(it) } catch (_: Exception) { /* Observer only. */ }
                }
                if (it != null || !awaitingRemoteOutcome) finishCommandResult(id,
                    endpointResult?.status ?: if (it is CancellationException) "cancelled" else "rejected",
                    endpointResult?.queueIntentResult?.takeIf { resultConfirmed })
                endpointResult?.let { result ->
                    if (result.queueIntentResult == null || resultConfirmed) onCompleted?.invoke(result)
                    else onRejected?.invoke() // Authority/publication uncertainty is not detailed success.
                }
            }
        }
    }

    private suspend fun executeQueueIntent(command: PlaybackDeviceCommand, authority: PlaybackAuthority,
        commandId: String, deliveryId: String? = null, isCurrent: () -> Boolean = { true }): PlaybackEndpointResult {
        val execution = QueueExecution(lifecycle, epoch, generation, commandId, deliveryId)
        queueExecution = execution
        try {
            val result = endpoint.execute(command, authority, isCurrent)
            execution.frozen = true
            return result
        } catch (error: Throwable) {
            if (queueExecution === execution) queueExecution = null
            throw error
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

    private suspend fun register(): Unit = verifyConnection {
        // Legacy registration is uncertain after a lost reply. Managed enrollment retains
        // one exact attempt in the API for idempotent lookup; neither path starts music.
        // Manual Retry/action paths also call register under transport: never join a leave
        // while holding its mutex. The heartbeat awaits completion outside the lock.
        if (predecessorDisposal?.isCompleted == false || disposal?.isCompleted == false ||
            !registrationPacing.mayAttempt(clockMs())) return@verifyConnection
        mutableState.value = state.value.copy(connecting = true)
        val life = lifecycle
        try {
            val deviceIdentity = try { identity?.invoke()?.readOrCreate(name) } catch (_: Exception) { null }
            val deviceName = deviceIdentity?.name ?: name
            // Read-only negotiation MUST precede any extension-bearing mutation. Older
            // servers return an absent sidecar, then receive the exact legacy register.
            val capabilities = probeQueueIntents()
            if (life != lifecycle || closed || !serviceActive) return@verifyConnection
            // A negotiated server must never silently fall back to ephemeral registrations
            // when encrypted fallback persistence failed.
            check(capabilities.registration == null || deviceIdentity != null) { "Stable playback identity is unavailable" }
            val capable = queueIntentsNegotiated && endpoint.supportsQueueIntents
            // Queue-intent publication and installation registration describe the SAME
            // live service receiver, not two independently rotated incarnation tokens.
            if (capable) registrationReceiverId = requireNotNull(endpoint.exportQueueIntentState()).receiverId
            receiverRegistered = capable
            // Record uncertainty BEFORE dispatch, so cancellation/lifecycle replacement
            // cannot reopen the write floodgate. Probe failures never mark a sent write.
            val attempt = registrationPacing.dispatched(clockMs())
            request(registration = true) { api.register(deviceName, if (capable) 1 else null,
                deviceIdentity?.stableDeviceId, registrationReceiverId) }
            if (life == lifecycle && !closed && serviceActive && state.value.deviceId.isNotEmpty()) {
                registrationPacing.confirmed(attempt)
            }
        } finally {
            if (life == lifecycle) mutableState.value = state.value.copy(connecting = false)
        }
    }
    private suspend fun poll(): Unit = verifyConnection {
        val id = state.value.deviceId
        if (id.isEmpty()) return@verifyConnection
        playAdmission?.takeIf { admissionCurrent(it) && it.activatedEpoch == epoch && it.stagedVersion != it.payloadVersion }?.let { admission ->
            val authority = executionAuthority() ?: return@let
            val staged = endpointAction {
                var result: PlaybackEndpointResult
                do {
                    if (!admissionCurrent(admission)) return@endpointAction null
                    val payload = admission.payloadVersion
                    result = if (admission.staging.queue.isEmpty()) endpoint.activate(admission.staging, null, authority)
                        else endpoint.activatePreparedResume(admission.staging, authority)
                    if (payload == admission.payloadVersion && result.status == "ok") admission.stagedVersion = payload
                } while (admissionCurrent(admission) && admission.stagedVersion != admission.payloadVersion && result.status == "ok")
                result
            }
            if (staged == null || staged.status != "ok" || !admissionCurrent(admission)) {
                abandonAdmission(); return@verifyConnection
            }
            retainPublication(staged)
        }
        val pending = acks.toList(); acks.clear()
        playAdmission?.let { admission ->
            if (pending.any { it.deliveryId == admission.activationDelivery && it.status == "ok" && it.epoch == admission.activatedEpoch })
                admission.activationAckSent = true
        }
        val final = ackState
        val finalMetadata = ackQueueIntentState
        ackState = null; ackQueueIntentState = null
        // These two endpoint reads are synchronous on the serial owner dispatcher. No
        // suspension, resolver or publication may split musical state from its sidecar.
        val snapshot = if (state.value.activeDeviceId == id && (authorized() && activatedEpoch == epoch || final != null)) final ?: endpoint.exportSnapshot().validated() else null
        val metadata = if (snapshot == null || !receiverRegistered) null
            else if (final != null) requireNotNull(finalMetadata) else requireNotNull(endpoint.exportQueueIntentState())
        val publication = playAdmission?.takeIf { snapshot != null && it.activatedEpoch == epoch && it.stagedVersion == it.payloadVersion }
            ?.let { it.id to it.payloadVersion }
        val queue = queueExecution
        val requestEpoch = epoch
        val requestSequence = if (snapshot != null) ++sequence else null
        val sent = clockMs()
        // Capture the ACTUAL outgoing request before suspension. The buffers above have
        // already been cleared; inspecting them in a catch would misclassify sent ACKs.
        val inbox = handoffWitness?.takeIf { it.phase == HandoffPhase.ACCEPTED && handoffCurrent(it) &&
            it.baseEpoch == requestEpoch && snapshot == null && requestSequence == null && metadata == null &&
            pending.isEmpty() && publication == null && pendingCommand == null && playAdmission == null &&
            queue == null && pendingFinalClose == null && state.value.view?.let { view ->
                handoffView(it, view, advanced = false)
            } == true }
        try {
            request(ownerPoll = true, admissionPublication = publication) {
                api.poll(id, requestEpoch, requestSequence, snapshot, pending, metadata)
            }
        } catch (error: ApiException) {
            if (inbox == null || !reconcileHandoffInbox(error, inbox, sent)) throw error
        }
        if (queue?.deliveryId != null && pending.any { it.deliveryId == queue.deliveryId } && queueExecution === queue)
            queueExecution = null
    }
    /** Pristine idle ownership needs no import. Accept only the exact EMPTY native
     * incarnation already acknowledged by the broker, never a retained nonempty display. */
    private fun acceptedEmptyQueueIntentBinding(expected: QueueIntentBinding?,
        view: PlaybackDevicesView? = state.value.view, localDeviceId: String = state.value.deviceId): Boolean {
        if (!receiverRegistered || expected == null || view == null || view.activeDeviceId != localDeviceId ||
            expected.outputDeviceId != localDeviceId || view.queueIntents?.binding != expected ||
            expected.currentKey != null || expected.currentEventId != null) return false
        val accepted = view.state ?: return false
        val live = endpoint.exportSnapshot().validated()
        fun empty(snapshot: PlaybackSnapshot) = snapshot.queue.isEmpty() && snapshot.position == -1 &&
            snapshot.occurrence == null && !snapshot.playing && !snapshot.waiting && !snapshot.loading
        if (!empty(accepted) || !empty(live)) return false
        val metadata = endpoint.exportQueueIntentState()?.validated(live) ?: return false
        return metadata == view.queueIntents?.state && metadata.receiverId == expected.receiverId &&
            metadata.revision == expected.revision && accepted.context == live.context && accepted.shuffle == live.shuffle &&
            accepted.repeat == live.repeat && accepted.truncated == live.truncated && accepted.notice == live.notice
    }

    /** Readiness only. A fresh own poll may confirm this exact live receiver without
     * activating it. Old requests/keys remain fenced; server display is never imported. */
    private fun acceptedRetainedPrepared(expected: QueueIntentBinding?,
        view: PlaybackDevicesView? = state.value.view, localDeviceId: String = state.value.deviceId): PlaybackSnapshot? {
        if (!receiverRegistered || expected == null || view == null || retainedPreparedEpoch != view.epoch ||
            view.activeDeviceId != localDeviceId || expected.outputDeviceId != localDeviceId || view.queueIntents?.binding != expected ||
            view.transfer != null || view.quarantineMs != 0L || view.directives.isNotEmpty() ||
            view.commands.any { it.status == "pending" } ||
            view.devices.none { it.deviceId == localDeviceId && it.available }) return null
        val accepted = view.state ?: return null
        val live = endpoint.exportSnapshot().validated()
        val current = live.queue.getOrNull(live.position) ?: return null
        if (live.playing || live.waiting || live.loading || expected.currentKey != current.key ||
            expected.currentEventId != live.occurrence?.eventId) return null
        val metadata = endpoint.exportQueueIntentState()?.validated(live) ?: return null
        if (metadata != view.queueIntents?.state || metadata.receiverId != expected.receiverId ||
            metadata.revision != expected.revision ||
            PlaybackQueueIntents.semanticSnapshot(accepted) != PlaybackQueueIntents.semanticSnapshot(live)) return null
        return live
    }

    /** NEW serialized prepared action only; never called by consume/poll or a directive.
     * Reaccept the exact live paused queue under verified authority, without import/seek/start.
     * The action and endpoint retain their independent session/service/origin fences. */
    private suspend fun activateRetainedPrepared(authority: PlaybackAuthority, expected: QueueIntentBinding?,
        isCurrent: () -> Boolean): Boolean {
        if (uncertainConnection || state.value.recovery != PlaybackConnectionRecovery.NONE ||
            !authority.isValid() || !isCurrent()) return false
        val live = acceptedRetainedPrepared(expected ?: state.value.queueIntentBinding) ?: return false
        val result = endpoint.activatePreparedResume(live, authority)
        if (result.status != "ok" || !authority.isValid() || !isCurrent()) return false
        require(result.snapshot == live && result.queueIntentState == state.value.view?.queueIntents?.state) {
            "Retained preparation must preserve exact live state and metadata"
        }
        activatedEpoch = epoch
        retainedPreparedEpoch = 0L
        return true
    }

    private fun currentPublication(status: String): PlaybackEndpointResult {
        val snapshot = endpoint.exportSnapshot().validated()
        val metadata = if (receiverRegistered) requireNotNull(endpoint.exportQueueIntentState()).validated(snapshot) else null
        return PlaybackEndpointResult(status, snapshot, queueIntentState = metadata)
    }

    private fun localPublicationCurrent(result: PlaybackEndpointResult): Boolean {
        if (currentAuthority()?.isValid() != true) return false
        val metadata = result.queueIntentState ?: return false
        val liveMetadata = endpoint.exportQueueIntentState() ?: return false
        if (metadata.receiverId != liveMetadata.receiverId || metadata.revision != liveMetadata.revision) return false
        val live = endpoint.exportSnapshot()
        return result.snapshot.queue.getOrNull(result.snapshot.position)?.key == live.queue.getOrNull(live.position)?.key &&
            result.snapshot.occurrence?.eventId == live.occurrence?.eventId
    }

    private fun retainPublication(result: PlaybackEndpointResult) {
        val snapshot = result.snapshot.validated()
        val metadata = if (receiverRegistered) requireNotNull(result.queueIntentState).validated(snapshot) else null
        result.queueIntentResult?.validated(snapshot, requireNotNull(metadata), result.status)
        ackState = snapshot
        ackQueueIntentState = metadata
    }

    /** One bounded final publication, NOT request(): its response must never activate/start audio. */
    private suspend fun publishFinalClose(result: PlaybackEndpointResult, ack: PlaybackAck? = null) {
        retireHandoff()
        val completion = requireNotNull(result.finalClose)
        finalCloseVersion++
        pendingFinalClose = completion
        try {
            val id = state.value.deviceId
            val pending = acks.toList() + listOfNotNull(ack)
            acks.clear(); ackState = null; ackQueueIntentState = null
            if (id.isNotEmpty() && serviceActive && !closed) {
                val closingEpoch = epoch
                val closingSequence = ++sequence
                val snapshot = result.snapshot.validated()
                withTimeout(4000) { api.poll(id, closingEpoch, closingSequence, snapshot, pending,
                    if (receiverRegistered) requireNotNull(result.queueIntentState) else null) }
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

    private suspend fun request(registration: Boolean = false, ownerPoll: Boolean = false,
        admissionPublication: Pair<String, Long>? = null, call: suspend () -> PlaybackDevicesView) {
        if (closed || !serviceActive) return
        val life = lifecycle; val sent = clockMs(); val revision = actionVersion
        val sentFeedback = selectionFeedback
        val sentCommand = pendingCommand?.let { CommandFeedback(it, observedPendingCommand == it) }
        val view = withTimeout(4000) { call() }.validated()
        if (life != lifecycle || closed || !serviceActive || !work.isActive) return
        consume(view, sent, registration, startsAllowed = revision == actionVersion,
            grantAuthority = registration || ownerPoll || state.value.connected, sentFeedback = sentFeedback,
            sentCommand = sentCommand, admissionPublication = admissionPublication)
        // A successful fresh observation may make a NEW explicit tap possible; it never
        // resurrects the retired intent or treats pending recovery as an empty room.
        if (ownerPoll && state.value.connected && view.transfer == null && view.directives.isEmpty() &&
            view.quarantineMs == 0L) uncertainConnection = false
    }
    private suspend fun consume(
        view: PlaybackDevicesView,
        sent: Long,
        registration: Boolean = false,
        startsAllowed: Boolean = true,
        grantAuthority: Boolean = true,
        sentFeedback: SelectionFeedback? = null,
        sentCommand: CommandFeedback? = null,
        admissionPublication: Pair<String, Long>? = null,
        handoffReconciliation: HandoffWitness? = null,
    ) {
        if (registration) {
            ownRegistration = view.registration?.devices?.singleOrNull { it.deviceId == view.deviceId }
            require(view.registration == null || ownRegistration?.receiverId == registrationReceiverId)
        } else ownRegistration?.let { own ->
            require(view.registration?.devices?.singleOrNull { it.deviceId == own.deviceId } == own) {
                "Playback registration was replaced"
            }
        }
        if (!registration && view.epoch < epoch) return
        if (!registration && view.epoch == epoch && view.stateSeq < (state.value.view?.stateSeq ?: 0)) return
        requireHandoffSidecars(view)
        observeHandoff(view)
        val id = if (registration) view.deviceId else state.value.deviceId
        require(validPlaybackProtocolId(id) && view.devices.any { it.deviceId == id })
        val previous = state.value.view
        val changed = view.epoch != epoch
        val ownerChanged = previous?.activeDeviceId != view.activeDeviceId
        if (registration || changed || ownerChanged) retainedPreparedEpoch = 0L
        val priorMetadata = previous?.queueIntents?.state
        val nextMetadata = view.queueIntents?.state
        if (!changed && !ownerChanged && priorMetadata != null && nextMetadata != null && nextMetadata.receiverId == priorMetadata.receiverId)
            require(nextMetadata.revision >= priorMetadata.revision) { "Playback semantic revision decreased" }
        val admission = playAdmission
        val matchingTransfer = admission?.claimEpoch != null && view.transfer?.let {
            it.commandId == admission.id && it.targetDeviceId == id && it.sourceDeviceId.isEmpty()
        } == true
        val expectedActivation = admission != null && admissionCurrent(admission) && matchingTransfer &&
            view.epoch == admission.claimEpoch!! + 1 && view.activeDeviceId == id &&
            view.transfer?.phase == "activating" && view.directives.any {
                it.type == "activate" && it.commandId == admission.id && it.epoch == view.epoch
            }
        if (admission != null && admission.claimEpoch != null) {
            val legitimate = admissionCurrent(admission) && (matchingTransfer &&
                (view.epoch == admission.claimEpoch && view.activeDeviceId.isEmpty() || expectedActivation ||
                    view.epoch == admission.activatedEpoch && view.activeDeviceId == id) ||
                admission.activationAckSent && view.epoch == admission.activatedEpoch && view.activeDeviceId == id &&
                    view.transfer == null && view.commands.any { it.commandId == admission.id && it.status == "ok" })
            if (!legitimate) abandonAdmission()
        }
        // A stable REMOTE owner's heartbeat grants no local authority, but is not a
        // new cancellation either. Repeated suspension would invalidate every async
        // queue preparation that happens to overlap the one-second polling cadence.
        if (changed || view.activeDeviceId != id && (ownerChanged || !released || activatedEpoch != 0L)) {
            val preserve = handoffReconciliation != null && handoffWitness === handoffReconciliation &&
                handoffCurrent(handoffReconciliation) && handoffView(handoffReconciliation, view, advanced = true)
            fenceLocal(invalidateActions = !expectedActivation && (!registration || uncertainConnection), preserveHandoff = preserve)
            // Exactly the synchronous internal fence above advanced actionVersion. Never
            // refresh a witness after a suspension or an intervening external retirement.
            if (preserve && handoffWitness === handoffReconciliation)
                handoffWitness = handoffReconciliation!!.copy(revision = actionVersion)
            acks.clear(); ackState = null; ackQueueIntentState = null
        }
        if (registration) {
            deliveries.clear(); acks.clear(); ackState = null; ackQueueIntentState = null
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
        val terminal = outcome != null && outcome.status != "pending" || outcomeForgotten
        val completedCommand = pendingCommand.takeIf { terminal }
        if (terminal) { pendingCommand = null; observedPendingCommand = null }
        val selectionOutcome = view.commands.find { it.commandId == sentFeedback?.commandId }
        // A newer selection may retire the command while this request is suspended.
        // Retain its failure/forgotten guidance without reviving control or its callback.
        val sentCommandOutcome = view.commands.find { it.commandId == sentCommand?.commandId }
        val sentCommandForgotten = sentCommand?.observedPending == true && sentCommandOutcome == null
        val failureStatuses = setOf("timeout", "cancelled", "rejected", "media", "blocked")
        val guidance = when {
            !grantAuthority && state.value.recovery == PlaybackConnectionRecovery.AUTH_REQUIRED -> "Sign in again to connect your players."
            !grantAuthority -> "Playback connection needs verification. The action was not replayed. Reconnect before pressing Play."
            outcomeForgotten || sentCommandForgotten || outcome?.status in failureStatuses ||
                sentCommandOutcome?.status in failureStatuses || selectionOutcome?.status in failureStatuses -> "The action could not be confirmed. It was not replayed."
            view.state?.error == "blocked" -> "Playback needs a tap on the selected device."
            view.state?.error == "media" -> "The selected device could not play this audio. Choose music or retry explicitly."
            else -> ""
        }
        val progress = when {
            view.quarantineMs > 0 -> "Waiting for previous playback permission to expire. The selected output will be prepared paused."
            view.transfer != null -> "Switching playback; waiting for the players to confirm."
            else -> ""
        }
        val retainedGuidance = !registration && (progress.isNotEmpty() || selectionFeedback != null || intent != null) &&
            state.value.message.isNotEmpty() &&
            state.value.messagePurpose == PlaybackDevicesMessagePurpose.GUIDANCE
        val connectedMessage = guidance.ifEmpty { if (retainedGuidance) state.value.message else progress }
        val messagePurpose = if (guidance.isEmpty() && !retainedGuidance && progress.isNotEmpty())
            PlaybackDevicesMessagePurpose.PROGRESS else PlaybackDevicesMessagePurpose.GUIDANCE
        observeSelectionFeedback(view, sentFeedback, grantAuthority)
        observeInteractionBinding(id, view)
        mutableState.value = PlaybackDevicesState(id, view,
            intent?.target ?: view.transfer?.targetDeviceId.orEmpty(), intent != null, grantAuthority, false,
            authorizedFor(id, view), view.activeDeviceId == id && view.state?.error == "blocked", connectedMessage,
            recovery = when {
                grantAuthority -> PlaybackConnectionRecovery.NONE
                state.value.recovery == PlaybackConnectionRecovery.AUTH_REQUIRED -> PlaybackConnectionRecovery.AUTH_REQUIRED
                else -> PlaybackConnectionRecovery.VERIFICATION_NEEDED
            },
            verifyingConnection = connectionChecks > 0, messagePurpose = messagePurpose,
            pendingSelection = selectionFeedback?.presentation,
            queueIntentBinding = view.queueIntents?.binding,
            canQueueIntent = grantAuthority && serviceActive && view.queueIntents?.binding != null &&
                (view.activeDeviceId != id || receiverRegistered && (activatedEpoch == epoch ||
                    !released && clockMs() < leaseDeadline &&
                        (acceptedEmptyQueueIntentBinding(view.queueIntents?.binding, view, id) ||
                            acceptedRetainedPrepared(view.queueIntents?.binding, view, id) != null))) &&
                view.devices.any { it.deviceId == view.activeDeviceId && it.available } &&
                view.transfer == null && view.quarantineMs == 0L)
        publishAuthority()
        if (terminal) finishCommandResult(completedCommand, outcome?.status ?: "cancelled",
            view.queueIntents?.results?.find { it.commandId == completedCommand }?.result)
        leaseTimer?.cancel()
        val feedbackAtLease = selectionFeedback?.presentation?.token
        if (grantAuthority && view.activeDeviceId == id) leaseTimer = work.launch {
            while (clockMs() < leaseDeadline) delay((leaseDeadline - clockMs()).coerceAtLeast(1))
            if (epoch == view.epoch && state.value.activeDeviceId == id) {
                uncertainConnection = true
                fenceLocal()
                clearSelectionFeedback(feedbackAtLease)
                mutableState.value = state.value.copy(connected = false, recovery = PlaybackConnectionRecovery.VERIFICATION_NEEDED,
                    message = "Playback permission expired. Reconnect and press Play explicitly.",
                    messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE)
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
        playAdmission?.let { pending ->
            if (pending.claimEpoch != null && pending.activationAckSent &&
                view.commands.any { it.commandId == pending.id && it.status == "ok" } &&
                pending.activatedEpoch == epoch && pending.stagedVersion == pending.payloadVersion &&
                admissionPublication == (pending.id to pending.payloadVersion) &&
                view.transfer == null && currentAuthority()?.isValid() == true &&
                admissionCurrent(pending)) pending.accepted.complete(true)
        }
    }
    private fun authorizedFor(id: String, view: PlaybackDevicesView) = view.activeDeviceId == id && !released && clockMs() < leaseDeadline && activatedEpoch == view.epoch

    private suspend fun runDirective(d: PlaybackDirective, startsAllowed: Boolean) {
        val delivered = d to directiveMetadata(d)
        deliveries[d.deliveryId]?.let { original ->
            require(original == delivered) { "Conflicting playback delivery" }
            return // A retained delivery never executes or resamples its terminal receipt twice.
        }
        deliveries[d.deliveryId] = delivered
        while (deliveries.size > 256) deliveries.remove(deliveries.keys.first())
        if (d.epoch != epoch) return
        if (d.commandId in abandonedClaims || handoffWitness?.let {
                it.phase == HandoffPhase.RETIRED && it.lifecycle == lifecycle && it.commandId == d.commandId
            } == true) {
            // An obsolete claim cannot stage retained audio or execute a late activation.
            acks += PlaybackAck(d.deliveryId, d.epoch, "rejected")
            // Abandoned activation has no execution authority or truthful imported state;
            // never pair a fabricated blank snapshot with retained provenance.
            return
        }
        if (d.type == "prepare") {
            // Validate the ORIGINAL musical snapshot and metadata, even when a first-Play
            // admission will later stage blank state. Never strip malformed/unknown priority.
            val valid = validateHandoff(d)
            if (d.epoch == epoch) acks += PlaybackAck(d.deliveryId, d.epoch, if (valid) "ok" else "rejected")
            return
        }
        val authority = executionAuthority()
        if (authority == null) return // Cannot ACK execution without actual owner state. Server fences on timeout.
        val result = when (d.type) {
            "release" -> {
                retireHandoff()
                // Capture final position/intent BEFORE pause. Preserve playing=true in the ACK snapshot
                // so the destination can resume intentional playback only after the acknowledged stop.
                val final = if (activatedEpoch == epoch) endpoint.exportSnapshot().validated()
                    else (state.value.view?.state ?: PlaybackSnapshot()).paused()
                val metadata = if (!receiverRegistered) null else if (activatedEpoch == epoch)
                    requireNotNull(endpoint.exportQueueIntentState()) else state.value.view?.queueIntents?.state
                generation++; actionVersion++; revokeAuthority(); endpointJob?.cancel()
                endpoint.suspendOutput(); released = true; activatedEpoch = 0; retainedPreparedEpoch = 0
                PlaybackEndpointResult("ok", final, queueIntentState = metadata)
            }
            "activate" -> {
                if (!startsAllowed || intent != null && intent?.target != state.value.deviceId) {
                    endpoint.suspendOutput(); currentPublication("rejected")
                } else {
                    val handoff = handoffWitness?.takeIf { it.phase == HandoffPhase.AWAITING_ACTIVATION }
                    require(handoff == null || handoffActivationCurrent(handoff, d)) { "Activation did not match the accepted handoff" }
                    val admission = playAdmission?.takeIf { it.id == d.commandId && it.claimEpoch != null }
                    if (admission != null && !admissionCurrent(admission)) {
                        abandonAdmission()
                        return // Retired admission cannot ACK fabricated execution state.
                    }
                    // Activation can arrive without our prepare being observed (including
                    // reconnect). Raw validation must still precede blank first-Play staging.
                    if (!validateHandoff(d)) {
                        // A definite invalid prepare is a terminal rejection, not an
                        // uncertain timeout. Publish the exact untouched native state.
                        if (d.epoch == epoch && authority.isValid()) {
                            acks += PlaybackAck(d.deliveryId, d.epoch, "rejected")
                            retainPublication(currentPublication("rejected"))
                        }
                        return
                    }
                    // Handoff validation suspends BEFORE endpointJob exists. A Pause,
                    // selection ABA or owner loss there must not become a fresh start.
                    if (handoff != null && (!handoffActivationCurrent(handoff, d) || !authority.isValid())) {
                        if (d.epoch == epoch) acks += PlaybackAck(d.deliveryId, d.epoch, "rejected")
                        return
                    }
                    activating = true
                    val activated = try { endpointAction {
                        if (handoff != null && (!handoffActivationCurrent(handoff, d) || !authority.isValid()))
                            return@endpointAction null
                        // Retain cancellation identity through the actual endpoint suspension;
                        // same-target reselection must also cancel this unfinished activation.
                        var latest: PlaybackEndpointResult
                        do {
                            if (admission != null && !admissionCurrent(admission)) return@endpointAction null
                            val payload = admission?.payloadVersion
                            // Replacement stages empty. A captured live Resume is paused, never
                            // retained server music. If latest payload changes across activation,
                            // re-stage before ACK; the obsolete completion grants nothing.
                            val preserve = admission?.queueOrigin
                            val snapshot = if (preserve != null) (d.state ?: PlaybackSnapshot()).validated()
                                else admission?.staging ?: (d.state ?: PlaybackSnapshot()).validated()
                            // A displayed/live queue is a stale-work fence, never import authority.
                            // No prepared contents may be silently replaced by a conflicting room.
                            if (preserve != null && preserve.snapshot.queue.isNotEmpty()) {
                                val before = preserve.metadata
                                val incoming = directiveMetadata(d)
                                if (preserve.snapshot.queueOriginIdentity() != snapshot.queueOriginIdentity() ||
                                    before != null && incoming != null && (before.origins != incoming.origins ||
                                        before.highKeys != incoming.highKeys || before.provenance != incoming.provenance ||
                                        before.receiverId == incoming.receiverId && before.revision != incoming.revision))
                                    return@endpointAction currentPublication("rejected")
                            }
                            val safe = if (admission != null || uncertainConnection || recoverPaused &&
                                state.value.view?.transfer?.sourceDeviceId.isNullOrEmpty()) snapshot.paused() else snapshot
                            latest = if (admission != null && preserve == null && safe.queue.isNotEmpty()) endpoint.activatePreparedResume(safe, authority)
                                else endpoint.activate(safe, if (admission != null && preserve == null) null else directiveMetadata(d), authority)
                            if (admission != null && payload == admission.payloadVersion && latest.status == "ok")
                                admission.stagedVersion = payload
                        } while (admission != null && admissionCurrent(admission) && payload != admission.payloadVersion)
                        latest
                    } } finally { activating = false }
                    if (activated == null || !authority.isValid()) return
                    if (handoff != null) {
                        if (!handoffActivationCurrent(handoff, d)) return
                        handoffWitness = null // Actual, still-current execution completed exactly once.
                    }
                    if (admission != null && !admissionCurrent(admission)) {
                        return // Timeout/fencing, never fabricated state under a successor.
                    }
                    activatedEpoch = epoch; recoverPaused = false
                    if (admission != null && activated.status == "ok") {
                        admission.activatedEpoch = epoch
                        admission.activationDelivery = d.deliveryId
                    }
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
                    if (command.type in setOf("pause", "close")) { retireHandoff(); generation++; actionVersion++; revokeAuthority(); endpoint.suspendOutput() }
                    val current = executionAuthority() ?: return
                    val executed = endpointAction {
                        if (command.type == "queueIntent") {
                            // Never import/refresh a retained display to make a stale intent
                            // valid. A capable owner already has its live incarnation installed.
                            if (!receiverRegistered) return@endpointAction null
                            if (activatedEpoch != epoch) {
                                val expected = QueueIntentBinding.fromJson(command.json.getValue("expected") as kotlinx.serialization.json.JsonObject)
                                if (!acceptedEmptyQueueIntentBinding(expected)) return@endpointAction null
                                activatedEpoch = epoch
                            }
                        } else if (!initializePaused(current)) return@endpointAction null
                        if (command.type == "queueIntent") executeQueueIntent(command, current, d.commandId, d.deliveryId)
                        else endpoint.execute(command, current)
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
        if (d.type == "command" && d.command?.get("type")?.toString() == "\"queueIntent\"") {
            // HTTP acceptance is not completion. An incomplete native implementation must
            // time out/fence, never manufacture a successful detail from its status string.
            requireNotNull(result.queueIntentResult)
        }
        acks += PlaybackAck(d.deliveryId, d.epoch, result.status, result.queueIntentResult)
        retainPublication(result)
        mutableState.value = state.value.copy(exclusiveOwner = authorizedFor(state.value.deviceId, state.value.view!!), needsTap = result.status == "blocked")
    }

    private suspend fun validateHandoff(directive: PlaybackDirective): Boolean = try {
        withTimeout(4000) {
            endpoint.prepare((directive.state ?: PlaybackSnapshot()).validated(), directiveMetadata(directive))
        }
    } catch (error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        false
    }

    private fun requireHandoffSidecars(view: PlaybackDevicesView) {
        if (!queueIntentsNegotiated || !receiverRegistered) return
        val extension = requireNotNull(view.queueIntents) { "Negotiated playback extension missing" }
        val covered = extension.directives.mapTo(HashSet()) { it.deliveryId }
        require(view.directives.filter { it.type == "prepare" || it.type == "activate" }.all { it.deliveryId in covered }) {
            "Negotiated handoff metadata entry missing"
        }
    }

    private fun directiveMetadata(directive: PlaybackDirective): QueueIntentState? {
        val entry = state.value.view?.queueIntents?.directives?.find { it.deliveryId == directive.deliveryId }
        if (queueIntentsNegotiated && receiverRegistered && directive.type in setOf("prepare", "activate"))
            requireNotNull(entry) { "Negotiated handoff metadata entry missing" }
        // Explicit entry.state == null means known legacy provenance. An absent entry
        // above is a malformed negotiated publication, never permission to reset priority.
        return entry?.state
    }

    private suspend fun initializePaused(authority: PlaybackAuthority): Boolean {
        if (activatedEpoch == epoch) return true
        val recovered = withTimeout(4000) { endpoint.activate((state.value.view?.state ?: PlaybackSnapshot()).paused(),
            state.value.view?.queueIntents?.state, authority) }
        if (!authority.isValid()) return false
        require(!recovered.snapshot.playing) { "Paused recovery must remain silent" }
        if (recovered.status != "ok") { message("The accepted queue could not be prepared. Choose music explicitly."); return false }
        activatedEpoch = epoch
        return true
    }

    private suspend fun exchange(missingTarget: String? = null, block: suspend () -> Unit) {
        if (closed || !serviceActive) return
        val life = lifecycle
        val missingSelf = missingTarget == null || missingTarget == state.value.deviceId
        val feedbackAtDispatch = selectionFeedback?.presentation?.token
        try { block() }
        catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            if (life == lifecycle && !closed && serviceActive) failed(error, feedbackAtDispatch, missingSelf)
        }
        catch (error: Exception) { if (life == lifecycle && !closed && serviceActive) failed(error, feedbackAtDispatch, missingSelf) }
    }
    private suspend fun failed(error: Exception, feedbackAtDispatch: Long?, missingSelf: Boolean = true) {
        // Remember only a previously activated same-epoch output, never an initial display.
        // This cannot authorize output or replay work; lifecycle/owner/epoch/selection retire it.
        val retained = if (state.value.deviceId.isNotEmpty() && state.value.activeDeviceId == state.value.deviceId &&
            (activatedEpoch == epoch || retainedPreparedEpoch == epoch)) epoch else 0L
        // Unknown writes are NOT replayed. Stop buffered output immediately, not merely remote UI state.
        uncertainConnection = true
        fenceLocal(); acks.clear(); ackState = null; ackQueueIntentState = null; pendingCommand = null
        retainedPreparedEpoch = retained
        clearSelectionFeedback(feedbackAtDispatch)
        mutableState.value = state.value.copy(connected = false, connecting = false, exclusiveOwner = false,
            messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE,
            recovery = if (state.value.recovery == PlaybackConnectionRecovery.AUTH_REQUIRED) PlaybackConnectionRecovery.AUTH_REQUIRED else PlaybackConnectionRecovery.VERIFICATION_NEEDED,
            message = if (state.value.recovery == PlaybackConnectionRecovery.AUTH_REQUIRED) "Sign in again to connect your players."
                else "Connection or playback changed. The action was not replayed. Retry connection, choose an output, then press Play.")
        // Reconcile a device-scoped conflict through its validated safe view; never replay its action.
        if (error is ApiException && error.status == 401) { authLost(); return }
        if (error is ApiException && error.status == 404) {
            // Rename addresses its TARGET, unlike device-scoped control/poll requests.
            // Its 404 cannot invalidate this live service's issuer registration. A later
            // self poll will independently establish whether our own exact ID expired.
            if (missingSelf) {
                lifecycle++; retainedPreparedEpoch = 0L
                clearSelectionFeedback(); mutableState.value = state.value.copy(deviceId = "")
            }
            return
        }
        if (error is ApiException && error.status == 409) {
            val life = lifecycle
            verifyConnection {
                try {
                    val sent = clockMs()
                    val safe = try { error.responseBody?.let(PlaybackDevicesView::fromJson) } catch (_: IllegalArgumentException) { null }
                    val view = (safe ?: withTimeout(4000) { api.view() }).validated().copy(directives = emptyList())
                    if (life != lifecycle || closed || !serviceActive || !work.isActive) return@verifyConnection
                    consume(view, sent, startsAllowed = false, grantAuthority = false)
                } catch (reconciliationError: Exception) {
                    // A failed reconciliation never restores authority; known auth loss also
                    // must not advertise a connection retry as a sign-in replacement.
                    if (life == lifecycle && !closed && work.isActive && reconciliationError is ApiException && reconciliationError.status == 401) {
                        mutableState.value = state.value.copy(recovery = PlaybackConnectionRecovery.AUTH_REQUIRED,
                            message = "Sign in again to connect your players.", messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE)
                    }
                }
            }
        }
    }
    private fun readOnlyFailure(error: Exception, guidance: String) {
        val authRequired = error is ApiException && error.status == 401 || state.value.recovery == PlaybackConnectionRecovery.AUTH_REQUIRED
        mutableState.value = state.value.copy(
            recovery = when {
                authRequired -> PlaybackConnectionRecovery.AUTH_REQUIRED
                !endpoint.supportsExactHandoff -> PlaybackConnectionRecovery.UNSUPPORTED
                else -> PlaybackConnectionRecovery.VERIFICATION_NEEDED
            },
            message = if (authRequired) "Sign in again to connect your players." else guidance,
            messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE)
    }
    private fun message(value: String) { mutableState.value = state.value.copy(message = value, messagePurpose = PlaybackDevicesMessagePurpose.GUIDANCE) }
    private fun nonce() = UUID.randomUUID().toString()
}
