package dev.spofify.mobile.core.origin

import java.net.URI
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The one credential boundary: explicit scheme, canonical host, effective port. */
class ServerOrigin private constructor(val value: String, internal val url: HttpUrl) {
    fun contains(candidate: HttpUrl): Boolean =
        candidate.scheme == url.scheme && candidate.host == url.host && candidate.port == url.port

    override fun equals(other: Any?): Boolean = other is ServerOrigin && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        fun parse(input: String): ServerOrigin {
            require(input.isNotEmpty() && input.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
            val uri = try { URI(input) } catch (_: Exception) { throw IllegalArgumentException("Invalid origin") }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https")
            val authority = requireNotNull(uri.rawAuthority)
            require(authority.isNotEmpty() && authority.none { it == '@' || it == '%' } && !authority.endsWith(':'))
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
            val url = requireNotNull(input.toHttpUrlOrNull())
            require(url.username.isEmpty() && url.password.isEmpty())
            val host = if (':' in url.host) "[${url.host}]" else url.host
            val port = if (url.port == if (scheme == "https") 443 else 80) "" else ":${url.port}"
            return ServerOrigin("$scheme://$host$port", url.newBuilder().encodedPath("/").build())
        }
    }
}
