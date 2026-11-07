package dev.spofify.mobile.core

import dev.spofify.mobile.core.saved.SavedDownloadTransport
import dev.spofify.mobile.core.saved.SavedMediaDescriptor
import dev.spofify.mobile.core.saved.SavedOccurrence
import dev.spofify.mobile.core.saved.SavedScope
import dev.spofify.mobile.core.saved.SavedStorageFailure
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind
import dev.spofify.mobile.core.saved.SavedTransferResponse
import dev.spofify.mobile.core.saved.SavedTransportException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import kotlin.coroutines.resumeWithException

/** A terminal coherent membership snapshot, not media-readiness or playback authority. */
class OfflineManifest internal constructor(
    val target: SavedTarget,
    val title: String,
    val generation: String,
    val total: Long,
    occurrences: List<SavedOccurrence>,
) {
    val occurrences: List<SavedOccurrence> = occurrences.toList()
}

/** Issued by one live client/session. URLs are validated relative routes, never authorization. */
class OfflineMediaDescriptor internal constructor(
    val trackId: String,
    val mediaVersion: String,
    val sha256: String,
    val byteLength: Long,
    val format: String,
    val contentType: String,
    val downloadUrl: String,
    internal val issuer: SpofifyClient,
    internal val identity: ValidatedSessionIdentity,
) {
    val strongETag: String get() = "\"$mediaVersion\""
}

/** Delivered before any body bytes. A resumed request can legitimately return a fresh 200. */
data class OfflineMediaResponse(
    val status: Int,
    val etag: String,
    val contentRange: String?,
    val contentLength: Long?,
    val contentType: String,
    val startOffset: Long,
)

data class OfflineTransfer(val response: OfflineMediaResponse, val transferredBytes: Long)

/** Callbacks run on the original client's worker. Do not retain the reused byte buffer.
 * begin must truncate/restart on startOffset=0, including a 200 response to a Range request.
 * The sink owns partial-file lifetime, complete SHA-256 verification and atomic promotion;
 * successful transport is NOT proof of a verified local file. */
interface OfflineMediaSink {
    fun begin(response: OfflineMediaResponse)
    fun write(bytes: ByteArray, offset: Int, length: Int)
}

