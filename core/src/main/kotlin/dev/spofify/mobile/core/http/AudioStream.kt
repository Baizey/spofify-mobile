package dev.spofify.mobile.core.http

import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One owned authenticated byte range. It is not a reusable URL or a credential-bearing request. */
class AudioStream internal constructor(
    source: InputStream,
    val length: Long,
    val mediaType: String?,
    private val permitted: () -> Boolean,
    private val finish: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    val input: InputStream = object : FilterInputStream(source) {
        private fun checkAccess() {
            if (closed.get() || !permitted()) throw IOException("Audio session retired")
        }
        override fun read(): Int { checkAccess(); return super.read() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkAccess()
            return super.read(buffer, offset, length)
        }
        override fun skip(count: Long): Long { checkAccess(); return super.skip(count) }
        override fun close() = this@AudioStream.close()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) finish()
    }
}
