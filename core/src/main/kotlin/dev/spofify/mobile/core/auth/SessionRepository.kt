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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** HTTP session validation only; it neither proves nor monitors player connectivity. */
enum class OnlineValidation { IDLE, VALIDATING, VALIDATED, UNAVAILABLE }

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
    // Synchronous, non-blocking and non-throwing fence for installation-wide work
    // that uses no account authority. The consumer must start closed.
    private val onInstallationNetworkAllowed: (Boolean) -> Unit = {},
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private val modeChanges = Mutex()
    private val mutableOffline = MutableStateFlow(false)
    val manualOffline = mutableOffline.asStateFlow()
    private val mutableGrant = MutableStateFlow<OnlineGrant?>(null)
    val onlineGrant = mutableGrant.asStateFlow()
    private val mutableValidation = MutableStateFlow(OnlineValidation.IDLE)
    val onlineValidation = mutableValidation.asStateFlow()
    private val mutablePolicyLoaded = MutableStateFlow(manualOfflineStore == null)
    val offlinePolicyLoaded = mutablePolicyLoaded.asStateFlow()
    private var handingOff = false
    private val bootstrapAllowed get() = mutablePolicyLoaded.value && !mutableOffline.value && !handingOff
    private var generation = 0L
    private var initialized = false
    private var operation: Job? = null
    // Application startup may validate before the first Activity.onStart; only an actual stop disallows recovery.
    private var appBackgrounded = false
    private var foregroundEpoch = 0L
    private var validationRetry: Job? = null
    private var recoverable = false
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
        val local: JsonObject? = null,
        val reattachBasis: JsonObject? = null,
    )

    fun playbackTransport(expected: AccountIdentity): PlaybackTransport = PlaybackTransport { name, deviceId, local, reattachBasis ->
        require(isValidDeviceName(name))
        require(deviceId.isNotBlank() && deviceId.toByteArray().size <= 128 && deviceId.none(Char::isISOControl))
        val claim = local?.takeIf { requireNotNull(it["queue"] as? JsonArray) { "Local player has no queue" }.isNotEmpty() }
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
            val hello = featureJson.encodeToString(Hello("hello", admission.ticket, name, deviceId, true, claim, reattachBasis))
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
                    if (authority.isCurrent && sameCredential(saved, record)) {
                        advance(AuthState.Authenticated(expected, AuthNotice.CONNECTION_FAILED))
                        mutableValidation.value = OnlineValidation.UNAVAILABLE
                    }
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

    /** Serialize a final local-cache commit with session retirement. Prepare bytes before calling. */
    fun commitLocalIfCurrent(expected: AccountIdentity, commit: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrent(expected)) false else { commit(); true }
    }

    fun isCurrent(expected: AccountIdentity): Boolean = synchronized(lock) {
        (state.value as? AuthState.Authenticated)?.identity == expected
    }

    fun start() {
        val job = synchronized(lock) {
            if (initialized) return
            initialized = true
            val token = advance(AuthState.Starting)
            if (mutablePolicyLoaded.value) operationLocked { restoreStored(token) }
            else scope.launch(io, start = CoroutineStart.LAZY) {
                // Policy initialization survives a concurrent explicit sign-out. The old
                // generation's session read does not. No bootstrap is allowed until known.
                val offline = try { requireNotNull(manualOfflineStore).read() } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    publish(token, AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE))
                    return@launch
                }
                synchronized(lock) {
                    mutableOffline.value = offline
                    onInstallationNetworkAllowed(!offline)
                    mutablePolicyLoaded.value = true
                    if (generation == token) operationLocked { restoreStored(token) } else null
                }?.start()
            }
        }
        job.start()
    }

    private suspend fun restoreStored(token: Long) {
        val record = try {
            readSession()?.let { stored ->
                val origin = ServerOrigin.parse(stored.server)
                // Local authority comes from installed authenticated storage, never a saved password.
                // Check structure without constructing a transport or treating the cookie's age as revocation.
                parseSessionCookie(origin, stored.cookie)
                stored.session.validated()
                require(stored.sessionMode == null || stored.sessionMode == "rolling-v1")
                StoredSession(origin.value, stored.cookie, stored.session, stored.sessionMode)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish(token, AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE))
            return
        }
        if (record == null) {
            publish(token, AuthState.SignedOut())
            return
        }
        currentCoroutineContext().ensureActive()
        val validate = synchronized(lock) {
            if (token != generation || handingOff) return
            installLocalLocked(record, token)
            bootstrapAllowed.also { if (it) mutableValidation.value = OnlineValidation.VALIDATING }
        }
        if (validate) validateInstalled(token, record)
    }

    /**
     * Invoke from the mode controller, outside session-owned network work. Entry fences
     * authority before persistence; only a successful write changes the preference. The
     * optional callback coordinates local output only, after the network gate is closed.
     */
    suspend fun setManualOffline(enabled: Boolean, beforeDisconnect: suspend (AccountIdentity) -> Unit = {}) {
        changeManualMode(enabled, beforeDisconnect)
    }

    private suspend fun changeManualMode(
        enabled: Boolean, beforeDisconnect: suspend (AccountIdentity) -> Unit,
    ): Unit = modeChanges.withLock {
        val alreadySet = synchronized(lock) {
            check(mutablePolicyLoaded.value) { "Session policy not loaded" }
            mutableOffline.value == enabled
        }
        if (alreadySet) {
            // Explicit Go online also starts a fresh batch after a terminal HTTP failure in auto mode.
            if (!enabled) synchronized(lock) {
                if (bootstrapAllowed && mutableGrant.value == null && mutableValidation.value != OnlineValidation.VALIDATING)
                    validateLocalLocked() else null
            }?.start()
            return@withLock
        }
        val store = checkNotNull(manualOfflineStore) { "No durable manual mode store" }
        // Admission closes both gates before queuing the potentially blocked preference write.
        val entry = if (enabled) synchronized(lock) {
            onInstallationNetworkAllowed(false)
            handingOff = true
            val grant = mutableGrant.value
            val validating = mutableValidation.value == OnlineValidation.VALIDATING
            mutableGrant.value = null
            val token = ++generation
            (authJobs.toList() + featureJobs.toList()).forEach { it.cancel() }
            api?.cancel()
            ModeEntry(token, grant?.identity, validating, api as? FeatureTransport)
        } else null
        withContext(io + NonCancellable) {
            try {
                entry?.transport?.closeRetiredStreams()
                store.write(enabled)
            } catch (failure: Exception) {
                if (entry != null) {
                    val next = synchronized(lock) {
                        handingOff = false
                        onInstallationNetworkAllowed(true)
                        if (generation != entry.generation) null else when (val current = state.value) {
                            is AuthState.Authenticated -> when {
                                current.identity == entry.owner && api != null -> {
                                    mutableGrant.value = OnlineGrant(current.identity, generation)
                                    null
                                }
                                entry.validating -> validationLocked(generation, requireNotNull(saved))
                                else -> null
                            }
                            AuthState.Starting, AuthState.Validating -> operationLocked { restoreStored(entry.generation) }
                            AuthState.SigningIn -> { mutableState.value = signedOutState(null); null }
                            else -> null
                        }
                    }
                    next?.start()
                }
                throw failure
            }
            try {
                entry?.owner?.takeIf(::isCurrent)?.let { owner -> withTimeoutOrNull(1_000) { beforeDisconnect(owner) } }
            } finally {
                val transition = synchronized(lock) { switchModeLocked(enabled) }
                if (!enabled) onInstallationNetworkAllowed(true)
                completeModeSwitch(transition)
            }
        }
    }

    private class ModeEntry(val generation: Long, val owner: AccountIdentity?, val validating: Boolean,
        val transport: FeatureTransport?)

    private suspend fun completeModeSwitch(transition: Transition) {
        try { transition.transport?.closeRetiredStreams() } finally {
            transition.retired.joinAll()
            transition.next?.start()
        }
    }

    private class Transition(val transport: FeatureTransport?, val retired: List<Job>, val next: Job?)

    private fun switchModeLocked(enabled: Boolean): Transition {
        val retired = authJobs.toList() + featureJobs.toList()
        val transport = api as? FeatureTransport
        val current = state.value
        val recovering = current is AuthState.Starting || current is AuthState.Validating || current is AuthState.Unavailable
        val target = when {
            current is AuthState.Authenticated -> current.copy(notice = saveNotice())
            current is AuthState.SignedOut -> current
            recovering -> AuthState.Starting
            else -> AuthState.SignedOut() // unfinished login is never promoted locally
        }
        val token = advance(target)
        retired.forEach { it.cancel() } // includes independent remote logouts
        mutableOffline.value = enabled
        handingOff = false
        val next = when {
            current is AuthState.Authenticated -> if (enabled) null else validationLocked(token, requireNotNull(saved))
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

    fun useManualLogin(): Unit = synchronized(lock) {
        if (!mutablePolicyLoaded.value) return
        advance(AuthState.SignedOut())
        saved = null
        identity = null
    }

    /** Bootstrap permits one bounded sequence; after the first stop, recovery needs the foreground. */
    fun setAppForeground(foreground: Boolean): Unit = synchronized(lock) {
        appBackgrounded = !foreground
        if (!foreground) {
            foregroundEpoch++
            validationRetry?.cancel(ValidationBackgrounded())
        }
    }

    /** Independent reachability probe after repeated player handshake failures. Only a
     * transient session transport failure retires the grant; a broken endpoint cannot. */
    suspend fun confirmServiceAfterSocketFailures(grant: OnlineGrant) {
        try { withOnlineGrant(grant) { renew(grant.identity) } }
        catch (failure: AuthFailure) {
            if (!failure.canRetryValidation()) return
            synchronized(lock) {
                val current = state.value as? AuthState.Authenticated ?: return
                if (mutableGrant.value !== grant || !bootstrapAllowed) return
                advance(current.copy(notice = AuthNotice.CONNECTION_FAILED))
                recoverable = true
                mutableValidation.value = OnlineValidation.UNAVAILABLE
            }
        }
    }

    /** App-owned recovery timer or connectivity hint, never a screen-triggered retry. */
    fun recoverUnavailable() {
        val job = synchronized(lock) {
            if (!bootstrapAllowed || appBackgrounded || !recoverable || mutableValidation.value != OnlineValidation.UNAVAILABLE) return
            validateLocalLocked()
        }
        job?.start()
    }

    /** Main-safe foreground revalidation of a live grant. */
    fun refresh() {
        val job = synchronized(lock) {
            if (!bootstrapAllowed || mutableGrant.value == null) return
            validateLocalLocked()
        }
        job?.start()
    }

    private fun validateLocalLocked(): Job? {
        val current = state.value as? AuthState.Authenticated ?: return null
        val record = saved ?: return null
        return validationLocked(advance(current.copy(notice = saveNotice())), record)
    }

    private fun validationLocked(token: Long, record: StoredSession): Job {
        mutableValidation.value = OnlineValidation.VALIDATING
        return operationLocked { validateInstalled(token, record) }
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

    private suspend fun validateInstalled(token: Long, record: StoredSession) {
        var retryEpoch: Long? = null
        try {
            synchronized(lock) { if (token != generation || !bootstrapAllowed) return }
            val origin = ServerOrigin.parse(record.server)
            val client = apis.create(origin)
            synchronized(lock) {
                if (token != generation || !bootstrapAllowed) { client.cancel(); return }
                api = client
            }
            require(client.origin == origin)
            client.validateCookie(record.cookie)
            val validation = validateStoredSession(token, client, record.cookie)
            retryEpoch = validation.retryEpoch
            val response = validation.result.getOrThrow()
            if (response.session.accountId != record.session.accountId) throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
            val updated = StoredSession(origin.value, response.cookie ?: record.cookie, response.session, response.mode ?: record.sessionMode)
            currentCoroutineContext().ensureActive()
            val write = synchronized(lock) {
                if (token != generation || !bootstrapAllowed) return
                if (retryEpoch != null && retryEpoch != foregroundEpoch) throw AuthFailure(AuthFailure.Kind.UNAVAILABLE)
                promote(updated, requireNotNull(identity).incarnation)
                persistLocked(updated)
            }
            write.start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val clear = synchronized(lock) {
                if (token != generation || !bootstrapAllowed) return
                // A child can finish before onStop yet have its result dispatched afterward.
                val retiredRetry = retryEpoch != null && retryEpoch != foregroundEpoch
                if (!retiredRetry && error is AuthFailure && error.kind == AuthFailure.Kind.UNAUTHORIZED) {
                    advance(AuthState.SignedOut(AuthNotice.SESSION_EXPIRED))
                    saved = null
                    identity = null
                    persistLocked(null)
                } else {
                    // Even invalid/mismatched server responses do not invalidate installed local authority.
                    // Never expose a network grant for them, and do not overwrite the local record.
                    api?.cancel()
                    api = null
                    mutableState.value = AuthState.Authenticated(requireNotNull(identity), AuthNotice.CONNECTION_FAILED)
                    recoverable = error is AuthFailure && error.canRetryValidation()
                    mutableValidation.value = OnlineValidation.UNAVAILABLE
                    null
                }
            }
            clear?.start()
        }
    }

    private class ValidationBackgrounded : CancellationException("App no longer foreground")
    private class StoredValidation(val result: Result<AuthReply>, val retryEpoch: Long? = null)

    private fun AuthFailure.canRetryValidation(): Boolean = kind == AuthFailure.Kind.UNAVAILABLE && transient

    private suspend fun validateStoredSession(token: Long, client: AuthApi, cookie: String): StoredValidation {
        val initialFailure = try { return StoredValidation(Result.success(client.session(cookie))) } catch (failure: AuthFailure) {
            if (!failure.canRetryValidation()) throw failure
            failure
        }
        // One child owns both the delays and the retried calls. Backgrounding cancels just that
        // child so restore can report failure; retiring auth authority cancels the whole operation.
        return supervisorScope {
            val (epoch, retry) = synchronized(lock) {
                if (token != generation || !bootstrapAllowed || appBackgrounded) throw initialFailure
                foregroundEpoch to async(start = CoroutineStart.LAZY) {
                    var lastFailure = initialFailure
                    repeat(3) { attempt ->
                        delay(1_000L shl attempt)
                        synchronized(lock) {
                            ensureActive()
                            if (token != generation || !bootstrapAllowed) throw lastFailure
                        }
                        try { return@async client.session(cookie) } catch (failure: AuthFailure) {
                            if (!failure.canRetryValidation()) throw failure
                            lastFailure = failure
                        }
                    }
                    throw lastFailure
                }.also { validationRetry = it }
            }
            val result = try {
                Result.success(retry.await())
            } catch (_: ValidationBackgrounded) {
                ensureActive()
                Result.failure(initialFailure)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            } finally {
                synchronized(lock) { if (validationRetry === retry) validationRetry = null }
            }
            StoredValidation(result, epoch)
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
                    // Keep the current connection failure visible if an older write finishes.
                    // Storage health is still retained for the next successful validation.
                    if (current.notice != AuthNotice.CONNECTION_FAILED)
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
        mutableValidation.value = OnlineValidation.IDLE
        recoverable = false
        operation?.cancel()
        featureJobs.toList().forEach { it.cancel() }
        api?.cancel()
        api = null
        mutableState.value = if (state is AuthState.SignedOut) signedOutState(state.notice) else state
        return generation
    }

    private fun saveNotice(): AuthNotice? = if (sessionSaveFailed || profileSaveFailed) AuthNotice.SAVE_FAILED else null

    private fun installLocalLocked(record: StoredSession, incarnation: Long) {
        saved = record
        // Keep native/cache ownership stable if the server updated mutable session metadata.
        val current = identity?.takeIf { it.incarnation == incarnation && it.origin == record.server && it.accountId == record.session.accountId }
        val installed = current ?: AccountIdentity(record.server, record.session.accountId, record.session.username, incarnation)
        identity = installed
        mutableState.value = AuthState.Authenticated(installed, saveNotice())
    }

    private fun promote(record: StoredSession, incarnation: Long) {
        installLocalLocked(record, incarnation)
        if (bootstrapAllowed) {
            mutableGrant.value = OnlineGrant(requireNotNull(identity), generation)
            mutableValidation.value = OnlineValidation.VALIDATED
        }
    }

    private fun signedOutState(notice: AuthNotice?) = AuthState.SignedOut(
        if (localInvalidationFailed) AuthNotice.LOCAL_LOGOUT_FAILED else notice,
    )

    private fun publish(token: Long, state: AuthState) = synchronized(lock) {
        if (token == generation) mutableState.value = if (state is AuthState.SignedOut) signedOutState(state.notice) else state
    }
}
