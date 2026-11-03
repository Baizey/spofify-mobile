package dev.spofify.mobile.core

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SessionStoreOwnerTest {
    private val session = Session("id", "listener", "user", "csrf")
    private val saved = StoredSession("https://music.example.com", "cookie", session)

    @Test fun retiredLeasesCannotReadClearOrOverwriteTheNewSession() {
        val memory = MemorySessionStore()
        val owner = SessionStoreOwner(memory)
        val old = owner.acquire()
        old.write(saved)
        val current = owner.acquire()
        current.write(saved.copy(server = "https://new.example.com"))
        old.write(null)
        old.write(saved)
        assertNull(old.read())
        assertEquals("https://new.example.com", current.read()!!.server)
        owner.clear()
        current.write(saved)
        assertNull(owner.read())
    }

    @Test fun delayedOldClient401CannotClearNewClientPersistence() = runBlocking {
        val first = MockWebServer().apply { start() }
        val second = MockWebServer().apply { start() }
        try {
            val memory = MemorySessionStore()
            val owner = SessionStoreOwner(memory)
            val old = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${first.port}"), owner.acquire())
            first.enqueue(loginResponse())
            old.login("listener", "password")
            first.takeRequest()
            first.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(300, TimeUnit.MILLISECONDS))
            val rejected = async(start = CoroutineStart.UNDISPATCHED) { runCatching { old.library() } }
            first.takeRequest()
            val next = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${second.port}"), owner.acquire())
            second.enqueue(loginResponse(cookie = "spofify_session=new-cookie; Path=/; HttpOnly; Max-Age=3600"))
            next.login("listener", "password")
            assertTrue(rejected.await().exceptionOrNull() is ApiException)
            assertEquals(next.server.origin, memory.read()!!.server)
            assertTrue(memory.read()!!.cookie.contains("new-cookie"))
            assertNotNull(next.currentSession())
        } finally { first.shutdown(); second.shutdown() }
    }
}
