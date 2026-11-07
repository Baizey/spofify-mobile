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

/** Credential-free, process-local identity. Compare the client instance as well as this value;
 * incarnation is not persistent authority and must never be restored from disk. */
data class ValidatedSessionIdentity(val origin: String, val accountId: String, val incarnation: Long)

enum class AuthenticationChangeKind { DORMANT, VALIDATED, RESET, INVALIDATED, EXPIRED }

/** DORMANT fences access without proving saved auth invalid (for example an offline restore).
 * RESET, INVALIDATED and EXPIRED retire authorization; consumers may wipe retired storage.
 * revision orders notifications within ONE client, including notifications racing across threads. */
data class AuthenticationChange(
    val revision: Long,
    val kind: AuthenticationChangeKind,
    val identity: ValidatedSessionIdentity?,
)

/** One origin and one account. The same client can safely be shared with images and Media3. */
class SpofifyClient(
    val server: ServerAddress,
    private val store: SessionStore,
    private val authenticationTimeMillis: () -> Long,
) {
    constructor(server: ServerAddress, store: SessionStore) : this(server, store, System::currentTimeMillis)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var cookie: Cookie? = null
    private var session: Session? = null
    private var generation: Long = 0
    // Deadline/credential publication is NOT a new logical auth incarnation.
    private var credentialRevision: Long = 0
    private var sessionMode: String? = null
    private var persistedDeadline: Long = 0
    private var nextPersistenceAttempt: Long = 0
    private class RestoreProbe(val generation: Long)
    private class TerminalLogout(val auth: RequestAuthentication)
    private class RequestAuthentication(val generation: Long, val revision: Long, val cookie: Cookie?,
        val session: Session?, val restoring: Boolean = false, val terminal: Boolean = false) {
        @Volatile var dispatchedRevision = revision
    }
    // Capture at Call creation, before OkHttp's queue can span an account switch.
    // Weak keys also retire never-executed Calls; completed Calls remove themselves.
    private val requests = java.util.WeakHashMap<Call, RequestAuthentication>()
    private var authentication = AuthenticationChange(0, AuthenticationChangeKind.DORMANT, null)

    /** Synchronous fencing notification, always OUTSIDE the client lock, including disk failures.
     * Install before starting auth operations. Listeners must be nonthrowing, fence access/cancel
     * work rather than wait for I/O, and ignore stale revisions and retired client instances.
     * Concurrent callbacks can arrive out of order; an event is not itself read authority. Call
     * validatedSessionIdentity() on every protected read and match the captured incarnation.
     * DORMANT must not be treated as logout: failed/offline restoration can retain dormant bytes.
     * No cookie, token, password or Session is exposed by this callback. */
    @Volatile var onAuthenticationChanged: ((AuthenticationChange) -> Unit)? = null

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
        .eventListenerFactory { call ->
            val captured = requestAuthentication(call.request())
            synchronized(lock) { requests[call] = captured }
            object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    val route = connection.route()
                    recordPlaybackLeaveRoute(captured.generation, call.request().url, route.address.url, route.proxy.type(), route.socketAddress)
                }
                override fun callEnd(call: Call) { synchronized(lock) { requests.remove(call) } }
                override fun callFailed(call: Call, ioe: IOException) { synchronized(lock) { requests.remove(call) } }
            }
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(object : CookieJar {
            // Installation and negotiated deadline updates are explicitly validated below.
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = changingAuthentication {
                expireSessionLocked()
                if (!server.contains(url)) emptyList()
                else cookie?.takeIf { it.expiresAt > authenticationTimeMillis() }?.let { listOf(it) } ?: emptyList()
            }
        })
        .addInterceptor { chain ->
            val request = chain.request()
            if (!server.contains(request.url)) throw IOException("Off-origin requests are not allowed")
            val login = request.method == "POST" && request.url.encodedPath == "/api/auth/login"
            val auth = synchronized(lock) {
                requests[chain.call()] ?: throw IOException("Missing request authentication")
            }
            requireLiveDispatch(auth)
            val builder = request.newBuilder().tag(RequestAuthentication::class.java, auth)
                .header("Origin", server.origin).removeHeader("Cookie")
                .removeHeader("X-CSRF-Token").removeHeader("X-Spofify-Login").removeHeader("X-Spofify-Session-Mode")
            if (request.method !in listOf("GET", "HEAD", "OPTIONS")) {
                builder.header("Content-Type", "application/json")
                // OkHttp can otherwise replay a body after certain 503/421 responses even
                // with retryOnConnectionFailure(false). Every mutation is explicitly one-shot.
                builder.method(request.method, OneShotBody(request.body ?: "".toRequestBody(null)))
                if (login) builder.header("X-Spofify-Login", "1").header("X-Spofify-Session-Mode", ROLLING_SESSION_MODE)
                else auth.session?.let { builder.header("X-CSRF-Token", it.csrfToken) }
            }
            val response = chain.proceed(builder.build())
            try {
                if (!login && !auth.terminal) {
                    if (response.code == 401 && auth.cookie != null)
                        invalidate(auth.generation, notifyUnauthorized = true, expectedCredentialRevision = auth.dispatchedRevision)
                    else if (!auth.restoring && (response.isSuccessful || response.code == 304))
                        acceptRollingDeadline(response, auth)
                }
                response
            } catch (error: Exception) {
                // The response is not handed to a caller when invalidation/storage fails.
                response.close()
                throw error
            }
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val captured = request.tag(RequestAuthentication::class.java)
                ?: throw IOException("Missing request authentication")
            // Recheck after connection/route waits as well as before them. An old
            // captured cookie is not permission to dispatch after live auth expired.
            requireLiveDispatch(captured)
            // CookieJar runs later than request capture. Never let it borrow a successor
            // account/token (or replace the fixed retired logout credential).
            val bound = request.newBuilder().removeHeader("Cookie")
            captured.cookie?.let { bound.header("Cookie", "${it.name}=${it.value}") }
            chain.proceed(bound.build())
        }
        .build()

    fun currentSession(): Session? = changingAuthentication {
        expireSessionLocked()
        session.takeIf { authentication.identity != null }
    }

    /** Live, server-validated authority only; a persisted summary or restore-in-flight cookie
     * never qualifies. Expiry synchronously fences listeners before this call returns. Auth reads
     * fail closed without throwing if clearing the expired persisted session fails. */
    fun validatedSessionIdentity(): ValidatedSessionIdentity? = changingAuthentication {
        expireSessionLocked()
        authentication.identity
    }

    /** Match the actual captured Session, not structural account equality (including account ABA).
     * Suitable for each cached read alongside the caller's client/origin/output-authority checks. */
    fun hasValidSession(expected: Session): Boolean = changingAuthentication {
        expireSessionLocked()
        session === expected && authentication.identity != null
    }

    // Snapshot transitions under the auth lock, notify only after releasing it. Do not serialize
    // callbacks with another lock: graph -> client reads must not invert client -> graph fencing.
    // Revision fencing lets consumers reject delayed events without blocking a successor login.
    private inline fun <T> changingAuthentication(block: () -> T): T {
        var change: AuthenticationChange? = null
        try {
            return synchronized(lock) {
                val previous = authentication.revision
                try { block() }
                finally { if (authentication.revision != previous) change = authentication }
            }
        } finally {
            change?.let { onAuthenticationChanged?.invoke(it) }
        }
    }

    private fun authenticationChangedLocked(kind: AuthenticationChangeKind) {
        authentication = AuthenticationChange(authentication.revision + 1, kind,
            if (kind == AuthenticationChangeKind.VALIDATED)
                ValidatedSessionIdentity(server.origin, checkNotNull(session).accountId, generation)
            else null)
    }

    private fun expireSessionLocked() {
        if (cookie?.let { it.expiresAt <= authenticationTimeMillis() } == true) {
            if (sessionMode == ROLLING_SESSION_MODE) {
                // A lost reply/save can leave an older LOCAL deadline. Fence all live
                // authority, retain the encrypted credential for dormant server validation.
                if (authentication.identity != null) authenticationChangedLocked(AuthenticationChangeKind.DORMANT)
                return
            }
            try { resetSessionLocked(AuthenticationChangeKind.EXPIRED) }
            catch (_: IOException) {
                // Read-only authority queries are used synchronously by playback and UI. Runtime
                // auth and the expiry event were already cleared/recorded before the disk write;
                // failed persistence must not crash these readers or restore expired authority.
                // Explicit clear/invalidation operations retain their persistence error behavior.
            }
        }
    }

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
        if (generation == expectedGeneration && authentication.identity != null && session != null && cookie?.let { it.expiresAt > authenticationTimeMillis() } == true) {
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
    internal fun preparePlaybackLeave(deviceId: String, nanoTime: () -> Long = System::nanoTime): suspend () -> Unit =
        preparePlaybackLeave(deviceId, registrationGeneration = null, nanoTime = nanoTime)

    internal fun preparePlaybackLeave(deviceId: String, registrationGeneration: String?, nanoTime: () -> Long = System::nanoTime): suspend () -> Unit {
        require(registrationGeneration == null || validPlaybackProtocolId(registrationGeneration))
        require(validPlaybackProtocolId(deviceId)) { "Invalid playback device identifier" }
        val capturedAt = nanoTime()
        val request = synchronized(lock) {
            val oldCookie = cookie
            val oldSession = session
            val oldAddress = playbackLeaveAddress
            if (authentication.identity == null || oldCookie == null || oldSession == null || oldAddress == null || oldCookie.expiresAt <= authenticationTimeMillis()) null
            else {
                val body = buildJsonObject {
                    put("action", "leave"); put("deviceId", deviceId)
                    registrationGeneration?.let { put("registrationGeneration", it) }
                }.toString()
                PlaybackLeaveAuthority(
                    Request.Builder().url(server.url("api", "me", "player"))
                        .header("Origin", server.origin)
                        .header("Cookie", "${oldCookie.name}=${oldCookie.value}")
                        .header("X-CSRF-Token", oldSession.csrfToken)
                        .header("Accept", "application/json")
                        .apply { if (registrationGeneration != null) header("X-Spofify-Playback-Registration", "installation-v1") }
                        .post(OneShotBody(body.toRequestBody("application/json; charset=utf-8".toMediaType())))
                        .build(),
                    oldCookie.expiresAt, oldAddress,
                )
            }
        }
        return PreparedPlaybackLeave(request, capturedAt, nanoTime)::invoke
    }

    private fun resetSession(): Long = changingAuthentication { resetSessionLocked(AuthenticationChangeKind.RESET) }

    private fun resetSessionLocked(kind: AuthenticationChangeKind): Long {
        cookie = null
        session = null
        sessionMode = null
        persistedDeadline = 0
        nextPersistenceAttempt = 0
        playbackLeaveAddress = null
        generation++
        credentialRevision++
        authenticationChangedLocked(kind)
        try { store.write(null) }
        catch (_: Exception) { throw IOException("Unable to clear the saved session. Sign in again.") }
        return generation
    }

    /** Old requests must not clear or resurrect a newer account session. */
    private fun invalidate(expectedGeneration: Long, notifyUnauthorized: Boolean = false,
        expectedCredentialRevision: Long? = null): Boolean = changingAuthentication {
        if (generation != expectedGeneration || (expectedCredentialRevision != null && credentialRevision != expectedCredentialRevision)) false
        else {
            val invalidatedGeneration = generation + 1
            try { resetSessionLocked(AuthenticationChangeKind.INVALIDATED) }
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
                val reply = decode<AuthenticationReply>(result)
                val mode = if (reply.sessionMode == ROLLING_SESSION_MODE && rollingResponse(result)) ROLLING_SESSION_MODE
                    else if (reply.sessionMode == null && result.headers.values("X-Spofify-Session-Mode").isEmpty()) null
                    else throw ApiException(result.code, "The server returned an incompatible session mode")
                val candidates = result.headers.values("Set-Cookie").mapNotNull {
                    parseSessionCookie(server, it, authenticationTimeMillis(), absoluteExpiry = mode != null)
                }
                val summary = reply.session()
                if (candidates.size != 1) throw ApiException(result.code, "The server did not issue a valid session cookie")
                install(summary, candidates.single(), loginGeneration, mode)
                summary
            }
        } catch (error: CancellationException) {
            invalidate(loginGeneration)
            throw error
        }
    }

    /** Revalidates persisted origin and cookie before contacting the server; no optimistic authentication. */
    suspend fun restore(): Session? {
        val (restoreGeneration, saved) = changingAuthentication {
            generation++
            credentialRevision++
            cookie = null
            session = null
            sessionMode = null
            persistedDeadline = 0
            nextPersistenceAttempt = 0
            playbackLeaveAddress = null
            authenticationChangedLocked(AuthenticationChangeKind.DORMANT)
            generation to store.read()
        }
        if (saved == null) return null
        val candidate = if (saved.server == server.origin && saved.sessionMode in setOf(null, ROLLING_SESSION_MODE))
            parseSessionCookie(server, saved.cookie, authenticationTimeMillis(),
                allowExpired = saved.sessionMode == ROLLING_SESSION_MODE, absoluteExpiry = saved.sessionMode != null) else null
        if (candidate == null) { invalidate(restoreGeneration); return null }
        synchronized(lock) {
            requireCurrentGeneration(restoreGeneration)
            cookie = candidate
            sessionMode = saved.sessionMode
        }
        return try {
            val probe = request(server.url("api", "auth", "session")).newBuilder()
                .tag(RestoreProbe::class.java, RestoreProbe(restoreGeneration)).build()
            response(probe) { result ->
                val summary = decode<Session>(result)
                val mode = if (rollingResponse(result)) ROLLING_SESSION_MODE else null
                if (saved.sessionMode != null && mode == null)
                    throw ApiException(result.code, "The server returned an incompatible session mode")
                val validated = if (mode != null) rollingCookie(result, candidate)
                    ?: throw ApiException(result.code, "The server did not return a valid rolling deadline") else candidate
                install(summary, validated, restoreGeneration, mode)
                summary
            }
        } catch (error: ApiException) {
            // Rate limits and temporary server failures do not prove the saved session
            // invalid. Drop unvalidated runtime auth, but allow an explicit later retry.
            if (error.status == 401) invalidate(restoreGeneration)
            else forgetRestore(restoreGeneration)
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
    private fun forgetRestore(expectedGeneration: Long) = changingAuthentication {
        if (generation == expectedGeneration) {
            cookie = null
            session = null
            sessionMode = null
            persistedDeadline = 0
            nextPersistenceAttempt = 0
            playbackLeaveAddress = null
            generation++
            credentialRevision++
            authenticationChangedLocked(AuthenticationChangeKind.DORMANT)
        }
    }

    suspend fun logout() {
        var clearFailure: IOException? = null
        val captured = changingAuthentication {
            val retired = RequestAuthentication(generation, credentialRevision, cookie, session, terminal = true)
            try { resetSessionLocked(AuthenticationChangeKind.INVALIDATED) }
            catch (error: IOException) { clearFailure = error }
            retired
        }
        // Local retirement precedes network suspension. Only this fixed one-shot logout
        // may use the captured old credential; no late reply can mutate successor auth.
        val logout = request(server.url("api", "auth", "logout"), "POST", "{}").newBuilder()
            .tag(TerminalLogout::class.java, TerminalLogout(captured)).build()
        try { response(logout) { Unit } }
        finally { clearFailure?.let { throw it } }
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
    private fun install(summary: Session, value: Cookie, expectedGeneration: Long, mode: String? = null) = changingAuthentication {
        if (value.expiresAt <= authenticationTimeMillis()) throw ApiException(200, "The server returned an expired session cookie")
        if (summary.accountId.isBlank() || summary.username.isBlank() || summary.role !in setOf("admin", "user") || summary.csrfToken.isBlank())
            throw ApiException(200, "The server returned an invalid session")
        requireCurrentGeneration(expectedGeneration)
        store.write(StoredSession(server.origin, value.toString(), summary, mode))
        cookie = value
        session = summary
        sessionMode = mode
        persistedDeadline = value.expiresAt
        nextPersistenceAttempt = 0
        credentialRevision++
        authenticationChangedLocked(AuthenticationChangeKind.VALIDATED)
    }
    private fun requestAuthentication(request: Request): RequestAuthentication {
        if (!server.contains(request.url)) throw IOException("Off-origin requests are not allowed")
        request.tag(TerminalLogout::class.java)?.let { terminal ->
            if (request.method != "POST" || request.url != server.url("api", "auth", "logout"))
                throw IOException("Invalid sign-out request")
            return terminal.auth
        }
        return changingAuthentication {
            expireSessionLocked()
            val restoring = request.tag(RestoreProbe::class.java)?.let {
                it.generation == generation && request.method == "GET" && request.url == server.url("api", "auth", "session")
            } == true
            if (cookie != null && authentication.identity == null && !restoring)
                throw IOException("The saved connection needs validation. Retry the connection.")
            RequestAuthentication(generation, credentialRevision, cookie, session, restoring)
        }
    }

    private fun requireLiveDispatch(captured: RequestAuthentication) = changingAuthentication {
        if (captured.terminal) return@changingAuthentication
        requireCurrentGeneration(captured.generation)
        if (!captured.restoring && captured.cookie != null) {
            expireSessionLocked()
            requireCurrentGeneration(captured.generation)
            val live = cookie
            if (authentication.identity == null || live == null || live.expiresAt <= authenticationTimeMillis() ||
                live.name != captured.cookie.name || live.value != captured.cookie.value)
                throw IOException("The saved connection needs validation. Retry the connection.")
        }
        captured.dispatchedRevision = credentialRevision
    }

    private fun rollingResponse(result: Response) = result.headers.values("X-Spofify-Session-Mode").singleOrNull() == ROLLING_SESSION_MODE

    private fun rollingCookie(result: Response, expected: Cookie): Cookie? {
        if (!rollingResponse(result) || !server.contains(result.request.url)) return null
        val candidates = result.headers.values("Set-Cookie").mapNotNull {
            parseSessionCookie(server, it, authenticationTimeMillis(), absoluteExpiry = true)
        }
        return candidates.singleOrNull()?.takeIf { it.name == expected.name && it.value == expected.value }
    }

    private fun acceptRollingDeadline(result: Response, captured: RequestAuthentication) = changingAuthentication {
        if (generation != captured.generation || sessionMode != ROLLING_SESSION_MODE || session == null) return@changingAuthentication
        val previous = cookie ?: return@changingAuthentication
        if (captured.cookie?.value != previous.value) return@changingAuthentication
        val validated = rollingCookie(result, previous) ?: return@changingAuthentication
        if (validated.expiresAt > previous.expiresAt) {
            cookie = validated
            credentialRevision++ // Fence old 401s WITHOUT retiring a healthy logical Session.
            // Once live authority was fenced dormant, an ordinary late response may
            // save a newer deadline but cannot reacquire it. Only explicit restore can.
        }
        val current = checkNotNull(cookie)
        if (persistedDeadline < current.expiresAt && authenticationTimeMillis() >= nextPersistenceAttempt) {
            try {
                store.write(StoredSession(server.origin, current.toString(), checkNotNull(session), sessionMode))
                persistedDeadline = current.expiresAt
                nextPersistenceAttempt = 0
            } catch (_: Exception) {
                // Never rewrite an accepted musical/API result as failure or delete the old
                // encrypted credential. A later normal response retries this bounded save.
                nextPersistenceAttempt = authenticationTimeMillis() + 60_000L
            }
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
internal fun parseSessionCookie(server: ServerAddress, serialized: String, nowMillis: Long = System.currentTimeMillis(),
    allowExpired: Boolean = false, absoluteExpiry: Boolean = false): Cookie? {
    val parts = serialized.split(';')
    if (parts.drop(1).none { it.trim().equals("Path=/", ignoreCase = true) }) return null
    if (absoluteExpiry && (parts.drop(1).none { it.trim().startsWith("Expires=", ignoreCase = true) } ||
        parts.drop(1).filter { it.trim().startsWith("Max-Age=", ignoreCase = true) }
            .any { it.substringAfter('=').trim().toLongOrNull()?.let { age -> age <= 0 } != false })) return null
    // The server's absolute deadline must not drift on every quiet poll due to
    // Max-Age/receipt-time rounding, delay or process restart.
    val wire = if (absoluteExpiry) parts.filterNot { it.trim().startsWith("Max-Age=", ignoreCase = true) }.joinToString(";") else serialized
    val value = Cookie.parse(server.url(), wire) ?: return null
    val secure = server.origin.startsWith("https:")
    val expected = if (secure) "__Host-spofify_session" else "spofify_session"
    return value.takeIf {
        it.name == expected && it.value.isNotEmpty() && it.value.length <= 512 && it.hostOnly &&
            it.domain == server.url().host && it.path == "/" && it.httpOnly &&
            it.secure == secure && it.persistent && (allowExpired || it.expiresAt > nowMillis)
    }
}

private const val ROLLING_SESSION_MODE = "rolling-v1"

@Serializable
private data class AuthenticationReply(val accountId: String, val username: String, val role: String,
    val csrfToken: String, val sessionMode: String? = null) {
    fun session() = Session(accountId, username, role, csrfToken)
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
