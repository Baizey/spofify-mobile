package dev.spofify.mobile.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer

class DerivedPlaylistsApiTest {
    private lateinit var web: MockWebServer
    private lateinit var api: DerivedPlaylistsApi
    private lateinit var client: SpofifyClient
    private val rawDefinition = objectOf("""{"version":1,"sources":[{"slotId":"s_0123456789abcdef0123456789abcdef","kind":"likes"}],"duplicates":"keep","sort":"sourceOrder"}""")

    @Before fun setup() {
        web = MockWebServer().also { it.start() }
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
        api = DerivedPlaylistsApi(client)
    }
    @After fun teardown() { web.shutdown() }

    @Test fun typedRecipeRoundTripsEveryPredicateIncludingBooleanGenreKnown() {
        val predicates = listOf(
            RecipePredicate.Genre("r&b"), RecipePredicate.GenreKnown(false),
            RecipePredicate.Artist("artist/opaque"), RecipePredicate.Album("album/opaque"),
            RecipePredicate.Availability(RecipeAvailability.UNAVAILABLE),
            RecipePredicate.Membership(RecipeMembershipSource.Likes),
            RecipePredicate.Membership(RecipeMembershipSource.Playlist("manual")),
            RecipePredicate.Not(RecipePredicate.GenreKnown(true)),
            RecipePredicate.Any(listOf(RecipePredicate.GenreKnown(false), RecipePredicate.Genre("jazz"))),
        )
        val recipe = Recipe(
            sources = listOf(
                RecipeSource.Playlist("s_0123456789abcdef0123456789abcdef", "manual", RecipeTake(RecipeTakeSide.LAST, Int.MAX_VALUE)),
                RecipeSource.Likes("s_1123456789abcdef0123456789abcdef"),
                RecipeSource.AllSaved("s_2123456789abcdef0123456789abcdef"),
            ),
            duplicates = RecipeDuplicates.UNIQUE,
            sort = RecipeSort.TITLE,
            include = RecipePredicate.All(predicates),
            exclude = RecipePredicate.GenreKnown(false),
            take = RecipeTake(RecipeTakeSide.FIRST, 123456),
        )
        val json = recipe.toJson()
        assertEquals(recipe, Recipe.fromJson(json))
        assertEquals("1", json["version"]!!.jsonPrimitive.content)
        val known = json["include"]!!.jsonObject["args"]!!.jsonArray[1].jsonObject["value"]!!.jsonPrimitive
        assertFalse(known.isString)
        assertEquals("false", known.content)
        assertFalse(json["sources"]!!.jsonArray[1].jsonObject.containsKey("take"))
        assertEquals("s_0123456789abcdef0123456789abcdef", recipe.sources.first().slotId)
        assertTrue(newRecipeSlotId().matches(Regex("s_[0-9a-f]{32}")))
    }

    @Test fun rawDefinitionIsPreservedAndWritesUseExactDefinitionRoutes() = runBlocking {
        authenticate()
        val initial = client.personalRevision.value
        val future = objectOf(rawDefinition.toString().dropLast(1) + ""","futureBuilderField":{"value":false}}""")
        web.enqueue(jsonResponse("""{"definition":$future,"revision":5}"""))
        val loaded = api.definition("rules")
        assertEquals(future, loaded.definition)
        assertEquals(5L, loaded.revision)
        assertEquals("/api/me/playlists/rules/definition", web.takeRequest().path)
        web.enqueue(jsonResponse(summary(derived = true)))
        api.updateDefinition("rules", loaded.definition, loaded.revision)
        val put = web.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/me/playlists/rules/definition", put.path)
        val payload = objectOf(put.body.readUtf8())
        assertEquals(setOf("definition", "expectedRevision"), payload.keys)
        assertEquals(future, payload["definition"])
        assertEquals("5", payload["expectedRevision"]!!.jsonPrimitive.content)
        web.enqueue(jsonResponse(summary(derived = true), 201))
        api.create("Rules", definition = future)
        val create = web.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/me/playlists", create.path)
        val createBody = objectOf(create.body.readUtf8())
        assertEquals("derived", createBody["kind"]!!.jsonPrimitive.content)
        assertEquals(future, createBody["definition"])
        assertFalse(createBody.containsKey("description"))
        assertEquals(initial.playlists + 2, client.personalRevision.value.playlists)
        assertEquals(initial.likes, client.personalRevision.value.likes)
        assertEquals(initial.history, client.personalRevision.value.history)
    }

    @Test fun evaluationUsesSignedCursorUnchangedAndSupportsLongRanksAndCounts() = runBlocking {
        val cursor = "c1_opaque.signature+/%?=&"
        web.enqueue(jsonResponse(page(cursor = cursor)))
        val first = api.evaluate("rules", limit = 2)
        assertEquals(5_000_000_000L, first.total)
        assertEquals(1_000_001L, first.items.single().position)
        assertEquals("occ-2", first.items.single().track.entryId)
        assertEquals("signed-selection", first.items.single().selectionToken)
        assertEquals("/api/me/playlists/rules/evaluation?limit=2", web.takeRequest().path)
        web.enqueue(jsonResponse(page()))
        api.evaluate("rules", limit = null, cursor = first.nextCursor)
        val next = web.takeRequest()
        assertEquals("GET", next.method)
        assertEquals("/api/me/playlists/rules/evaluation", next.requestUrl!!.encodedPath)
        assertEquals(cursor, next.requestUrl!!.queryParameter("cursor"))
        assertEquals(setOf("cursor"), next.requestUrl!!.queryParameterNames)
    }