/** Explicit download operations only: never registers/selects output or records listening history. */
class OfflineCollectionsApi(private val client: SpofifyClient) {
    /** No partial publication or automatic restart on stale cursors. The caller explicitly retries
     * page one after failure, preserving its previous installed playable generation. */
    suspend fun manifest(target: SavedTarget, limit: Int = 100): OfflineManifest {
        require(limit in 1..100) { "Invalid offline page limit" }
        offlineId(target.id)
        val resource = when (target.kind) {
            SavedTargetKind.MANUAL_PLAYLIST, SavedTargetKind.DERIVED_PLAYLIST -> {
                require(target.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid offline playlist identifier" }
                "playlists"
            }
            SavedTargetKind.ALBUM -> "albums"
            SavedTargetKind.ARTIST -> "artists"
        }
        val identity = liveIdentity()
        val all = ArrayList<SavedOccurrence>()
        val occurrences = hashSetOf<String>()
        val cursors = hashSetOf<String>()
        var cursor: String? = null
        var budget = OFFLINE_MANIFEST_BYTES
        var first: OfflinePage? = null
        var recorded = 0L
        do {
            val query = linkedMapOf("limit" to limit.toString())
            cursor?.let { query["cursor"] = it }
            val (root, used) = jsonGet(offlinePath + listOf(resource, target.id, "manifest"), identity, budget, query)
            budget -= used
            val page = incompatibleOfflineBoundary { offlinePage(root, target, limit, all.size.toLong()) }
            val prior = first
            if (prior == null) first = page else if (!page.sameGeneration(prior)) throw incompatibleOffline()
            for (item in page.items) {
                if (!occurrences.add(item.occurrenceId)) throw incompatibleOffline()
                if (item.hasAudio) recorded++
                all.add(item)
            }
            if (all.size.toLong() > page.total || recorded > page.recorded) throw incompatibleOffline()
            cursor = page.next
            if (cursor == null) {
                if (all.size.toLong() != page.total || recorded != page.recorded) throw incompatibleOffline()
            } else if (page.items.isEmpty() || all.size.toLong() >= page.total || !cursors.add(cursor)) {
                throw incompatibleOffline()
            }
        } while (cursor != null)
        requireIdentity(identity)
        currentCoroutineContext().ensureActive()
        val summary = checkNotNull(first)
        return OfflineManifest(target, summary.title, summary.fence, summary.total, all)
    }

    suspend fun currentMedia(trackId: String): OfflineMediaDescriptor {
        offlineId(trackId)
        val identity = liveIdentity()
        val root = jsonGet(offlinePath + listOf("tracks", trackId, "media"), identity, OFFLINE_DESCRIPTOR_BYTES).first
        return decodeMedia(trackId, identity, root)
    }

    private fun decodeMedia(trackId: String, identity: ValidatedSessionIdentity, root: JsonObject): OfflineMediaDescriptor {
        return incompatibleOfflineBoundary {
            require(root.number("version") == 1L && root.text("trackId") == trackId)
            val sha = root.text("sha256")
            val version = root.text("mediaVersion")
            require(sha.matches(Regex("[0-9a-f]{64}")) && version == "sha256:$sha")
            val length = root.number("byteLength")
            require(length > 0)
            val format = root.text("format")
            val contentType = root.text("contentType")
            require(offlineContentTypes[format] == contentType)
            val relative = root.text("downloadUrl")
            mediaUrl(trackId, version, relative)
            requireIdentity(identity)
            OfflineMediaDescriptor(trackId, version, sha, length, format, contentType, relative, client, identity)
        }
    }

    /** One attempt, no redirects or retries. Caller admission/storage budgets remain authoritative.
     * Cancellation remains attached through the final byte and sink callback. No escaping body. */
    suspend fun download(media: OfflineMediaDescriptor, offset: Long = 0, sink: OfflineMediaSink): OfflineTransfer {
        require(media.issuer === client) { "The media descriptor belongs to another client" }
        require(offset >= 0 && offset < media.byteLength) { "Invalid offline media offset" }
        requireIdentity(media.identity)
        val context = currentCoroutineContext()
        context.ensureActive()
        val url = mediaUrl(media.trackId, media.mediaVersion, media.downloadUrl)
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
            if (offset > 0) {
                header("Range", "bytes=$offset-")
                header("If-Range", media.strongETag)
            }
        }.build()
        val result = client.offlineOwnedResponse(request, errorFactory = ::offlineError) { response ->
            requireIdentity(media.identity)
            context.ensureActive()
            val headers = incompatibleOfflineBoundary { mediaHeaders(response, media, offset) }
            sink.begin(headers)
            requireIdentity(media.identity)
            context.ensureActive()
            val body = response.body ?: throw incompatibleOffline()
            val source = body.source()
            val bytes = ByteArray(32 * 1024)
            val expected = media.byteLength - headers.startOffset
            var received = 0L
            while (received < expected) {
                context.ensureActive()
                requireIdentity(media.identity)
                val count = source.read(bytes, 0, minOf(bytes.size.toLong(), expected - received).toInt())
                if (count < 0) throw incompatibleOffline()
                if (count == 0) throw incompatibleOffline()
                context.ensureActive()
                requireIdentity(media.identity)
                sink.write(bytes, 0, count)
                received += count
            }
            // Probe without allocating expected+1 (which could overflow Long). Never deliver extras.
            context.ensureActive()
            requireIdentity(media.identity)
            if (source.read(bytes, 0, 1) != -1) throw incompatibleOffline()
            context.ensureActive()
            requireIdentity(media.identity)
            OfflineTransfer(headers, received)
        }
        context.ensureActive()
        requireIdentity(media.identity)
        return result
    }

