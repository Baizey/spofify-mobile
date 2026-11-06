package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.*
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaylistSourceStampTest {
    @Test fun onlyConfirmedRuleInputsRetireSourceAndReturningToSameAccountDoesNotReviveIt() = runBlocking {
        MockWebServer().use { web ->
            web.start(InetAddress.getByName("127.0.0.1"), 0)
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            try {
                suspend fun login() {
                    web.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                        .setHeader("Set-Cookie", "spofify_session=fixture; Path=/; HttpOnly; Max-Age=3600")
                        .setBody("""{"accountId":"fixture","username":"listener","role":"user","csrfToken":"csrf"}"""))
                    client.login("listener", "synthetic")
                }
                login()
                val source = PlaylistSourceStamp(client)
                val before = client.personalRevision.value
                assertTrue(source.isCurrent())
                client.notifyPersonalChange(PersonalCollection.HISTORY)
                assertTrue("History/progress is not a recipe input", source.isCurrent())
                web.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
                try { client.setLiked("song", true); fail("Expected rejected mutation") } catch (_: ApiException) { }
                assertEquals(before.likes, client.personalRevision.value.likes)
                assertTrue(source.isCurrent())
                web.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
                client.setLiked("song", true)
                assertEquals(before.likes + 1, client.personalRevision.value.likes)
                assertFalse(source.isCurrent())
                val afterLike = PlaylistSourceStamp(client)
                assertTrue(afterLike.isCurrent())
                client.notifyPersonalChange(PersonalCollection.PLAYLISTS)
                assertFalse(afterLike.isCurrent())
                val beforeLogout = PlaylistSourceStamp(client)
                client.clearSession()
                login()
                assertFalse("Equal account identity is not the same authenticated incarnation", beforeLogout.isCurrent())
                assertFalse(source.isCurrent())
            } finally {
                client.httpClient.dispatcher.cancelAll()
                client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun readGenerationNeverRenewsCapturedCopyAndPreparationCannotRetireItsOwnSource() {
        val source = PlaylistRequestGeneration()
        val ticket = source.ticket()
        val captured = PlaylistCopySource(7, "original-fence") { source.accepts(ticket) }
        val preparation = PlaylistPlaybackRequest(PlaylistRequestGeneration(), captured.isCurrent)
        var deliveries = 0
        preparation.deliver { deliveries++ }
        assertEquals(1, deliveries)
        assertTrue(captured.isCurrent())
        source.invalidate() // The production route does this before every new read.
        preparation.deliver { deliveries++ }
        assertEquals(1, deliveries)
        assertFalse(captured.isCurrent())
        assertEquals("original-fence", captured.fence)
        assertEquals(7L, captured.revision)
    }
}
