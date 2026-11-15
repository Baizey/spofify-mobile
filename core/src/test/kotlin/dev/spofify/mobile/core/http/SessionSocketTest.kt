package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.FeatureAuthority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSocketTest {
    @Test fun thirtyThreeSmallFramesSurviveWithoutAConsumerAndDrainInOrder() = runTest {
        val transport = FakeSocket()
        val socket = opened(transport)
        try {
            val expected = (1..33).map(::frame)
            expected.forEach(socket::receive)
            assertTrue(socket.isOpen)
            val received = mutableListOf<String>()
            val consumer = backgroundScope.launch { socket.messages.collect { received.add(it) } }
            runCurrent()
            assertEquals(expected, received)
            socket.receive(frame(34))
            runCurrent()
            assertEquals(expected + frame(34), received)
            assertTrue(socket.isOpen)
            assertFalse(transport.cancelled)
            consumer.cancel()
        } finally { socket.close() }
    }

    @Test fun frameCapacityIsBoundedAtTwoHundredFiftySix() {
        val transport = FakeSocket()
        val socket = opened(transport)
        try {
            repeat(256) { socket.receive(frame(it)) }
            assertTrue(socket.isOpen)
            socket.receive(frame(256))
            assertFalse(socket.isOpen)
            assertTrue(transport.cancelled)
        } finally { socket.close() }
    }

    @Test fun cumulativeBytesStillCloseBeforeTheFrameAllowanceIsExhausted() {
        val transport = FakeSocket()
        val socket = opened(transport)
        val oneMiB = "a".repeat(1024 * 1024)
        try {
            repeat(5) { socket.receive(oneMiB) }
            assertTrue(socket.isOpen)
            socket.receive("x")
            assertFalse(socket.isOpen)
            assertTrue(transport.cancelled)
        } finally { socket.close() }
    }

    @Test fun aSingleOversizedUtf8FrameClosesSafely() {
        val transport = FakeSocket()
        val socket = opened(transport)
        try {
            socket.receive("😀".repeat(5 * 1024 * 1024 / 4 + 1))
            assertFalse(socket.isOpen)
            assertTrue(transport.cancelled)
        } finally { socket.close() }
    }

    private fun opened(transport: FakeSocket) = SessionSocket(FeatureAuthority(Any()) { true }) {}.also {
        it.attach(transport)
        it.opened()
    }

    private fun frame(sequence: Int) = """{"type":"playback","playback":{"outputVersion":1,"stateVersion":1,"playbackSequence":$sequence,"eventId":"event","hasReportedPlayback":true,"elapsed":0.0,"duration":120.0,"isPlaying":false,"isBuffering":false,"isLoading":false,"error":""}}"""

    private class FakeSocket : WebSocket {
        var cancelled = false
        override fun request() = Request.Builder().url("http://localhost/").build()
        override fun queueSize() = 0L
        override fun send(text: String) = !cancelled
        override fun send(bytes: ByteString) = !cancelled
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
}
