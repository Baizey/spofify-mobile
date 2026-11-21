package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.origin.ServerOrigin
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FeatureErrorResponseTest {
    @Test fun mutationPreservesBoundedSemanticErrorWithoutRetrying() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val body = """{"code":"FEATURE_CONFLICT","error":"Refresh the resource"}"""
            server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(body))
            val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
            try {
                val statuses = mutableListOf<Int>()
                val response = api.mutate(listOf("api", "feature"), "PUT", "{}", cookie(), "fixture-csrf") { code, _ -> statuses += code }
                assertEquals(409, response.status)
                assertEquals(body, response.bytes.decodeToString())
                assertEquals(listOf(409), statuses)
                assertEquals(1, server.requestCount)
                assertEquals("PUT", server.takeRequest().method)
            } finally {
                api.cancel()
            }
        }
    }

    @Test fun oversizedAndNonJsonErrorsPreserveStatusButNotTheirBodies() = runBlocking {
        val responses = listOf(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody("x".repeat(8193)),
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setChunkedBody("x".repeat(8193), 512),
            MockResponse().setResponseCode(500).setHeader("Content-Type", "text/html").setBody("<html>not a semantic error</html>"),
        )
        for (fixture in responses) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(fixture)
                val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
                try {
                    val response = api.mutate(listOf("api", "feature"), "PUT", "{}", cookie(), "fixture-csrf") { _, _ -> }
                    assertTrue(response.status >= 400)
                    assertTrue(response.bytes.isEmpty())
                    assertEquals(1, server.requestCount)
                } finally {
                    api.cancel()
                }
            }
        }
    }

    @Test fun readStatusesNeverReplayAndOnlyOptIn503RetainsBoundedJson() = runBlocking {
        for (status in listOf(408, 421, 503)) {
            MockWebServer().use { server ->
                server.start()
                val body = """{"code":"SEARCH_INDEX_NOT_READY","error":"The index is not ready"}"""
                server.enqueue(MockResponse().setResponseCode(status)
                    .setHeader("Content-Type", "application/json").setHeader("Retry-After", "0")
                    .setHeader("Set-Cookie", "spofify_session=must-not-renew; Path=/; HttpOnly")
                    .setBody(body))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
                val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
                try {
                    val statuses = mutableListOf<Int>()
                    val response = api.get(listOf("search"), listOf("query" to "music"), cookie(), false,
                        preserveUnavailableBody = true) { code, renewed ->
                        statuses += code
                        assertNull(renewed)
                    }
                    assertEquals(status, response.status)
                    assertEquals(listOf(status), statuses)
                    assertEquals(1, server.requestCount)
                    if (status == 503) {
                        assertEquals(body, response.bytes.decodeToString())
                        assertEquals("application/json", response.mediaType)
                    } else {
                        assertTrue(response.bytes.isEmpty())
                        assertNull(response.mediaType)
                    }
                    // Only a separate caller action consumes the queued successful response.
                    assertEquals(200, api.get(listOf("search"), listOf("query" to "music"), cookie(), false) { _, _ -> }.status)
                    assertEquals(2, server.requestCount)
                } finally {
                    api.cancel()
                }
            }
        }
    }

    @Test fun read503DiscardsOversizedNonJsonAndArtworkErrorBodies() = runBlocking {
        val fixtures = listOf(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("x".repeat(8193)) to false,
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setChunkedBody("x".repeat(8193), 512) to false,
            MockResponse().setResponseCode(503).setHeader("Content-Type", "text/html").setBody("<html>private diagnostic</html>") to false,
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("{\"code\":\"SEARCH_BUSY\"}") to true,
        )
        for ((fixture, artwork) in fixtures) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(fixture.setHeader("Retry-After", "0"))
                val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
                try {
                    val response = api.get(listOf("search"), emptyList(), cookie(), artwork,
                        preserveUnavailableBody = true) { _, _ -> }
                    assertEquals(503, response.status)
                    assertTrue(response.bytes.isEmpty())
                    assertNull(response.mediaType)
                    assertEquals(1, server.requestCount)
                } finally {
                    api.cancel()
                }
            }
        }
    }

    @Test fun defaultGet503StillDiscardsItsBodyWithoutStatusReplay() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "0").setBody("""{"code":"SEARCH_BUSY","error":"private detail"}"""))
            val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
            try {
                val response = api.get(listOf("search"), emptyList(), cookie(), false) { _, _ -> }
                assertEquals(503, response.status)
                assertTrue(response.bytes.isEmpty())
                assertNull(response.mediaType)
                assertEquals(1, server.requestCount)
            } finally {
                api.cancel()
            }
        }
    }

    private fun cookie() = "spofify_session=${UUID.randomUUID()}; Path=/; HttpOnly; Expires=Tue, 01 Jan 2030 00:00:00 GMT"
}
