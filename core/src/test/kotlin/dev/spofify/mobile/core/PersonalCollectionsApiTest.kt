package dev.spofify.mobile.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer

class PersonalCollectionsApiTest {
    private lateinit var web: MockWebServer
    private lateinit var api: PersonalCollectionsApi
    private lateinit var client: SpofifyClient

    @Before fun setup() {
        web = MockWebServer().also { it.start() }
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
        api = PersonalCollectionsApi(client)
    }
    @After fun teardown() { web.shutdown() }

    @Test fun discoveryIncludesBothKindsAndNeverEvaluatesRecipes() = runBlocking {
        web.enqueue(jsonResponse("""{"items":[${summary()},${summary("rules", derived = true)}],"total":2,"limit":40,"offset":0}"""))
        val page = api.list()
        assertEquals(PlaylistKind.MANUAL, page.items[0].kind)
        assertEquals(2L, page.items[0].trackCount)
        assertEquals(PlaylistKind.DERIVED, page.items[1].kind)
        assertEquals(PlaylistCountState.UNEVALUATED, page.items[1].countState)
        assertNull(page.items[1].trackCount)
        assertNull(page.items[1].availableCount)
        assertFalse(page.items[1].capabilities.addTracks)
        assertTrue(page.items[1].capabilities.editDefinition)
        assertEquals("/api/me/playlists?limit=40&offset=0", web.takeRequest().path)
        web.enqueue(jsonResponse("""{"items":[],"total":0,"limit":100,"offset":40}"""))
        api.playlists(PlaylistKind.MANUAL, 100, 40)
        val request = web.takeRequest()
        assertEquals("manual", request.requestUrl!!.queryParameter("kind"))
        assertEquals(2, web.requestCount)
    }

