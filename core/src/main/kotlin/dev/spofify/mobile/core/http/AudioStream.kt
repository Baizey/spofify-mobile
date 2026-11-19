package dev.spofify.mobile.core.http

import java.io.Closeable
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Response evidence for checking a retry, not a persistent source identifier. */
@ConsistentCopyVisibility
data class AudioRepresentation internal constructor(
    val totalLength: Long?, val etag: String?, val lastModified: String?, val mediaType: String?,
    internal val unambiguous: Boolean, internal val hasValidator: Boolean,
) {
    val canContinue: Boolean get() = totalLength != null && unambiguous && hasValidator
}

/** One owned authenticated byte range. It is not a reusable URL or a credential-bearing request. */
class AudioStream internal constructor(
    source: InputStream,
    val length: Long,
    val representation: AudioRepresentation = AudioRepresentation(length, null, null, null, true, false),
    private val permitted: () -> Boolean,
    private val recoverable: Boolean = false,
    private val cancelCall: () -> Unit = {},
    private val finish: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    val mediaType: String? get() = representation.mediaType

    /** Validate before delivering any retry bytes; ordinary opens retain their existing range rules. */
    fun checkContinuation(previous: AudioRepresentation?, position: Long, requestedLength: Long?) {
        if (previous != null) {
            if (representation != previous) throw IOException("Audio representation changed during retry")
            if (!previous.canContinue) {
                throw IOException("Audio retry has no representation validator")
            }
        }
        val total = representation.totalLength ?: throw IOException("Audio retry has no complete length")
        if (!representation.unambiguous || position >= total || length != minOf(total - position, requestedLength ?: Long.MAX_VALUE)) {
            throw IOException("Audio retry did not supply the requested range")
        }
    }

    val input: InputStream = object : FilterInputStream(source) {
        private var remaining = length

        private inline fun <T> access(read: () -> T): T {
            if (closed.get() || !permitted()) throw IOException("Audio session retired")
            try {
                return read()
            } catch (error: IOException) {
                if (recoverable && !closed.get() && permitted() && error.isAudioInterruption(remaining > 0)) {
                    throw TransientAudioLoadFailure(error)
                }
                throw error
            }
        }

        private fun consumed(count: Int) {
            if (remaining > 0) {
                if (count < 0) throw EOFException("Audio range ended early")
                remaining -= count
            }
        }

        override fun read(): Int = access { super.read().also { consumed(if (it < 0) -1 else 1) } }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            access { super.read(buffer, offset, length).also(::consumed) }
        override fun skip(count: Long): Long = access {
            super.skip(count).also { if (remaining > 0) remaining -= it }
        }
        override fun close() = this@AudioStream.close()
    }

    /** Wake a loader blocked inside read. The loader still owns closing its response body. */
    fun cancel() = cancelCall()

    override fun close() {
        if (closed.compareAndSet(false, true)) finish()
    }
}
