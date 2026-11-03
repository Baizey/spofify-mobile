package dev.spofify.mobile.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

internal class MemorySessionStore : SessionStore {
    var value: StoredSession? = null
    override fun read() = value
    override fun write(value: StoredSession?) { this.value = value }
}

internal const val SESSION_JSON = """{"accountId":"a1","username":"listener","role":"user","csrfToken":"csrf-secret","newField":true}"""
internal fun jsonResponse(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
internal fun loginResponse(role: String = "user", cookie: String = "spofify_session=cookie-secret; Path=/; HttpOnly; Max-Age=3600; SameSite=Lax") =
    jsonResponse(SESSION_JSON.replace("\"user\"", "\"$role\"")).addHeader("Set-Cookie", cookie)

class SpofifyClientAuthTest {
    private lateinit var web: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var client: SpofifyClient
    @Before fun setup() {
        web = MockWebServer()
        web.start()
        store = MemorySessionStore()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), store)
    }
    @After fun teardown() { web.shutdown() }

    @Test fun loginPersistsOnlySessionAndExpiringCookieAndWritesUseCsrf() = runBlocking {
        web.enqueue(loginResponse())
        web.enqueue(jsonResponse("""{"trackId":"track/1%","liked":true}"""))
        val session = client.login("listener", "password-not-persisted")
        assertEquals("user", session.role)
        val login = web.takeRequest()
        assertEquals("POST", login.method)
        assertEquals(client.server.origin, login.getHeader("Origin"))
        assertEquals("1", login.getHeader("X-Spofify-Login"))
        assertNull(login.getHeader("X-CSRF-Token"))
        assertTrue(login.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(login.body.readUtf8().contains("password-not-persisted"))
        val saved = store.value!!
        assertEquals(client.server.origin, saved.server)
        assertTrue(saved.cookie.contains("expires=", ignoreCase = true))
        assertFalse(saved.toString().contains("password-not-persisted"))
        assertEquals(session, saved.session)
        client.setLiked("track/1%", true)
        val write = web.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("/api/me/likes", write.path)
        assertEquals("csrf-secret", write.getHeader("X-CSRF-Token"))
        assertEquals("spofify_session=cookie-secret", write.getHeader("Cookie"))
        assertEquals(client.server.origin, write.getHeader("Origin"))
        assertNull(write.getHeader("X-Spofify-Login"))
        assertEquals("""{"trackId":"track/1%","liked":true}""", write.body.readUtf8())
    }

    @Test fun failedLoginIsSafeAndDoesNotNotifyUnauthorized() = runBlocking {
        var notifications = 0
        client.onUnauthorized = { notifications++ }
        web.enqueue(jsonResponse("""{"error":"password <secret> /private/path"}""", 401))
        val error = apiFailure { client.login("listener", "password") }
        assertEquals(401, error.status)
        assertEquals("Invalid username or password", error.message)
        assertNull(client.currentSession())
        assertNull(store.value)
        assertEquals(0, notifications)
    }

    @Test fun sharedMedia401InvalidatesAccountAndNotifies() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        var notifications = 0
        client.onUnauthorized = { notifications++ }
        web.enqueue(jsonResponse("{}", 401))
        client.httpClient.newCall(Request.Builder().url(client.server.url("api", "library", "tracks", "track", "audio")).build()).execute().close()
        assertNull(client.currentSession())
        assertNull(store.value)
        assertEquals(1, notifications)
        assertEquals("spofify_session=cookie-secret", web.takeRequest().getHeader("Cookie"))
    }

    @Test fun restoreRevalidatesAndRefreshesSession() = runBlocking {
        web.enqueue(loginResponse())
        val session = client.login("listener", "password")
        web.takeRequest()
        val restored = SpofifyClient(client.server, store)
        assertNull(restored.currentSession())
        web.enqueue(jsonResponse(SESSION_JSON.replace("csrf-secret", "fresh-csrf")))
        assertEquals(session.copy(csrfToken = "fresh-csrf"), restored.restore())
        val request = web.takeRequest()
        assertEquals("/api/auth/session", request.path)
        assertEquals("spofify_session=cookie-secret", request.getHeader("Cookie"))
        assertEquals("fresh-csrf", store.value!!.session.csrfToken)
        web.enqueue(jsonResponse("{}", 401))
        assertNull(restored.restore())
        assertNull(store.value)
        assertNull(restored.currentSession())
        assertTrue(restored.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
        val requestsAfterRejection = web.requestCount
        assertNull(restored.restore())
        assertEquals(requestsAfterRejection, web.requestCount)
    }

    @Test fun restore503PreservesSavedSessionForExplicitRetry() = runBlocking {
        assertTransientRestoreCanRetry(503)
    }

    @Test fun restore429PreservesSavedSessionForExplicitRetry() = runBlocking {
        assertTransientRestoreCanRetry(429)
    }

    @Test fun other5xxRestoreFailuresAlsoPreserveSavedSession() = runBlocking {
        for (status in listOf(500, 502, 599)) assertTransientRestoreCanRetry(status)
    }

    private suspend fun assertTransientRestoreCanRetry(status: Int) {
        client.clearSession()
        val saved = StoredSession(client.server.origin,
            "spofify_session=cookie-secret; Path=/; HttpOnly; Expires=Wed, 01 Jan 2031 00:00:00 GMT",
            Session("a1", "listener", "user", "old-csrf"))
        store.value = saved
        var unauthorized = 0
        client.onUnauthorized = { unauthorized++ }
        assertNull(client.currentSession())
        web.enqueue(jsonResponse("{}", status))
        assertEquals(status, apiFailure { client.restore() }.status)
        assertEquals(saved, store.value)
        assertNull(client.currentSession())
        assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
        assertEquals(0, unauthorized)
        val rejected = web.takeRequest()
        assertEquals("GET", rejected.method)
        assertEquals("/api/auth/session", rejected.path)
        assertEquals("spofify_session=cookie-secret", rejected.getHeader("Cookie"))

        web.enqueue(jsonResponse(SESSION_JSON))
        val restored = client.restore()!!
        assertEquals("csrf-secret", restored.csrfToken)
        assertEquals(restored, client.currentSession())
        assertEquals(restored, store.value!!.session)
        assertEquals(1, client.httpClient.cookieJar.loadForRequest(client.server.url()).size)
        assertEquals(0, unauthorized)
        val retried = web.takeRequest()
        assertEquals("GET", retried.method)
        assertEquals("/api/auth/session", retried.path)
        assertEquals("spofify_session=cookie-secret", retried.getHeader("Cookie"))
    }

    @Test fun incompatibleRestoreJsonStillPermanentlyInvalidatesSavedSession() = runBlocking {
        store.value = StoredSession(client.server.origin,
            "spofify_session=cookie-secret; Path=/; HttpOnly; Expires=Wed, 01 Jan 2031 00:00:00 GMT",
            Session("a1", "listener", "user", "old-csrf"))
        web.enqueue(jsonResponse("""{"unexpected":"response"}"""))
        assertEquals(200, apiFailure { client.restore() }.status)
        assertNull(store.value)
        assertNull(client.currentSession())
        assertTrue(client.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
    }

    @Test fun expiredOrMalformedRestoresNeverReachNetwork() = runBlocking {
        val session = Session("id", "listener", "user", "csrf")
        listOf(
            "spofify_session=x; Path=/; HttpOnly; Expires=Thu, 01 Jan 1970 00:00:01 GMT",
            "spofify_session=x; Path=/; HttpOnly",
            "spofify_session=x; Path=/; Max-Age=3600",
            "spofify_session=x; HttpOnly; Max-Age=3600",
            "spofify_session=x; Path=/; HttpOnly; Secure; Max-Age=3600",
            "spofify_session=x; Domain=127.0.0.1; Path=/; HttpOnly; Max-Age=3600",
            "spofify_session=x; Path=/api; HttpOnly; Max-Age=3600",
            "__Host-spofify_session=x; Path=/; HttpOnly; Max-Age=3600",
        ).forEach { serialized ->
            store.value = StoredSession(client.server.origin, serialized, session)
            assertNull(client.restore())
            assertNull(store.value)
        }
        assertEquals(0, web.requestCount)
    }

    @Test fun switchingServersNeverReusesStoredCookie() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        val other = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port + 1}"), store)
        assertNull(other.restore())
        assertNull(other.currentSession())
        assertNull(store.value)
        assertEquals(1, web.requestCount)
    }

    @Test fun cookieJarRequiresExactOriginAndIgnoresUnsolicitedCookies() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        val jar = client.httpClient.cookieJar
        assertEquals(1, jar.loadForRequest(client.server.url("media")).size)
        assertTrue(jar.loadForRequest(client.server.url().newBuilder().port(web.port + 1).build()).isEmpty())
        assertTrue(jar.loadForRequest(client.server.url().newBuilder().scheme("https").build()).isEmpty())
        assertTrue(jar.loadForRequest(client.server.url().newBuilder().host("localhost").build()).isEmpty())
        val forged = Cookie.Builder().name("spofify_session").value("forged").hostOnlyDomain("127.0.0.1").path("/").build()
        jar.saveFromResponse(client.server.url(), listOf(forged))
        assertEquals("cookie-secret", jar.loadForRequest(client.server.url()).single().value)
    }

    @Test fun offOriginIsBlockedBeforeCookiesOrNetworkAndRedirectsAreNotFollowed() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        try {
            client.httpClient.newCall(Request.Builder().url("http://127.0.0.1:${web.port + 1}/secret").header("Cookie", "injected=1").build()).execute().close()
            fail("Off-origin request accepted")
        } catch (error: IOException) { assertEquals("Off-origin requests are not allowed", error.message) }
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${web.port + 1}/stolen"))
        val error = apiFailure { client.library() }
        assertEquals(302, error.status)
        assertEquals(2, web.requestCount)
        assertFalse(client.httpClient.followRedirects)
        assertFalse(client.httpClient.followSslRedirects)
        assertFalse(client.httpClient.retryOnConnectionFailure)
    }

    @Test fun logoutClearsEvenWhenServerFails() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        web.enqueue(jsonResponse("{}", 503))
        assertEquals(503, apiFailure { client.logout() }.status)
        val request = web.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/logout", request.path)
        assertEquals("csrf-secret", request.getHeader("X-CSRF-Token"))
        assertNull(client.currentSession())
        assertNull(store.value)
    }

    @Test fun mutationIsNotReplayedOnRetryable503() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        web.enqueue(loginResponse())
        assertEquals(503, apiFailure { client.login("listener", "password") }.status)
        assertEquals(1, web.requestCount)
        assertNotNull(web.takeRequest(1, TimeUnit.SECONDS))
        assertNull(web.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun disconnectedMutationIsNotRetried() = runBlocking {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        web.enqueue(MockResponse().setResponseCode(204))
        try { client.recordHistory("event-id", "track-id"); fail("Expected network error") } catch (_: IOException) { }
        assertEquals(1, web.requestCount)
        assertNotNull(web.takeRequest(1, TimeUnit.SECONDS))
        assertNull(web.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun canceledRestoreCannotLeaveAnUnvalidatedMediaCookie() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        val restored = SpofifyClient(client.server, store)
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async(Dispatchers.Default) { restored.restore() }
        assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(1, restored.httpClient.cookieJar.loadForRequest(client.server.url()).size)
        job.cancelAndJoin()
        assertNull(restored.currentSession())
        assertTrue(restored.httpClient.cookieJar.loadForRequest(client.server.url()).isEmpty())
        assertNotNull(store.value) // An explicit later restore can revalidate the saved session.
    }

    @Test fun pendingLoginCannotResurrectAnExplicitlyClearedSession() = runBlocking {
        val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(5, TimeUnit.SECONDS)
                return loginResponse()
            }
        }
        val job = async(Dispatchers.Default) {
            try { client.login("listener", "password"); null } catch (error: IOException) { error }
        }
        try {
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            client.clearSession()
        } finally { release.countDown() }
        assertNotNull(job.await())
        assertNull(client.currentSession())
        assertNull(store.value)
    }

    @Test fun oldMedia401CannotInvalidateANewerLogin() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/old-media") {
                release.await(5, TimeUnit.SECONDS)
                jsonResponse("{}", 401)
            } else loginResponse()
        }
        var notifications = 0
        client.onUnauthorized = { notifications++ }
        val oldRequest = async(Dispatchers.IO) {
            client.httpClient.newCall(Request.Builder().url(client.server.url("old-media")).build()).execute().use { it.code }
        }
        try {
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            client.login("listener", "password")
        } finally { release.countDown() }
        assertEquals(401, oldRequest.await())
        assertNotNull(client.currentSession())
        assertNotNull(store.value)
        assertEquals(0, notifications)
    }

    @Test fun responseBodyAndSessionInstallationDoNotBlockCallerDispatcher() = runBlocking {
        var installationThread: String? = null
        val observingStore = object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) {
                if (value != null) installationThread = Thread.currentThread().name
            }
        }
        val observedClient = SpofifyClient(client.server, observingStore)
        web.enqueue(loginResponse())
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fake-ui") }.asCoroutineDispatcher().use { ui ->
            withContext(ui) { observedClient.login("listener", "password") }
        }
        assertNotNull(installationThread)
        assertNotEquals("fake-ui", installationThread)
    }

    @Test fun bodylessSharedClientMutationIsAlsoNotReplayed() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        web.enqueue(MockResponse().setResponseCode(204))
        client.httpClient.newCall(Request.Builder().url(client.server.url("resource")).delete().build()).execute().use {
            assertEquals(503, it.code)
        }
        assertEquals(1, web.requestCount)
        assertNotNull(web.takeRequest(1, TimeUnit.SECONDS))
        assertNull(web.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun rejectsDuplicateSessionCookies() = runBlocking {
        web.enqueue(loginResponse().addHeader("Set-Cookie", "spofify_session=second; Path=/; HttpOnly; Max-Age=3600"))
        assertTrue(apiFailure { client.login("listener", "password") }.message.contains("cookie"))
        assertNull(client.currentSession())
        assertNull(store.value)
    }

    @Test fun rejectsLoginWithoutValidCookieOrJson() = runBlocking {
        web.enqueue(jsonResponse(SESSION_JSON))
        assertTrue(apiFailure { client.login("listener", "password") }.message.contains("cookie"))
        web.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>/private/path secret</html>"))
        val error = apiFailure { client.login("listener", "password") }
        assertTrue(error.message.contains("JSON"))
        assertFalse(error.message.contains("/private/path"))
        assertNull(store.value)
    }
}

internal suspend fun apiFailure(block: suspend () -> Unit): ApiException {
    try { block(); fail("Expected ApiException") } catch (error: ApiException) { return error }
    throw AssertionError("Expected ApiException")
}
