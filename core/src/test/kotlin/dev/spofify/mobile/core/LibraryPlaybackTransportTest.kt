package dev.spofify.mobile.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryPlaybackTransportTest {
    private class Socket : WebSocket {
        val sent = mutableListOf<String>()
        var accept = true
        var canceled = false
        var pendingBytes = 0L
        override fun request() = Request.Builder().url("http://localhost/api/me/player/socket").build()
        override fun queueSize() = pendingBytes
        override fun send(text: String): Boolean { if (!accept) return false; sent.add(text); return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { canceled = true }
    }
    private fun response(socket: Socket, protocol: String = LibraryPlaybackProtocol.SUBPROTOCOL) = Response.Builder()
        .request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols")
        .header("Sec-WebSocket-Protocol", protocol).build()

    @Test fun firstSnapshotIsRetainedUntilCollectorAndSendsCaptureOneConnectionCountersAndMonotonicTime() = runTest {
        var now = 41_000_000L
        val socket = Socket()
        lateinit var listener: WebSocketListener
        val transport = OkHttpLibraryPlaybackTransport(ValidatedSessionIdentity("http://localhost", "account", 1), this, "phone",
            { true }, { action -> action() }, {}, {}, { now })
        transport.start("hello-once") { listener = it; socket }
        listener.onOpen(socket, response(socket))
        assertNull(transport.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Pause)))
        listener.onMessage(socket, librarySnapshotFrame())
        // Deliberately attach AFTER the initial frame.
        val first = transport.events.first() as LibraryPlaybackEvent.Message
        assertEquals("connection", first.message.connectionId)
        val command = transport.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Pause))!!
        assertEquals("1", command.commandId)
        assertNull(command.clientSequence)
        val ack = transport.send(LibraryPlaybackClientMessage.Ack("prepare", 1, "", "ok"))!!
        assertEquals(1L, ack.clientSequence)
        assertEquals(now, ack.sentAtNanos)
        now += 2_000_000_000
        val report = transport.send(LibraryPlaybackClientMessage.Report(2, "event", "execute", 1.0, 24.0, true, false, false, "", false))!!
        assertEquals(2L, report.clientSequence)
        assertEquals(now, report.sentAtNanos)
        assertEquals("2", transport.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Resume))!!.commandId)
        assertEquals(1, socket.sent.count { it == "hello-once" })
        transport.close()
        assertTrue(socket.canceled)
        assertNull(transport.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Next)))
    }
    @Test fun invalidLocalRequestDoesNotConsumeCounterButFailedEnqueueClosesWithoutReplay() = runTest {
        val socket = Socket()
        lateinit var listener: WebSocketListener
        val transport = OkHttpLibraryPlaybackTransport(ValidatedSessionIdentity("http://localhost", "account", 1), this, "phone",
            { true }, { action -> action() }, {}, {})
        transport.start("hello") { listener = it; socket }
        listener.onOpen(socket, response(socket)); listener.onMessage(socket, librarySnapshotFrame())
        transport.events.first()
        assertNull(transport.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Seek(Double.NaN))))
        assertEquals("1", transport.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Pause))!!.commandId)
        socket.accept = false
        assertNull(transport.send(LibraryPlaybackClientMessage.Ack("execution", 1, "", "ok")))
        assertTrue(transport.events.first() is LibraryPlaybackEvent.Closed)
        assertTrue(socket.canceled)
        assertEquals(2, socket.sent.size)
        transport.close()
    }
    @Test fun wrongFirstFrameRepeatedSnapshotForeignSessionOrBinaryClosesAndDropsQueuedGrants() = runTest {
        for (fault in 0..4) {
            val socket = Socket()
            lateinit var listener: WebSocketListener
            val transport = OkHttpLibraryPlaybackTransport(ValidatedSessionIdentity("http://localhost", "account", 1), this, "phone",
                { true }, { action -> action() }, {}, {})
            transport.start("hello") { listener = it; socket }
            listener.onOpen(socket, response(socket))
            if (fault > 0) listener.onMessage(socket, librarySnapshotFrame())
            when (fault) {
                0 -> listener.onMessage(socket, libraryInstructionFrame())
                1 -> listener.onMessage(socket, librarySnapshotFrame())
                2 -> listener.onMessage(socket, libraryInstructionFrame().replace("\"sessionId\":\"session\"", "\"sessionId\":\"other\""))
                3 -> listener.onMessage(socket, ByteString.EMPTY)
                4 -> listener.onMessage(socket, libraryInstructionFrame().replace("\"outputVersion\":1", "\"epoch\":1"))
            }
            val closed = transport.events.first() as LibraryPlaybackEvent.Closed
            assertEquals(LibraryPlaybackEvent.Reason.PROTOCOL, closed.reason)
            assertTrue(socket.canceled)
            transport.close()
        }
    }
    @Test fun exactSubprotocolIsRequiredAndSlowConsumersCloseInsteadOfDroppingFrames() = runTest {
        for (wrongProtocol in listOf(true, false)) {
            val socket = Socket()
            lateinit var listener: WebSocketListener
            val transport = OkHttpLibraryPlaybackTransport(ValidatedSessionIdentity("http://localhost", "account", 1), this, "phone",
                { true }, { action -> action() }, {}, {})
            transport.start("hello") { listener = it; socket }
            listener.onOpen(socket, response(socket, if (wrongProtocol) "spofify.library-playback.v1" else LibraryPlaybackProtocol.SUBPROTOCOL))
            if (!wrongProtocol) {
                listener.onMessage(socket, librarySnapshotFrame())
                repeat(64) { listener.onMessage(socket, libraryInstructionFrame()) }
            }
            val closed = transport.events.first() as LibraryPlaybackEvent.Closed
            assertEquals(if (wrongProtocol) LibraryPlaybackEvent.Reason.PROTOCOL else LibraryPlaybackEvent.Reason.CAPACITY, closed.reason)
            assertTrue(socket.canceled)
            transport.close()
        }
    }
    @Test fun slowWriterUsesIndependentFiveMiBUtf8BudgetAndClosesInsteadOfDroppingOrReplaying() = runTest {
        val socket = Socket()
        lateinit var listener: WebSocketListener
        val transport = OkHttpLibraryPlaybackTransport(ValidatedSessionIdentity("http://localhost", "account", 1), this, "phone",
            { true }, { action -> action() }, {}, {})
        transport.start("hello") { listener = it; socket }
        listener.onOpen(socket, response(socket)); listener.onMessage(socket, librarySnapshotFrame())
        transport.events.first()
        val message = LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Rename("🎵".repeat(64)))
        val raw = LibraryPlaybackCodec.encode(message, "1")
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > raw.length)
        socket.pendingBytes = 5L * 1024 * 1024 - bytes
        assertEquals("1", transport.send(message)!!.commandId) // Exact byte boundary is admitted.
        val ack = LibraryPlaybackClientMessage.Ack("execution", 1, "", "ok")
        val ackBytes = LibraryPlaybackCodec.encode(ack, clientSequence = 1).toByteArray(Charsets.UTF_8).size
        socket.pendingBytes = 5L * 1024 * 1024 - ackBytes + 1 // One byte over, still below OkHttp's.
        assertNull(transport.send(ack))
        assertEquals(LibraryPlaybackEvent.Reason.CAPACITY,
            (transport.events.first() as LibraryPlaybackEvent.Closed).reason)
        assertEquals(2, socket.sent.size) // Only hello and the first command; no dropped ACK/report.
        assertTrue(socket.canceled)
        assertNull(transport.send(LibraryPlaybackClientMessage.Ack("execution", 1, "", "ok")))
        transport.close()
    }
    @Test fun authenticationRenewalIsIndependentOfMusicAndFailureRetiresSocket() = runTest {
        val socket = Socket()
        lateinit var listener: WebSocketListener
        var renewals = 0
        val transport = OkHttpLibraryPlaybackTransport(ValidatedSessionIdentity("http://localhost", "account", 1), this, "phone",
            { true }, { action -> action() }, { renewals++; if (renewals == 2) throw java.io.IOException("private details") }, {})
        transport.start("hello") { listener = it; socket }
        listener.onOpen(socket, response(socket)); listener.onMessage(socket, librarySnapshotFrame())
        transport.events.first()
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, renewals)
        assertEquals(listOf("hello"), socket.sent)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(LibraryPlaybackEvent.Reason.NETWORK, (transport.events.first() as LibraryPlaybackEvent.Closed).reason)
        assertTrue(socket.canceled)
        transport.close()
    }
    @Test fun authLossAtDispatchFencesSendAndQueuedAuthorityCannotSurviveAccountOrOfflineAba() = runTest {
        var live = true
        var dispatch = true
        val socket = Socket()
        lateinit var listener: WebSocketListener
        val transport = OkHttpLibraryPlaybackTransport(ValidatedSessionIdentity("http://localhost", "account", 1), this, "phone",
            { live }, { action -> dispatch && action() }, {}, {})
        transport.start("hello") { listener = it; socket }
        listener.onOpen(socket, response(socket)); listener.onMessage(socket, librarySnapshotFrame())
        transport.events.first()
        dispatch = false
        assertNull(transport.send(LibraryPlaybackClientMessage.Ack("execution", 1, "", "ok")))
        live = false; runCurrent(); advanceTimeBy(250); runCurrent()
        live = true
        assertFalse(transport.isCurrent)
        assertNull(transport.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Resume)))
        assertEquals(listOf("hello"), socket.sent)
        assertTrue(transport.events.first() is LibraryPlaybackEvent.Closed)
        transport.close()
    }
}