    @Test fun previewTargetPairAndContinuationAreJsonPostNotPlayableSelection() = runBlocking {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
        web.enqueue(jsonResponse(page(preview = true)))
        val result = api.preview(rawDefinition, "rules", 5, limit = null, cursor = "c1_preview.signature")
        assertNull(result.items.single().selectionToken)
        val request = web.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/me/playlists/preview", request.path)
        assertEquals("csrf-secret", request.getHeader("X-CSRF-Token"))
        val payload = objectOf(request.body.readUtf8())
        assertEquals(setOf("definition", "targetPlaylistId", "expectedRevision", "cursor"), payload.keys)
        assertEquals(rawDefinition, payload["definition"])
        assertEquals("rules", payload["targetPlaylistId"]!!.jsonPrimitive.content)
        assertEquals("c1_preview.signature", payload["cursor"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.preview(rawDefinition, targetPlaylistId = "rules") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.preview(rawDefinition, expectedRevision = 5) } }
        assertEquals(2, web.requestCount)
    }

    @Test fun copySendsFenceAndMetadataAndCreatesManualSummaryWithoutConversion() = runBlocking {
        authenticate()
        val initial = client.personalRevision.value
        web.enqueue(jsonResponse(summary(derived = false), 201))
        val copied = api.copy("rules", "Editable", "Includes unavailable and duplicates", 5, "f1_fence")
        assertEquals(PlaylistKind.MANUAL, copied.kind)
        assertEquals(2L, copied.trackCount)
        val request = web.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/me/playlists/rules/copy", request.path)
        assertEquals(setOf("name", "description", "expectedRevision", "fence"), objectOf(request.body.readUtf8()).keys)
        assertEquals(initial.playlists + 1, client.personalRevision.value.playlists)
        assertEquals(initial.likes, client.personalRevision.value.likes)
        assertEquals(initial.history, client.personalRevision.value.history)
    }

    @Test fun malformedDerivedMutationAcknowledgementsNeverPublishChanges() = runBlocking {
        authenticate()
        val initial = client.personalRevision.value
        web.enqueue(jsonResponse("{}", 201))
        assertTrue(apiFailure { api.create("Rules", definition = rawDefinition) }.status in 200..299)
        web.enqueue(jsonResponse("{}", 200))
        assertTrue(apiFailure { api.updateDefinition("rules", rawDefinition, 5) }.status in 200..299)
        web.enqueue(jsonResponse("{}", 201))
        assertTrue(apiFailure { api.copy("rules", "Copy", expectedRevision = 5, fence = "f1_fence") }.status in 200..299)
        assertEquals(initial, client.personalRevision.value)
        assertEquals(4, web.requestCount)
    }

    @Test fun conflictsBusyAndCopyLimitNeverTriggerRetriesOrManualFallback() = runBlocking {
        authenticate()
        val initial = client.personalRevision.value
        for ((status, code) in listOf(409 to "STALE_EVALUATION", 429 to "EVALUATION_BUSY", 400 to "INVALID_CURSOR")) {
            web.enqueue(jsonResponse("""{"error":{"code":"$code","message":"private server internals"}}""", status))
            val error = apiFailure { api.evaluate("rules", cursor = "opaque-cursor") }
            assertEquals(status, error.status)
            assertEquals(code, error.code)
            assertFalse(error.message.contains("private"))
            assertEquals("/api/me/playlists/rules/evaluation", web.takeRequest().requestUrl!!.encodedPath)
        }
        web.enqueue(jsonResponse("""{"error":{"code":"COPY_LIMIT_EXCEEDED","message":"unsafe"}}""", 422))
        assertEquals("COPY_LIMIT_EXCEEDED", apiFailure { api.copy("rules", "Copy", expectedRevision = 5, fence = "f1_fence") }.code)
        web.takeRequest()
        assertEquals(5, web.requestCount)
        assertEquals(initial, client.personalRevision.value)
    }

    @Test fun rejectsPartialPagesAndMissingSelectionRatherThanReturningSuccessfulEmptyData() = runBlocking {
        web.enqueue(jsonResponse(page().replace("\"complete\":true", "\"complete\":false")))
        assertEquals(200, apiFailure { api.evaluate("rules") }.status)
        web.enqueue(jsonResponse(page(preview = true)))
        assertEquals(200, apiFailure { api.evaluate("rules") }.status)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.evaluate("rules/escaped") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.evaluate("rules", cursor = "x".repeat(4097)) } }
        assertEquals(2, web.requestCount)
    }

    private suspend fun authenticate() {
        web.enqueue(loginResponse())
        client.login("listener", "password")
        web.takeRequest()
    }

    private fun page(cursor: String? = null, preview: Boolean = false): String =
        """{"items":[{"occurrenceId":"occ-2","selectionToken":${if (preview) "null" else "\"signed-selection\""},"position":1000001,"track":${track()}}],"total":5000000000,"availableTotal":4000000000,"limit":2,"nextCursor":${cursor?.let { ApiJson.encodeToString(kotlinx.serialization.serializer<String>(), it) } ?: "null"},"revision":5,"fence":"f1_fence","complete":true}"""
    private fun track() = """{"kind":"track","id":"same","name":"Song","hasAudio":true}"""
    private fun summary(derived: Boolean) =
        """{"id":"${if (derived) "rules" else "copy"}","name":"Rules","description":"","kind":"${if (derived) "derived" else "manual"}","countState":"${if (derived) "unevaluated" else "exact"}","trackCount":${if (derived) "null" else "2"},"availableCount":${if (derived) "null" else "2"},"revision":5,"capabilities":{"editMetadata":true,"editEntries":${!derived},"editDefinition":$derived,"play":true,"copyToManual":$derived,"delete":true,"addTracks":${!derived}}}"""
    private fun objectOf(text: String) = ApiJson.parseToJsonElement(text) as JsonObject
}
