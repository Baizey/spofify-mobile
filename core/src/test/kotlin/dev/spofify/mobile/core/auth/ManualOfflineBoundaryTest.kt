package dev.spofify.mobile.core.auth

import dev.spofify.mobile.core.http.OkHttpAuthApiFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ManualOfflineBoundaryTest {
    private class Policy(@Volatile var enabled: Boolean) : ManualOfflineStore {
        override fun read() = enabled
        override fun write(enabled: Boolean) { this.enabled = enabled }
    }
    private class Sessions(@Volatile var record: StoredSession?) : SessionStore {
        override fun read() = record
        override fun write(value: StoredSession?) { record = value }
    }
    private val logins = object : LoginStore {
        override fun read() = error("No discovery from remembered credentials")
        override fun write(value: RememberedLogin) = error("No password replay")
    }
    private fun record(server: MockWebServer) = StoredSession(server.url("/").toString(),
        "spofify_session=verified; Path=/; HttpOnly", SessionSummary("a", "user", "user", "csrf"))
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun validation() = json("""{"accountId":"a","username":"user","role":"user","csrfToken":"csrf"}""")
    private suspend fun rejected(block: suspend () -> Any?) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected cancellation, got $error", error is CancellationException)
    }
    private suspend fun probe(repo: SessionRepository, id: AccountIdentity) {
        repo.refresh(); repo.signIn(id.origin, "user", "never-submit")
        rejected { repo.renew(id) }
        rejected { repo.get(id, listOf("api", "library")) }
        rejected { repo.mutate(id, listOf("api", "me", "queue"), "PUT", "{}") }
        rejected { repo.playbackTransport(id).connect("phone", "device") }
        rejected { repo.audio(id, "track", 20, 5) }
    }

    @Test fun liveHandoffRetiresStreamSocketRequestAndWorkerButNotAccount() = runBlocking {
        withTimeout(10_000) {
            MockWebServer().use { server ->
                server.start()
                val policy = Policy(false)
                val sessions = Sessions(record(server))
                val owner = SupervisorJob()
                val repo = SessionRepository(sessions, logins, OkHttpAuthApiFactory(), CoroutineScope(owner), manualOfflineStore = policy)
                try {
                    server.enqueue(validation()); repo.start()
                    val first = repo.onlineGrant.first { it != null }!!
                    val id = first.identity
                    fun path() = server.takeRequest(2, TimeUnit.SECONDS)!!.path
                    assertEquals("/api/auth/session", path())
                    server.enqueue(json("""{"ticket":"fixture"}"""))
                    val handoffFrame = CompletableDeferred<Unit>()
                    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text == "handoff") handoffFrame.complete(Unit) else webSocket.send("queued-event")
                        }
                    }))
                    val socket = repo.playbackTransport(id).connect("phone", "device")
                    assertEquals("/api/me/player/connect", path()); assertEquals("/api/me/player/socket", path())
                    server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/6").setBody("234"))
                    repo.audio(id, "track", 2, 3).use { stream ->
                        assertEquals("bytes=2-4", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Range"))
                        server.enqueue(json("{}").setBodyDelay(3, TimeUnit.SECONDS))
                        val pending = async(Dispatchers.IO) { repo.get(id, listOf("fixture")) }
                        assertEquals("/fixture", path())
                        val entered = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
                        val worker = launch { repo.withOnlineGrant(first) {
                            entered.complete(Unit)
                            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(20); cleaned.complete(Unit) } }
                        } }
                        entered.await()
                        repo.setManualOffline(true) { active ->
                            assertEquals(id, active); assertTrue(socket.send("handoff")); handoffFrame.await()
                            rejected { repo.audio(id, "new", 0, null) }
                        }
                        assertTrue(cleaned.isCompleted); worker.join(); rejected { pending.await() }
                        assertTrue(repo.isCurrent(id)); assertTrue(policy.enabled); assertNull(repo.onlineGrant.value)
                        assertFalse(repo.publishIfCurrent(first) { fail("queued publication") })
                        assertFalse(socket.send("retired")); rejected { socket.messages.first() }
                        assertTrue(runCatching { stream.input.read() }.exceptionOrNull() is IOException)
                        probe(repo, id)
                        // Recreate from the exact session + policy just committed. Not even a
                        // transport factory invocation is allowed for this airgapped restore.
                        val restarted = SessionRepository(sessions, logins, AuthApiFactory { error("Offline transport") },
                            CoroutineScope(owner), manualOfflineStore = policy)
                        restarted.start()
                        val local = restarted.state.first { it is AuthState.Authenticated } as AuthState.Authenticated
                        assertEquals(id.accountId, local.identity.accountId); assertNull(restarted.onlineGrant.value)
                        probe(restarted, local.identity)
                        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
                        server.enqueue(validation())
                        repo.setManualOffline(false) { fail("No handoff on return") }
                        val fresh = repo.onlineGrant.first { it != null }!!
                        assertEquals(id, fresh.identity); assertNotSame(first, fresh); assertFalse(policy.enabled)
                        assertEquals("/api/auth/session", path()); rejected { repo.withOnlineGrant(first) { fail("retired worker") } }
                        server.enqueue(validation()); repo.renew(id)
                        assertSame(fresh, repo.onlineGrant.value); assertEquals("/api/auth/session", path())
                        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS)) // no mutation or player replay
                    }
                } finally { owner.cancelAndJoin() }
            }
        }
    }

    @Test fun airgappedColdRestoreAndExplicitSignoutNeverDispatchEvenAtBootstrap() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val sessions = Sessions(record(server)); val policy = Policy(true)
            repeat(2) { launchIndex ->
                val owner = SupervisorJob()
                try {
                    val repo = SessionRepository(sessions, logins, OkHttpAuthApiFactory(), CoroutineScope(owner), manualOfflineStore = policy)
                    repo.signIn(server.url("/").toString(), "user", "no") // before policy read
                    repo.start()
                    withTimeout(2_000) { repo.state.first { it !is AuthState.Starting } }
                    assertTrue(repo.manualOffline.value); assertNull(repo.onlineGrant.value)
                    if (launchIndex == 0) {
                        val id = (repo.state.value as AuthState.Authenticated).identity
                        probe(repo, id); repo.signOut(); assertFalse(repo.isCurrent(id))
                        withTimeout(2_000) { owner.children.toList().joinAll() }; assertNull(sessions.record)
                    } else assertEquals(AuthState.SignedOut(), repo.state.value)
                } finally { owner.cancelAndJoin() }
            }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS)); assertEquals(0, server.requestCount)
        }
    }
}
