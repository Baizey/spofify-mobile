package dev.spofify.mobile.feature.playlists

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.spofify.mobile.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private fun previewPage(offset: Int, total: Int = 81, fence: String = "fence", next: String? =
    if (offset + 40 < total) "cursor-${offset + 40}" else null, positionShift: Int = 0): String {
    val items = (offset until minOf(offset + 40, total)).joinToString(",") {
        // Every track is deliberately the same; occurrence identity, not track ID, must survive.
        """{"occurrenceId":"occ-$it","selectionToken":null,"position":${it + positionShift},"track":{"kind":"track","id":"same","name":"Song $it"}}"""
    }
    return """{"items":[$items],"total":$total,"availableTotal":$total,"limit":40,"nextCursor":${next?.let { "\"$it\"" } ?: "null"},"revision":null,"fence":"$fence","complete":true}"""
}
private fun previewResponse(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
    .setHeader("Content-Type", "application/json").setBody(body)
private fun previewClient(web: MockWebServer) = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
    override fun read(): StoredSession? = null
    override fun write(value: StoredSession?) = Unit
})

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistEditorPreviewStateTest {
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent(); assertTrue("Timed out waiting for preview", condition())
    }

    @Test fun deliberateRefreshSingleFlightExactCursorRetryAndDuplicateTrackOccurrences() = runTest {
        MockWebServer().use { web ->
            web.start()
            val client = previewClient(web)
            val state = EditorPreviewState(DerivedPlaylistsApi(client), client, this)
            val rules = blankRecipe()
            state.more(); state.retry()
            assertEquals(0, web.requestCount)
            web.enqueue(previewResponse(previewPage(0)))
            web.enqueue(previewResponse("{}", 503))
            web.enqueue(previewResponse(previewPage(40)))
            web.enqueue(previewResponse(previewPage(80)))
            state.refresh(rules, true, null); state.more(); state.refresh(rules, true, null)
            until { !state.pending }
            state.more(); state.more(); until { !state.pending }
            repeat(6) { state.more() }
            assertEquals(2, web.requestCount)
            assertEquals(40, state.entries.size)
            assertNotNull(state.error)
            state.retry(); state.retry(); until { !state.pending }
            assertEquals(80, state.entries.size)
            state.more(); until { !state.pending }
            assertEquals(81, state.entries.size)
            assertEquals(81, state.entries.map { it.occurrenceId }.toSet().size)
            assertEquals(1, state.entries.map { it.track.id }.toSet().size)
            assertFalse(state.canAppend)
            state.more(); assertEquals(4, web.requestCount)
            val bodies = (0 until 4).map { ApiJson.parseToJsonElement(web.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject }
            assertFalse(bodies[0].containsKey("cursor"))
            assertEquals(listOf("cursor-40", "cursor-40", "cursor-80"), bodies.drop(1).map { it.getValue("cursor").jsonPrimitive.content })
        }
    }

    @Test fun fenceRevisionTotalsAndRepeatedCursorChangesStopWithoutPublishingMixedRows() = runTest {
        val invalidPages = listOf(
            previewPage(40, fence = "changed"),
            previewPage(40).replace("\"revision\":null", "\"revision\":2"),
            previewPage(40, total = 82),
            previewPage(40, next = "cursor-40"),
            previewPage(40, positionShift = 1), // increasing positions still skip the prefix frontier
            previewPage(40).replace("\"position\":79", "\"position\":80"), // gap within an increasing batch
            previewPage(0), // repeated occurrences/positions are not progress
        )
        for (invalid in invalidPages) MockWebServer().use { web ->
            web.start(); val client = previewClient(web)
            val state = EditorPreviewState(DerivedPlaylistsApi(client), client, this)
            web.enqueue(previewResponse(previewPage(0))); web.enqueue(previewResponse(invalid))
            state.refresh(blankRecipe(), true, null); until { !state.pending }
            state.more(); until { !state.pending }
            assertTrue(state.stale); assertEquals(40, state.entries.size)
            state.more(); state.retry(); assertEquals(2, web.requestCount)
        }
    }

    @Test fun previewMustBeginAtZeroAndInvalidPrefixNeedsExplicitRefresh() = runTest {
        MockWebServer().use { web ->
            web.start(); val client = previewClient(web)
            val state = EditorPreviewState(DerivedPlaylistsApi(client), client, this)
            web.enqueue(previewResponse(previewPage(0, positionShift = 1)))
            state.refresh(blankRecipe(), true, null); until { !state.pending }
            assertTrue(state.stale)
            assertTrue(state.entries.isEmpty())
            assertNull(state.page)
            state.more(); state.retry(); runCurrent()
            assertEquals(1, web.requestCount)

            web.enqueue(previewResponse(previewPage(0)))
            state.refresh(blankRecipe(), true, null); until { !state.pending }
            assertFalse(state.stale)
            assertEquals(0L, state.entries.first().position)
            assertEquals(40, state.entries.size)
            assertEquals(2, web.requestCount)
        }
    }

    @Test fun expiredPreviewCursorRetainsReadOnlyPrefixAndCannotBeRetried() = runTest {
        for (status in listOf(409, 410)) MockWebServer().use { web ->
            web.start(); val client = previewClient(web)
            val state = EditorPreviewState(DerivedPlaylistsApi(client), client, this)
            web.enqueue(previewResponse(previewPage(0)))
            web.enqueue(previewResponse("{}", status))
            state.refresh(blankRecipe(), true, null); until { !state.pending }
            val prefix = state.entries
            state.more(); until { !state.pending }
            assertTrue("Expired cursor $status requires a new deliberate evaluation", state.stale)
            assertEquals(prefix, state.entries)
            assertNull(state.error)
            repeat(5) { state.more(); state.retry() }
            runCurrent(); assertEquals(2, web.requestCount)

            web.enqueue(previewResponse(previewPage(0, fence = "fresh")))
            state.refresh(blankRecipe(), true, null); until { !state.pending }
            assertFalse(state.stale)
            assertEquals("fresh", state.page?.fence)
            val bodies = (0 until 3).map {
                ApiJson.parseToJsonElement(web.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            }
            assertFalse(bodies[0].containsKey("cursor"))
            assertEquals("cursor-40", bodies[1].getValue("cursor").jsonPrimitive.content)
            assertFalse("Refresh starts a new prefix, never restarts into retained rows", bodies[2].containsKey("cursor"))
        }
    }

    @Test fun equalAccountReauthenticationCannotReusePreviousPreviewSessionIncarnation() = runTest {
        MockWebServer().use { web ->
            web.start(); val client = previewClient(web)
            fun loginResponse() = previewResponse("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
            web.enqueue(loginResponse()); client.login("listener", "synthetic")
            val before = client.currentSession()
            val state = EditorPreviewState(DerivedPlaylistsApi(client), client, this)
            val rules = blankRecipe()
            web.enqueue(previewResponse(previewPage(0)))
            state.refresh(rules, true, null); until { !state.pending }
            web.enqueue(loginResponse()); client.login("listener", "synthetic")
            assertEquals(before, client.currentSession())
            assertNotSame(before, client.currentSession())
            state.checkInputs(rules, true, null)
            state.more(); state.retry()
            assertTrue(state.stale); assertEquals(3, web.requestCount)
        }
    }

    @Test fun playlistRevisionChangeBeforeReservationOrDispatchCannotSendObsoletePreviewCursor() = runTest {
        for (afterReservation in listOf(false, true)) MockWebServer().use { web ->
            web.start(); val client = previewClient(web)
            try {
                web.enqueue(previewResponse("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                    .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600"))
                client.login("listener", "synthetic")
                val state = EditorPreviewState(DerivedPlaylistsApi(client), client, this)
                web.enqueue(previewResponse(previewPage(0)))
                state.refresh(blankRecipe(), true, null); until { !state.pending }
                val prefix = state.entries
                val before = client.personalRevision.value.playlists
                if (afterReservation) {
                    state.more()
                    assertTrue(state.pending)
                }
                client.notifyPersonalChange(PersonalCollection.PLAYLISTS)
                assertEquals(before + 1, client.personalRevision.value.playlists)
                if (!afterReservation) state.more()
                runCurrent()
                assertTrue("The source fence must precede the HTTP read", state.stale)
                assertFalse(state.pending)
                assertEquals(prefix, state.entries)
                state.more(); state.retry(); runCurrent()
                assertEquals("Only login and the established prefix were requested", 2, web.requestCount)
                state.invalidate()
            } finally {
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun invalidOrEditedDraftCannotEstablishOrContinuePreviewWithoutNewExplicitRefresh() = runTest {
        MockWebServer().use { web ->
            web.start(); val client = previewClient(web)
            val draft = PlaylistEditorDraft().apply { derived = true }
            val state = EditorPreviewState(DerivedPlaylistsApi(client), client, this) { draft.editVersion }
            val a = draft.rules
            val b = a.withField("sort", JsonPrimitive("title"))
            state.refresh(a.withField("sources", null), true, null)
            assertEquals(0, web.requestCount)
            web.enqueue(previewResponse(previewPage(0)))
            state.refresh(a, true, null, draft.editVersion); until { !state.pending }
            draft.rules = b
            draft.rules = a // Both edits happen before the holder observes a composition.
            state.checkInputs(draft.rules, true, null, draft.editVersion)
            state.more(); state.retry()
            assertTrue(state.stale); assertEquals(1, web.requestCount)
            web.enqueue(previewResponse(previewPage(0)))
            state.refresh(a, true, null, draft.editVersion); until { !state.pending }
            assertFalse(state.stale); assertEquals(2, web.requestCount)
            draft.derived = false; draft.derived = true
            state.checkInputs(a, true, null, draft.editVersion)
            state.more(); assertTrue(state.stale); assertEquals(2, web.requestCount)
        }
    }
}

/** Actual editor form + viewport: no evaluation on open/edit and no action replacing scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistEditorContinuousHostTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    @Test fun explicitPreviewThenVisibleAppendThenFormEditInvalidatesTheEntireChain() {
        val cursors = CopyOnWriteArrayList<String>()
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return previewResponse("""{"items":[],"total":0,"limit":100,"offset":0}""")
                    assertEquals("/api/me/playlists/preview", request.requestUrl!!.encodedPath)
                    val body = ApiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val cursor = body["cursor"]?.jsonPrimitive?.content ?: "initial"
                    cursors += cursor
                    return previewResponse(previewPage(if (cursor == "initial") 0 else cursor.removePrefix("cursor-").toInt()))
                }
            }
            web.start(); val client = previewClient(web)
            val shown = mutableStateOf(true)
            val draft = PlaylistEditorDraft().apply { derived = true; name = "Draft" }
            try {
                compose.setContent { MaterialTheme {
                    if (shown.value) PlaylistEditor(client, null, draft, {}, { fail("Preview must not save") })
                } }
                compose.waitForIdle(); assertTrue(cursors.isEmpty())
                compose.onNodeWithText("Refresh preview").performScrollTo().performClick()
                waitForRequests(cursors, 1)
                waitForPublishedPreview()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("40. Song 39"))
                waitForRequests(cursors, 2)
                scrollTo("61. Song 60")
                assertEquals(listOf("initial", "cursor-40"), cursors.toList())
                // Discover the offscreen lazy form before targeting its descendant control.
                // Edit through the actual form before the next frontier is visible.
                scrollTo("Add source")
                compose.onNodeWithText("Add source").performScrollTo().performClick()
                compose.waitForIdle()
                val countAfterEdit = cursors.size
                scrollTo("80. Song 79")
                compose.waitForIdle()
                assertEquals(countAfterEdit, cursors.size)
                scrollTo("Preview changed. Refresh preview.")
                compose.onNodeWithText("Preview changed. Refresh preview.").assertExists()
                scrollTo("Refresh preview")
                compose.onNodeWithText("Refresh preview").performScrollTo().performClick()
                waitForRequests(cursors, countAfterEdit + 1)
                waitForPublishedPreview()
                assertEquals("initial", cursors.last())
            } finally {
                compose.runOnIdle { shown.value = false }; compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }
    @Test fun idlePlaylistRevisionEmissionMarksPreviewStaleWithoutDispatchingAnotherCursor() {
        val cursors = CopyOnWriteArrayList<String>()
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/api/auth/login")
                        return previewResponse("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                    if (request.method == "GET") return previewResponse("""{"items":[],"total":0,"limit":100,"offset":0}""")
                    assertEquals("/api/me/playlists/preview", request.requestUrl!!.encodedPath)
                    val body = ApiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val cursor = body["cursor"]?.jsonPrimitive?.content ?: "initial"
                    cursors += cursor
                    return previewResponse(previewPage(if (cursor == "initial") 0 else cursor.removePrefix("cursor-").toInt()))
                }
            }
            web.start(); val client = previewClient(web)
            val shown = mutableStateOf(true)
            val draft = PlaylistEditorDraft().apply { derived = true; name = "Retained draft" }
            val rules = draft.rules
            try {
                runBlocking { client.login("listener", "synthetic") }
                compose.setContent { MaterialTheme {
                    if (shown.value) PlaylistEditor(client, null, draft, {}, { fail("Revision invalidation must not save") })
                } }
                compose.waitForIdle()
                compose.onNodeWithText("Refresh preview").performScrollTo().performClick()
                waitForRequests(cursors, 1)
                waitForPublishedPreview()
                scrollTo("Refresh preview")
                compose.waitForIdle()
                assertEquals(listOf("initial"), cursors.toList())
                val before = client.personalRevision.value.playlists

                // Assert the idle host changes BEFORE any scroll or edit could incidentally
                // recompose it. No direct checkInputs/invalidate call substitutes for the host.
                compose.runOnIdle { client.notifyPersonalChange(PersonalCollection.PLAYLISTS) }
                assertEquals(before + 1, client.personalRevision.value.playlists)
                val stale = "Preview changed. Refresh preview."
                compose.waitUntil(5_000) {
                    compose.waitForIdle()
                    compose.onAllNodesWithText(stale).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(stale).assertExists()
                assertEquals("Retained draft", draft.name)
                assertEquals(rules, draft.rules)
                assertEquals(listOf("initial"), cursors.toList())
                scrollTo("40. Song 39")
                compose.onNodeWithText("40. Song 39").assertExists()
                compose.waitForIdle()
                assertEquals("Stale rows at the frontier cannot continue the old chain", listOf("initial"), cursors.toList())

                scrollTo("Refresh preview")
                compose.onNodeWithText("Refresh preview").performScrollTo().performClick()
                waitForRequests(cursors, 2)
                waitForPublishedPreview()
                assertEquals(listOf("initial", "initial"), cursors.toList())
            } finally {
                compose.runOnIdle { shown.value = false }; compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun waitForPublishedPreview() {
        // Observe a real published first row, not a removed routine count/explanation line.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("1. Song 0")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Retry preview").assertDoesNotExist()
    }
    private fun scrollTo(text: String) {
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            try {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text)); true
            } catch (_: AssertionError) { false }
        }
    }
    private fun waitForRequests(requests: List<String>, count: Int) {
        compose.waitUntil(5_000) { compose.waitForIdle(); requests.size >= count }
        compose.waitForIdle()
    }
}
