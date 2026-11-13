package dev.spofify.mobile.core

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.Source
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

/** Raw RFC6455 bytes, never replacement-decoded strings or an OkHttp test frame writer. */
internal fun libraryWireHeader(opcode: Int, length: Long, final: Boolean = true): Buffer = Buffer().apply {
    writeByte((if (final) 0x80 else 0) or opcode)
    when {
        length < 126 -> writeByte(length.toInt())
        length <= 65535 -> { writeByte(126); writeShort(length.toInt()) }
        else -> { writeByte(127); writeLong(length) }
    }
}
internal fun libraryWireFrame(opcode: Int, payload: ByteArray, final: Boolean = true): Buffer =
    libraryWireHeader(opcode, payload.size.toLong(), final).write(payload)

class LibraryPlaybackIngressTest {
    private fun drain(source: Source, consume: (Buffer) -> Unit = { it.clear() }): Long {
        val buffer = Buffer()
        var count = 0L
        source.use {
            while (true) {
                val read = it.read(buffer, Long.MAX_VALUE)
                if (read == -1L) break
                assertTrue(read > 0)
                count += read
                consume(buffer)
            }
        }
        return count
    }
    private fun reject(wire: Buffer) {
        val source = LibraryPlaybackIngressSource(wire)
        try { drain(source); fail("Invalid raw frame admitted") }
        catch (e: LibraryPlaybackIngressException) {
            assertEquals("Invalid library playback ingress", e.message)
        }
        try { source.read(Buffer(), 1); fail("Failed source revived") }
        catch (_: LibraryPlaybackIngressException) { }
    }

    @Test fun validScalarsIncludingReplacementCharacterSurviveEveryFragmentAndControlBoundaryUnchanged() {
        val payload = "\u0000a\u007f\u0080\u07ff\u0800\ud7ff\ue000\ufffd\uffff🎵\udbff\udfff".toByteArray(Charsets.UTF_8)
        val wire = Buffer()
        payload.forEachIndexed { index, byte ->
            wire.writeAll(libraryWireFrame(if (index == 0) 1 else 0, byteArrayOf(byte), index == payload.lastIndex))
            wire.writeAll(libraryWireFrame(9, byteArrayOf(0xff.toByte()))) // Controls are NOT text.
            wire.writeAll(libraryWireFrame(10, byteArrayOf(0x80.toByte())))
        }
        wire.writeAll(libraryWireFrame(1, "second�".toByteArray(Charsets.UTF_8)))
        wire.writeAll(libraryWireFrame(8, byteArrayOf(3, 0xe8.toByte()) + "bye�".toByteArray(Charsets.UTF_8)))
        val expected = wire.clone().readByteString()
        val actual = Buffer()
        drain(LibraryPlaybackIngressSource(wire)) { actual.writeAll(it) }
        assertEquals(expected, actual.readByteString())
    }

    @Test fun malformedOriginalUtf8IsRejectedIncludingOverlongSurrogatesOutOfRangeAndTruncation() {
        val malformed = listOf(
            listOf(0x80), listOf(0xc0, 0xaf), listOf(0xc1, 0xbf), listOf(0xc2),
            listOf(0xe0, 0x80, 0xaf), listOf(0xed, 0xa0, 0x80), listOf(0xe2, 0x28, 0xa1),
            listOf(0xf0, 0x80, 0x80, 0xaf), listOf(0xf4, 0x90, 0x80, 0x80),
            listOf(0xf5, 0x80, 0x80, 0x80), listOf(0xf0, 0x9f, 0x92), listOf(0xff),
        )
        for (values in malformed) {
            val bytes = values.map { it.toByte() }.toByteArray()
            reject(libraryWireFrame(1, bytes))
            val fragmented = Buffer()
            bytes.forEachIndexed { index, byte ->
                fragmented.writeAll(libraryWireFrame(if (index == 0) 1 else 0, byteArrayOf(byte), false))
                fragmented.writeAll(libraryWireFrame(9, byteArrayOf()))
            }
            fragmented.writeAll(libraryWireFrame(0, byteArrayOf()))
            reject(fragmented)
        }
        // A new text frame cannot repair an incomplete final scalar.
        reject(libraryWireFrame(1, byteArrayOf(0xc2.toByte())).apply {
            writeAll(libraryWireFrame(1, byteArrayOf(0x80.toByte())))
        })
    }

