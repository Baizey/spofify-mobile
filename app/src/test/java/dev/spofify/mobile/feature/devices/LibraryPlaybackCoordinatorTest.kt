package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryPlaybackCoordinatorTest {
    private class Endpoint : NativePlaybackEndpoint {
        var stops = 0
        var starts = 0
        override var onObservation: ((String, Boolean) -> Unit)? = null
        override suspend fun prepare(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean) = "ok"
        override fun release(): Boolean { stops++; return true }
        override suspend fun execute(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority): String {
            if (instruction.desiredPlaying && authority.isValid()) starts++
            return "ok"
        }
        override fun observe() = LibraryPlaybackObservation(0.0, 0.0, false, false, false, "")
        override fun suspendOutput() { stops++ }
    }
    private class Socket : LibraryPlaybackTransport {
        override val identity = ValidatedSessionIdentity("http://localhost", "account", 1)
        override var isCurrent = true
        val incoming = Channel<LibraryPlaybackEvent>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        val sent = mutableListOf<LibraryPlaybackClientMessage>()
        private var command = 0L
        private var sequence = 0L
        override fun send(message: LibraryPlaybackClientMessage): LibraryPlaybackSend? {
            if (!isCurrent) return null
            sent += message
            return if (message is LibraryPlaybackClientMessage.Command)
                LibraryPlaybackSend(0, commandId = (++command).toString())
            else LibraryPlaybackSend(0, clientSequence = ++sequence)
        }
        override fun close() { isCurrent = false; incoming.close() }
        fun message(value: LibraryPlaybackServerMessage) { incoming.trySend(LibraryPlaybackEvent.Message(value)).getOrThrow() }
    }
    private class Store : SessionStore {
        override fun read(): StoredSession? = null
        override fun write(value: StoredSession?) = Unit
    }
    private fun snapshot(version: Long = 1, sequence: Long = 0, elapsed: Double = 0.0): LibraryPlaybackSnapshot {
        val state = LibraryPlaybackState(1, emptyList(), -1, null, false, "off", false,
            emptyList(), elapsed, 0.0, false, false, false, "", false, "", null)
        val playback = LibraryPlaybackStatus(1, version, sequence, "", false, elapsed, 0.0, false, false, false, "")
        return LibraryPlaybackSnapshot("room", version, 1, state, playback,
            listOf(LibraryPlaybackDevice("phone", "Phone", true, true)), "phone", "connection", null, 0)
    }
    private fun Socket.admit(snapshot: LibraryPlaybackSnapshot = snapshot()) = message(
        LibraryPlaybackServerMessage("snapshot", "room", connectionId = "connection", deviceId = "phone", snapshot = snapshot))

    @Test fun admissionAndReconnectNeverUploadQueueOrReplayCommand() = runTest {
        val first = Socket(); val second = Socket(); val sockets = ArrayDeque(listOf(first, second))
        val endpoint = Endpoint()
        val client = SpofifyClient(ServerAddress.parse("http://localhost"), Store())
        val owner = PlaybackCoordinator(client, endpoint, backgroundScope,
            openTransport = { _, _, _, _ -> sockets.removeFirst() }, authenticationIdentity = { "account-session" },
            deviceIdentity = { "phone" to "Phone" }, clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { testScheduler.currentTime })
        owner.retry(); runCurrent(); first.admit(); runCurrent()
        assertTrue(owner.state.value.connected)
        assertTrue(first.sent.isEmpty()); assertEquals(0, endpoint.starts)
        var outcomes = 0
        assertTrue(owner.submit(LibraryPlaybackCommand.Resume) { assertNull(it); outcomes++ })
        first.incoming.trySend(LibraryPlaybackEvent.Closed(LibraryPlaybackEvent.Reason.NETWORK))
        runCurrent()
        assertFalse(owner.state.value.connected); assertEquals(1, outcomes)
        owner.retry(); runCurrent(); second.admit(); runCurrent()
        assertTrue(second.sent.isEmpty()); assertEquals(0, endpoint.starts)
        owner.close()
    }

    @Test fun accountChangeDuringTicketWaitCannotPublishOrBorrowSuccessor() = runTest {
        val transport = CompletableDeferred<LibraryPlaybackTransport>()
        val socket = Socket(); val endpoint = Endpoint(); var account: Any? = "first-incarnation"
        val owner = PlaybackCoordinator(SpofifyClient(ServerAddress.parse("http://localhost"), Store()), endpoint, backgroundScope,
            openTransport = { _, _, _, _ -> transport.await() }, authenticationIdentity = { account },
            deviceIdentity = { "phone" to "Phone" }, clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { 0 })
        owner.retry(); runCurrent(); account = "second-incarnation"; transport.complete(socket); runCurrent()
        assertFalse(owner.state.value.connected); assertFalse(socket.isCurrent)
        assertFalse(owner.submit(LibraryPlaybackCommand.Resume))
        owner.close()
    }

    @Test fun foreignSessionFrameStopsAndFencesPendingAction() = runTest {
        val socket = Socket(); val endpoint = Endpoint()
        val owner = PlaybackCoordinator(SpofifyClient(ServerAddress.parse("http://localhost"), Store()), endpoint, backgroundScope,
            openTransport = { _, _, _, _ -> socket }, authenticationIdentity = { "session" },
            deviceIdentity = { "phone" to "Phone" }, clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { 0 })
        owner.retry(); runCurrent(); socket.admit(); runCurrent()
        val witness = requireNotNull(owner.captureBinding())
        socket.message(LibraryPlaybackServerMessage("state", "other-room", snapshot = snapshot()))
        runCurrent()
        assertFalse(owner.state.value.connected); assertFalse(owner.isCurrent(witness)); assertTrue(endpoint.stops > 0)
        owner.close()
    }

    @Test fun scalarOrderingBuffersFutureStateWithoutChangingAuthorityOrActionBinding() = runTest {
        val socket = Socket(); val endpoint = Endpoint()
        val owner = PlaybackCoordinator(SpofifyClient(ServerAddress.parse("http://localhost"), Store()), endpoint, backgroundScope,
            openTransport = { _, _, _, _ -> socket }, authenticationIdentity = { "session" },
            deviceIdentity = { "phone" to "Phone" }, clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { 0 })
        owner.retry(); runCurrent(); socket.admit(); runCurrent()
        val binding = requireNotNull(owner.captureBinding())
        socket.message(LibraryPlaybackServerMessage("playback", "room", playback = snapshot(sequence = 1, elapsed = 3.0).playback))
        runCurrent()
        assertTrue(owner.isCurrent(binding)); assertEquals(3.0, owner.state.value.snapshot!!.playback.elapsed, 0.0)
        socket.message(LibraryPlaybackServerMessage("playback", "room", playback = snapshot(version = 2, sequence = 3, elapsed = 8.0).playback))
        runCurrent()
        assertEquals(1L, owner.state.value.snapshot!!.stateVersion)
        socket.message(LibraryPlaybackServerMessage("state", "room", snapshot = snapshot(version = 2, sequence = 2, elapsed = 7.0)))
        runCurrent()
        assertEquals(8.0, owner.state.value.snapshot!!.playback.elapsed, 0.0)
        assertFalse(owner.isCurrent(binding)); assertEquals(0, endpoint.starts)
        owner.close()
    }

    @Test fun sameTargetSelectionRetiresHeldBindingBeforeServerResponse() = runTest {
        val socket = Socket()
        val owner = PlaybackCoordinator(SpofifyClient(ServerAddress.parse("http://localhost"), Store()), Endpoint(), backgroundScope,
            openTransport = { _, _, _, _ -> socket }, authenticationIdentity = { "session" },
            deviceIdentity = { "phone" to "Phone" }, clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { testScheduler.currentTime })
        owner.retry(); runCurrent(); socket.admit(); runCurrent()
        val before = requireNotNull(owner.captureBinding())
        owner.select("phone")
        val after = requireNotNull(owner.captureBinding())
        assertFalse(owner.isCurrent(before)); assertNotEquals(before, after)
        owner.select("phone")
        assertFalse(owner.isCurrent(after))
        assertEquals(2, socket.sent.filterIsInstance<LibraryPlaybackClientMessage.Command>().size)
        owner.close()
    }

    @Test fun sameVersionIdentityMutationFailsClosedAndCannotRefreshHeldBinding() = runTest {
        val socket = Socket()
        val owner = PlaybackCoordinator(SpofifyClient(ServerAddress.parse("http://localhost"), Store()), Endpoint(), backgroundScope,
            openTransport = { _, _, _, _ -> socket }, authenticationIdentity = { "session" },
            deviceIdentity = { "phone" to "Phone" }, clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { 0 })
        owner.retry(); runCurrent(); socket.admit(); runCurrent()
        val binding = requireNotNull(owner.captureBinding())
        socket.message(LibraryPlaybackServerMessage("state", "room", snapshot = snapshot().copy(selectedConnectionId = "replacement")))
        runCurrent()
        assertFalse(owner.state.value.connected); assertFalse(owner.isCurrent(binding))
        owner.close()
    }

    @Test fun unchangedStructuralPublicationCannotRollBackNewerScalar() = runTest {
        val socket = Socket()
        val owner = PlaybackCoordinator(SpofifyClient(ServerAddress.parse("http://localhost"), Store()), Endpoint(), backgroundScope,
            openTransport = { _, _, _, _ -> socket }, authenticationIdentity = { "session" },
            deviceIdentity = { "phone" to "Phone" }, clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { 0 })
        owner.retry(); runCurrent(); socket.admit(); runCurrent()
        socket.message(LibraryPlaybackServerMessage("playback", "room", playback = snapshot(sequence = 3, elapsed = 9.0).playback))
        runCurrent()
        socket.message(LibraryPlaybackServerMessage("state", "room", snapshot = snapshot(sequence = 2, elapsed = 8.0)))
        runCurrent()
        assertTrue(owner.state.value.connected)
        assertEquals(9.0, owner.state.value.snapshot!!.playback.elapsed, 0.0)
        owner.close()
    }

    @Test fun invalidFirstFrameFailsClosedInsteadOfTreatingStateAsAdmission() = runTest {
        val socket = Socket()
        val owner = PlaybackCoordinator(SpofifyClient(ServerAddress.parse("http://localhost"), Store()), Endpoint(), backgroundScope,
            openTransport = { _, _, _, _ -> socket }, authenticationIdentity = { "session" },
            deviceIdentity = { "phone" to "Phone" }, clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { 0 })
        owner.retry(); runCurrent(); socket.message(LibraryPlaybackServerMessage("state", "room", snapshot = snapshot())); runCurrent()
        assertFalse(owner.state.value.connected); assertFalse(socket.isCurrent)
        owner.close()
    }
}
