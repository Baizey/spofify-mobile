package dev.spofify.mobile.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.cache.MusicSourceChoice
import dev.spofify.mobile.core.http.AudioStream
import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Both sources consume the same Media3 range, but only HTTP contributes bandwidth samples. */
@UnstableApi
internal class SessionAudioDataSource(sessions: SessionRepository, identity: AccountIdentity, private val choice: MusicSourceChoice) : DataSource {
    private var hit: AudioStream? = null
    private val disk = RangeSource(false) { _, _, _ -> requireNotNull(hit) }
    private val network = RangeSource(true) { id, start, length -> runBlocking(Dispatchers.IO) { sessions.audio(identity, id, start, length) } }
    private var active: RangeSource? = null

    override fun open(dataSpec: DataSpec): Long {
        check(active == null)
        val uri = dataSpec.uri
        require(uri.scheme == "spofify" && uri.authority == "audio" && uri.pathSegments.size == 1 && uri.query == null && uri.fragment == null)
        hit = choice.openLocal(requireNotNull(uri.lastPathSegment), dataSpec.position)
        val source = if (hit != null) disk else network
        active = source
        return try { source.open(dataSpec) } catch (e: Exception) { close(); throw e }
    }
    override fun addTransferListener(listener: TransferListener) { disk.addTransferListener(listener); network.addTransferListener(listener) }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = requireNotNull(active).read(buffer, offset, length)
    override fun getUri() = active?.uri
    override fun close() { try { active?.close() } finally { active = null; hit?.close(); hit = null } }
}

@UnstableApi
private class RangeSource(network: Boolean, private val range: (String, Long, Long?) -> AudioStream) : BaseDataSource(network) {
    private var stream: AudioStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val length = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        val opened = range(requireNotNull(dataSpec.uri.lastPathSegment), dataSpec.position, length)
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