    @Test fun metadataAndOccurrenceMutationsUseExactMethodsBodiesAndEscapedSegments() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        val initial = client.personalRevision.value
        web.enqueue(jsonResponse(summary(), 201))
        api.create("Mix")
        val create = web.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/me/playlists", create.path)
        assertEquals("csrf-secret", create.getHeader("X-CSRF-Token"))
        assertEquals(setOf("name"), body(create.body.readUtf8()).keys)
        web.enqueue(jsonResponse(summary(revision = 2)))
        api.update("p/a%", "Changed", "Notes", 1)
        val update = web.takeRequest()
        assertEquals("PATCH", update.method)
        assertEquals("/api/me/playlists/p%2Fa%25", update.path)
        assertEquals("1", body(update.body.readUtf8())["expectedRevision"]!!.jsonPrimitive.content)
        web.enqueue(jsonResponse(summary(revision = 3)))
        api.add("p/a%", "track/opaque%", 2)
        val add = web.takeRequest()
        assertEquals("POST", add.method)
        assertEquals("/api/me/playlists/p%2Fa%25/entries", add.path)
        assertEquals("track/opaque%", body(add.body.readUtf8())["trackId"]!!.jsonPrimitive.content)
        web.enqueue(jsonResponse(summary(revision = 4)))
        api.remove("p/a%", "second/copy%", 3)
        val remove = web.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals("/api/me/playlists/p%2Fa%25/entries/second%2Fcopy%25", remove.path)
        assertEquals(setOf("expectedRevision"), body(remove.body.readUtf8()).keys)
        web.enqueue(jsonResponse("", 204))
        api.delete("p/a%", 4)
        assertEquals("DELETE", web.takeRequest().method)
        assertEquals(initial.playlists + 5, client.personalRevision.value.playlists)
        assertEquals(initial.likes, client.personalRevision.value.likes)
        assertEquals(initial.history, client.personalRevision.value.history)
    }

    @Test fun completeOrderPagesAllOccurrencesAndReordersDuplicatesNotTrackIds() = runBlocking {
        authenticate()
        val initial = client.personalRevision.value
        web.enqueue(jsonResponse(summary(count = 101)))
        web.enqueue(jsonResponse(entriesPage(0, 100, 101)))
        web.enqueue(jsonResponse(entriesPage(100, 1, 101)))
        web.enqueue(jsonResponse(summary(count = 101)))
        val snapshot = api.loadCompleteOrder("p")
        assertEquals(initial, client.personalRevision.value)
        assertEquals(101, snapshot.entries.size)
        assertEquals(1, snapshot.entries.map { it.track.id }.toSet().size)
        assertEquals("e100", snapshot.entries.last().track.entryId)
        assertEquals("/api/me/playlists/p", web.takeRequest().path)
        assertEquals("/api/me/playlists/p/entries?limit=100&offset=0", web.takeRequest().path)
        assertEquals("/api/me/playlists/p/entries?limit=100&offset=100", web.takeRequest().path)
        assertEquals("/api/me/playlists/p", web.takeRequest().path)
        web.enqueue(jsonResponse(summary(count = 101, revision = 2)))
        api.reorder(snapshot, snapshot.entryIds.reversed())
        val order = web.takeRequest()
        assertEquals("PUT", order.method)
        assertEquals("/api/me/playlists/p/order", order.path)
        val payload = body(order.body.readUtf8())
        assertEquals("e100", payload["entryIds"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("1", payload["expectedRevision"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.reorder(snapshot, snapshot.entryIds.dropLast(1)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.reorder(snapshot, List(101) { "e0" }) } }
        assertEquals(6, web.requestCount)
        assertEquals(initial.playlists + 1, client.personalRevision.value.playlists)
    }

    @Test fun completeOrderRejectsRevisionChangeEvenWhenCountAndTrackIdsStayEqual() = runBlocking {
        web.enqueue(jsonResponse(summary()))
        web.enqueue(jsonResponse(entriesPage(0, 2, 2)))
        web.enqueue(jsonResponse(summary(revision = 2)))
        val error = apiFailure { api.loadCompleteOrder("p") }
        assertEquals(409, error.status)
        assertEquals("REVISION_CONFLICT", error.code)
        assertEquals(3, web.requestCount)
    }

    @Test fun completeOrderRejectsIncompleteOrUnboundedOrders() = runBlocking {
        web.enqueue(jsonResponse(summary(count = 1001)))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.loadCompleteOrder("p") } }
        web.takeRequest()
        web.enqueue(jsonResponse(summary()))
        web.enqueue(jsonResponse(entriesPage(0, 0, 2)))
        assertEquals(409, apiFailure { api.loadCompleteOrder("p") }.status)
        assertEquals(3, web.requestCount)
    }

    @Test fun manualPlaybackKeepsFlatDuplicateEntryIdentityAndContext() = runBlocking {
        web.enqueue(jsonResponse("""{"context":{"kind":"playlist","id":"p","name":"Mix"},"items":[${flatTrack("e1")},${flatTrack("e2")}],"total":2,"limit":2,"truncated":false}"""))
        val result = api.playback("p")
        assertEquals("/api/me/playlists/p/playback", web.takeRequest().path)
        assertEquals("playlist", result.context.kind)
        assertEquals(listOf("e1", "e2"), result.queue.items.map { it.entryId })
        assertEquals(listOf("same", "same"), result.items.map { it.id })
        assertEquals(2, result.queue.total)
        assertEquals(LibraryPlaybackContext("playlist", "p", "Mix"), result.queue.context)
        assertEquals(0L, result.queue.revision) // Legacy manual playback has no revision authority.
    }

    @Test fun manualQueueConversionRetainsEnvelopeOrContextRevisionWhenProvided() = runBlocking {
        web.enqueue(jsonResponse("""{"context":{"kind":"playlist","id":"p","name":"Mix"},"items":[],"total":0,"limit":0,"truncated":false,"revision":7}"""))
        val result = api.playback("p")
        assertEquals(7L, result.queue.revision)
        assertEquals(LibraryPlaybackContext("playlist", "p", "Mix"), result.queue.context)
        val contextRevision = result.copy(revision = 0, context = result.context.copy(revision = 8))
        assertEquals(8L, contextRevision.queue.revision)
    }

    @Test fun malformedSuccessfulMutationResponseIsNotRetriedOrAcceptedAsSaved() = runBlocking {
        authenticate()
        val initial = client.personalRevision.value
        web.enqueue(jsonResponse("{}", 201))
        val error = apiFailure { api.create("Maybe committed") }
        assertTrue(error.status in 200..299)
        assertEquals(2, web.requestCount)
        assertEquals("POST", web.takeRequest().method)
        assertEquals(initial, client.personalRevision.value)
    }

    @Test fun unexpectedSuccessfulDeleteAcknowledgementDoesNotPublishAChange() = runBlocking {
        authenticate()
        val initial = client.personalRevision.value
        web.enqueue(jsonResponse("{}", 200))
        assertEquals(200, apiFailure { api.delete("p", 1) }.status)
        assertEquals(initial, client.personalRevision.value)
        assertEquals(2, web.requestCount)
    }

    @Test fun conflictAndUncertainWritesAreNeverRetried() = runBlocking {
        authenticate()
        val initial = client.personalRevision.value
        web.enqueue(jsonResponse("""{"error":{"code":"REVISION_CONFLICT","message":"private /server/data"}}""", 409))
        val conflict = apiFailure { api.add("p", "same", 1) }
        assertEquals(409, conflict.status)
        assertEquals("REVISION_CONFLICT", conflict.code)
        assertFalse(conflict.message.contains("private"))
        web.takeRequest()
        web.enqueue(jsonResponse("{}", 503))
        assertEquals(503, apiFailure { api.create("Maybe committed") }.status)
        web.takeRequest()
        assertEquals(3, web.requestCount)
        assertEquals(initial, client.personalRevision.value)
    }

    private suspend fun authenticate() {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
    }

    private fun summary(id: String = "p", count: Int = 2, revision: Long = 1, derived: Boolean = false): String =
        """{"id":"$id","name":"Mix","description":"","kind":"${if (derived) "derived" else "manual"}","countState":"${if (derived) "unevaluated" else "exact"}","trackCount":${if (derived) "null" else count},"availableCount":${if (derived) "null" else count},"revision":$revision,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","capabilities":{"editMetadata":true,"editEntries":${!derived},"editDefinition":$derived,"play":true,"copyToManual":$derived,"delete":true,"addTracks":${!derived}}}"""
    private fun track() = """{"kind":"track","id":"same","name":"Song","hasAudio":true}"""
    private fun flatTrack(entryId: String) = track().dropLast(1) + ""","entryId":"$entryId"}"""
    private fun entriesPage(offset: Int, size: Int, total: Int) =
        """{"items":[${(offset until offset + size).joinToString(",") { """{"id":"e$it","position":$it,"track":${track()}}""" }}],"total":$total,"limit":100,"offset":$offset}"""
    private fun body(text: String) = ApiJson.parseToJsonElement(text) as JsonObject
}
