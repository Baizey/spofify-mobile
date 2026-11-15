package dev.spofify.mobile.core.library

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.origin.ServerOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class LibraryClientTest {
    private class Sessions(var value: StoredSession?) : SessionStore {
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }

    private suspend fun fixture(role: String = "user", block: suspend (MockWebServer, SessionRepository, AccountIdentity) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val origin = ServerOrigin.parse(server.url("/").toString())
            val cookie = "spofify_session=fixture-token; Path=/; HttpOnly; Expires=Tue, 01 Jan 2030 00:00:00 GMT"
            val summary = SessionSummary("account", "fixture-user", role, "fixture-csrf")
            val client = OkHttpAuthApi.defaultClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val sessions = SessionRepository(Sessions(StoredSession(origin.value, cookie, summary)), object : LoginStore {
                override fun read(): RememberedLogin? = null
                override fun write(value: RememberedLogin) = Unit
            }, AuthApiFactory { OkHttpAuthApi(it, client) }, scope)
            server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"$role","csrfToken":"fixture-csrf"}"""))
            try {
                sessions.start()
                val state = withTimeout(3000) { sessions.state.first { it is AuthState.Authenticated } } as AuthState.Authenticated
                server.takeRequest(2, TimeUnit.SECONDS)
                block(server, sessions, state.identity)
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun page(kind: String = "track", availability: String = "\"hasAudio\":true") =
        """{"items":[{"kind":"$kind","id":"one","name":"Love",$availability,"coverage":{"tracks":1,"inLibrary":1}}],"total":1,"limit":10,"offset":0,"revision":1}"""

    @Test fun searchMatchesWorkingWebRequestAndEncodesQueryOnce() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json(page()))
        val result = LibraryClient(sessions, identity).search(MusicKind.TRACK, "A & 日本 %2F +")
        assertEquals("one", result.items.single().id)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        val url = request.requestUrl!!
        assertEquals("/api/library", url.encodedPath)
        assertEquals("tracks", url.queryParameter("kind"))
        assertEquals("10", url.queryParameter("limit"))
        assertEquals("all", url.queryParameter("scope"))
        assertEquals("in-library", url.queryParameter("availability"))
        assertEquals("A & 日本 %2F +", url.queryParameter("q"))
        assertEquals("spofify_session=fixture-token", request.getHeader("Cookie"))
        assertEquals(identity.origin, request.getHeader("Origin"))
        assertFalse(request.path!!.contains("fixture-token"))
    } }

    @Test fun administrativeProjectionIsConsumedWithoutPublishingItsPrivatePath() = runBlocking { fixture("admin") { server, sessions, identity ->
        server.enqueue(json(page(availability = "\"libraryPath\":\"private-do-not-publish\"")))
        val item = LibraryClient(sessions, identity).search(MusicKind.TRACK, "love").items.single()
        assertTrue(item.hasAudio)
        assertFalse(item.toString().contains("private-do-not-publish"))
    } }

    @Test fun listenerMissingAvailabilityIsRejectedRatherThanInvented() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json(page(availability = "\"libraryPath\":\"private-do-not-publish\"")))
        assertTrue(runCatching { LibraryClient(sessions, identity).search(MusicKind.TRACK, "love") }.isFailure)
    } }

    @Test fun emptyQueryAndRetiredIdentityNeverDispatch() = runBlocking { fixture { server, sessions, identity ->
        val api = LibraryClient(sessions, identity)
        for (query in listOf("", "  ", "x\n", "\uD800")) assertTrue(runCatching { api.search(MusicKind.TRACK, query) }.isFailure)
        assertTrue(runCatching { LibraryClient(sessions, identity.copy(incarnation = identity.incarnation + 1))
            .search(MusicKind.TRACK, "love") }.exceptionOrNull() is CancellationException)
        assertEquals(1, server.requestCount)
    } }

    @Test fun transientHttpStatusesAreNotAutomaticallyReplayed() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json("{}", 503))
        val failure = runCatching { LibraryClient(sessions, identity).search(MusicKind.TRACK, "love") }.exceptionOrNull()
        assertTrue(failure is LibraryException && failure.status == 503)
        assertEquals(2, server.requestCount)
    } }

    @Test fun playbackAdmissionKeepsTicketOutOfUrlAndUsesExactSubprotocol() = runBlocking { fixture { server, sessions, identity ->
        val hello = CompletableDeferred<String>()
        server.enqueue(json("""{"ticket":"one-use-fixture-ticket"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onMessage(socket: okhttp3.WebSocket, text: String) {
                hello.complete(text)
                socket.send("""{"type":"snapshot"}""")
            }
        }).setHeader("Sec-WebSocket-Protocol", "spofify.library-playback.v2"))
        val connection = sessions.playbackTransport(identity).connect("Phone", "fixture-device")
        try {
            val frame = kotlinx.serialization.json.Json.parseToJsonElement(withTimeout(3000) { hello.await() })
                .let { it as kotlinx.serialization.json.JsonObject }
            assertEquals(setOf("type", "ticket", "name", "deviceId", "canPlay"), frame.keys)
            assertEquals("hello", (frame.getValue("type") as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("one-use-fixture-ticket", (frame.getValue("ticket") as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("true", (frame.getValue("canPlay") as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("""{"type":"snapshot"}""", withTimeout(3000) { connection.messages.first() })
            val admission = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", admission.method)
            assertEquals("/api/me/player/connect", admission.path)
            assertEquals("{}", admission.body.readUtf8())
            assertEquals("fixture-csrf", admission.getHeader("X-CSRF-Token"))
            val upgrade = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/me/player/socket", upgrade.path)
            assertNull(upgrade.requestUrl!!.query)
            assertEquals("spofify.library-playback.v2", upgrade.getHeader("Sec-WebSocket-Protocol"))
            assertEquals(identity.origin, upgrade.getHeader("Origin"))
            assertFalse(upgrade.path!!.contains("fixture-ticket"))
        } finally { connection.close() }
    } }

    @Test fun inPlaceRenewalDoesNotRetireAuthenticatedIdentity() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"user","csrfToken":"fixture-csrf"}"""))
        sessions.renew(identity)
        assertTrue(sessions.isCurrent(identity))
        assertEquals(identity, (sessions.state.value as AuthState.Authenticated).identity)
        assertEquals("/api/auth/session", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    } }

    @Test fun audioRangesAreBytesAndStopReadingAfterAccountRetirement() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/mpeg")
            .setHeader("Content-Range", "bytes 2-5/10").setBody("2345"))
        sessions.audio(identity, "opaque/%", 2, 4).use { stream ->
            assertEquals(4L, stream.length)
            assertEquals("2345", stream.input.readBytes().decodeToString())
        }
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(listOf("api", "library", "tracks", "opaque/%", "audio"), request.requestUrl!!.pathSegments)
        assertEquals("bytes=2-5", request.getHeader("Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg").setBody("audio"))
        val stream = sessions.audio(identity, "track", 0, null)
        try {
            server.enqueue(MockResponse().setResponseCode(204))
            sessions.signOut()
            assertTrue(runCatching { stream.input.read() }.exceptionOrNull() is java.io.IOException)
        } finally { stream.close() }
    } }

    @Test fun currentUnauthorizedResponseRetiresSession() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json("{}", 401))
        assertTrue(runCatching { LibraryClient(sessions, identity).search(MusicKind.TRACK, "love") }.isFailure)
        assertTrue(sessions.state.value is AuthState.SignedOut)
    } }

}
