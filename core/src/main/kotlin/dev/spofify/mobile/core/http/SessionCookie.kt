package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.origin.ServerOrigin
import okhttp3.Cookie

internal fun cookieName(origin: ServerOrigin) = if (origin.url.isHttps) "__Host-spofify_session" else "spofify_session"

/** Pure validation shared by transport and authenticated local restore. No client, DNS or clock-based revocation. */
internal fun parseSessionCookie(origin: ServerOrigin, raw: String, requireAbsoluteExpiry: Boolean = false): Cookie {
    // Strip ONLY Max-Age: Cookie.parse would otherwise rebase it on the device clock.
    val parts = raw.split(';')
    val attributes = parts.drop(1)
    fun String.named(name: String) = substringBefore('=').trim().equals(name, ignoreCase = true)
    val expires = attributes.filter { it.named("Expires") }
    val maxAge = attributes.filter { it.named("Max-Age") }
    if (expires.size > 1 || maxAge.size > 1 ||
        (maxAge.isNotEmpty() && (expires.isEmpty() ||
            maxAge.single().substringAfter('=', "").trim().toLongOrNull()?.let { it > 0 } != true))) {
        throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
    }
    val absolute = parts.filterIndexed { index, part -> index == 0 || !part.named("Max-Age") }.joinToString(";")
    val cookie = Cookie.parse(origin.url, absolute) ?: throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
    if ((requireAbsoluteExpiry || expires.isNotEmpty()) && (expires.size != 1 || !cookie.persistent)) {
        throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
    }
    if (cookie.name != cookieName(origin) || cookie.value.isEmpty() || !cookie.hostOnly ||
        cookie.domain != origin.url.host || cookie.path != "/" || !cookie.httpOnly ||
        cookie.secure != origin.url.isHttps || cookie.expiresAt <= 0L) {
        throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
    }
    return cookie
}
