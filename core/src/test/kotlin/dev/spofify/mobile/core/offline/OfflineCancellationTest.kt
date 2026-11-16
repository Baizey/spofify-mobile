package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.http.FeatureResponse
import dev.spofify.mobile.core.http.FeatureTransport
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.playback.PlaybackConnection
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineCancellationTest {
    @Test fun delayedOldOwnerFailuresRemainCancellationAfterSameAccountReauthentication() = runTest {
        for (failure in failures()) {
            val f = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable?>()
            f.transport.exchange = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                throw failure
            }
            val pending = launch {
                // Observe the exception AT the client boundary, before a cancelled outer Job can mask it.
                observed.complete(runCatching { f.client.prepareMedia("one") }.exceptionOrNull())
            }
            entered.await()
            f.sessions.useManualLogin()
            f.sessions.signIn(f.transport.origin.value, "fixture-user", "fixture-password")
            runCurrent()
            val successor = (f.sessions.state.value as AuthState.Authenticated).identity
            assertEquals(f.identity.accountId, successor.accountId)
            assertNotEquals(f.identity.incarnation, successor.incarnation)
            release.complete(Unit)
            assertTrue(observed.await() is CancellationException)
            pending.join()
            assertTrue(f.sessions.isCurrent(successor))
            assertEquals(1, f.transport.reads.get())
        }
    }

    @Test fun callerCancelledSameOwnerCannotReceiveSafeFailureFromNoncooperativeWork() = runTest {
        for (failure in failures()) {
            val f = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable?>()
            f.transport.exchange = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                throw failure
            }
            val pending = launch {
                observed.complete(runCatching { f.client.prepareMedia("one") }.exceptionOrNull())
            }
            entered.await()
            pending.cancel()
            release.complete(Unit)
            assertTrue(observed.await() is CancellationException)
            pending.join()
            assertTrue(f.sessions.isCurrent(f.identity))
            assertEquals(1, f.transport.reads.get())
        }
    }

    private fun failures(): List<Exception> = listOf(
        OfflineException(OfflineFailure.LIMIT_EXCEEDED),
        AuthFailure(AuthFailure.Kind.INVALID_RESPONSE),
        IOException("private failure"),
        IllegalArgumentException("private failure"),
    )

    private data class Fixture(val sessions: SessionRepository, val identity: AccountIdentity, val transport: Transport) {
        val client = OfflineClient(sessions, identity)
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
        return Fixture(sessions, (sessions.state.value as AuthState.Authenticated).identity, transport)
    }

    private class Transport : AuthApi, FeatureTransport {
        override val origin = ServerOrigin.parse("http://offline.test:6030")
        val summary = SessionSummary("account", "fixture-user", "user", "fixture-csrf")
        val reads = AtomicInteger()
        var exchange: suspend () -> FeatureResponse = { error("Unexpected request") }
        override suspend fun login(username: String, password: String) = AuthReply(summary, "fixture-cookie", null)
        override suspend fun session(cookie: String) = AuthReply(summary, null, null)
        override suspend fun logout(cookie: String, csrfToken: String) = Unit
        override fun validateCookie(cookie: String) = Unit
        override fun cancel() = Unit // Deliberately ignores cancellation to exercise failure-side fences.
        override suspend fun get(segments: List<String>, query: List<Pair<String, String>>, cookie: String,
            artwork: Boolean, onHeaders: (Int, String?) -> Unit): FeatureResponse {
            reads.incrementAndGet()
            return exchange().also { onHeaders(it.status, null) }
        }
        override suspend fun mutate(segments: List<String>, method: String, body: String, cookie: String,
            csrfToken: String, onHeaders: (Int, String?) -> Unit): FeatureResponse = error("No mutation expected")
        override suspend fun socket(cookie: String, hello: String, authority: FeatureAuthority,
            onHeaders: (Int, String?) -> Unit): PlaybackConnection = error("No playback expected")
        override suspend fun audio(trackId: String, position: Long, length: Long?, cookie: String,
            authority: FeatureAuthority, onHeaders: (Int, String?) -> Unit): AudioStream = error("No audio expected")
    }
}
