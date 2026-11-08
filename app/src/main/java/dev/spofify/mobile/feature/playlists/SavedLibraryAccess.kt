package dev.spofify.mobile.feature.playlists

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import dev.spofify.mobile.core.saved.SavedCollectionSnapshot
import dev.spofify.mobile.core.saved.SavedLibraryReader
import dev.spofify.mobile.core.saved.SavedMediaDescriptor
import dev.spofify.mobile.core.saved.SavedMediaLease
import dev.spofify.mobile.core.saved.SavedScope
import dev.spofify.mobile.core.saved.SavedTarget
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact installed occurrence, not a track-ID lookup or a server/local playback credential. */
data class SavedMediaRequest(
    val target: SavedTarget,
    val generation: String,
    val occurrenceId: String,
    val media: SavedMediaDescriptor,
) {
    init { require(generation.isNotBlank() && occurrenceId.isNotBlank()) }
}

/** One captured offline-reader incarnation. isCurrent checks identity/eligibility/incarnation ONLY,
 * not collection membership or an action selection guard. Eligibility comes from the offline owner,
 * never bytes, cookie timestamps, a URI or account-name equality. No network fallback. */
@androidx.annotation.OptIn(UnstableApi::class)
class SavedLibraryAccess(
    private val reader: SavedLibraryReader,
    private val scope: SavedScope,
    private val isCurrent: () -> Boolean,
) {
    private val retired = AtomicBoolean(false)
    private fun current() = !retired.get() && runCatching(isCurrent).getOrDefault(false)

    suspend fun collections(): List<SavedCollectionSnapshot> = withContext(Dispatchers.IO) {
        if (!current()) return@withContext emptyList()
        val snapshots = reader.snapshots(scope)
        currentCoroutineContext().ensureActive()
        if (current()) snapshots else emptyList()
    }

    /** Returns an UNOPENED, lease-free source: cancellation or discarded delivery owns no file.
     * Actual DataSource.open revalidates exact membership before AND after lease acquisition.
     * Once opened, reads do not look up membership again; Unsave/Update cannot revoke that lease.
     * Normal close releases it, and every reopen performs a new exact admission. */
    suspend fun openVerifiedMedia(request: SavedMediaRequest): DataSource? = withContext(Dispatchers.IO) {
        if (!current() || !matches(reader.snapshot(scope, request.target), request) || !current())
            return@withContext null
        currentCoroutineContext().ensureActive()
        VerifiedSavedDataSource(request.media.byteLength, ::current) { acquire(request) }
    }

    private fun matches(snapshot: SavedCollectionSnapshot?, request: SavedMediaRequest): Boolean {
        if (snapshot == null || snapshot.target != request.target || snapshot.generation != request.generation) return false
        val row = snapshot.occurrences.singleOrNull { it.occurrence.occurrenceId == request.occurrenceId } ?: return false
        return row.available && row.occurrence.hasAudio && row.occurrence.trackId == request.media.trackId && row.media == request.media
    }

    /** Blocking IO, only from actual DataSource.open, including reopening after normal close. */
    private fun acquire(request: SavedMediaRequest): SavedMediaLease? {
        if (!current() || !matches(reader.snapshot(scope, request.target), request) || !current()) return null
        val lease = reader.open(scope, request.target, request.occurrenceId) ?: return null
        try {
            if (lease.descriptor != request.media || !matches(reader.snapshot(scope, request.target), request) || !current()) {
                lease.close()
                return null
            }
            return lease
        } catch (error: Throwable) {
            try { lease.close() } catch (close: Throwable) { error.addSuppressed(close) }
            throw error
        }
    }

    /** Immediate access fence only: no locks, stream close, disk IO or waiting in auth callbacks. */
    fun retire() { retired.set(true) }
}

/** The URI is an opaque process-local label, never a path, network route or authorization token. */
@androidx.annotation.OptIn(UnstableApi::class)
private class VerifiedSavedDataSource(
    private val exactLength: Long,
    private val isCurrent: () -> Boolean,
    private val reacquire: () -> SavedMediaLease?,
) : BaseDataSource(false) {
    private val opaqueUri = Uri.Builder().scheme("spofify-saved").authority("media")
        .appendPath(UUID.randomUUID().toString()).build()
    private var lease: SavedMediaLease? = null
    private var opened = false
    private var remaining = 0L

    private fun guard() { if (!isCurrent()) throw IOException("Saved media access is no longer current") }

    override fun open(dataSpec: DataSpec): Long {
        check(!opened) { "Saved media source is already open" }
        try {
            transferInitializing(dataSpec)
            guard()
            if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null)
                throw IOException("Saved media only supports local reads")
            if (dataSpec.position < 0 || dataSpec.position > exactLength ||
                dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length < 0)
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            val accepted = lease ?: reacquire()?.also { lease = it }
                ?: throw IOException("The requested saved occurrence is unavailable")
            guard()
            // Never InputStream.skip: consume a bounded buffer so every skip step is fenced.
            val buffer = ByteArray(32 * 1024)
            var skip = dataSpec.position
            while (skip > 0) {
                guard()
                val count = accepted.input.read(buffer, 0, minOf(skip, buffer.size.toLong()).toInt())
                guard()
                if (count <= 0) throw EOFException("Verified saved media ended before its position")
                skip -= count
            }
            remaining = exactLength - dataSpec.position
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
            guard()
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (error: Throwable) {
            try { close() } catch (close: Throwable) { error.addSuppressed(close) }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        check(opened) { "Saved media source is not open" }
        guard()
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val accepted = lease ?: throw IOException("Saved media source is closed")
        val count = accepted.input.read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        guard()
        if (count <= 0) throw EOFException("Verified saved media ended early")
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = if (opened) opaqueUri else null
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        val accepted = lease
        lease = null
        remaining = 0
        val wasOpened = opened
        opened = false
        try { accepted?.close() } finally { if (wasOpened) transferEnded() }
    }
}
