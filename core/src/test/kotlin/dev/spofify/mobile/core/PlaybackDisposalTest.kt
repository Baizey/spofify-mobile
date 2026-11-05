package dev.spofify.mobile.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Disposal authority is an exact old-session leave, not a replacement API/media client. */
class PlaybackDisposalTest {
    private lateinit var web: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var client: SpofifyClient

    @Before fun setup() {
        web = MockWebServer().also { it.start(loopback(), 0) }
        store = MemorySessionStore()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), store)
    }

    @After fun teardown() {
        client.httpClient.dispatcher.cancelAll()
        client.httpClient.connectionPool.evictAll()
        client.httpClient.dispatcher.executorService.shutdown()
        web.shutdown()
    }

    private suspend fun login(cookie: String = "cookie-secret", csrf: String = "csrf-secret") {
        web.enqueue(jsonResponse(SESSION_JSON.replace("csrf-secret", csrf)).addHeader(
            "Set-Cookie", "spofify_session=$cookie; Path=/; HttpOnly; Max-Age=3600; SameSite=Lax",
        ))
        client.login("synthetic", "synthetic-password")
        assertEquals("/api/auth/login", takeRequest().path)
    }

    private fun takeRequest(): RecordedRequest = requireNotNull(web.takeRequest(2, TimeUnit.SECONDS))
    private fun loopback(last: Int = 1): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, last.toByte()))

    private fun recordRoute(
        socketAddress: InetSocketAddress = InetSocketAddress(loopback(), web.port),
        proxyType: Proxy.Type = Proxy.Type.DIRECT,
        requestUrl: HttpUrl = client.server.url(),
        routeUrl: HttpUrl = requestUrl,
        generation: Long = client.personalMutationGeneration(),
    ) = client.recordPlaybackLeaveRoute(generation, requestUrl, routeUrl, proxyType, socketAddress)

    private fun assertExactLeave(request: RecordedRequest, id: String = "old-exact_ID-1") {
        assertEquals("POST", request.method)
        assertEquals("/api/me/player", request.path)
        assertEquals("${client.server.url().host}:${web.port}", request.getHeader("Host"))
        assertEquals(client.server.origin, request.getHeader("Origin"))
        assertEquals("spofify_session=cookie-secret", request.getHeader("Cookie"))
        assertEquals("csrf-secret", request.getHeader("X-CSRF-Token"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertNull(request.getHeader("X-Spofify-Login"))
        assertNull(request.getHeader("X-Spofify-Playback-Extensions"))
        assertEquals("{\"action\":\"leave\",\"deviceId\":\"$id\"}", request.body.readUtf8())
    }

    private suspend fun failsWithoutSending(leave: suspend () -> Unit) {
        val before = web.requestCount
        try { leave(); fail("Retired disposal authority was accepted") } catch (_: IOException) { }
        assertEquals(before, web.requestCount)
    }

    @Test fun capturedExactLeaveSurvivesOrdinaryAuthClearAndDispatcherCancellation() = runBlocking {
        login()
        val leave = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
        assertEquals(1, web.requestCount) // Capturing is not a network operation.
        client.clearSession()
        client.httpClient.dispatcher.cancelAll()
        assertNull(client.currentSession())
        assertNull(store.value)
        assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
        web.enqueue(MockResponse().setResponseCode(204))
        leave()
        assertExactLeave(takeRequest())
        failsWithoutSending(leave)
        assertNull(client.currentSession())
        assertNull(store.value)
    }

    @Test fun successorSessionIsNeverBorrowedOrInvalidatedOrOverwrittenByOldCleanup() = runBlocking {
        login()
        val leave = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
        client.clearSession()
        client.httpClient.dispatcher.cancelAll()
        login(cookie = "successor-cookie", csrf = "successor-csrf")
        val saved = store.value
        val successor = client.currentSession()
        var unauthorized = 0
        client.onUnauthorized = { unauthorized++ }
        web.enqueue(MockResponse().setResponseCode(401)
            .addHeader("Set-Cookie", "spofify_session=forged; Path=/; HttpOnly; Max-Age=3600"))
        try { leave(); fail("Rejected cleanup succeeded") } catch (error: ApiException) { assertEquals(401, error.status) }
        assertExactLeave(takeRequest())
        assertEquals(successor, client.currentSession())
        assertEquals(saved, store.value)
        assertEquals(0, unauthorized)
        assertEquals("successor-cookie", client.httpClient.cookieJar.loadForRequest(client.server.url()).single().value)
        failsWithoutSending(leave)
        web.enqueue(jsonResponse("{}"))
        client.setLiked("track", true)
        val ordinary = takeRequest()
        assertEquals("successor-csrf", ordinary.getHeader("X-CSRF-Token"))
        assertEquals("spofify_session=successor-cookie", ordinary.getHeader("Cookie"))
    }

    @Test fun neitherSameOriginMediaRedirectNorDifferentOriginRedirectIsFollowed() = runBlocking {
        login()
        val other = MockWebServer().also { it.start() }
        try {
            for (target in listOf(web.url("/api/library/tracks/forbidden/audio"), other.url("/stolen"))) {
                val leave = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
                web.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target))
                try { leave(); fail("Redirect accepted") } catch (error: ApiException) { assertEquals(307, error.status) }
                assertExactLeave(takeRequest())
                failsWithoutSending(leave)
            }
            assertNull(web.takeRequest(150, TimeUnit.MILLISECONDS))
            assertEquals(0, other.requestCount)
            assertEquals(3, web.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun concurrentInvocationsHaveOnlyOneWinner() = runBlocking {
        login()
        val leave = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
        web.enqueue(MockResponse().setResponseCode(204))
        val first = async(Dispatchers.Default) { runCatching { leave() } }
        val second = async(Dispatchers.Default) { runCatching { leave() } }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is IOException)
        assertExactLeave(takeRequest())
        assertEquals(2, web.requestCount)
        failsWithoutSending(leave)
    }

    @Test fun uncertainDisconnectAndRetryableResponsesNeverReplay() = runBlocking {
        login()
        for (response in listOf(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "0"),
            MockResponse().setResponseCode(408),
            MockResponse().setResponseCode(421),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )) {
            val leave = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
            web.enqueue(response)
            try { leave(); fail("Rejected/uncertain cleanup succeeded") } catch (_: IOException) { }
            assertExactLeave(takeRequest())
            failsWithoutSending(leave)
        }
        assertNull(web.takeRequest(150, TimeUnit.MILLISECONDS))
        assertEquals(5, web.requestCount)
    }

    @Test fun callerCancellationCancelsHeldCallAndCannotReplayOrTouchSuccessor() = runBlocking {
        login()
        val leave = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val attempt = async(Dispatchers.Default) { leave() }
        assertExactLeave(takeRequest())
        withTimeout(1500) { attempt.cancelAndJoin() }
        failsWithoutSending(leave)
        login(cookie = "successor-cookie", csrf = "successor-csrf")
        assertEquals("successor-csrf", client.currentSession()!!.csrfToken)
        assertEquals(3, web.requestCount)
    }

    @Test fun alreadyCancelledInvocationNeverDispatchesAndConsumesTheCapability() = runBlocking {
        login()
        val leave = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
        // Exercise an actual invocation in a cancelled context, not an unstarted coroutine.
        val entered = async(Dispatchers.Default) {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            leave()
        }
        entered.join()
        assertTrue(entered.isCancelled)
        assertEquals(1, web.requestCount)
        failsWithoutSending(leave)
    }

    @Test fun deadlineIsMeasuredFromCaptureNotInvocationAndCancelsHeldCall() = runBlocking {
        login()
        // Spend most of the capability's lifetime before invoking, without a multi-second sleep.
        // The seam alters this capability only; the real OkHttp call still has a real deadline.
        val now = AtomicLong(1000)
        val leave = client.preparePlaybackLeave("old-exact_ID-1", now::get)
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(3600))
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val start = System.nanoTime()
        val attempt = async(Dispatchers.Default) {
            try { leave(); fail("Held cleanup exceeded deadline") } catch (_: IOException) { }
        }
        assertExactLeave(takeRequest())
        withTimeout(2000) { attempt.await() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800)
        failsWithoutSending(leave)
        assertEquals(2, web.requestCount)
    }

    @Test fun atOrAfterFourSecondDeadlineCannotSendEvenBeforeExpiryTimerRuns() = runBlocking {
        login()
        for (elapsed in listOf(4000L, 4001L, 100_000L)) {
            val now = AtomicLong(1000)
            val leave = client.preparePlaybackLeave("old-exact_ID-1", now::get)
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(elapsed))
            failsWithoutSending(leave)
            failsWithoutSending(leave)
        }
        assertEquals(1, web.requestCount)
    }

    @Test fun unusedCapabilityExpiresOnRealTimerWithoutAnyInvocation() = runBlocking {
        login()
        // A stopped test clock proves expiry is independently scheduled, not only checked on invoke.
        val leave = client.preparePlaybackLeave("old-exact_ID-1") { 0L }
        delay(4250)
        failsWithoutSending(leave)
        assertEquals(1, web.requestCount)
    }

    @Test fun noAuthAtCaptureCannotBorrowLaterLoginAndInvalidIdsNeverSend() = runBlocking {
        val api = PlaybackDevicesApi(client)
        val unavailable = api.prepareLeave("old-exact_ID-1")
        for (id in listOf("", "../other", "phone/other", "with space", "a\n", "x".repeat(129))) {
            try { api.prepareLeave(id); fail("Invalid ID accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, web.requestCount)
        failsWithoutSending(unavailable)
        val beforeLogin = api.prepareLeave("old-exact_ID-1")
        login()
        failsWithoutSending(beforeLogin)
        client.clearSession()
        failsWithoutSending(api.prepareLeave("old-exact_ID-1"))
        assertEquals(1, web.requestCount)
    }

    @Test fun fixedDnsOnlyReturnsCapturedAddressAndNeverResolvesAnotherHostname() {
        val address = loopback()
        val dns = PlaybackLeaveDns("never-resolve.invalid", address)
        assertSame(address, dns.lookup("never-resolve.invalid").single())
        for (host in listOf("other.invalid", "localhost", "127.0.0.1", "")) {
            try { dns.lookup(host); fail("Different hostname accepted") } catch (_: IOException) { }
        }
        assertEquals(0, web.requestCount)
    }

    @Test fun hostnameRouteIsFixedAtCaptureWithNoFreshDnsOrSuccessorAddressFallback() = runBlocking {
        client = SpofifyClient(ServerAddress.parse("http://localhost:${web.port}"), store)
        login() // Ordinary production DNS/connection listener naturally records the direct route.
        val recordedByListener = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
        recordRoute(InetSocketAddress(loopback(2), web.port))
        val unreachableAtCapture = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
        recordRoute() // A successor route must not repair the captured unreachable destination.
        // The fixture is bound specifically to 127.0.0.1, not the unreachable 127.0.0.2.
        // Fresh DNS of localhost or borrowing the latest route would incorrectly send a request.
        withTimeout(2000) { failsWithoutSending(unreachableAtCapture) }
        web.enqueue(MockResponse().setResponseCode(204))
        recordedByListener()
        assertExactLeave(takeRequest())
        assertEquals(2, web.requestCount)
    }

    @Test fun proxyUnresolvedOrWrongPortRoutesFailClosedAndCannotBorrowLaterDirectRoute() = runBlocking {
        login()
        for ((proxy, socket) in listOf(
            Proxy.Type.HTTP to InetSocketAddress(loopback(), web.port),
            Proxy.Type.SOCKS to InetSocketAddress(loopback(), web.port),
            Proxy.Type.DIRECT to InetSocketAddress.createUnresolved("never-resolve.invalid", web.port),
            Proxy.Type.DIRECT to InetSocketAddress(loopback(), if (web.port == 65535) web.port - 1 else web.port + 1),
        )) {
            recordRoute(socket, proxy)
            val unavailable = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
            recordRoute()
            failsWithoutSending(unavailable)
        }
        assertEquals(1, web.requestCount)
    }

    @Test fun productionListenerRejectsAnActuallyAcquiredHttpProxyRoute() = runBlocking {
        login()
        val proxy = MockWebServer().also { it.start(loopback(), 0) }
        try {
            proxy.enqueue(jsonResponse("{}"))
            val proxied = client.httpClient.newBuilder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback(), proxy.port))).build()
            withContext(Dispatchers.IO) {
                proxied.newCall(Request.Builder().url(client.server.url("api", "me", "player")).build())
                    .execute().use { assertTrue(it.isSuccessful) }
            }
            assertNotNull(proxy.takeRequest(2, TimeUnit.SECONDS))
            val unavailable = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
            recordRoute() // No late direct route may turn missing authority into a send.
            failsWithoutSending(unavailable)
            assertEquals(1, proxy.requestCount)
            assertEquals(1, web.requestCount)
        } finally { proxy.shutdown() }
    }

    @Test fun differentOriginAndStaleGenerationCannotSupplyOrReplaceCleanupRoute() = runBlocking {
        login()
        val origin = client.server.url()
        val unresolved = InetSocketAddress.createUnresolved("never-resolve.invalid", web.port)
        val otherOrigins = listOf(
            origin.newBuilder().host("other.invalid").build(),
            origin.newBuilder().scheme("https").build(),
            origin.newBuilder().port(if (web.port == 65535) web.port - 1 else web.port + 1).build(),
        )
        for (other in otherOrigins) {
            recordRoute(unresolved)
            recordRoute(requestUrl = other, routeUrl = origin)
            failsWithoutSending(PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1"))
            recordRoute(requestUrl = origin, routeUrl = other)
            failsWithoutSending(PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1"))
        }
        val oldGeneration = client.personalMutationGeneration()
        client.clearSession()
        login(cookie = "successor-cookie", csrf = "successor-csrf")
        recordRoute(unresolved)
        recordRoute(generation = oldGeneration)
        failsWithoutSending(PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1"))
        recordRoute()
        recordRoute(InetSocketAddress(loopback(2), web.port), generation = oldGeneration)
        web.enqueue(MockResponse().setResponseCode(204))
        PlaybackDevicesApi(client).prepareLeave("successor-exact-ID")()
        val request = takeRequest()
        assertEquals("spofify_session=successor-cookie", request.getHeader("Cookie"))
        assertEquals("successor-csrf", request.getHeader("X-CSRF-Token"))
        assertEquals("{\"action\":\"leave\",\"deviceId\":\"successor-exact-ID\"}", request.body.readUtf8())
        assertEquals(3, web.requestCount)
    }

    @Test fun cookieExpiringAfterCaptureCannotSend() = runBlocking {
        web.enqueue(loginResponse(cookie = "spofify_session=cookie-secret; Path=/; HttpOnly; Max-Age=1"))
        client.login("synthetic", "synthetic-password")
        takeRequest()
        val leave = PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1")
        delay(1100)
        failsWithoutSending(leave)
        // A still-present Session object cannot grant fresh disposal authority with an expired cookie.
        failsWithoutSending(PlaybackDevicesApi(client).prepareLeave("old-exact_ID-1"))
        assertEquals(1, web.requestCount)
    }
}
