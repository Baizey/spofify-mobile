package dev.spofify.mobile.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.cache.MusicSourceChoice
import dev.spofify.mobile.core.http.AudioRepresentation
import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.http.TransientAudioLoadFailure
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Both branches use the same Media3 range. Only HTTP contributes bandwidth or retry events. */
@UnstableApi
internal class SessionAudioDataSource(
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
    private val choice: MusicSourceChoice,
    private val currentSource: () -> AudioSourceOwner?,
) : DataSource {
    private var local: AudioStream? = null
    private val disk = RangeSource(false) { requireNotNull(local).also { local = null } }
    private val network = RangeSource(true, ::openNetwork)
    private var active: RangeSource? = null
    private var cancellation: DisposableHandle? = null
    private var representation: AudioRepresentation? = null
    private lateinit var retry: AudioLoadRetry

    override fun open(dataSpec: DataSpec): Long {
        check(active == null)
        val uri = dataSpec.uri
        require(uri.scheme == "spofify" && uri.authority == "audio" && uri.pathSegments.size == 1 && uri.query == null && uri.fragment == null)
        val id = requireNotNull(uri.lastPathSegment)
        val installed = currentSource().forRange(id, dataSpec.key)
        local = choice.openLocal(id, dataSpec.position)
        val source = if (local != null) disk else network
        active = source
        return try {
            if (source === network) {
                if (!::retry.isInitialized) retry = AudioLoadRetry(installed)
                if (retry.source !== installed) throw InterruptedIOException("Audio period replaced")
            }
            source.open(dataSpec)
        } catch (e: Exception) { close(); throw e }
    }

    private fun openNetwork(dataSpec: DataSpec): AudioStream {
        val pending = retry.pending.get()
        val job = pending?.job ?: retry.source.job
        val length = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        fun checkOwner() {
            if (!retry.source.job.isActive || pending != null && (!pending.isCurrent() || !sessions.isCurrent(identity))) {
                throw InterruptedIOException("Audio load retired")
            }
        }
        var acquired: AudioStream? = null
        try {
            checkOwner()
            val opened = runBlocking(job) {
                checkOwner()
                suspend fun acquire() = sessions.audio(identity, retry.source.trackId, dataSpec.position, length).also { acquired = it }
                val budget = pending?.remainingPrepareRetryMs()
                if (budget == null) acquire() else withTimeout(budget) { acquire() }
            }
            if (pending != null) opened.checkContinuation(representation, dataSpec.position, length)
            cancellation = job.invokeOnCompletion { opened.cancel() }
            checkOwner()
            representation = opened.representation
            return opened
        } catch (failure: Exception) {
            acquired?.close()
            throw when (failure) {
                is TransientAudioLoadFailure -> failure.forLoader()
                is CancellationException, is InterruptedException -> InterruptedIOException("Audio load cancelled")
                else -> failure
            }
        }
    }

    private fun TransientAudioLoadFailure.forLoader(): IOException {
        val owner = retry.source.interrupted(retry)
        return if (owner != null && sessions.isCurrent(identity) && representation?.canContinue != false) {
            RetryableAudioLoad(owner, retry, this)
        } else this
    }

    override fun addTransferListener(listener: TransferListener) { disk.addTransferListener(listener); network.addTransferListener(listener) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = requireNotNull(active)
        return try { source.read(buffer, offset, length) } catch (failure: TransientAudioLoadFailure) {
            throw if (source === network) failure.forLoader() else failure
        }
    }
    override fun getUri() = active?.uri
    override fun close() {
        cancellation?.dispose()
        cancellation = null
        try { active?.close() } finally { active = null; local?.close(); local = null }
    }
}

@UnstableApi
private class RangeSource(network: Boolean, private val acquire: (DataSpec) -> AudioStream) : BaseDataSource(network) {
    private var stream: AudioStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val length = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        val opened = acquire(dataSpec)
        stream = opened
        uri = dataSpec.uri
        remaining = if (length != null && opened.length >= 0) minOf(length, opened.length) else length ?: opened.length
        started = true
        transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val read = requireNotNull(stream).input.read(buffer, offset, count)
        if (read < 0) {
            if (remaining > 0) throw EOFException("Audio range ended early")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining > 0) remaining -= read
        bytesTransferred(read)
        return read
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        try { stream?.close() } finally {
            stream = null
            uri = null
            if (started) { started = false; transferEnded() }
        }
    }
}
