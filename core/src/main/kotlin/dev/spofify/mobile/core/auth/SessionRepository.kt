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
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
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

    fun isCurrent(expected: AccountIdentity): Boolean = synchronized(lock) {
        (state.value as? AuthState.Authenticated)?.identity == expected
    }

    fun start() {
        val job = synchronized(lock) {
            if (initialized) return
            initialized = true
            val token = advance(AuthState.Starting)
            operationLocked {
                val record = try { readSession() } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    publish(token, AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE))
                    return@operationLocked
                }
                if (record == null) publish(token, AuthState.SignedOut()) else restore(token, record, null)
            }
        }
        job.start()
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
            if (state.value !is AuthState.Unavailable) return
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
            if (state.value !is AuthState.Authenticated) return
            val record = saved ?: return
            val previous = identity
            val token = advance(AuthState.Validating)
            operationLocked { restore(token, record, previous) }
        }
        job.start()
    }

    fun signOut() {
        val job = synchronized(lock) {
            val retired = saved
            val token = advance(AuthState.SignedOut())
            saved = null
            identity = null
            val clear = persistLocked(null)
            // Separate from the cancellable auth operation and from the durable lane.
            scope.launch(io, start = CoroutineStart.LAZY) {
                clear.start()
                val localFailure = !clear.await()
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
        job.start()
    }

    private suspend fun restore(token: Long, record: StoredSession, previous: AccountIdentity?) {
        try {
            val origin = ServerOrigin.parse(record.server)
            val client = apis.create(origin)
            client.validateCookie(record.cookie)
            record.session.validated()
            synchronized(lock) {
                if (token != generation) { client.cancel(); return }
                saved = record
                api = client
            }
            val response = client.session(record.cookie)
            require(response.session.accountId == record.session.accountId)
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
                    mutableState.value = AuthState.Unavailable(AuthNotice.CONNECTION_FAILED)
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
        scope.launch(io, start = CoroutineStart.LAZY, block = block).also { operation = it }

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
        operation?.cancel()
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
    }

    private fun signedOutState(notice: AuthNotice?) = AuthState.SignedOut(
        if (localInvalidationFailed) AuthNotice.LOCAL_LOGOUT_FAILED else notice,
    )

    private fun publish(token: Long, state: AuthState) = synchronized(lock) {
        if (token == generation) mutableState.value = if (state is AuthState.SignedOut) signedOutState(state.notice) else state
    }
}
