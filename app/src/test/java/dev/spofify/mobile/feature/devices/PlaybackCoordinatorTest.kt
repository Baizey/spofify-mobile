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
        var starts = mutableListOf<PlaybackSnapshot>()
        var authority: PlaybackAuthority? = null
        var preparing = 0
        override fun suspendOutput() { stops++; snapshot = snapshot.paused() }
        override fun exportSnapshot() = snapshot
        override suspend fun prepare(snapshot: PlaybackSnapshot): Boolean { snapshot.validated(); preparing++; return true }
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            check(authority.isValid()); this.snapshot = snapshot; starts += snapshot; this.authority = authority
            return PlaybackEndpointResult("ok", snapshot)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
            check(authority.isValid()); this.authority = authority
            snapshot = when (command.type) { "resume", "retry" -> snapshot.copy(playing = true); "pause", "close" -> snapshot.paused(); else -> snapshot }
            return PlaybackEndpointResult("ok", snapshot)
        }
    }
    private class Protocol(var current: PlaybackDevicesView) : PlaybackDevicesProtocol {
        var registers = 0; var polls = 0; var leaves = 0
        var lastState: PlaybackSnapshot? = null
        val sentAcks = mutableListOf<PlaybackAck>()
        val selections = mutableListOf<String>()
        var transferGate: CompletableDeferred<Unit>? = null
        var pollGate: CompletableDeferred<Unit>? = null
        var lateRenameFailure: CompletableDeferred<Unit>? = null
        var failPoll = false
        override suspend fun view() = current.copy(directives = emptyList())
        override suspend fun register(name: String): PlaybackDevicesView { registers++; return current }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
            polls++; pollGate?.await(); if (failPoll) error("synthetic network failure")
            lastState = state; sentAcks += acks
            val result = current; current = current.copy(directives = emptyList()); return result
        }
        override suspend fun rename(deviceId: String, name: String): PlaybackDevicesView {
            lateRenameFailure?.let { gate -> withContext(NonCancellable) { gate.await(); error("synthetic late failure") } }
            return current
        }
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand) = current
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
            selections += targetDeviceId; transferGate?.await(); return current
        }
        override suspend fun leave(deviceId: String) { leaves++ }
    }

    @Test fun foregroundUiDoesNotRegisterAndUnsupportedEndpointNeverAdvertises() = runTest {
        val api = Protocol(view()); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        runCurrent(); assertEquals(0, api.registers)
        endpoint.supportsExactHandoff = false; c.setServiceActive(true); runCurrent()
        assertEquals(0, api.registers); assertFalse(c.state.value.exclusiveOwner)
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

    @Test fun authLossInvalidatesHeldAuthoritySynchronouslyAndStopsActualAudio() = runTest {
        val api = Protocol(view(snapshot = prepared)); val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        c.remoteCommand(PlaybackDeviceCommand.transport("resume")); runCurrent()
        val old = endpoint.authority!!; assertTrue(old.isValid())
        c.authLost()
        assertFalse(old.isValid()); assertFalse(endpoint.snapshot.playing)
        assertFalse(c.state.value.exclusiveOwner)
        c.close()
    }
}
