package dev.spofify.mobile.update

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class UpdateResource { METADATA, APK }

internal fun interface UpdateTransport {
    fun newCall(url: URI, resource: UpdateResource, remainingMillis: Long): UpdateCall
}

internal interface UpdateCall {
    fun execute(): UpdateResponse
    fun cancel()
}

internal interface UpdateResponse : Closeable {
    val code: Int
    val body: InputStream
    fun headers(name: String): List<String>
}

/** No app client, account data, cookie jar, authenticator, proxy, or automatic redirects. */
internal class AnonymousUpdateTransport : UpdateTransport {
    private val client = OkHttpClient.Builder()
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun newCall(url: URI, resource: UpdateResource, remainingMillis: Long): UpdateCall {
        val call = client.newCall(updateRequest(url, resource))
        call.timeout().timeout(remainingMillis, TimeUnit.MILLISECONDS)
        return object : UpdateCall {
            override fun execute(): UpdateResponse {
                val response = call.execute()
                return object : UpdateResponse {
                    override val code get() = response.code
                    override val body get() = response.body.byteStream()
                    override fun headers(name: String) = response.headers(name)
                    override fun close() = response.close()
                }
            }

            override fun cancel() = call.cancel()
        }
    }
}

internal fun updateRequest(url: URI, resource: UpdateResource): Request = Request.Builder()
    .url(url.toString())
    .get()
    .header("User-Agent", "Spofify-Mobile-Updater")
    .header("Accept-Encoding", "identity")
    .apply {
        when (resource) {
            UpdateResource.METADATA -> {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
            UpdateResource.APK -> header("Accept", "application/octet-stream")
        }
    }
    .build()

internal fun assetRedirect(response: UpdateResponse): URI {
    val location = response.headers("Location").singleOrNull() ?: throw RejectedUpdate()
    if (location.length > 8192 || location.any { it.code !in 33..126 }) throw RejectedUpdate()
    val uri = try {
        URI(location)
    } catch (_: URISyntaxException) {
        throw RejectedUpdate()
    }
    // GitHub's canonical browser download redirects to its signed release-asset CDN. No relative
    // paths, wildcard subdomains, other GitHub endpoints, user info, explicit ports, or fragments.
    if (!uri.scheme.equals("https", ignoreCase = true) ||
        !uri.rawAuthority.equals("release-assets.githubusercontent.com", ignoreCase = true) ||
        uri.rawFragment != null || uri.rawPath.isNullOrEmpty()
    ) throw RejectedUpdate()
    return uri
}

internal fun UpdateResponse.requireFullBody(): Long? {
    if (code != 200) {
        // Do not hammer anonymous GitHub rate limits or ignore an explicit server retry interval.
        if (code in setOf(408, 500, 502, 503, 504) && headers("Retry-After").isEmpty()) {
            throw TransientUpdateFailure()
        }
        throw RejectedUpdate()
    }
    val encodings = headers("Content-Encoding")
    if (encodings.isNotEmpty() && !encodings.singleOrNull().equals("identity", ignoreCase = true)) {
        throw RejectedUpdate()
    }
    if (headers("Content-Range").isNotEmpty()) throw RejectedUpdate()
    val lengths = headers("Content-Length")
    if (lengths.isEmpty()) return null
    return lengths.singleOrNull()?.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toLongOrNull() ?: throw RejectedUpdate()
}

internal inline fun <T> networkIo(action: () -> T): T = try {
    action()
} catch (_: SSLException) {
    throw RejectedUpdate()
} catch (_: IOException) {
    throw TransientUpdateFailure()
}
