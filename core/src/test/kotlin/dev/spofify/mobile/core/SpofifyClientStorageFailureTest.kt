package dev.spofify.mobile.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpofifyClientStorageFailureTest {
    private lateinit var web: MockWebServer
    private lateinit var memory: MemorySessionStore
    private lateinit var client: SpofifyClient
    @Volatile private var failClear = false

    @Before fun setup() {
        web = MockWebServer()
        web.start()
        memory = MemorySessionStore()
        val throwingStore = object : SessionStore {
            override fun read() = memory.read()
            override fun write(value: StoredSession?) {
                if (value == null && failClear) throw IllegalStateException("private storage /private/account/session")
                memory.write(value)
            }
        }
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), throwingStore)
    }
    @After fun teardown() { web.shutdown() }

    @Test fun unauthorizedStopsPlaybackAndDropsRuntimeAuthEvenWhenPersistedClearFails() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        failClear = true
        var stopped = 0
        client.onUnauthorized = {
            assertNull(client.currentSession())
            assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
            stopped++
        }
        web.enqueue(jsonResponse("""{"error":"private response /private/account/session"}""", 401))
        try {
            ApiTransport(client).get(listOf("api", "library"))
            fail("Expected a safe persistence/network failure")
        } catch (error: IOException) {
            assertSafeExceptionChain(error)
        }
        web.takeRequest()
        assertEquals(1, stopped)
        assertNull(client.currentSession())
        assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
        assertNotNull(memory.value) // Persistence failed; runtime auth must still be unusable.
        withTimeout(5_000) {
            while (client.httpClient.dispatcher.runningCallsCount() != 0) delay(10)
        }
        // A discarded 401 must not leave a connection allocated to an unread response body.
        assertEquals(client.httpClient.connectionPool.connectionCount(), client.httpClient.connectionPool.idleConnectionCount())
        web.enqueue(jsonResponse("{}"))
        ApiTransport(client).get(listOf("api", "library"))
        val after = web.takeRequest()
        assertNull(after.getHeader("Cookie"))
        assertNull(after.getHeader("X-CSRF-Token"))
        assertEquals(1, stopped)
    }

    @Test fun oldUnauthorizedResponseCannotStopOrInvalidateANewerLoginWhenClearWouldFail() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/old") {
                release.await(5, TimeUnit.SECONDS)
                jsonResponse("{}", 401)
            } else loginResponse(cookie = "spofify_session=new-cookie; Path=/; HttpOnly; Max-Age=3600; SameSite=Lax")
        }
        var stopped = 0
        client.onUnauthorized = { stopped++ }
        val old = async(Dispatchers.Default) { apiFailure { ApiTransport(client).get(listOf("old")) } }
        try {
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            client.login("new-account", "password")
            failClear = true
        } finally { release.countDown() }
        assertEquals(401, old.await().status)
        assertEquals(0, stopped)
        assertNotNull(client.currentSession())
        assertEquals("new-cookie", client.httpClient.cookieJar.loadForRequest(client.server.url()).single().value)
        assertTrue(memory.value!!.cookie.contains("new-cookie"))
    }

    @Test fun explicitClearFailureIsSafeAndCannotKeepRuntimeCredentials() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        failClear = true
        try { client.clearSession(); fail("Expected storage failure") }
        catch (error: IOException) {
            assertEquals("Unable to clear the saved session. Sign in again.", error.message)
            assertSafeExceptionChain(error)
        }
        assertNull(client.currentSession())
        assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
    }

    private fun assertSafeExceptionChain(error: Throwable) {
        var current: Throwable? = error
        repeat(8) {
            current?.let {
                assertTrue(it is IOException)
                assertFalse(it.toString().contains("private"))
                assertFalse(it.toString().contains(client.server.origin))
                current = it.cause
            }
        }
        assertNull("Unexpected exception cause cycle or excessive wrapping", current)
    }
}
