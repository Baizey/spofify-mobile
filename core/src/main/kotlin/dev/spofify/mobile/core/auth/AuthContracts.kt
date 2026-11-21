package dev.spofify.mobile.core.auth

import dev.spofify.mobile.core.origin.ServerOrigin
import java.io.IOException
import kotlinx.serialization.Serializable

@Serializable
class SessionSummary(
    val accountId: String,
    val username: String,
    val role: String,
    val csrfToken: String,
    val sessionMode: String? = null,
) {
    fun validated(): SessionSummary = apply {
        require(accountId.isNotBlank() && username.isNotBlank() && csrfToken.isNotBlank())
        require(role == "user" || role == "admin")
        require(sessionMode == null || sessionMode == "rolling-v1")
        require(csrfToken.none { it.isISOControl() })
    }
    override fun toString(): String = "SessionSummary(redacted)"
}

/** Shape remains compatible with the installed encrypted session JSON. Never UI state. */
@Serializable
class StoredSession(
    val server: String,
    val cookie: String,
    val session: SessionSummary,
    val sessionMode: String? = null,
) {
    override fun toString(): String = "StoredSession(redacted)"
}

/** Only the last fully successful triple. Not a preference for failed attempts. */
@Serializable
class RememberedLogin(val server: String, val username: String, val password: String) {
    override fun toString(): String = "RememberedLogin(redacted)"
}

interface SessionStore {
    fun read(): StoredSession?
    fun write(value: StoredSession?)
}

interface LoginStore {
    fun read(): RememberedLogin?
    fun write(value: RememberedLogin)
}

class AuthFailure(val kind: Kind, val transient: Boolean = false) : IOException("Authentication request failed") {
    enum class Kind { UNAUTHORIZED, UNAVAILABLE, INVALID_RESPONSE }
}

class AuthReply(val session: SessionSummary, val cookie: String?, val mode: String?)

interface AuthApi {
    val origin: ServerOrigin
    suspend fun login(username: String, password: String): AuthReply
    suspend fun session(cookie: String): AuthReply
    suspend fun logout(cookie: String, csrfToken: String)
    fun cancel()
    /** Validates a saved cookie without deriving local revocation from its deadline. */
    fun validateCookie(cookie: String)
}

fun interface AuthApiFactory {
    fun create(origin: ServerOrigin): AuthApi
}
