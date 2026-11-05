package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
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
        var activateGate: CompletableDeferred<Unit>? = null
        var executeGate: CompletableDeferred<Unit>? = null
        var cancelledActions = 0
        var nextStatus = "ok"
        val executed = mutableListOf<String>()
        override fun suspendOutput() { stops++; onSuspend?.invoke(); snapshot = snapshot.paused() }
        override fun exportSnapshot() = snapshot
        override suspend fun prepare(snapshot: PlaybackSnapshot): Boolean { snapshot.validated(); preparing++; return true }
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
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
                assertEquals(1, api.views)
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

    @Test fun heartbeatRetriesUnknownRegistrationWithBoundedBackoffAndManualRetryCannotStorm() = runTest {
        val api = Protocol(view()).apply { failRegister = true }; val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent(); assertEquals(1, api.registers)
        assertTrue(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        assertFalse(c.state.value.connecting)
        repeat(10) { c.retry(); runCurrent() }
        assertTrue(c.state.value.retryAvailable); assertFalse(c.state.value.verifyingConnection)
        advanceTimeBy(7999); runCurrent(); assertEquals(1, api.registers)
        advanceTimeBy(1); runCurrent(); assertEquals(2, api.registers)
        advanceTimeBy(15999); runCurrent(); assertEquals(2, api.registers)
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
