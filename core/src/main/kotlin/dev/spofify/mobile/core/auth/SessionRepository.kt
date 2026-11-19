package dev.spofify.mobile.core.auth

import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.http.parseSessionCookie
import dev.spofify.mobile.core.http.FeatureResponse
import dev.spofify.mobile.core.http.FeatureTransport
import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.playback.PlaybackTransport
import dev.spofify.mobile.core.playback.isValidDeviceName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccountIdentity(val origin: String, val accountId: String, val username: String, val incarnation: Long)

enum class AuthNotice {
    INVALID_ORIGIN, INVALID_CREDENTIALS, CONNECTION_FAILED, STORAGE_UNAVAILABLE,
    SAVE_FAILED, SESSION_EXPIRED, REMOTE_LOGOUT_FAILED, LOCAL_LOGOUT_FAILED,
}

sealed interface AuthState {
    data object Starting : AuthState
    data class SignedOut(val notice: AuthNotice? = null) : AuthState
    data object SigningIn : AuthState
    data object Validating : AuthState
    data class Unavailable(val notice: AuthNotice) : AuthState
    data class Authenticated(val identity: AccountIdentity, val notice: AuthNotice? = null) : AuthState
}

/**
 * App-lifetime owner. The authority monitor never encloses storage or network I/O.
 * A current fully validated response atomically promotes authority and accepts its
 * durable intent. Those intents run in acceptance order, independently of retired
 * network jobs: an in-flight save precedes signout's clear and every successor save.
 * Cookies are explicit values, never automatic CookieJar publications.
 */
