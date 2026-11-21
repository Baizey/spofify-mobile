package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.auth.FeatureAuthority
import dev.spofify.mobile.core.playback.PlaybackConnection
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
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
import okio.GzipSource
import okio.buffer

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
    private val cookieName = cookieName(origin)

    init {
        require(!client.followRedirects && !client.followSslRedirects && !client.retryOnConnectionFailure)
        require(client.cache == null && client.cookieJar === CookieJar.NO_COOKIES)
        require(client.interceptors.isEmpty() && client.networkInterceptors.isEmpty())
        require(client.authenticator === Authenticator.NONE && client.proxyAuthenticator === Authenticator.NONE)
    }

    // Intercept status follow-ups even when OkHttp's connection recovery is disabled.
    private val sessionClient = client.newBuilder().addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code in NO_FOLLOW_UP) throw response.use { failure(it) }
        response
    }.build()

    // Ordinary GET connection recovery is safe; HTTP status responses are never replayed.
    private enum class UnavailableBodyPolicy { DISCARD, BOUNDED_JSON }
    internal class ReadStatus(val response: FeatureResponse) : ProtocolException("Read status received")
    private val readClient = client.newBuilder()
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code in NO_FOLLOW_UP) {
                // JSON services use bounded 503 codes (for example search index readiness).
                // Throw before OkHttp's status follow-up logic, without losing that safe body.
                val rejected = response.use {
                    if (it.code == 503 && chain.request().header("Accept") == "application/json" &&
                        chain.request().tag(UnavailableBodyPolicy::class.java) == UnavailableBodyPolicy.BOUNDED_JSON)
                        featureErrorResponse(it, artwork = false)
                    else FeatureResponse(it.code, byteArrayOf(), null, it.header("Retry-After") != null)
                }
                throw ReadStatus(rejected)
            }
            response
        }.build()

    private val media = OriginMediaTransport(origin, readClient, ::cookieHeader, ::renewedCookie)

    override suspend fun socket(
        cookie: String, hello: String, authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit,
    ): PlaybackConnection = media.socket(cookie, hello, authority, onHeaders)

    override suspend fun audio(
        trackId: String, position: Long, length: Long?, cookie: String,
        authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit,
    ): AudioStream = media.audio(trackId, position, length, cookie, authority, onHeaders)

    override suspend fun versionedAudio(
        media: dev.spofify.mobile.core.offline.OfflineMediaDescriptor, cookie: String,
        authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit,
    ) = this.media.audio(media.trackId, 0, null, cookie, authority, onHeaders, media)

    override suspend fun get(
        segments: List<String>,
        query: List<Pair<String, String>>,
        cookie: String,
        artwork: Boolean,
        preserveUnavailableBody: Boolean,
        onHeaders: (Int, String?) -> Unit,
    ): FeatureResponse {
        val policy = if (preserveUnavailableBody) UnavailableBodyPolicy.BOUNDED_JSON else UnavailableBodyPolicy.DISCARD
        val request = featureRequest(segments, query, cookie, artwork)
            .tag(UnavailableBodyPolicy::class.java, policy).get().build()
        return try {
            execute(request, readClient) { featureResponse(it, artwork, onHeaders) }
        } catch (status: ReadStatus) {
            onHeaders(status.response.status, null)
            status.response
        }
    }

    override suspend fun mutate(
        segments: List<String>, method: String, body: String, cookie: String, csrfToken: String,
        onHeaders: (Int, String?) -> Unit,
    ): FeatureResponse {
        require(segments.take(2) == listOf("api", "me"))
        require(method in setOf("POST", "PATCH", "PUT", "DELETE"))
        require(body.toByteArray().size <= 65536)
        val request = featureRequest(segments, emptyList(), cookie, false)
            .header("X-CSRF-Token", csrfToken)
            .method(method, OneShotBody(body.toRequestBody(JSON))).build()
        return execute(request) { featureResponse(it, false, onHeaders) }
    }

    private fun featureRequest(
        segments: List<String>, query: List<Pair<String, String>>, cookie: String, artwork: Boolean,
    ): Request.Builder {
        val url = origin.url.newBuilder().apply {
            segments.forEach { segment ->
                require(segment.isNotEmpty() && segment != "." && segment != ".." && segment.none(Char::isISOControl))
                addPathSegment(segment)
            }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        check(origin.contains(url))
        return Request.Builder().url(url).header("Origin", origin.value)
            .header("Cookie", cookieHeader(cookie))
            .header("Accept", if (artwork) "image/jpeg, image/png" else "application/json")
            .header("Cache-Control", "no-store")
    }

    private fun featureResponse(
        response: Response, artwork: Boolean, onHeaders: (Int, String?) -> Unit,
    ): FeatureResponse {
        onHeaders(response.code, renewedCookie(response))
        if (response.code == 204) return FeatureResponse(response.code, byteArrayOf(), null)
        val body = response.body
        val mime = body.contentType()
        if (!response.isSuccessful) return featureErrorResponse(response, artwork)
        require(if (artwork) mime?.type == "image" && mime.subtype in setOf("jpeg", "png")
            else mime?.type == "application" && mime.subtype == "json")
        val bound = if (artwork) 8L * 1024 * 1024 else 2L * 1024 * 1024
        val source = body.source()
        source.request(bound + 1)
        require(source.buffer.size <= bound)
        return FeatureResponse(response.code, source.readByteArray(), mime.toString())
    }

    private fun featureErrorResponse(response: Response, artwork: Boolean): FeatureResponse {
        // Keep one bound for semantic read/mutation errors; never retain arbitrary HTML.
        val body = response.body
        val mime = body.contentType()
        val bound = 8L * 1024
        val retryAfter = response.header("Retry-After") != null
        fun statusOnly() = FeatureResponse(response.code, byteArrayOf(), null, retryAfter)
        if (artwork || mime?.type != "application" || mime.subtype != "json" || body.contentLength() > bound)
            return statusOnly()
        // A broken error body must not turn a permanent or throttled status into a transport retry.
        return try {
            // For intercepted 503s, OkHttp hasn't run its transparent gzip decoder yet.
            val encoding = response.header("Content-Encoding")
            val source = when {
                encoding.equals("gzip", ignoreCase = true) -> {
                    // Bound compressed input too, including chunked bodies and long gzip headers.
                    val raw = body.source()
                    if (raw.request(bound + 1) || raw.buffer.size > bound) return statusOnly()
                    GzipSource(raw.buffer).buffer()
                }
                encoding == null || encoding.equals("identity", ignoreCase = true) -> body.source()
                else -> return statusOnly()
            }
            source.use {
                it.request(bound + 1)
                if (it.buffer.size > bound) statusOnly()
                else FeatureResponse(response.code, it.readByteArray(), mime.toString(), retryAfter)
            }
        } catch (_: IOException) {
            statusOnly()
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
        exchange(request("/api/auth/session").header("Cookie", cookieHeader(cookie)).get().build(), transport = sessionClient)

    override suspend fun logout(cookie: String, csrfToken: String) {
        val request = request("/api/auth/logout").header("Cookie", cookieHeader(cookie))
            .header("X-CSRF-Token", csrfToken).post(OneShotBody("".toRequestBody(JSON))).build()
        execute(request) { response ->
            if (response.code != 204) throw failure(response)
            // Logout never publishes Set-Cookie or rolling renewal.
        }
    }

    override fun validateCookie(cookie: String) { parseCookie(cookie) }

    override fun closeRetiredStreams() = media.closeRetiredStreams()

    override fun cancel() {
        media.cancel()
        calls.forEach(Call::cancel)
    }

    private fun renewedCookie(response: Response): String? =
        if (response.isSuccessful) response.headers.values("Set-Cookie")
            .filter { it.substringBefore(';').substringBefore('=').trim() == cookieName }
            .also { require(it.size <= 1) }.singleOrNull()
            ?.let { parseCookie(it, requireAbsoluteExpiry = true).toString() } else null

    private fun request(path: String): Request.Builder {
        val url = origin.url.newBuilder().encodedPath(path).build()
        check(origin.contains(url))
        return Request.Builder().url(url).header("Origin", origin.value)
            .header("Accept", "application/json").header("Cache-Control", "no-store")
    }

    private suspend fun exchange(request: Request, requireCookie: Boolean = false, transport: OkHttpClient = client): AuthReply =
        execute(request, transport) { response ->
            if (response.code != 200) throw failure(response)
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

    private fun parseCookie(raw: String, requireAbsoluteExpiry: Boolean = false): Cookie =
        parseSessionCookie(origin, raw, requireAbsoluteExpiry)

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
                        if (e is ReadStatus || e is AuthFailure) e else transportFailure(e, cancelled = call.isCanceled()),
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
                                transportFailure(error, call.isCanceled(), response.isSuccessful && response.body.contentLength() >= 0)
                            else error,
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

    private fun failure(response: Response) = AuthFailure(
        if (response.code == 401) AuthFailure.Kind.UNAUTHORIZED else AuthFailure.Kind.UNAVAILABLE,
        transient = isTransientReadStatus(response.code, response.header("Retry-After") != null),
    )

    private fun transportFailure(error: IOException, cancelled: Boolean, readingKnownLength: Boolean = false) = AuthFailure(
        AuthFailure.Kind.UNAVAILABLE,
        // Don't treat DNS, refused/unreachable connections or TLS failures as temporary reads.
        transient = !cancelled && when (error) {
            is SSLException -> false
            is SocketTimeoutException, is EOFException -> true
            is ProtocolException -> readingKnownLength && error.message == "unexpected end of stream"
            else -> error.cause is EOFException // OkHttp can wrap an HTTP/1 header EOF.
        },
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
        private val NO_FOLLOW_UP = setOf(408, 421, 503)
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).build()
    }
}