    @Test fun closeReasonIsStrictUtf8ButCloseMayAbortAnUndeliveredFragment() {
        reject(libraryWireFrame(8, byteArrayOf(3, 0xe8.toByte(), 0xc0.toByte(), 0xaf.toByte())))
        reject(libraryWireFrame(8, byteArrayOf(3, 0xe8.toByte(), 0xe2.toByte())))
        val wire = libraryWireFrame(1, byteArrayOf(0xf0.toByte()), false).apply {
            writeAll(libraryWireFrame(8, byteArrayOf(3, 0xe8.toByte())))
        }
        val expected = wire.clone().readByteString()
        val actual = Buffer()
        drain(LibraryPlaybackIngressSource(wire)) { actual.writeAll(it) }
        assertEquals(expected, actual.readByteString())
    }

    @Test fun oversizedHeaderIsRejectedBeforeForwardingHeaderOrReadingAnyPayload() {
        for (length in listOf(LibraryPlaybackProtocol.INBOUND_BYTES.toLong() + 1, Long.MAX_VALUE)) {
            val header = libraryWireHeader(1, length)
            val bytes = header.size
            val input = ScriptedSource(listOf(header, 1L))
            val output = Buffer()
            try { LibraryPlaybackIngressSource(input).read(output, Long.MAX_VALUE); fail() }
            catch (_: LibraryPlaybackIngressException) { }
            assertEquals(bytes, input.readBytes)
            assertEquals(0L, output.size)
            assertTrue(input.maxRequest <= 1)
        }
    }

    @Test fun fragmentedLimitIsCumulativeAcrossControlsAndRefusesNextPayloadBeforeAllocation() {
        val limit = LibraryPlaybackProtocol.INBOUND_BYTES.toLong()
        // Neither frame is individually oversized. No large test array/message is allocated.
        val first = libraryWireHeader(1, limit / 2, false)
        val ping = libraryWireFrame(9, byteArrayOf(0xff.toByte()))
        val second = libraryWireHeader(0, limit / 2, false)
        val third = libraryWireHeader(0, 1)
        val accepted = first.size + limit / 2 + ping.size + second.size + limit / 2
        val allHeaders = first.size + ping.size + second.size + third.size
        val input = ScriptedSource(listOf(first, limit / 2, ping, second, limit / 2, third, 1L))
        val source = LibraryPlaybackIngressSource(input)
        var forwarded = 0L
        try { drain(source) { forwarded += it.size; it.clear() }; fail() }
        catch (_: LibraryPlaybackIngressException) { }
        assertEquals(accepted, forwarded)
        assertEquals(limit + allHeaders, input.readBytes) // No byte of offending payload read.
        assertTrue(input.maxRequest <= 8192)
    }

    @Test fun exactSixteenMiBMessageAndFreshMessageAfterItAreAdmittedWithoutSharedBudget() {
        val limit = LibraryPlaybackProtocol.INBOUND_BYTES.toLong()
        val first = libraryWireHeader(1, limit, false)
        val final = libraryWireHeader(0, 0)
        val next = libraryWireFrame(1, "�".toByteArray(Charsets.UTF_8))
        val expected = first.size + limit + final.size + next.size
        val input = ScriptedSource(listOf(first, limit, final, next))
        assertEquals(expected, drain(LibraryPlaybackIngressSource(input)))
        assertTrue(input.maxRequest <= 8192)
    }

