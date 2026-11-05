package dev.spofify.mobile.playback

import dev.spofify.mobile.core.ServerAddress
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.*

/** Faults surround an ACTUAL broker request. Never implements or resets room semantics.
 * Only action + generated device IDs are recorded; no credential/body/catalog logs.
 */
internal class RegistrationFaultProxy(private val broker: PersistentGoBroker) : AutoCloseable {
    class Fault(val action: String, val beforeAcceptance: Boolean = false, val drop: Boolean = false) {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    val next = AtomicReference<Fault?>()
    val actions = CopyOnWriteArrayList<String>()
    val acceptedRegistrations = CopyOnWriteArrayList<String>()
    val leaves = CopyOnWriteArrayList<String>()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(8, TimeUnit.SECONDS).build()
    private val web = MockWebServer()
    val server: ServerAddress
    init {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.path)
                check(path.startsWith("/api/")) { "Fixture proxy accepts API paths only" }
                val bytes = request.body.readByteArray()
                val body = if (path == "/api/me/player" && request.method == "POST")
                    Json.parseToJsonElement(bytes.decodeToString()).jsonObject else null
                val action = body?.get("action")?.jsonPrimitive?.content ?: "read"
                actions += action
                val fault = next.get()?.takeIf { it.action == action && next.compareAndSet(it, null) }
                if (fault?.beforeAcceptance == true) {
                    fault.reached.countDown()
                    check(fault.release.await(6, TimeUnit.SECONDS)) { "Owned delayed request not released" }
                }
                val upstream = Request.Builder().url(broker.server.origin + path)
                    .headers(request.headers).header("Host", broker.server.origin.removePrefix("http://"))
                    .header("Origin", broker.server.origin).removeHeader("Content-Length")
                    .method(requireNotNull(request.method), if (request.method == "GET") null else bytes.toRequestBody("application/json".toMediaType()))
                    .build()
                return http.newCall(upstream).execute().use { response ->
                    val data = response.body.string()
                    if (response.isSuccessful && action == "register") {
                        acceptedRegistrations += Json.parseToJsonElement(data).jsonObject.getValue("deviceId").jsonPrimitive.content
                    }
                    if (response.isSuccessful && action == "leave") leaves += requireNotNull(body).getValue("deviceId").jsonPrimitive.content
                    if (fault != null && !fault.beforeAcceptance) {
                        fault.reached.countDown()
                        if (!fault.drop) check(fault.release.await(6, TimeUnit.SECONDS)) { "Owned accepted response not released" }
                    }
                    if (fault?.drop == true) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else MockResponse().setResponseCode(response.code).setHeaders(response.headers).setBody(data)
                }
            }
        }
        web.start(InetAddress.getByName("127.0.0.1"), 0)
        server = ServerAddress.parse("http://127.0.0.1:${web.port}")
    }
    override fun close() {
        next.getAndSet(null)?.release?.countDown()
        web.shutdown()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
        check(http.dispatcher.executorService.awaitTermination(3, TimeUnit.SECONDS))
    }
}
