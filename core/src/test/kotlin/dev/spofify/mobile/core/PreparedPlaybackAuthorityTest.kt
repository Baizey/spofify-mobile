package dev.spofify.mobile.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class PreparedPlaybackAuthorityTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient

    @Before fun setup() {
        web = MockWebServer().also { it.start() }
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
    }

    @After fun teardown() { web.shutdown() }

    @Test fun ordinaryQueueDefaultsHaveNoPreparedStateOrSelectionAuthority() {
        val queue = PlaybackQueue(emptyList())
        assertNull(queue.preparedState)
        assertNull(queue.selectionAuthority)
        val decoded = ApiJson.decodeFromString(PlaybackQueue.serializer(), """{"items":[]}""")
        assertNull(decoded.preparedState)
        assertNull(decoded.selectionAuthority)
    }

    @Test fun originalRequestTokenIsBoundSeparatelyFromReturnedOccurrenceTokens() = runBlocking {
        authenticate(client)
        val queue = preparedQueue()
        val state = requireNotNull(queue.preparedState)
        val authority = requireNotNull(queue.selectionAuthority)
        assertTrue(authority.isValidFor(client, state))
        assertEquals("original-signed-click", authority.selectionTokenFor(client, state))
        assertEquals("occ-2", state.queue[state.position].occurrenceId)
        assertEquals(2, web.requestCount) // Authentication plus one preparation, no refetch.
        assertEquals("PreparedPlaybackAuthority([redacted])", authority.toString())
        listOf("original-signed-click", "returned-token", "csrf-secret", "cookie-secret").forEach {
            assertFalse(authority.toString().contains(it))
            assertFalse(queue.toString().contains(it))
            assertFalse(state.toJson().toString().contains(it))
        }
    }

    @Test fun wholeCollectionAuthorityIsValidWithoutAnOriginalSelectionToken() = runBlocking {
        authenticate(client)
        val queue = preparedQueue(token = null)
        val state = requireNotNull(queue.preparedState)
        val authority = requireNotNull(queue.selectionAuthority)
        assertTrue(authority.isValidFor(client, state))
        assertNull(authority.selectionTokenFor(client, state))
        assertEquals(0, state.position)
        assertFalse(authority.isValidFor(client, state.copy(position = 1)))
        assertNull(authority.selectionTokenFor(client, state.copy(position = 1)))
        client.notifyPersonalChange(PersonalCollection.LIKES)
        assertTrue(authority.isValidFor(client, state)) // Personal revisions are not auth generations.
    }

    @Test fun authorityNeverSerializesWithTheQueueOrItsPositiveSnapshot() = runBlocking {
        authenticate(client)
        val queue = preparedQueue()
        val encoded = ApiJson.encodeToString(PlaybackQueue.serializer(), queue)
        listOf("selectionAuthority", "selectionToken", "original-signed-click", "returned-token", "csrf-secret", "cookie-secret").forEach {
            assertFalse("Selection authority escaped into serialized queue", encoded.contains(it))
        }
        val decoded = ApiJson.decodeFromString(PlaybackQueue.serializer(), encoded)
        assertEquals(queue.preparedState, decoded.preparedState)
        assertNull(decoded.selectionAuthority)
    }

    @Test fun equalSessionValuesOnAnotherClientDoNotGrantAuthority() = runBlocking {
        authenticate(client)
        val queue = preparedQueue()
        val state = requireNotNull(queue.preparedState)
        val authority = requireNotNull(queue.selectionAuthority)
        val other = SpofifyClient(client.server, MemorySessionStore())
        authenticate(other)
        assertEquals(client.server.origin, other.server.origin)
        assertEquals(client.currentSession(), other.currentSession())
        assertNotSame(client.currentSession(), other.currentSession())
        assertFalse(authority.isValidFor(other, state))
        assertNull(authority.selectionTokenFor(other, state))
    }

    @Test fun logoutAndEqualValuedReloginNeverReviveAuthority() = runBlocking {
        authenticate(client)
        val originalSession = client.currentSession()
        val originalGeneration = client.personalMutationGeneration()
        val queue = preparedQueue()
        val state = requireNotNull(queue.preparedState)
        val authority = requireNotNull(queue.selectionAuthority)
        client.clearSession()
        assertFalse(authority.isValidFor(client, state))
        assertNull(authority.selectionTokenFor(client, state))
        authenticate(client)
        assertEquals(originalSession, client.currentSession())
        assertNotSame(originalSession, client.currentSession())
        assertNotEquals(originalGeneration, client.personalMutationGeneration())
        assertFalse(authority.isValidFor(client, state))
        assertNull(authority.selectionTokenFor(client, state))
    }

    @Test fun sourceCapturedBeforeRequestCannotBindAfterASessionChange() = runBlocking {
        authenticate(client)
        val source = PreparedPlaybackSource.capture(client)
        client.clearSession()
        authenticate(client)
        val queue = preparedQueue()
        assertNotNull(queue.selectionAuthority) // The new request belongs to the new session.
        assertNull(source.bind("original-signed-click", requireNotNull(queue.preparedState)))
        assertEquals("PreparedPlaybackSource([redacted])", source.toString())
    }

    @Test fun unauthenticatedSourceCannotBorrowALaterSession() = runBlocking {
        val source = PreparedPlaybackSource.capture(client)
        authenticate(client)
        val queue = preparedQueue()
        assertNotNull(queue.selectionAuthority)
        assertNull(source.bind("original-signed-click", requireNotNull(queue.preparedState)))
    }

    @Test fun responseArrivingAfterSessionClearCannotMintAuthority() = runBlocking {
        authenticate(client)
        val releaseResponse = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(releaseResponse.await(5, TimeUnit.SECONDS))
                return jsonResponse(playbackBody("occ-2"))
            }
        }
        val pending = async(Dispatchers.Default) {
            DerivedPlaylistsApi(client).playback("rules", 5, "f1_fence", "original-signed-click")
        }
        try {
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            client.clearSession()
        } finally {
            releaseResponse.countDown()
        }
        val parsed = pending.await()
        val queue = parsed.toPlaybackQueue()
        assertNotNull(queue.preparedState)
        assertNull(queue.selectionAuthority)
        assertEquals(2, web.requestCount)
    }

    @Test fun unauthenticatedPlaybackCannotMintAuthority() = runBlocking {
        assertNull(preparedQueue().selectionAuthority)
    }

    @Test fun changingSelectedOccurrenceOrContextCannotReuseOriginalAuthority() = runBlocking {
        authenticate(client)
        val queue = preparedQueue()
        val state = requireNotNull(queue.preparedState)
        val authority = requireNotNull(queue.selectionAuthority)
        val context = requireNotNull(state.context)
        val selected = state.queue[state.position]
        val changedTracks = listOf(
            selected.copy(id = "other-track"),
            selected.copy(targetId = "other-derived"),
            selected.copy(occurrenceId = "other-occurrence"),
            selected.copy(playbackContext = null),
            selected.copy(playbackContext = context.copy(fence = "f2_other")),
            selected.copy(playbackContext = context.copy(revision = 6)),
            selected.copy(playbackContext = context.copy(id = "other-derived")),
        )
        val invalid = listOf(
            state.copy(position = 0), // Same track ID, different duplicate occurrence.
            state.copy(position = -1),
            state.copy(position = state.queue.size),
            state.copy(context = null),
            state.copy(context = context.copy(kind = "playlist")),
            state.copy(context = context.copy(id = "other-derived")),
            state.copy(context = context.copy(revision = 6)),
            state.copy(context = context.copy(fence = "f2_other")),
        ) + changedTracks.map { changed ->
            state.copy(queue = state.queue.mapIndexed { index, track -> if (index == state.position) changed else track })
        }
        invalid.forEach {
            assertFalse("Changed selected identity retained authority", authority.isValidFor(client, it))
            assertNull(authority.selectionTokenFor(client, it))
        }
        assertTrue(authority.isValidFor(client, state))
    }

    /** Exercise actual API capture/return glue and the public callback conversion. */
    private suspend fun preparedQueue(token: String? = "original-signed-click"): PlaybackQueue {
        web.enqueue(jsonResponse(playbackBody(if (token == null) "occ-1" else "occ-2")))
        val parsed = DerivedPlaylistsApi(client).playback("rules", 5, "f1_fence", token)
        web.takeRequest()
        val queue = parsed.toPlaybackQueue()
        assertEquals(queue, parsed.queue)
        listOf("original-signed-click", "returned-token", "selectionTokens", "csrf-secret", "cookie-secret").forEach {
            assertFalse("Derived playback logged selection authority", parsed.toString().contains(it))
        }
        return queue
    }

    private fun playbackBody(start: String) = """{
        "context":{"kind":"derived","id":"rules","name":"Rules","revision":5,"fence":"f1_fence"},
        "items":[
            {"kind":"track","id":"same","name":"Song","hasAudio":true,"occurrenceId":"occ-1","selectionToken":"returned-token-1"},
            {"kind":"track","id":"same","name":"Song","hasAudio":true,"occurrenceId":"occ-2","selectionToken":"returned-token-2"}
        ],
        "total":2,"limit":1000,"truncated":false,"revision":5,"fence":"f1_fence",
        "startOccurrenceId":"$start","notice":null
    }"""

    private suspend fun authenticate(candidate: SpofifyClient) {
        web.enqueue(loginResponse())
        candidate.login("listener", "password")
        web.takeRequest()
    }
}
