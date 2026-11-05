package dev.spofify.mobile.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okhttp3.Response
import kotlin.coroutines.resumeWithException

/** One origin and one account. The same client can safely be shared with images and Media3. */
class SpofifyClient(val server: ServerAddress, private val store: SessionStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var cookie: Cookie? = null
    private var session: Session? = null
    private var generation: Long = 0
    // One no-auth, already connected direct address; never resolve or persist route metadata.
    private var playbackLeaveAddress: InetAddress? = null
    private val mutablePersonalRevision = MutableStateFlow(PersonalRevision())
    val personalRevision: StateFlow<PersonalRevision> = mutablePersonalRevision.asStateFlow()

    /**
     * Invoked on the request's OkHttp worker under the auth-generation lock. Must not block or
     * wait for another thread accessing this client; UI consumers should dispatch to their scope.
     */
    @Volatile var onUnauthorized: (() -> Unit)? = null

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .eventListenerFactory {
            val requestGeneration = synchronized(lock) { generation }
            object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    val route = connection.route()
                    recordPlaybackLeaveRoute(requestGeneration, call.request().url, route.address.url, route.proxy.type(), route.socketAddress)
                }
            }
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(object : CookieJar {
            // Only login may install a session, after the entire response has been validated.
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
                if (!server.contains(url)) emptyList()
                else cookie?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { listOf(it) } ?: emptyList()
            }
        })
        .addInterceptor { chain ->
            val request = chain.request()
            if (!server.contains(request.url)) throw IOException("Off-origin requests are not allowed")
            val login = request.method == "POST" && request.url.encodedPath == "/api/auth/login"
            val requestGeneration = synchronized(lock) { generation }
            val builder = request.newBuilder().header("Origin", server.origin).removeHeader("Cookie")
                .removeHeader("X-CSRF-Token").removeHeader("X-Spofify-Login")
            if (request.method !in listOf("GET", "HEAD", "OPTIONS")) {
                builder.header("Content-Type", "application/json")
                // OkHttp can otherwise replay a body after certain 503/421 responses even
                // with retryOnConnectionFailure(false). Every mutation is explicitly one-shot.
                builder.method(request.method, OneShotBody(request.body ?: "".toRequestBody(null)))
                if (login) builder.header("X-Spofify-Login", "1")
                else currentSession()?.let { builder.header("X-CSRF-Token", it.csrfToken) }
            }
            val response = chain.proceed(builder.build())
            try {
                if (response.code == 401 && !login) invalidate(requestGeneration, notifyUnauthorized = true)
                response
            } catch (error: Exception) {
                // The response is not handed to a caller when invalidation/storage fails.
                response.close()
                throw error
            }
        }
        .build()

    fun currentSession(): Session? = synchronized(lock) { session }

    /** Metadata-only seam shared by the production connection listener and core route tests.
     * Neither stale generations nor another origin may supply a cleanup destination. A proxied
     * or unresolved same-origin connection retires any previous direct candidate. */
    internal fun recordPlaybackLeaveRoute(
        expectedGeneration: Long, requestUrl: HttpUrl, routeUrl: HttpUrl,
        proxyType: Proxy.Type, socketAddress: InetSocketAddress,
    ) = synchronized(lock) {
        if (generation == expectedGeneration && server.contains(requestUrl) && server.contains(routeUrl)) {
            playbackLeaveAddress = socketAddress.address?.takeIf {
                proxyType == Proxy.Type.DIRECT && socketAddress.port == requestUrl.port && !socketAddress.isUnresolved
            }
        }
    }

    /** Call only after a confirmed personal mutation, never after optimistic UI changes or reads. */
    fun notifyPersonalChange(collection: PersonalCollection) = synchronized(lock) {
        notifyPersonalChange(collection, generation)
    }

    /** Capture before an external API mutation and pass back only after its confirmed success. */
    internal fun personalMutationGeneration(): Long = synchronized(lock) { generation }

    internal fun notifyPersonalChange(collection: PersonalCollection, expectedGeneration: Long) = synchronized(lock) {
        if (generation == expectedGeneration && session != null && cookie?.let { it.expiresAt > System.currentTimeMillis() } == true) {
            val previous = mutablePersonalRevision.value
            mutablePersonalRevision.value = when (collection) {
                PersonalCollection.LIKES -> previous.copy(likes = previous.likes + 1)
                PersonalCollection.HISTORY -> previous.copy(history = previous.history + 1)
                PersonalCollection.PLAYLISTS -> previous.copy(playlists = previous.playlists + 1)
            }
        }
    }

    fun clearSession() { resetSession() }

    /** Only the fixed player leave may outlive clearSession/cancelAll. No client, store, session
     * object or mutable auth provider escapes. The clock seam is per capability, for core tests. */
    internal fun preparePlaybackLeave(deviceId: String, nanoTime: () -> Long = System::nanoTime): suspend () -> Unit {
        require(validPlaybackProtocolId(deviceId)) { "Invalid playback device identifier" }
        val capturedAt = nanoTime()
        val request = synchronized(lock) {
            val oldCookie = cookie
            val oldSession = session
            val oldAddress = playbackLeaveAddress
            if (oldCookie == null || oldSession == null || oldAddress == null || oldCookie.expiresAt <= System.currentTimeMillis()) null
            else {
                val body = buildJsonObject { put("action", "leave"); put("deviceId", deviceId) }.toString()
                PlaybackLeaveAuthority(
                    Request.Builder().url(server.url("api", "me", "player"))
                        .header("Origin", server.origin)
                        .header("Cookie", "${oldCookie.name}=${oldCookie.value}")
                        .header("X-CSRF-Token", oldSession.csrfToken)
                        .header("Accept", "application/json")
                        .post(OneShotBody(body.toRequestBody("application/json; charset=utf-8".toMediaType())))
                        .build(),
                    oldCookie.expiresAt, oldAddress,
                )
            }
        }
        return PreparedPlaybackLeave(request, capturedAt, nanoTime)::invoke
    }

    private fun resetSession(): Long = synchronized(lock) {
        cookie = null
        session = null
        playbackLeaveAddress = null
        generation++
        try { store.write(null) }
        catch (_: Exception) { throw IOException("Unable to clear the saved session. Sign in again.") }
        generation
    }

    /** Old requests must not clear or resurrect a newer account session. */
    private fun invalidate(expectedGeneration: Long, notifyUnauthorized: Boolean = false): Boolean = synchronized(lock) {
        if (generation != expectedGeneration) false
        else {
            val invalidatedGeneration = generation + 1
            try { resetSession() }
            finally {
                // Runtime auth is cleared before persistence. Even a failed disk clear must stop
                // buffered playback. Invoke under the generation lock so an old 401 cannot signal
                // after a newer login has installed its session on this same client.
                if (notifyUnauthorized && generation == invalidatedGeneration && session == null) onUnauthorized?.invoke()
            }
            true
        }
    }

    suspend fun login(username: String, password: String): Session {
        val loginGeneration = resetSession()
        val body = buildJsonObject { put("username", username); put("password", password) }.toString()
        return try {
            response(request(server.url("api", "auth", "login"), "POST", body)) { result ->
                val candidates = result.headers.values("Set-Cookie").mapNotNull { parseSessionCookie(server, it) }
                val summary = decode<Session>(result)
                if (candidates.size != 1) throw ApiException(result.code, "The server did not issue a valid session cookie")
                install(summary, candidates.single(), loginGeneration)
                summary
            }
        } catch (error: CancellationException) {
            invalidate(loginGeneration)
            throw error
        }
    }

    /** Revalidates persisted origin and cookie before contacting the server; no optimistic authentication. */
    suspend fun restore(): Session? {
        val (restoreGeneration, saved) = synchronized(lock) {
            generation++
            cookie = null
            session = null
            playbackLeaveAddress = null
            generation to store.read()
        }
        if (saved == null) return null
        val candidate = if (saved.server == server.origin) parseSessionCookie(server, saved.cookie) else null
        if (candidate == null) { invalidate(restoreGeneration); return null }
        synchronized(lock) {
            requireCurrentGeneration(restoreGeneration)
            cookie = candidate
        }
        return try {
            val summary = get<Session>(server.url("api", "auth", "session"))
            install(summary, candidate, restoreGeneration)
            summary
        } catch (error: ApiException) {
            // Rate limits and temporary server failures do not prove the saved session
            // invalid. Drop unvalidated runtime auth, but allow an explicit later retry.
            if (error.status == 429 || error.status in 500..599) forgetRestore(restoreGeneration)
            else invalidate(restoreGeneration)
            if (error.status == 401) null else throw error
        } catch (error: IOException) {
            forgetRestore(restoreGeneration)
            throw error
        } catch (error: CancellationException) {
            forgetRestore(restoreGeneration)
            throw error
        }
    }

    // A transport failure or cancellation must not leave an unvalidated cookie usable by media.
    // Keep the persisted copy for a later explicit restoration attempt.
    private fun forgetRestore(expectedGeneration: Long) = synchronized(lock) {
        if (generation == expectedGeneration) {
            cookie = null
            session = null
            playbackLeaveAddress = null
            generation++
        }
    }

    suspend fun logout() {
        val logoutGeneration = synchronized(lock) { generation }
        try { mutate(server.url("api", "auth", "logout"), "POST", "{}") }
        finally { invalidate(logoutGeneration) }
    }

    suspend fun library(kind: String = "albums", query: String = "", offset: Int = 0, artistId: String? = null, albumId: String? = null): LibraryPage {
        require(offset in 0..1_000_000) { "Invalid page offset" }
        val child = albumId != null || artistId != null
        val url = server.url("api", "library").newBuilder()
            .addQueryParameter("kind", if (albumId != null) "tracks" else if (artistId != null) "albums" else kind)
            .addQueryParameter("scope", "all")
            .addQueryParameter("availability", if (child) "all" else "in-library")
            .addQueryParameter("limit", "40").addQueryParameter("offset", offset.toString())
        if (!child && query.isNotEmpty()) url.addQueryParameter("q", query)
        if (albumId != null) url.addQueryParameter("albumId", validId(albumId))
        else if (artistId != null) url.addQueryParameter("artistId", validId(artistId))
        return get(url.build())
    }

    suspend fun playback(item: LibraryItem): PlaybackQueue = when (item.kind) {
        "artist", "album" -> get(server.url("api", "library", plural(item.kind), item.id, "playback"))
        "track" -> if (!item.albumId.isNullOrEmpty()) get(server.url("api", "library", "albums", item.albumId, "playback"))
            else if (item.playable) PlaybackQueue(listOf(item), total = 1) else PlaybackQueue(emptyList())
        else -> throw IllegalArgumentException("Unsupported library kind")
    }

    suspend fun likes(offset: Int = 0): LibraryPage = get<TrackPage>(pageUrl(server.url("api", "me", "likes"), offset)).flatten()
    suspend fun likedPlayback(): PlaybackQueue = get(server.url("api", "me", "likes", "playback"))
    suspend fun playlists(offset: Int = 0): PlaylistPage = get(pageUrl(server.url("api", "me", "playlists"), offset).newBuilder().addQueryParameter("kind", "manual").build())
    suspend fun playlistEntries(id: String, offset: Int = 0): LibraryPage = get<TrackPage>(pageUrl(server.url("api", "me", "playlists", id, "entries"), offset)).flatten(keepEntries = true)
    suspend fun playlistPlayback(id: String): PlaybackQueue = get(server.url("api", "me", "playlists", id, "playback"))

    suspend fun setLiked(trackId: String, liked: Boolean) {
        val writeGeneration = synchronized(lock) { generation }
        mutate(server.url("api", "me", "likes"), "PUT", buildJsonObject { put("trackId", validId(trackId)); put("liked", liked) }.toString())
        notifyPersonalChange(PersonalCollection.LIKES, writeGeneration)
    }

    suspend fun likeStatus(trackId: String): Boolean {
        val body = buildJsonObject { putJsonArray("trackIds") { add(kotlinx.serialization.json.JsonPrimitive(validId(trackId))) } }.toString()
        return response(request(server.url("api", "me", "likes", "status"), "POST", body)) { result ->
            decode<LikeStatuses>(result).items.firstOrNull { it.trackId == trackId }?.liked
                ?: throw ApiException(result.code, "The server returned an incomplete like status")
        }
    }

    suspend fun recent(): LibraryPage = get<TrackPage>(server.url("api", "me", "recent").newBuilder().addQueryParameter("limit", "40").build()).flatten()
    suspend fun recordHistory(eventId: String, trackId: String) {
        val writeGeneration = synchronized(lock) { generation }
        mutate(server.url("api", "me", "history"), "POST", buildJsonObject { put("eventId", validId(eventId)); put("trackId", validId(trackId)) }.toString())
        notifyPersonalChange(PersonalCollection.HISTORY, writeGeneration)
    }

    fun audioUrl(item: LibraryItem): String {
        require(item.kind == "track") { "Only tracks have audio" }
        return server.url("api", "library", "tracks", item.id, "audio").toString()
    }
    fun artworkUrl(item: LibraryItem): String = server.url("api", "library", plural(item.kind), item.id, "artwork").toString()

    private fun plural(kind: String): String = when (kind) {
        "track" -> "tracks"; "album" -> "albums"; "artist" -> "artists"
        else -> throw IllegalArgumentException("Unsupported library kind")
    }
    private fun validId(id: String): String {
        require(id.isNotEmpty() && id != "." && id != ".." && id.none { it.isISOControl() }) { "Invalid identifier" }
        return id
    }
    private fun pageUrl(url: HttpUrl, offset: Int): HttpUrl {
        require(offset in 0..1_000_000) { "Invalid page offset" }
        return url.newBuilder().addQueryParameter("limit", "40").addQueryParameter("offset", offset.toString()).build()
    }
    private fun requireCurrentGeneration(expectedGeneration: Long) {
        if (generation != expectedGeneration) throw IOException("The account session changed while the request was in progress")
    }
    private fun install(summary: Session, value: Cookie, expectedGeneration: Long) {
        if (value.expiresAt <= System.currentTimeMillis()) throw ApiException(200, "The server returned an expired session cookie")
        if (summary.accountId.isBlank() || summary.username.isBlank() || summary.role !in setOf("admin", "user") || summary.csrfToken.isBlank())
            throw ApiException(200, "The server returned an invalid session")
        synchronized(lock) {
            requireCurrentGeneration(expectedGeneration)
            store.write(StoredSession(server.origin, value.toString(), summary))
            cookie = value
            session = summary
        }
    }
    private fun request(url: HttpUrl, method: String = "GET", body: String? = null): Request = Request.Builder().url(url)
        .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    private suspend inline fun <reified T> get(url: HttpUrl): T = response(request(url)) { decode<T>(it) }
    private suspend fun mutate(url: HttpUrl, method: String, body: String) { response(request(url, method, body)) { Unit } }
    internal suspend fun <T> response(
        request: Request,
        errorFactory: ((Response) -> ApiException)? = null,
        block: (Response) -> T,
    ): T = httpClient.newCall(request).awaitResponse(
        errorFactory ?: { ApiException(it.code, safeError(it.code, request.url.encodedPath == "/api/auth/login")) },
        block,
    )
    private inline fun <reified T> decode(response: Response): T {
        val mediaType = response.body?.contentType()
        if (mediaType?.subtype != "json" && mediaType?.subtype?.endsWith("+json") != true)
            throw ApiException(response.code, "Expected a JSON API response; check the server address and reverse proxy")
        return try { json.decodeFromString<T>(response.body!!.string()) }
        catch (_: SerializationException) { throw ApiException(response.code, "The server returned an incompatible JSON response") }
        catch (_: IllegalArgumentException) { throw ApiException(response.code, "The server returned an incompatible JSON response") }
    }
    private fun safeError(status: Int, login: Boolean): String = when (status) {
        401 -> if (login) "Invalid username or password" else "Your session has expired. Sign in again."
        403 -> "The server refused this request. Check your account permissions."
        404 -> "This item is no longer available"
        409 -> "The item changed on the server. Refresh and try again."
        429 -> "Too many requests. Please try again later."
        in 300..399 -> "The server redirected the request. Enter its direct origin instead."
        in 500..599 -> "The server is temporarily unavailable"
        else -> "The server could not complete the request (HTTP $status)"
    }
}

