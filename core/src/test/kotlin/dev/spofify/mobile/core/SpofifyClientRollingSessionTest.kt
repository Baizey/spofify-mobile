package dev.spofify.mobile.core

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpofifyClientRollingSessionTest {
    private class Store : SessionStore {
        @Volatile var value: StoredSession? = null
        @Volatile var failRead = false
        @Volatile var failSave = false
        val saves = AtomicInteger()
        override fun read(): StoredSession? {
            if (failRead) throw IOException("Synthetic storage temporarily unavailable")
            return value
        }
        override fun write(value: StoredSession?) {
            if (value != null) {
                saves.incrementAndGet()
                if (failSave) throw IOException("Synthetic storage temporarily unavailable")
            }
            this.value = value
        }
    }
    private lateinit var web: MockWebServer
    private lateinit var store: Store
    private lateinit var client: SpofifyClient
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = AtomicLong(System.currentTimeMillis() / 1000 * 1000)
    private val clients = mutableListOf<SpofifyClient>()
    private fun create(store: SessionStore = this.store, origin: String = "http://127.0.0.1:${web.port}") =
        SpofifyClient(ServerAddress.parse(origin), store, now::get).also { clients.add(it) }
    @Before fun setup() { web = MockWebServer().also { it.start() }; store = Store(); client = create() }
    @After fun teardown() {
        clients.forEach { it.httpClient.dispatcher.cancelAll(); it.httpClient.connectionPool.evictAll(); it.httpClient.dispatcher.executorService.shutdown() }
        web.shutdown()
    }
    private fun cookie(deadline: Long, token: String = "rolling-token") =
        "spofify_session=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000; Expires=" +
            DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(deadline).atZone(ZoneOffset.UTC))
    private fun rolling(deadline: Long, body: String = "{}", token: String = "rolling-token", code: Int = 200) =
        jsonResponse(body, code).setHeader("X-Spofify-Session-Mode", "rolling-v1").setHeader("Set-Cookie", cookie(deadline, token))
    private suspend fun login(token: String = "rolling-token", account: String = "a1"): Session {
        web.enqueue(rolling(now.get() + 30 * day, SESSION_JSON.replace("a1", account).dropLast(1) + ",\"sessionMode\":\"rolling-v1\"}", token))
        val session = client.login("listener", "explicit-password")
        assertEquals("rolling-v1", web.takeRequest().getHeader("X-Spofify-Session-Mode"))
        return session
    }
    private fun deadline() = requireNotNull(Cookie.parse(client.server.url(), store.value!!.cookie)).expiresAt
    private suspend fun get(path: String = "api/library") = ApiTransport(client).get(path.split('/'))
    private fun await(latch: CountDownLatch) = assertTrue("Controlled request boundary not reached", latch.await(5, TimeUnit.SECONDS))

    @Test fun rollingUseAcrossWeeksPreservesActualSessionAndIncarnationAndCoalescesSaves() = runBlocking {
        val session = login()
        val identity = client.validatedSessionIdentity()
        val initialSaves = store.saves.get()
        repeat(4) {
            now.addAndGet(20 * day)
            val next = now.get() + 30 * day
            web.enqueue(rolling(next)); get(); web.takeRequest()
            assertSame(session, client.currentSession())
            assertEquals(identity, client.validatedSessionIdentity())
            assertTrue(client.hasValidSession(session))
            assertEquals(next, deadline())
            assertEquals("rolling-v1", store.value!!.sessionMode)
            repeat(3) { web.enqueue(rolling(next)); get(); web.takeRequest() }
        }
        assertEquals(initialSaves + 4, store.saves.get())
        // Every later deadline is read dynamically, not an old expiry observer
        // which skips once and silently loses the eventual expiry check.
        now.set(deadline())
        assertFalse(client.hasValidSession(session))
        assertNull(client.currentSession())
        assertNotNull(store.value) // Local uncertainty is dormant, never live authority.
        web.enqueue(jsonResponse("{}", 401))
        assertNull(client.restore())
        val probe = web.takeRequest()
        assertEquals("/api/auth/session", probe.path)
        assertEquals("spofify_session=rolling-token", probe.getHeader("Cookie"))
        assertNull(store.value) // Authoritative idle expiry still requires login.
    }

    @Test fun processRestartOffline5xxParseAndTransientReadFailureRetainSavedConnection() = runBlocking {
        login()
        val saved = store.value
        now.addAndGet(10 * day)
        client = create()
        store.failRead = true
        try { client.restore(); fail("Expected storage unavailability") } catch (_: IOException) { }
        assertSame(saved, store.value)
        store.failRead = false
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { client.restore(); fail("Expected offline restore") } catch (_: IOException) { }
        web.takeRequest(); assertSame(saved, store.value); assertNull(client.validatedSessionIdentity())
        web.enqueue(jsonResponse("{}", 503))
        assertEquals(503, apiFailure { client.restore() }.status)
        web.takeRequest(); assertSame(saved, store.value)
        web.enqueue(jsonResponse("not-json"))
        assertEquals(200, apiFailure { client.restore() }.status)
        web.takeRequest(); assertSame(saved, store.value)
        web.enqueue(rolling(now.get() + 30 * day, SESSION_JSON))
        val restored = requireNotNull(client.restore())
        web.takeRequest()
        assertTrue(client.hasValidSession(restored))
        assertEquals(now.get() + 30 * day, deadline())
    }

    @Test fun failedDeadlineSaveKeepsLiveIdentityAndExpiredLocalRecordCanRevalidateDormantly() = runBlocking {
        val original = login()
        val old = store.value
        now.addAndGet(29 * day)
        val extended = now.get() + 30 * day
        store.failSave = true
        web.enqueue(rolling(extended)); get(); web.takeRequest()
        assertSame(original, client.currentSession()); assertSame(old, store.value)
        val failedSaves = store.saves.get()
        repeat(3) { web.enqueue(rolling(extended)); get(); web.takeRequest() }
        assertEquals(failedSaves, store.saves.get()) // Bounded retry, not every quiet poll.
        // Process death before retry leaves the older cookie persisted. It grants
        // no authority after local expiry, but the SAME server row can still live.
        now.addAndGet(2 * day)
        client = create()
        store.failSave = false
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return rolling(extended, SESSION_JSON)
            }
        }
        val pending = async(Dispatchers.IO) { client.restore() }
        try {
            await(entered)
            assertNull(client.currentSession()); assertNull(client.validatedSessionIdentity())
            assertNotNull(store.value)
        } finally { release.countDown() }
        val restored = requireNotNull(pending.await())
        assertTrue(client.hasValidSession(restored)); assertEquals(extended, deadline())
        assertNotSame(original, restored)
    }

    @Test fun deadlineSaveRetriesOnLaterNormalUseWithoutChangingSession() = runBlocking {
        val session = login(); val next = now.addAndGet(day) + 30 * day
        store.failSave = true
        web.enqueue(rolling(next)); get(); web.takeRequest()
        store.failSave = false; now.addAndGet(60_000)
        web.enqueue(rolling(next)); get(); web.takeRequest()
        assertEquals(next, deadline()); assertSame(session, client.currentSession())
    }

    @Test fun oldDeadlineRepliesAndOld401CannotUndoNewDeadlineButFreshRevocationStillWins() = runBlocking {
        val session = login(); now.addAndGet(day)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val newest = now.get() + 30 * day
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/stale" -> { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); jsonResponse("{}", 401) }
                "/fresh-revoked" -> jsonResponse("{}", 401)
                else -> rolling(newest)
            }
        }
        var unauthorized = 0; client.onUnauthorized = { unauthorized++ }
        val old = async(Dispatchers.IO) { apiFailure { get("stale") } }
        try { await(entered); get(); assertSame(session, client.currentSession()) }
        finally { release.countDown() }
        assertEquals(401, old.await().status)
        assertEquals(0, unauthorized); assertSame(session, client.currentSession()); assertEquals(newest, deadline())
        assertEquals(401, apiFailure { get("fresh-revoked") }.status)
        assertEquals(1, unauthorized); assertNull(client.currentSession()); assertNull(store.value)
    }

    @Test fun concurrentOutOfOrderExtensionsNeverRegressOrReplayAndKeepCapturedSession() = runBlocking {
        val session = login(); now.addAndGet(day)
        val earlier = now.get() + 30 * day; val later = earlier + day
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/earlier") {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); rolling(earlier)
            } else rolling(later)
        }
        val old = async(Dispatchers.IO) { get("earlier") }
        try { await(entered); get("later") } finally { release.countDown() }
        old.await()
        assertEquals(later, deadline()); assertSame(session, client.currentSession())
        assertEquals(3, web.requestCount) // login + exactly the two original requests.
    }

    @Test fun droppedMutationBodyDoesNotDeleteAcceptedDeadlineOrReplayMutation() = runBlocking {
        val session = login(); now.addAndGet(day); val next = now.get() + 30 * day
        web.enqueue(rolling(next, "x".repeat(4096)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try { client.likeStatus("synthetic"); fail("Expected lost body") } catch (_: IOException) { }
        assertEquals(2, web.requestCount)
        assertEquals(next, deadline()); assertSame(session, client.currentSession())
    }

    @Test fun logoutRetiresBeforeReplyAndLateCookieOrLogoutCannotOverwriteSuccessorLogin() = runBlocking {
        val oldSession = login(); now.addAndGet(day)
        val lateEntered = CountDownLatch(1); val lateRelease = CountDownLatch(1)
        val logoutEntered = CountDownLatch(1); val logoutRelease = CountDownLatch(1)
        val next = now.get() + 30 * day
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/late" -> { lateEntered.countDown(); check(lateRelease.await(5, TimeUnit.SECONDS)); rolling(next) }
                "/api/auth/logout" -> { logoutEntered.countDown(); check(logoutRelease.await(5, TimeUnit.SECONDS)); MockResponse().setResponseCode(204).setHeader("Set-Cookie", "spofify_session=; Path=/; HttpOnly; Max-Age=0") }
                else -> rolling(next, SESSION_JSON.dropLast(1) + ",\"sessionMode\":\"rolling-v1\"}", "successor-token")
            }
        }
        val late = async(Dispatchers.IO) { get("late") }
        await(lateEntered)
        val logout = async(Dispatchers.IO) { client.logout() }
        try {
            await(logoutEntered)
            assertNull(client.currentSession()); assertNull(store.value); assertFalse(client.hasValidSession(oldSession))
            val successor = client.login("listener", "explicit-password")
            assertNotSame(oldSession, successor)
            lateRelease.countDown(); late.await()
            assertSame(successor, client.currentSession())
            assertTrue(store.value!!.cookie.contains("successor-token"))
        } finally { lateRelease.countDown(); logoutRelease.countDown() }
        logout.await()
        assertTrue(store.value!!.cookie.contains("successor-token")); assertNotNull(client.currentSession())
    }

    @Test fun retiredStoreOwnerAndOriginCannotPublishLateRollingDeadline() = runBlocking {
        val owner = SessionStoreOwner(store)
        client = create(owner.acquire())
        login()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/late") {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); rolling(now.get() + 31 * day)
            } else rolling(now.get() + 30 * day, SESSION_JSON.dropLast(1) + ",\"sessionMode\":\"rolling-v1\"}", "other-token")
        }
        val retired = client
        val late = async(Dispatchers.IO) { ApiTransport(retired).get(listOf("late")) }
        try {
            await(entered)
            client = create(owner.acquire(), "http://localhost:${web.port}")
            retired.clearSession()
            val next = client.login("listener", "explicit-password")
            release.countDown(); late.await()
            assertSame(next, client.currentSession()); assertEquals(client.server.origin, store.value!!.server)
            assertTrue(store.value!!.cookie.contains("other-token"))
        } finally { release.countDown() }
    }

    @Test fun onlyNegotiatedSameTokenPolicyValidResponsesCanExtendAndFailuresKeepAuth() = runBlocking {
        val session = login(); val saved = store.value; now.addAndGet(day)
        val next = now.get() + 30 * day
        for (response in listOf(
            rolling(next, token = "unrelated-token"),
            rolling(next).removeHeader("X-Spofify-Session-Mode"),
            rolling(next).setHeader("X-Spofify-Session-Mode", "unknown"),
            rolling(next).setHeader("Set-Cookie", cookie(next) + "; Domain=127.0.0.1"),
            rolling(next).setHeader("Set-Cookie", cookie(next).replace("Path=/", "Path=/private")),
            rolling(next).setHeader("Set-Cookie", cookie(next) + "; Secure"),
        )) { web.enqueue(response); get(); web.takeRequest(); assertSame(saved, store.value); assertSame(session, client.currentSession()) }
        for (code in listOf(302, 503)) {
            web.enqueue(rolling(next, code = code))
            assertEquals(code, apiFailure { get() }.status)
            web.takeRequest(); assertSame(saved, store.value); assertSame(session, client.currentSession())
        }
    }

    @Test fun queuedAuthenticatedMutationCannotDispatchAfterDeadlineAndExplicitRestoreCanRecover() = runBlocking {
        val original = login()
        val saved = store.value
        val queued = client.httpClient.newCall(Request.Builder().url(client.server.url("expired-write"))
            .post("{}".toRequestBody()).build())
        now.set(deadline() + 1)
        try { queued.execute().close(); fail("Expired ordinary mutation reached the backend") } catch (_: IOException) { }
        assertEquals(1, web.requestCount) // The login only; no ordinary request was dispatched.
        assertSame(saved, store.value)
        assertNull(client.currentSession()); assertNull(client.validatedSessionIdentity())
        assertFalse(client.hasValidSession(original))
        // Model a server deadline previously extended by a lost reply. This is
        // an explicit probe, not resurrection of a truly server-expired row.
        web.enqueue(rolling(now.get() + 30 * day, SESSION_JSON))
        assertNotNull(client.restore())
        val probe = web.takeRequest()
        assertEquals("GET", probe.method); assertEquals("/api/auth/session", probe.path)
        assertEquals("spofify_session=rolling-token", probe.getHeader("Cookie"))
        assertEquals(2, web.requestCount)
    }

    @Test fun queuedCallUsesCurrentHealthyDeadlineNotItsCapturedExpiry() = runBlocking {
        val session = login()
        val oldDeadline = deadline()
        val queued = client.httpClient.newCall(Request.Builder().url(client.server.url("healthy-write"))
            .post("{}".toRequestBody()).build())
        now.addAndGet(29 * day)
        val extended = now.get() + 30 * day
        web.enqueue(rolling(extended)); get(); web.takeRequest()
        now.set(oldDeadline + day)
        web.enqueue(rolling(extended))
        queued.execute().use { assertEquals(200, it.code) }
        assertEquals("/healthy-write", web.takeRequest().path)
        assertSame(session, client.currentSession()); assertTrue(client.hasValidSession(session))
        assertEquals(extended, deadline())
    }

    @Test fun aLateOrdinaryReplyCanSaveDeadlineButCannotReactivateObservedDormantAuthority() = runBlocking {
        val session = login()
        val oldDeadline = deadline()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val extended = oldDeadline + 10 * day
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/held") {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); rolling(extended)
            } else rolling(extended, SESSION_JSON)
        }
        val held = async(Dispatchers.IO) { get("held") }
        try {
            await(entered)
            now.set(oldDeadline + 1)
            assertFalse(client.hasValidSession(session))
        } finally { release.countDown() }
        held.await()
        assertEquals(extended, deadline())
        assertNull(client.currentSession()); assertNull(client.validatedSessionIdentity())
        assertFalse(client.hasValidSession(session))
        assertNotNull(client.restore())
    }

    @Test fun aCallCreatedBeforeReloginCannotBorrowSuccessorCredentials() = runBlocking {
        login()
        val queued = client.httpClient.newCall(Request.Builder().url(client.server.url("queued-old-write"))
            .post("{}".toRequestBody()).build())
        val next = login("successor-token")
        try { queued.execute().close(); fail("Retired call reached successor account") } catch (_: IOException) { }
        assertEquals(2, web.requestCount)
        assertSame(next, client.currentSession()); assertTrue(store.value!!.cookie.contains("successor-token"))
    }

    @Test fun queuedCallDispatchedAfterExtensionStillHonorsFreshAuthoritativeRevocation() = runBlocking {
        login()
        val queued = client.httpClient.newCall(Request.Builder().url(client.server.url("fresh-at-dispatch")).build())
        now.addAndGet(day)
        web.enqueue(rolling(now.get() + 30 * day)); get(); web.takeRequest()
        var unauthorized = 0; client.onUnauthorized = { unauthorized++ }
        web.enqueue(jsonResponse("{}", 401))
        queued.execute().use { assertEquals(401, it.code) }
        assertEquals(1, unauthorized); assertNull(client.currentSession()); assertNull(store.value)
    }

    @Test fun conditionalAndRangeResponsesExtendWithoutChangingCapturedAuthority() = runBlocking {
        val session = login(); now.addAndGet(day)
        for (code in listOf(206, 304)) {
            val next = now.get() + 30 * day
            web.enqueue(rolling(next, code = code))
            client.httpClient.newCall(Request.Builder().url(client.server.url("media")).build()).execute().use { assertEquals(code, it.code) }
            web.takeRequest(); assertEquals(next, deadline()); assertSame(session, client.currentSession())
            now.addAndGet(day)
        }
    }
}
