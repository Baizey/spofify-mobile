package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.FeatureAuthority
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.playback.PlaybackConnection
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.ByteString

/** Owns long-lived resources; retiring the originating authenticated API closes every resource. */
internal class OriginMediaTransport(
    private val origin: ServerOrigin,
    client: OkHttpClient,
    private val cookieHeader: (String) -> String,
    private val renewedCookie: (Response) -> String?,
) {
    private val socketClient = client
    private val audioClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    private val audioCalls = ConcurrentHashMap.newKeySet<Call>()
    private val sockets = ConcurrentHashMap.newKeySet<SessionSocket>()

    fun cancel() {
        sockets.toList().forEach { it.close() }
        audioCalls.toList().forEach { it.cancel() }
    }

    suspend fun socket(
        cookie: String, hello: String, authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit,
    ): PlaybackConnection = suspendCancellableCoroutine { continuation ->
        var pendingHello: String? = hello
        val connection = SessionSocket(authority) { sockets.remove(it) }
        sockets.add(connection)
        continuation.invokeOnCancellation { pendingHello = null; connection.close() }
        val request = request(listOf("api", "me", "player", "socket"), cookie)
            .header("Sec-WebSocket-Protocol", PROTOCOL).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connection.attach(webSocket)
                if (!continuation.isActive || !authority.isCurrent ||
                    response.headers.values("Sec-WebSocket-Protocol") != listOf(PROTOCOL)) {
                    connection.close()
                    if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                    return
                }
                connection.opened()
                val firstFrame = pendingHello
                pendingHello = null
                if (firstFrame == null || !connection.send(firstFrame)) {
                    connection.close()
                    if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                    return
                }
                continuation.resume(connection, onCancellation = { _, value, _ -> value.close() })
            }

            override fun onMessage(webSocket: WebSocket, text: String) = connection.receive(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = connection.close()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = connection.close()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = connection.close()
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                pendingHello = null
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
        val call = audioClient.newCall(request)
        audioCalls.add(call)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel(); audioCalls.remove(call) }
            val callback = object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    audioCalls.remove(call)
                    if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive || !authority.isCurrent) {
                        closeAudio(call, response)
                        if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
                        return
                    }
                    try {
                        onHeaders(response.code, renewedCookie(response))
                        require(response.code == if (ranged) 206 else 200)
                        val body = response.body
                        require(response.header("Content-Encoding").let { it == null || it.equals("identity", true) })
                        var bytes = body.contentLength()
                        if (media != null) {
                            require(!ranged && bytes == media.byteLength && response.headers.values("ETag") == listOf("\"${media.mediaVersion}\""))
                        }
                        if (ranged) {
                            val range = RANGE.matchEntire(response.header("Content-Range").orEmpty())
                                ?: throw IOException("Invalid audio range")
                            val first = range.groupValues[1].toLong()
                            val last = range.groupValues[2].toLong()
                            require(first == position && last >= first && (end == null || last <= end))
                            val total = range.groupValues[3]
                            require(total == "*" || total.toLong() > last)
                            val rangeLength = Math.addExact(last - first, 1)
                            require(bytes < 0 || bytes == rangeLength)
                            bytes = rangeLength
                        }
                        val stream = AudioStream(body.byteStream(), bytes, body.contentType()?.toString(),
                            { authority.isCurrent }, { closeAudio(call, response) })
                        continuation.resume(stream, onCancellation = { _, value, _ -> value.close() })
                    } catch (_: Exception) {
                        closeAudio(call, response)
                        if (continuation.isActive) continuation.resumeWithException(AuthFailure(AuthFailure.Kind.UNAVAILABLE))
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
        const val PROTOCOL = "spofify.library-playback.v2"
        val SHA = Regex("[0-9a-f]{64}")
        val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")
    }
}
