package dev.spofify.mobile.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** All destinations are synthetic loopback. No device, real account, or backend is needed. */
class SpofifyClientOfflineTest {
    private lateinit var web: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var client: SpofifyClient
    private val now = AtomicLong(System.currentTimeMillis())
    private var directProbePassed = false

    @Before fun setup() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        web = MockWebServer().also { it.start(loopback, 0) }
        store = MemorySessionStore()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), store, now::get)
        val request = client.server.url()
        if (request.host != loopback.hostAddress || request.port != web.port)
            fail("Synthetic listener/request endpoint mismatch; ${fixtureFailureDetails()}")
        // One fixture-liveness admission, not an HTTP attempt or a retry of the product call.
        // Explicit NO_PROXY applies only to this test socket, never to the product client.
        try {
            Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress(loopback, web.port), 1000) }
            directProbePassed = true
        } catch (error: IOException) {
            throw AssertionError("Synthetic TCP liveness admission failed; ${fixtureFailureDetails()}",
                networkError(error, TransportFailurePhase.CALL))
        }
        assertEquals("A bare TCP probe must not create an HTTP request", 0, web.requestCount)
    }

    @After fun teardown() {
        client.httpClient.dispatcher.cancelAll()
        client.httpClient.connectionPool.evictAll()
        client.httpClient.dispatcher.executorService.shutdown()
        web.shutdown()
    }

    @Test fun offlineRejectsApiArtworkMediaAndAuthBeforeAnyRequestOrDnsAttempt() = runBlocking {
        val saved = StoredSession(client.server.origin,
            "spofify_session=saved; Path=/; HttpOnly; Max-Age=3600", Session("a1", "listener", "user", "csrf"))
        store.value = saved
        client.setOffline(true)
        val dns = AtomicInteger()
        val media = client.httpClient.newBuilder().dns { dns.incrementAndGet(); error("DNS must not run") }.build()
        val changes = mutableListOf<AuthenticationChange>()
        client.onAuthenticationChanged = { changes.add(it) }
        ioFailure { client.library() }
        ioFailure { ApiTransport(client).get(listOf("api", "me", "playlists")) }
        ioFailure { client.openLibraryPlayback(this@runBlocking, "Phone", "phone", true).close() }
        ioFailure { client.login("listener", "synthetic-password") }
        ioFailure { client.restore() }
        for (path in listOf("artwork", "audio")) {
            ioFailure {
                media.newCall(Request.Builder().url(client.server.url("api", "library", "tracks", "t1", path))
                    .header("Range", "bytes=0-15").build()).execute().use { it.body!!.bytes() }
            }
        }
        assertTrue(client.isOffline)
        assertEquals(saved, store.value) // A blocked auth attempt is not logout/invalidation.
        assertTrue(changes.isEmpty())
        assertEquals(0, dns.get())
        assertEquals(0, web.requestCount)
    }

    @Test fun modeChangesPreserveLiveSessionAndDoNotSendCleanupOrReauthenticate() = runBlocking {
        val live = login()
        val identity = client.validatedSessionIdentity()
        val saved = store.value
        val changes = mutableListOf<AuthenticationChange>()
        client.onAuthenticationChanged = { changes.add(it) }
        client.setOffline(true)
        client.setOffline(true)
        ioFailure { client.login("other", "synthetic-password") }
        ioFailure { client.restore() }
        assertSame(live, client.currentSession())
        assertTrue(client.hasValidSession(live))
        assertEquals(identity, client.validatedSessionIdentity())
        assertEquals(saved, store.value)
        client.setOffline(false)
        client.setOffline(false)
        assertFalse(client.isOffline)
        assertSame(live, client.currentSession())
        assertEquals(identity, client.validatedSessionIdentity())
        assertTrue(changes.isEmpty())
        assertEquals(1, web.requestCount)
    }

    @Test fun offlineLogoutRetiresLocallyWithoutLogoutOrPlaybackAdmissionTraffic() = runBlocking {
        val live = login()
        client.setOffline(true)
        client.logout()
        ioFailure { client.openLibraryPlayback(this@runBlocking, "Phone", "phone", true).close() }
        assertNull(client.currentSession())
        assertFalse(client.hasValidSession(live))
        assertNull(store.value)
        assertEquals(1, web.requestCount)
    }

    @Test fun capturedCallAndPendingPlaybackAdmissionCannotReviveAcrossOfflineAba() = runBlocking {
        login()
        val call = client.httpClient.newCall(Request.Builder().url(client.server.url("old-artwork")).build())
        val release = CountDownLatch(1)
        delayed(release, jsonResponse("""{"ticket":"${"a".repeat(64)}"}"""))
        val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.openLibraryPlayback(this@runBlocking, "Phone", "phone", true).close() }
        }
        try {
            takeRequest()
            client.setOffline(true)
            client.setOffline(false)
            assertTrue(call.isCanceled())
            ioFailure { call.execute().close() }
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
        } finally { release.countDown() }
        assertEquals(2, web.requestCount) // Login + original ticket only: no upgrade/replay.
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = jsonResponse("{\"items\":[],\"total\":0}")
        }
        assertTrue(client.library().items.isEmpty())
        assertEquals(3, web.requestCount) // Only fresh explicit online work is admitted.
    }

    @Test fun unchangedOnlineSettingDoesNotCancelFreshWork() = runBlocking {
        val call = client.httpClient.newCall(Request.Builder().url(client.server.url("fresh-artwork")).build())
        client.setOffline(false)
        assertFalse(call.isCanceled())
        web.enqueue(MockResponse().setBody("synthetic-image"))
        call.execute().use { assertEquals("synthetic-image", it.body!!.string()) }
        assertEquals(1, web.requestCount)
    }

    @Test fun entryCancelsInflightApiAndLateReplyCannotPublishAfterExit() = runBlocking {
        val release = CountDownLatch(1)
        delayed(release, jsonResponse("{\"items\":[],\"total\":0}"))
        val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { client.library() } }
        try {
            takeRequest(workerSnapshotOnFailure = true)
            client.setOffline(true)
            client.setOffline(false)
            // Cancellation must finish the client attempt without waiting for server headers.
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
        } finally { release.countDown() }
        assertEquals(1, web.requestCount)
    }

    @Test fun parserAlreadyHoldingAResponseCannotReturnItsResultAfterOfflineAba() = runBlocking {
        web.enqueue(jsonResponse("{\"synthetic\":true}"))
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                client.response(Request.Builder().url(client.server.url("parse")).build()) {
                    it.body!!.string()
                    parsing.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "late parsed value"
                }.also { published.incrementAndGet() }
            }
        }
        try {
            assertTrue(parsing.await(5, TimeUnit.SECONDS))
            client.setOffline(true)
            client.setOffline(false)
        } finally { release.countDown() }
        assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
        assertEquals(0, published.get())
        assertEquals(1, web.requestCount)
    }

    @Test fun prefetchedBodyCannotPublishAfterOfflineEntryOrAba() = runBlocking {
        for (exitBeforeRelease in listOf(false, true)) {
            client.setOffline(false)
            val live = login()
            val identity = client.validatedSessionIdentity()
            val revisions = client.personalRevision.value
            val authChanges = AtomicInteger()
            client.onAuthenticationChanged = { authChanges.incrementAndGet() }

            val size = 16_384
            web.enqueue(MockResponse().setBody("x".repeat(size)))
            val parked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cachedRead = AtomicInteger()
            val published = AtomicInteger()
            val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    client.response(Request.Builder().url(client.server.url("prefetch")).build()) {
                        val source = it.body.source()
                        source.require(size.toLong()) // Prefetch the entire synthetic body.
                        assertEquals('x'.code, source.readByte().toInt())
                        parked.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        // Internal prefetched bytes remain readable. No erasure is promised.
                        assertEquals(size - 1, source.readByteArray((size - 1).toLong()).size)
                        cachedRead.incrementAndGet()
                        "retired result"
                    }.also { published.incrementAndGet() }
                }
            }
            try {
                assertTrue(parked.await(5, TimeUnit.SECONDS))
                client.setOffline(true)
                if (exitBeforeRelease) client.setOffline(false)
            } finally { release.countDown() }

            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
            assertEquals(1, cachedRead.get())
            assertEquals(0, published.get())
            assertSame(live, client.currentSession())
            assertEquals(identity, client.validatedSessionIdentity())
            assertEquals(0, authChanges.get())
            assertEquals(revisions, client.personalRevision.value)
        }
    }

    @Test fun receivedRawMediaBodyIsFencedEvenAfterOfflineExit() = runBlocking {
        web.enqueue(MockResponse().setBody("synthetic-media").setBodyDelay(1, TimeUnit.SECONDS))
        val call = client.httpClient.newCall(Request.Builder().url(client.server.url("audio")).build())
        val response = call.execute()
        try {
            takeRequest()
            client.setOffline(true)
            assertTrue(call.isCanceled())
            client.setOffline(false)
            ioFailure { response.body!!.string() }
        } finally { response.close() }
        assertEquals(1, web.requestCount)
    }

    @Test fun lateLoginCannotPersistOrInstallAfterOfflineAba() = runBlocking {
        val release = CountDownLatch(1)
        delayed(release, loginResponse())
        val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { client.login("listener", "synthetic-password") } }
        try {
            takeRequest()
            client.setOffline(true)
            client.setOffline(false)
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
        } finally { release.countDown() }
        assertNull(client.currentSession())
        assertNull(store.value)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = loginResponse()
        }
        assertNotNull(client.login("listener", "synthetic-password"))
        assertEquals(2, web.requestCount)
    }

    @Test fun entryCancelsInflightTerminalLogoutWithoutBorrowingSuccessorAuth() = runBlocking {
        login()
        val release = CountDownLatch(1)
        delayed(release, MockResponse().setResponseCode(204))
        val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { client.logout() } }
        try {
            takeRequest()
            assertNull(client.currentSession())
            client.setOffline(true)
            client.setOffline(false)
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
        } finally { release.countDown() }
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = loginResponse()
        }
        val successor = client.login("listener", "synthetic-password")
        assertSame(successor, client.currentSession())
        assertEquals(3, web.requestCount)
    }

    @Test fun lateRestoreCannotInstallAndKeepsDormantSavedBytes() = runBlocking {
        login()
        val saved = store.value
        val release = CountDownLatch(1)
        delayed(release, jsonResponse(SESSION_JSON))
        val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { client.restore() } }
        try {
            takeRequest()
            client.setOffline(true)
            client.setOffline(false)
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
        } finally { release.countDown() }
        assertNull(client.currentSession())
        assertEquals(saved, store.value)
        assertEquals(2, web.requestCount)
    }

    @Test fun staleRestoreFailureCannotMutateAuthAfterEntryOrEntryExitAba() = runBlocking {
        for (exitBeforeCleanup in listOf(false, true)) {
            client.setOffline(false)
            login()
            val saved = store.value
            val notifications = AtomicInteger()
            client.onAuthenticationChanged = { notifications.incrementAndGet() }
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val executor = Executors.newSingleThreadExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            val parked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pending = async(dispatcher) { runCatching { client.restore() } }
            try {
                takeRequest()
                assertEquals(1, notifications.get()) // The explicit restore became dormant.
                assertEquals(1, client.httpClient.cookieJar.loadForRequest(client.server.url()).size)
                executor.submit {
                    parked.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                assertTrue(parked.await(5, TimeUnit.SECONDS))
                client.setOffline(true)
                // These assertions run BEFORE the cancelled restore coroutine can clean up.
                assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
                assertEquals(2, notifications.get()) // Entry owns provisional retirement.
                assertEquals(saved, store.value)
                if (exitBeforeCleanup) client.setOffline(false)
                val generationAfterModeChange = client.personalMutationGeneration()
                release.countDown()
                assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
                assertEquals(2, notifications.get()) // No late DORMANT from stale failure.
                assertEquals(generationAfterModeChange, client.personalMutationGeneration())
                assertNull(client.currentSession())
                assertEquals(saved, store.value)
            } finally {
                release.countDown()
                pending.cancelAndJoin()
                dispatcher.close()
            }
        }
        assertEquals(4, web.requestCount) // Two explicit logins and two explicit probes only.
    }

    @Test fun lateShared401CannotRetireSessionOrEmitUnauthorizedAfterEntry() = runBlocking {
        val live = login()
        val saved = store.value
        val unauthorized = AtomicInteger()
        client.onUnauthorized = { unauthorized.incrementAndGet() }
        val release = CountDownLatch(1)
        delayed(release, jsonResponse("{}", 401))
        val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.httpClient.newCall(Request.Builder().url(client.server.url("artwork")).build()).execute().close() }
        }
        try {
            takeRequest()
            client.setOffline(true)
            client.setOffline(false)
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
        } finally { release.countDown() }
        assertSame(live, client.currentSession())
        assertEquals(saved, store.value)
        assertEquals(0, unauthorized.get())
        assertEquals(2, web.requestCount)
    }

    @Test fun inflightPlaybackAdmissionIsCancelledByOfflineEntryWithoutAnUpgrade() = runBlocking {
        val live = login()
        val saved = store.value
        val release = CountDownLatch(1)
        delayed(release, jsonResponse("""{"ticket":"${"a".repeat(64)}"}"""))
        val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.openLibraryPlayback(this@runBlocking, "Phone", "phone", true).close() }
        }
        try {
            takeRequest()
            client.setOffline(true)
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
        } finally { release.countDown() }
        assertSame(live, client.currentSession())
        assertEquals(saved, store.value)
        assertEquals(2, web.requestCount) // Login + canceled ticket; no socket or cleanup request.
    }

    @Test fun offlinePlaybackAdmissionIsRejectedRatherThanDeferredUntilExit() = runBlocking {
        val live = login()
        client.setOffline(true)
        ioFailure { client.openLibraryPlayback(this@runBlocking, "Phone", "phone", true).close() }
        assertEquals(1, web.requestCount)
        client.setOffline(false)
        assertSame(live, client.currentSession())
        assertEquals(1, web.requestCount)
    }

    @Test fun elapsedCookieExpiryIsNotObservedUnauthorizedEvenWhileOffline() = runBlocking {
        val live = login()
        val expiry = client.httpClient.cookieJar.loadForRequest(client.server.url()).single().expiresAt
        val changes = mutableListOf<AuthenticationChange>()
        client.onAuthenticationChanged = { changes.add(it) }
        val unauthorized = AtomicInteger()
        client.onUnauthorized = { unauthorized.incrementAndGet() }
        client.setOffline(true)
        now.set(expiry)
        assertNull(client.currentSession())
        assertFalse(client.hasValidSession(live))
        assertEquals(AuthenticationChangeKind.EXPIRED, changes.single().kind)
        assertEquals(0, unauthorized.get())
        assertEquals(1, web.requestCount)
    }

    @Test fun confirmedMutationGenerationCannotCrossOfflineAba() = runBlocking {
        login()
        val before = client.personalRevision.value
        val captured = client.personalMutationGeneration()
        client.setOffline(true)
        client.notifyPersonalChange(PersonalCollection.LIKES)
        client.setOffline(false)
        client.notifyPersonalChange(PersonalCollection.LIKES, captured)
        assertEquals(before, client.personalRevision.value)
        client.notifyPersonalChange(PersonalCollection.LIKES)
        assertEquals(before.likes + 1, client.personalRevision.value.likes)
    }

    private suspend fun login(): Session {
        web.enqueue(loginResponse())
        val live = try { client.login("listener", "synthetic-password") }
        catch (error: IOException) {
            throw AssertionError("Synthetic login transport failed; received=${web.requestCount}, " +
                "queued=${client.httpClient.dispatcher.queuedCallsCount()}, " +
                "running=${client.httpClient.dispatcher.runningCallsCount()}; ${fixtureFailureDetails()}", error)
        }
        takeRequest()
        return live
    }

    private fun takeRequest(workerSnapshotOnFailure: Boolean = false) {
        val request = web.takeRequest(5, TimeUnit.SECONDS)
        val snapshot = if (request == null && workerSnapshotOnFailure) "\n${BoundedWorkerSnapshot.capture()}" else ""
        val fixture = if (request == null) "; ${fixtureFailureDetails()}" else ""
        assertNotNull("Synthetic request did not arrive; received=${web.requestCount}, " +
            "queued=${client.httpClient.dispatcher.queuedCallsCount()}, " +
            "running=${client.httpClient.dispatcher.runningCallsCount()}$fixture$snapshot", request)
    }

    /** Failure-only configuration snapshot, NOT proof of the route the running call selected.
     * Never render a Proxy/selector object, external proxy address, URL or exception message. */
    private fun fixtureFailureDetails(): String {
        val request = client.server.url()
        val numericHost = request.host.takeIf { host ->
            host.isNotEmpty() && host.length <= 15 && host.all { it in '0'..'9' || it == '.' }
        } ?: "[NON_NUMERIC]"
        val proxyConfig = try {
            val explicit = client.httpClient.proxy
            if (explicit != null) "EXPLICIT:${explicit.type().name}"
            else {
                val types = client.httpClient.proxySelector.select(request.toUri()).orEmpty()
                    .take(4).map { it.type().name }
                "INHERITED:selectorSnapshot=${types.joinToString(",").ifEmpty { "EMPTY" }}"
            }
        } catch (_: Exception) { "UNAVAILABLE" }
        return "listener=127.0.0.1:${web.port}; request=$numericHost:${request.port}; " +
            "directProbe=${if (directProbePassed) "PASSED" else "NOT_PASSED"}; proxyConfig=$proxyConfig"
    }

    private fun delayed(release: CountDownLatch, response: MockResponse) {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(5, TimeUnit.SECONDS))
                return response
            }
        }
    }

    private suspend fun ioFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected fenced online work") } catch (_: IOException) { }
    }
}
