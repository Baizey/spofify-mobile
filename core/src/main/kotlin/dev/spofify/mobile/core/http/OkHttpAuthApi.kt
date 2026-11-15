package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.origin.ServerOrigin
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Shares the connection pool without exposing OkHttp types across the app composition boundary. */
class OkHttpAuthApiFactory : AuthApiFactory {
    private val client = OkHttpAuthApi.defaultClient()
    override fun create(origin: ServerOrigin): AuthApi = OkHttpAuthApi(origin, client)
}

/** No global cookie jar, redirects, retry, HTTP logging or private-response disk cache. */
internal class OkHttpAuthApi(
    override val origin: ServerOrigin,
    private val client: OkHttpClient = defaultClient(),
) : AuthApi, FeatureTransport {
    private val json = Json { ignoreUnknownKeys = true }
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val cookieName = if (origin.url.isHttps) "__Host-spofify_session" else "spofify_session"

    init {
        require(!client.followRedirects && !client.followSslRedirects && !client.retryOnConnectionFailure)
        require(client.cache == null && client.cookieJar === CookieJar.NO_COOKIES)
        require(client.interceptors.isEmpty() && client.networkInterceptors.isEmpty())
        require(client.authenticator === Authenticator.NONE && client.proxyAuthenticator === Authenticator.NONE)
    }

    // Ordinary GET connection recovery is safe; HTTP status responses are never replayed.
    private class ReadStatus(val status: Int) : java.net.ProtocolException("Read status received")
    private val readClient = client.newBuilder()
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code in setOf(408, 421, 503)) {
                val status = response.code
                response.close()
                throw ReadStatus(status)
            }
            response
        }.build()

    override suspend fun get(
        segments: List<String>,
        query: List<Pair<String, String>>,
        cookie: String,
        artwork: Boolean,
        onHeaders: (Int, String?) -> Unit,
    ): FeatureResponse {
        val url = origin.url.newBuilder().apply {
            segments.forEach { segment ->
                require(segment.isNotEmpty() && segment != "." && segment != ".." && segment.none(Char::isISOControl))
                addPathSegment(segment)
            }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        check(origin.contains(url))
        val request = Request.Builder().url(url).get()
            .header("Origin", origin.value)
            .header("Cookie", cookieHeader(cookie))
            .header("Accept", if (artwork) "image/jpeg, image/png" else "application/json")
            .header("Cache-Control", "no-store").build()
        return try {
            execute(request, readClient) { response ->
                val renewed = if (response.isSuccessful) response.headers.values("Set-Cookie")
                    .filter { it.substringBefore(';').substringBefore('=').trim() == cookieName }
                    .also { require(it.size <= 1) }.singleOrNull()
                    ?.let { parseCookie(it, requireAbsoluteExpiry = true).toString() } else null
                onHeaders(response.code, renewed)
                if (response.code != 200) FeatureResponse(response.code, byteArrayOf(), null)
                else {
                    val body = response.body ?: throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
                    val mime = body.contentType()
                    require(if (artwork) mime?.type == "image" && mime.subtype in setOf("jpeg", "png")
                        else mime?.type == "application" && mime.subtype == "json")
                    val bound = if (artwork) 8L * 1024 * 1024 else 2L * 1024 * 1024
                    val source = body.source()
                    source.request(bound + 1)
                    require(source.buffer.size <= bound)
                    FeatureResponse(200, source.readByteArray(), mime.toString())
                }
            }
        } catch (status: ReadStatus) {
            onHeaders(status.status, null)
            FeatureResponse(status.status, byteArrayOf(), null)
        }
    }

    @Serializable
    private class LoginBody(val username: String, val password: String)

    override suspend fun login(username: String, password: String): AuthReply {
        val body = json.encodeToString(LoginBody(username, password)).toRequestBody(JSON)
        val request = request("/api/auth/login").header("X-Spofify-Login", "1")
            .header("X-Spofify-Session-Mode", "rolling-v1").post(OneShotBody(body)).build()
        return exchange(request, requireCookie = true)
    }

    override suspend fun session(cookie: String): AuthReply =
        exchange(request("/api/auth/session").header("Cookie", cookieHeader(cookie)).get().build())

    override suspend fun logout(cookie: String, csrfToken: String) {
        val request = request("/api/auth/logout").header("Cookie", cookieHeader(cookie))
            .header("X-CSRF-Token", csrfToken).post(OneShotBody("".toRequestBody(JSON))).build()
        execute(request) { response ->
            if (response.code != 204) throw failure(response.code)
            // Logout never publishes Set-Cookie or rolling renewal.
        }
    }

    override fun validateCookie(cookie: String) { parseCookie(cookie) }

    override fun cancel() { calls.forEach(Call::cancel) }

    private fun request(path: String): Request.Builder {
        val url = origin.url.newBuilder().encodedPath(path).build()
        check(origin.contains(url))
        return Request.Builder().url(url).header("Origin", origin.value)
            .header("Accept", "application/json").header("Cache-Control", "no-store")
    }

    private suspend fun exchange(request: Request, requireCookie: Boolean = false): AuthReply =
        execute(request) { response ->
            if (response.code != 200) throw failure(response.code)
            try {
                // Parse the server's absolute cookie attributes at headers, before body I/O.
                val modes = response.headers.values("X-Spofify-Session-Mode")
                require(modes.isEmpty() || modes == listOf("rolling-v1"))
                val cookies = response.headers.values("Set-Cookie").filter { raw ->
                    raw.substringBefore(';').substringBefore('=').trim() == cookieName
                }
                require(cookies.size <= 1)
                val cookie = cookies.singleOrNull()?.let { parseCookie(it, requireAbsoluteExpiry = true).toString() }
                require(!requireCookie || cookie != null)
                val body = response.body ?: throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
                val source = body.source()
                source.request(MAX_BODY + 1)
                if (source.buffer.size > MAX_BODY) throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
                val bytes = source.readByteArray()
                require(body.contentType()?.subtype == "json")
                val summary = json.decodeFromString<SessionSummary>(bytes.decodeToString(throwOnInvalidSequence = true)).validated()
                require(summary.sessionMode == null || modes == listOf(summary.sessionMode))
                AuthReply(summary, cookie, modes.singleOrNull())
            } catch (error: SerializationException) {
                throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
            } catch (error: IllegalArgumentException) {
                throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
            }
        }

    private fun parseCookie(raw: String, requireAbsoluteExpiry: Boolean = false): Cookie {
        // Cookie.parse gives Max-Age precedence and rebases it on the device clock.
        // Strip ONLY that attribute; OkHttp still owns cookie/date/attribute parsing.
        // Expires is the backend's durable deadline, even if the device clock is skewed.
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
        if (cookie.name != cookieName || cookie.value.isEmpty() || !cookie.hostOnly ||
            cookie.domain != origin.url.host || cookie.path != "/" || !cookie.httpOnly ||
            cookie.secure != origin.url.isHttps || cookie.expiresAt <= 0L) {
            throw AuthFailure(AuthFailure.Kind.INVALID_RESPONSE)
        }
        return cookie
    }

    private fun cookieHeader(raw: String): String = parseCookie(raw).let { "${it.name}=${it.value}" }

    /** Keep the continuation and Call owned until body consumption AND close finish. */
    private suspend fun <T> execute(
        request: Request,
        transport: OkHttpClient = client,
        consume: (Response) -> T,
    ): T {
        check(origin.contains(request.url))
        val call = transport.newCall(request)
        calls.add(call)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            val callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    calls.remove(call)
                    if (continuation.isActive) continuation.resumeWithException(
                        if (e is ReadStatus) e else AuthFailure(AuthFailure.Kind.UNAVAILABLE),
                    )
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!continuation.isActive) {
                            // Cancel before close: do not drain a retired slow response body.
                            call.cancel()
                            response.close()
                            return
                        }
                        val result = response.use(consume)
                        // Only a fully consumed, already closed value crosses the suspension.
                        continuation.resumeWith(Result.success(result))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is IOException && error !is AuthFailure)
                                AuthFailure(AuthFailure.Kind.UNAVAILABLE) else error,
                        )
                    } finally {
                        calls.remove(call)
                    }
                }
            }
            try { call.enqueue(callback) } catch (error: Exception) {
                call.cancel()
                calls.remove(call)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun failure(code: Int) = AuthFailure(
        if (code == 401) AuthFailure.Kind.UNAUTHORIZED else AuthFailure.Kind.UNAVAILABLE,
    )

    /** OkHttp otherwise may follow up some 408/503/421 responses despite retry=false. */
    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }

    companion object {
        private const val MAX_BODY = 64L * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).build()
    }
}