    /** Callback-scoped store adapter. The original client owns request cancellation and closes
     * the body only after consume returns. A stream retained by consume is unusable afterwards. */
    fun savedTransport(scope: SavedScope, isCurrent: () -> Boolean): SavedDownloadTransport {
        val identity = liveIdentity()
        require(scope.origin == identity.origin && scope.accountId == identity.accountId) { "Saved account scope mismatch" }
        val prepared = ConcurrentHashMap<SavedMediaDescriptor, OfflineMediaDescriptor>()
        fun checkScope(candidate: SavedScope) {
            if (candidate != scope || !isCurrent()) throw IOException("Saved download is no longer current")
            requireIdentity(identity)
        }
        return object : SavedDownloadTransport {
            override suspend fun prepare(scope: SavedScope, trackId: String): SavedMediaDescriptor {
                checkScope(scope)
                offlineId(trackId)
                var retryAfter: Long? = null
                val media = try {
                    val root = jsonGet(offlinePath + listOf("tracks", trackId, "media"), identity,
                        OFFLINE_DESCRIPTOR_BYTES, errorFactory = { response ->
                            retryAfter = offlineRetryAfter(response)
                            offlineError(response)
                        }).first
                    decodeMedia(trackId, identity, root)
                } catch (error: ApiException) { throw SavedTransportException(error.status, retryAfter) }
                checkScope(scope)
                val saved = SavedMediaDescriptor(media.trackId, media.mediaVersion, media.sha256, media.byteLength, media.strongETag)
                prepared.entries.removeIf { it.key.trackId == trackId }
                prepared[saved] = media
                return saved
            }

            override suspend fun transfer(
                scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long,
                consume: (SavedTransferResponse) -> Unit,
            ) {
                checkScope(scope)
                val media = prepared[descriptor] ?: run {
                    // Restarted partials need current representation proof before reuse.
                    if (prepare(scope, descriptor.trackId) != descriptor) throw SavedTransportException(409)
                    prepared[descriptor] ?: throw IOException("Saved descriptor is unavailable")
                }
                require(offset >= 0 && offset < media.byteLength) { "Invalid offline media offset" }
                val context = currentCoroutineContext()
                val request = Request.Builder().url(mediaUrl(media.trackId, media.mediaVersion, media.downloadUrl))
                    .header("Accept-Encoding", "identity").apply {
                        if (offset > 0) { header("Range", "bytes=$offset-"); header("If-Range", media.strongETag) }
                    }.build()
                var retryAfter: Long? = null
                try {
                    client.offlineOwnedResponse(request, errorFactory = { response ->
                        retryAfter = offlineRetryAfter(response)
                        offlineError(response)
                    }) { response ->
                        checkScope(scope)
                        context.ensureActive()
                        val headers = incompatibleOfflineBoundary { mediaHeaders(response, media, offset) }
                        val raw = response.body?.byteStream() ?: throw incompatibleOffline()
                        val expected = media.byteLength - headers.startOffset
                        var count = 0L
                        var ended = false
                        val active = AtomicBoolean(true)
                        val stream = object : InputStream() {
                            private fun check() {
                                if (!active.get()) throw IOException("Saved media response is closed")
                                context.ensureActive()
                                checkScope(scope)
                            }
                            override fun read(): Int {
                                val b = ByteArray(1)
                                return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 255
                            }
                            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                                require(off >= 0 && len >= 0 && off <= bytes.size - len)
                                check()
                                if (len == 0) return 0
                                if (count == expected) {
                                    if (!ended && raw.read() != -1) throw incompatibleOffline()
                                    ended = true
                                    check()
                                    return -1
                                }
                                val n = raw.read(bytes, off, minOf(len.toLong(), expected - count, 32L * 1024).toInt())
                                if (n <= 0) throw incompatibleOffline()
                                check()
                                count += n
                                return n
                            }
                            override fun close() { active.set(false); raw.close() }
                        }
                        try {
                            consume(SavedTransferResponse(headers.status, stream, headers.contentLength,
                                headers.contentRange, headers.etag))
                            context.ensureActive()
                            checkScope(scope)
                            // A successful verifier must consume the exact full response and EOF.
                            require(count == expected && ended) { "Saved verifier did not consume complete media" }
                        } finally {
                            active.set(false)
                        }
                    }
                } catch (error: ApiException) { throw SavedTransportException(error.status, retryAfter) }
                context.ensureActive()
                checkScope(scope)
            }
        }
    }

    private fun liveIdentity(): ValidatedSessionIdentity = client.validatedSessionIdentity()
        ?: throw IOException("Sign in before downloading saved music")

    private fun requireIdentity(expected: ValidatedSessionIdentity) {
        if (client.validatedSessionIdentity() != expected) throw IOException("The account session changed")
    }

    private fun mediaUrl(trackId: String, version: String, relative: String): HttpUrl = incompatibleOfflineBoundary {
        require(relative.startsWith("/api/me/offline/v1/tracks/") && !relative.startsWith("//") &&
            relative.none { it.isISOControl() || it.isWhitespace() || it == '\\' })
        val uri = try { URI(relative) } catch (_: Exception) { throw IllegalArgumentException() }
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null)
        val url = client.server.url().resolve(relative) ?: throw IllegalArgumentException()
        require(client.server.contains(url) && url.fragment == null && uri.rawPath == url.encodedPath &&
            url.pathSegments == offlinePath + listOf("tracks", trackId, "audio") &&
            url.querySize == 1 && url.queryParameterNames == setOf("version") && url.queryParameter("version") == version)
        url
    }

    private suspend fun jsonGet(
        path: List<String>, identity: ValidatedSessionIdentity, maxBytes: Long,
        query: Map<String, String> = emptyMap(),
        errorFactory: (Response) -> ApiException = ::offlineError,
    ): Pair<JsonObject, Long> {
        requireIdentity(identity)
        val context = currentCoroutineContext()
        val url = client.server.url(*path.toTypedArray()).newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val result = client.response(Request.Builder().url(url).header("Accept", "application/json").build(), errorFactory) { response ->
            context.ensureActive()
            requireIdentity(identity)
            incompatibleOfflineBoundary {
                require(response.code == 200)
                val subtype = response.body?.contentType()?.subtype
                require(subtype == "json" || subtype?.endsWith("+json") == true)
                val bytes = offlineReadBounded(response, maxBytes) ?: throw IllegalArgumentException()
                val root = OfflineRawJson.parse(bytes.first)
                requireIdentity(identity)
                context.ensureActive()
                root to bytes.second
            }
        }
        requireIdentity(identity)
        context.ensureActive()
        return result
    }
}

