package dev.spofify.mobile.core.search

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.http.FeatureResponse
import dev.spofify.mobile.core.http.FeatureTransport
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.playback.PlaybackConnection
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic, noncooperative transports exercise fences on failure as well as success. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchOwnershipTest {
    @Test fun liveConnectionFailureIsSanitizedAndNeverRetried() = runTest {
        val f = fixture()
        f.transport.exchange = { throw IOException("private/query/cursor/secret") }
        val failure = runCatching { f.client.search("private-query") }.exceptionOrNull()
        assertTrue(failure is SearchException)
        assertEquals(SearchErrorCode.CONNECTION_FAILED, (failure as SearchException).code)
        assertEquals(0, failure.status)
        assertEquals("Search request failed", failure.message)
        assertNull(failure.cause)
        assertEquals(1, f.transport.requests)
    }

    @Test fun retiredInFlightSuccessHttpErrorAndNoncooperativeFailureCannotEscapeIntoNewEpoch() = runTest {
        for (variant in 0..3) {
            val f = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.transport.exchange = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                when (variant) {
                    0 -> response(SearchTestData.page().toString())
                    1 -> response("""{"error":"private/query/cursor/secret"}""")
                    2 -> throw IOException("private/query/cursor/secret")
                    else -> response("""{"code":"SEARCH_BUSY","error":"private/secret"}""", 503)
                }
            }
            val pending = async { f.client.search("query") }
            entered.await()
            f.sessions.signOut()
            runCurrent()
            f.sessions.signIn(f.identity.origin, "fixture-user", "fixture-password")
            runCurrent()
            val next = (f.sessions.state.value as AuthState.Authenticated).identity
            assertEquals(f.identity.accountId, next.accountId)
            assertNotEquals(f.identity.incarnation, next.incarnation)
            release.complete(Unit)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertTrue(f.sessions.isCurrent(next))
            assertEquals(1, f.transport.requests)
        }
    }

    @Test fun cancellationWithoutRetirementPreservesTheAccountAndDoesNotReplay() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.transport.exchange = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            response(SearchTestData.page().toString())
        }
        val pending = async { f.client.next("opaque-cursor") }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(f.sessions.isCurrent(f.identity))
        assertEquals(1, f.transport.requests)
    }

    private data class Fixture(val sessions: SessionRepository, val identity: AccountIdentity, val transport: Transport) {
        val client = SearchClient(sessions, identity)
    }

    private fun TestScope.fixture(): Fixture {
        val transport = Transport()
        val store = object : SessionStore {
            var value: StoredSession? = StoredSession(transport.origin.value, "fixture-cookie", transport.summary)
            override fun read() = value
            override fun write(value: StoredSession?) { this.value = value }
        }
        val sessions = SessionRepository(store, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin) = Unit
        }, AuthApiFactory { transport }, backgroundScope, StandardTestDispatcher(testScheduler))
        sessions.start()
        runCurrent()
        return Fixture(sessions, (sessions.state.value as AuthState.Authenticated).identity, transport)
    }

    private class Transport : AuthApi, FeatureTransport {
        override val origin = ServerOrigin.parse("http://search.test:6030")
        val summary = SessionSummary("account", "fixture-user", "user", "fixture-csrf")
        var requests = 0
        var exchange: suspend () -> FeatureResponse = { response(SearchTestData.page().toString()) }
        override suspend fun login(username: String, password: String) = AuthReply(summary, "fixture-cookie", null)
        override suspend fun session(cookie: String) = AuthReply(summary, null, null)
        override suspend fun logout(cookie: String, csrfToken: String) = Unit
        override fun validateCookie(cookie: String) = Unit
        override fun cancel() = Unit
        override suspend fun get(segments: List<String>, query: List<Pair<String, String>>, cookie: String,
            artwork: Boolean, preserveUnavailableBody: Boolean, onHeaders: (Int, String?) -> Unit): FeatureResponse {
            requests++
            check(segments == listOf("search") && !artwork && preserveUnavailableBody)
            return exchange().also { onHeaders(it.status, null) }
        }
        override suspend fun mutate(segments: List<String>, method: String, body: String, cookie: String,
            csrfToken: String, onHeaders: (Int, String?) -> Unit): FeatureResponse = error("Search must not mutate")
        override suspend fun socket(cookie: String, hello: String, authority: FeatureAuthority,
            onHeaders: (Int, String?) -> Unit): PlaybackConnection = error("Search must not connect playback")
        override suspend fun audio(trackId: String, position: Long, length: Long?, cookie: String,
            authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit): AudioStream = error("Search must not read audio")
    }

    companion object {
        private fun response(body: String, status: Int = 200) = FeatureResponse(status, body.toByteArray(), "application/json")
    }
}
