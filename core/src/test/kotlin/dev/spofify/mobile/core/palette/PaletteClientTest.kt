package dev.spofify.mobile.core.palette

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.http.FeatureResponse
import dev.spofify.mobile.core.http.FeatureTransport
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.playback.PlaybackConnection
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Entirely in-memory transport: no socket, server, credentials store, or network probes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PaletteClientTest {
    @Test fun readsAndChoicesUseOnlyExactAccountRouteAndCasJson() = runTest {
        val f = fixture()
        val original = f.client.read()
        assertEquals(0L, original.revision)
        assertEquals(PaletteId.JADE, original.paletteId)
        f.transport.exchange = { response(PaletteTestData.document(id = "ember", revision = "1")) }
        val chosen = f.client.choose(PaletteId.EMBER, 0)
        assertEquals(PaletteId.EMBER, chosen.paletteId)
        assertEquals(1L, chosen.revision)
        assertEquals(2, f.transport.requests.size)
        val read = f.transport.requests[0]
        val write = f.transport.requests[1]
        for (request in listOf(read, write)) {
            assertEquals(listOf("api", "me", "palette"), request.path)
            assertTrue(request.query.isEmpty())
            assertEquals("fixture-cookie", request.cookie)
        }
        assertEquals("GET", read.method)
        assertFalse(read.artwork)
        assertNull(read.body)
        assertNull(read.csrf)
        assertEquals("PUT", write.method)
        assertEquals("fixture-csrf", write.csrf)
        val body = requireNotNull(write.body)
        assertEquals(Json.parseToJsonElement("""{"paletteId":"ember","expectedRevision":0,"catalogVersion":1}"""),
            Json.parseToJsonElement(body))
        assertTrue(body.toByteArray().size <= 1024)
        assertFalse(chosen.toString().contains("fixture-cookie"))
        assertFalse(chosen.toString().contains("fixture-csrf"))
    }

    @Test fun sameChoiceStillUsesReturnedIncrementAndMaximumSafeRevisionIsPreserved() = runTest {
        val f = fixture()
        f.transport.exchange = { response(PaletteTestData.document(revision = "42")) }
        assertEquals(42L, f.client.choose(PaletteId.JADE, 41).revision)
        f.transport.exchange = { response(PaletteTestData.document(id = "violet", revision = "9007199254740991")) }
        assertEquals(PaletteParser.MAX_REVISION, f.client.choose(PaletteId.VIOLET, PaletteParser.MAX_REVISION - 1).revision)
        val body = Json.parseToJsonElement(f.transport.requests.last().body!!).jsonObject
        assertEquals(9_007_199_254_740_990L, body.getValue("expectedRevision").jsonPrimitive.long)
    }

    @Test fun invalidExpectedRevisionNeverDispatchesOrBecomesUncertain() = runTest {
        val f = fixture()
        for (revision in listOf(-1L, PaletteParser.MAX_REVISION, PaletteParser.MAX_REVISION + 1, Long.MAX_VALUE)) {
            val failure = failure { f.client.choose(PaletteId.JADE, revision) }
            assertEquals(PaletteFailure.INVALID, failure.failure)
            assertFalse(failure.outcomeUncertain)
        }
        assertTrue(f.transport.requests.isEmpty())
    }

    @Test fun semanticFailuresAreTypedBoundedSanitizedAndNeverRetried() = runTest {
        val f = fixture()
        data class Case(val status: Int, val code: String, val failure: PaletteFailure, val uncertain: Boolean = false)
        val cases = listOf(
            Case(409, "PALETTE_REVISION_CONFLICT", PaletteFailure.CONFLICT),
            Case(409, "PALETTE_CATALOG_CONFLICT", PaletteFailure.CATALOG),
            Case(400, "INVALID_PALETTE", PaletteFailure.INVALID),
            Case(413, "", PaletteFailure.INVALID),
            Case(415, "", PaletteFailure.INVALID),
            Case(403, "", PaletteFailure.UNAUTHORIZED),
            Case(500, "PALETTE_STORAGE_FAILED", PaletteFailure.UNAVAILABLE, true),
            Case(503, "", PaletteFailure.UNAVAILABLE, true),
            Case(409, "UNKNOWN", PaletteFailure.INVALID_RESPONSE, true),
            Case(202, "", PaletteFailure.INVALID_RESPONSE, true),
        )
        for ((index, case) in cases.withIndex()) {
            f.transport.exchange = { response("""{"code":"${case.code}","error":"fixture-secret/private/path"}""", case.status) }
            val failure = failure { f.client.choose(PaletteId.JADE, 0) }
            assertEquals(case.failure, failure.failure)
            assertEquals(case.uncertain, failure.outcomeUncertain)
            assertEquals(index + 1, f.transport.requests.size)
        }
        // A shared parser error need not carry a semantic code.
        f.transport.exchange = { response("""{"error":"fixture-secret"}""", 400) }
        assertEquals(PaletteFailure.INVALID, failure { f.client.read() }.failure)
        // Missing/oversized/ambiguous conflict bodies cannot invent a catalog/revision distinction.
        for (body in listOf("", " ".repeat(4097),
                """{"code":"PALETTE_REVISION_CONFLICT","code":"PALETTE_CATALOG_CONFLICT","error":"fixture-secret"}""")) {
            f.transport.exchange = { response(body, 409) }
            assertEquals(PaletteFailure.INVALID_RESPONSE, failure { f.client.read() }.failure)
        }
    }

    @Test fun malformedSuccessAndMismatchedPutResultFailClosedWithWriteUncertainty() = runTest {
        val f = fixture()
        val bodies = listOf(
            PaletteTestData.document(owner = "other-account", id = "ember", revision = "1"),
            PaletteTestData.document(id = "jade", revision = "1"),
            PaletteTestData.document(id = "ember", revision = "0"),
            PaletteTestData.document(id = "ember", revision = "2"),
            PaletteTestData.document(id = "ember", revision = "9007199254740992"),
            " ".repeat(16 * 1024 + 1),
            """{"error":"fixture-secret"}""",
        )
        for ((index, body) in bodies.withIndex()) {
            f.transport.exchange = { response(body) }
            val failure = failure { f.client.choose(PaletteId.EMBER, 0) }
            assertEquals(PaletteFailure.INVALID_RESPONSE, failure.failure)
            assertTrue(failure.outcomeUncertain)
            assertEquals(index + 1, f.transport.requests.size)
        }
        f.transport.exchange = { response(PaletteTestData.document(), mediaType = "text/plain") }
        assertEquals(PaletteFailure.INVALID_RESPONSE, failure { f.client.read() }.failure)
        f.transport.exchange = { response(PaletteTestData.document(version = "2")) }
        assertEquals(PaletteFailure.CATALOG, failure { f.client.read() }.failure)
    }

    @Test fun connectionLossAfterPutIsUncertainAndDoesNotReadOrReplay() = runTest {
        val f = fixture()
        f.transport.exchange = { throw IOException("fixture-secret/private/path") }
        val failure = failure { f.client.choose(PaletteId.EMBER, 0) }
        assertEquals(PaletteFailure.UNAVAILABLE, failure.failure)
        assertTrue(failure.outcomeUncertain)
        runCurrent()
        assertEquals(1, f.transport.requests.size)
        assertEquals("PUT", f.transport.requests.single().method)
        f.transport.exchange = { response(PaletteTestData.document(id = "ember", revision = "1")) }
        assertEquals(1L, f.client.read().revision) // Explicit reconciliation only.
        assertEquals(listOf("PUT", "GET"), f.transport.requests.map { it.method })
    }

    @Test fun callerCancellationPropagatesWithoutReplayAndDoesNotRetireTheAccount() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.transport.exchange = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            response(PaletteTestData.document(id = "ember", revision = "1"))
        }
        val pending = async { f.client.choose(PaletteId.EMBER, 0) }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(f.sessions.isCurrent(f.identity))
        assertEquals(1, f.transport.requests.size)
    }

    @Test fun retiredIdentityCannotDispatchAndAuthoritative401RetiresTheAccount() = runTest {
        val f = fixture()
        val retired = PaletteClient(f.sessions, f.identity.copy(incarnation = f.identity.incarnation + 1))
        assertTrue(runCatching { retired.read() }.exceptionOrNull() is CancellationException)
        assertTrue(f.transport.requests.isEmpty())
        f.transport.exchange = { response("{}", 401) }
        assertTrue(runCatching { f.client.read() }.exceptionOrNull() is CancellationException)
        assertTrue(f.sessions.state.value is AuthState.SignedOut)
        assertFalse(f.sessions.isCurrent(f.identity))
        assertTrue(runCatching { f.client.choose(PaletteId.JADE, 0) }.exceptionOrNull() is CancellationException)
        assertEquals(1, f.transport.requests.size)
    }

    @Test fun lateWriteSuccessOrFailureCannotEscapeIntoRecreatedSameAccountSession() = runTest {
        for (lateFailure in listOf(false, true)) {
            val f = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.transport.exchange = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (lateFailure) throw IOException("fixture-secret")
                response(PaletteTestData.document(id = "ember", revision = "1"))
            }
            val pending = async { f.client.choose(PaletteId.EMBER, 0) }
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
            assertEquals(1, f.transport.requests.size)
            assertTrue(f.sessions.isCurrent(next))
        }
    }

    private suspend fun failure(action: suspend () -> Any): PaletteException {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue(failure is PaletteException)
        return (failure as PaletteException).also {
            assertEquals("Account palette request failed", it.message)
            assertNull(it.cause)
            assertFalse(it.toString().contains("fixture-secret"))
        }
    }

    private data class Fixture(val sessions: SessionRepository, val identity: AccountIdentity, val transport: Transport) {
        val client = PaletteClient(sessions, identity)
    }

    private fun TestScope.fixture(): Fixture {
        val transport = Transport()
        val store = object : SessionStore {
            var value: StoredSession? = StoredSession(transport.origin.value, "fixture-cookie", transport.summary)
            override fun read() = value
            override fun write(value: StoredSession?) { this.value = value }
        }
        val logins = object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin) = Unit
        }
        val sessions = SessionRepository(store, logins, AuthApiFactory { transport }, backgroundScope,
            StandardTestDispatcher(testScheduler))
        sessions.start()
        runCurrent()
        val identity = (sessions.state.value as AuthState.Authenticated).identity
        return Fixture(sessions, identity, transport)
    }

    private data class Request(
        val method: String, val path: List<String>, val query: List<Pair<String, String>>,
        val cookie: String, val body: String? = null, val csrf: String? = null, val artwork: Boolean = false,
    )

    private class Transport : AuthApi, FeatureTransport {
        override val origin = ServerOrigin.parse("http://palette.test:6030")
        val summary = SessionSummary("account", "fixture-user", "user", "fixture-csrf")
        val requests = mutableListOf<Request>()
        var exchange: suspend () -> FeatureResponse = { response(PaletteTestData.document()) }
        override suspend fun login(username: String, password: String) = AuthReply(summary, "fixture-cookie", null)
        override suspend fun session(cookie: String) = AuthReply(summary, null, null)
        override suspend fun logout(cookie: String, csrfToken: String) = Unit
        override fun validateCookie(cookie: String) = Unit
        override fun cancel() = Unit // Intentionally uncooperative to exercise retirement fences.

        override suspend fun get(
            segments: List<String>, query: List<Pair<String, String>>, cookie: String, artwork: Boolean,
            preserveUnavailableBody: Boolean,
            onHeaders: (Int, String?) -> Unit,
        ): FeatureResponse {
            check(!preserveUnavailableBody)
            requests += Request("GET", segments, query, cookie, artwork = artwork)
            return exchange().also { onHeaders(it.status, null) }
        }

        override suspend fun mutate(
            segments: List<String>, method: String, body: String, cookie: String, csrfToken: String,
            onHeaders: (Int, String?) -> Unit,
        ): FeatureResponse {
            requests += Request(method, segments, emptyList(), cookie, body, csrfToken)
            return exchange().also { onHeaders(it.status, null) }
        }

        override suspend fun socket(cookie: String, hello: String, authority: FeatureAuthority,
            onHeaders: (Int, String?) -> Unit): PlaybackConnection = error("Palette must not connect playback")
        override suspend fun audio(trackId: String, position: Long, length: Long?, cookie: String,
            authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit): AudioStream = error("Palette must not request audio")
    }

    companion object {
        private fun response(body: String, status: Int = 200, mediaType: String = "application/json; charset=utf-8") =
            FeatureResponse(status, body.toByteArray(Charsets.UTF_8), mediaType)
    }
}
