package dev.spofify.mobile.core.personal

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthApiFactory
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.auth.LoginStore
import dev.spofify.mobile.core.auth.RememberedLogin
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.auth.SessionStore
import dev.spofify.mobile.core.auth.SessionSummary
import dev.spofify.mobile.core.auth.StoredSession
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.origin.ServerOrigin
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class PersonalLibraryClientTest {
    @Test fun summariesRemainInServerPageOrderAndPendingCountsStayUnknown() = runBlocking {
        fixture { server, api, _, _ ->
            server.enqueue(json(page("${summary("List10")},${summary("List2", kind = "derived", counts = "null", countState = "pending", evaluation = "pending")}", total = 12, limit = 2, offset = 4)))
            val result = api.playlists(limit = 2, offset = 4)
            assertEquals(listOf("List10", "List2"), result.items.map { it.id })
            assertEquals(12L, result.total)
            assertTrue(result.hasMore)
            val pending = result.items.last()
            assertNull(pending.trackCount)
            assertNull(pending.availableCount)
            assertEquals(PersonalCountState.PENDING, pending.countState)
            assertEquals(PersonalEvaluationState.PENDING, pending.evaluationState)
            assertFalse(pending.capabilities.editEntries)
            assertTrue(pending.capabilities.editDefinition)
            val request = server.request()
            assertEquals("/api/me/playlists", request.requestUrl!!.encodedPath)
            assertEquals("2", request.requestUrl!!.queryParameter("limit"))
            assertEquals("4", request.requestUrl!!.queryParameter("offset"))
            assertNull(request.requestUrl!!.queryParameter("kind"))
        }
    }

    @Test fun playlistDetailAndKindFilterUseExactAccountRoutes() = runBlocking {
        fixture { server, api, _, _ ->
            server.enqueue(json(summary("Rules", kind = "derived", evaluation = "stale", countState = "stale")))
            val detail = api.playlist("Rules")
            assertEquals(PersonalEvaluationState.STALE, detail.evaluationState)
            assertEquals(2L, detail.trackCount)
            assertEquals("/api/me/playlists/Rules", server.request().path)
            server.enqueue(json(page(summary("Rules", kind = "derived", evaluation = "ready"))))
            assertEquals(PersonalPlaylistKind.DERIVED, api.playlists(kind = PersonalPlaylistKind.DERIVED).items.single().kind)
            assertEquals("derived", server.request().requestUrl!!.queryParameter("kind"))
        }
    }

    @Test fun duplicateManualTracksKeepTheirEntryIdsAndStoredPositionGaps() = runBlocking {
        fixture { server, api, _, _ ->
            val entries = """{"id":"entry-a","position":4,"track":$track},{"id":"entry-b","position":9,"track":$track}"""
            server.enqueue(json(page(entries, total = 6, limit = 2, offset = 2)))
            val result = api.manualEntries("Mix", limit = 2, offset = 2)
            assertEquals(listOf("entry-a", "entry-b"), result.items.map { it.id })
            assertEquals(listOf(4L, 9L), result.items.map { it.position })
            assertEquals(listOf("track", "track"), result.items.map { it.track.id })
            assertEquals(6L, result.total)
            val request = server.request()
            assertEquals("/api/me/playlists/Mix/entries", request.requestUrl!!.encodedPath)
            assertEquals("2", request.requestUrl!!.queryParameter("offset"))
            assertNull(request.requestUrl!!.queryParameter("cursor"))
        }
    }

    @Test fun derivedCursorPagesPreserveExactActionIdentitiesAndReadyDoesNotMeanLastPage() = runBlocking {
        fixture { server, api, _, _ ->
            val items = """{"occurrenceId":"path-a","selectionToken":"signed-a","position":0,"track":$track},{"occurrenceId":"path-b","selectionToken":"signed-b","position":1,"track":$track}"""
            server.enqueue(json(derivedPage(items, nextCursor = "\"c1_+/%2F\"")))
            val result = api.derivedEntries("Rules", limit = 2, cursor = "c1_+/%2F")
            assertTrue(result.complete)
            assertEquals(42L, result.revision)
            assertEquals("input-fence", result.fence)
            assertEquals("c1_+/%2F", result.nextCursor)
            assertEquals(listOf("signed-a", "signed-b"), result.items.map { it.selectionToken })
            assertEquals(listOf("path-a", "path-b"), result.items.map { it.occurrenceId })
            val request = server.request()
            assertEquals("c1_+/%2F", request.requestUrl!!.queryParameter("cursor"))
            assertNull(request.requestUrl!!.queryParameter("offset"))
            assertEquals("2", request.requestUrl!!.queryParameter("limit"))
        }
    }

    @Test fun staleAndFailedDerivedRowsAreDisplayOnlyAndKeepStoredPositions() = runBlocking {
        fixture { server, api, _, _ ->
            val rows = """{"occurrenceId":"old-path","selectionToken":null,"position":17,"track":$track}"""
            server.enqueue(json(derivedPage(rows, complete = false, state = "error", error = ",\"evaluationErrorCode\":\"EVALUATION_FAILED\"")))
            val result = api.derivedEntries("Rules", limit = 2)
            assertFalse(result.complete)
            assertEquals(PersonalEvaluationState.ERROR, result.evaluationState)
            assertEquals("EVALUATION_FAILED", result.evaluationErrorCode)
            assertEquals("", result.fence)
            assertNull(result.nextCursor)
            assertNull(result.items.single().selectionToken)
            assertEquals(17L, result.items.single().position)
            assertEquals(4L, result.total)
            assertNull(server.request().requestUrl!!.queryParameter("cursor"))
        }
    }

    @Test fun likeStatusPreservesRepeatedRequestedIdsAndToggleSendsOneAbsolutePut() = runBlocking {
        fixture { server, api, _, identity ->
            server.enqueue(json("""{"items":[{"trackId":"a","liked":true},{"trackId":"b","liked":false},{"trackId":"a","liked":true}]}"""))
            val statuses = api.likeStatus(listOf("a", "b", "a"))
            assertEquals(listOf("a", "b", "a"), statuses.map { it.trackId })
            val statusRequest = server.request()
            assertEquals("POST", statusRequest.method)
            assertEquals("/api/me/likes/status", statusRequest.path)
            assertEquals(listOf("a", "b", "a"), statusRequest.bodyJson()["trackIds"]!!.jsonArray.map { it.jsonPrimitive.content })
            server.enqueue(json("""{"trackId":"track","liked":false}"""))
            assertFalse(api.toggleLike("track", currentlyLiked = true).liked)
            val toggle = server.request()
            assertEquals("PUT", toggle.method)
            assertEquals("/api/me/likes", toggle.path)
            assertFalse(toggle.bodyJson()["liked"]!!.jsonPrimitive.boolean)
            assertEquals("fixture-csrf", toggle.getHeader("X-CSRF-Token"))
            assertEquals(identity.origin, toggle.getHeader("Origin"))
            assertEquals("spofify_session=fixture-token", toggle.getHeader("Cookie"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun likesUseListenerSafeProjectionEvenForAdminAndNeverInferAvailabilityFromAPath() = runBlocking {
        fixture(role = "admin") { server, api, _, _ ->
            val withPrivateExtra = track.dropLast(1) + ",\"libraryPath\":\"private-do-not-publish\"}"
            server.enqueue(json(likesPage(withPrivateExtra)))
            val result = api.likes()
            assertTrue(result.items.single().track.hasAudio)
            assertEquals("2026-01-01T00:00:00.123456789Z", result.items.single().likedAt)
            assertFalse(result.toString().contains("private-do-not-publish"))
            server.request()
            val missingAvailability = withPrivateExtra.replace("\"hasAudio\":true,", "")
            server.enqueue(json(likesPage(missingAvailability)))
            assertFailure(200, PersonalLibraryErrorCode.INVALID_RESPONSE) { api.likes() }
            server.request()
        }
    }

    @Test fun metadataAndEntryMutationsCarryExpectedRevisionAndNeverUseTrackIdAsEntryId() = runBlocking {
        fixture { server, api, _, _ ->
            server.enqueue(json(summary("New"), status = 201))
            assertEquals("New", api.createManualPlaylist("  New  ", "Description\nline").id)
            val create = server.request()
            assertEquals("POST", create.method)
            assertEquals("/api/me/playlists", create.path)
            val createBody = create.bodyJson()
            assertEquals(setOf("name", "description"), createBody.keys)
            assertEquals("New", createBody["name"]!!.jsonPrimitive.content)

            server.enqueue(json(summary("New", revision = 2)))
            assertEquals(2L, api.renamePlaylist("New", "Name", "Unchanged description", 1).revision)
            val rename = server.request()
            assertEquals("PATCH", rename.method)
            assertEquals("/api/me/playlists/New", rename.path)
            assertEquals(1L, rename.bodyJson()["expectedRevision"]!!.jsonPrimitive.long)

            server.enqueue(json(summary("New", revision = 3)))
            api.addTrack("New", "track", 2)
            val add = server.request()
            assertEquals("POST", add.method)
            assertEquals("/api/me/playlists/New/entries", add.path)
            assertEquals("track", add.bodyJson()["trackId"]!!.jsonPrimitive.content)

            server.enqueue(json(summary("New", revision = 4)))
            api.removeEntry("New", "exact-entry", 3)
            val remove = server.request()
            assertEquals("DELETE", remove.method)
            assertEquals("/api/me/playlists/New/entries/exact-entry", remove.path)
            assertEquals(setOf("expectedRevision"), remove.bodyJson().keys)

            server.enqueue(json(summary("New", revision = 5)))
            api.reorderEntries("New", listOf("entry-b", "entry-a"), 4)
            val reorder = server.request()
            assertEquals("PUT", reorder.method)
            assertEquals("/api/me/playlists/New/order", reorder.path)
            assertEquals(listOf("entry-b", "entry-a"), reorder.bodyJson()["entryIds"]!!.jsonArray.map { it.jsonPrimitive.content })

            server.enqueue(MockResponse().setResponseCode(204))
            api.deletePlaylist("New", 5)
            val delete = server.request()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/me/playlists/New", delete.path)
            assertEquals(5L, delete.bodyJson()["expectedRevision"]!!.jsonPrimitive.long)
        }
    }

    @Test fun recentRequestsOnlyLimit100WhileHistoryUsesOrdinaryPagesAndRetainsUnavailableFacts() = runBlocking {
        fixture { server, api, _, _ ->
            val unavailable = track.replace("\"hasAudio\":true", "\"hasAudio\":false")
                .replace("\"inLibrary\":1", "\"inLibrary\":0")
            val activity = """{"id":"event","playedAt":"2026-01-01T00:00:00Z","track":$unavailable,"origin":"offline","receivedAt":"2026-01-02T00:00:00Z","metadataMissing":true}"""
            server.enqueue(json(page(activity, total = 800, limit = 100)))
            val recent = api.recent()
            assertEquals(800L, recent.total)
            assertEquals(100, recent.limit)
            assertFalse(recent.items.single().track.hasAudio)
            assertTrue(recent.items.single().metadataMissing)
            assertEquals("offline", recent.items.single().origin)
            val request = server.request()
            assertEquals("/api/me/recent?limit=100", request.path)
            assertNull(request.requestUrl!!.queryParameter("offset"))

            server.enqueue(json(page(activity, total = 800, limit = 3, offset = 6)))
            val history = api.history(limit = 3, offset = 6)
            assertEquals(6, history.offset)
            assertTrue(history.hasMore)
            assertEquals("2026-01-02T00:00:00Z", history.items.single().receivedAt)
            val historyRequest = server.request()
            assertEquals("/api/me/history", historyRequest.requestUrl!!.encodedPath)
            assertEquals("6", historyRequest.requestUrl!!.queryParameter("offset"))
        }
    }

    @Test fun opaqueIdsAreEncodedOnceNotInterpretedAsPathsOrQueryParameters() = runBlocking {
        fixture { server, api, _, _ ->
            val id = "日本 +%2F &"
            server.enqueue(json(summary(id)))
            assertEquals(id, api.playlist(id).id)
            val request = server.request()
            assertEquals(id, request.requestUrl!!.pathSegments.last())
            assertNull(request.requestUrl!!.query)
            assertTrue(request.requestUrl!!.encodedPath.contains("%252F"))
        }
    }

    @Test fun failuresAreSafeTypedAndUncertainWritesAreNeverReplayed() = runBlocking {
        fixture { server, api, _, _ ->
            server.enqueue(json("""{"error":{"code":"EVALUATION_TIMEOUT","message":"/private/raw-secret"}}""", status = 503))
            val failure = runCatching { api.addTrack("List", "track", 1) }.exceptionOrNull()
            assertTrue(failure is PersonalLibraryException)
            assertEquals(503, (failure as PersonalLibraryException).status)
            assertFalse(failure.toString().contains("raw-secret"))
            assertNull(failure.cause)
            server.request()
            assertEquals(2, server.requestCount)

            server.enqueue(json("""{"error":"private-conflict"}""", status = 409))
            assertFailure(409, PersonalLibraryErrorCode.HTTP_ERROR) { api.deletePlaylist("List", 1) }
            server.request()
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun malformedMissingAndContradictoryFieldsAreRejectedInsteadOfInvented() = runBlocking {
        fixture { server, api, _, _ ->
            server.enqueue(json(summary("Pending", kind = "derived", countState = "pending", evaluation = "pending", counts = "0")))
            assertFailure(200, PersonalLibraryErrorCode.INVALID_RESPONSE) { api.playlist("Pending") }
            server.request()

            server.enqueue(json("""{"items":[],"total":3,"limit":40,"offset":0}"""))
            assertFailure(200, PersonalLibraryErrorCode.INVALID_RESPONSE) { api.playlists() }
            server.request()

            server.enqueue(json("""{"items":[{"trackId":"b","liked":true},{"trackId":"a","liked":false}]}"""))
            assertFailure(200, PersonalLibraryErrorCode.INVALID_RESPONSE) { api.likeStatus(listOf("a", "b")) }
            server.request()

            server.enqueue(json(derivedPage("""{"occurrenceId":"path","selectionToken":null,"position":0,"track":$track}""")))
            assertFailure(200, PersonalLibraryErrorCode.INVALID_RESPONSE) { api.derivedEntries("Rules", limit = 2) }
            server.request()
        }
    }

    @Test fun invalidInputsAndRetiredIdentityNeverDispatch() = runBlocking {
        fixture { server, api, sessions, identity ->
            assertFailure(400, PersonalLibraryErrorCode.INVALID_REQUEST) { api.recent(101) }
            assertFailure(400, PersonalLibraryErrorCode.INVALID_REQUEST) { api.likeStatus(List(101) { "track" }) }
            assertFailure(400, PersonalLibraryErrorCode.INVALID_REQUEST) { api.derivedEntries("Rules", cursor = "") }
            assertFailure(400, PersonalLibraryErrorCode.INVALID_REQUEST) { api.addTrack("List", "track", 0) }
            assertFailure(400, PersonalLibraryErrorCode.INVALID_REQUEST) { api.createManualPlaylist("\uD800") }
            assertFailure(400, PersonalLibraryErrorCode.INVALID_REQUEST) { api.reorderEntries("List", listOf("a", "a"), 1) }
            val stale = PersonalLibraryClient(sessions, identity.copy(incarnation = identity.incarnation + 1))
            assertTrue(runCatching { stale.likes() }.exceptionOrNull() is CancellationException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unauthorizedReadRetiresTheAuthenticatedSession() = runBlocking {
        fixture { server, api, sessions, _ ->
            server.enqueue(json("{}", status = 401))
            assertTrue(runCatching { api.likes() }.isFailure)
            assertTrue(sessions.state.value is AuthState.SignedOut)
            server.request()
        }
    }

    private suspend fun assertFailure(status: Int, code: PersonalLibraryErrorCode, action: suspend () -> Any?) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected a safe typed failure", failure is PersonalLibraryException)
        failure as PersonalLibraryException
        assertEquals(status, failure.status)
        assertEquals(code, failure.code)
        assertNull(failure.cause)
    }

    private suspend fun fixture(
        role: String = "user",
        block: suspend (MockWebServer, PersonalLibraryClient, SessionRepository, AccountIdentity) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.start()
            val origin = ServerOrigin.parse(server.url("/").toString())
            val record = StoredSession(origin.value,
                "spofify_session=fixture-token; Path=/; HttpOnly; Expires=Tue, 01 Jan 2030 00:00:00 GMT",
                SessionSummary("account", "fixture-user", role, "fixture-csrf"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = OkHttpAuthApi.defaultClient()
            val sessions = SessionRepository(object : SessionStore {
                var value: StoredSession? = record
                override fun read() = value
                override fun write(value: StoredSession?) { this.value = value }
            }, object : LoginStore {
                override fun read(): RememberedLogin? = null
                override fun write(value: RememberedLogin) = Unit
            }, AuthApiFactory { OkHttpAuthApi(it, client) }, scope)
            server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"$role","csrfToken":"fixture-csrf"}"""))
            try {
                sessions.start()
                val identity = (withTimeout(3000) { sessions.state.first { it is AuthState.Authenticated } } as AuthState.Authenticated).identity
                server.request()
                block(server, PersonalLibraryClient(sessions, identity), sessions, identity)
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun MockWebServer.request(): RecordedRequest = takeRequest(2, TimeUnit.SECONDS) ?: error("Request not received")
    private fun RecordedRequest.bodyJson() = Json.parseToJsonElement(body.clone().readUtf8()).jsonObject
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun page(items: String, total: Int = 1, limit: Int = 40, offset: Int = 0) =
        """{"items":[$items],"total":$total,"limit":$limit,"offset":$offset}"""
    private fun summary(id: String, revision: Int = 1, kind: String = "manual", counts: String = "2", countState: String = "exact", evaluation: String? = null): String {
        val derived = kind == "derived"
        return """{"id":${JsonPrimitive(id)},"name":"Name","description":"Description","kind":"$kind","countState":"$countState","trackCount":$counts,"availableCount":$counts,"capabilities":{"editMetadata":true,"editEntries":${!derived},"editDefinition":$derived,"play":true,"copyToManual":$derived,"delete":true,"addTracks":${!derived}},"revision":$revision,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"${if (evaluation == null) "" else ",\"evaluationState\":\"$evaluation\""}}"""
    }
    private fun derivedPage(items: String, complete: Boolean = true, state: String = "ready", nextCursor: String = "null", error: String = "") =
        """{"items":[$items],"total":4,"availableTotal":4,"limit":2,"nextCursor":$nextCursor,"revision":42,"fence":"${if (complete) "input-fence" else ""}","complete":$complete,"evaluationState":"$state"$error}"""
    private fun likesPage(track: String) =
        """{"items":[{"track":$track,"likedAt":"2026-01-01T00:00:00.123456789Z"}],"total":1,"availableTotal":1,"limit":40,"offset":0}"""
    private val track = """{"kind":"track","id":"track","name":"Song","hasAudio":true,"coverage":{"tracks":1,"inLibrary":1,"albums":0},"artists":[{"id":"artist","name":"Artist"}],"durationMs":120000}"""
}
