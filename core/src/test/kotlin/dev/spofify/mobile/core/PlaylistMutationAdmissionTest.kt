package dev.spofify.mobile.core

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Publication tokens must belong to the send, not to a later acknowledgement/DTO decoder. */
class PlaylistMutationAdmissionTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private lateinit var personal: PersonalCollectionsApi
    private lateinit var derived: DerivedPlaylistsApi
    private val manual = """{"id":"p","name":"Mix","trackCount":0,"availableCount":0,"revision":2}"""
    private val rules = """{"id":"rules","name":"Rules","kind":"derived","countState":"unevaluated","trackCount":null,"availableCount":null,"revision":2}"""
    private val definition = ApiJson.parseToJsonElement("""{"version":1,"sources":[{"slotId":"s_0123456789abcdef0123456789abcdef","kind":"likes"}],"duplicates":"keep","sort":"sourceOrder"}""") as JsonObject

    @Before fun setup() {
        web = MockWebServer().also { it.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
        personal = PersonalCollectionsApi(client)
        derived = DerivedPlaylistsApi(client)
    }

    @After fun teardown() {
        client.httpClient.dispatcher.cancelAll()
        client.httpClient.connectionPool.evictAll()
        client.httpClient.dispatcher.executorService.shutdown()
        web.shutdown()
    }

    private class Mutation(val name: String, val reply: String?, val send: suspend () -> Unit)

    @Test fun everyMutationWrapperCapturesBeforeSendAndCannotBorrowEqualValuedSuccessorAuth() = runBlocking {
        val snapshot = ManualPlaylistSnapshot(
            PersonalPlaylistSummary("p", "Mix", trackCount = 0, availableCount = 0, revision = 1), emptyList(),
        )
        val writes = listOf(
            Mutation("manual create", manual) { personal.createPlaylist("Mix"); Unit },
            Mutation("manual update", manual) { personal.updatePlaylist("p", "Mix", "", 1); Unit },
            Mutation("manual delete", null) { personal.deletePlaylist("p", 1) },
            Mutation("manual add", manual) { personal.addEntry("p", "track", 1); Unit },
            Mutation("manual remove", manual) { personal.removeEntry("p", "entry", 1); Unit },
            Mutation("manual reorder", manual) { personal.reorder(snapshot, emptyList()); Unit },
            Mutation("derived update", rules) { derived.updateDefinition("rules", definition, 1); Unit },
            Mutation("derived create", rules) { derived.create("Rules", definition = definition); Unit },
            Mutation("derived copy", manual) { derived.copy("rules", "Copy", expectedRevision = 1, fence = "f1_fence"); Unit },
        )
        web.enqueue(loginResponse())
        client.login("listener", "synthetic-password")
        takeRequest()
        for (write in writes) {
            val before = client.personalRevision.value
            val oldSession = requireNotNull(client.currentSession())
            val release = CountDownLatch(1)
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/auth/login") return loginResponse()
                    check(release.await(5, TimeUnit.SECONDS))
                    return write.reply?.let { jsonResponse(it) } ?: MockResponse().setResponseCode(204)
                }
            }
            val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { write.send() }
            try {
                assertNotEquals("/api/auth/login", takeRequest().path)
                val successor = client.login("listener", "synthetic-password")
                takeRequest()
                assertEquals(oldSession, successor)
                assertNotSame(oldSession, successor)
            } finally { release.countDown() }
            withTimeout(5000) { pending.await() }
            assertEquals("Stale ${write.name} published a personal revision", before, client.personalRevision.value)
            assertFalse(client.hasValidSession(oldSession))
        }
        assertEquals(1 + writes.size * 2, web.requestCount) // No replay or hidden verification calls.
    }

    @Test fun decodedAcknowledgementCannotCaptureNewAuthorityAfterSendReturnsAcrossOfflineAba() = runBlocking {
        web.enqueue(loginResponse())
        val live = client.login("listener", "synthetic-password")
        takeRequest()
        val identity = client.validatedSessionIdentity()
        val before = client.personalRevision.value
        val writeGeneration = client.personalMutationGeneration()
        web.enqueue(jsonResponse(manual))
        val acknowledgement = ApiTransport(client).send("POST", personalPlaylistsPath, JsonObject(emptyMap()))
        takeRequest()
        client.setOffline(true)
        client.setOffline(false)
        assertEquals("p", confirmedPlaylistMutation(client, acknowledgement, writeGeneration).id)
        assertEquals(before, client.personalRevision.value)
        assertSame(live, client.currentSession())
        assertEquals(identity, client.validatedSessionIdentity())
        // A new explicit send's token still permits an ordinary confirmed publication.
        val freshGeneration = client.personalMutationGeneration()
        web.enqueue(jsonResponse(manual))
        val fresh = ApiTransport(client).send("POST", personalPlaylistsPath, JsonObject(emptyMap()))
        takeRequest()
        confirmedPlaylistMutation(client, fresh, freshGeneration)
        assertEquals(before.playlists + 1, client.personalRevision.value.playlists)
        assertEquals(3, web.requestCount)
    }

    private fun takeRequest(): RecordedRequest = requireNotNull(web.takeRequest(5, TimeUnit.SECONDS)) {
        "Synthetic mutation request did not arrive; received=${web.requestCount}, " +
            "queued=${client.httpClient.dispatcher.queuedCallsCount()}, running=${client.httpClient.dispatcher.runningCallsCount()}"
    }
}
