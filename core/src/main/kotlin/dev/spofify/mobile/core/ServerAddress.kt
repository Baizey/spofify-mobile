package dev.spofify.mobile.core

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
        fun parse(input: String): ServerAddress {
            require(input.none { it.isISOControl() }) { "Invalid server origin" }
            return ServerAddress(canonical(input.trim()))
        }

        private fun canonical(input: String): String {
            require(input.none { it.isWhitespace() || it.isISOControl() } && '\\' !in input) { "Invalid server origin" }
            val uri = try { URI(input) } catch (_: Exception) { throw IllegalArgumentException("Invalid server origin") }
            require(uri.scheme?.lowercase() in setOf("https", "http") && uri.rawAuthority != null &&
                uri.rawUserInfo == null && '@' !in uri.rawAuthority && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) { "Enter only an HTTP(S) origin, without credentials, path, query or fragment" }
            require(!uri.rawAuthority.endsWith(':')) { "An explicit server port must not be empty" }
            val url = try { input.toHttpUrl() } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid server origin") }
            require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials are not allowed in server origins" }
            // OkHttp treats numeric names as hostnames; resolvers may interpret shortened/octal IPv4
            // differently. Check only numeric syntax, after IDN normalization, without resolving DNS.
            if (url.host.all { it in '0'..'9' || it == '.' }) {
                val octets = url.host.split('.')
                require(octets.size == 4 && octets.all {
                    it.isNotEmpty() && it.length <= 3 && (it.length == 1 || it[0] != '0') &&
                        (it.toIntOrNull() ?: -1) in 0..255
                }) { "Use an unambiguous four-octet IPv4 address" }
            }
            return url.toString().removeSuffix("/")
        }
    }
}