private const val PLAYBACK_LEAVE_LIFETIME_MS = 4000L
private val PLAYBACK_LEAVE_LIFETIME_NS = TimeUnit.MILLISECONDS.toNanos(PLAYBACK_LEAVE_LIFETIME_MS)
private class PlaybackLeaveAuthority(val request: Request, val cookieExpiresAt: Long, val address: InetAddress)

/** No system/custom resolver, reverse lookup, fallback address or resolver worker. The origin
 * hostname remains in the request URL, Host header and default TLS/hostname verification. */
internal class PlaybackLeaveDns(private val host: String, private val address: InetAddress) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != host) throw leaveUnavailable()
        return listOf(address)
    }
}

/** Not an authenticated client clone: the only reachable operation is this captured POST.
 * Unused auth is dropped by the timer, and claiming the capability removes it from the closure.
 * Transport resources are created only on invocation and never share ordinary cancellation. */
private class PreparedPlaybackLeave(
    authority: PlaybackLeaveAuthority?,
    private val capturedAt: Long,
    private val nanoTime: () -> Long,
) {
    private val pending = AtomicReference(authority)
    private val expiry = if (authority == null) null else CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        // Schedule against capture, not against eventual dispatcher execution. Rounding down
        // may retire unused authority <1ms early, never extend its lifetime.
        val remaining = try { remainingNanos() } catch (_: IOException) { 0L }
        delay(TimeUnit.NANOSECONDS.toMillis(remaining))
        pending.set(null)
    }

    private fun remainingNanos(): Long {
        val elapsed = nanoTime() - capturedAt
        if (elapsed < 0 || elapsed >= PLAYBACK_LEAVE_LIFETIME_NS) throw leaveUnavailable()
        return PLAYBACK_LEAVE_LIFETIME_NS - elapsed
    }

    suspend fun invoke() {
        val authority = pending.getAndSet(null) ?: throw leaveUnavailable()
        expiry?.cancel()
        currentCoroutineContext().ensureActive()
        remainingNanos()
        if (authority.cookieExpiresAt <= System.currentTimeMillis()) throw leaveUnavailable()
        val dispatcher = Dispatcher()
        val pool = ConnectionPool()
        var call: Call? = null
        try {
            // Fresh transport deliberately retains OkHttp's platform TLS/hostname defaults.
            // No inherited interceptor, cookie jar, authenticator, cache or successor auth.
            // Never enter blocking InetAddress DNS or proxy selection: cancellation cannot
            // interrupt those calls. Use only the captured, previously connected direct route.
            val transport = OkHttpClient.Builder()
                .dispatcher(dispatcher).connectionPool(pool)
                .proxy(Proxy.NO_PROXY)
                .dns(PlaybackLeaveDns(authority.request.url.host, authority.address))
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor { chain ->
                    remainingNanos()
                    if (authority.cookieExpiresAt <= System.currentTimeMillis()) throw leaveUnavailable()
                    chain.proceed(chain.request())
                }
                .build()
            currentCoroutineContext().ensureActive()
            call = transport.newCall(authority.request)
            call.timeout().deadlineNanoTime(System.nanoTime() + remainingNanos())
            call.awaitResponse({ ApiException(it.code, "The server refused playback cleanup (HTTP ${it.code})") }) { Unit }
        } finally {
            call?.cancel()
            pool.evictAll()
            dispatcher.executorService.shutdown()
        }
    }
}

