package dev.spofify.mobile.core

import java.io.EOFException
import java.net.ProtocolException
import okhttp3.Response
import okio.Buffer
import okio.Source

/** No peer bytes/diagnostics are retained in failures. */
internal class LibraryPlaybackIngressException : ProtocolException("Invalid library playback ingress")

/** OkHttp 5.5.1 exposes the upgraded, already authenticated/decrypted socket publicly.
 * Replace only its source, BEFORE RealWebSocket creates its buffered reader. Its sink,
 * cancellation, TLS verification and connection ownership remain unchanged. Never wrap
 * ResponseBody: WebSocket reads bypass it. Compression is deliberately not negotiated. */
internal fun guardLibraryPlaybackUpgrade(response: Response): Response {
    if (response.code != 101) return response
    try {
        if (response.headers.values("Sec-WebSocket-Extensions").isNotEmpty()) throw LibraryPlaybackIngressException()
        val socket = response.socket ?: throw LibraryPlaybackIngressException()
        val guarded = object : okio.Socket by socket {
            override val source: Source = LibraryPlaybackIngressSource(socket.source)
        }
        return response.newBuilder().socket(guarded).build()
    } catch (error: Exception) {
        response.socket?.cancel()
        response.close()
        throw error
    }
}

/** Pass-through guard, not a second WebSocket transport. OkHttp still owns frame delivery,
 * ping/pong, close codes, output and lifecycle. A fixed ten-byte header is checked BEFORE
 * forwarding it, so its reader cannot allocate the offending frame/message. Payload is
 * checked BEFORE forwarding in at most 8 KiB chunks, with constant UTF-8 state across
 * continuations and intervening controls. Upstream socket buffering has bounded read-ahead;
 * no whole-message buffering, decoding, copy or allocation is performed here. */
internal class LibraryPlaybackIngressSource(source: Source) : Source {
    private val upstream = source
    private val header = ByteArray(10)
    private val chunk = Buffer()
    private var headerSize = 0
    private var headerOffset = 0
    private var remaining = 0L
    private var payloadOffset = 0L
    private var opcode = 0
    private var final = false
    private var fragmented = false
    private var messageBytes = 0L
    private var sawClose = false
    private var failed = false
    private val text = Utf8()
    private val closeReason = Utf8()

    override fun timeout() = upstream.timeout()
    override fun close() = upstream.close()

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (failed) throw LibraryPlaybackIngressException()
        if (byteCount == 0L) return 0
        try {
            if (headerOffset == headerSize && remaining == 0L && !readHeader()) return -1
            if (headerOffset < headerSize) {
                val count = minOf(byteCount, (headerSize - headerOffset).toLong()).toInt()
                sink.write(header, headerOffset, count)
                headerOffset += count
                return count.toLong()
            }
            val count = upstream.read(chunk, minOf(byteCount, remaining, CHUNK_BYTES))
            if (count <= 0L) reject()
            for (i in 0L until count) {
                val value = chunk[i].toInt() and 0xff
                if (opcode == 0 || opcode == 1) text.accept(value)
                else if (opcode == 8 && payloadOffset + i >= 2) closeReason.accept(value)
            }
            payloadOffset += count
            remaining -= count
            if (remaining == 0L) finishFrame()
            sink.write(chunk, count)
            return count
        } catch (_: EOFException) {
            reject()
        } catch (error: LibraryPlaybackIngressException) {
            failed = true
            chunk.clear()
            throw error
        }
    }

    private fun readByteOrNull(): Int? {
        val count = upstream.read(chunk, 1)
        if (count == -1L) return null
        if (count != 1L) reject()
        return chunk.readByte().toInt() and 0xff
    }

    private fun readHeader(): Boolean {
        headerSize = 0
        headerOffset = 0
        val first = readByteOrNull() ?: run {
            if (fragmented) reject()
            return false
        }
        if (sawClose) reject()
        header[headerSize++] = first.toByte()
        fun byte(): Int = (readByteOrNull() ?: throw EOFException()).also { header[headerSize++] = it.toByte() }
        val second = byte()
        final = first and 0x80 != 0
        opcode = first and 0x0f
        // Server frames are unmasked; no extensions, binary messages or reserved opcodes.
        if (first and 0x70 != 0 || second and 0x80 != 0 ||
            (opcode != 0 && opcode != 1 && opcode != 8 && opcode != 9 && opcode != 10)) reject()
        val control = opcode >= 8
        val shortLength = second and 0x7f
        if (control && (!final || shortLength > 125)) reject()
        var length = shortLength.toLong()
        if (shortLength == 126 || shortLength == 127) {
            length = 0
            repeat(if (shortLength == 126) 2 else 8) { index ->
                val value = byte()
                if (shortLength == 127 && index == 0 && value and 0x80 != 0) reject()
                length = (length shl 8) or value.toLong()
            }
            if (length < (if (shortLength == 126) 126 else 65536)) reject()
        }
        if (!control) {
            if ((opcode == 0) != fragmented) reject()
            if (length > LibraryPlaybackProtocol.INBOUND_BYTES.toLong() - messageBytes) reject()
            messageBytes += length
            if (opcode == 1) text.reset()
            fragmented = !final
        } else if (opcode == 8) {
            if (length == 1L) reject()
            closeReason.reset()
        }
        payloadOffset = 0
        remaining = length
        if (remaining == 0L) finishFrame()
        return true
    }

    private fun finishFrame() {
        if ((opcode == 0 || opcode == 1) && final) {
            text.finish()
            messageBytes = 0
        } else if (opcode == 8) {
            closeReason.finish()
            // A close aborts any unfinished message; it never delivers partial text.
            fragmented = false
            messageBytes = 0
            sawClose = true
        }
    }

    private fun reject(): Nothing {
        failed = true
        chunk.clear()
        throw LibraryPlaybackIngressException()
    }

    /** RFC 3629 scalar-value validation. U+FFFD (EF BF BD) is ordinary valid input. */
    private class Utf8 {
        private var needed = 0
        private var low = 0x80
        private var high = 0xbf
        fun reset() { needed = 0; low = 0x80; high = 0xbf }
        fun finish() { if (needed != 0) throw LibraryPlaybackIngressException() }
        fun accept(value: Int) {
            if (needed != 0) {
                if (value !in low..high) throw LibraryPlaybackIngressException()
                needed--; low = 0x80; high = 0xbf
                return
            }
            when (value) {
                in 0..0x7f -> Unit
                in 0xc2..0xdf -> needed = 1
                in 0xe0..0xef -> {
                    needed = 2
                    if (value == 0xe0) low = 0xa0
                    if (value == 0xed) high = 0x9f
                }
                in 0xf0..0xf4 -> {
                    needed = 3
                    if (value == 0xf0) low = 0x90
                    if (value == 0xf4) high = 0x8f
                }
                else -> throw LibraryPlaybackIngressException()
            }
        }
    }

    private companion object { const val CHUNK_BYTES = 8192L }
}
