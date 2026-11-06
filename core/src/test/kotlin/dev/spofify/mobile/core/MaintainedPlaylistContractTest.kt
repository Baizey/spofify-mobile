package dev.spofify.mobile.core

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer

class MaintainedPlaylistContractTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private lateinit var api: DerivedPlaylistsApi

    @Before fun setup() {
        web = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
        api = DerivedPlaylistsApi(client)
    }
    @After fun teardown() {
        client.httpClient.dispatcher.cancelAll()
        client.httpClient.connectionPool.evictAll()
        client.httpClient.dispatcher.executorService.shutdown()
        web.shutdown()
    }

    @Test fun maintainedSummaryCountsAreTruthfulIncludingEmptySnapshotAndMissingSnapshot() = runBlocking {
        for ((state, counts) in listOf("ready" to "2", "ready" to "0", "pending" to "null",
            "pending" to "2", "stale" to "2", "error" to "2", "error" to "null")) {
            web.enqueue(jsonResponse(summary(state, counts)))
            val result = PersonalCollectionsApi(client).playlist("rules")
            assertEquals(counts.toLongOrNull(), result.trackCount)
            assertEquals(counts.toLongOrNull(), result.availableCount)
            assertEquals(state.uppercase(), result.evaluationState?.name)
            assertFalse(result.capabilities.editEntries)
            assertFalse(result.capabilities.addTracks)
            assertTrue(result.capabilities.copyToManual)
            assertEquals("GET", web.takeRequest().method)
        }
        for (invalid in listOf(summary("ready", "null"), summary("stale", "2").replace("\"countState\":\"stale\"", "\"countState\":\"exact\""))) {
            web.enqueue(jsonResponse(invalid))
            assertEquals(200, apiFailure { PersonalCollectionsApi(client).playlist("rules") }.status)
            web.takeRequest()
        }
    }

    @Test fun savedEntriesReadReturnsDisplayOnlyNonreadyRowsWithoutSchedulingOrRetrying() = runBlocking {
        for (state in listOf("pending", "stale", "error")) {
            web.enqueue(jsonResponse(page(state)))
            val result = api.entries("rules", limit = 40)
            assertFalse(result.current)
            assertEquals("Song", result.items.single().track.name)
            assertNull(result.items.single().selectionToken)
            assertEquals("", result.fence)
            assertNull(result.nextCursor)
            assertEquals(7L, result.revision)
            val request = web.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/me/playlists/rules/entries?limit=40", request.path)
            assertEquals(0L, request.bodySize)
        }
        web.enqueue(jsonResponse(page("pending", rows = false)))
        val empty = api.entries("rules")
        assertEquals(0L, empty.total)
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.current)
        assertEquals(4, web.requestCount) // No client evaluation/polling loop.
    }

    @Test fun readyEntriesKeepExactSignedOccurrenceAuthorityAndNonreadyCannotSmuggleIt() = runBlocking {
        web.enqueue(jsonResponse(page("ready")))
        val ready = api.entries("rules")
        assertTrue(ready.current)
        assertEquals("signed-selection", ready.items.single().selectionToken)
        assertEquals("occ", ready.items.single().track.entryId)
        assertEquals("fence", ready.fence)
        web.takeRequest()
        val invalid = listOf(
            page("stale").replace("\"selectionToken\":null", "\"selectionToken\":\"old-token\""),
            page("error").replace("\"fence\":\"\"", "\"fence\":\"old-fence\""),
            page("pending").replace("\"nextCursor\":null", "\"nextCursor\":\"old-cursor\""),
            page("ready").replace("\"complete\":true", "\"complete\":false"),
        )
        for (wire in invalid) {
            web.enqueue(jsonResponse(wire))
            assertEquals(200, apiFailure { api.entries("rules") }.status)
            web.takeRequest()
        }
        assertEquals(5, web.requestCount)
    }

    private fun summary(state: String, counts: String): String {
        val countState = if (state == "ready") "exact" else if (counts == "null") "pending" else "stale"
        return """{"id":"rules","name":"Rules","kind":"derived","revision":7,"countState":"$countState","trackCount":$counts,"availableCount":$counts,"evaluationState":"$state","capabilities":{"editEntries":false,"addTracks":false,"copyToManual":true}}"""
    }

    private fun page(state: String, rows: Boolean = true): String {
        val ready = state == "ready"
        val items = if (rows) """[{"occurrenceId":"occ","selectionToken":${if (ready) "\"signed-selection\"" else "null"},"position":0,"track":{"kind":"track","id":"song","name":"Song","hasAudio":true}}]""" else "[]"
        return """{"items":$items,"total":${if (rows) 1 else 0},"availableTotal":${if (rows) 1 else 0},"limit":40,"nextCursor":null,"revision":7,"fence":"${if (ready) "fence" else ""}","complete":$ready,"evaluationState":"$state","evaluationErrorCode":${if (state == "error") "\"EVALUATION_FAILED\"" else "null"}}"""
    }
}
