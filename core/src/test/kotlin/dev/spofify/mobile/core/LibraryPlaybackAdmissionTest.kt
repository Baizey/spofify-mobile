package dev.spofify.mobile.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LibraryPlaybackAdmissionTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private lateinit var store: MemorySessionStore
    private lateinit var scope: CoroutineScope
    private var transport: LibraryPlaybackTransport? = null
    private var peer: WebSocket? = null
    private val hellos = Channel<String>(4)
    @Before fun setup() {
        web = MockWebServer(); web.start()
        store = MemorySessionStore()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), store)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun teardown() {
        transport?.close(); scope.cancel(); peer?.close(1000, "fixture complete"); hellos.close()
        web.shutdown()
        client.httpClient.connectionPool.evictAll()
        client.httpClient.dispatcher.executorService.shutdown()
        assertTrue(client.httpClient.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
    }
    private suspend fun login() {
        web.enqueue(loginResponse()); client.login("listener", "synthetic-password"); web.takeRequest(5, TimeUnit.SECONDS)!!
    }
    private fun offerSocket() {
        web.enqueue(jsonResponse("""{"ticket":"${"a".repeat(64)}"}"""))
        web.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", LibraryPlaybackProtocol.SUBPROTOCOL)
            .withWebSocketUpgrade(object : WebSocketListener() {
                private var greeted = false
                override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!greeted) {
                        greeted = true
                        hellos.trySend(text)
                        webSocket.send(librarySnapshotFrame())
                    }
                }
            }))
    }
    private suspend fun open(): LibraryPlaybackTransport {
        offerSocket()
        val opened = client.openLibraryPlayback(scope, "Phone", "phone", true)
        transport = opened
        withTimeout(5000) { opened.events.first { it is LibraryPlaybackEvent.Message } }
        return opened
    }
    @Test fun admissionUsesExactOriginOneShotEmptyObjectCsrfCookieAndOnlyRequiredSubprotocol() = runBlocking {
        login(); val opened = open()
        val connect = web.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", connect.method)
        assertEquals("/api/me/player/connect", connect.path)
        assertEquals("{}", connect.body.readUtf8())
        assertEquals(client.server.origin, connect.getHeader("Origin"))
        assertEquals("csrf-secret", connect.getHeader("X-CSRF-Token"))
        assertEquals("spofify_session=cookie-secret", connect.getHeader("Cookie"))
        val upgrade = web.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", upgrade.method)
        assertEquals("/api/me/player/socket", upgrade.path)
        assertEquals(client.server.origin, upgrade.getHeader("Origin"))
        assertEquals("spofify_session=cookie-secret", upgrade.getHeader("Cookie"))
        assertNull(upgrade.getHeader("X-CSRF-Token"))
        assertEquals(LibraryPlaybackProtocol.SUBPROTOCOL, upgrade.getHeader("Sec-WebSocket-Protocol"))
        val hello = withTimeout(5000) { hellos.receive() }
        assertEquals(LibraryPlaybackCodec.hello("a".repeat(64), "Phone", "phone", true), hello)
        assertEquals(client.validatedSessionIdentity(), opened.identity)
        assertTrue(opened.isCurrent)
        assertEquals(3, web.requestCount)
    }
    @Test fun offlineBothEdgesPermanentlyRetireSocketWithoutReplayingTicketOrCommands() = runBlocking {
        login(); val opened = open()
        val before = web.requestCount
        client.setOffline(true)
        val event = withTimeout(5000) { opened.events.first() } as LibraryPlaybackEvent.Closed
        assertEquals(LibraryPlaybackEvent.Reason.OFFLINE, event.reason)
        client.setOffline(false)
        assertFalse(opened.isCurrent)
        assertNull(opened.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Resume)))
        assertEquals(before, web.requestCount)
    }
    @Test fun sameAccountLoginIsANewIncarnationAndCannotRevivePriorSocket() = runBlocking {
        login(); val opened = open(); val previous = opened.identity
        client.clearSession()
        assertEquals(LibraryPlaybackEvent.Reason.AUTHENTICATION,
            (withTimeout(5000) { opened.events.first() } as LibraryPlaybackEvent.Closed).reason)
        web.takeRequest(5, TimeUnit.SECONDS); web.takeRequest(5, TimeUnit.SECONDS)
        login()
        assertEquals(previous.accountId, client.validatedSessionIdentity()!!.accountId)
        assertNotEquals(previous.incarnation, client.validatedSessionIdentity()!!.incarnation)
        assertFalse(opened.isCurrent)
        assertNull(opened.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Next)))
    }
    @Test fun shared401ImmediatelyRetiresLiveSocketAndDoesNotExposeRawFailureText() = runBlocking {
        login(); val opened = open()
        web.enqueue(jsonResponse("""{"error":"provider SQL password secret"}""", 401))
        withContext(Dispatchers.IO) {
            client.httpClient.newCall(Request.Builder().url(client.server.url("api", "library")).build()).execute().close()
        }
        assertNull(client.validatedSessionIdentity())
        val event = withTimeout(5000) { opened.events.first() } as LibraryPlaybackEvent.Closed
        assertEquals(LibraryPlaybackEvent.Reason.AUTHENTICATION, event.reason)
        assertFalse(event.toString().contains("password"))
        assertNull(opened.send(LibraryPlaybackClientMessage.Ack("execution", 1, "", "ok")))
    }
    @Test fun admissionRedirectAndUnavailableResponsesAreNeverRetriedOrFollowed() = runBlocking {
        login()
        for (code in listOf(302, 503)) {
            web.enqueue(jsonResponse("{}", code).setHeader("Location", "/other/socket").setHeader("Retry-After", "0"))
            val before = web.requestCount
            try { client.openLibraryPlayback(scope, "Phone", "phone", true); fail() }
            catch (e: ApiException) { assertEquals(code, e.status) }
            assertEquals(before + 1, web.requestCount)
        }
    }
    @Test fun pendingTicketCannotCrossAnOfflineExitAbaOrUpgradeAfterItsOriginalAdmission() = runBlocking {
        login()
        web.enqueue(jsonResponse("""{"ticket":"${"a".repeat(64)}"}""").setBodyDelay(1, TimeUnit.SECONDS))
        val attempt = async(Dispatchers.Default) { runCatching { client.openLibraryPlayback(scope, "Phone", "phone", true) } }
        assertEquals("/api/me/player/connect", web.takeRequest(5, TimeUnit.SECONDS)!!.path)
        client.setOffline(true); client.setOffline(false)
        val result = withTimeout(5000) { attempt.await() }
        result.getOrNull()?.close()
        assertTrue(result.isFailure)
        assertEquals(2, web.requestCount)
    }
    /** Hold the public factory return until after an admission edge. The actual upgrade Call
     * is already behind the auth interceptor, either at another app interceptor or in DNS
     * after Bridge, but start() cannot yet hold its WebSocket handle. */
    private suspend fun delayedUpgradeAcrossEdge(accountChange: Boolean, routeWait: Boolean) = coroutineScope {
        client = SpofifyClient(ServerAddress.parse("http://localhost:${web.port}"), store)
        login()
        val waiting = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val handleUnpublished = CountDownLatch(1)
        val releaseHandle = CountDownLatch(1)
        val actualCall = AtomicReference<Call>()
        val isolatedPool = ConnectionPool()
        fun awaitRelease(gate: CountDownLatch) {
            if (!gate.await(5, TimeUnit.SECONDS)) throw java.io.IOException("Synthetic upgrade gate timed out")
        }
        client.libraryPlaybackWebSocketFactory = { admitted ->
            val builder = admitted.newBuilder().connectionPool(isolatedPool).addInterceptor { chain ->
                actualCall.set(chain.call())
                if (!routeWait) { waiting.countDown(); awaitRelease(releaseWork) }
                chain.proceed(chain.request())
            }
            if (routeWait) builder.dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    waiting.countDown(); awaitRelease(releaseWork)
                    return listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                }
            })
            val gated = builder.build()
            object : WebSocket.Factory {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    val handle = gated.newWebSocket(request, listener)
                    try {
                        awaitRelease(waiting)
                        handleUnpublished.countDown()
                        awaitRelease(releaseHandle)
                        return handle
                    } catch (error: Exception) { handle.cancel(); throw error }
                }
            }
        }
        web.enqueue(jsonResponse("""{"ticket":"${"a".repeat(64)}"}"""))
        val opening = async(Dispatchers.Default) { runCatching { client.openLibraryPlayback(scope, "Phone", "phone", true) } }
        try {
            withContext(Dispatchers.IO) { assertTrue(handleUnpublished.await(5, TimeUnit.SECONDS)) }
            val captured = actualCall.get()!!
            assertFalse(captured.isCanceled())
            val before = client.validatedSessionIdentity()!!
            if (accountChange) {
                client.clearSession()
                // This assertion is BEFORE the factory returns and before any successor exists.
                assertTrue(captured.isCanceled())
                web.enqueue(loginResponse(cookie = "spofify_session=successor-cookie; Path=/; HttpOnly; Max-Age=3600; SameSite=Lax")
                    .setBody(SESSION_JSON.replace("\"a1\"", "\"b1\"")))
                client.login("listener", "synthetic-successor-password")
                assertNotEquals(before.accountId, client.validatedSessionIdentity()!!.accountId)
            } else {
                client.setOffline(true)
                assertTrue(captured.isCanceled())
                client.setOffline(false)
                assertEquals(before, client.validatedSessionIdentity())
            }
            client.libraryPlaybackWebSocketFactory = { it }
            val successor = open() // Fresh admission while the old factory still holds its handle.
            assertTrue(successor.isCurrent)
            releaseWork.countDown(); releaseHandle.countDown()
            val stale = withTimeout(5000) { opening.await() }.getOrNull()
            withTimeout(5000) {
                while (client.httpClient.dispatcher.runningCalls().any { it === captured }) delay(10)
            }
            if (stale != null) {
                assertFalse(stale.isCurrent)
                assertTrue(withTimeout(5000) { stale.events.first() } is LibraryPlaybackEvent.Closed)
                assertNull(stale.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Resume)))
                stale.close()
            }
            assertTrue(successor.isCurrent)
            assertNotNull(successor.send(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Pause)))
            val count = if (accountChange) 5 else 4
            assertEquals(count, web.requestCount)
            val recorded = List(count - 1) { web.takeRequest(5, TimeUnit.SECONDS)!! } // login() consumed the first request.
            val upgrades = recorded.filter { it.path == "/api/me/player/socket" }
            assertEquals(1, upgrades.size) // No old headers were emitted after either gate released.
            assertEquals(if (accountChange) "spofify_session=successor-cookie" else "spofify_session=cookie-secret", upgrades.single().getHeader("Cookie"))
            assertEquals(2, recorded.count { it.path == "/api/me/player/connect" })
        } finally {
            releaseWork.countDown(); releaseHandle.countDown()
            client.libraryPlaybackWebSocketFactory = { it }
            withContext(NonCancellable) { withTimeout(5000) { opening.await() }.getOrNull()?.close() }
            isolatedPool.evictAll()
        }
    }
    @Test fun delayedUpgradeDispatchAndUnpublishedHandleAreCancelledOnAccountChange() = runBlocking {
        delayedUpgradeAcrossEdge(accountChange = true, routeWait = false)
    }
    @Test fun delayedUpgradeRouteAndUnpublishedHandleCannotEmitOldCookieAfterAccountChange() = runBlocking {
        delayedUpgradeAcrossEdge(accountChange = true, routeWait = true)
    }
    @Test fun delayedUpgradeDispatchAndUnpublishedHandleCannotSurviveOfflineAba() = runBlocking {
        delayedUpgradeAcrossEdge(accountChange = false, routeWait = false)
    }
    @Test fun delayedUpgradeRouteAndUnpublishedHandleCannotEmitCookieAfterOfflineAba() = runBlocking {
        delayedUpgradeAcrossEdge(accountChange = false, routeWait = true)
    }
    @Test fun localAuthenticationStorageFailureStillSynchronouslyRetiresSocket() = runBlocking {
        val backing = MemorySessionStore()
        var failClear = false
        client = SpofifyClient(client.server, object : SessionStore {
            override fun read() = backing.read()
            override fun write(value: StoredSession?) {
                if (failClear && value == null) throw java.io.IOException("synthetic storage failure")
                backing.write(value)
            }
        })
        login(); val opened = open(); failClear = true
        try { client.clearSession(); fail() } catch (_: java.io.IOException) { }
        assertEquals(LibraryPlaybackEvent.Reason.AUTHENTICATION,
            (withTimeout(5000) { opened.events.first() } as LibraryPlaybackEvent.Closed).reason)
        assertNull(client.validatedSessionIdentity())
        assertFalse(opened.isCurrent)
    }
    @Test fun websocketUpgradeHttpStatusFollowupIsRefusedBeforeASecondNetworkAttempt() = runBlocking {
        login()
        web.enqueue(jsonResponse("""{"ticket":"${"a".repeat(64)}"}"""))
        web.enqueue(jsonResponse("{}", 503).setHeader("Retry-After", "0"))
        val before = web.requestCount
        val opened = client.openLibraryPlayback(scope, "Phone", "phone", true)
        transport = opened
        assertTrue(withTimeout(5000) { opened.events.first() } is LibraryPlaybackEvent.Closed)
        assertEquals(before + 2, web.requestCount)
    }
    @Test fun missingValidatedSessionAndOfflineModeDoNotRequestATicket() = runBlocking {
        try { client.openLibraryPlayback(scope, "Phone", "phone", true); fail() } catch (_: java.io.IOException) { }
        assertEquals(0, web.requestCount)
        login(); client.setOffline(true)
        val before = web.requestCount
        try { client.openLibraryPlayback(scope, "Phone", "phone", true); fail() } catch (_: java.io.IOException) { }
        assertEquals(before, web.requestCount)
    }
    @Test fun malformedOrDuplicateTicketNeverUpgradesAndTicketIsNotPersisted() = runBlocking {
        login()
        val saved = store.value
        for (raw in listOf("""{"ticket":"${"a".repeat(64)}","ticket":"${"b".repeat(64)}"}""", """{"ticket":"bad"}""", """{"ticket":null}""")) {
            web.enqueue(jsonResponse(raw))
            val before = web.requestCount
            try { client.openLibraryPlayback(scope, "Phone", "phone", true); fail() } catch (_: LibraryPlaybackProtocolException) { }
            assertEquals(before + 1, web.requestCount)
        }
        assertSame(saved, store.value)
    }
}