    @Test fun invalidFrameStructureCannotResetOrBypassMessageState() {
        val empty = byteArrayOf()
        val invalid = listOf(
            libraryWireFrame(0, empty), // continuation without a message
            libraryWireFrame(2, empty), // binary is forbidden, including empty binary
            libraryWireFrame(3, empty), libraryWireFrame(11, empty),
            libraryWireFrame(9, empty, false), // fragmented control
            libraryWireFrame(10, ByteArray(126)),
            libraryWireFrame(8, byteArrayOf(3)),
            Buffer().writeByte(0xc1).writeByte(0), // RSV1 (compressed) without negotiation
            Buffer().writeByte(0x81).writeByte(0x80), // masked server frame
            Buffer().writeByte(0x81).writeByte(126).writeShort(1), // nonminimal lengths
            Buffer().writeByte(0x81).writeByte(127).writeLong(126),
            Buffer().writeByte(0x81).writeByte(127).writeLong(Long.MIN_VALUE),
            libraryWireHeader(1, 126).writeByte(0x61), // truncated payload
            Buffer().writeByte(0x81), // truncated header
            libraryWireFrame(1, empty, false), // EOF before final continuation
            libraryWireFrame(1, empty, false).apply { writeAll(libraryWireFrame(1, empty)) },
            libraryWireFrame(8, empty).apply { writeAll(libraryWireFrame(1, empty)) },
        )
        invalid.forEach(::reject)
    }

    @Test fun publicUpgradeAdapterPreservesExactNativeSinkCancellationTimeoutAndSourceClose() {
        var cancels = 0
        var closes = 0
        val timeout = Timeout()
        val original = object : okio.Socket {
            override val sink = Buffer()
            override val source = object : Source {
                override fun timeout() = timeout
                override fun read(sink: Buffer, byteCount: Long) = -1L
                override fun close() { closes++ }
            }
            override fun cancel() { cancels++ }
        }
        val response = Response.Builder().request(Request.Builder().url("https://example.invalid/socket").build())
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").socket(original).build()
        val guarded = guardLibraryPlaybackUpgrade(response).socket!!
        assertNotSame(original, guarded)
        assertSame(original.sink, guarded.sink)
        assertSame(timeout, guarded.source.timeout())
        guarded.cancel()
        assertEquals(1, cancels)
        guarded.source.close()
        assertEquals(1, closes)
        assertEquals(1, cancels) // Source close and whole-socket cancellation remain distinct.
        assertSame(original, response.socket) // No mutation of the original socket/response.
    }

    @Test fun oneByteReadsPreserveHeaderPayloadAndUtf8StateAndZeroReadConsumesNothing() {
        val wire = libraryWireFrame(1, "🎵�".toByteArray(Charsets.UTF_8))
        val expected = wire.clone().readByteString()
        val source = LibraryPlaybackIngressSource(wire)
        val output = Buffer()
        assertEquals(0L, source.read(output, 0))
        assertEquals(expected.size.toLong(), wire.size)
        while (source.read(output, 1) != -1L) Unit
        assertEquals(expected, output.readByteString())
        source.close()
    }

    /** Buffers are small wire headers/controls; Longs generate ASCII payload incrementally. */
    private class ScriptedSource(parts: List<Any>) : Source {
        private val remaining = java.util.ArrayDeque(parts)
        private val ascii = ByteArray(8192) { 0x61 }
        var readBytes = 0L
        var maxRequest = 0L
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
        override fun read(sink: Buffer, byteCount: Long): Long {
            maxRequest = maxOf(maxRequest, byteCount)
            if (byteCount == 0L) return 0
            while (!remaining.isEmpty()) {
                val part = remaining.first
                val count = when (part) {
                    is Buffer -> {
                        if (part.size == 0L) { remaining.removeFirst(); continue }
                        part.read(sink, byteCount)
                    }
                    is Long -> {
                        if (part == 0L) { remaining.removeFirst(); continue }
                        val count = minOf(byteCount, part, ascii.size.toLong()).toInt()
                        sink.write(ascii, 0, count)
                        remaining.removeFirst(); remaining.addFirst(part - count)
                        count.toLong()
                    }
                    else -> error("Invalid synthetic segment")
                }
                readBytes += count
                return count
            }
            return -1
        }
    }
}
