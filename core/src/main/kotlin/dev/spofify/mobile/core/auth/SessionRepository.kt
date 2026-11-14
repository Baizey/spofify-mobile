package dev.spofify.mobile.core.auth

import dev.spofify.mobile.core.origin.ServerOrigin
import kotlinx.coroutines.*
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
 * App-lifetime owner. All authority and disk publications share one monitor;
 * network never holds it. Every async completion must still own its generation.
 * Cookies are explicit request/response values, never automatic CookieJar writes.
 * No auth transition deletes offline files, receipts, keys or remembered login.
 */
class SessionRepository(
    private val sessions: SessionStore,
    private val logins: LoginStore,
    private val apis: AuthApiFactory,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private var generation = 0L
    private var durableGeneration = 0L
    private var initialized = false
    private var operation: Job? = null
    private var api: AuthApi? = null
    private var saved: StoredSession? = null
    private var identity: AccountIdentity? = null

    /** For future authenticated work: callers capture identity; cancellation is not publication authority. */
    fun isCurrent(expected: AccountIdentity): Boolean = synchronized(lock) {
        (state.value as? AuthState.Authenticated)?.identity == expected
    }

    fun start(): Unit = synchronized(lock) {
        if (initialized) return@synchronized
        initialized = true
        val token = advance(AuthState.Starting)
        operation = scope.launch(io) {
            val record = try {
                synchronized(lock) { if (token == generation) sessions.read() else null }
            } catch (_: Exception) {
                publish(token, AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE))
                return@launch
            }
            if (record == null) {
                publish(token, AuthState.SignedOut())
            } else {
                restore(token, record, null)
            }
        }
    }

    /** Reads contain secrets and never enter StateFlow. A corrupt record is preserved, not repaired. */
    suspend fun rememberedLogin(): RememberedLogin? = withContext(io) { synchronized(lock) { logins.read() } }

    fun signIn(server: String, username: String, password: String): Unit = synchronized(lock) {
        val origin = try { ServerOrigin.parse(server) } catch (_: IllegalArgumentException) {
            advance(AuthState.SignedOut(AuthNotice.INVALID_ORIGIN))
            return@synchronized
        }
        val token = advance(AuthState.SigningIn)
        saved = null
        identity = null
        val client = apis.create(origin).also { api = it }
        operation = scope.launch(io) {
            try {
                val login = client.login(username, password)
                val cookie = requireNotNull(login.cookie)
                val validated = client.session(cookie)
                require(validated.session.accountId == login.session.accountId)
                val record = StoredSession(origin.value, validated.cookie ?: cookie, validated.session, validated.mode ?: login.mode)
                synchronized(lock) {
                    if (token != generation) return@synchronized
                    coroutineContext.ensureActive()
                    var notice: AuthNotice? = null
                    // Commit only after both responses were validated. Failure preserves prior durable values.
                    try { sessions.write(record); durableGeneration = token } catch (_: Exception) { notice = AuthNotice.SAVE_FAILED }
                    try { logins.write(RememberedLogin(origin.value, validated.session.username, password)) }
                    catch (_: Exception) { notice = AuthNotice.SAVE_FAILED }
                    promote(record, token, notice)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val notice = if (error is AuthFailure && error.kind == AuthFailure.Kind.UNAUTHORIZED)
                    AuthNotice.INVALID_CREDENTIALS else AuthNotice.CONNECTION_FAILED
                publish(token, AuthState.SignedOut(notice))
            }
        }
    }

    /** Explicit recovery, not login/password replay. No timer or hidden network retry. */
    fun retry(): Unit = synchronized(lock) {
        if (state.value !is AuthState.Unavailable) return@synchronized
        val record = saved
        val previous = identity
        val token = advance(AuthState.Validating)
        operation = scope.launch(io) {
            val candidate = if (record != null) record else try {
                synchronized(lock) { if (token == generation) sessions.read() else null }
            } catch (_: Exception) {
                publish(token, AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE))
                return@launch
            }
            if (candidate == null) publish(token, AuthState.SignedOut()) else restore(token, candidate, previous)
        }
    }

    fun useManualLogin(): Unit = synchronized(lock) {
        advance(AuthState.SignedOut())
        saved = null
        identity = null
    }

    /** Revalidate on foreground entry, once; failures fence access but retain recovery material. */
    fun refresh(): Unit = synchronized(lock) {
        if (state.value !is AuthState.Authenticated) return@synchronized
        val record = saved ?: return@synchronized
        val previous = identity
        val token = advance(AuthState.Validating)
        operation = scope.launch(io) { restore(token, record, previous) }
    }

    /** Immediate local fence; bounded remote logout uses ONLY the retired cookie and CSRF. */
    fun signOut(): Unit = synchronized(lock) {
        val retired = saved
        val token = advance(AuthState.SignedOut())
        saved = null
        identity = null
        scope.launch(io) {
            val localFailure = synchronized(lock) {
                // A delayed clear must never erase a successfully persisted successor.
                if (durableGeneration > token) false else try {
                    sessions.write(null)
                    durableGeneration = token
                    false
                } catch (_: Exception) { true }
            }
            if (localFailure) publish(token, AuthState.SignedOut(AuthNotice.LOCAL_LOGOUT_FAILED))
            val remoteFailure = if (retired == null) false else {
                val logout = apis.create(ServerOrigin.parse(retired.server))
                try { logout.logout(retired.cookie, retired.session.csrfToken); false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { true }
                finally { logout.cancel() }
            }
            if (!localFailure && remoteFailure) publish(token, AuthState.SignedOut(AuthNotice.REMOTE_LOGOUT_FAILED))
        }
    }

    private suspend fun restore(token: Long, record: StoredSession, previous: AccountIdentity?) {
        val context = currentCoroutineContext()
        try {
            val origin = ServerOrigin.parse(record.server)
            val client = apis.create(origin)
            synchronized(lock) {
                if (token != generation) { client.cancel(); return }
                client.validateCookie(record.cookie)
                record.session.validated()
                saved = record
                api = client
            }
            val response = client.session(record.cookie)
            require(response.session.accountId == record.session.accountId)
            val updated = StoredSession(origin.value, response.cookie ?: record.cookie, response.session, response.mode ?: record.sessionMode)
            synchronized(lock) {
                if (token != generation) return
                context.ensureActive()
                var notice: AuthNotice? = null
                try { sessions.write(updated); durableGeneration = token }
                catch (_: Exception) { notice = AuthNotice.SAVE_FAILED }
                promote(updated, previous?.incarnation ?: token, notice)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(lock) {
                if (token != generation) return
                if (error is AuthFailure && error.kind == AuthFailure.Kind.UNAUTHORIZED) {
                    saved = null
                    identity = null
                    var notice = AuthNotice.SESSION_EXPIRED
                    try { sessions.write(null); durableGeneration = token }
                    catch (_: Exception) { notice = AuthNotice.LOCAL_LOGOUT_FAILED }
                    mutableState.value = AuthState.SignedOut(notice)
                } else {
                    // Corrupt storage, TLS, DNS, 5xx, parser errors and mismatched identity
                    // are NOT authoritative revocation. Preserve every durable byte.
                    mutableState.value = AuthState.Unavailable(AuthNotice.CONNECTION_FAILED)
                }
            }
        }
    }

    /** Must hold lock. Retires the in-flight auth operation before replacing authority. */
    private fun advance(state: AuthState): Long {
        generation++
        operation?.cancel()
        api?.cancel()
        api = null
        mutableState.value = state
        return generation
    }

    private fun promote(record: StoredSession, incarnation: Long, notice: AuthNotice?) {
        saved = record
        identity = AccountIdentity(record.server, record.session.accountId, record.session.username, incarnation)
        mutableState.value = AuthState.Authenticated(requireNotNull(identity), notice)
    }

    private fun publish(token: Long, state: AuthState) = synchronized(lock) {
        if (token == generation) mutableState.value = state
    }
}
