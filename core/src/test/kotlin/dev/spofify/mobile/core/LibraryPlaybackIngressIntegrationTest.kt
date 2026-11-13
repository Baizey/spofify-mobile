package dev.spofify.mobile.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import okio.Timeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises the production admitted client -> public Response.socket -> real OkHttp reader
 * path. The last application interceptor supplies a synthetic upgraded socket of EXACT raw
 * bytes; no internal API, network fault proxy, replacement-decoded callback or frame writer.
 * Ordinary login/ticket HTTP still goes through the client's real exact-origin policy. */
class LibraryPlaybackIngressIntegrationTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private lateinit var scope: CoroutineScope
    private var transport: LibraryPlaybackTransport? = null
    private var peer: SyntheticSocket? = null
    private val upgrade = AtomicReference<Request>()

    @Before fun setup() {
        web = MockWebServer(); web.start()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun teardown() {
        transport?.close(); peer?.cancel(); scope.cancel()
        web.shutdown()
        client.httpClient.connectionPool.evictAll()
        client.httpClient.dispatcher.executorService.shutdown()
        assertTrue(client.httpClient.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
    }

    private suspend fun open(wire: Source, extension: String? = null, socketPresent: Boolean = true): LibraryPlaybackTransport {
        web.enqueue(loginResponse())
        client.login("listener", "synthetic-password")
        web.enqueue(jsonResponse("""{"ticket":"${"a".repeat(64)}"}"""))
        val peerSocket = SyntheticSocket(wire)
        peer = peerSocket
        client.libraryPlaybackWebSocketFactory = { admitted ->
            // The source wrapper must not replace the authenticated TLS/route/output stack.
            assertSame(client.httpClient.sslSocketFactory, admitted.sslSocketFactory)
            assertSame(client.httpClient.hostnameVerifier, admitted.hostnameVerifier)
            assertSame(client.httpClient.certificatePinner, admitted.certificatePinner)
            assertSame(client.httpClient.connectionPool, admitted.connectionPool)
            assertSame(client.httpClient.dispatcher, admitted.dispatcher)
            admitted.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                upgrade.set(request)
                val accept = (request.header("Sec-WebSocket-Key")!! + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                    .encodeUtf8().sha1().base64()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols")
                    .header("Connection", "Upgrade").header("Upgrade", "websocket")
                    .header("Sec-WebSocket-Accept", accept)
                    .header("Sec-WebSocket-Protocol", LibraryPlaybackProtocol.SUBPROTOCOL)
                    .apply {
                        if (socketPresent) socket(peerSocket)
                        if (extension != null) header("Sec-WebSocket-Extensions", extension)
                    }.build()
            }.build()
        }
        return client.openLibraryPlayback(scope, "Phone", "phone", true).also { transport = it }
    }
    private suspend fun closed(opened: LibraryPlaybackTransport) {
        val event = withTimeout(5000) { opened.events.first() } as LibraryPlaybackEvent.Closed
        assertEquals(LibraryPlaybackEvent.Reason.PROTOCOL, event.reason)
        assertFalse(opened.isCurrent)
        assertNull(opened.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Resume)))
        // onFailure publishes Closed before OkHttp's finally closes the source.
        assertTrue(peer!!.canceled.await(5, TimeUnit.SECONDS))
    }

    @Test fun realReaderNeverDeliversMalformedOriginalTextEvenIfReplacementWouldBeValidJson() = runBlocking {
        // Previously Okio turned C0 AF into replacement characters in a valid display name.
        val json = librarySnapshotFrame().replace("Phone", "NAME")
        val prefix = json.substringBefore("NAME").toByteArray(Charsets.UTF_8)
        val suffix = json.substringAfter("NAME").toByteArray(Charsets.UTF_8)
        val raw = prefix + byteArrayOf(0xc0.toByte(), 0xaf.toByte()) + suffix
        closed(open(libraryWireFrame(1, raw)))
    }

    @Test fun realReaderRejectsMalformedScalarSplitAcrossContinuationsAndInterleavedPing() = runBlocking {
        val json = librarySnapshotFrame().replace("Phone", "NAME")
        val wire = libraryWireFrame(1, json.substringBefore("NAME").toByteArray(Charsets.UTF_8) + byteArrayOf(0xed.toByte()), false)
        wire.writeAll(libraryWireFrame(9, byteArrayOf(0xff.toByte())))
        wire.writeAll(libraryWireFrame(0, byteArrayOf(0xa0.toByte()), false))
        wire.writeAll(libraryWireFrame(10, byteArrayOf(0x80.toByte())))
        wire.writeAll(libraryWireFrame(0, byteArrayOf(0x80.toByte()) + json.substringAfter("NAME").toByteArray(Charsets.UTF_8)))
        closed(open(wire))
    }

    @Test fun realReaderPreservesLiteralReplacementScalarControlsAndApplicationOrder() = runBlocking {
        val snapshot = librarySnapshotFrame().replace("Phone", "Phone�🎵").toByteArray(Charsets.UTF_8)
        // Split inside U+FFFD, not at a UTF-8/code-point boundary.
        val scalar = "Phone".toByteArray(Charsets.UTF_8)
        var split = -1
        for (i in 0..snapshot.size - scalar.size) {
            if (scalar.indices.all { snapshot[i + it] == scalar[it] }) { split = i + scalar.size + 1; break }
        }
        check(split > 0)
        val wire = libraryWireFrame(1, snapshot.copyOfRange(0, split), false)
        wire.writeAll(libraryWireFrame(9, byteArrayOf(0xff.toByte())))
        wire.writeAll(libraryWireFrame(10, byteArrayOf(0x80.toByte())))
        wire.writeAll(libraryWireFrame(0, snapshot.copyOfRange(split, snapshot.size)))
        wire.writeAll(libraryWireFrame(1, libraryInstructionFrame().toByteArray(Charsets.UTF_8)))
        val opened = open(wire)
        val events = withTimeout(5000) { opened.events.take(2).toList() }
        val first = (events[0] as LibraryPlaybackEvent.Message).message
        val second = (events[1] as LibraryPlaybackEvent.Message).message
        assertEquals("snapshot", first.type)
        assertEquals("Phone�🎵", first.snapshot!!.devices.single().name)
        assertEquals("instruction", second.type)
        assertTrue(opened.isCurrent)
        assertNull(upgrade.get().header("Sec-WebSocket-Extensions"))
        assertEquals(client.server.origin, upgrade.get().header("Origin"))
        assertEquals("spofify_session=cookie-secret", upgrade.get().header("Cookie"))
        opened.close()
    }

    @Test fun realReaderRefusesOversizedDeclarationWithoutWaitingForOrAllocatingPayload() = runBlocking {
        closed(open(libraryWireHeader(1, LibraryPlaybackProtocol.INBOUND_BYTES.toLong() + 1)))
    }

    @Test fun realReaderRefusesCumulativeFragmentedOversizeBeforeRequestingTheOffendingPayload() = runBlocking {
        val limit = LibraryPlaybackProtocol.INBOUND_BYTES.toLong()
        val first = libraryWireHeader(1, limit, false)
        val last = libraryWireHeader(0, 1)
        val payload = ByteArray(8192) { 0x61 }
        var remaining = limit
        val generated = object : Source {
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (first.size > 0) return first.read(sink, byteCount)
                if (remaining > 0) {
                    val count = minOf(byteCount, remaining, payload.size.toLong()).toInt()
                    sink.write(payload, 0, count); remaining -= count
                    return count.toLong()
                }
                if (last.size > 0) return last.read(sink, byteCount)
                // No extra payload exists: any request here would block/fail the test,
                // instead of detecting oversize from the cumulative frame headers.
                throw IOException("Offending payload was requested")
            }
        }
        closed(open(generated))
        assertEquals(0L, remaining)
        assertEquals(0L, last.size)
    }

    @Test fun negotiatedCompressionIsRejectedRatherThanDecompressedOutsideTheBound() = runBlocking {
        closed(open(libraryWireFrame(1, librarySnapshotFrame().toByteArray(Charsets.UTF_8)), "permessage-deflate"))
    }

    @Test fun missingPublicSocketFailsClosedWithoutABodyOrListenerFallback() = runBlocking {
        val opened = open(Buffer(), socketPresent = false)
        val event = withTimeout(5000) { opened.events.first() } as LibraryPlaybackEvent.Closed
        assertEquals(LibraryPlaybackEvent.Reason.PROTOCOL, event.reason)
        assertFalse(opened.isCurrent)
    }

    private class SyntheticSocket(private val wire: Source) : okio.Socket {
        val canceled = CountDownLatch(1)
        override val sink = Buffer()
        override val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun close() { cancel(); wire.close() }
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (canceled.count == 0L) throw IOException("Synthetic socket closed")
                val read = wire.read(sink, byteCount)
                if (read != -1L) return read
                // Keep the valid socket open until the consumer closes it. Otherwise EOF
                // correctly retires queued authority before the collector can observe it.
                if (!canceled.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic socket deadline")
                throw IOException("Synthetic socket closed")
            }
        }
        override fun cancel() { canceled.countDown() }
    }
}
