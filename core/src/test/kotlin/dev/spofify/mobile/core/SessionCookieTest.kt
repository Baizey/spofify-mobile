package dev.spofify.mobile.core

import org.junit.Assert.*
import org.junit.Test

class SessionCookieTest {
    private val https = ServerAddress.parse("https://music.example")
    private val http = ServerAddress.parse("http://192.168.1.5:8080")

    @Test fun httpsRequiresExactHostPrefixedSecureCookie() {
        val valid = "__Host-spofify_session=token; Path=/; Secure; HttpOnly; Max-Age=3600; SameSite=Lax"
        val cookie = parseSessionCookie(https, valid)!!
        assertTrue(cookie.hostOnly)
        assertTrue(cookie.secure)
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.persistent)
        assertEquals("music.example", cookie.domain)
        assertNotNull(parseSessionCookie(https, cookie.toString()))
        listOf(
            valid.replace("__Host-spofify_session", "spofify_session"),
            valid.replace("; Secure", ""),
            valid.replace("; HttpOnly", ""),
            valid.replace("; Path=/", ""),
            valid.replace("Path=/", "Path=/api"),
            "$valid; Domain=music.example",
            "$valid; Domain=example",
            valid.replace("Max-Age=3600", "Max-Age=0"),
            valid.replace("Max-Age=3600", "Max-Age=-1"),
            valid.replace("; Max-Age=3600", ""),
            valid.replace("=token", "="),
        ).forEach { assertNull("Accepted $it", parseSessionCookie(https, it)) }
    }

    @Test fun httpRequiresUnprefixedNonSecureHostOnlyCookie() {
        val valid = "spofify_session=token; Path=/; HttpOnly; Max-Age=3600"
        assertNotNull(parseSessionCookie(http, valid))
        assertNull(parseSessionCookie(http, "$valid; Secure"))
        assertNull(parseSessionCookie(http, "$valid; Domain=192.168.1.5"))
        assertNull(parseSessionCookie(http, valid.replace("spofify_session", "__Host-spofify_session")))
    }

    @Test fun serializationStoresAbsoluteExpiryRatherThanRebasingMaxAgeOnRestore() {
        val cookie = parseSessionCookie(https, "__Host-spofify_session=token; Path=/; Secure; HttpOnly; Max-Age=3600")!!
        val serialized = cookie.toString()
        assertTrue(serialized.contains("expires=", ignoreCase = true))
        assertFalse(serialized.contains("max-age=", ignoreCase = true))
        val restored = parseSessionCookie(https, serialized)!!
        // HTTP-date has second precision; serialization must not extend the session lifetime.
        assertTrue(restored.expiresAt <= cookie.expiresAt)
        assertTrue(cookie.expiresAt - restored.expiresAt < 1000)
        assertNull(parseSessionCookie(https, "__Host-spofify_session=token; Path=/; Secure; HttpOnly; Expires=Thu, 01 Jan 1970 00:00:01 GMT"))
    }
}
