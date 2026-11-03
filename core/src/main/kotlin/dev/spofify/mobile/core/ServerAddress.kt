package dev.spofify.mobile.core

import java.net.InetAddress
import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** An exact canonical origin; validation never performs a DNS lookup. */
data class ServerAddress(val origin: String) {
    init {
        require(canonical(origin) == origin) { "Use ServerAddress.parse for a canonical server origin" }
    }

    fun url(vararg segments: String): HttpUrl = origin.toHttpUrl().newBuilder().apply {
        segments.forEach {
            require(it.isNotEmpty() && it != "." && it != "..") { "Invalid empty or dot path segment" }
            // addPathSegment, not addEncodedPathSegment: IDs are opaque, including percent signs.
            addPathSegment(it)
        }
    }.build()

    internal fun contains(url: HttpUrl): Boolean {
        val base = origin.toHttpUrl()
        return url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.username.isEmpty() && url.password.isEmpty()
    }

    companion object {
        fun parse(input: String): ServerAddress = ServerAddress(canonical(input.trim()))

        private fun canonical(input: String): String {
            require(input.none { it.isWhitespace() || it.isISOControl() } && '\\' !in input) { "Invalid server origin" }
            val uri = try { URI(input) } catch (_: Exception) { throw IllegalArgumentException("Invalid server origin") }
            require(uri.scheme?.lowercase() in setOf("https", "http") && uri.rawAuthority != null &&
                uri.rawUserInfo == null && '@' !in uri.rawAuthority && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) { "Enter only an HTTP(S) origin, without credentials, path, query or fragment" }
            val url = try { input.toHttpUrl() } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid server origin") }
            require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials are not allowed in server origins" }
            require(url.isHttps || privateLiteral(url.host)) { "HTTP requires a concrete private or loopback IP address; use HTTPS otherwise" }
            return url.toString().removeSuffix("/")
        }

        private fun privateLiteral(host: String): Boolean {
            if (':' in host) {
                // A restricted numeric alphabet guarantees getByName cannot perform DNS or use a zone ID.
                if (!host.all { it in "0123456789abcdefABCDEF:" }) return false
                val bytes = try { InetAddress.getByName(host).address } catch (_: Exception) { return false }
                if (bytes.size != 16) return false
                return (bytes[0].toInt() and 0xfe) == 0xfc ||
                    (bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte())
            }
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it[0] == '0') || it.any { c -> c !in '0'..'9' } }) return false
            val octets = parts.map { it.toInt() }
            if (octets.any { it !in 0..255 }) return false
            val (a, b) = octets
            return a == 127 || a == 10 || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }
    }
}
