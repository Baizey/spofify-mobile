package dev.spofify.mobile.core

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApiTransportTest {
    private lateinit var web: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var client: SpofifyClient
    private lateinit var api: ApiTransport

    @Before fun setup() {
        web = MockWebServer()
        web.start()
        store = MemorySessionStore()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), store)
        api = ApiTransport(client)
    }
    @After fun teardown() { web.shutdown() }

    @Test fun sharesAuthenticatedHeadersAndEncodesOpaqueSegmentsAndQueriesExactlyOnce() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        web.enqueue(jsonResponse("{}"))
        api.get(listOf("api", "library", "tracks", "track/a%2F +?"), mapOf("q" to "A & B/50% + ☃"))
        val read = web.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("/api/library/tracks/track%2Fa%252F%20+%3F", read.requestUrl!!.encodedPath)
        assertEquals("A & B/50% + ☃", read.requestUrl!!.queryParameter("q"))
        assertEquals("application/json", read.getHeader("Accept"))
        assertEquals(client.server.origin, read.getHeader("Origin"))
        assertEquals("spofify_session=cookie-secret", read.getHeader("Cookie"))
        assertNull(read.getHeader("X-CSRF-Token"))
        assertNull(read.getHeader("X-Spofify-Login"))
        web.enqueue(jsonResponse("{}"))
        api.send("PATCH", listOf("api", "me", "playlists", "p/a%"), buildJsonObject { put("name", "Renamed") })
        val write = web.takeRequest()
        assertEquals("PATCH", write.method)
        assertEquals("/api/me/playlists/p%2Fa%25", write.path)
        assertEquals("csrf-secret", write.getHeader("X-CSRF-Token"))
        assertEquals(client.server.origin, write.getHeader("Origin"))
        assertEquals("spofify_session=cookie-secret", write.getHeader("Cookie"))
        assertEquals("application/json; charset=utf-8", write.getHeader("Content-Type"))
        assertEquals("""{"name":"Renamed"}""", write.body.readUtf8())
    }

    @Test fun mutation204IsNullButGetRequiresAJsonObject() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(204))
        assertNull(api.send("DELETE", listOf("api", "me", "playlists", "p")))
        assertEquals("DELETE", web.takeRequest().method)
        web.enqueue(MockResponse().setResponseCode(204))
        assertEquals(204, apiFailure { api.get(listOf("api", "library")) }.status)
    }

    @Test fun unauthorizedUsesTheExistingAccountInvalidation() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        var notifications = 0
        client.onUnauthorized = { notifications++ }
        web.enqueue(jsonResponse("""{"error":"/private/user/cookie-secret"}""", 401))
        val error = apiFailure { api.get(listOf("api", "me", "playlists")) }
        assertEquals(401, error.status)
        assertEquals("Your session has expired. Sign in again.", error.message)
        assertNull(error.code)
        assertNull(client.currentSession())
        assertNull(store.value)
        assertEquals(1, notifications)
    }

    @Test fun onlyKnownBoundedTypedErrorCodesSurviveAndNoServerMessagesEscape() = runBlocking {
        web.enqueue(jsonResponse("""{"error":{"code":"STALE_EVALUATION","message":"/private/owner/secret"}}""", 409))
        val recognized = apiFailure { api.send("POST", listOf("api", "me", "playlists"), buildJsonObject {}) }
        assertEquals("STALE_EVALUATION", recognized.code)
        assertEquals(409, recognized.status)
        assertEquals("The item changed on the server. Refresh and try again.", recognized.message)
        for (body in listOf(
            """{"error":{"code":"/private/secret","message":"secret"}}""",
            """{"error":{"code":123}}""",
            """{"error":{"code":{"private":"secret"}}}""",
            """{"error":"REVISION_CONFLICT"}""",
            """{"code":"REVISION_CONFLICT"}""",
            """{"error":{"code":"REVISION_CONFLICT"}""",
            "[\"/private/secret\"]",
            " ".repeat(8193) + """{"error":{"code":"REVISION_CONFLICT"}}""",
        )) {
            web.enqueue(jsonResponse(body, 409))
            val malformed = apiFailure { api.get(listOf("api", "library")) }
            assertEquals(409, malformed.status)
            assertNull(malformed.code)
            assertFalse(malformed.message.contains("private"))
            assertFalse(malformed.message.contains("secret"))
            assertNull(malformed.cause)
        }
    }

    @Test fun device404And409PreserveOnlyStrictlyValidatedReconciliationViews() = runBlocking {
        val view = """{"devices":[],"activeDeviceId":"","epoch":1,"leaseMs":0,"state":null,"stateSeq":0,"stateAgeMs":0,"transfer":null,"directives":[],"commands":[],"error":"/private/secret"}"""
        for (status in listOf(404, 409)) {
            web.enqueue(jsonResponse(view, status))
            val error = apiFailure { api.send("POST", listOf("api", "me", "player"), buildJsonObject { put("action", "poll") }) }
            val safe = error.responseBody!!
            assertEquals(1L, PlaybackDevicesView.fromJson(safe).epoch)
            assertFalse(safe.toString().contains("private"))
            assertFalse(safe.toString().contains("secret"))
            assertFalse(error.message.contains("private"))
            assertFalse(error.toString().contains("secret"))
        }
        // 404/409 views do not deliver directives. Reject command-bearing data before any
        // command parsing, including malformed commands and nested private JSON masquerading
        // as a track key or selection token.
        for (command in listOf(
            "{}",
            """{"type":"seek"}""",
            """{"type":"play","track":{"id":"t","name":"Song","artists":[],"albumName":"","albumId":"","duration":1,"key":{"libraryPath":"/private/secret"},"order":0,"selectionToken":{"libraryPath":"/private/secret"}}}""",
        )) {
            val withDirective = view.replace("\"directives\":[]", "\"directives\":[{\"deliveryId\":\"d\",\"epoch\":1,\"type\":\"command\",\"command\":$command}]")
            web.enqueue(jsonResponse(withDirective, 409))
            val rejected = apiFailure { api.get(listOf("api", "me", "player")) }
            assertEquals(409, rejected.status)
            assertNull(rejected.responseBody)
            assertFalse(rejected.message.contains("private"))
        }
        for ((path, status, body) in listOf(
            Triple(listOf("api", "me", "playlists"), 409, view),
            Triple(listOf("api", "me", "player", "other"), 409, view),
            Triple(listOf("api", "me", "player"), 500, view),
            Triple(listOf("api", "me", "player"), 409, view.dropLast(1) + ",\"libraryPath\":\"/private/secret\"}"),
            Triple(listOf("api", "me", "player"), 409, view.replace("\"epoch\":1", "\"epoch\":-1")),
        )) {
            web.enqueue(jsonResponse(body, status))
            assertNull(apiFailure { api.get(path) }.responseBody)
        }
    }

    @Test fun rejectsHtmlMalformedNonObjectAndOversizedSuccessWithoutPrivateDetails() = runBlocking {
        for (response in listOf(
            MockResponse().setHeader("Content-Type", "text/html").setBody("<html>/private/secret</html>"),
            jsonResponse("""{"private":"/private/secret"""),
            jsonResponse("[\"/private/secret\"]"),
            jsonResponse("null"),
            jsonResponse("{}").setHeader("Content-Length", (16L * 1024 * 1024 + 1).toString()),
        )) {
            web.enqueue(response)
            val error = apiFailure { api.get(listOf("api", "library")) }
            assertTrue(error.message.contains("JSON"))
            assertFalse(error.message.contains("private"))
            assertFalse(error.message.contains(client.server.origin))
            assertNull(error.cause)
        }
        web.enqueue(jsonResponse("{}").setHeader("Content-Type", "application/problem+json"))
        assertTrue(api.get(listOf("api", "library")).isEmpty())
    }

    @Test fun allowsAThousandTrackQueueWithinTheResponseBound() = runBlocking {
        val items = (1..1000).joinToString(",") { """{"kind":"track","id":"$it","name":"Song $it","hasAudio":true}""" }
        web.enqueue(jsonResponse("""{"items":[$items],"total":1000,"limit":1000,"truncated":false}"""))
        val response = api.get(listOf("api", "me", "likes", "playback"))
        assertEquals(1000, response["items"]!!.jsonArray.size)
        assertEquals("1000", response["total"]!!.jsonPrimitive.content)
    }

    @Test fun redirectsAreNotFollowedAndRetryableMutationsAreNeverReplayed() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "${client.server.origin}/private/secret"))
        assertEquals(302, apiFailure { api.get(listOf("api", "library")) }.status)
        assertEquals(1, web.requestCount)
        web.takeRequest()
        web.enqueue(jsonResponse("{}", 503).setHeader("Retry-After", "0"))
        web.enqueue(jsonResponse("{}"))
        assertEquals(503, apiFailure { api.send("DELETE", listOf("api", "me", "playlists", "p")) }.status)
        assertEquals(2, web.requestCount)
        assertNotNull(web.takeRequest(1, TimeUnit.SECONDS))
        assertNull(web.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun disconnectedMutationHasOnlyOneAttemptAndASafeNetworkError() = runBlocking {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        web.enqueue(jsonResponse("{}"))
        try {
            api.send("POST", listOf("api", "me", "history"), buildJsonObject { put("eventId", "event") })
            fail("Expected a network error")
        } catch (error: IOException) {
            val safeMessage = "Unable to reach the server. Check your connection and server address."
            // Coroutine debug stacktrace recovery may copy an IOException with the original
            // safe IOException as its cause. Inspect every cause rather than forbidding that
            // wrapper: no raw OkHttp exception, request URL, or private detail may survive.
            var current: Throwable? = error
            repeat(8) {
                current?.let {
                    assertTrue(it is IOException)
                    assertEquals(safeMessage, it.message)
                    assertFalse(it.toString().contains(client.server.origin))
                    current = it.cause
                }
            }
            assertNull("Unexpected exception cause cycle or excessive wrapping", current)
        }
        assertEquals(1, web.requestCount)
        assertNotNull(web.takeRequest(1, TimeUnit.SECONDS))
        assertNull(web.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun cancellationAbortsBothPendingHeadersAndAnInProgressBody() = runBlocking {
        for (response in listOf(
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
            slowStreamingResponse(),
        )) {
            web.enqueue(response)
            val pending = async(Dispatchers.Default) { api.get(listOf("api", "library")) }
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            delay(100)
            assertFalse("Response should still be awaiting headers or consuming its body", pending.isCompleted)
            withTimeout(5_000) { pending.cancelAndJoin() }
            withTimeout(5_000) {
                while (client.httpClient.dispatcher.runningCallsCount() != 0) delay(10)
            }
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun slowBodyDoesNotBlockTheCallingSingleThreadDispatcher() = runBlocking {
        web.enqueue(slowStreamingResponse())
        val uiThread = AtomicReference<Thread>()
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-ui").also(uiThread::set)
        }.asCoroutineDispatcher().use { ui ->
            val pending = async(ui) { api.get(listOf("api", "library")) }
            try {
                assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
                delay(100)
                assertFalse("The streaming response must still be in progress", pending.isCompleted)
                withTimeout(5_000) { withContext(ui) { assertSame(uiThread.get(), Thread.currentThread()) } }
            } finally { pending.cancelAndJoin() }
            withTimeout(5_000) {
                while (client.httpClient.dispatcher.runningCallsCount() != 0) delay(10)
            }
        }
    }

    // Long enough to require cancellation, but each server-side sleep is short enough for
    // MockWebServer to observe the closed socket and finish within its shutdown timeout.
    private fun slowStreamingResponse() = jsonResponse("""{"items":[]}""" + " ".repeat(4096))
        .throttleBody(1, 25, TimeUnit.MILLISECONDS)

    @Test fun invalidMethodsAndPathSegmentsFailBeforeNetworkWithoutEchoingInput() = runBlocking {
        for (path in listOf(emptyList(), listOf("api", ".."), listOf("api", "secret\npath"))) {
            try { api.get(path); fail("Invalid path accepted") }
            catch (error: IllegalArgumentException) { assertEquals("Invalid API path", error.message) }
        }
        try { api.send("private-method", listOf("api")); fail("Invalid method accepted") }
        catch (error: IllegalArgumentException) { assertEquals("Unsupported API method", error.message) }
        assertEquals(0, web.requestCount)
    }
}
