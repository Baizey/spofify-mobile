package dev.spofify.mobile.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.AudioStream
import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Media3 supplies byte ranges; the session owner supplies private HTTP authority. */
@UnstableApi
internal class SessionAudioDataSource(
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
) : BaseDataSource(true) {
    private var stream: AudioStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        check(stream == null)
        require(dataSpec.uri.scheme == "spofify" && dataSpec.uri.authority == "audio" && dataSpec.uri.pathSegments.size == 1)
        require(dataSpec.uri.query == null && dataSpec.uri.fragment == null)
        transferInitializing(dataSpec)
        val id = requireNotNull(dataSpec.uri.lastPathSegment)
        val length = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        val opened = runBlocking(Dispatchers.IO) { sessions.audio(identity, id, dataSpec.position, length) }
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
            if (started) {
                started = false
                transferEnded()
            }
        }
    }
}
