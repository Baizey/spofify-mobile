package dev.spofify.mobile.core.saved

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One admitted transfer per store; buffers are 64 KiB and each track has bounded attempts.
 * Runs on caller's IO executor, never launches work, selects output, or retries a deferred job.
 * Active playback can preempt this worker by setNetworkAllowed(false); explicit retry resumes it.
 */
class SavedDownloader(
    private val store: SavedLibraryStore,
    private val transport: SavedDownloadTransport,
    private val maxAttemptsPerTrack: Int = 3,
) {
    init { require(maxAttemptsPerTrack in 1..8) }

    suspend fun download(scope: SavedScope, target: SavedTarget): Unit = coroutineScope {
        val work = store.beginWork(scope, target) ?: return@coroutineScope
        val job = currentCoroutineContext()[Job]!!
        val cancellation = SavedTransferCancellation { job.isActive }
        val jobRegistration = cancellation.onCancel { job.cancel(CancellationException("Saved transfer cancelled")) }
        var failure: String? = null
        try {
            store.registerCancellation(work, cancellation)
            val tracks = store.workGeneration(work).items.filter { it.hasAudio }.map { it.trackId }.distinct()
            for (trackId in tracks) {
                store.checkWork(work)
                cancellation.throwIfCancelled()
                val result = downloadTrack(scope, work, trackId, cancellation)
                if (result != null) failure = result
                if (result == "retry_later" || result == "storage" || result == "auth") break
            }
        } catch (e: CancellationException) {
            failure = "cancelled"
            throw e
        } catch (_: SavedWorkCancelled) {
            failure = "cancelled"
        } catch (_: IOException) {
            failure = "transfer_failed"
        } finally {
            jobRegistration.close()
            cancellation.close()
            store.endWork(work, if (job.isActive) failure else "cancelled", canPublish = { job.isActive })
        }
    }

    private suspend fun downloadTrack(scope: SavedScope, work: SavedWork, trackId: String, cancellation: SavedTransferCancellation): String? {
        var descriptor = store.workGeneration(work).media[trackId]
        var lastFailure = "transfer_failed"
        repeat(maxAttemptsPerTrack) {
            currentCoroutineContext().ensureActive()
            store.checkWork(work)
            cancellation.throwIfCancelled()
            try {
                if (descriptor == null) {
                    descriptor = transport.prepare(scope, trackId)
                    require(descriptor!!.trackId == trackId) { "Prepared saved track mismatch" }
                    cancellation.throwIfCancelled()
                    store.bindDescriptor(work, descriptor!!, cancellation::throwIfCancelled)
                }
                val current = descriptor!!
                if (store.isVerified(work, current, cancellation::throwIfCancelled)) return null
                store.pinWriter(work, current)
                try {
                    transfer(scope, work, current, cancellation)
                    return null
                } finally { store.unpinWriter(work, current) }
            } catch (e: SavedTransportException) {
                when (e.status) {
                    // Only the authenticated owner's accepted INVALIDATED event can retire data.
                    401 -> return "auth"
                    404 -> return "unavailable"
                    409 -> {
                        descriptor?.let { store.backend.discardPartial(work.namespace.key, mediaKey(it)) }
                        descriptor = null
                        lastFailure = "version_changed"
                    }
                    429, 503 -> {
                        if (e.retryAfterMillis != null && e.retryAfterMillis > 0) return "retry_later"
                        lastFailure = "retry_later"
                    }
                    in 500..599 -> lastFailure = "transfer_failed"
                    else -> return "transfer_failed"
                }
            } catch (e: SavedWorkCancelled) { throw e
            } catch (_: SavedIntegrityFailure) {
                descriptor?.let { store.backend.discardPartial(work.namespace.key, mediaKey(it)) }
                lastFailure = "integrity"
            } catch (_: SavedStorageFailure) {
                return "storage"
            } catch (_: IOException) {
                // Only exact descriptor-bound partials survive. Next attempt revalidates all headers.
                lastFailure = "transfer_failed"
            } catch (_: IllegalArgumentException) {
                return "invalid_descriptor"
            }
        }
        return lastFailure
    }

    private suspend fun transfer(scope: SavedScope, work: SavedWork, descriptor: SavedMediaDescriptor, cancellation: SavedTransferCancellation) {
        val namespace = work.namespace.key
        val key = mediaKey(descriptor)
        var offset = disk { store.backend.length(namespace, key, true) } ?: 0
        if (offset >= descriptor.byteLength) {
            if (offset == descriptor.byteLength && diskInput(namespace, key).use { input -> hashMatches(input, descriptor) { store.checkWork(work); cancellation.throwIfCancelled() } }) {
                cancellation.throwIfCancelled()
                store.promote(work, descriptor, cancellation::throwIfCancelled)
                return
            }
            disk { store.backend.discardPartial(namespace, key) }
            offset = 0
        }
        store.checkWork(work)
        cancellation.throwIfCancelled()
        store.reserveTransfer(work, descriptor).use {
        transport.transfer(scope, descriptor, offset) { response ->
            cancellation.onCancel { response.close() }.use {
                store.checkWork(work)
                cancellation.throwIfCancelled()
                when (response.status) {
                    200, 206 -> Unit
                    else -> throw SavedTransportException(response.status, response.retryAfterMillis)
                }
                if (response.etag != descriptor.strongETag) throw SavedIntegrityFailure()
                if (response.status == 200) {
                    if (response.contentRange != null || response.contentLength != descriptor.byteLength) throw SavedIntegrityFailure()
                    // Range ignored or If-Range failed: full representation, NEVER append.
                    offset = 0
                } else {
                    val match = RANGE.matchEntire(response.contentRange ?: "") ?: throw SavedIntegrityFailure()
                    val start = match.groupValues[1].toLongOrNull() ?: throw SavedIntegrityFailure()
                    val end = match.groupValues[2].toLongOrNull() ?: throw SavedIntegrityFailure()
                    val total = match.groupValues[3].toLongOrNull() ?: throw SavedIntegrityFailure()
                    if (start != offset || end != descriptor.byteLength - 1 || total != descriptor.byteLength ||
                        start > end || response.contentLength != descriptor.byteLength - offset) throw SavedIntegrityFailure()
                }
                val body = response.body ?: throw SavedIntegrityFailure()
                diskOutput(namespace, key, offset).use { output ->
                    if (offset == 0L) store.restartTransferReservation(work, descriptor.byteLength)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = descriptor.byteLength - offset
                    while (remaining > 0) {
                        store.checkWork(work)
                        cancellation.throwIfCancelled()
                        val count = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count <= 0) throw IOException("Short saved media body")
                        // No writes from a body released while blocked on read.
                        store.checkWork(work)
                        cancellation.throwIfCancelled()
                        output.write(buffer, 0, count)
                        cancellation.throwIfCancelled()
                        store.transferred(work, count)
                        remaining -= count
                    }
                    store.checkWork(work)
                    cancellation.throwIfCancelled()
                    if (body.read() != -1) throw SavedIntegrityFailure()
                }
                store.checkWork(work)
                if (!diskInput(namespace, key).use { input -> hashMatches(input, descriptor) { store.checkWork(work); cancellation.throwIfCancelled() } }) throw SavedIntegrityFailure()
                cancellation.throwIfCancelled()
                store.promote(work, descriptor, cancellation::throwIfCancelled)
            }
        }
        }
    }

    private inline fun <T> disk(block: () -> T): T = try { block() } catch (e: IOException) { throw SavedStorageFailure(e) }
    private fun diskOutput(namespace: String, key: String, offset: Long): OutputStream {
        val output = disk { store.backend.writePartial(namespace, key, offset) }
        return object : OutputStream() {
            override fun write(b: Int) = disk { output.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = disk { output.write(b, off, len) }
            override fun close() = disk { output.close() }
        }
    }
    private fun diskInput(namespace: String, key: String): InputStream {
        val input = disk { store.backend.openPartial(namespace, key) }
        return object : java.io.FilterInputStream(input) {
            override fun read(): Int = disk { input.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = disk { input.read(b, off, len) }
            override fun close() = disk { input.close() }
        }
    }

    private companion object { val RANGE = Regex("bytes (0|[1-9][0-9]*)-(0|[1-9][0-9]*)/(0|[1-9][0-9]*)") }
}
private class SavedIntegrityFailure : IOException("Saved representation verification failed")
