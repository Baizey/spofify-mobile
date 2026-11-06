package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedContinuationCoordinatorTest {
    private val phone = "a".repeat(32)
    private val track = PlaybackTrack("song", "Song", emptyList(), "", "", 100.0, key = 1, order = 0)
    private val music = PlaybackSnapshot(queue = listOf(track), position = 0, elapsed = 12.0, playing = true,
        occurrence = PlaybackOccurrence(1, "song", "exact-event", 0.5, false))
    private fun view(lead: PlaybackLeadership? = PlaybackLeadership(1, 1, "installation", phone),
        directives: List<PlaybackDirective> = emptyList()) = PlaybackDevicesView(phone,
        listOf(PlaybackDevice(phone, "Phone", true)), phone, 1, 8000, state = music,
        stateSeq = 0, stateAgeMs = 0, transfer = null, directives = directives, commands = emptyList(), leadership = lead)
    private class Output : NativePlaybackEndpoint {
        override val supportsExactHandoff = true
        var snapshot = PlaybackSnapshot()
        var starts = 0; var stops = 0
        var continuation: CachedPlaybackPermission? = null
        var live: PlaybackAuthority? = null
        override fun retainCachedOutput(permission: CachedPlaybackPermission): Boolean {
            if (!snapshot.playing) return false
            continuation = permission
            return true
        }
        override fun suspendOutput() { stops++; snapshot = snapshot.paused() }
        override fun exportSnapshot() = snapshot
        override suspend fun prepare(snapshot: PlaybackSnapshot) = true
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            this.snapshot = snapshot; live = authority; starts++
            return PlaybackEndpointResult("ok", snapshot)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority) =
            PlaybackEndpointResult("rejected", snapshot)
    }
    private class Wire(var current: PlaybackDevicesView) : PlaybackDevicesProtocol {
        var failure: Exception? = null
        var polls = 0; var reads = 0; var claims = 0
        override suspend fun view(): PlaybackDevicesView { reads++; failure?.let { throw it }; return current }
        override suspend fun register(name: String) = current
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
            polls++; failure?.let { throw it }
            return current.copy(directives = emptyList())
        }
        override suspend fun rename(deviceId: String, name: String) = current
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView = error("No command replay")
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
            claims++; error("Reconnect cannot claim")
        }
        override suspend fun leave(deviceId: String) = Unit
    }
    private fun activatedView() = view(directives = listOf(PlaybackDirective("activate", 1, "activate", state = music)))

    @Test fun outageAndSameLeadReconnectionKeepExactOutputWithoutPublicationOrRestart() = runTest {
        val wire = Wire(activatedView()); val output = Output()
        val owner = PlaybackCoordinator(wire, output, backgroundScope, clockMs = { testScheduler.currentTime })
        owner.setServiceActive(true); runCurrent()
        assertEquals(1, output.starts)
        wire.failure = IOException("offline")
        advanceTimeBy(1001); runCurrent()
        val permission = requireNotNull(output.continuation)
        assertTrue(permission.isValid()); assertFalse(requireNotNull(output.live).isValid())
        assertNull(owner.currentAuthority()); assertFalse(owner.state.value.connected)
        val polls = wire.polls
        advanceTimeBy(60_000); runCurrent()
        assertTrue(permission.isValid()); assertEquals(music, output.snapshot)
        wire.failure = null; wire.current = view()
        advanceTimeBy(1001); runCurrent()
        assertTrue(permission.isValid()); assertEquals(1, output.starts); assertEquals(0, output.stops)
        assertEquals(polls, wire.polls); assertEquals(0, wire.claims); assertTrue(wire.reads > 1)
        owner.close(); assertFalse(permission.isValid())
    }

    @Test fun changedClearedLegacyAndMissingLeadYieldBeforeStaleActivate() = runTest {
        for (lead in listOf(PlaybackLeadership(1, 2, "installation", "b".repeat(32)),
            PlaybackLeadership(1, 2, "cleared", ""), PlaybackLeadership(1, 2, "legacy", ""), null)) {
            val wire = Wire(activatedView()); val output = Output()
            val owner = PlaybackCoordinator(wire, output, backgroundScope, clockMs = { testScheduler.currentTime })
            owner.setServiceActive(true); runCurrent()
            wire.failure = IOException("offline"); advanceTimeBy(1001); runCurrent()
            val permission = requireNotNull(output.continuation)
            wire.failure = null; wire.current = view(lead, activatedView().directives)
            advanceTimeBy(1001); runCurrent()
            assertFalse(permission.isValid()); assertFalse(output.snapshot.playing)
            assertEquals(1, output.starts); assertTrue(output.stops > 0); assertEquals(0, wire.claims)
            owner.close()
        }
    }

    @Test fun stoppedIntentAndKnownAuthLossNeverResumeOnReconnection() = runTest {
        val wire = Wire(activatedView()); val output = Output()
        val owner = PlaybackCoordinator(wire, output, backgroundScope, clockMs = { testScheduler.currentTime })
        owner.setServiceActive(true); runCurrent()
        wire.failure = IOException("offline"); advanceTimeBy(1001); runCurrent()
        output.snapshot = output.snapshot.paused()
        wire.failure = null; wire.current = view()
        advanceTimeBy(1001); runCurrent()
        assertFalse(output.snapshot.playing); assertEquals(1, output.starts)
        wire.failure = ApiException(401, "expired")
        advanceTimeBy(1001); runCurrent()
        assertFalse(requireNotNull(output.continuation).isValid())
        assertEquals(PlaybackConnectionRecovery.AUTH_REQUIRED, owner.state.value.recovery)
        assertFalse(output.snapshot.playing)
        owner.close()
    }
}
