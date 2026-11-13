package dev.spofify.mobile.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Credential-free, process-local DENIAL-ONLY history of genuine validated account scopes.
 * Compare only within the same client. This is NOT a live Session, credential, playback/receiver
 * authority or permission to grant local eligibility. A revision change can retire an older
 * scope even after cookie expiry or an account A -> B -> A transition hides the intermediate B. */
data class ObservedAuthenticatedScope internal constructor(
    val origin: String,
    val accountId: String,
    val scopeRevision: Long,
)

enum class AuthenticationChangeKind { DORMANT, VALIDATED, RESET, INVALIDATED, EXPIRED }

/** DORMANT fences access without proving saved auth invalid (for example an offline restore).
 * RESET/INVALIDATED retire authorization. EXPIRED retires live server authority only: elapsed
 * cookie expiry is NOT an observed 401 and need not retire separately approved local eligibility.
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
    private val admission = NetworkAdmission(lock)

    /** Explicit, process-local zero-network admission. The app owns persistence and local-media
     * eligibility. Mode changes neither authenticate nor clear/replace a live Session, and do
     * not send logout or musical commands. Both edges permanently fence earlier calls and response parsing. */
    val isOffline: Boolean get() = admission.isOffline
    fun setOffline(offline: Boolean) {
        var retired: List<Call> = emptyList()
        try {
            changingAuthentication {
                retired = admission.setOffline(offline) {
                    // Fence a restore's provisional cookie NOW, not from its eventual failure
                    // callback. Validated live Sessions remain unchanged by the mode edge.
                    if (offline && cookie != null && authentication.identity == null) forgetRestoreLocked()
                }
            }
        } finally {
            // Cancel only captured calls, outside the shared lock. Dispatcher-wide cancellation
            // could accidentally cancel a fresh online successor racing an entry/exit ABA.
            retired.forEach { it.cancel() }
        }
    }

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
    private class RequestAuthentication(val generation: Long, val revision: Long, val admissionEpoch: Long, val cookie: Cookie?,
        val session: Session?, val restoring: Boolean = false, val terminal: Boolean = false) {
        @Volatile var dispatchedRevision = revision
    }
    // Capture at Call creation, before OkHttp's queue can span an account switch.
    // Weak keys also retire never-executed Calls; completed Calls remove themselves.
    private val requests = java.util.WeakHashMap<Call, RequestAuthentication>()
    private class LibrarySocketAdmission(val auth: RequestAuthentication, val identity: ValidatedSessionIdentity) {
        var retire: ((LibraryPlaybackEvent.Reason) -> Unit)? = null
        var upgradeCall: Call? = null
        var retired = false
    }
    private val librarySockets = mutableSetOf<LibrarySocketAdmission>()
    /** Supported factory seam for deterministic dispatch/handle-publication tests. Production
     * uses the exact admitted client, including its captured-cookie/no-followup policy. */
    internal var libraryPlaybackWebSocketFactory: (OkHttpClient) -> okhttp3.WebSocket.Factory = { it }
    private var authentication = AuthenticationChange(0, AuthenticationChangeKind.DORMANT, null)
    @Volatile private var observedScope: ObservedAuthenticatedScope? = null

    /** Synchronous fencing notification, always OUTSIDE the client lock, including disk failures.
     * Install before starting auth operations. Listeners must be nonthrowing, fence access/cancel
     * work rather than wait for I/O, and ignore stale revisions and retired client instances.
     * Concurrent callbacks can arrive out of order; an event is not itself read authority. Call
     * validatedSessionIdentity() on every protected read and match the captured incarnation.
     * DORMANT must not be treated as logout: failed/offline restoration can retain dormant bytes.
     * No cookie, token, password or Session is exposed by this callback. */
    @Volatile var onAuthenticationChanged: ((AuthenticationChange) -> Unit)? = null

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
            synchronized(lock) {
                admission.track(call, captured.admissionEpoch)
                requests[call] = captured
            }
            object : EventListener() {
                override fun callEnd(call: Call) {
                    synchronized(lock) { requests.remove(call) }
                    admission.finished(call)
                }
                override fun callFailed(call: Call, ioe: IOException) {
                    synchronized(lock) { requests.remove(call) }
                    admission.finished(call)
                }
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
                if (isOffline || !server.contains(url)) emptyList()
                else cookie?.takeIf { it.expiresAt > authenticationTimeMillis() }?.let { listOf(it) } ?: emptyList()
            }
        })
        .addInterceptor { chain ->
            val request = chain.request()
            if (!server.contains(request.url)) throw IOException("Off-origin requests are not allowed")
            val login = request.method == "POST" && request.url.encodedPath == "/api/auth/login"
            val auth = synchronized(lock) {
                // RealWebSocket replaces EventListener.Factory with NONE. Register its actual
                // Call BEFORE Bridge/Connect/route waits, while retirement is excluded. The
                // WebSocket handle need not have returned to start() yet: auth/offline/close
                // can already cancel this exact Call without touching a successor.
                val binding = request.tag(LibrarySocketAdmission::class.java)?.takeIf {
                    request.method == "GET" && request.url == server.url("api", "me", "player", "socket")
                }
                if (binding != null) {
                    if (binding !in librarySockets || !librarySocketCurrentLocked(binding) ||
                        binding.upgradeCall?.let { it !== chain.call() } == true)
                        throw IOException("Playback admission was superseded")
                    admission.track(chain.call(), binding.auth.admissionEpoch)
                    requests[chain.call()] = binding.auth
                    binding.upgradeCall = chain.call()
                    binding.auth
                } else requests[chain.call()] ?: throw IOException("Missing request authentication")
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
            // OkHttp deliberately skips network interceptors for WebSocket upgrades. Bind this
            // request to its captured cookie here; CookieJar must never lend it successor auth.
            if (request.tag(LibrarySocketAdmission::class.java) != null && request.method == "GET")
                auth.cookie?.let { builder.header("Cookie", "${it.name}=${it.value}") }
            val response = chain.proceed(builder.build())
            try {
                admission.requireCurrent(auth.admissionEpoch)
                if (!login && !auth.terminal) {
                    if (response.code == 401 && auth.cookie != null)
                        invalidate(auth.generation, notifyUnauthorized = true, expectedCredentialRevision = auth.dispatchedRevision,
                            expectedAdmission = auth.admissionEpoch)
                    else if (!auth.restoring && (response.isSuccessful || response.code == 304))
                        acceptRollingDeadline(response, auth)
                }
                admission.guard(response, auth.admissionEpoch)
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

    // WebSocket upgrades bypass network interceptors. Keep their explicitly captured Cookie,
    // and use BridgeInterceptor's per-attempt cookie hook to refuse even HTTP 503/421 followups
    // (retryOnConnectionFailure(false) alone does not disable all HTTP status retries).
    // TLS, auth/exact-origin interceptors, dispatcher and pool remain the shared client's.
    private fun libraryWebSocketClient(binding: LibrarySocketAdmission): OkHttpClient {
        val attempted = java.util.concurrent.atomic.AtomicBoolean()
        return httpClient.newBuilder().cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                if (url != server.url("api", "me", "player", "socket") || !attempted.compareAndSet(false, true))
                    throw IOException("Playback upgrade cannot be retried")
                requireLiveDispatch(binding.auth)
                synchronized(lock) {
                    if (!librarySocketCurrentLocked(binding)) throw IOException("Playback admission was superseded")
                }
                return emptyList()
            }
        }).addInterceptor { chain ->
            // RealWebSocket adds its compression offer before application interceptors.
            // The v2 contract uses uncompressed text; do not permit an inflater to bypass
            // the original-byte bound/UTF-8 guard. A negotiated extension fails closed.
            val request = chain.request().newBuilder().removeHeader("Sec-WebSocket-Extensions").build()
            guardLibraryPlaybackUpgrade(chain.proceed(request))
        }.build()
    }

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

    /** Pure lock-free denial-only observation: no expiry checks, storage, callbacks or authority
     * acquisition. Survives RESET/EXPIRED/DORMANT/INVALIDATED/logout; consumers must revoke those
     * events separately. Null and a matching witness never establish new local/live authority. */
    fun observedAuthenticatedScope(): ObservedAuthenticatedScope? = observedScope

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
            retireLibrarySockets()
            change?.let { onAuthenticationChanged?.invoke(it) }
        }
    }

    private fun librarySocketCurrentLocked(binding: LibrarySocketAdmission): Boolean =
        !binding.retired && admission.isCurrent(binding.auth.admissionEpoch) && authentication.identity == binding.identity &&
            session === binding.auth.session && cookie?.let { live ->
                live.expiresAt > authenticationTimeMillis() && live.name == binding.auth.cookie?.name && live.value == binding.auth.cookie?.value
            } == true

    /** Detach only this admission's actual Call. Caller cancels it OUTSIDE the auth lock. */
    private fun detachLibrarySocketLocked(binding: LibrarySocketAdmission): Call? {
        binding.retired = true
        librarySockets.remove(binding)
        return binding.upgradeCall.also { call ->
            binding.upgradeCall = null
            if (call != null) {
                requests.remove(call)
                admission.finished(call)
            }
        }
    }

    private fun retireLibrarySockets() {
        val retired = synchronized(lock) {
            librarySockets.filter { !librarySocketCurrentLocked(it) }.map {
                val reason = if (isOffline) LibraryPlaybackEvent.Reason.OFFLINE else LibraryPlaybackEvent.Reason.AUTHENTICATION
                Triple(detachLibrarySocketLocked(it), it.retire, reason)
            }
        }
        // Cancel even a still-unpublished WebSocket's in-flight upgrade before notifying its
        // transport. Never call OkHttp/socket/coordinator callbacks under the authentication lock.
        retired.forEach { (call, retire, reason) -> call?.cancel(); retire?.invoke(reason) }
    }

    /** One-use ticket, exact-origin shared authenticated transport. No reconnect or musical replay.
     * The caller supplies its deterministic account-scoped logical device identity, not a lease. */
    suspend fun openLibraryPlayback(
        scope: CoroutineScope, name: String, deviceId: String, canPlay: Boolean,
    ): LibraryPlaybackTransport {
        LibraryPlaybackCodec.name(name)
        LibraryPlaybackCodec.text(deviceId)
        scope.coroutineContext.ensureActive()
        val binding = changingAuthentication {
            expireSessionLocked()
            val identity = authentication.identity ?: throw IOException("Playback requires a validated session")
            val epoch = admission.capture()
            LibrarySocketAdmission(RequestAuthentication(generation, credentialRevision, epoch, cookie, session), identity)
        }
        fun bound(path: String, method: String, body: String? = null): Request =
            request(server.url("api", "me", "player", path), method, body, binding.auth.admissionEpoch)
                .newBuilder().tag(LibrarySocketAdmission::class.java, binding).build()
        val ticket = response(bound("connect", "POST", "{}")) {
            requireLiveDispatch(binding.auth)
            val media = it.body.contentType()
            if (media?.subtype != "json" || it.body.source().request(1025)) throw LibraryPlaybackProtocolException()
            LibraryPlaybackCodec.ticket(it.body.string()).also { requireLiveDispatch(binding.auth) }
        }
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        val transport = OkHttpLibraryPlaybackTransport(
            identity = binding.identity, scope = scope, deviceId = deviceId,
            current = { changingAuthentication { expireSessionLocked(); librarySocketCurrentLocked(binding) } },
            guardedSend = { action -> synchronized(lock) { librarySocketCurrentLocked(binding) && action() } },
            renewAuthentication = { renewLibraryPlaybackAuthentication(binding) },
            unregister = {
                val call = synchronized(lock) { detachLibrarySocketLocked(binding) }
                call?.cancel()
            },
        )
        binding.retire = transport::retire
        try {
            changingAuthentication {
                if (!librarySocketCurrentLocked(binding)) throw IOException("Playback admission was superseded")
                librarySockets.add(binding)
            }
            if (!transport.isCurrent) throw IOException("Playback admission was superseded")
            val hello = LibraryPlaybackCodec.hello(ticket, name, deviceId, canPlay)
            transport.start(hello) { listener ->
                requireLiveDispatch(binding.auth)
                libraryPlaybackWebSocketFactory(libraryWebSocketClient(binding)).newWebSocket(bound("socket", "GET").newBuilder()
                    .header("Sec-WebSocket-Protocol", LibraryPlaybackProtocol.SUBPROTOCOL).build(), listener)
            }
            return transport
        } catch (error: Exception) {
            transport.close()
            throw error
        }
    }

    /** Rolling HTTP renewal is authentication-only. WS frames never refresh the HTTP session. */
    private suspend fun renewLibraryPlaybackAuthentication(binding: LibrarySocketAdmission) {
        requireLiveDispatch(binding.auth)
        val renewal = request(server.url("api", "auth", "session"), admissionEpoch = binding.auth.admissionEpoch)
            .newBuilder().tag(LibrarySocketAdmission::class.java, binding).build()
        response(renewal) {
            requireLiveDispatch(binding.auth)
            if (it.body.source().request(65537)) throw LibraryPlaybackProtocolException()
            val renewed = decode<Session>(it)
            if (renewed.accountId != binding.identity.accountId || renewed != binding.auth.session) {
                invalidate(binding.auth.generation, notifyUnauthorized = true,
                    expectedCredentialRevision = binding.auth.dispatchedRevision, expectedAdmission = binding.auth.admissionEpoch)
                throw LibraryPlaybackProtocolException()
            }
            requireLiveDispatch(binding.auth)
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

    /** Call only after a confirmed personal mutation, never after optimistic UI changes or reads. */
    fun notifyPersonalChange(collection: PersonalCollection) = synchronized(lock) {
        notifyPersonalChange(collection, personalMutationGeneration())
    }

    /** Capture before an external API mutation and pass back only after its confirmed success. */
    internal fun personalMutationGeneration(): Long = synchronized(lock) { generation + admission.revision }

    internal fun notifyPersonalChange(collection: PersonalCollection, expectedGeneration: Long) = synchronized(lock) {
        if (!isOffline && personalMutationGeneration() == expectedGeneration && authentication.identity != null && session != null && cookie?.let { it.expiresAt > authenticationTimeMillis() } == true) {
            val previous = mutablePersonalRevision.value
            mutablePersonalRevision.value = when (collection) {
                PersonalCollection.LIKES -> previous.copy(likes = previous.likes + 1)
                PersonalCollection.HISTORY -> previous.copy(history = previous.history + 1)
                PersonalCollection.PLAYLISTS -> previous.copy(playlists = previous.playlists + 1)
            }
        }
    }

    fun clearSession() { resetSession() }

    private fun resetSession(): Long = changingAuthentication { resetSessionLocked(AuthenticationChangeKind.RESET) }

    private fun resetSessionLocked(kind: AuthenticationChangeKind): Long {
        cookie = null
        session = null
        sessionMode = null
        persistedDeadline = 0
        nextPersistenceAttempt = 0
        generation++
        credentialRevision++
        authenticationChangedLocked(kind)
        try { store.write(null) }
        catch (_: Exception) { throw IOException("Unable to clear the saved session. Sign in again.") }
        return generation
    }

    /** Old requests must not clear or resurrect a newer account session. */
    private fun invalidate(expectedGeneration: Long, notifyUnauthorized: Boolean = false,
        expectedCredentialRevision: Long? = null, expectedAdmission: Long): Boolean = changingAuthentication {
        if (generation != expectedGeneration || (expectedCredentialRevision != null && credentialRevision != expectedCredentialRevision) ||
            !admission.isCurrent(expectedAdmission)) false
        else admission.publish(expectedAdmission) {
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
        val (loginGeneration, loginAdmission) = changingAuthentication {
            val epoch = admission.capture()
            admission.publish(epoch) { resetSessionLocked(AuthenticationChangeKind.RESET) to epoch }
        }
        val body = buildJsonObject { put("username", username); put("password", password) }.toString()
        return try {
            response(request(server.url("api", "auth", "login"), "POST", body, loginAdmission)) { result ->
                val reply = decode<AuthenticationReply>(result)
                val mode = if (reply.sessionMode == ROLLING_SESSION_MODE && rollingResponse(result)) ROLLING_SESSION_MODE
                    else if (reply.sessionMode == null && result.headers.values("X-Spofify-Session-Mode").isEmpty()) null
                    else throw ApiException(result.code, "The server returned an incompatible session mode")
                val candidates = result.headers.values("Set-Cookie").mapNotNull {
                    parseSessionCookie(server, it, authenticationTimeMillis(), absoluteExpiry = mode != null)
                }
                val summary = reply.session()
                if (candidates.size != 1) throw ApiException(result.code, "The server did not issue a valid session cookie")
                install(summary, candidates.single(), loginGeneration, loginAdmission, mode)
                summary
            }
        } catch (error: CancellationException) {
            invalidate(loginGeneration, expectedAdmission = loginAdmission)
            throw error
        }
    }

    /** Revalidates persisted origin and cookie before contacting the server; no optimistic authentication. */
    suspend fun restore(): Session? {
        val restoreAdmission = admission.capture()
        val (restoreGeneration, saved) = changingAuthentication { admission.publish(restoreAdmission) {
            generation++
            credentialRevision++
            cookie = null
            session = null
            sessionMode = null
            persistedDeadline = 0
            nextPersistenceAttempt = 0
            authenticationChangedLocked(AuthenticationChangeKind.DORMANT)
            generation to store.read()
        } }
        if (saved == null) return null
        val candidate = if (saved.server == server.origin && saved.sessionMode in setOf(null, ROLLING_SESSION_MODE))
            parseSessionCookie(server, saved.cookie, authenticationTimeMillis(),
                allowExpired = saved.sessionMode == ROLLING_SESSION_MODE, absoluteExpiry = saved.sessionMode != null) else null
        if (candidate == null) {
            // A syntactically valid, merely elapsed legacy cookie is expiry, not observed401.
            val expired = saved.server == server.origin && saved.sessionMode == null &&
                parseSessionCookie(server, saved.cookie, authenticationTimeMillis(), allowExpired = true)
                    ?.let { it.expiresAt <= authenticationTimeMillis() } == true
            changingAuthentication { admission.publish(restoreAdmission) {
                requireCurrentGeneration(restoreGeneration)
                resetSessionLocked(if (expired) AuthenticationChangeKind.EXPIRED else AuthenticationChangeKind.INVALIDATED)
            } }
            return null
        }
        synchronized(lock) { admission.publish(restoreAdmission) {
            requireCurrentGeneration(restoreGeneration)
            cookie = candidate
            sessionMode = saved.sessionMode
        } }
        return try {
            val probe = request(server.url("api", "auth", "session"), admissionEpoch = restoreAdmission).newBuilder()
                .tag(RestoreProbe::class.java, RestoreProbe(restoreGeneration)).build()
            response(probe) { result ->
                val summary = decode<Session>(result)
                val mode = if (rollingResponse(result)) ROLLING_SESSION_MODE else null
                if (saved.sessionMode != null && mode == null)
                    throw ApiException(result.code, "The server returned an incompatible session mode")
                val validated = if (mode != null) rollingCookie(result, candidate)
                    ?: throw ApiException(result.code, "The server did not return a valid rolling deadline") else candidate
                install(summary, validated, restoreGeneration, restoreAdmission, mode)
                summary
            }
        } catch (error: ApiException) {
            // Rate limits and temporary server failures do not prove the saved session
            // invalid. Drop unvalidated runtime auth, but allow an explicit later retry.
            if (error.status == 401) invalidate(restoreGeneration, expectedAdmission = restoreAdmission)
            else forgetRestore(restoreGeneration, restoreAdmission)
            if (error.status == 401) null else throw error
        } catch (error: IOException) {
            forgetRestore(restoreGeneration, restoreAdmission)
            throw error
        } catch (error: CancellationException) {
            forgetRestore(restoreGeneration, restoreAdmission)
            throw error
        }
    }

    // A transport failure or cancellation must not leave an unvalidated cookie usable by media.
    // Keep the persisted copy for a later explicit restoration attempt. If install linearized
    // before an offline edge, a stale return must not erase that already validated live Session.
    private fun forgetRestore(expectedGeneration: Long, expectedAdmission: Long) = changingAuthentication {
        if (generation == expectedGeneration && admission.isCurrent(expectedAdmission) && authentication.identity == null)
            forgetRestoreLocked()
    }

    private fun forgetRestoreLocked() {
        cookie = null
        session = null
        sessionMode = null
        persistedDeadline = 0
        nextPersistenceAttempt = 0
        generation++
        credentialRevision++
        authenticationChangedLocked(AuthenticationChangeKind.DORMANT)
    }

    suspend fun logout() {
        var clearFailure: IOException? = null
        val captured = changingAuthentication {
            val retired = RequestAuthentication(generation, credentialRevision, admission.revision, cookie, session, terminal = true)
            try { resetSessionLocked(AuthenticationChangeKind.INVALIDATED) }
            catch (error: IOException) { clearFailure = error }
            retired
        }
        if (!admission.isCurrent(captured.admissionEpoch)) {
            clearFailure?.let { throw it }
            return // Offline logout is local-only, including a mode change racing retirement.
        }
        // Local retirement precedes network suspension. Only this fixed one-shot logout
        // may use the captured old credential; no late reply can mutate successor auth.
        val logout = request(server.url("api", "auth", "logout"), "POST", "{}", captured.admissionEpoch).newBuilder()
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

    suspend fun likes(offset: Int = 0): LibraryPage = get<TrackPage>(pageUrl(server.url("api", "me", "likes"), offset)).flatten()
    suspend fun playlists(offset: Int = 0): PlaylistPage = get(pageUrl(server.url("api", "me", "playlists"), offset).newBuilder().addQueryParameter("kind", "manual").build())
    suspend fun playlistEntries(id: String, offset: Int = 0): LibraryPage = get<TrackPage>(pageUrl(server.url("api", "me", "playlists", id, "entries"), offset)).flatten(keepEntries = true)

    suspend fun setLiked(trackId: String, liked: Boolean) {
        val writeGeneration = personalMutationGeneration()
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
    private fun install(summary: Session, value: Cookie, expectedGeneration: Long, expectedAdmission: Long, mode: String? = null) = changingAuthentication { admission.publish(expectedAdmission) {
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
        // Publish inside the genuine VALIDATED commit, before changingAuthentication releases
        // the lock and invokes Graph callbacks. Expiry/callback delays cannot hide this scope.
        val previous = observedScope
        if (previous == null || previous.origin != server.origin || previous.accountId != summary.accountId)
            observedScope = ObservedAuthenticatedScope(server.origin, summary.accountId,
                (previous?.scopeRevision ?: 0L) + 1L)
    } }
    private fun requestAuthentication(request: Request): RequestAuthentication {
        if (!server.contains(request.url)) throw IOException("Off-origin requests are not allowed")
        request.tag(LibrarySocketAdmission::class.java)?.let { binding ->
            val valid = (request.method == "POST" && request.url == server.url("api", "me", "player", "connect")) ||
                (request.method == "GET" && request.url in listOf(server.url("api", "me", "player", "socket"), server.url("api", "auth", "session")))
            if (!valid) throw IOException("Invalid playback admission request")
            requireLiveDispatch(binding.auth)
            synchronized(lock) {
                if (!librarySocketCurrentLocked(binding)) throw IOException("Playback admission was superseded")
            }
            return binding.auth
        }
        request.tag(TerminalLogout::class.java)?.let { terminal ->
            if (request.method != "POST" || request.url != server.url("api", "auth", "logout"))
                throw IOException("Invalid sign-out request")
            admission.requireCurrent(terminal.auth.admissionEpoch)
            return terminal.auth
        }
        return changingAuthentication {
            val epoch = request.tag(NetworkAdmissionEpoch::class.java)?.value ?: admission.capture()
            admission.requireCurrent(epoch)
            expireSessionLocked()
            val restoring = request.tag(RestoreProbe::class.java)?.let {
                it.generation == generation && request.method == "GET" && request.url == server.url("api", "auth", "session")
            } == true
            if (cookie != null && authentication.identity == null && !restoring)
                throw IOException("The saved connection needs validation. Retry the connection.")
            RequestAuthentication(generation, credentialRevision, epoch, cookie, session, restoring)
        }
    }

    private fun requireLiveDispatch(captured: RequestAuthentication) = changingAuthentication {
        admission.requireCurrent(captured.admissionEpoch)
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

    private fun acceptRollingDeadline(result: Response, captured: RequestAuthentication) = changingAuthentication { admission.publish(captured.admissionEpoch) {
        if (generation != captured.generation || sessionMode != ROLLING_SESSION_MODE || session == null) return@publish
        val previous = cookie ?: return@publish
        if (captured.cookie?.value != previous.value) return@publish
        val validated = rollingCookie(result, previous) ?: return@publish
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
    } }

    private fun request(url: HttpUrl, method: String = "GET", body: String? = null,
        admissionEpoch: Long = admission.capture()): Request = Request.Builder().url(url)
        .tag(NetworkAdmissionEpoch::class.java, NetworkAdmissionEpoch(admissionEpoch))
        .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    private suspend inline fun <reified T> get(url: HttpUrl): T = response(request(url)) { decode<T>(it) }
    private suspend fun mutate(url: HttpUrl, method: String, body: String) { response(request(url, method, body)) { Unit } }
    internal suspend fun <T> response(
        request: Request,
        errorFactory: ((Response) -> ApiException)? = null,
        block: (Response) -> T,
    ): T {
        val epoch = request.tag(NetworkAdmissionEpoch::class.java)?.value ?: admission.capture()
        admission.requireCurrent(epoch)
        val bound = request.newBuilder().tag(NetworkAdmissionEpoch::class.java, NetworkAdmissionEpoch(epoch)).build()
        return httpClient.newCall(bound).awaitResponse(
            errorFactory ?: { ApiException(it.code, safeError(it.code, request.url.encodedPath == "/api/auth/login")) },
            block, admission, epoch,
        )
    }
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

/** Keep cancellation attached through response consumption, not merely header delivery. */
private suspend fun <T> Call.awaitResponse(errorFactory: (Response) -> ApiException, block: (Response) -> T,
    admission: NetworkAdmission, admissionEpoch: Long): T {
    val result = try { suspendCancellableCoroutine<T> { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled)
                    continuation.resumeWithException(networkError(e, TransportFailurePhase.CALL))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val parsed = response.use {
                        admission.requireCurrent(admissionEpoch)
                        if (!it.isSuccessful) throw errorFactory(it)
                        block(it)
                    }
                    admission.requireCurrent(admissionEpoch)
                    continuation.resume(parsed) { _, _, _ -> }
                } catch (error: Exception) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(if (error is IOException && error !is ApiException)
                            networkError(error, TransportFailurePhase.RESPONSE_CONSUMER) else error)
                    }
                }
            }
        })
    } } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // A queued stale error (especially 401) is not a newly observed invalidation.
        admission.requireCurrent(admissionEpoch)
        throw error
    }
    // A completed parse may have resumed onto a dispatcher that was still queued at entry.
    // Check again in the receiving coroutine; neither cancellation nor an exit ABA revives it.
    admission.requireCurrent(admissionEpoch)
    return result
}

/** Cookie policy is shared by login and restore; parsing does not install or trust the cookie. */
internal fun parseSessionCookie(server: ServerAddress, serialized: String, nowMillis: Long = System.currentTimeMillis(),
    allowExpired: Boolean = false, absoluteExpiry: Boolean = false): Cookie? {
    val parts = serialized.split(';')
    if (parts.drop(1).none { it.trim().equals("Path=/", ignoreCase = true) }) return null
    if (absoluteExpiry && (parts.drop(1).none { it.trim().startsWith("Expires=", ignoreCase = true) } ||
        parts.drop(1).filter { it.trim().startsWith("Max-Age=", ignoreCase = true) }
            .any { it.substringAfter('=').trim().toLongOrNull()?.let { age -> age <= 0 } != false })) return null
    // The server's absolute deadline must not drift on every authenticated renewal due to
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
