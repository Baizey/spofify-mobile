package dev.spofify.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RememberedLoginIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class MemorySessions : SessionStore {
        var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class MemoryLogins : LoginStore {
        var value: RememberedLogin? = null
        override fun read() = value
        override fun write(value: RememberedLogin?) { this.value = value }
    }
    private fun login() = MockResponse().setHeader("Content-Type", "application/json")
        .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")
        .setBody("""{"accountId":"id","username":"listener","role":"user","csrfToken":"synthetic-csrf"}""")

    @Test fun remembersCredentialsSeparatelyFromTheSessionAndNeverAutoSignsInAtAnotherServer() = runBlocking {
        val web = MockWebServer().apply { start() }
        val other = MockWebServer().apply { start() }
        val sessions = MemorySessions()
        val logins = MemoryLogins()
        val graph = AppGraph(context, sessions, logins)
        try {
            graph.setRememberLogin(true)
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(login()); client.login("listener", "synthetic-password")
            graph.saveLogin(client, "listener", "synthetic-password")
            graph.clear()
            assertNull(sessions.value)
            assertEquals(client.server.origin, graph.rememberedLogin()!!.server)
            assertEquals("listener", logins.value!!.username)
            assertEquals("synthetic-password", logins.value!!.password)
            graph.configure(ServerAddress.parse("http://127.0.0.1:${other.port}"))
            assertEquals(0, other.requestCount)
            assertNull(graph.client!!.currentSession())
            assertEquals(client.server.origin, logins.value!!.server)
            val plainSettings = context.getSharedPreferences("connection", Context.MODE_PRIVATE).all.values
            assertFalse(plainSettings.contains("synthetic-password"))
            assertFalse(plainSettings.contains("listener"))
        } finally { graph.clear(); web.shutdown(); other.shutdown() }
    }

    @Test fun optingOutForgetsOnlyCredentialsAndLeavesAnActiveSessionUsable() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = MemorySessions()
        val logins = MemoryLogins()
        val graph = AppGraph(context, sessions, logins)
        try {
            graph.setRememberLogin(true)
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(login()); val active = client.login("listener", "synthetic-password")
            graph.saveLogin(client, "listener", "synthetic-password")
            assertNotNull(logins.value)
            graph.setRememberLogin(false)
            assertNull(logins.value)
            assertNull(graph.rememberedLogin())
            assertFalse(graph.rememberLoginEnabled)
            assertEquals(active, client.currentSession())
            assertNotNull(sessions.value)
            graph.saveLogin(client, "listener", "synthetic-password")
            assertNull(logins.value)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun aRetiredOrUnauthenticatedClientCannotReplaceTheSavedLogin() = runBlocking {
        val web = MockWebServer().apply { start() }
        val logins = MemoryLogins()
        val graph = AppGraph(context, MemorySessions(), logins)
        try {
            graph.setRememberLogin(true)
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            assertThrows(IllegalStateException::class.java) { graph.saveLogin(client, "listener", "synthetic-password") }
            assertNull(logins.value)
            web.enqueue(login()); client.login("listener", "synthetic-password")
            graph.saveLogin(client, "listener", "synthetic-password")
            val saved = logins.value
            graph.configure(ServerAddress.parse("https://other.example.com"))
            assertThrows(IllegalStateException::class.java) { graph.saveLogin(client, "other", "different-password") }
            assertSame(saved, logins.value)
        } finally { graph.clear(); web.shutdown() }
    }
}
