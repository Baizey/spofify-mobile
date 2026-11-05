package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Protocol-accurate admission progression: music cannot execute before activation ACK acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplicitPlayAdmissionTest {
    private val retained = PlaybackSnapshot(queue = listOf(PlaybackTrack("old", "Retained", emptyList(), "", "", 90.0,
        key = 1, order = 0)), position = 0, playing = true,
        occurrence = PlaybackOccurrence(1, "old", "retained-history", 30.0, false))
    private fun room(owner: String = "", epoch: Long = 3, lease: Long = if (owner.isEmpty()) 0 else 8000,
        quarantine: Long = 0) = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true),
        PlaybackDevice("other", "Other", false)), owner, epoch, lease, quarantine, retained, 0, 0,
        null, emptyList(), emptyList())
    private inner class Protocol(var current: PlaybackDevicesView = room()) : PlaybackDevicesProtocol {
        var claims = 0; var ordinary = 0; var polls = 0; var registers = 0
        var claimId: String? = null
        var fresh: PlaybackDevicesView? = null
        var readGate: CompletableDeferred<Unit>? = null
        var claimGate: CompletableDeferred<Unit>? = null
        var ackGate: CompletableDeferred<Unit>? = null
        var expectEmptyActivation = true
        var failClaim = false; var loseActivationAck = false; var acknowledgeActivation = true
        var allowGuard = true
        var activationAck = false
        val sent = mutableListOf<Pair<List<PlaybackAck>, PlaybackSnapshot?>>()
        val delivered = mutableSetOf<String>()
        override suspend fun view() = current
        override suspend fun register(name: String): PlaybackDevicesView { registers++; return current }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
            polls++; readGate?.await(); fresh?.let { current = it; fresh = null }
            sent += acks to state
            val id = claimId
            if (id != null && acks.any { it.deliveryId == "prepare-$id" && it.status == "ok" }) {
                current = current.copy(activeDeviceId = "phone", epoch = epoch + 1, leaseMs = 8000,
                    transfer = PlaybackTransfer(id, "", "phone", "activating"),
                    directives = listOf(PlaybackDirective("activate-$id", epoch + 1, "activate", id, state = retained)))
            } else if (id != null && acks.any { it.deliveryId == "activate-$id" }) {
                requireNotNull(state)
                if (expectEmptyActivation) {
                    assertTrue("Replacement activation must stage empty", state.queue.isEmpty()); assertNull(state.occurrence)
                }
                assertFalse(state.playing)
                ackGate?.await()
                activationAck = true
                if (loseActivationAck) error("synthetic uncertain ACK")
                if (acknowledgeActivation) current = current.copy(transfer = null, directives = emptyList(),
                    commands = listOf(PlaybackOutcome(id, "ok")), state = state)
            }
            return current.copy(directives = current.directives.filter { delivered.add(it.deliveryId) })
        }
        override suspend fun claimUnselected(deviceId: String, epoch: Long, commandId: String, selectionSeq: Long): PlaybackDevicesView {
            claims++; claimId = commandId; claimGate?.await()
            if (!allowGuard || current.activeDeviceId.isNotEmpty() || current.transfer != null || current.epoch != epoch)
                throw ApiException(409, "synthetic guarded conflict", responseBody = current.toJson())
            if (failClaim) error("synthetic lost claim response")
            current = current.copy(transfer = PlaybackTransfer(commandId, "", deviceId, "preparing"),
                directives = listOf(PlaybackDirective("prepare-$commandId", epoch, "prepare", commandId, state = retained)),
                commands = listOf(PlaybackOutcome(commandId, "pending")))
            return current.copy(directives = emptyList()) // Transfers observe; only poll/register deliver work.
        }
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
            ordinary++; return current
        }
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand) = current
        override suspend fun rename(deviceId: String, name: String) = current
        override suspend fun leave(deviceId: String) {}
    }
    private class Endpoint : NativePlaybackEndpoint {
        override val supportsExactHandoff = true
        var snapshot = PlaybackSnapshot()
        val stages = mutableListOf<PlaybackSnapshot>()
        var activateGate: CompletableDeferred<Unit>? = null
        var prepareCount = 0
        override fun suspendOutput() { snapshot = snapshot.paused() }
        override fun exportSnapshot() = snapshot
        override suspend fun prepare(snapshot: PlaybackSnapshot): Boolean { prepareCount++; return true }
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            assertTrue(authority.isValid()); stages += snapshot; activateGate?.await(); this.snapshot = snapshot
            return PlaybackEndpointResult("ok", snapshot)
        }
        override suspend fun activatePreparedResume(snapshot: PlaybackSnapshot, authority: PlaybackAuthority) = activate(snapshot, authority)
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority) = PlaybackEndpointResult("ok", snapshot)
    }

    @Test fun freshGuardPrepareActivateAndExactAckPrecedeOneRequestedStart() = runTest {
        val api = Protocol(); val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        var starts = 0
        coordinator.explicitLocalPlay { permit ->
            assertTrue(api.activationAck); assertTrue(permit.isValid()); starts++
            PlaybackEndpointResult("ok", PlaybackSnapshot())
        }
        runCurrent(); assertEquals(1, api.claims); assertEquals(0, starts)
        advanceTimeBy(1000); runCurrent(); assertEquals(0, starts); assertTrue(output.stages.isEmpty())
        advanceTimeBy(1000); runCurrent(); assertEquals(0, starts)
        assertEquals(PlaybackSnapshot(), output.stages.single())
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, starts); assertEquals(0, api.ordinary); assertEquals(1, api.registers)
        advanceTimeBy(4000); runCurrent(); assertEquals(1, starts)
        coordinator.close()
    }

    @Test fun pristineRegistrationDoesNotClaimOrStartRetainedMusic() = runTest {
        val api = Protocol(room("phone", epoch = 1)); val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true)
        var starts = 0
        coordinator.explicitLocalPlay { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        runCurrent()
        assertEquals(1, starts); assertEquals(0, api.claims)
        assertTrue(output.stages.all { !it.playing })
        coordinator.close()
    }

    @Test fun freshReadCannotRetargetCachedEmptyPlayToAnUnavailableWinner() = runTest {
        val api = Protocol(); val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        api.fresh = room("other", epoch = 3)
        var starts = 0
        coordinator.explicitLocalPlay { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(0, starts); assertEquals(0, api.claims); assertEquals(0, api.ordinary)
        assertEquals("other", coordinator.state.value.activeDeviceId)
        coordinator.close()
    }

    @Test fun quarantineLeaseAndPendingWorkNeverAcquireOnLaterPoll() = runTest {
        for (busy in listOf(room(quarantine = 1), room(lease = 1),
            room().copy(transfer = PlaybackTransfer("peer", "", "other", "preparing")),
            room().copy(directives = listOf(PlaybackDirective("pending", 3, "prepare", "peer", state = retained))))) {
            val api = Protocol(busy); val output = Endpoint()
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            var starts = 0
            coordinator.explicitLocalPlay { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
            runCurrent(); api.current = room(); advanceTimeBy(3000); runCurrent()
            assertEquals(0, starts); assertEquals(0, api.claims); assertEquals(0, api.ordinary)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun roundedZeroServerConflictNeverFallsBackOrRetries() = runTest {
        val api = Protocol().apply { allowGuard = false }; val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        var starts = 0
        coordinator.explicitLocalPlay { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        runCurrent(); advanceTimeBy(5000); runCurrent()
        assertEquals(1, api.claims); assertEquals(0, api.ordinary); assertEquals(0, starts)
        coordinator.close()
    }

    @Test fun uncertainClaimAndActivationAckRetireMusicPermanently() = runTest {
        for (claimFailure in listOf(true, false)) {
            val api = Protocol().apply { failClaim = claimFailure; loseActivationAck = !claimFailure }
            val coordinator = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            var starts = 0
            coordinator.explicitLocalPlay { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
            runCurrent(); advanceTimeBy(3000); runCurrent()
            api.failClaim = false; api.loseActivationAck = false
            coordinator.retry(); runCurrent(); advanceTimeBy(3000); runCurrent()
            assertEquals(0, starts); assertEquals(1, api.claims); assertEquals(0, api.ordinary)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun pauseRouteAbaNewPlayAndLifecycleCancelDuringClaim() = runTest {
        for (cancel in listOf("pause", "route", "newPlay", "service", "auth", "choice")) {
            val api = Protocol(); val coordinator = PlaybackCoordinator(api, Endpoint(), backgroundScope,
                clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            api.claimGate = CompletableDeferred()
            var route = 0; var starts = 0
            coordinator.explicitLocalPlay(isCurrent = { route == 0 }) { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
            runCurrent(); assertEquals(1, api.claims)
            when (cancel) {
                "pause" -> coordinator.remoteCommand(PlaybackDeviceCommand.transport("pause"))
                "route" -> route = 2 // Monotonic origin generation, even when route identity returns.
                "newPlay" -> coordinator.explicitLocalPlay(isCurrent = { false }) { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
                "service" -> coordinator.setServiceActive(false)
                "auth" -> coordinator.authLost()
                "choice" -> coordinator.select("phone")
            }
            api.claimGate!!.complete(Unit); runCurrent(); advanceTimeBy(4000); runCurrent()
            assertEquals(cancel, 0, starts)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun newerPlayReplacesOnlyMusicWithinTheSameBoundedClaim() = runTest {
        val api = Protocol(); val coordinator = PlaybackCoordinator(api, Endpoint(), backgroundScope,
            clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        api.claimGate = CompletableDeferred()
        val starts = mutableListOf<String>()
        var firstValid = true
        coordinator.explicitLocalPlay(isCurrent = { firstValid }, admissionIsCurrent = { true }) { starts += "first"; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        runCurrent(); assertEquals(1, api.claims)
        firstValid = false
        coordinator.explicitLocalPlay { starts += "latest"; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        api.claimGate!!.complete(Unit); runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(listOf("latest"), starts); assertEquals(1, api.claims); assertEquals(0, api.ordinary)
        advanceTimeBy(4000); runCurrent(); assertEquals(listOf("latest"), starts)
        coordinator.close()
    }

    @Test fun routeLifetimeCannotBeRevivedByLatestPayload() = runTest {
        val api = Protocol(); val coordinator = PlaybackCoordinator(api, Endpoint(), backgroundScope,
            clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent(); api.claimGate = CompletableDeferred()
        var lifetime = true; var starts = 0
        coordinator.explicitLocalPlay(admissionIsCurrent = { lifetime }) { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        runCurrent(); lifetime = false
        coordinator.explicitLocalPlay { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        api.claimGate!!.complete(Unit); runCurrent(); advanceTimeBy(4000); runCurrent()
        assertEquals(0, starts); assertEquals(1, api.claims)
        coordinator.close()
    }

    @Test fun replacementDuringResumeActivationRestagesEmptyBeforeAck() = runTest {
        val api = Protocol(); val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        var starts = 0
        coordinator.explicitLocalPlay(preparedResume = retained.paused()) { starts += 100; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        runCurrent(); output.activateGate = CompletableDeferred()
        advanceTimeBy(2000); runCurrent(); assertEquals(retained.paused(), output.stages.single())
        coordinator.explicitLocalPlay { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        output.activateGate!!.complete(Unit); runCurrent()
        assertEquals(PlaybackSnapshot(), output.stages.last())
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, starts); assertTrue(api.activationAck); assertEquals(1, api.claims)
        coordinator.close()
    }

    @Test fun latestPayloadIsRestagedWhenResumeActivationAckIsQueuedOrInFlight() = runTest {
        for (inFlight in listOf(false, true)) {
            val api = Protocol().apply { expectEmptyActivation = false }; val output = Endpoint()
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            var starts = 0
            coordinator.explicitLocalPlay(preparedResume = retained.paused()) { starts += 100; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
            runCurrent(); advanceTimeBy(2000); runCurrent()
            assertEquals(retained.paused(), output.stages.single())
            if (inFlight) {
                api.ackGate = CompletableDeferred(); advanceTimeBy(1000); runCurrent()
                assertEquals(0, starts)
            }
            coordinator.explicitLocalPlay {
                assertTrue(output.stages.last().queue.isEmpty())
                assertTrue(api.sent.last().second?.queue?.isEmpty() == true)
                starts++; PlaybackEndpointResult("ok", PlaybackSnapshot())
            }
            api.ackGate?.complete(Unit); runCurrent(); advanceTimeBy(2000); runCurrent()
            assertEquals(1, starts); assertEquals(1, api.claims); assertTrue(api.activationAck)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun newPlayInvalidatesAWaitingCancellationResistantStartOnly() = runTest {
        val api = Protocol(room("phone", epoch = 1)); val coordinator = PlaybackCoordinator(api, Endpoint(), backgroundScope,
            clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        val held = CompletableDeferred<Unit>(); val starts = mutableListOf<String>()
        coordinator.explicitLocalPlay { permit ->
            withContext(NonCancellable) { held.await() }
            if (permit.isValid()) starts += "obsolete"
            PlaybackEndpointResult("ok", PlaybackSnapshot())
        }
        runCurrent()
        coordinator.explicitLocalPlay { permit -> if (permit.isValid()) starts += "latest"; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        runCurrent(); held.complete(Unit); runCurrent()
        assertEquals(listOf("latest"), starts)
        coordinator.close()
    }

    @Test fun boundedDeadlineAndGenericPreparedCommandsNeverClaim() = runTest {
        val api = Protocol().apply { acknowledgeActivation = false }
        val coordinator = PlaybackCoordinator(api, Endpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        coordinator.localAction { PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        coordinator.preparedTransportCommand(PlaybackDeviceCommand.transport("resume")) { true }
        runCurrent(); assertEquals(0, api.claims)
        var starts = 0
        coordinator.explicitLocalPlay { starts++; PlaybackEndpointResult("ok", PlaybackSnapshot()) }
        runCurrent(); advanceTimeBy(13_000); runCurrent()
        assertEquals(0, starts); assertEquals(1, api.claims)
        api.acknowledgeActivation = true; coordinator.retry(); runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(0, starts)
        coordinator.close()
    }
}
