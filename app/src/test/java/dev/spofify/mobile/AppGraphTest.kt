package dev.spofify.mobile

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppGraphTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class MemoryStore(var value: StoredSession? = null) : SessionStore {
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private fun summary() = """{"accountId":"id","username":"listener","role":"user","csrfToken":"csrf"}"""
    private fun response() = MockResponse().setHeader("Content-Type", "application/json").setBody(summary())
    private fun login() = response().setHeader("Set-Cookie", "spofify_session=secret; Path=/; HttpOnly; Max-Age=3600")

    @Test fun aNewScreenReusesTheValidatedSessionWithoutClearingBackgroundAuthentication() = runBlocking {
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, MemoryStore())
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(login())
            val session = client.login("listener", "password")
            assertEquals(session, graph.restore())
            assertEquals(session, client.currentSession())
            assertEquals(1, web.requestCount) // No screen-owned revalidation/null-session window.
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun aRetiredClientAndItsQueuedExpiryCallbackCannotAffectANewAccount() = runBlocking {
        val first = MockWebServer().apply { start() }
        val second = MockWebServer().apply { start() }
        val memory = MemoryStore()
        val graph = AppGraph(context, memory)
        try {
            val old = graph.configure(ServerAddress.parse("http://127.0.0.1:${first.port}"))
            first.enqueue(login()); old.login("listener", "password")
            val oldCallback = old.onUnauthorized!!
            val next = graph.configure(ServerAddress.parse("http://127.0.0.1:${second.port}"))
            second.enqueue(login()); next.login("listener", "password")
            oldCallback()
            old.clearSession()
            graph.clear(old) // An old logout cannot clear the current graph.
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(next, graph.client)
            assertFalse(graph.sessionExpired.value)
            assertNotNull(next.currentSession())
            assertEquals(next.server.origin, memory.value!!.server)
        } finally { graph.clear(); first.shutdown(); second.shutdown() }
    }

    @Test fun offlineRestorationCanRetryTheSavedSessionWithoutAPassword() = runBlocking {
        val web = MockWebServer().apply { start() }
        val server = "http://127.0.0.1:${web.port}"
        val memory = MemoryStore(StoredSession(server,
            "spofify_session=secret; Path=/; HttpOnly; Max-Age=3600",
            Session("id", "listener", "user", "csrf")))
        val graph = AppGraph(context, memory)
        try {
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            try { graph.restore(); fail("Expected a connection failure") } catch (_: IOException) { }
            assertNotNull(memory.value)
            assertNull(graph.client!!.currentSession())
            web.enqueue(response())
            assertEquals("listener", graph.restore()!!.username)
            assertNotNull(graph.client!!.currentSession())
            assertEquals(2, web.requestCount)
        } finally { graph.clear(); web.shutdown() }
    }
}
