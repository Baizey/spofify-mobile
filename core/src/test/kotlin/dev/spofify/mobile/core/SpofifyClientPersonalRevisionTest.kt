package dev.spofify.mobile.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpofifyClientPersonalRevisionTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient

    @Before fun setup() {
        web = MockWebServer()
        web.start()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
    }
    @After fun teardown() { web.shutdown() }

    @Test fun confirmedMutationsIncrementOnlyTheirOwnCounterOnThisClient() = runBlocking {
        val other = SpofifyClient(client.server, MemorySessionStore())
        assertEquals(PersonalRevision(), client.personalRevision.value)
        web.enqueue(loginResponse())
        client.login("listener", "password")
        assertEquals(PersonalRevision(), client.personalRevision.value)
        web.enqueue(MockResponse().setResponseCode(204))
        client.setLiked("track", true)
        assertEquals(PersonalRevision(likes = 1), client.personalRevision.value)
        client.notifyPersonalChange(PersonalCollection.HISTORY)
        assertEquals(PersonalRevision(likes = 1, history = 1), client.personalRevision.value)
        client.notifyPersonalChange(PersonalCollection.PLAYLISTS)
        assertEquals(PersonalRevision(likes = 1, history = 1, playlists = 1), client.personalRevision.value)
        assertEquals(PersonalRevision(), other.personalRevision.value)
    }

    @Test fun readsAndFailedWritesDoNotAdvanceFreshness() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.enqueue(jsonResponse("""{"items":[],"total":0}"""))
        client.likes()
        web.enqueue(jsonResponse("{}", 503))
        assertEquals(503, apiFailure { client.setLiked("track", true) }.status)
        web.enqueue(jsonResponse("{}", 429))
        assertEquals(429, apiFailure { client.setLiked("track", false) }.status)
        assertEquals(PersonalRevision(), client.personalRevision.value)
        assertNotNull(client.currentSession())
    }

    @Test fun cancelledMutationDoesNotAdvanceFreshness() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) { client.setLiked("track", true) }
        assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertEquals(PersonalRevision(), client.personalRevision.value)
    }

    @Test fun oldSuccessfulWritesCannotNotifyAfterTheAuthGenerationChanges() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/api/me/likes") {
                release.await(5, TimeUnit.SECONDS)
                MockResponse().setResponseCode(204)
            } else loginResponse()
        }
        val pending = async(Dispatchers.Default) { client.setLiked("track", true) }
        try {
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            client.login("different-account", "password")
        } finally { release.countDown() }
        pending.await()
        assertNotNull(client.currentSession())
        assertEquals(PersonalRevision(), client.personalRevision.value)
    }

    @Test fun explicitNotificationsRequireAStillValidatedSession() = runBlocking {
        client.notifyPersonalChange(PersonalCollection.LIKES)
        assertEquals(PersonalRevision(), client.personalRevision.value)
        web.enqueue(loginResponse())
        client.login("listener", "password")
        client.notifyPersonalChange(PersonalCollection.HISTORY)
        assertEquals(PersonalRevision(history = 1), client.personalRevision.value)
        client.clearSession()
        client.notifyPersonalChange(PersonalCollection.PLAYLISTS)
        assertEquals(PersonalRevision(history = 1), client.personalRevision.value)
    }
}
