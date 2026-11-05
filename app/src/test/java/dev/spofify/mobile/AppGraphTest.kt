package dev.spofify.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
    private class MemoryLogins(var value: RememberedLogin? = null) : LoginStore {
        var writes = 0
        override fun read() = value
        override fun write(value: RememberedLogin?) { writes++; this.value = value }
    }
    private fun summary() = """{"accountId":"id","username":"listener","role":"user","csrfToken":"csrf"}"""
    private fun response() = MockResponse().setHeader("Content-Type", "application/json").setBody(summary())
    private fun login() = response().setHeader("Set-Cookie", "spofify_session=secret; Path=/; HttpOnly; Max-Age=3600")

    @Test fun legacyFalseIsRemovedWithoutDiscardingAValidEncryptedLoginOrUnrelatedPreferences() {
        val settings = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val saved = RememberedLogin("https://saved.example", "saved-listener", "synthetic-password")
        settings.edit().putBoolean("remember_login", false).putString("server", "https://hint.example")
            .putString("synthetic_unrelated_setting", "preserve-me").commit()
        val logins = MemoryLogins(saved)
        val graph = AppGraph(context, MemoryStore(), logins)
        try {
            assertTrue(graph.rememberLoginEnabled)
            assertFalse(settings.contains("remember_login"))
            assertSame(saved, graph.rememberedLogin())
            assertEquals(0, logins.writes)
            assertEquals("https://hint.example", graph.lastServer)
            assertEquals("preserve-me", settings.getString("synthetic_unrelated_setting", null))
            assertNull(graph.client)
        } finally { graph.clear() }
    }

    @Test fun legacyPreferenceRemovalFailureIsBestEffortAndCannotGateAValidLoginRecord() {
        val settings = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        settings.edit().putBoolean("remember_login", false).commit()
        val saved = RememberedLogin("https://saved.example", "saved-listener", "synthetic-password")
        for (throws in listOf(false, true)) {
            val failingSettings = object : SharedPreferences by settings {
                override fun edit(): SharedPreferences.Editor {
                    val editor = settings.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                        override fun commit(): Boolean {
                            if (throws) throw IllegalStateException("Synthetic preference removal failure")
                            return false
                        }
                    }
                }
            }
            val wrapped = object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String?, mode: Int) =
                    if (name == "connection") failingSettings else super.getSharedPreferences(name, mode)
            }
            val logins = MemoryLogins(saved)
            val graph = AppGraph(wrapped, MemoryStore(), logins)
            try {
                assertTrue(settings.contains("remember_login")) // Removal really did fail.
                assertTrue(graph.rememberLoginEnabled)
                assertSame(saved, graph.rememberedLogin())
                assertEquals(0, logins.writes)
                assertNull(graph.client)
            } finally { graph.clear() }
        }
    }

    @Test fun legacyFalseWithNoEncryptedLoginDoesNotResurrectPreviouslyDeletedCredentials() {
        val settings = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        settings.edit().putBoolean("remember_login", false).putString("server", "https://hint.example").commit()
        val logins = MemoryLogins()
        val graph = AppGraph(context, MemoryStore(), logins)
        try {
            assertTrue(graph.rememberLoginEnabled)
            assertFalse(settings.contains("remember_login"))
            assertNull(graph.rememberedLogin())
            assertEquals(0, logins.writes)
            assertNull(graph.client)
            assertEquals("https://hint.example", graph.lastServer)
        } finally { graph.clear() }
    }

    @Test fun legacyTrueIsAlsoRemovedWithoutRewritingTheSeparateSessionRecord() {
        val settings = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        settings.edit().putBoolean("remember_login", true).commit()
        val saved = StoredSession("https://saved.example",
            "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600",
            Session("id", "listener", "user", "synthetic-csrf"))
        val sessions = MemoryStore(saved)
        val graph = AppGraph(context, sessions, MemoryLogins())
        try {
            assertTrue(graph.rememberLoginEnabled)
            assertFalse(settings.contains("remember_login"))
            assertSame(saved, sessions.value)
            assertEquals(saved.server, graph.client!!.server.origin)
            assertNull(graph.client!!.currentSession())
        } finally { graph.clear() }
    }

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

    @Test fun queuedExpiryCannotRetireANewerSessionInstalledOnTheSameClient() = runBlocking {
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, MemoryStore())
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(login()); client.login("listener", "password")
            client.clearSession()
            val oldEpoch = dev.spofify.mobile.playback.PlaybackRequests.epoch
            client.onUnauthorized!!.invoke() // Expiry posts main cleanup, but invalidates starts now.
            assertTrue(dev.spofify.mobile.playback.PlaybackRequests.epoch > oldEpoch)
            web.enqueue(login()); val fresh = client.login("listener", "password")
            val freshEpoch = dev.spofify.mobile.playback.PlaybackRequests.epoch
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(client, graph.client)
            assertSame(fresh, client.currentSession())
            assertFalse(graph.sessionExpired.value)
            assertEquals(freshEpoch, dev.spofify.mobile.playback.PlaybackRequests.epoch)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun offlineRestorationCanRetryTheSavedSessionWithoutAPassword() = runBlocking {
        val web = MockWebServer().apply { start() }
        val server = "http://127.0.0.1:${web.port}"
        val memory = MemoryStore(StoredSession(server,
            "spofify_session=secret; Path=/; HttpOnly; Max-Age=3600",
            Session("id", "listener", "user", "csrf")))
        val remembered = RememberedLogin(server, "listener", "synthetic-password")
        val graph = AppGraph(context, memory, MemoryLogins(remembered))
        try {
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            try { graph.restore(); fail("Expected a connection failure") } catch (_: IOException) { }
            assertNotNull(memory.value)
            assertNull(graph.client!!.currentSession())
            assertSame(remembered, graph.rememberedLogin())
            // DISCONNECT_AT_START produces a bookkeeping placeholder, not a parsed HTTP request.
            // Consume it separately; its synthetic method/path/body cannot establish wire behavior.
            assertNotNull(web.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
            web.enqueue(response())
            assertEquals("listener", graph.restore()!!.username)
            assertNotNull(graph.client!!.currentSession())
            assertEquals(2, web.requestCount)
            // Assert only the decoded retry: cookie revalidation, never a login POST/password body.
            val retry = web.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("GET", retry.method)
            assertEquals("/api/auth/session", retry.path)
            assertEquals(0L, retry.bodySize)
        } finally { graph.clear(); web.shutdown() }
    }
}