private fun leaveUnavailable() = IOException("Playback cleanup is unavailable or expired")
private fun networkError() = IOException("Unable to reach the server. Check your connection and server address.")

/** Keep cancellation attached through response consumption, not merely header delivery. */
private suspend fun <T> Call.awaitResponse(errorFactory: (Response) -> ApiException, block: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(networkError())
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw errorFactory(it)
                        block(it)
                    }
                    continuation.resume(result) { _, _, _ -> }
                } catch (error: Exception) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(if (error is IOException && error !is ApiException) networkError() else error)
                    }
                }
            }
        })
    }

/** Cookie policy is shared by login and restore; parsing does not install or trust the cookie. */
internal fun parseSessionCookie(server: ServerAddress, serialized: String): Cookie? {
    if (serialized.split(';').drop(1).none { it.trim().equals("Path=/", ignoreCase = true) }) return null
    val value = Cookie.parse(server.url(), serialized) ?: return null
    val secure = server.origin.startsWith("https:")
    val expected = if (secure) "__Host-spofify_session" else "spofify_session"
    return value.takeIf {
        it.name == expected && it.value.isNotEmpty() && it.value.length <= 512 && it.hostOnly &&
            it.domain == server.url().host && it.path == "/" && it.httpOnly &&
            it.secure == secure && it.persistent && it.expiresAt > System.currentTimeMillis()
    }
}

private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

@Serializable
private data class TrackEnvelope(val track: LibraryItem, val id: String? = null)
@Serializable
private data class TrackPage(val items: List<TrackEnvelope>, val total: Int, val limit: Int = 40, val offset: Int = 0) {
    fun flatten(keepEntries: Boolean = false) = LibraryPage(items.map {
        if (keepEntries) it.track.copy(entryId = it.id) else it.track
    }, total, limit, offset)
}
@Serializable
private data class LikeStatus(val trackId: String, val liked: Boolean)
@Serializable
private data class LikeStatuses(val items: List<LikeStatus>)
