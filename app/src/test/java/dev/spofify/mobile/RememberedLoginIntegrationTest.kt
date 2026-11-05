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
    private class MemoryLogins(var value: RememberedLogin? = null) : LoginStore {
        override fun read() = value
        override fun write(value: RememberedLogin?) { this.value = value }
    }
    private fun login(username: String = "listener") = MockResponse().setHeader("Content-Type", "application/json")
        .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")
        .setBody("""{"accountId":"id","username":"$username","role":"user","csrfToken":"synthetic-csrf"}""")

    @Test fun automaticallyRemembersCredentialsSeparatelyFromTheSessionAndNeverAutoSignsInAtAnotherServer() = runBlocking {
        val web = MockWebServer().apply { start() }
        val other = MockWebServer().apply { start() }
        val sessions = MemorySessions()
        val logins = MemoryLogins()
        val graph = AppGraph(context, sessions, logins)
        try {
            // No opt-in call: a current successful login is always eligible to be remembered.
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

    @Test fun compatibilityForgetLeavesAnActiveSessionUsableAndCannotDisableTheNextAutomaticSave() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = MemorySessions()
        val logins = MemoryLogins()
        val graph = AppGraph(context, sessions, logins)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(login()); val active = client.login("listener", "synthetic-password")
            graph.saveLogin(client, "listener", "synthetic-password")
            assertNotNull(logins.value)
            graph.setRememberLogin(false)
            assertNull(logins.value)
            assertNull(graph.rememberedLogin())
            assertTrue(graph.rememberLoginEnabled)
            assertEquals(active, client.currentSession())
            assertNotNull(sessions.value)
            assertFalse(context.getSharedPreferences("connection", Context.MODE_PRIVATE).contains("remember_login"))

            // The compatibility operation forgets now; it is not a hidden opt-out flag.
            web.enqueue(login("next-listener")); client.login("next-listener", " next synthetic password \t")
            graph.saveLogin(client, "next-listener", " next synthetic password \t")
            assertEquals("next-listener", logins.value!!.username)
            assertEquals(" next synthetic password \t", logins.value!!.password)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun aRetiredUnauthenticatedOrUsernameMismatchedClientCannotReplaceTheSavedLogin() = runBlocking {
        val web = MockWebServer().apply { start() }
        val previous = RememberedLogin("https://previous.example", "previous", "previous-synthetic-password")
        val logins = MemoryLogins(previous)
        val graph = AppGraph(context, MemorySessions(), logins)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            assertThrows(IllegalStateException::class.java) { graph.saveLogin(client, "listener", "synthetic-password") }
            assertSame(previous, logins.value)
            web.enqueue(login()); client.login("listener", "synthetic-password")
            assertThrows(IllegalStateException::class.java) { graph.saveLogin(client, "other", "different-password") }
            assertSame(previous, logins.value)

            graph.saveLogin(client, "listener", "synthetic-password")
            val saved = logins.value
            graph.configure(ServerAddress.parse("https://other.example.com"))
            assertThrows(IllegalStateException::class.java) { graph.saveLogin(client, "listener", "synthetic-password") }
            assertSame(saved, logins.value)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun anOldSessionCannotSaveEvenWhenTheSameClientReauthenticatesTheSameUsername() = runBlocking {
        val web = MockWebServer().apply { start() }
        val logins = MemoryLogins()
        val graph = AppGraph(context, MemorySessions(), logins)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(login()); val oldSession = client.login("listener", "old-synthetic-password")
            graph.saveLogin(client, "listener", "old-synthetic-password", oldSession)
            val previous = logins.value
            web.enqueue(login()); val current = client.login("listener", "new-synthetic-password")
            assertEquals(oldSession, current)
            assertNotSame(oldSession, current)
            assertThrows(IllegalStateException::class.java) {
                graph.saveLogin(client, "listener", "old-synthetic-password", oldSession)
            }
            assertSame(previous, logins.value)
            graph.saveLogin(client, "listener", "new-synthetic-password", current)
            assertEquals("new-synthetic-password", logins.value!!.password)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun failedAndPartialAuthenticationCannotReplaceAPreviousSuccessfulLogin() = runBlocking {
        val web = MockWebServer().apply { start() }
        val previous = RememberedLogin("https://previous.example", "previous", "previous-synthetic-password")
        val logins = MemoryLogins(previous)
        val sessions = MemorySessions()
        val graph = AppGraph(context, sessions, logins)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            val incomplete = listOf(
                MockResponse().setResponseCode(401).setBody("""{"error":"invalid credentials"}"""),
                login().removeHeader("Set-Cookie"),
                login().setBody("""{"accountId":"id","username":"listener"}"""),
            )
            for (response in incomplete) {
                web.enqueue(response)
                try {
                    client.login("listener", "incorrect-synthetic-password")
                    fail("An incomplete authentication must not install a session")
                } catch (_: java.io.IOException) { }
                assertNull(client.currentSession())
                assertNull(sessions.value)
                assertThrows(IllegalStateException::class.java) {
                    graph.saveLogin(client, "listener", "incorrect-synthetic-password")
                }
                assertSame(previous, logins.value)
            }
            assertEquals(3, web.requestCount)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun onlyTheLatestSuccessfulAuthenticatedTripleSurvivesLogoutAndFailedAttemptsAtOtherOrigins() = runBlocking {
        val first = MockWebServer().apply { start() }
        val second = MockWebServer().apply { start() }
        val failed = MockWebServer().apply { start() }
        val logins = MemoryLogins()
        val graph = AppGraph(context, MemorySessions(), logins)
        try {
            val old = graph.configure(ServerAddress.parse("http://127.0.0.1:${first.port}"))
            first.enqueue(login("first-listener")); old.login("first-listener", "first-synthetic-password")
            graph.saveLogin(old, "first-listener", "first-synthetic-password")
            val current = graph.configure(ServerAddress.parse("http://127.0.0.1:${second.port}"))
            second.enqueue(login("latest-listener")); current.login("latest-listener", " latest synthetic password \t")
            graph.saveLogin(current, "latest-listener", " latest synthetic password \t")
            val latest = logins.value!!
            second.enqueue(MockResponse().setResponseCode(204)); current.logout()
            graph.clear(current)
            assertSame(latest, graph.rememberedLogin())

            val rejected = graph.configure(ServerAddress.parse("http://127.0.0.1:${failed.port}"))
            failed.enqueue(MockResponse().setResponseCode(401))
            try { rejected.login("rejected", "incorrect-synthetic-password"); fail("Expected rejection") }
            catch (_: java.io.IOException) { }
            assertEquals(rejected.server.origin, graph.lastServer)
            assertSame(latest, graph.rememberedLogin())
            assertEquals(current.server.origin, latest.server)
            assertEquals("latest-listener", latest.username)
            assertEquals(" latest synthetic password \t", latest.password)
        } finally { graph.clear(); first.shutdown(); second.shutdown(); failed.shutdown() }
    }
}
