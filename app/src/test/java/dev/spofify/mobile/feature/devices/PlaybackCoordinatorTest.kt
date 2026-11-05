package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {
    private val track = PlaybackTrack("track", "Song", emptyList(), "Album", "album", 100.0, key = 1, order = 0)
    private val prepared get() = PlaybackSnapshot(queue = listOf(track), position = 0, elapsed = 20.0, duration = 100.0, playing = true,
        occurrence = PlaybackOccurrence(1, "track", "history-event", 19.0, false))
    private fun view(owner: String = "phone", epoch: Long = 1, snapshot: PlaybackSnapshot? = null,
        directives: List<PlaybackDirective> = emptyList(), quarantine: Long = 0, transfer: PlaybackTransfer? = null) = PlaybackDevicesView(
        "phone", listOf(PlaybackDevice("phone", "Phone", true), PlaybackDevice("pc", "PC", true), PlaybackDevice("tablet", "Tablet", true)),
        owner, epoch, if (owner.isEmpty()) 0 else 8000, quarantine, snapshot, 0, 0, transfer, directives, emptyList())

    private class Endpoint : NativePlaybackEndpoint {
        override var supportsExactHandoff = true
        var snapshot = PlaybackSnapshot()
        var stops = 0
        var onSuspend: (() -> Unit)? = null
        var starts = mutableListOf<PlaybackSnapshot>()
        var authority: PlaybackAuthority? = null
        var preparing = 0
        var prepareGate: CompletableDeferred<Unit>? = null
        var activating = 0
        var activateGate: CompletableDeferred<Unit>? = null
        var executeGate: CompletableDeferred<Unit>? = null
        var cancelledActions = 0
        var nextStatus = "ok"
        val executed = mutableListOf<String>()
        override fun suspendOutput() { stops++; onSuspend?.invoke(); snapshot = snapshot.paused() }
        override fun exportSnapshot() = snapshot
        override suspend fun prepare(snapshot: PlaybackSnapshot): Boolean { snapshot.validated(); preparing++; prepareGate?.await(); return true }
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            activating++
            try { activateGate?.await() } catch (error: CancellationException) { cancelledActions++; throw error }
            check(authority.isValid()); this.snapshot = snapshot; starts += snapshot; this.authority = authority
            return PlaybackEndpointResult("ok", snapshot)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
            if (command.type !in setOf("pause", "close")) {
                try { executeGate?.await() } catch (error: CancellationException) { cancelledActions++; throw error }
            }
            check(authority.isValid()); this.authority = authority; executed += command.type
            snapshot = when (command.type) { "resume", "retry" -> snapshot.copy(playing = true, error = ""); "pause" -> snapshot.paused(); "close" -> snapshot.paused().copy(error = ""); else -> snapshot }
            val status = nextStatus; nextStatus = "ok"
            if (status in setOf("blocked", "media")) snapshot = snapshot.paused().copy(error = status)
            return PlaybackEndpointResult(status, snapshot)
        }
    }
    private class Protocol(var current: PlaybackDevicesView) : PlaybackDevicesProtocol {
        var registers = 0; var polls = 0; var leaves = 0; var views = 0; var renames = 0
        var viewGate: CompletableDeferred<Unit>? = null
        var viewFailure: Exception? = null
        var renameFailure: Exception? = null
        var lastState: PlaybackSnapshot? = null
        val sentAcks = mutableListOf<PlaybackAck>()
        val ackStates = mutableListOf<Pair<List<PlaybackAck>, PlaybackSnapshot?>>()
        val selections = mutableListOf<String>()
        val selectionIds = mutableListOf<String>()
        var onTransfer: (suspend (String, String, Long) -> PlaybackDevicesView)? = null
        var transferGate: CompletableDeferred<Unit>? = null
        var pollGate: CompletableDeferred<Unit>? = null
        var lateRenameFailure: CompletableDeferred<Unit>? = null
        var failPoll = false
        var failRegister = false
        var registerGate: CompletableDeferred<Unit>? = null
        var pollFailure: Exception? = null
        var onPoll: (suspend (Long, Long?, PlaybackSnapshot?, List<PlaybackAck>) -> PlaybackDevicesView)? = null
        val commands = mutableListOf<Pair<Long, String>>()
        val commandIds = mutableListOf<String>()
        var echoPendingCommand = false
        override suspend fun view(): PlaybackDevicesView {
            views++; viewGate?.await(); viewFailure?.let { throw it }
            return current.copy(directives = emptyList())
        }
        override suspend fun register(name: String): PlaybackDevicesView {
            registers++; registerGate?.await(); if (failRegister) error("synthetic unknown registration")
            return current
        }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
            polls++; pollGate?.await(); pollFailure?.let { throw it }; if (failPoll) error("synthetic network failure")
            if (acks.any { it.deliveryId.startsWith("release-") }) requireNotNull(state) { "Execution ACK requires actual state" }
            acks.filter { it.status in setOf("blocked", "media") }.forEach {
                requireNotNull(state); require(!state.playing && state.error == it.status) { "Outcome does not match state" }
            }
            if (acks.isNotEmpty()) ackStates += acks to state
            lastState = state; sentAcks += acks
            onPoll?.let { return it(epoch, stateSeq, state, acks) }
            val result = current; current = current.copy(directives = emptyList()); return result
        }
        override suspend fun rename(deviceId: String, name: String): PlaybackDevicesView {
            renames++
            lateRenameFailure?.let { gate -> withContext(NonCancellable) { gate.await(); error("synthetic late failure") } }
            renameFailure?.let { throw it }
            return current
        }
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView {
            commands += epoch to command.type; commandIds += commandId
            return if (echoPendingCommand) current.copy(commands = listOf(PlaybackOutcome(commandId, "pending"))) else current
        }
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
            selections += targetDeviceId; selectionIds += commandId
            onTransfer?.let { return it(commandId, targetDeviceId, selectionSeq) }
            transferGate?.await(); return current
        }
        override suspend fun leave(deviceId: String) { leaves++ }
    }

    @Test fun foregroundUiDoesNotRegisterAndUnsupportedEndpointNeverAdvertises() = runTest {
        val api = Protocol(view()); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        runCurrent(); assertEquals(0, api.registers)
        endpoint.supportsExactHandoff = false; c.setServiceActive(true); runCurrent()
        assertEquals(0, api.registers); assertFalse(c.state.value.exclusiveOwner)
        assertEquals(PlaybackConnectionRecovery.UNSUPPORTED, c.state.value.recovery)
        assertFalse(c.state.value.retryAvailable)
        c.retry(); runCurrent()
        assertTrue(c.state.value.connected); assertFalse(c.state.value.retryAvailable)
        c.close()
        assertEquals(PlaybackConnectionRecovery.OWNER_ENDED, c.state.value.recovery)
    }

    @Test fun readVerificationTracksInitialRetainedFailureAndRecoveryWithoutRegistration() = runTest {
        for (retained in listOf(false, true)) for (status in listOf(500, 401)) {
            val api = Protocol(view(owner = "pc", snapshot = prepared))
            val endpoint = Endpoint()
            val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            assertFalse(c.state.value.retryAvailable)
            if (retained) { c.retry(); runCurrent(); assertTrue(c.state.value.connected) }
            api.viewGate = CompletableDeferred(); api.viewFailure = ApiException(status, "synthetic read failure")
            c.retry(); runCurrent()
            assertTrue(c.state.value.verifyingConnection); assertFalse(c.state.value.retryAvailable)
            api.viewGate!!.complete(Unit); runCurrent()
            assertFalse(c.state.value.verifyingConnection)
            assertEquals(retained, c.state.value.connected) // Cached health is not verification success.
            assertEquals(retained, c.state.value.view != null)
            assertEquals(status != 401, c.state.value.retryAvailable)
            assertEquals(if (status == 401) PlaybackConnectionRecovery.AUTH_REQUIRED else PlaybackConnectionRecovery.VERIFICATION_NEEDED,
                c.state.value.recovery)
            if (status == 401) {
                api.viewFailure = ApiException(500, "still unverified")
                c.retry(); runCurrent()
                assertEquals(PlaybackConnectionRecovery.AUTH_REQUIRED, c.state.value.recovery)
                assertFalse(c.state.value.retryAvailable)
            }
            api.viewFailure = null
            c.retry(); runCurrent()
            assertTrue(c.state.value.connected); assertFalse(c.state.value.retryAvailable)
            assertFalse(c.state.value.verifyingConnection)
            assertEquals("The picker already labels view-only capability", "", c.state.value.message)
            assertEquals(0, api.registers); assertEquals(0, api.polls)
            assertEquals(0, endpoint.stops); assertTrue(endpoint.starts.isEmpty())
            c.close()
        }
    }

    @Test fun queuedReadVerificationsClearOnLifecycleCancellation() = runTest {
        for (end in listOf("stop", "auth", "close", "scope")) {
            val parent = SupervisorJob()
            val ownedScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            val api = Protocol(view()).apply { viewGate = CompletableDeferred() }
            val c = PlaybackCoordinator(api, Endpoint(), ownedScope, clockMs = { testScheduler.currentTime })
            c.retry(); c.retry(); runCurrent()
            assertEquals(1, api.views); assertTrue(c.state.value.verifyingConnection)
            when (end) {
                "stop" -> { c.setServiceActive(true); c.setServiceActive(false) }
                "auth" -> c.authLost()
                "close" -> c.close()
                else -> parent.cancel()
            }
            runCurrent()
            assertFalse(c.state.value.verifyingConnection)
            assertEquals(end == "stop", c.state.value.retryAvailable)
            api.viewGate!!.complete(Unit); runCurrent()
            assertNull(c.state.value.view); assertFalse(c.state.value.verifyingConnection)
            c.close(); parent.cancel(); runCurrent()
        }
    }

    @Test fun pollFailuresExposeRecoveryOnlyAfterVerificationIncludingConflictRead() = runTest {
        for (status in listOf(500, 404, 409, 401)) {
            val api = Protocol(view(owner = "pc", snapshot = prepared))
            val endpoint = Endpoint()
            val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            api.pollGate = CompletableDeferred(); api.pollFailure = ApiException(status, "synthetic poll failure")
            api.viewGate = CompletableDeferred()
            c.retry(); runCurrent()
            assertTrue(c.state.value.verifyingConnection); assertFalse(c.state.value.retryAvailable)
            api.pollGate!!.complete(Unit); runCurrent()
            if (status == 409) {
                assertEquals(2, api.views) // Read-only extension probe precedes registration.
                assertTrue(c.state.value.verifyingConnection); assertFalse(c.state.value.retryAvailable)
                api.viewGate!!.complete(Unit); runCurrent()
                assertTrue(c.state.value.message.contains("not replayed"))
            }
            assertFalse(c.state.value.connected); assertFalse(c.state.value.verifyingConnection)
            assertEquals(status != 401, c.state.value.retryAvailable)
            assertTrue(endpoint.starts.isEmpty()); assertNull(c.currentAuthority())
            c.close()
        }
    }

    @Test fun healthyConnectionGuidanceIsNotConnectionRecovery() = runTest {
        for (healthy in listOf(
            view(owner = ""), view(owner = "").copy(devices = emptyList()),
            view(owner = "pc", snapshot = prepared.paused().copy(error = "blocked")),
            view(owner = "pc", snapshot = prepared.paused().copy(error = "media")),
            view(owner = "", quarantine = 2000),
            view(transfer = PlaybackTransfer("switch", "phone", "pc", "preparing")),
        )) {
            val api = Protocol(healthy)
            val c = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
            if (healthy.devices.isEmpty()) c.retry() else c.setServiceActive(true)
            runCurrent()
            assertTrue(c.state.value.connected); assertFalse(c.state.value.retryAvailable)
            assertFalse(c.state.value.verifyingConnection)
            if (healthy.quarantineMs > 0 || healthy.transfer != null) {
                assertTrue(c.state.value.message.isNotEmpty())
                assertEquals(PlaybackDevicesMessagePurpose.PROGRESS, c.state.value.messagePurpose)
            } else if (healthy.state?.error in setOf("blocked", "media")) {
                assertTrue(c.state.value.message.isNotEmpty())
                assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
            } else assertEquals("", c.state.value.message)
            c.rename("phone", " "); runCurrent()
            assertTrue(c.state.value.message.contains("1–64")); assertFalse(c.state.value.retryAvailable)
            assertEquals(0, api.renames)
            c.close()
        }
    }

    @Test fun readOnlyRenameUncertaintyNeedsVerificationNotBlindWriteReplay() = runTest {
        for (status in listOf(500, 401)) {
            val api = Protocol(view(owner = "pc"))
            val c = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
            c.retry(); runCurrent()
            api.renameFailure = ApiException(status, "synthetic unknown rename")
            c.rename("pc", "New name"); runCurrent()
            assertTrue(c.state.value.connected); assertFalse(c.state.value.verifyingConnection)
            assertEquals(status != 401, c.state.value.retryAvailable)
            assertTrue(c.state.value.message.isNotEmpty())
            c.retry(); runCurrent()
            assertFalse(c.state.value.retryAvailable); assertEquals(1, api.renames)
            assertEquals("PC", c.state.value.devices.first { it.deviceId == "pc" }.name)
            assertEquals(0, api.registers); assertEquals(0, api.polls)
            c.close()
        }
    }

    @Test fun firstRegistrationPreservesTheGenuinePreBindPlaybackGeneration() = runTest {
        val api = Protocol(view()).apply { registerGate = CompletableDeferred() }
        val endpoint = Endpoint().apply { onSuspend = { dev.spofify.mobile.playback.PlaybackRequests.invalidate() } }
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        val tapEpoch = dev.spofify.mobile.playback.PlaybackRequests.epoch
        c.setServiceActive(true); runCurrent()
        var accepted = false
        c.localAction { permit ->
            accepted = tapEpoch == dev.spofify.mobile.playback.PlaybackRequests.epoch && permit.isValid()
            PlaybackEndpointResult(if (accepted) "ok" else "rejected", endpoint.snapshot)
        }
        runCurrent(); assertFalse(accepted)
        api.registerGate!!.complete(Unit); runCurrent()
        assertTrue(accepted)
        assertEquals(tapEpoch, dev.spofify.mobile.playback.PlaybackRequests.epoch)
        c.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        assertTrue(dev.spofify.mobile.playback.PlaybackRequests.epoch > tapEpoch)
        c.close()
    }

    @Test fun remoteDisplayDoesNotImportStartOrCreateLocalOccurrence() = runTest {
        val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        assertTrue(endpoint.starts.isEmpty()); assertTrue(endpoint.snapshot.queue.isEmpty())
        assertEquals("history-event", c.state.value.view?.state?.occurrence?.eventId)
        assertNull(c.currentAuthority()); assertFalse(c.state.value.exclusiveOwner)
        c.close()
    }

    @Test fun prepareIsValidationOnlyThenActivationImportsExactPreparedQueue() = runTest {
        val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        api.current = view(owner = "pc", snapshot = prepared, directives = listOf(PlaybackDirective("prepare-1", 1, "prepare", "switch", state = prepared)))
        c.retry(); runCurrent()
        assertEquals(1, endpoint.preparing); assertTrue(endpoint.starts.isEmpty())
        api.current = view(epoch = 2, snapshot = prepared, transfer = PlaybackTransfer("switch", "pc", "phone", "activating"),
            directives = listOf(PlaybackDirective("activate-1", 2, "activate", "switch", state = prepared)))
        c.retry(); runCurrent()
        assertEquals(prepared, endpoint.starts.single())
        c.retry(); runCurrent()
        assertTrue(api.sentAcks.any { it.deliveryId == "activate-1" && it.epoch == 2L && it.status == "ok" })
        assertEquals(prepared.occurrence, api.lastState?.occurrence)
        assertTrue(c.state.value.exclusiveOwner)
        c.close()
    }

    @Test fun incomingLegacyToggleUsesActualOutputInsteadOfStaleRemoteView() = runTest {
        val api = Protocol(view(snapshot = prepared.paused())); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        assertTrue(endpoint.snapshot.playing)
        // Server display incorrectly still says paused: the owner must pause, not resume.
        val toggle = kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("toggle")) }
        api.current = view(snapshot = prepared.paused(), directives = listOf(PlaybackDirective("toggle-pause", 1, "command", command = toggle)))
        c.retry(); runCurrent()
        assertEquals("pause", endpoint.executed.last()); assertFalse(endpoint.snapshot.playing)
        // Conversely, stale playing display must not prevent an explicit legacy resume.
        api.current = view(snapshot = prepared, directives = listOf(PlaybackDirective("toggle-resume", 1, "command", command = toggle)))
        c.retry(); runCurrent()
        assertEquals("resume", endpoint.executed.last()); assertTrue(endpoint.snapshot.playing)
        c.close()
    }

    @Test fun releaseStopsBufferedOutputAndAckCarriesExactFinalIntentAndPosition() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        endpoint.snapshot = prepared
        api.current = view(snapshot = prepared, directives = listOf(PlaybackDirective("release", 1, "release", "switch")))
        c.retry(); runCurrent()
        assertFalse(endpoint.snapshot.playing); assertNull(c.currentAuthority())
        api.current = view(snapshot = prepared)
        c.retry(); runCurrent()
        assertTrue(api.sentAcks.any { it.deliveryId == "release" && it.status == "ok" })
        assertEquals(prepared, api.lastState) // playing intent retained, but output is actually stopped
        c.close()
    }

    @Test fun blockedAndMediaDirectiveResultsAreAckedBeforeNewCloseOrSuccessfulRetry() = runTest {
        for (failure in listOf("blocked", "media")) for (next in listOf("close", "retry")) {
            val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
            val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
            endpoint.nextStatus = failure
            api.current = view(snapshot = prepared, directives = listOf(PlaybackDirective("failed-$failure-$next", 1,
                "command", command = PlaybackDeviceCommand.transport("resume").json)))
            c.retry(); runCurrent()
            assertEquals(failure, endpoint.snapshot.error)
            c.remoteCommand(PlaybackDeviceCommand.transport(next)); runCurrent()
            val paired = api.ackStates.single { (acks, _) -> acks.any { it.deliveryId == "failed-$failure-$next" } }
            assertEquals(failure, paired.second?.error)
            assertFalse(paired.second!!.playing)
            assertEquals(next, endpoint.executed.last())
            assertEquals("", endpoint.snapshot.error)
            assertTrue(c.state.value.connected)
            assertFalse(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
            c.close()
        }
    }

    @Test fun pauseAndCloseAfterReleaseKeepTheRequiredAckSnapshotAtomic() = runTest {
        for (interruption in listOf("pause", "close")) {
            val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
            val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
            api.current = view(snapshot = prepared, transfer = PlaybackTransfer("switch", "phone", "pc", "releasing"),
                directives = listOf(PlaybackDirective("release-$interruption", 1, "release", "switch")))
            c.retry(); runCurrent()
            assertFalse(endpoint.snapshot.playing)
            // Interruption lands after execution but BEFORE the poll that transmits the ACK.
            c.remoteCommand(PlaybackDeviceCommand.transport(interruption)); runCurrent()
            c.retry(); runCurrent()
            val paired = api.ackStates.single { (acks, _) -> acks.any { it.deliveryId == "release-$interruption" } }
            assertNotNull(paired.second)
            assertEquals(prepared.paused(), paired.second)
            // Subsequent polls are no longer the owner and correctly carry no state.
            assertNull(api.lastState)
            c.close()
        }
    }

    @Test fun leaseExpiresDuringBoundedStalledPollAndStopsActualOutput() = runTest {
        val api = Protocol(view(snapshot = prepared).copy(leaseMs = 3500)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        assertTrue(endpoint.snapshot.playing)
        api.pollGate = CompletableDeferred()
        val stops = endpoint.stops
        advanceTimeBy(1001); runCurrent() // heartbeat now stalled, before its 4-second timeout
        assertTrue(endpoint.snapshot.playing)
        advanceTimeBy(500); runCurrent() // conservative 1500ms lease deadline
        assertFalse(endpoint.snapshot.playing); assertNull(c.currentAuthority())
        assertTrue(endpoint.stops > stops)
        assertEquals(PlaybackConnectionRecovery.VERIFICATION_NEEDED, c.state.value.recovery)
        assertTrue(c.state.value.verifyingConnection); assertFalse(c.state.value.retryAvailable)
        advanceTimeBy(3500); runCurrent() // Existing bounded poll times out, without a new attempt yet.
        assertFalse(c.state.value.verifyingConnection); assertTrue(c.state.value.retryAvailable)
        c.close()
    }

    @Test fun reconnectDoesNotAutoplayAndExplicitResumeCanRecoverPausedQueue() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        api.failPoll = true; c.retry(); runCurrent()
        api.failPoll = false; c.retry(); runCurrent()
        assertTrue(endpoint.starts.isEmpty()); assertFalse(endpoint.snapshot.playing)
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        assertEquals(1, endpoint.starts.size); assertFalse(endpoint.starts.single().playing)
        assertTrue(endpoint.snapshot.playing)
        c.close()
    }

    @Test fun anObservedRemoteOutcomeDisappearingDoesNotLockControlsOrReplayIt() = runTest {
        val api = Protocol(view(owner = "pc", snapshot = prepared)).apply { echoPendingCommand = true }
        val c = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("next")); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("next")); runCurrent()
        assertEquals(1, api.commands.size)
        c.retry(); runCurrent() // Successful view no longer retains the observed outcome.
        assertTrue(c.state.value.message.contains("not replayed"))
        c.remoteCommand(PlaybackDeviceCommand.transport("previous")); runCurrent()
        assertEquals(listOf("next", "previous"), api.commands.map { it.second })
        c.close()
    }

    @Test fun latestUnsentSelectionCoalescesAndPickerRemainsResponsive() = runTest {
        val api = Protocol(view()); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        val gate = CompletableDeferred<Unit>(); api.transferGate = gate
        c.select("pc"); runCurrent()
        c.select("phone"); c.select("tablet"); runCurrent()
        assertEquals("tablet", c.state.value.selectedDeviceId)
        assertTrue(c.state.value.selectionUnconfirmed)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("pc", "tablet"), api.selections)
        c.close()
    }

    @Test fun quietSelectionFeedbackFencesSameTargetAbaAndTracksAcceptedTransferAfterRpc() = runTest {
        val api = Protocol(view()); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        val first = CompletableDeferred<Unit>(); val latest = CompletableDeferred<Unit>()
        api.onTransfer = { id, target, _ ->
            if (api.selections.size == 1) first.await() else latest.await()
            view(transfer = PlaybackTransfer(id, "phone", target, "preparing"))
        }
        c.select("pc"); runCurrent()
        val old = c.state.value.pendingSelection!!
        c.select("tablet")
        val middle = c.state.value.pendingSelection!!
        c.select("pc")
        val newest = c.state.value.pendingSelection!!
        assertEquals("pc", newest.deviceId)
        assertTrue(old.token != middle.token && middle.token != newest.token && old.token != newest.token)
        assertEquals(PlaybackDevicesMessagePurpose.PROGRESS, c.state.value.messagePurpose)
        assertEquals("phone", c.state.value.activeDeviceId) // Never optimistic authority.
        first.complete(Unit); runCurrent()
        assertEquals(listOf("pc", "pc"), api.selections) // Only latest unsent choice is sent.
        assertEquals(newest, c.state.value.pendingSelection)
        latest.complete(Unit); runCurrent()
        assertFalse(c.state.value.selectionUnconfirmed)
        assertEquals(newest, c.state.value.pendingSelection) // RPC return is not transfer completion.
        val id = api.selectionIds.last()
        api.current = view(transfer = PlaybackTransfer(id, "phone", "pc", "releasing"))
        repeat(12) { advanceTimeBy(1000); runCurrent(); assertEquals(newest, c.state.value.pendingSelection) }
        api.current = view(owner = "pc", epoch = 2)
        c.retry(); runCurrent()
        assertNull(c.state.value.pendingSelection)
        assertEquals("pc", c.state.value.activeDeviceId)
        assertEquals(1, api.registers); assertEquals(2, api.selections.size)
        assertTrue(endpoint.starts.isEmpty()); assertTrue(endpoint.executed.isEmpty())
        c.close()
    }

    @Test fun oldSelectionFailureCannotClearNewerRingAndGuidanceSurvivesProgressHeartbeats() = runTest {
        for (sameTarget in listOf(false, true)) {
            val api = Protocol(view()); val endpoint = Endpoint()
            val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            val first = CompletableDeferred<Unit>(); val latest = CompletableDeferred<Unit>()
            api.onTransfer = { id, target, _ ->
                if (api.selections.size == 1) { first.await(); error("synthetic unknown old write") }
                latest.await()
                view(transfer = PlaybackTransfer(id, "phone", target, "preparing"))
            }
            c.select("pc"); runCurrent()
            c.select("tablet")
            if (sameTarget) c.select("pc")
            val newest = c.state.value.pendingSelection!!
            first.complete(Unit); runCurrent()
            assertEquals(newest, c.state.value.pendingSelection)
            assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
            assertTrue(c.state.value.message.contains("not replayed"))
            latest.complete(Unit); runCurrent()
            val id = api.selectionIds.last()
            api.current = view(transfer = PlaybackTransfer(id, "phone", newest.deviceId, "preparing"))
            repeat(12) {
                advanceTimeBy(1000); runCurrent()
                assertEquals(newest, c.state.value.pendingSelection)
                assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
                assertTrue(c.state.value.message.contains("not replayed"))
            }
            assertEquals(2, api.selections.size); assertTrue(endpoint.starts.isEmpty())
            c.close()
        }
    }

    @Test fun olderTerminalSelectionResponseCannotCompleteTheLatestSameTargetRequest() = runTest {
        for (status in listOf("ok", "rejected", "timeout")) {
            val api = Protocol(view())
            val c = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            val first = CompletableDeferred<Unit>(); val latest = CompletableDeferred<Unit>()
            api.onTransfer = { id, target, _ ->
                if (api.selections.size == 1) {
                    first.await()
                    view(quarantine = 2000, transfer = PlaybackTransfer(id, "phone", target, "waiting"))
                        .copy(commands = listOf(PlaybackOutcome(id, status)))
                } else {
                    latest.await()
                    view(transfer = PlaybackTransfer(id, "phone", target, "preparing"))
                }
            }
            c.select("pc"); runCurrent()
            c.select("tablet"); c.select("pc")
            val newest = c.state.value.pendingSelection!!
            first.complete(Unit); runCurrent()
            assertEquals(newest, c.state.value.pendingSelection)
            if (status != "ok") {
                assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
                assertTrue(c.state.value.message.contains("not replayed"))
            }
            latest.complete(Unit); runCurrent()
            assertEquals(newest, c.state.value.pendingSelection)
            if (status != "ok") assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
            assertEquals(listOf("pc", "pc"), api.selections)
            c.close()
        }
    }

    @Test fun terminalSelectionFailuresWinOverTransferAndQuarantineProgressAndDoNotReviveRing() = runTest {
        for (status in listOf("timeout", "cancelled", "rejected", "media", "blocked")) {
            val api = Protocol(view())
            val c = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            api.onTransfer = { id, target, _ -> view(transfer = PlaybackTransfer(id, "phone", target, "preparing")) }
            c.select("pc"); runCurrent()
            assertNotNull(c.state.value.pendingSelection)
            val id = api.selectionIds.single()
            api.current = view(quarantine = 2000, transfer = PlaybackTransfer(id, "phone", "pc", "waiting"))
                .copy(commands = listOf(PlaybackOutcome(id, status)))
            c.retry(); runCurrent()
            assertNull(c.state.value.pendingSelection)
            assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
            assertTrue(c.state.value.message.contains("not replayed"))
            api.current = api.current.copy(commands = emptyList()) // Stale transfer metadata cannot revive it.
            repeat(3) {
                advanceTimeBy(1000); runCurrent()
                assertNull(c.state.value.pendingSelection)
                assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
            }
            assertEquals(1, api.selections.size)
            c.close()
        }
    }

    @Test fun commandFailureAndForgottenOutcomeWinEvenDuringAnotherPendingSelection() = runTest {
        for (status in listOf("timeout", "cancelled", "rejected", "media", "blocked", "forgotten", "pending", "ok", "missing-unobserved")) {
            val api = Protocol(view(owner = "pc", snapshot = prepared)).apply { echoPendingCommand = status != "missing-unobserved" }
            val c = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            val results = mutableListOf<String>()
            c.remoteCommandWithResult(PlaybackDeviceCommand.transport("next"), isCurrent = { true }, onResult = { results += it })
            runCurrent()
            assertTrue(results.isEmpty())
            val gate = CompletableDeferred<Unit>(); api.pollGate = gate
            c.retry(); runCurrent() // Poll snapshots the old command before a newer choice retires it.
            c.select("tablet")
            val newest = c.state.value.pendingSelection!!
            assertEquals(listOf("cancelled"), results)
            val transferGate = CompletableDeferred<Unit>(); api.transferGate = transferGate
            api.current = view(owner = "pc", quarantine = 2000,
                transfer = PlaybackTransfer("older-transfer", "pc", "phone", "waiting"))
                .copy(commands = if (status in setOf("forgotten", "missing-unobserved")) emptyList()
                    else listOf(PlaybackOutcome(api.commandIds.single(), status)))
            gate.complete(Unit); runCurrent()
            val failure = status !in setOf("pending", "ok", "missing-unobserved")
            assertEquals(status, newest, c.state.value.pendingSelection)
            assertEquals(status, if (failure) PlaybackDevicesMessagePurpose.GUIDANCE else PlaybackDevicesMessagePurpose.PROGRESS,
                c.state.value.messagePurpose)
            assertEquals(status, failure, c.state.value.message.contains("not replayed"))
            assertEquals("Presentation must not complete a retired command again", listOf("cancelled"), results)
            assertEquals(1, api.commands.size)
            c.close(); transferGate.complete(Unit); runCurrent()
            assertEquals(listOf("cancelled"), results)
        }
    }

    @Test fun outputFailuresRemainGuidanceWhenTransferAndQuarantineAreAlsoPresent() = runTest {
        for (error in listOf("blocked", "media")) {
            val api = Protocol(view(owner = "pc", snapshot = prepared.paused().copy(error = error), quarantine = 2000,
                transfer = PlaybackTransfer("external", "pc", "tablet", "waiting")))
            val c = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            assertNotNull(c.state.value.pendingSelection)
            assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
            assertTrue(c.state.value.message.isNotEmpty())
            assertFalse(c.state.value.needsTap) // A remote blocked output does not enable local Continue.
            c.close()
        }
    }

    @Test fun ordinaryHeartbeatsAndQuarantineAloneNeverCreateSelectionFeedback() = runTest {
        val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        repeat(12) { tick ->
            api.current = view(owner = "pc", snapshot = prepared.copy(elapsed = 20.0 + tick), quarantine = if (tick % 2 == 0) 2000 else 0)
            advanceTimeBy(1000); runCurrent()
            assertNull(c.state.value.pendingSelection)
        }
        api.current = view(owner = "pc", transfer = PlaybackTransfer("external", "pc", "tablet", "preparing"))
        c.retry(); runCurrent()
        val observed = c.state.value.pendingSelection!!
        assertEquals("tablet", observed.deviceId)
        repeat(12) { advanceTimeBy(1000); runCurrent(); assertEquals(observed, c.state.value.pendingSelection) }
        api.current = view(owner = "tablet", epoch = 2)
        c.retry(); runCurrent(); assertNull(c.state.value.pendingSelection)
        assertTrue(api.selections.isEmpty()); assertTrue(api.commands.isEmpty()); assertTrue(endpoint.starts.isEmpty())
        c.close()
    }

    @Test fun selectionTimeoutAndAllLifecycleEndsClearFeedbackWithoutReplay() = runTest {
        for (end in listOf("timeout", "stop", "auth", "close", "scope")) {
            val parent = SupervisorJob()
            val ownedScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            val api = Protocol(view()).apply { transferGate = CompletableDeferred() }
            val c = PlaybackCoordinator(api, Endpoint(), ownedScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            c.select("pc"); runCurrent(); assertNotNull(c.state.value.pendingSelection)
            when (end) {
                "timeout" -> { advanceTimeBy(4000); runCurrent() }
                "stop" -> c.setServiceActive(false)
                "auth" -> c.authLost()
                "close" -> c.close()
                "scope" -> parent.cancel()
            }
            runCurrent(); assertNull(c.state.value.pendingSelection)
            assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
            val sent = api.selections.size
            api.transferGate!!.complete(Unit); runCurrent()
            assertNull(c.state.value.pendingSelection); assertEquals(sent, api.selections.size)
            c.close(); parent.cancel(); runCurrent()
        }
    }

    @Test fun disposedSelectionResponseCannotReviveFeedbackOrClearReplacementRequest() = runTest {
        val endpoint = Endpoint(); val oldApi = Protocol(view())
        val old = PlaybackCoordinator(oldApi, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        old.setServiceActive(true); runCurrent()
        val gate = CompletableDeferred<Unit>()
        oldApi.onTransfer = { id, target, _ ->
            withContext(NonCancellable) { gate.await() }
            view(transfer = PlaybackTransfer(id, "phone", target, "preparing"))
        }
        old.select("pc"); runCurrent(); assertNotNull(old.state.value.pendingSelection)
        old.close(); assertNull(old.state.value.pendingSelection)
        val api = Protocol(view()).apply { transferGate = CompletableDeferred() }
        val replacement = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        replacement.setServiceActive(true); runCurrent()
        replacement.select("tablet"); runCurrent()
        val newest = replacement.state.value.pendingSelection!!
        val stops = endpoint.stops
        gate.complete(Unit); runCurrent()
        assertNull(old.state.value.pendingSelection)
        assertEquals(newest, replacement.state.value.pendingSelection)
        assertEquals(stops, endpoint.stops)
        replacement.close(); api.transferGate!!.complete(Unit); runCurrent()
    }

    @Test fun validationAndUnavailableGuidanceRemainReadableAlongsidePendingSelection() = runTest {
        val api = Protocol(view()).apply { transferGate = CompletableDeferred() }
        val c = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.select("pc"); runCurrent()
        val pending = c.state.value.pendingSelection
        c.rename("phone", " ")
        assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
        assertTrue(c.state.value.message.contains("1–64")); assertEquals(pending, c.state.value.pendingSelection)
        c.select("missing")
        assertTrue(c.state.value.message.contains("unavailable")); assertEquals(pending, c.state.value.pendingSelection)
        c.select("tablet")
        assertEquals("tablet", c.state.value.pendingSelection?.deviceId)
        assertTrue(c.state.value.message.contains("unavailable"))
        assertEquals(PlaybackDevicesMessagePurpose.GUIDANCE, c.state.value.messagePurpose)
        assertEquals(0, api.renames)
        c.close(); api.transferGate!!.complete(Unit); runCurrent()
    }

    @Test fun readOnlyBrowsingAndDisposalNeverAdvertiseOrStopUnrelatedNativeAudio() = runTest {
        val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
        endpoint.supportsExactHandoff = false; endpoint.snapshot = prepared
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); c.retry(); runCurrent()
        assertEquals("pc", c.state.value.activeDeviceId)
        assertEquals(0, api.registers); assertEquals(0, api.polls); assertEquals(0, endpoint.stops)
        c.close()
        assertEquals(0, endpoint.stops); assertTrue(endpoint.snapshot.playing)
    }

    @Test fun selectingAnotherOutputKeepsHealthySourceUntilAcknowledgedRelease() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        val stops = endpoint.stops
        c.select("pc"); runCurrent()
        assertEquals(stops, endpoint.stops); assertTrue(endpoint.snapshot.playing)
        api.current = view(snapshot = prepared, directives = listOf(PlaybackDirective("release-after-selection", 1, "release", "switch")))
        c.retry(); runCurrent(); assertFalse(endpoint.snapshot.playing)
        c.retry(); runCurrent()
        assertTrue(api.sentAcks.any { it.deliveryId == "release-after-selection" && it.status == "ok" })
        c.close()
    }

    @Test fun newRegistrationNeverPublishesOldEndpointQueueBeforeInitialization() = runTest {
        val api = Protocol(view()); val endpoint = Endpoint(); endpoint.snapshot = prepared
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        assertNull(api.lastState); assertNull(c.currentAuthority())
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        assertTrue(endpoint.starts.single().queue.isEmpty())
        assertTrue(api.lastState!!.queue.isEmpty())
        c.close()
    }

    @Test fun uncertainReconnectActivationIsPausedEvenIfOldSourceIntentSaidPlaying() = runTest {
        val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        api.failPoll = true; c.retry(); runCurrent(); api.failPoll = false
        api.current = view(epoch = 2, snapshot = prepared, transfer = PlaybackTransfer("switch", "pc", "phone", "activating"),
            directives = listOf(PlaybackDirective("late-activate", 2, "activate", "switch", state = prepared)))
        c.retry(); runCurrent()
        assertFalse(endpoint.starts.single().playing)
        c.close()
    }

    @Test fun lateFailureFromDisposedCoordinatorCannotStopReplacementOutput() = runTest {
        val endpoint = Endpoint(); val oldApi = Protocol(view(snapshot = prepared))
        val old = PlaybackCoordinator(oldApi, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        old.setServiceActive(true); runCurrent()
        val gate = CompletableDeferred<Unit>(); oldApi.lateRenameFailure = gate
        old.rename("phone", "New name"); runCurrent()
        old.close()
        val replacement = PlaybackCoordinator(Protocol(view(snapshot = prepared)), endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        replacement.setServiceActive(true); runCurrent()
        replacement.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        assertTrue(endpoint.snapshot.playing)
        val stops = endpoint.stops
        gate.complete(Unit); runCurrent()
        assertEquals(stops, endpoint.stops); assertTrue(endpoint.snapshot.playing)
        replacement.close()
    }

    @Test fun cancellationOfCallerScopeFailsClosedWithoutExplicitClose() = runTest {
        val parent = SupervisorJob()
        val ownedScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, ownedScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        val held = endpoint.authority!!; assertTrue(held.isValid()); assertTrue(endpoint.snapshot.playing)
        parent.cancel(); assertFalse(held.isValid()); runCurrent()
        assertFalse(endpoint.snapshot.playing); assertFalse(c.state.value.exclusiveOwner)
        assertEquals(PlaybackConnectionRecovery.OWNER_ENDED, c.state.value.recovery)
        assertFalse(c.state.value.verifyingConnection); assertFalse(c.state.value.retryAvailable)
        c.close()
    }

    @Test fun callerCancellationStopsBeforeNonCancellableChildCompletes() = runTest {
        val parent = SupervisorJob()
        val ownedScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, ownedScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        val gate = CompletableDeferred<Unit>(); api.lateRenameFailure = gate
        c.rename("phone", "New name"); runCurrent()
        assertTrue(endpoint.snapshot.playing)
        parent.cancel(); runCurrent()
        assertFalse("Buffered output must stop before the blocked child completes", endpoint.snapshot.playing)
        assertFalse(parent.isCompleted)
        gate.complete(Unit); runCurrent()
        c.close()
    }

    @Test fun initialBindingPreservesPendingPlayButStoppingBeforeRegistrationStillFences() = runTest {
        val api = Protocol(view()).apply { registerGate = CompletableDeferred() }; val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true)
        assertEquals("Binding must not invalidate a pre-bind user request", 0, endpoint.stops)
        runCurrent(); assertEquals("", c.state.value.deviceId)
        assertTrue(c.state.value.connecting); assertTrue(c.state.value.verifyingConnection)
        assertFalse(c.state.value.retryAvailable)
        c.setServiceActive(false)
        assertFalse(c.state.value.connecting); assertFalse(c.state.value.verifyingConnection)
        assertTrue(c.state.value.retryAvailable)
        assertTrue(endpoint.stops > 0)
        assertNull(c.currentAuthority())
        c.close()
    }

    @Test fun pauseBeforeServiceBindingCancelsPendingNativeIntent() = runTest {
        val endpoint = Endpoint().apply { snapshot = prepared }
        val c = PlaybackCoordinator(Protocol(view()), endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.remoteCommand(PlaybackDeviceCommand.transport("pause"))
        assertEquals(1, endpoint.stops); assertFalse(endpoint.snapshot.playing)
        c.close()
    }

    @Test fun changingSelectionCancelsSuspendedLocalActionAndInvalidatesItsAuthority() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        var held: PlaybackAuthority? = null
        var cancelled = false
        c.localAction { authority ->
            held = authority
            try { awaitCancellation() } finally { cancelled = true }
        }
        runCurrent(); assertTrue(held!!.isValid())
        c.select("pc")
        assertFalse(held!!.isValid())
        runCurrent(); assertTrue(cancelled)
        assertEquals(listOf("pc"), api.selections)
        c.close()
    }

    @Test fun heartbeatSpacesUnknownRegistrationAtLeastThirtySecondsAndManualRetryCannotStorm() = runTest {
        val api = Protocol(view()).apply { failRegister = true }; val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent(); assertEquals(1, api.registers)
        assertTrue(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        assertFalse(c.state.value.connecting)
        repeat(10) { c.retry(); runCurrent() }
        assertTrue(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        // Real persistent broker retains an unknown accepted row for >=30s. This floor
        // reduces avoidable churn; it does not prove expiry after delayed acceptance.
        advanceTimeBy(29999); runCurrent(); assertEquals(1, api.registers)
        advanceTimeBy(1); runCurrent(); assertEquals(2, api.registers)
        advanceTimeBy(29999); runCurrent(); assertEquals(2, api.registers)
        advanceTimeBy(1); runCurrent(); assertEquals(3, api.registers)
        api.failRegister = false
        advanceTimeBy(29999); runCurrent(); assertEquals(3, api.registers)
        advanceTimeBy(1); runCurrent(); assertEquals(4, api.registers)
        assertEquals("phone", c.state.value.deviceId)
        assertFalse(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        assertTrue(endpoint.starts.isEmpty())
        c.close()
    }

    @Test fun forgottenServerIdIsRegisteredAgainByHeartbeatWithoutAutoplay() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        api.pollFailure = ApiException(404, "gone")
        c.retry(); runCurrent(); assertEquals("", c.state.value.deviceId)
        assertTrue(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        api.pollFailure = null
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, api.registers); assertEquals("phone", c.state.value.deviceId)
        assertTrue(endpoint.starts.isEmpty()); assertFalse(endpoint.snapshot.playing)
        c.close()
    }

    @Test fun explicitLocalActionWaitsOnceForRegistrationInitializesPausedAndPublishesExactResult() = runTest {
        val api = Protocol(view(snapshot = prepared)).apply { registerGate = CompletableDeferred() }
        val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        var calls = 0
        val result = prepared.copy(elapsed = 42.0)
        c.setServiceActive(true); runCurrent()
        c.localAction { authority ->
            assertTrue(authority.isValid()); assertFalse(endpoint.starts.single().playing)
            calls++; PlaybackEndpointResult("ok", result)
        }
        c.localAction { fail("Only one pending action is admitted"); PlaybackEndpointResult("ok", prepared) }
        runCurrent(); assertEquals(0, calls)
        api.registerGate!!.complete(Unit); runCurrent()
        assertEquals(1, calls); assertEquals(result, api.lastState)
        assertTrue(api.commands.isEmpty())
        c.close()
    }

    @Test fun localActionRejectsRemoteOutputAndNeverRunsAfterFailedInitialRegistration() = runTest {
        val api = Protocol(view(owner = "pc")); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.localAction { fail("Remote output cannot grant local authority"); PlaybackEndpointResult("ok", prepared) }
        runCurrent(); assertTrue(c.state.value.message.contains("this phone")); assertTrue(api.commands.isEmpty())
        c.close()
        val failingApi = Protocol(view()).apply { failRegister = true; registerGate = CompletableDeferred() }
        val next = PlaybackCoordinator(failingApi, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        next.setServiceActive(true); runCurrent()
        next.localAction { fail("Failed registration must not replay user intent"); PlaybackEndpointResult("ok", prepared) }
        failingApi.registerGate!!.complete(Unit); runCurrent()
        failingApi.failRegister = false
        advanceTimeBy(8000); runCurrent()
        assertEquals("Unknown accepted registration cannot be retried at the old eight-second interval", "", next.state.value.deviceId)
        advanceTimeBy(22000); runCurrent()
        assertEquals("phone", next.state.value.deviceId); assertTrue(endpoint.starts.isEmpty())
        next.close()
    }

    @Test fun pauseBeforeRegistrationCompletesInvalidatesQueuedPlaySynchronously() = runTest {
        val api = Protocol(view(snapshot = prepared)).apply { registerGate = CompletableDeferred() }; val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.localAction { fail("Paused pre-registration Play must not run"); PlaybackEndpointResult("ok", prepared) }
        val stops = endpoint.stops
        c.remoteCommand(PlaybackDeviceCommand.transport("pause"))
        assertTrue(endpoint.stops > stops)
        api.registerGate!!.complete(Unit); runCurrent()
        assertFalse(endpoint.snapshot.playing); assertEquals(listOf("pause"), endpoint.executed)
        c.close()
    }

    @Test fun pauseCancelsSuspendedExplicitActionWithoutCancellingHeartbeat() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        var cancelled = false
        var held: PlaybackAuthority? = null
        c.localAction { authority ->
            held = authority
            try { awaitCancellation() } finally { cancelled = true }
        }
        runCurrent(); assertTrue(held!!.isValid())
        c.remoteCommand(PlaybackDeviceCommand.transport("pause"))
        assertFalse(held!!.isValid()); assertFalse(endpoint.snapshot.playing)
        runCurrent(); assertTrue(cancelled); assertEquals(listOf("pause"), endpoint.executed)
        val polls = api.polls
        advanceTimeBy(1000); runCurrent(); assertTrue(api.polls > polls)
        c.close()
    }

    @Test fun pauseCancelsSuspendedEndpointResumeAndCloseCancelsAnotherAction() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint().apply { executeGate = CompletableDeferred() }
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        assertEquals(1, endpoint.cancelledActions); assertEquals(listOf("pause"), endpoint.executed)
        var cancelled = false
        c.localAction { try { awaitCancellation() } finally { cancelled = true } }; runCurrent()
        val stops = endpoint.stops
        c.close(); assertTrue(endpoint.stops > stops)
        runCurrent(); assertTrue(cancelled)
    }

    @Test fun pausedInFlightResponseCannotStartActivationOrResumeWithNewAuthority() = runTest {
        for (directive in listOf(
            PlaybackDirective("late-start", 2, "activate", "switch", state = prepared),
            PlaybackDirective("late-command", 2, "command", command = PlaybackDeviceCommand.transport("resume").json),
        )) {
            val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
            val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            val gate = CompletableDeferred<Unit>(); api.pollGate = gate
            c.retry(); runCurrent()
            c.remoteCommand(PlaybackDeviceCommand.transport("pause"))
            api.current = view(epoch = 2, snapshot = prepared,
                transfer = PlaybackTransfer("switch", "pc", "phone", "activating"), directives = listOf(directive))
            gate.complete(Unit); runCurrent()
            assertTrue(endpoint.starts.isEmpty()); assertFalse(endpoint.snapshot.playing)
            c.retry(); runCurrent()
            assertTrue(api.sentAcks.any { it.deliveryId == directive.deliveryId && it.status == "rejected" })
            assertFalse(api.sentAcks.any { it.deliveryId == directive.deliveryId && it.status == "ok" })
            c.close()
        }
    }

    @Test fun pauseCancelsHeartbeatActivationChildButNotItsParentPoll() = runTest {
        val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        endpoint.activateGate = CompletableDeferred()
        api.current = view(epoch = 2, snapshot = prepared, transfer = PlaybackTransfer("switch", "pc", "phone", "activating"),
            directives = listOf(PlaybackDirective("activation", 2, "activate", "switch", state = prepared)))
        advanceTimeBy(1000); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        assertEquals(1, endpoint.cancelledActions)
        assertFalse(api.sentAcks.any { it.deliveryId == "activation" && it.status == "ok" })
        val polls = api.polls
        advanceTimeBy(1000); runCurrent(); assertTrue(api.polls > polls)
        assertTrue(endpoint.starts.isEmpty())
        c.close()
    }

    @Test fun stableRemoteHeartbeatsDoNotCancelAnInFlightMusicalPreparation() = runTest {
        val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        val stops = endpoint.stops
        repeat(3) { advanceTimeBy(1000); runCurrent() }
        assertEquals(stops, endpoint.stops)
        assertNull(c.currentAuthority())
        assertTrue(endpoint.executed.isEmpty())
        api.current = view(owner = "pc", epoch = 2, snapshot = prepared)
        c.retry(); runCurrent()
        assertTrue(endpoint.stops > stops) // A real epoch/output change STILL fences the old intent.
        c.close()
    }

    @Test fun queuedResumeDoesNotSurviveRevokeAndPauseRoutesToFreshRemoteOwnerAfterPoll() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        var gate = CompletableDeferred<Unit>(); api.pollGate = gate
        c.retry(); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        api.current = view(epoch = 2, snapshot = prepared)
        gate.complete(Unit); runCurrent()
        assertTrue(endpoint.executed.isEmpty()); assertTrue(endpoint.starts.isEmpty())
        gate = CompletableDeferred(); api.pollGate = gate
        c.retry(); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("pause"))
        api.current = view(owner = "pc", epoch = 3, snapshot = prepared)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(3L to "pause"), api.commands)
        assertTrue(endpoint.executed.isEmpty())
        c.close()
    }

    @Test fun pauseSilencesImmediatelyDuringTransferOrQuarantine() = runTest {
        for (pendingView in listOf(
            view(transfer = PlaybackTransfer("switch", "phone", "pc", "preparing")),
            view(owner = "", quarantine = 2000),
        )) {
            val api = Protocol(pendingView); val endpoint = Endpoint()
            val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            c.setServiceActive(true); runCurrent()
            endpoint.snapshot = prepared
            val stops = endpoint.stops
            c.remoteCommand(PlaybackDeviceCommand.transport("pause"))
            assertTrue(endpoint.stops > stops); assertFalse(endpoint.snapshot.playing)
            runCurrent(); assertTrue(api.commands.isEmpty())
            c.close()
        }
    }

    @Test fun conflictReconciliationIsDisplayOnlyUntilFreshSuccessfulPoll() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        val held = c.currentAuthority()!!
        api.pollFailure = ApiException(409, "conflict")
        c.retry(); runCurrent()
        assertFalse(held.isValid()); assertNull(c.currentAuthority()); assertFalse(c.state.value.connected)
        assertTrue(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        assertTrue(c.state.value.message.contains("not replayed"))
        c.rename("phone", "Renamed after conflict"); runCurrent()
        assertFalse("A rename response is not a fresh owner poll", c.state.value.connected)
        assertTrue(c.state.value.retryAvailable); assertTrue(c.state.value.message.contains("not replayed"))
        c.localAction { fail("Conflict display must not grant authority"); PlaybackEndpointResult("ok", prepared) }
        runCurrent(); assertFalse(endpoint.snapshot.playing)
        api.pollFailure = null
        c.retry(); runCurrent()
        assertFalse(endpoint.snapshot.playing)
        assertFalse(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        assertTrue(endpoint.snapshot.playing)
        c.close()
    }

    @Test fun retainedAuthorityRenewsFromAtomicPermitAndRevokesSynchronously() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        val held = c.currentAuthority()!!
        advanceTimeBy(7000); runCurrent()
        assertTrue("Successful polling renews existing tokens beyond their first deadline", held.isValid())
        assertTrue(withContext(Dispatchers.Default) { held.isValid() })
        c.remoteCommand(PlaybackDeviceCommand.transport("pause"))
        assertFalse(held.isValid())
        assertFalse(withContext(Dispatchers.Default) { held.isValid() })
        c.close()
    }

    /** Policy regression only: Endpoint records the admitted snapshot, not audio execution.
     * Real broker + Media3 composition supplies the independent execution proof. */
    @Test fun acceptedOwnSourcePresentSelectionSurvivesOnlyItsEmptyInboxStaleEpochConflict() = runTest {
        ownSelectionEpochConflict("exact-empty-inbox", expectPlaying = true)
    }

    @Test fun staleEpochSelectionExceptionCannotRelaxUncertaintyPublicationBindingOrOutcomeFences() = runTest {
        for (variant in listOf("prior-io", "prepare-ack", "source-less", "command-mismatch", "target-mismatch",
            "source-mismatch", "epoch-jump", "outcome-ok", "outcome-cancelled", "outcome-timeout", "outcome-missing",
            "missing-body", "malformed-body", "missing-reason", "selection-aba")) {
            ownSelectionEpochConflict(variant, expectPlaying = false)
        }
    }

    // Production decodes this narrow fact separately; the retained body stays sanitized.
    private fun inboxEpochConflict(body: JsonObject?, reason: PlaybackConflictReason? = PlaybackConflictReason.STALE_EPOCH) =
        ApiException(409, "synthetic playback conflict", responseBody = body, playbackConflictReason = reason)

    private suspend fun TestScope.ownSelectionEpochConflict(variant: String, expectPlaying: Boolean) {
        val source = if (variant == "source-less") "" else "pc"
        val api = Protocol(view(owner = source, snapshot = prepared))
        val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        var acceptedId = ""
        var transferCount = 0
        fun pending(id: String, owner: String = source, epoch: Long = 1, phase: String = "preparing") =
            view(owner = owner, epoch = epoch, snapshot = prepared,
                transfer = PlaybackTransfer(id, source, "phone", phase))
                .copy(commands = listOf(PlaybackOutcome(id, "pending")))
        try {
            api.onTransfer = { id, target, _ ->
                transferCount++
                if (transferCount == 1) {
                    acceptedId = id
                    assertEquals("phone", target)
                    pending(id) // A transfer response never delivers the receiver inbox.
                } else {
                    // Genuine later public selections, never refreshed authority for the old one.
                    view(owner = source, snapshot = prepared, transfer = PlaybackTransfer(id, source, target, "preparing"))
                        .copy(commands = listOf(PlaybackOutcome(id, "pending")))
                }
            }
            c.setServiceActive(true); runCurrent()
            assertEquals(1, api.registers)
            c.select("phone"); runCurrent()
            assertTrue(acceptedId.isNotEmpty()); assertEquals(0, endpoint.preparing)
            api.onPoll = { epoch, sequence, snapshot, acks ->
                assertEquals(1L, epoch); assertNull(sequence); assertNull(snapshot); assertTrue(acks.isEmpty())
                pending(acceptedId).copy(directives = listOf(PlaybackDirective("own-prepare", 1, "prepare", acceptedId, state = prepared)))
            }
            c.retry(); runCurrent() // Only a fresh own-device poll delivers the real prepare shape.
            assertEquals(1, endpoint.preparing)
            assertTrue(endpoint.starts.isEmpty()); assertNull(c.currentAuthority())
            assertEquals(acceptedId, c.state.value.view?.transfer?.commandId)
            assertTrue(c.state.value.view?.commands?.any { it.commandId == acceptedId && it.status == "pending" } == true)
            if (variant != "prepare-ack") {
                api.onPoll = { epoch, sequence, snapshot, acks ->
                    assertEquals(1L, epoch); assertNull(sequence); assertNull(snapshot)
                    assertEquals(listOf(PlaybackAck("own-prepare", 1, "ok")), acks)
                    pending(acceptedId, phase = "releasing")
                }
                c.retry(); runCurrent() // Successful prepare ACK precedes the empty-inbox race.
            }
            if (variant == "prior-io") {
                api.onPoll = { _, _, _, acks -> assertTrue(acks.isEmpty()); throw IOException("synthetic accepted-selection uncertainty") }
                c.retry(); runCurrent()
                assertTrue(c.state.value.retryAvailable); assertNull(c.currentAuthority())
            }
            if (variant == "selection-aba") {
                c.select("tablet"); runCurrent(); c.select("phone"); runCurrent()
                assertEquals(listOf("phone", "tablet", "phone"), api.selections)
                assertNotEquals(acceptedId, api.selectionIds.last())
            }
            val nextEpoch = if (variant == "epoch-jump") 3L else 2L
            val activation = pending(acceptedId, owner = "phone", epoch = nextEpoch, phase = "activating")
                .copy(directives = listOf(PlaybackDirective("own-activate", nextEpoch, "activate", acceptedId, state = prepared)))
            val safe = activation.copy(directives = emptyList(), error = "",
                transfer = requireNotNull(activation.transfer).copy(
                    commandId = if (variant == "command-mismatch") "unrelated-command" else acceptedId,
                    sourceDeviceId = if (variant == "source-mismatch") "tablet" else source,
                    targetDeviceId = if (variant == "target-mismatch") "tablet" else "phone"),
                commands = when (variant) {
                    "outcome-ok" -> listOf(PlaybackOutcome(acceptedId, "ok"))
                    "outcome-cancelled" -> listOf(PlaybackOutcome(acceptedId, "cancelled"))
                    "outcome-timeout" -> listOf(PlaybackOutcome(acceptedId, "timeout"))
                    "outcome-missing" -> emptyList()
                    else -> listOf(PlaybackOutcome(acceptedId, "pending"))
                }).validated()
            val body = when (variant) {
                "missing-body" -> null
                "malformed-body" -> JsonObject(mapOf("epoch" to JsonPrimitive("invalid")))
                else -> safe.toJson()
            }
            // Missing/malformed bodies may fall back to a real read-only API view, but that
            // fallback must never manufacture the narrow accepted-write classification.
            api.current = activation.copy(directives = emptyList())
            api.onPoll = { epoch, sequence, snapshot, acks ->
                assertEquals("$variant old target inbox epoch", 1L, epoch)
                assertNull(sequence); assertNull(snapshot)
                if (variant == "prepare-ack") assertEquals(listOf(PlaybackAck("own-prepare", 1, "ok")), acks)
                else assertTrue("$variant must witness an actually empty inbox request", acks.isEmpty())
                throw inboxEpochConflict(body, if (variant == "missing-reason") null else PlaybackConflictReason.STALE_EPOCH)
            }
            val viewsBefore = api.views
            val pollsBefore = api.polls
            c.retry(); runCurrent()
            assertEquals("$variant conflict is not replayed or internally retried", pollsBefore + 1, api.polls)
            assertEquals("$variant reconciliation never activates", emptyList<PlaybackSnapshot>(), endpoint.starts)
            assertNull("$variant GET/safe conflict view grants no authority", c.currentAuthority())
            assertTrue("$variant reconciliation cannot execute transport", endpoint.executed.isEmpty())
            assertEquals("$variant must not register again", 1, api.registers)
            assertEquals(if (variant == "selection-aba") 3 else 1, api.selections.size)
            assertTrue(api.commands.isEmpty())
            assertEquals(if (variant in setOf("missing-body", "malformed-body")) viewsBefore + 1 else viewsBefore, api.views)
            // Real Go error/GET views contain no delivered inbox directives. Only this
            // subsequent fresh own-device poll delivers activate; musical payload is unchanged.
            api.onPoll = { epoch, sequence, snapshot, acks ->
                assertEquals(nextEpoch, epoch); assertNull(sequence); assertNull(snapshot); assertTrue(acks.isEmpty())
                activation
            }
            val pollsBeforeActivation = api.polls
            c.retry(); runCurrent()
            assertEquals("$variant fresh activation poll is not replayed", pollsBeforeActivation + 1, api.polls)
            if (expectPlaying) {
                assertEquals("Exact accepted empty-inbox conflict must preserve intentional playing admission",
                    listOf(prepared), endpoint.starts)
            } else {
                // A conservative implementation may refuse the activation entirely. If it
                // imports, the only permitted state is the exact unchanged payload paused.
                assertTrue("$variant must not admit playing after uncertainty", endpoint.starts.all { it == prepared.paused() })
                assertFalse("$variant must leave actual endpoint policy state paused", endpoint.snapshot.playing)
            }
            endpoint.starts.forEach { admitted ->
                assertEquals(prepared.queue, admitted.queue); assertEquals(prepared.occurrence, admitted.occurrence)
            }
            assertEquals(1, api.registers); assertTrue(api.commands.isEmpty())
            assertEquals(if (variant == "selection-aba") 3 else 1, api.selections.size)
        } finally { c.close(); runCurrent() }
    }

    @Test fun reconciledOwnHandoffCannotPlayAfterPublicInvalidationAtAnyActivationSuspension() = runTest {
        for (stage in listOf("before-poll", "held-poll", "validation", "activation", "no-directive")) {
            for (action in listOf("pause", "close", "same-target", "aba")) {
                withReconciledOwnHandoff { api, endpoint, c, id, safe ->
                    val responseGate = CompletableDeferred<Unit>()
                    val validationGate = CompletableDeferred<Unit>()
                    val activationGate = CompletableDeferred<Unit>()
                    val activation = handoffActivation(safe, id, "activation-$stage-$action")
                    var freshPollEntered = false
                    fun invalidate() {
                        when (action) {
                            "pause", "close" -> c.remoteCommand(PlaybackDeviceCommand.transport(action))
                            "same-target" -> c.select("phone")
                            else -> { c.select("tablet"); runCurrent(); c.select("phone") }
                        }
                        runCurrent()
                    }
                    api.onPoll = { _, _, _, _ -> safe }
                    try {
                        if (stage == "before-poll") invalidate()
                        if (stage == "validation") endpoint.prepareGate = validationGate
                        if (stage == "activation") endpoint.activateGate = activationGate
                        val prepares = endpoint.preparing
                        val activations = endpoint.activating
                        api.onPoll = { _, _, _, _ ->
                            freshPollEntered = true
                            if (stage == "held-poll") responseGate.await()
                            if (stage == "no-directive") safe else activation
                        }
                        c.retry(); runCurrent()
                        assertTrue("$stage/$action reaches the real fresh-poll script", freshPollEntered)
                        if (stage == "validation") assertEquals(prepares + 1, endpoint.preparing)
                        if (stage == "activation") assertEquals(activations + 1, endpoint.activating)
                        if (stage in setOf("held-poll", "validation", "activation")) {
                            assertTrue("$stage/$action setup must suspend before any playing endpoint effect", endpoint.starts.none { it.playing })
                        }
                        // Subsequent genuine Pause publication may legitimately initialize a
                        // PAUSED endpoint; do not reject that or force null state/ACK payloads.
                        api.onPoll = { _, _, _, _ -> safe }
                        if (stage != "before-poll") invalidate()
                        endpoint.prepareGate = null; endpoint.activateGate = null
                        responseGate.complete(Unit); validationGate.complete(Unit); activationGate.complete(Unit); runCurrent()
                        assertTrue("$stage/$action cannot finish obsolete playing activation", endpoint.starts.none { it.playing })
                        // New delivery ID prevents ordinary delivery deduplication from being
                        // the only reason an obsolete command cannot acquire playing admission.
                        api.onPoll = { _, _, _, _ -> handoffActivation(safe, id, "late-$stage-$action") }
                        c.retry(); runCurrent()
                        assertTrue("$stage/$action cannot resurrect the retired command", endpoint.starts.none { it.playing })
                        assertFalse(endpoint.snapshot.playing)
                        assertEquals(1, api.registers); assertTrue(api.commands.isEmpty())
                        val explicitChoices = when (action) { "same-target" -> 2; "aba" -> 3; else -> 1 }
                        assertTrue("No transfer replay beyond explicit selections", api.selections.size in 1..explicitChoices)
                        if (action in setOf("pause", "close")) assertEquals(listOf("phone"), api.selections)
                        endpoint.starts.forEach { admitted ->
                            assertEquals(prepared.queue, admitted.queue); assertEquals(prepared.occurrence, admitted.occurrence)
                        }
                    } finally {
                        endpoint.prepareGate = null; endpoint.activateGate = null
                        responseGate.complete(Unit); validationGate.complete(Unit); activationGate.complete(Unit); runCurrent()
                    }
                }
            }
        }
    }

    @Test fun awaitingHandoffCannotReuseClassificationAfterAnotherFailureOrMismatchedActivation() = runTest {
        for (fault in listOf("second-409", "fresh-io", "source", "command", "epoch")) {
            withReconciledOwnHandoff { api, endpoint, c, id, safe ->
                var next = safe
                if (fault in setOf("second-409", "fresh-io")) {
                    if (fault == "second-409") next = safe.copy(epoch = 3)
                    api.onPoll = { _, _, _, _ ->
                        if (fault == "fresh-io") throw IOException("synthetic fresh-poll uncertainty")
                        else throw inboxEpochConflict(next.toJson())
                    }
                    c.retry(); runCurrent()
                    assertTrue(endpoint.starts.isEmpty()); assertNull(c.currentAuthority())
                }
                val activation = when (fault) {
                    "source" -> handoffActivation(safe.copy(transfer = requireNotNull(safe.transfer).copy(sourceDeviceId = "tablet")), id, "wrong-source")
                    "command" -> handoffActivation(safe, "unrelated-command", "wrong-command")
                    "epoch" -> handoffActivation(safe.copy(epoch = 3), id, "wrong-epoch")
                    else -> handoffActivation(next, id, "after-$fault")
                }
                api.onPoll = { _, _, _, _ -> activation }
                c.retry(); runCurrent()
                assertTrue("$fault cannot borrow an already consumed classification", endpoint.starts.none { it.playing })
                assertFalse(endpoint.snapshot.playing)
                assertEquals(1, api.registers); assertEquals(listOf("phone"), api.selections); assertTrue(api.commands.isEmpty())
            }
        }
    }

    @Test fun moreThan256PublicInvalidationsCannotReviveTheRetiredHandoffCommand() = runTest {
        withReconciledOwnHandoff { api, endpoint, c, id, safe ->
            api.onPoll = { _, _, _, _ -> safe }
            repeat(257) { c.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent() }
            assertTrue(endpoint.starts.none { it.playing })
            api.onPoll = { _, _, _, _ -> handoffActivation(safe, id, "after-257-invalidations") }
            c.retry(); runCurrent()
            assertTrue("A retired command cannot become new permission after bounded repeated invalidation", endpoint.starts.none { it.playing })
            assertFalse(endpoint.snapshot.playing)
            assertEquals(1, api.registers); assertEquals(listOf("phone"), api.selections); assertTrue(api.commands.isEmpty())
        }
    }

    private fun handoffActivation(safe: PlaybackDevicesView, command: String, delivery: String) = safe.copy(
        directives = listOf(PlaybackDirective(delivery, safe.epoch, "activate", command, state = prepared)))

    /** The accepted write is proved by the actual transfer callback and validated response;
     * pendingSelection/SelectionFeedback presentation is never sampled as authorization. */
    private suspend fun TestScope.withReconciledOwnHandoff(
        block: suspend (Protocol, Endpoint, PlaybackCoordinator, String, PlaybackDevicesView) -> Unit,
    ) {
        val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        var id = ""
        try {
            api.onTransfer = { command, target, _ ->
                if (id.isEmpty()) {
                    id = command
                    view(owner = "pc", snapshot = prepared, transfer = PlaybackTransfer(command, "pc", target, "preparing"))
                        .copy(commands = listOf(PlaybackOutcome(command, "pending")))
                } else {
                    // A newer explicit serialized transfer may supersede the old proof, but
                    // never returns old directives or an invented execution ACK.
                    view(owner = "phone", epoch = 2, snapshot = prepared.paused(),
                        transfer = PlaybackTransfer(command, "", target, "preparing"))
                        .copy(commands = listOf(PlaybackOutcome(command, "pending")))
                }
            }
            c.setServiceActive(true); runCurrent(); c.select("phone"); runCurrent()
            assertEquals(1, api.registers); assertTrue(id.isNotEmpty())
            assertEquals(id, c.state.value.view?.transfer?.commandId)
            api.onPoll = { _, _, snapshot, acks ->
                assertNull(snapshot); assertTrue(acks.isEmpty())
                view(owner = "pc", snapshot = prepared, transfer = PlaybackTransfer(id, "pc", "phone", "preparing"),
                    directives = listOf(PlaybackDirective("prepare-owned", 1, "prepare", id, state = prepared)))
                    .copy(commands = listOf(PlaybackOutcome(id, "pending")))
            }
            c.retry(); runCurrent(); assertEquals(1, endpoint.preparing)
            api.onPoll = { _, _, snapshot, acks ->
                assertNull(snapshot); assertEquals(listOf(PlaybackAck("prepare-owned", 1, "ok")), acks)
                view(owner = "pc", snapshot = prepared, transfer = PlaybackTransfer(id, "pc", "phone", "releasing"))
                    .copy(commands = listOf(PlaybackOutcome(id, "pending")))
            }
            c.retry(); runCurrent()
            val safe = view(epoch = 2, snapshot = prepared, transfer = PlaybackTransfer(id, "pc", "phone", "activating"))
                .copy(commands = listOf(PlaybackOutcome(id, "pending"))).validated()
            api.onPoll = { epoch, sequence, snapshot, acks ->
                assertEquals(1L, epoch); assertNull(sequence); assertNull(snapshot); assertTrue(acks.isEmpty())
                throw inboxEpochConflict(safe.toJson())
            }
            c.retry(); runCurrent()
            assertTrue("Reconciliation cannot activate", endpoint.starts.isEmpty()); assertNull(c.currentAuthority())
            assertEquals(1, api.registers); assertEquals(listOf("phone"), api.selections); assertTrue(api.commands.isEmpty())
            block(api, endpoint, c, id, safe)
        } finally { c.close(); runCurrent() }
    }

    @Test fun lateStaleEpochPollAfterAuthLossOrCloseCannotReconcileOrActivate() = runTest {
        for (end in listOf("auth", "close")) {
            val api = Protocol(view(owner = "pc", snapshot = prepared)); val endpoint = Endpoint()
            val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            val gate = CompletableDeferred<Unit>()
            var acceptedId = ""
            try {
                api.onTransfer = { id, _, _ ->
                    acceptedId = id
                    view(owner = "pc", snapshot = prepared, transfer = PlaybackTransfer(id, "pc", "phone", "releasing"))
                        .copy(commands = listOf(PlaybackOutcome(id, "pending")))
                }
                c.setServiceActive(true); runCurrent(); c.select("phone"); runCurrent()
                val activating = view(epoch = 2, snapshot = prepared, transfer = PlaybackTransfer(acceptedId, "pc", "phone", "activating"))
                    .copy(commands = listOf(PlaybackOutcome(acceptedId, "pending")), error = "")
                api.onPoll = { _, _, snapshot, acks ->
                    assertNull(snapshot); assertTrue(acks.isEmpty())
                    withContext(NonCancellable) { gate.await(); throw inboxEpochConflict(activating.toJson()) }
                }
                c.retry(); runCurrent()
                val views = api.views
                if (end == "auth") c.authLost() else c.close()
                gate.complete(Unit); runCurrent()
                assertTrue(endpoint.starts.isEmpty()); assertNull(c.currentAuthority())
                assertFalse(endpoint.snapshot.playing); assertEquals(views, api.views)
                assertEquals(1, api.registers); assertEquals(listOf("phone"), api.selections); assertTrue(api.commands.isEmpty())
                assertEquals(if (end == "auth") PlaybackConnectionRecovery.AUTH_REQUIRED else PlaybackConnectionRecovery.OWNER_ENDED,
                    c.state.value.recovery)
            } finally { gate.complete(Unit); c.close(); runCurrent() }
        }
    }

    @Test fun authLossInvalidatesHeldAuthoritySynchronouslyAndStopsActualAudio() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        val old = endpoint.authority!!; assertTrue(old.isValid())
        c.authLost()
        assertFalse(old.isValid()); assertFalse(endpoint.snapshot.playing)
        assertFalse(c.state.value.exclusiveOwner)
        assertEquals(PlaybackConnectionRecovery.AUTH_REQUIRED, c.state.value.recovery)
        assertFalse(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        c.close()
    }
}
