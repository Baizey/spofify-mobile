package dev.spofify.mobile.core

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Credential-free lifetime capability used by cache reads; no cache policy belongs in core. */
class SpofifyClientAuthenticationLifetimeTest {
    private lateinit var web: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var client: SpofifyClient
    private val now = AtomicLong(System.currentTimeMillis())
    private val changes = Collections.synchronizedList(mutableListOf<AuthenticationChange>())

    @Before fun setup() {
        web = MockWebServer().also { it.start() }
        store = MemorySessionStore()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), store, now::get)
        client.onAuthenticationChanged = { changes.add(it) }
    }

    @After fun teardown() {
        client.httpClient.dispatcher.cancelAll()
        web.shutdown()
    }

    @Test fun identityExistsOnlyAfterValidatedInstallAndContainsNoSecrets() = runBlocking {
        assertNull(client.validatedSessionIdentity())
        assertFalse(client.hasValidSession(Session("a1", "listener", "user", "csrf-secret")))
        val session = login()
        val identity = requireNotNull(client.validatedSessionIdentity())
        assertEquals(client.server.origin, identity.origin)
        assertEquals("a1", identity.accountId)
        assertTrue(client.hasValidSession(session))
        assertFalse(client.hasValidSession(session.copy())) // Structural equality is not authority.
        assertEquals(listOf(AuthenticationChangeKind.RESET, AuthenticationChangeKind.VALIDATED), changes.map { it.kind })
        assertEquals(identity, changes.last().identity)
        assertTrue(changes[1].revision > changes[0].revision)
        val notifications = changes.size
        repeat(10) {
            assertEquals(identity, client.validatedSessionIdentity())
            assertTrue(client.hasValidSession(session))
        }
        assertEquals(notifications, changes.size) // Reads do not cause notification traffic.
        listOf("csrf-secret", "cookie-secret", "password", "listener").forEach {
            assertFalse(identity.toString().contains(it))
            assertFalse(changes.toString().contains(it))
        }
    }

    @Test fun equalValuedReloginAndAccountAbaCannotReviveCapturedSession() = runBlocking {
        val a = login()
        val first = requireNotNull(client.validatedSessionIdentity())
        val b = login("b2")
        assertEquals("b2", client.validatedSessionIdentity()!!.accountId)
        assertFalse(client.hasValidSession(a))
        assertTrue(client.hasValidSession(b))
        val nextA = login()
        assertEquals(a, nextA)
        assertNotSame(a, nextA)
        assertFalse(client.hasValidSession(a))
        assertFalse(client.hasValidSession(b))
        assertTrue(client.hasValidSession(nextA))
        assertNotEquals(first.incarnation, client.validatedSessionIdentity()!!.incarnation)
        assertEquals(3, changes.count { it.kind == AuthenticationChangeKind.VALIDATED })
        client.clearSession()
        assertNull(client.validatedSessionIdentity())
        assertFalse(client.hasValidSession(nextA))
        assertEquals(AuthenticationChangeKind.RESET, changes.last().kind)
        assertNull(changes.last().identity)
    }

    @Test fun savedSummaryAndProvisionalRestoreCookieDoNotAuthorizeAnyCachedRead() = runBlocking {
        val old = login()
        val oldIdentity = requireNotNull(client.validatedSessionIdentity())
        changes.clear()
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.IO) { client.restore() }
        try {
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(store.value)
            assertEquals(1, client.httpClient.cookieJar.loadForRequest(client.server.url()).size)
            assertNull(client.validatedSessionIdentity())
            assertNull(client.currentSession())
            assertFalse(client.hasValidSession(old))
            assertEquals(listOf(AuthenticationChangeKind.DORMANT), changes.map { it.kind })
        } finally {
            pending.cancelAndJoin()
        }
        assertNotNull(store.value)
        assertNull(client.validatedSessionIdentity())
        assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
        assertTrue(changes.all { it.kind == AuthenticationChangeKind.DORMANT && it.identity == null })
        web.enqueue(jsonResponse(SESSION_JSON))
        val restored = requireNotNull(client.restore())
        web.takeRequest()
        assertEquals(old, restored)
        assertNotSame(old, restored)
        assertFalse(client.hasValidSession(old))
        assertTrue(client.hasValidSession(restored))
        assertNotEquals(oldIdentity.incarnation, client.validatedSessionIdentity()!!.incarnation)
        assertEquals(AuthenticationChangeKind.VALIDATED, changes.last().kind)
    }

    @Test fun offlineRestoreIsDormantNotAnInvalidationAndPreservesExplicitRetry() = runBlocking {
        login()
        val saved = store.value
        changes.clear()
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { client.restore(); fail("Expected transport failure") } catch (_: IOException) { }
        web.takeRequest()
        assertEquals(saved, store.value)
        assertNull(client.validatedSessionIdentity())
        assertNull(client.currentSession())
        assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.all { it.kind == AuthenticationChangeKind.DORMANT })
        web.enqueue(jsonResponse(SESSION_JSON))
        assertNotNull(client.restore())
        web.takeRequest()
        assertNotNull(client.validatedSessionIdentity())
    }

    @Test fun temporaryRestoreRejectionDoesNotRequestAStorageWipe() = runBlocking {
        login()
        val saved = store.value
        for (status in listOf(429, 503)) {
            changes.clear()
            web.enqueue(jsonResponse("{}", status))
            assertEquals(status, apiFailure { client.restore() }.status)
            web.takeRequest()
            assertNull(client.validatedSessionIdentity())
            assertEquals(saved, store.value)
            assertTrue(changes.all { it.kind == AuthenticationChangeKind.DORMANT })
        }
    }

    @Test fun expiredSavedCookieInvalidatesWithoutContactingServer() = runBlocking {
        store.value = StoredSession(client.server.origin,
            "spofify_session=old; Path=/; HttpOnly; Expires=Thu, 01 Jan 1970 00:00:01 GMT",
            Session("a1", "listener", "user", "saved-csrf"))
        assertNull(client.restore())
        assertNull(client.validatedSessionIdentity())
        assertNull(store.value)
        assertEquals(listOf(AuthenticationChangeKind.DORMANT, AuthenticationChangeKind.INVALIDATED), changes.map { it.kind })
        assertEquals(0, web.requestCount)
    }

    @Test fun eachReadCapabilityDetectsExpirySynchronouslyWithoutANetworkRequest() = runBlocking {
        for (read in listOf<(Session) -> Boolean>(
            { client.hasValidSession(it) },
            { client.validatedSessionIdentity() != null },
            { client.currentSession() != null },
            { client.httpClient.cookieJar.loadForRequest(client.server.url()).isNotEmpty() },
        )) {
            now.set(System.currentTimeMillis())
            val session = login()
            changes.clear()
            val requests = web.requestCount
            val expiry = client.httpClient.cookieJar.loadForRequest(client.server.url()).single().expiresAt
            now.set(expiry) // Equality is already expired, not only expiry + 1.
            assertFalse(read(session))
            assertEquals(AuthenticationChangeKind.EXPIRED, changes.single().kind)
            assertNull(changes.single().identity)
            assertNull(store.value)
            assertFalse(client.hasValidSession(session))
            assertNull(client.currentSession())
            assertNull(client.validatedSessionIdentity())
            assertEquals(1, changes.size)
            assertEquals(requests, web.requestCount)
        }
    }

    @Test fun sharedUnauthorizedFencesSynchronouslyAndKeepsLegacyCallbackSemantics() = runBlocking {
        val session = login()
        changes.clear()
        var unauthorized = 0
        client.onUnauthorized = {
            unauthorized++
            assertNull(client.currentSession())
        }
        web.enqueue(jsonResponse("{}", 401))
        client.httpClient.newCall(Request.Builder().url(client.server.url("audio")).build()).execute().close()
        web.takeRequest()
        assertEquals(1, unauthorized)
        assertFalse(client.hasValidSession(session))
        assertEquals(AuthenticationChangeKind.INVALIDATED, changes.single().kind)
        assertNull(changes.single().identity)
        assertNull(store.value)
        web.enqueue(jsonResponse("{}", 401))
        assertEquals(401, apiFailure { client.login("listener", "password") }.status)
        web.takeRequest()
        assertEquals(1, unauthorized) // Failed login is not a shared unauthorized notification.
    }

    @Test fun oldMediaUnauthorizedCannotEmitAnInvalidationAfterSuccessorInstall() = runBlocking {
        login()
        val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/old-audio") {
                check(release.await(5, TimeUnit.SECONDS))
                jsonResponse("{}", 401)
            } else loginResponse()
        }
        var unauthorized = 0
        client.onUnauthorized = { unauthorized++ }
        val pending = async(Dispatchers.IO) {
            client.httpClient.newCall(Request.Builder().url(client.server.url("old-audio")).build()).execute().use { it.code }
        }
        try {
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            val successor = client.login("listener", "password")
            web.takeRequest()
            changes.clear()
            release.countDown()
            assertEquals(401, pending.await())
            assertTrue(client.hasValidSession(successor))
            assertTrue(changes.isEmpty())
            assertEquals(0, unauthorized)
        } finally {
            release.countDown()
            pending.await()
        }
    }

    @Test fun aDifferentClientCannotUseTheOriginalSessionReferenceEvenAfterSameAccountRestore() = runBlocking {
        val original = login()
        val other = SpofifyClient(client.server, store)
        assertNull(other.validatedSessionIdentity())
        assertFalse(other.hasValidSession(original))
        web.enqueue(jsonResponse(SESSION_JSON))
        val restored = requireNotNull(other.restore())
        web.takeRequest()
        assertEquals(original, restored)
        assertFalse(other.hasValidSession(original))
        assertFalse(client.hasValidSession(restored))
        assertTrue(client.hasValidSession(original))
        assertTrue(other.hasValidSession(restored))
    }

    @Test fun logoutFailureStillInvalidatesRatherThanLeavingDormantAuthority() = runBlocking {
        val session = login()
        changes.clear()
        web.enqueue(jsonResponse("{}", 503))
        assertEquals(503, apiFailure { client.logout() }.status)
        web.takeRequest()
        assertFalse(client.hasValidSession(session))
        assertNull(store.value)
        assertEquals(AuthenticationChangeKind.INVALIDATED, changes.single().kind)
    }

    @Test fun callbacksForInstallRestoreResetInvalidationAndExpiryRunWithoutClientLock() = runBlocking {
        val reader = Executors.newSingleThreadExecutor()
        val observed = Collections.synchronizedList(mutableListOf<AuthenticationChangeKind>())
        client.onAuthenticationChanged = { change ->
            // A listener may synchronously take an app lock whose owner needs this client.
            // This cross-thread read would time out if the callback held the client lock.
            reader.submit<Boolean> {
                client.validatedSessionIdentity()
                client.currentSession()
                true
            }.get(2, TimeUnit.SECONDS)
            observed.add(change.kind)
        }
        try {
            login()
            web.enqueue(jsonResponse(SESSION_JSON))
            assertNotNull(client.restore())
            web.takeRequest()
            now.set(client.httpClient.cookieJar.loadForRequest(client.server.url()).single().expiresAt)
            assertNull(client.validatedSessionIdentity())
            now.set(System.currentTimeMillis())
            login()
            web.enqueue(jsonResponse("{}", 401))
            client.httpClient.newCall(Request.Builder().url(client.server.url("audio")).build()).execute().close()
            web.takeRequest()
            assertTrue(observed.containsAll(AuthenticationChangeKind.entries))
        } finally {
            reader.shutdownNow()
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun expiryPersistenceFailureNeverThrowsFromAnyAuthReadAndStillFencesSynchronously() = runBlocking {
        val failClear = AtomicBoolean(false)
        val throwingStore = object : SessionStore {
            override fun read() = store.value
            override fun write(value: StoredSession?) {
                if (value == null && failClear.get()) throw IOException("private persistence detail")
                store.write(value)
            }
        }
        client = SpofifyClient(client.server, throwingStore, now::get)
        client.onAuthenticationChanged = { changes.add(it) }
        for (read in listOf<(Session) -> Boolean>(
            { client.hasValidSession(it) },
            { client.validatedSessionIdentity() != null },
            { client.currentSession() != null },
            { client.httpClient.cookieJar.loadForRequest(client.server.url()).isNotEmpty() },
        )) {
            failClear.set(false)
            now.set(System.currentTimeMillis())
            val session = login()
            val saved = store.value
            val expiry = client.httpClient.cookieJar.loadForRequest(client.server.url()).single().expiresAt
            changes.clear()
            failClear.set(true)
            now.set(expiry)
            assertFalse(read(session)) // No try/catch: expiry reads must not leak persistence errors.
            assertEquals(AuthenticationChangeKind.EXPIRED, changes.single().kind)
            assertNull(changes.single().identity)
            assertFalse(client.hasValidSession(session))
            assertNull(client.currentSession())
            assertNull(client.validatedSessionIdentity())
            assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
            assertEquals(1, changes.size)
            assertEquals(saved, store.value) // Failed disk clearing does not mean bytes were removed.
        }
    }

    @Test fun failedPersistenceStillDeliversExplicitResetAndInvalidationFences() = runBlocking {
        val failClear = AtomicBoolean(false)
        val throwingStore = object : SessionStore {
            override fun read() = store.value
            override fun write(value: StoredSession?) {
                if (value == null && failClear.get()) throw IOException("private persistence detail")
                store.write(value)
            }
        }
        client = SpofifyClient(client.server, throwingStore, now::get)
        client.onAuthenticationChanged = { changes.add(it) }
        for (kind in listOf(AuthenticationChangeKind.RESET, AuthenticationChangeKind.INVALIDATED)) {
            failClear.set(false)
            now.set(System.currentTimeMillis())
            val session = login()
            changes.clear()
            failClear.set(true)
            if (kind == AuthenticationChangeKind.INVALIDATED) web.enqueue(jsonResponse("{}", 401))
            try {
                when (kind) {
                    AuthenticationChangeKind.RESET -> client.clearSession()
                    else -> client.httpClient.newCall(Request.Builder().url(client.server.url("audio")).build()).execute().close()
                }
                fail("Expected safe persistence failure")
            } catch (error: IOException) {
                assertFalse(error.toString().contains("private persistence detail"))
            }
            if (kind == AuthenticationChangeKind.INVALIDATED) web.takeRequest()
            assertEquals(kind, changes.single().kind)
            assertNull(changes.single().identity)
            assertFalse(client.hasValidSession(session))
            assertNull(client.currentSession())
            assertNull(client.validatedSessionIdentity())
            assertNotNull(store.value) // Failed deletion does not mean disk state was cleared.
        }
    }

    @Test fun delayedOlderCallbackCannotBlockSuccessorLoginAndRevisionsFenceDeliveryAba() = runBlocking {
        login()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        val acceptedRevision = AtomicLong(-1)
        client.onAuthenticationChanged = { change ->
            if (change.kind == AuthenticationChangeKind.RESET && blockOnce.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            // The same monotonic fence required of AppGraph's atomic listener.
            acceptedRevision.accumulateAndGet(change.revision) { old, next -> maxOf(old, next) }
        }
        val oldReset = async(Dispatchers.IO) { client.clearSession() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val successor = login()
            assertTrue(client.hasValidSession(successor))
            val latest = acceptedRevision.get()
            release.countDown()
            oldReset.await()
            assertEquals(latest, acceptedRevision.get())
            assertTrue(client.hasValidSession(successor))
        } finally {
            release.countDown()
            oldReset.await()
        }
    }

    private suspend fun login(accountId: String = "a1"): Session {
        web.enqueue(loginResponse().setBody(SESSION_JSON.replace("\"a1\"", "\"$accountId\"")))
        val session = client.login("listener", "password")
        web.takeRequest()
        return session
    }
}