class SessionRepository(
    private val sessions: SessionStore,
    private val logins: LoginStore,
    private val apis: AuthApiFactory,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val manualOfflineStore: ManualOfflineStore? = null,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private val modeChanges = Mutex()
    private val mutableOffline = MutableStateFlow(false)
    val manualOffline = mutableOffline.asStateFlow()
    private val mutableGrant = MutableStateFlow<OnlineGrant?>(null)
    val onlineGrant = mutableGrant.asStateFlow()
    private var policyLoaded = manualOfflineStore == null
    private var handingOff = false
    private val bootstrapAllowed get() = policyLoaded && !mutableOffline.value && !handingOff
    private var generation = 0L
    private var initialized = false
    private var operation: Job? = null
    private var persistenceTail: Job? = null
    private var sessionSaveFailed = false
    private var profileSaveFailed = false
    private var localInvalidationFailed = false
    private var api: AuthApi? = null
    private var saved: StoredSession? = null
    private var identity: AccountIdentity? = null
    // Retiring authority cancels immediately. Keep membership until actual completion so a
    // manual transition can also join work already retiring from an earlier auth generation.
    private val authJobs = mutableSetOf<Job>()
    private val featureJobs = mutableSetOf<Job>()
    private val featureJson = Json

    @Serializable private class Admission(val ticket: String)
    @Serializable private class Hello(
        val type: String, val ticket: String, val name: String, val deviceId: String, val canPlay: Boolean,
    )

    fun playbackTransport(expected: AccountIdentity): PlaybackTransport = PlaybackTransport { name, deviceId ->
        require(isValidDeviceName(name))
        require(deviceId.isNotBlank() && deviceId.toByteArray().size <= 128 && deviceId.none(Char::isISOControl))
        featureExchange(expected) { client, record, headers, authority ->
            val transport = featureTransport(client)
            val response = transport.post(listOf("api", "me", "player", "connect"), "{}",
                record.cookie, record.session.csrfToken, headers)
            if (response.status != 200) throw AuthFailure(AuthFailure.Kind.UNAVAILABLE)
            val admission = try {
                featureJson.decodeFromString<Admission>(response.bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (_: Exception) { throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE) }
            require(admission.ticket.isNotBlank() && admission.ticket.toByteArray().size <= 4096)
            synchronized(lock) {
                if (!authority.isCurrent || !sameCredential(saved, record))
                    throw CancellationException("Session retired")
            }
            val hello = featureJson.encodeToString(Hello("hello", admission.ticket, name, deviceId, true))
            transport.socket(record.cookie, hello, authority, headers)
        }
    }

    suspend fun audio(
        expected: AccountIdentity, trackId: String, position: Long, length: Long?,
    ): AudioStream = featureExchange(expected) { client, record, headers, authority ->
        featureTransport(client).audio(trackId, position, length, record.cookie, authority, headers)
    }

    suspend fun versionedAudio(expected: AccountIdentity, media: dev.spofify.mobile.core.offline.OfflineMediaDescriptor): AudioStream =
        featureExchange(expected) { client, record, headers, authority ->
            featureTransport(client).versionedAudio(media, record.cookie, authority, headers)
        }

    internal fun isAdministrator(expected: AccountIdentity): Boolean = synchronized(lock) {
        require(isCurrent(expected))
        saved?.session?.role == "admin"
    }

    internal suspend fun get(
        expected: AccountIdentity,
        segments: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        artwork: Boolean = false,
        preserveUnavailableBody: Boolean = false,
    ): FeatureResponse = featureExchange(expected) { client, record, headers, _ ->
        featureTransport(client).get(segments, query, record.cookie, artwork, preserveUnavailableBody, headers)
    }

    internal suspend fun postJson(
        expected: AccountIdentity, segments: List<String>, body: String,
    ): FeatureResponse = featureExchange(expected) { client, record, headers, _ ->
        featureTransport(client).post(segments, body, record.cookie, record.session.csrfToken, headers)
    }

    internal suspend fun mutate(
        expected: AccountIdentity, segments: List<String>, method: String, body: String,
    ): FeatureResponse = featureExchange(expected) { client, record, headers, _ ->
        featureTransport(client).mutate(segments, method, body, record.cookie, record.session.csrfToken, headers)
    }

    /** In-place HTTP validation does not tear down a live, separately fenced player socket. */
    suspend fun renew(expected: AccountIdentity) {
        featureExchange(expected) { client, record, headers, authority ->
            val reply = try { client.session(record.cookie) } catch (failure: AuthFailure) {
                if (failure.kind == AuthFailure.Kind.UNAUTHORIZED) headers(401, null)
                throw failure
            }
            if (reply.session.accountId != expected.accountId) {
                synchronized(lock) {
                    if (authority.isCurrent) advance(AuthState.Unavailable(AuthNotice.CONNECTION_FAILED))
                }
                throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
            }
            val write = synchronized(lock) {
                if (!authority.isCurrent) throw CancellationException("Session retired")
                if (sameCredential(saved, record)) {
                    val updated = StoredSession(record.server, reply.cookie ?: record.cookie,
                        reply.session, reply.mode ?: record.sessionMode)
                    saved = updated
                    persistLocked(updated)
                } else null
            }
            write?.start()
            Unit
        }
    }

    private fun featureTransport(client: AuthApi): FeatureTransport = client as? FeatureTransport
        ?: throw AuthFailure(AuthFailure.Kind.UNAVAILABLE)

    private fun sameCredential(first: StoredSession?, second: StoredSession): Boolean =
        first?.cookie?.substringBefore(';')?.trim() == second.cookie.substringBefore(';').trim()

    /** Credentials stay here; every header, returned value and long-lived resource has the same owner. */
    private suspend fun <T : Any> featureExchange(
        expected: AccountIdentity,
        exchange: suspend (AuthApi, StoredSession, (Int, String?) -> Unit, FeatureAuthority) -> T,
    ): T = onlineWork(expected, null) { grant ->
        val authority = FeatureAuthority(lock) { mutableGrant.value === grant }
        val (record, client) = synchronized(lock) {
            if (!authority.isCurrent) throw CancellationException("Session retired")
            requireNotNull(saved) to requireNotNull(api)
        }
        val headers: (Int, String?) -> Unit = { status, renewed ->
            val write = synchronized(lock) {
                if (!authority.isCurrent) throw CancellationException("Session retired")
                val same = sameCredential(saved, record)
                when {
                    status == 401 && same -> {
                        advance(AuthState.SignedOut(AuthNotice.SESSION_EXPIRED))
                        saved = null
                        identity = null
                        persistLocked(null)
                    }
                    status == 401 -> throw AuthFailure(AuthFailure.Kind.UNAVAILABLE)
                    renewed != null && same && record.sessionMode == "rolling-v1" -> {
                        val updated = StoredSession(record.server, renewed, record.session, record.sessionMode)
                        saved = updated
                        persistLocked(updated)
                    }
                    else -> null
                }
            }
            write?.start()
        }
        exchange(client, record, headers, authority)
    }

    /** Own the entire online operation (including retries, parsing and terminal publication),
     * not just an individual request. Never invoke mode changes from within this block. */
    suspend fun <T : Any> withOnlineGrant(grant: OnlineGrant, block: suspend CoroutineScope.() -> T): T =
        onlineWork(grant.identity, grant) { coroutineScope(block) }

    /** For queued results delivered outside the network worker. Publication must be short and nonblocking. */
    fun publishIfCurrent(grant: OnlineGrant, publish: () -> Unit): Boolean = synchronized(lock) {
        if (mutableGrant.value !== grant || handingOff) false else { publish(); true }
    }

    private suspend fun <T : Any> onlineWork(
        expected: AccountIdentity, required: OnlineGrant?, block: suspend (OnlineGrant) -> T,
    ): T = coroutineScope {
        val job = currentCoroutineContext().job
        val grant = synchronized(lock) {
            val current = mutableGrant.value
            if (!bootstrapAllowed || current == null || current.identity != expected || (required != null && required !== current))
                throw CancellationException("No online grant")
            featureJobs.add(job)
            current
        }
        fun checkCurrent() = synchronized(lock) {
            if (mutableGrant.value !== grant) throw CancellationException("Session retired")
        }
        var produced: T? = null
        try {
            ensureActive()
            val result = block(grant)
            produced = result
            ensureActive()
            checkCurrent()
            result
        } catch (failure: Exception) {
            (produced as? java.io.Closeable)?.let { runCatching { it.close() } }
            ensureActive()
            checkCurrent()
            throw failure
        } finally {
            synchronized(lock) { featureJobs.remove(job) }
        }
    }

    fun isCurrent(expected: AccountIdentity): Boolean = synchronized(lock) {
        (state.value as? AuthState.Authenticated)?.identity == expected
    }

    fun start() {
        val job = synchronized(lock) {
            if (initialized) return
            initialized = true
            val token = advance(AuthState.Starting)
            if (policyLoaded) operationLocked { restoreStored(token) }
            else scope.launch(io, start = CoroutineStart.LAZY) {
                // Policy initialization survives a concurrent explicit sign-out. The old
                // generation's session read does not. No bootstrap is allowed until known.
                val offline = try { requireNotNull(manualOfflineStore).read() } catch (_: Exception) {
                    synchronized(lock) { initialized = false; publish(token, AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE)) }
                    return@launch
                }
                synchronized(lock) {
                    mutableOffline.value = offline
                    policyLoaded = true
                    if (generation == token) operationLocked { restoreStored(token) } else null
                }?.start()
            }
        }
        job.start()
    }

    private suspend fun restoreStored(token: Long) {
        val record = try { readSession() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish(token, AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE))
            return
        }
        if (record == null) publish(token, AuthState.SignedOut()) else restore(token, record, null)
    }

    /**
     * Invoke from the mode controller, outside session-owned network work. Persist first:
     * a failed write does not advertise a successful switch. Once committed, caller
     * cancellation cannot strand half a transition. The optional cooperative handoff may
     * use an existing socket only; it must check actual playback ownership itself.
     */
    suspend fun setManualOffline(enabled: Boolean, beforeDisconnect: suspend (AccountIdentity) -> Unit = {}) {
        modeChanges.withLock {
            synchronized(lock) {
                check(policyLoaded) { "Session policy not loaded" }
                if (mutableOffline.value == enabled) return
            }
            withContext(io + NonCancellable) {
                checkNotNull(manualOfflineStore) { "No durable manual mode store" }.write(enabled)
                val owner = synchronized(lock) {
                    handingOff = true
                    mutableGrant.value?.identity
                }
                try {
                    if (enabled && owner != null) withTimeoutOrNull(1_000) { beforeDisconnect(owner) }
                } finally {
                    val transition = synchronized(lock) { switchModeLocked(enabled) }
                    try { transition.transport?.closeRetiredStreams() } finally {
                        transition.retired.joinAll()
                        transition.next?.start()
                    }
                }
            }
        }
    }

    private class Transition(val transport: FeatureTransport?, val retired: List<Job>, val next: Job?)

    private fun switchModeLocked(enabled: Boolean): Transition {
        val retired = authJobs.toList() + featureJobs.toList()
        val transport = api as? FeatureTransport
        val current = state.value
        val recovering = current is AuthState.Starting || current is AuthState.Validating || current is AuthState.Unavailable
        val target = when {
            current is AuthState.Authenticated || current is AuthState.SignedOut -> current
            recovering -> AuthState.Starting
            else -> AuthState.SignedOut() // unfinished login is never promoted locally
        }
        val token = advance(target)
        retired.forEach { it.cancel() } // includes independent remote logouts
        mutableOffline.value = enabled
        handingOff = false
        val record = saved
        val previous = identity
        val next = when {
            enabled && current is AuthState.Authenticated -> null // retain exact identity and verified record
            record != null && (current is AuthState.Authenticated || recovering) -> operationLocked { restore(token, record, previous) }
            recovering -> operationLocked { restoreStored(token) }
            else -> null
        }
        return Transition(transport, retired, next)
    }

    /** Secrets stay out of StateFlow; reads wait for already accepted saves, never on Main. */
    suspend fun rememberedLogin(): RememberedLogin? = withContext(io) {
        val pending = synchronized(lock) { persistenceTail }
        pending?.join()
        ensureActive()
        logins.read()
    }

    fun signIn(server: String, username: String, password: String) {
        val job = synchronized(lock) {
            if (!bootstrapAllowed) return
            val origin = try { ServerOrigin.parse(server) } catch (_: IllegalArgumentException) {
                advance(AuthState.SignedOut(AuthNotice.INVALID_ORIGIN))
                return
            }
            val token = advance(AuthState.SigningIn)
            saved = null
            identity = null
            val client = apis.create(origin).also { api = it }
            operationLocked {
                try {
                    val login = client.login(username, password)
                    ensureActive()
                    val cookie = requireNotNull(login.cookie)
                    val validated = client.session(cookie)
                    require(validated.session.accountId == login.session.accountId)
                    val record = StoredSession(origin.value, validated.cookie ?: cookie, validated.session, validated.mode ?: login.mode)
                    ensureActive()
                    val write = synchronized(lock) {
                        if (token != generation) return@operationLocked
                        // Linearization point: a fully successful CURRENT login, not a pending attempt.
                        promote(record, token)
                        persistLocked(record, RememberedLogin(origin.value, validated.session.username, password))
                    }
                    write.start()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val notice = if (error is AuthFailure && error.kind == AuthFailure.Kind.UNAUTHORIZED)
                        AuthNotice.INVALID_CREDENTIALS else AuthNotice.CONNECTION_FAILED
                    publish(token, AuthState.SignedOut(notice))
                }
            }
        }
        job.start()
    }

    /** Explicit recovery, never login/password replay. */
    fun retry() {
        val job = synchronized(lock) {
            if (!policyLoaded) { start(); return }
            if (state.value is AuthState.Authenticated && mutableGrant.value == null) { refresh(); return }
            if (handingOff || state.value !is AuthState.Unavailable) return
            val record = saved
            val previous = identity
            val token = advance(AuthState.Validating)
            operationLocked {
                val candidate = record ?: try { readSession() } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    publish(token, AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE))
                    return@operationLocked
                }
                if (candidate == null) publish(token, AuthState.SignedOut()) else restore(token, candidate, previous)
            }
        }
        job.start()
    }

    fun useManualLogin() = synchronized(lock) {
        advance(AuthState.SignedOut())
        saved = null
        identity = null
    }

    /** Main-safe even while Keystore/SharedPreferences is blocked in the durable lane. */
    fun refresh() {
        val job = synchronized(lock) {
            val current = state.value as? AuthState.Authenticated ?: return
            if (!bootstrapAllowed) return
            val validatingReturn = mutableGrant.value == null
            if (validatingReturn && operation?.isCompleted == false) return
            val record = saved ?: return
            val previous = identity
            val token = advance(if (validatingReturn) current else AuthState.Validating)
            operationLocked { restore(token, record, previous) }
        }
        job.start()
    }

    fun signOut() {
        val (clear, job) = synchronized(lock) {
            val retired = saved.takeIf { bootstrapAllowed }
            val token = advance(AuthState.SignedOut())
            saved = null
            identity = null
            val clear = persistLocked(null)
            // Separate from the cancellable auth operation and from the durable lane.
            // The durable clear must start even if a mode switch cancels logout before dispatch.
            clear to trackAuthLocked(scope.launch(io, start = CoroutineStart.LAZY) {
                val localFailure = !clear.await()
                val remoteFailure = if (retired == null) false else {
                    val logout = apis.create(ServerOrigin.parse(retired.server))
                    try { logout.logout(retired.cookie, retired.session.csrfToken); false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { true }
                    finally { logout.cancel() }
                }
                if (!localFailure && remoteFailure) publish(token, AuthState.SignedOut(AuthNotice.REMOTE_LOGOUT_FAILED))
            })
        }
        clear.start()
        job.start()
    }

    private suspend fun restore(token: Long, record: StoredSession, previous: AccountIdentity?) {
        try {
            val origin = ServerOrigin.parse(record.server)
            record.session.validated()
            val localOnly = synchronized(lock) { if (token != generation) return; mutableOffline.value }
            if (localOnly) {
                // Only the installed authenticated SessionStore, never the remembered-login
                // record. Validate its structure without constructing ANY transport.
                parseSessionCookie(origin, record.cookie)
                require(record.sessionMode == null || record.sessionMode == "rolling-v1")
                synchronized(lock) {
                    if (token == generation) promote(StoredSession(origin.value, record.cookie, record.session,
                        record.sessionMode), previous?.incarnation ?: token)
                }
                return
            }
            val client = apis.create(origin)
            client.validateCookie(record.cookie)
            synchronized(lock) {
                if (token != generation) { client.cancel(); return }
                saved = record
                api = client
            }
            val response = client.session(record.cookie)
            if (response.session.accountId != record.session.accountId) throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
            val updated = StoredSession(origin.value, response.cookie ?: record.cookie, response.session, response.mode ?: record.sessionMode)
            currentCoroutineContext().ensureActive()
            val write = synchronized(lock) {
                if (token != generation) return
                promote(updated, previous?.incarnation ?: token)
                persistLocked(updated)
            }
            write.start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val clear = synchronized(lock) {
                if (token != generation) return
                if (error is AuthFailure && error.kind == AuthFailure.Kind.UNAUTHORIZED) {
                    saved = null
                    identity = null
                    mutableState.value = signedOutState(AuthNotice.SESSION_EXPIRED)
                    persistLocked(null)
                } else {
                    // Unavailability is not revocation: preserve all recovery material.
                    val active = (state.value as? AuthState.Authenticated)?.takeIf {
                        error is AuthFailure && error.kind == AuthFailure.Kind.UNAVAILABLE
                    }
                    mutableState.value = active?.copy(notice = AuthNotice.CONNECTION_FAILED)
                        ?: AuthState.Unavailable(AuthNotice.CONNECTION_FAILED)
                    null
                }
            }
            clear?.start()
        }
    }

    private suspend fun readSession(): StoredSession? {
        val pending = synchronized(lock) { persistenceTail }
        pending?.join()
        currentCoroutineContext().ensureActive()
        return sessions.read()
    }

    /** Caller holds only the short authority monitor; LAZY jobs start AFTER it is released. */
    private fun operationLocked(block: suspend CoroutineScope.() -> Unit): Job =
        trackAuthLocked(scope.launch(io, start = CoroutineStart.LAZY, block = block)).also { operation = it }

    private fun trackAuthLocked(job: Job): Job {
        authJobs.add(job)
        job.invokeOnCompletion { synchronized(lock) { authJobs.remove(job) } }
        return job
    }

    /**
     * Acceptance-ordered durable lane, not an authority lock. Once accepted, a save
     * must finish before the next clear/save even if its network job is retired.
     * Remote logout never holds up this lane. No reads/writes delete unrelated data.
     */
    private fun persistLocked(record: StoredSession?, profile: RememberedLogin? = null): Deferred<Boolean> {
        val previous = persistenceTail
        val write = scope.async(io, start = CoroutineStart.LAZY) {
            previous?.join()
            val sessionOk = try { sessions.write(record); true } catch (_: Exception) { false }
            val profileOk = if (profile == null) true else try { logins.write(profile); true } catch (_: Exception) { false }
            synchronized(lock) {
                sessionSaveFailed = !sessionOk
                if (record == null) localInvalidationFailed = !sessionOk
                else if (sessionOk) localInvalidationFailed = false
                if (profile != null) profileSaveFailed = !profileOk
                val current = mutableState.value
                if (current is AuthState.Authenticated) {
                    // Storage health can outlive a validation generation; do not hide a
                    // failed remembered-profile save merely because foreground revalidated.
                    mutableState.value = current.copy(notice = saveNotice())
                } else if (current is AuthState.SignedOut) {
                    // A newer FAILED login does not make a failed clear restart-safe.
                    mutableState.value = if (localInvalidationFailed) signedOutState(current.notice)
                    else if (current.notice == AuthNotice.LOCAL_LOGOUT_FAILED) AuthState.SignedOut() else current
                }
            }
            sessionOk && profileOk
        }
        persistenceTail = write
        return write
    }

    private fun advance(state: AuthState): Long {
        generation++
        mutableGrant.value = null
        operation?.cancel()
        featureJobs.toList().forEach { it.cancel() }
        api?.cancel()
        api = null
        mutableState.value = if (state is AuthState.SignedOut) signedOutState(state.notice) else state
        return generation
    }

    private fun saveNotice(): AuthNotice? = if (sessionSaveFailed || profileSaveFailed) AuthNotice.SAVE_FAILED else null

    private fun promote(record: StoredSession, incarnation: Long) {
        saved = record
        identity = AccountIdentity(record.server, record.session.accountId, record.session.username, incarnation)
        mutableState.value = AuthState.Authenticated(requireNotNull(identity), saveNotice())
        if (!mutableOffline.value) mutableGrant.value = OnlineGrant(requireNotNull(identity), generation)
    }

    private fun signedOutState(notice: AuthNotice?) = AuthState.SignedOut(
        if (localInvalidationFailed) AuthNotice.LOCAL_LOGOUT_FAILED else notice,
    )

    private fun publish(token: Long, state: AuthState) = synchronized(lock) {
        if (token == generation) mutableState.value = if (state is AuthState.SignedOut) signedOutState(state.notice) else state
    }
}
