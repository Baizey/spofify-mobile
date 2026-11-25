package dev.spofify.mobile.core.personal

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.origin.ServerOrigin
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PersonalLibraryClientTest {
    @Test fun likeStatusPreservesRepeatedRequestedIdsAndPutSendsExplicitStateOnce() = runBlocking {
        fixture { server, api, _ ->
            server.enqueue(json("""{"items":[{"trackId":"x","liked":true},{"trackId":"x","liked":true}]}"""))
            assertEquals(listOf("x", "x"), api.likeStatus(listOf("x", "x")).map { it.trackId })
            assertEquals("/api/me/likes/status", server.request().path)
            server.enqueue(json("""{"trackId":"x","liked":false}"""))
            assertFalse(api.setLike("x", false).liked)
            val put = server.request()
            assertEquals("/api/me/likes", put.path)
            assertEquals("PUT", put.method)
            assertEquals("fixture-csrf", put.getHeader("X-CSRF-Token"))
            assertEquals(JsonPrimitive(false), Json.parseToJsonElement(put.body.readUtf8()).jsonObject["liked"])
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun likesNeverInferAvailabilityFromPathAndUnauthorizedRetiresSession() = runBlocking {
        fixture { server, api, sessions ->
            val track = """{"kind":"track","id":"x","name":"Track","coverage":{"tracks":1,"inLibrary":1,"albums":0},"path":"private"}"""
            server.enqueue(json("""{"items":[{"track":$track,"likedAt":"2026-01-01T00:00:00Z"}],"total":1,"availableTotal":1,"limit":40,"offset":0}"""))
            assertEquals(PersonalLibraryErrorCode.INVALID_RESPONSE, (runCatching { api.likes() }.exceptionOrNull() as PersonalLibraryException).code)
            assertEquals("/api/me/likes?limit=40&offset=0", server.request().path)
            server.enqueue(json("{}", 401))
            assertTrue(runCatching { api.likes() }.isFailure)
            assertNull(sessions.onlineGrant.value)
        }
    }

    private suspend fun fixture(block: suspend (MockWebServer, PersonalLibraryClient, SessionRepository) -> Unit) {
        MockWebServer().use { server ->
            val origin = ServerOrigin.parse(server.url("/").toString())
            val record = StoredSession(origin.value,
                "spofify_session=fixture-token; Path=/; HttpOnly; Expires=Tue, 01 Jan 2030 00:00:00 GMT",
                SessionSummary("account", "fixture-user", "user", "fixture-csrf"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = OkHttpAuthApi.defaultClient()
            val sessions = SessionRepository(object : SessionStore {
                var value: StoredSession? = record
                override fun read() = value
                override fun write(value: StoredSession?) { this.value = value }
            }, object : LoginStore {
                override fun read(): RememberedLogin? = null
                override fun write(value: RememberedLogin) = Unit
            }, AuthApiFactory { OkHttpAuthApi(it, client) }, scope)
            server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"user","csrfToken":"fixture-csrf"}"""))
            try {
                sessions.start()
                val identity = withTimeout(3000) { sessions.onlineGrant.first { it != null } }!!.identity
                server.request()
                block(server, PersonalLibraryClient(sessions, identity), sessions)
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun MockWebServer.request() = checkNotNull(takeRequest(2, TimeUnit.SECONDS))
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
}
