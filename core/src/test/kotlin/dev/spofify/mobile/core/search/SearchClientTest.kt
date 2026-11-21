package dev.spofify.mobile.core.search

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.origin.ServerOrigin
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SearchClientTest {
    @Test fun rootEndpointEncodesQueryOnceOmitsKindForMixedRankingAndUsesSafeSessionHeaders() = runBlocking {
        for (role in listOf("user", "admin")) fixture(role) { server, sessions, identity ->
            val api = SearchClient(sessions, identity)
            val rows = listOf(SearchTestData.item("track", "z"), SearchTestData.item("artist", "a"), SearchTestData.item("album", "b"))
            server.enqueue(json(SearchTestData.page(rows).toString()))
            val query = "  A & 日本 %2F +  "
            val page = api.search(query)
            assertEquals(listOf("z", "a", "b"), page.items.map { it.id })
            assertEquals(3, page.resultCount)
            assertEquals(Instant.parse(SearchTestData.expiry), page.expiresAt)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/search", request.requestUrl!!.encodedPath)
            assertEquals(setOf("query", "limit"), request.requestUrl!!.queryParameterNames)
            assertEquals(query, request.requestUrl!!.queryParameter("query"))
            assertEquals("25", request.requestUrl!!.queryParameter("limit"))
            assertEquals("spofify_session=fixture-token", request.getHeader("Cookie"))
            assertEquals(identity.origin, request.getHeader("Origin"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertNull(request.getHeader("X-CSRF-Token"))
            assertTrue(page.items.first().hasAudio)
            assertEquals("", page.items.first().artists.first().id)
            assertFalse(page.toString().contains("fixture-token"))
        }
    }

    @Test fun singularKindAndCursorOnlyContinuationNeverReuseFirstPageParameters() = runBlocking {
        fixture { server, sessions, identity ->
            val api = SearchClient(sessions, identity)
            val cursor = "opaque/+%2F日本=next"
            server.enqueue(json(SearchTestData.page(count = 300, next = cursor).toString()))
            val first = api.search("Enough", MusicKind.TRACK, limit = 1)
            val initial = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(setOf("query", "kind", "limit"), initial.requestUrl!!.queryParameterNames)
            assertEquals("track", initial.requestUrl!!.queryParameter("kind"))
            assertEquals("1", initial.requestUrl!!.queryParameter("limit"))
            server.enqueue(json(SearchTestData.page(listOf(SearchTestData.item(id = "next")), count = 300).toString()))
            val next = api.next(first.nextCursor!!)
            assertEquals(300, next.resultCount) // Retained snapshot count, not this page's size.
            assertEquals(first.expiresAt, next.expiresAt)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/search", request.requestUrl!!.encodedPath)
            assertEquals(setOf("cursor"), request.requestUrl!!.queryParameterNames)
            assertEquals(cursor, request.requestUrl!!.queryParameter("cursor"))
            assertEquals(0L, request.bodySize)
        }
    }

    @Test fun everyKindUsesItsSingularFilterWithoutLegacyScopeOrAvailabilityParameters() = runBlocking {
        fixture { server, sessions, identity ->
            val api = SearchClient(sessions, identity)
            for (kind in MusicKind.entries) {
                val singular = kind.plural.dropLast(1)
                server.enqueue(json(SearchTestData.page(listOf(SearchTestData.item(singular))).toString()))
                assertEquals(kind, api.search("query", kind, 100).items.single().kind)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(setOf("query", "kind", "limit"), request.requestUrl!!.queryParameterNames)
                assertEquals(singular, request.requestUrl!!.queryParameter("kind"))
                assertEquals("100", request.requestUrl!!.queryParameter("limit"))
            }
        }
    }

    @Test fun bothRolesRequireExplicitAudioAndCannotFallBackToAnAdministratorLibraryPath() = runBlocking {
        for (role in listOf("admin", "user")) fixture(role) { server, sessions, identity ->
            val api = SearchClient(sessions, identity)
            val legacy = JsonObject((SearchTestData.item() - "hasAudio") + ("libraryPath" to JsonPrimitive("private/secret.flac")))
            server.enqueue(json(SearchTestData.page(listOf(legacy)).toString()))
            fails(SearchErrorCode.INVALID_RESPONSE, 200) { api.search("manual") }
            server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals(2, server.requestCount) // Auth plus one search, no legacy fallback.
        }
    }

    @Test fun documentedErrorsRetainTypedStatusCodesAndNeverAutomaticallyReplayOrFallBack() = runBlocking {
        fixture { server, sessions, identity ->
            val api = SearchClient(sessions, identity)
            val cases = listOf(400 to SearchErrorCode.INVALID_SEARCH_REQUEST, 410 to SearchErrorCode.SEARCH_EXPIRED,
                503 to SearchErrorCode.SEARCH_INDEX_NOT_READY, 503 to SearchErrorCode.SEARCH_UNAVAILABLE,
                503 to SearchErrorCode.SEARCH_BUSY)
            for ((index, case) in cases.withIndex()) {
                val (status, code) = case
                server.enqueue(json("""{"code":"${code.name}","error":"private/secret/query/cursor"}""", status)
                    .setHeader("Retry-After", "0"))
                val failure = fails(code, status) { api.search("private-query") }
                assertTrue(failure.retryAfter)
                assertFalse(failure.transient)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/search", request.requestUrl!!.encodedPath)
                assertEquals(index + 2, server.requestCount)
            }
            for ((status, body) in listOf(503 to """{"code":"SEARCH_EXPIRED","error":"private/secret"}""",
                    400 to """{"error":"private/secret"}""", 403 to "{}", 404 to "{}")) {
                server.enqueue(json(body, status))
                fails(SearchErrorCode.HTTP_ERROR, status) { api.next("opaque") }
                server.takeRequest(2, TimeUnit.SECONDS)
            }
            assertEquals(10, server.requestCount)
        }
    }

    @Test fun serverBusyIndexNotReadyAndRateLimitAreNotTransientButTemporaryServiceFailureIs() = runBlocking {
        fixture { server, sessions, identity ->
            val api = SearchClient(sessions, identity)
            val cases = listOf(
                Triple(503, SearchErrorCode.SEARCH_BUSY, false),
                Triple(503, SearchErrorCode.SEARCH_INDEX_NOT_READY, false),
                Triple(503, SearchErrorCode.SEARCH_UNAVAILABLE, true),
                Triple(429, SearchErrorCode.HTTP_ERROR, false),
                Triple(502, SearchErrorCode.HTTP_ERROR, true),
            )
            for ((index, case) in cases.withIndex()) {
                val (status, code, transient) = case
                server.enqueue(json("""{"code":"${code.name}","error":"fixture"}""", status))
                val failure = fails(code, status) { api.search("query") }
                assertFalse(failure.retryAfter)
                assertEquals(transient, failure.transient)
                assertEquals(index + 2, server.requestCount)
            }
        }
    }

    @Test fun compressedBusy503KeepsItsSemanticCodeAndCannotBeReplayed() = runBlocking {
        fixture { server, sessions, identity ->
            val data = ByteArrayOutputStream()
            GZIPOutputStream(data).use { it.write("""{"code":"SEARCH_BUSY","error":"fixture"}""".toByteArray()) }
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip").setBody(Buffer().write(data.toByteArray())))
            val failure = fails(SearchErrorCode.SEARCH_BUSY, 503) { SearchClient(sessions, identity).search("query") }
            assertFalse(failure.transient)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun invalidRequestsNeverDispatchAndMalformedSuccessesAreSanitized() = runBlocking {
        fixture { server, sessions, identity ->
            val api = SearchClient(sessions, identity)
            for (query in listOf("", " ", "a\nb", "a".repeat(201), "\uD800"))
                fails(SearchErrorCode.INVALID_SEARCH_REQUEST, 0) { api.search(query) }
            for (limit in listOf(0, 101)) fails(SearchErrorCode.INVALID_SEARCH_REQUEST, 0) { api.search("valid", limit = limit) }
            for (cursor in listOf("", " ", "bad\ncursor", "x".repeat(513)))
                fails(SearchErrorCode.INVALID_SEARCH_REQUEST, 0) { api.next(cursor) }
            assertEquals(1, server.requestCount)
            for (response in listOf(json("""{"error":"private/secret"}"""),
                    json(SearchTestData.page().toString()).setHeader("Content-Type", "text/plain"),
                    json(" ".repeat(2 * 1024 * 1024 + 1)))) {
                server.enqueue(response)
                // Invalid transport content/size may be rejected before a FeatureResponse exists.
                val failure = runCatching { api.search("valid") }.exceptionOrNull()
                assertTrue(failure is SearchException)
                assertEquals(SearchErrorCode.INVALID_RESPONSE, (failure as SearchException).code)
                assertEquals("Search request failed", failure.message)
                assertNull(failure.cause)
                server.takeRequest(2, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun retiredIdentityCannotDispatchAndAuthoritative401RetiresItsSession() = runBlocking {
        fixture { server, sessions, identity ->
            val retired = SearchClient(sessions, identity.copy(incarnation = identity.incarnation + 1))
            assertTrue(runCatching { retired.search("query") }.exceptionOrNull() is CancellationException)
            assertEquals(1, server.requestCount)
            server.enqueue(json("{}", 401))
            assertTrue(runCatching { SearchClient(sessions, identity).next("opaque") }.exceptionOrNull() is CancellationException)
            assertTrue(sessions.state.value is AuthState.SignedOut)
            assertFalse(sessions.isCurrent(identity))
            assertEquals(2, server.requestCount)
        }
    }

    private suspend fun fails(code: SearchErrorCode, status: Int, action: suspend () -> Any): SearchException {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected sanitized SearchException", failure is SearchException)
        assertEquals(code, (failure as SearchException).code)
        assertEquals(status, failure.status)
        assertEquals("Search request failed", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("private/secret"))
        assertFalse(failure.toString().contains("private-query"))
        return failure
    }

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
            val transport = OkHttpAuthApi.defaultClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val sessions = SessionRepository(Sessions(StoredSession(origin.value, cookie, summary)), object : LoginStore {
                override fun read(): RememberedLogin? = null
                override fun write(value: RememberedLogin) = Unit
            }, AuthApiFactory { OkHttpAuthApi(it, transport) }, scope)
            server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"$role","csrfToken":"fixture-csrf"}"""))
            try {
                sessions.start()
                val state = withTimeout(3000) { sessions.state.first { it is AuthState.Authenticated } } as AuthState.Authenticated
                server.takeRequest(2, TimeUnit.SECONDS)
                block(server, sessions, state.identity)
            } finally {
                scope.cancel(); scope.coroutineContext.job.join()
                transport.connectionPool.evictAll(); transport.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)
}
