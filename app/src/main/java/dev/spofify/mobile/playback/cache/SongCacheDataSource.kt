package dev.spofify.mobile.playback.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Authorization surrounds cache lookup AND each read; an upstream-only resolver is insufficient. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SongCacheDataSource(
    private val session: SongCache.SessionCache?,
    private val upstream: DataSource.Factory,
    private val trackId: (DataSpec) -> String?,
    private val authorize: (DataSpec) -> Unit,
    private val onDemandNetworkChange: (Boolean) -> Unit,
    private val speculative: Boolean = false,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var spec: DataSpec? = null
    private var id: String? = null
    @Volatile private var network: DataSource? = null
    private var cached: FileDataSource? = null
    private var pin: SongCache.Entry? = null
    private var writer: SongCache.Writer? = null
    private var consumed = 0L
    private var expected = C.LENGTH_UNSET.toLong()
    private var networkNotified = false
    private var completed = false

    private fun guard() {
        val request = spec ?: throw IOException("Audio source is not open")
        session?.check()
        authorize(request)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(spec == null) { "DataSource is already open" }
        spec = dataSpec
        guard()
        id = trackId(dataSpec)
        val entry = id?.let { session?.pin(it) }
        if (entry != null) {
            pin = entry
            val file = session!!.file(entry)
            if (file != null && verifies(file, entry)) {
                guard()
                if (dataSpec.position > entry.length) {
                    throw DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                }
                expected = if (dataSpec.length == C.LENGTH_UNSET.toLong()) entry.length - dataSpec.position
                    else minOf(dataSpec.length, entry.length - dataSpec.position)
                // FileDataSource accepts position==EOF with an unbounded length. Avoid constructing
                // a zero-length DataSpec (forbidden by Media3) for that valid edge case.
                val fileSpec = dataSpec.buildUpon().setUri(Uri.fromFile(file)).setLength(
                    if (expected == 0L) C.LENGTH_UNSET.toLong() else expected,
                ).build()
                val source = FileDataSource()
                listeners.forEach(source::addTransferListener)
                try {
                    source.open(fileSpec)
                    cached = source
                    guard()
                    return expected
                } catch (_: IOException) {
                    try { source.close() } catch (_: IOException) { /* cache-only close failure */ }
                    // An authorization failure must not become an upstream/cache fallback.
                    guard()
                }
            }
            discardHit()
        }
        return openNetwork(dataSpec, mayRetain = true)
    }

    /** Hash before exposing a hit, so same-length corruption cannot silently produce wrong audio. */
    private fun verifies(file: File, entry: SongCache.Entry): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
        val input = try { file.inputStream() } catch (_: IOException) { return false }
        try {
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                guard()
                val count = try { input.read(buffer) } catch (_: IOException) { return false }
                guard()
                if (count < 0) break
                total += count
                if (total > entry.length) return false
                hash.update(buffer, 0, count)
            }
            return total == entry.length && MessageDigest.isEqual(hash.digest(), entry.digest)
        } finally { try { input.close() } catch (_: IOException) { /* cache-only close failure */ } }
    }

    private fun openNetwork(request: DataSpec, mayRetain: Boolean): Long {
        guard()
        if (!speculative) {
            session?.demandNetwork()
            networkNotified = true
            onDemandNetworkChange(true)
        }
        val source = upstream.createDataSource()
        network = source
        listeners.forEach(source::addTransferListener)
        // Do not catch upstream errors: redirects/auth/media failures retain ordinary semantics.
        val length = source.open(request)
        guard()
        val coverage = validateResponse(source, request, length)
        expected = coverage.readLength
        if (mayRetain && coverage.retain && id != null && session != null) writer = session.writer(id!!)
        if (speculative && writer == null) throw IOException("Audio cache admission unavailable")
        return expected
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        guard()
        if (length == 0) return 0
        if (completed) return C.RESULT_END_OF_INPUT
        val local = cached
        val count: Int
        if (local != null) {
            count = try {
                if (consumed == expected) C.RESULT_END_OF_INPUT else local.read(buffer, offset, length)
            } catch (_: IOException) {
                guard()
                return fallbackRead(buffer, offset, length)
            }
            if (count == C.RESULT_END_OF_INPUT && consumed < expected) return fallbackRead(buffer, offset, length)
        } else {
            // Bounded tee chunks reserve actual bytes before each unbuffered disk write.
            val chunk = minOf(length, session?.chunkBytes() ?: 16 * 1024)
            count = try { network!!.read(buffer, offset, chunk) } catch (error: IOException) {
                writer?.abort(); writer = null
                throw error
            }
        }
        guard()
        if (count == C.RESULT_END_OF_INPUT) {
            if (expected != C.LENGTH_UNSET.toLong() && consumed != expected) {
                writer?.abort(); writer = null
                // Upstream truncation is a truthful media failure, never a retained success.
                throw IOException("Audio response ended before its declared length")
            }
            writer?.finish(); writer = null
            completed = true
        } else {
            consumed += count
            if (writer?.append(buffer, offset, count) == false) {
                writer = null
                if (speculative) throw IOException("Audio cache budget unavailable")
            }
            // Players may close immediately after the declared bytes without an extra EOF read.
            if (expected >= 0 && consumed == expected) {
                writer?.finish(); writer = null
                completed = true
            }
        }
        return count
    }

    private fun fallbackRead(buffer: ByteArray, offset: Int, length: Int): Int {
        guard()
        discardHit()
        val original = spec!!
        val remaining = if (original.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else original.length - consumed
        if (remaining == 0L) { completed = true; return C.RESULT_END_OF_INPUT }
        val next = original.buildUpon().setPosition(original.position + consumed).setLength(remaining).build()
        val prefix = consumed
        val lengthLeft = openNetwork(next, mayRetain = false)
        expected = if (lengthLeft < 0) lengthLeft else prefix + lengthLeft
        return read(buffer, offset, length)
    }

    private fun discardHit() {
        try { cached?.close() } catch (_: IOException) { /* local I/O failure is a miss */ }
        cached = null
        pin?.let { session!!.invalidate(it); session.unpin(it) }
        pin = null
    }

    /** Only the token invokes this concurrently; it never releases pins or deletes writer files. */
    internal fun cancelNetwork() { try { network?.close() } catch (_: IOException) { } }

    override fun getUri(): Uri? = spec?.uri // Never expose app-private span paths to the player.
    override fun getResponseHeaders(): Map<String, List<String>> = network?.responseHeaders.orEmpty()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun close() {
        writer?.abort(); writer = null
        try {
            try { cached?.close() } catch (_: IOException) { /* cache failures do not fail playback */ }
            cached = null
            pin?.let { session!!.unpin(it) }; pin = null
            network?.close()
        } finally {
            network = null
            spec = null
            consumed = 0
            completed = false
            expected = C.LENGTH_UNSET.toLong()
            if (networkNotified) { networkNotified = false; onDemandNetworkChange(false) }
        }
    }

    /**
     * Return total length only for a complete GET response. Unknown-length 200 is eligible and
     * completes only at clean EOF. Partial requests stream normally, but are never assembled with
     * other responses. Invalid range coverage fails as an upstream protocol error, even uncached.
     */
    private data class Coverage(val readLength: Long, val retain: Boolean)

    private fun validateResponse(source: DataSource, request: DataSpec, openedLength: Long): Coverage {
        val http = source as? HttpDataSource ?: return Coverage(openedLength, false)
        fun header(name: String) = source.responseHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value?.singleOrNull()
        if (source.uri != request.uri) throw IOException("Redirected audio response")
        val code = http.responseCode
        val declaredLength = header("Content-Length")?.let {
            it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: throw IOException("Invalid audio content length")
        }
        var readLength = openedLength
        var wholeResponse = true
        when (code) {
            200 -> {
                if (header("Content-Range") != null) throw IOException("Unexpected audio range")
                if (request.length >= 0 && declaredLength != null) {
                    readLength = minOf(request.length, (declaredLength - request.position).coerceAtLeast(0))
                }
            }
            206 -> {
                val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(header("Content-Range").orEmpty())
                    ?: throw IOException("Invalid audio range")
                val (start, end, size) = match.destructured
                val first = start.toLongOrNull() ?: throw IOException("Invalid audio range")
                val last = end.toLongOrNull() ?: throw IOException("Invalid audio range")
                val total = size.toLongOrNull() ?: throw IOException("Invalid audio range")
                if (first != request.position || last < first || last >= total) throw IOException("Invalid audio range coverage")
                readLength = last - first + 1
                if (request.length >= 0) {
                    if (readLength != minOf(request.length, total - first)) throw IOException("Invalid audio range coverage")
                } else if (openedLength >= 0 && openedLength != readLength) throw IOException("Invalid audio range length")
                if (declaredLength != null && declaredLength != readLength) throw IOException("Invalid audio range length")
                wholeResponse = first == 0L && last == total - 1
            }
            416 -> return Coverage(0, false) // Media3 accepts only a valid empty seek at EOF; never retain it.
            else -> throw IOException("Unexpected audio response")
        }
        val retain = wholeResponse && request.httpMethod == DataSpec.HTTP_METHOD_GET && request.position == 0L &&
            request.length == C.LENGTH_UNSET.toLong() && header("Content-Encoding")?.let { !it.equals("identity", true) } != true
        return Coverage(readLength, retain)
    }
}