/** Media consumers can own blocking disk IO that Call.cancel() cannot interrupt. Unlike the
 * ordinary API helper, cancellation must JOIN that consumer before releasing its caller's file
 * pins, space claims and transfer admission. No timeout may pretend a blocked syscall stopped.
 * This is deliberately opt-in: ordinary client/auth/playback response semantics are unchanged. */
private suspend fun <T> SpofifyClient.offlineOwnedResponse(
    request: Request,
    errorFactory: (Response) -> ApiException,
    consume: (Response) -> T,
): T {
    val call = httpClient.newCall(request)
    // No parent Job: cancellation requests stop network IO, but cannot cancel our join witness.
    val finished = CompletableDeferred<Unit>()
    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try {
                            if (!continuation.isCancelled) continuation.resumeWithException(offlineNetworkFailure())
                        } finally { finished.complete(Unit) }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                continuation.context.ensureActive()
                                if (!it.isSuccessful) throw errorFactory(it)
                                consume(it)
                            }
                            continuation.resume(result) { _, _, _ -> }
                        } catch (error: Throwable) {
                            if (!continuation.isCancelled) continuation.resumeWithException(when (error) {
                                // Only this local, statically typed storage failure survives IO
                                // sanitization. Strip its private filesystem/backend cause.
                                is SavedStorageFailure -> SavedStorageFailure()
                                is ApiException -> error
                                is IOException -> offlineNetworkFailure()
                                else -> error
                            })
                        } finally {
                            // response.use has closed the network body AND consume's disk
                            // write/hash/sync/close stack has actually unwound before publication.
                            finished.complete(Unit)
                        }
                    }
                })
            } catch (error: Throwable) {
                finished.complete(Unit)
                if (!continuation.isCancelled) continuation.resumeWithException(
                    if (error is IOException) offlineNetworkFailure() else error,
                )
            }
        }
    } finally {
        withContext(NonCancellable) { finished.await() }
    }
}

