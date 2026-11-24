package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.FeatureAuthority
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.playback.PlaybackConnection
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.ByteString
import okio.utf8Size

/** Owns long-lived resources; retiring the originating authenticated API closes every resource. */
internal class OriginMediaTransport(
    private val origin: ServerOrigin,
    client: OkHttpClient,
    private val cookieHeader: (String) -> String,
    private val renewedCookie: (Response) -> String?,
) {
    private val socketClient = client.newBuilder().retryOnConnectionFailure(false).build()
    // Media3 owns the bounded retry budget; don't replay a failed audio GET underneath it.
    private val fillClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    private val audioClient = fillClient.newBuilder().retryOnConnectionFailure(false).build()
    // One owner from pending headers through transfer and final close. Before transfer
    // Call.cancel() suffices; after transfer the entry can also finish the response body.
    private val audioCalls = ConcurrentHashMap<Call, () -> Unit>()
    private val sockets = ConcurrentHashMap.newKeySet<SessionSocket>()

    fun cancel() {
        sockets.forEach { it.close() }
        audioCalls.keys.forEach { it.cancel() }
    }

    fun closeRetiredStreams() = audioCalls.values.forEach { close -> close() }

    suspend fun socket(
        cookie: String, hello: String, authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit,
    ): PlaybackConnection {
        require(hello.utf8Size() <= SessionSocket.MAX_HELLO_BYTES)
        val request = request(listOf("api", "me", "player", "socket"), cookie).build()
        return upgrade(request, hello, authority, onHeaders)
    }

    private suspend fun upgrade(
        request: Request, hello: String, authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit,
    ): PlaybackConnection = suspendCancellableCoroutine { continuation ->
        val pendingHello = AtomicReference<String?>(hello)
        val connection = SessionSocket(authority) { sockets.remove(it) }
        sockets.add(connection)
        continuation.invokeOnCancellation { pendingHello.set(null); connection.close() }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connection.attach(webSocket)
                val firstFrame = pendingHello.getAndSet(null)
                if (firstFrame == null || !continuation.isActive || !authority.isCurrent) {
                    connection.close()
                    if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                    return
                }
                connection.opened()
                if (!connection.sendHello(firstFrame)) {
                    connection.close()
                    if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                    return
                }
                continuation.resume(connection, onCancellation = { _, value, _ -> value.close() })
            }

            override fun onMessage(webSocket: WebSocket, text: String) = connection.receive(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = connection.close()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = connection.peerClosed(code, reason)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = connection.peerClosed(code, reason)
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                pendingHello.set(null)
                connection.close()
                response?.let {
                    runCatching { onHeaders(it.code, null) }
                    it.close()
                }
                if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
            }
        }
        if (!authority.runIfCurrent {
                if (!continuation.isActive) false
                else {
                    connection.attach(socketClient.newWebSocket(request, listener))
                    true
                }
            }) {
            connection.close()
            if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
        }
    }

    suspend fun audio(
        trackId: String, position: Long, length: Long?, cookie: String,
        authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit,
        media: dev.spofify.mobile.core.offline.OfflineMediaDescriptor? = null,
    ): AudioStream {
        require(trackId.isNotBlank() && trackId.toByteArray().size <= 512 && trackId.none(Char::isISOControl))
        require(position >= 0 && (length == null || length > 0))
        val end = length?.let { Math.addExact(position, it - 1) }
        val ranged = position > 0 || length != null
        if (media != null) {
            require(!ranged && dev.spofify.mobile.core.offline.OfflineParser.validSegment(trackId))
            require(media.byteLength > 0 && media.mediaVersion == "sha256:${media.sha256}" && SHA.matches(media.sha256))
        }
        val path = if (media == null) listOf("api", "library", "tracks", trackId, "audio")
            else listOf("api", "me", "offline", "v1", "tracks", trackId, "audio")
        val request = request(path, cookie, media?.mediaVersion)
            .header("Accept-Encoding", "identity").apply {
            if (ranged) header("Range", "bytes=$position-${end ?: ""}")
        }.build()
        val call = (if (media == null) audioClient else fillClient).newCall(request)
        audioCalls[call] = {}
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel(); audioCalls.remove(call) }
            val callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    audioCalls.remove(call)
                    if (!continuation.isActive) return
                    val interrupted = when (e) {
                        is OkHttpAuthApi.ReadStatus -> e.response.status.isTransientAudioStatus()
                        else -> e.isAudioInterruption()
                    }
                    val retryable = media == null && interrupted && !call.isCanceled() && authority.isCurrent
                    continuation.resumeWithException(if (retryable) TransientAudioLoadFailure(e)
                        else AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive || !authority.isCurrent) {
                        closeAudio(call, response)
                        if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                        return
                    }
                    try {
                        onHeaders(response.code, renewedCookie(response))
                        if (media == null && response.code.isTransientAudioStatus() && authority.isCurrent && !call.isCanceled()) {
                            throw TransientAudioLoadFailure()
                        }
                        require(response.code == if (ranged) 206 else 200)
                        val body = response.body
                        require(response.header("Content-Encoding").let { it == null || it.equals("identity", true) })
                        var bytes = body.contentLength()
                        if (media != null) {
                            require(!ranged && bytes == media.byteLength && response.headers.values("ETag") == listOf("\"${media.mediaVersion}\""))
                        }
                        var totalLength = bytes.takeIf { it >= 0 }
                        if (ranged) {
                            val range = RANGE.matchEntire(response.header("Content-Range").orEmpty())
                                ?: throw IOException("Invalid audio range")
                            val first = range.groupValues[1].toLong()
                            val last = range.groupValues[2].toLong()
                            require(first == position && last >= first && (end == null || last <= end))
                            totalLength = range.groupValues[3].takeUnless { it == "*" }?.toLong()
                            require(totalLength == null || totalLength > last)
                            val rangeLength = Math.addExact(last - first, 1)
                            require(bytes < 0 || bytes == rangeLength)
                            bytes = rangeLength
                        }
                        val etag = response.header("ETag")
                        val representation = AudioRepresentation(totalLength, etag,
                            response.header("Last-Modified"), body.contentType()?.toString(),
                            EVIDENCE_HEADERS.none { response.headers.values(it).size > 1 },
                            etag?.matches(STRONG_ETAG) == true || response.headers.getDate("Last-Modified") != null)
                        val stream = AudioStream(body.byteStream(), bytes, representation,
                            { !call.isCanceled() && authority.isCurrent }, recoverable = media == null,
                            cancelCall = call::cancel) { closeAudio(call, response) }
                        if (!authority.runIfCurrent { audioCalls.replace(call, stream::close) != null }) {
                            throw AuthFailure(AuthFailure.Kind.UNAVAILABLE)
                        }
                        continuation.resume(stream, onCancellation = { _, value, _ -> value.close() })
                    } catch (failure: Exception) {
                        closeAudio(call, response)
                        if (continuation.isActive) continuation.resumeWithException(
                            if (failure is TransientAudioLoadFailure) failure else AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                    }
                }
            }
            if (!authority.runIfCurrent {
                    if (!continuation.isActive) false else { call.enqueue(callback); true }
                }) {
                call.cancel()
                audioCalls.remove(call)
                if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
            }
        }
    }

    private fun request(segments: List<String>, cookie: String, version: String? = null): Request.Builder {
        val url = origin.url.newBuilder().apply {
            segments.forEach(::addPathSegment)
            if (version != null) addQueryParameter("version", version)
        }.build()
        check(origin.contains(url))
        return Request.Builder().url(url).get().header("Origin", origin.value)
            .header("Cookie", cookieHeader(cookie)).header("Cache-Control", "no-store")
    }

    private fun closeAudio(call: Call, response: Response) {
        call.cancel()
        response.close()
        audioCalls.remove(call)
    }

    private companion object {
        val SHA = Regex("[0-9a-f]{64}")
        val EVIDENCE_HEADERS = listOf("Content-Range", "Content-Length", "Content-Type", "Content-Encoding", "ETag", "Last-Modified")
        val STRONG_ETAG = Regex("\"[!#-~\\x80-\\xff]*\"")
        val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")
    }
}