private fun offlineNetworkFailure() = IOException("Unable to reach the server. Check your connection and server address.")

private data class OfflinePage(
    val title: String, val description: String?, val revision: Long?, val fence: String,
    val total: Long, val recorded: Long, val items: List<SavedOccurrence>, val next: String?,
) {
    fun sameGeneration(other: OfflinePage): Boolean = title == other.title && description == other.description &&
        revision == other.revision && fence == other.fence && total == other.total && recorded == other.recorded
}

private fun offlinePage(root: JsonObject, target: SavedTarget, limit: Int, start: Long): OfflinePage {
    require(root.number("version") == 1L && root.number("limit") == limit.toLong())
    val playlist = target.kind == SavedTargetKind.MANUAL_PLAYLIST || target.kind == SavedTargetKind.DERIVED_PLAYLIST
    require(if (playlist) root["collection"] == null else root["playlist"] == null)
    val summary = root[if (playlist) "playlist" else "collection"] as? JsonObject ?: throw IllegalArgumentException()
    val kind = when (target.kind) {
        SavedTargetKind.MANUAL_PLAYLIST -> "manual"
        SavedTargetKind.DERIVED_PLAYLIST -> "derived"
        SavedTargetKind.ALBUM -> "album"
        SavedTargetKind.ARTIST -> "artist"
    }
    require(summary.text("kind") == kind && summary.text("id") == target.id)
    val title = summary.text("name")
    val revision = if (playlist) summary.number("revision").also { require(it > 0) } else null
    val description = if (playlist) summary.text("description") else null
    val fence = root.text("fence")
    require(fence.isNotEmpty() && fence.length <= 4096 && fence.none { it.isISOControl() })
    val total = root.number("total")
    val recorded = root.number("recordedAudioTotal")
    require(total <= Int.MAX_VALUE && recorded <= total && start <= total)
    val rows = root["items"] as? JsonArray ?: throw IllegalArgumentException()
    require(rows.size <= limit && rows.size.toLong() <= total - start)
    val items = rows.mapIndexed { index, element ->
        val row = element as? JsonObject ?: throw IllegalArgumentException()
        val occurrence = offlineId(row.text("occurrenceId"))
        require(row.number("position") == start + index)
        val track = row["track"] as? JsonObject ?: throw IllegalArgumentException()
        require(track.text("kind") == "track")
        val trackId = offlineId(track.text("id"))
        val artists = track["artists"]?.let { value ->
            require(value is JsonArray)
            value.map {
                require(it is JsonObject)
                offlineId(it.text("id"))
                it.text("name")
            }.joinToString(", ")
        } ?: ""
        fun optionalText(key: String): String? = if (track[key] == null) null else track.text(key)
        val duration = if (track["durationMs"] == null) 0 else track.number("durationMs")
        val audio = (track["hasAudio"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException()
        // Positive projection only: even unknown fields cannot persist paths, URLs or authority.
        SavedOccurrence(occurrence, trackId, track.text("name"), artists,
            optionalText("albumId"), optionalText("albumName"), duration, audio)
    }
    require(root.containsKey("nextCursor"))
    val next = when (val value = root["nextCursor"]) {
        JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content?.also {
            require(it.isNotEmpty() && it.length <= 4096 && it.none { c -> c.isISOControl() })
        } ?: throw IllegalArgumentException()
        else -> throw IllegalArgumentException()
    }
    return OfflinePage(title, description, revision, fence, total, recorded, items, next)
}

private val offlinePath = listOf("api", "me", "offline", "v1")
private const val OFFLINE_DESCRIPTOR_BYTES = 64L * 1024
private const val OFFLINE_MANIFEST_BYTES = 16L * 1024 * 1024
private val offlineContentTypes = mapOf(
    "mp3" to "audio/mpeg", "flac" to "audio/flac", "m4a" to "audio/mp4", "aac" to "audio/aac",
    "ogg" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav", "aif" to "audio/aiff",
    "aiff" to "audio/aiff", "webm" to "audio/webm",
)

private fun mediaHeaders(response: Response, media: OfflineMediaDescriptor, offset: Long): OfflineMediaResponse {
    fun header(name: String): String? {
        val values = response.headers.values(name)
        require(values.size <= 1)
        return values.singleOrNull()
    }
    require(response.code == 200 || response.code == 206)
    val etag = header("ETag")
    require(etag == media.strongETag)
    val type = header("Content-Type")
    require(type == media.contentType)
    require(header("Content-Encoding").let { it == null || it == "identity" })
    val range = header("Content-Range")
    val start = if (response.code == 206) {
        require(offset > 0 && range == "bytes $offset-${media.byteLength - 1}/${media.byteLength}")
        offset
    } else {
        require(range == null)
        0L
    }
    val length = header("Content-Length")?.let {
        require(it.matches(Regex("0|[1-9][0-9]*")))
        it.toLongOrNull() ?: throw IllegalArgumentException()
    }
    val expected = media.byteLength - start
    require(length == null || length == expected)
    val bodyLength = response.body?.contentLength() ?: -1L
    require(bodyLength < 0 || bodyLength == expected)
    return OfflineMediaResponse(response.code, requireNotNull(etag), range, length, requireNotNull(type), start)
}

private fun offlineId(id: String): String {
    require(id.isNotEmpty() && id != "." && id != ".." && id == id.trim() &&
        id.toByteArray(Charsets.UTF_8).size <= 512 && id.none { it.isISOControl() }) { "Invalid offline identifier" }
    return id
}

private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content ?: throw IllegalArgumentException()
private fun JsonObject.number(key: String): Long {
    val p = get(key) as? JsonPrimitive ?: throw IllegalArgumentException()
    require(!p.isString && p.content.matches(Regex("0|[1-9][0-9]*")))
    return p.content.toLongOrNull() ?: throw IllegalArgumentException()
}
private fun incompatibleOffline() = ApiException(200, "The server returned an incompatible offline response")
private inline fun <T> incompatibleOfflineBoundary(block: () -> T): T = try { block() }
catch (_: IllegalArgumentException) { throw incompatibleOffline() }

/** Read at most limit+1, including chunked/dishonestly framed bodies. */
private fun offlineReadBounded(response: Response, limit: Long): Pair<String, Long>? {
    val body = response.body ?: return null
    if (limit < 0 || body.contentLength() > limit) return null
    val buffer = Buffer()
    while (buffer.size <= limit) {
        if (body.source().read(buffer, minOf(8192L, limit + 1 - buffer.size)) == -1L)
            return buffer.size.let { size -> buffer.readUtf8() to size }
    }
    return null
}

private fun offlineRetryAfter(response: Response): Long? {
    if (response.code != 429 && response.code != 503) return null
    val value = response.headers.values("Retry-After").singleOrNull() ?: return null
    val seconds = value.takeIf { it.matches(Regex("[0-9]{1,18}")) }?.toLongOrNull() ?: return null
    return minOf(seconds, 3600L) * 1000L
}

private fun offlineError(response: Response): ApiException {
    // Never retain arbitrary error JSON/messages; errors have a tiny independent byte budget.
    val code = try {
        val subtype = response.body?.contentType()?.subtype
        if (subtype != "json" && subtype?.endsWith("+json") != true) null else {
            val text = offlineReadBounded(response, 8192)?.first
            val root = text?.let(OfflineRawJson::parse)
            val value = (root?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive
            value?.takeIf { it.isString }?.content?.takeIf { it in offlineErrorCodes[response.code].orEmpty() }
        }
    } catch (_: Exception) { null }
    val message = when (response.code) {
        401 -> "Your session has expired. Sign in again."
        403 -> "The server refused this request. Check your account permissions."
        404 -> "This item is no longer available"
        409 -> "The item changed on the server. Refresh and try again."
        429 -> "Too many requests. Please try again later."
        in 300..399 -> "The server redirected the request. Enter its direct origin instead."
        in 500..599 -> "The server is temporarily unavailable"
        else -> "The server could not complete the request (HTTP ${response.code})"
    }
    return ApiException(response.code, message, code)
}
private val offlineErrorCodes = mapOf(
    400 to setOf("INVALID_REQUEST", "INVALID_CURSOR"),
    404 to setOf("PLAYLIST_NOT_FOUND", "MEDIA_UNAVAILABLE", "REFERENCE_NOT_FOUND"),
    409 to setOf("STALE_EVALUATION", "SOURCE_UNAVAILABLE", "MEDIA_CHANGED"),
    429 to setOf("EVALUATION_BUSY", "MEDIA_BUSY"),
    503 to setOf("EVALUATION_TIMEOUT", "MEDIA_TIMEOUT"),
    500 to setOf("EVALUATION_FAILED"),
)

/** Separate from the player schema. Reject duplicate/escaped duplicate fields before JsonObject
 * materialization, strict JSON syntax and excessive nesting at this untrusted wire boundary. */
private class OfflineRawJson(private val raw: String) {
    private var at = 0
    private fun ws() { while (at < raw.length && raw[at] in " \t\r\n") at++ }
    private fun take(c: Char) { ws(); require(at < raw.length && raw[at++] == c) }
    private fun string(): String {
        ws()
        val start = at
        require(at < raw.length && raw[at++] == '"')
        while (at < raw.length) {
            when (val c = raw[at++]) {
                '"' -> return (ApiJson.parseToJsonElement(raw.substring(start, at)) as JsonPrimitive).content
                '\\' -> { require(at < raw.length); at++ }
                else -> require(c.code >= 32)
            }
        }
        throw IllegalArgumentException()
    }
    private fun value(depth: Int) {
        require(depth <= 16)
        ws(); require(at < raw.length)
        when (raw[at]) {
            '{' -> {
                at++; ws()
                if (at < raw.length && raw[at] == '}') { at++; return }
                val keys = hashSetOf<String>()
                while (true) {
                    require(keys.add(string().lowercase(java.util.Locale.ROOT)))
                    take(':'); value(depth + 1); ws(); require(at < raw.length)
                    if (raw[at++] == '}') break
                    require(raw[at - 1] == ',')
                }
            }
            '[' -> {
                at++; ws()
                if (at < raw.length && raw[at] == ']') { at++; return }
                while (true) {
                    value(depth + 1); ws(); require(at < raw.length)
                    if (raw[at++] == ']') break
                    require(raw[at - 1] == ',')
                }
            }
            '"' -> string()
            else -> {
                val start = at
                while (at < raw.length && raw[at] !in ",]} \t\r\n") at++
                val token = raw.substring(start, at)
                require(token in setOf("true", "false", "null") || token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
            }
        }
    }
    companion object {
        fun parse(raw: String): JsonObject {
            val parser = OfflineRawJson(raw)
            parser.value(0); parser.ws(); require(parser.at == raw.length)
            return ApiJson.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException()
        }
    }
}
