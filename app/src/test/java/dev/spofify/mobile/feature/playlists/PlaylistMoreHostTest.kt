package dev.spofify.mobile.feature.playlists

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.library.CatalogPlaybackFeedback
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual manual/derived Source -> More path, never a stand-alone menu callback test.
 * The tokens here are opaque wire fixtures, not valid signed evaluator integration tokens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistMoreHostTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher(scheduler))
    @Test fun manualMorePreservesExactDuplicateEntryForAllSupportedIntentsWithoutControllerPreparation() = check(derived = false)
    @Test fun derivedMorePreservesSignedExactOccurrenceAndFenceForAllSupportedIntentsWithoutPreparation() = check(derived = true)
    @Test fun manualCollectionMoreSendsWholeBoundedContextForAllSupportedIntents() = check(derived = false, collection = true)
    @Test fun derivedCollectionMorePreservesEvaluatedRevisionAndFence() = check(derived = true, collection = true)
    @Test fun manualOpenMoreCannotReviveAfterOutputReplacementABA() = check(derived = false, stale = true)
    @Test fun derivedOpenMoreCannotReviveAfterOutputReplacementABA() = check(derived = true, stale = true)
    @Test fun manualCollectionMoreCannotReviveAfterOutputReplacementABA() = check(derived = false, collection = true, stale = true)
    @Test fun derivedCollectionMoreCannotReviveAfterOutputReplacementABA() = check(derived = true, collection = true, stale = true)
    private fun check(derived: Boolean, collection: Boolean = false, stale: Boolean = false) {
        val preparations = AtomicInteger()
        val evaluations = AtomicInteger()
        val kind = if (derived) "derived" else "manual"
        val summary = """{"id":"fixture","kind":"$kind","name":"Playlist fixture","description":"","countState":"exact","trackCount":1,"availableCount":1,"evaluationState":${if (derived) "\"ready\"" else "null"},"revision":5,"capabilities":{"play":true,"editMetadata":false,"editEntries":false,"editDefinition":false,"delete":false,"copyToManual":false,"addTracks":false}}"""
        val track = """{"kind":"track","id":"same","name":"Fixture song","hasAudio":true}"""
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path.endsWith("/playback")) preparations.incrementAndGet()
                    if (path.endsWith("/evaluation")) evaluations.incrementAndGet()
                    return when (path) {
                        "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                        "/api/me/playlists" -> json("""{"items":[$summary],"total":1,"offset":0,"limit":40}""")
                        "/api/me/playlists/fixture" -> json(summary)
                        "/api/me/playlists/fixture/entries" -> if (derived) {
                            json("""{"items":[{"occurrenceId":"exact-path","selectionToken":"opaque.signature","position":0,"track":$track}],"total":1,"availableTotal":1,"limit":40,"nextCursor":null,"revision":5,"fence":"exact-fence","complete":true,"evaluationState":"ready"}""")
                        } else json("""{"items":[{"id":"exact-entry","position":0,"track":$track}],"total":1,"offset":0,"limit":40}""")
                        "/api/me/likes/status" -> json("""{"items":[{"trackId":"same","liked":false}]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            web.start(InetAddress.getByName("127.0.0.1"), 0)
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            val key = PlaybackInteractionKey()
            val binding = QueueIntentBinding("pc", "receiver", 7, 1, "history-A")
            val feedback = mutableStateOf(CatalogPlaybackFeedback(interactionKey = key, queueIntentBinding = binding))
            val shown = mutableStateOf(true)
            val accepted = mutableListOf<Pair<QueueIntentSelection, PlaybackListAction>>()
            val admittedSources = mutableListOf<() -> Boolean>()
            try {
                runBlocking { client.login("listener", "synthetic") }
                compose.setContent {
                    MaterialTheme {
                        if (shown.value) PlaylistsRoute(client, {}, { _, _, _ -> fail("No controller preparation") }, {},
                            initialPlaylistId = "fixture", playback = feedback.value,
                            // Exercise the real artwork URL admission used by MainActivity.Artwork.
                            // A placeholder-only host masks unsupported collection kinds at More.
                            artwork = { item, _ -> client.artworkUrl(item) },
                            onQueueIntent = { selection, action, capturedKey, capturedBinding, current ->
                                assertSame(key, capturedKey); assertEquals(binding, capturedBinding); assertTrue(current())
                                accepted += selection to action
                                admittedSources += current
                            })
                    }
                }
                compose.waitUntil(5_000) {
                    compose.waitForIdle()
                    compose.onAllNodesWithContentDescription("More actions for Fixture song").fetchSemanticsNodes().size == 1
                }
                val labels = listOf("Queue" to if (collection) PlaybackListAction.QUEUE_LOW else PlaybackListAction.QUEUE_HIGH,
                    "Play next" to PlaybackListAction.PLAY_NEXT, "Play now" to PlaybackListAction.PLAY_NOW)
                for ((label, expectedAction) in if (stale) labels.take(1) else labels) {
                    compose.runOnIdle { feedback.value = feedback.value.copy(interactionKey = key, queueIntentBinding = binding) }
                    compose.onNodeWithContentDescription(if (collection) "More actions for Playlist fixture" else "More actions for Fixture song").performClick()
                    if (!collection) compose.waitUntil(5_000) {
                        compose.waitForIdle(); compose.onAllNodesWithText("Like song").fetchSemanticsNodes().size == 1
                    }
                    compose.onNodeWithText(label).assertIsEnabled()
                    compose.runOnIdle { feedback.value = feedback.value.copy(buffering = true, playWhenReady = true,
                        interactionKey = if (stale) PlaybackInteractionKey() else key,
                        queueIntentBinding = if (stale) binding.copy(revision = 8) else binding) }
                    if (stale) {
                        compose.onNodeWithText(label).performScrollTo().assertIsNotEnabled()
                        compose.runOnIdle { feedback.value = feedback.value.copy(interactionKey = key, queueIntentBinding = binding) }
                        compose.onNodeWithText(label).assertIsNotEnabled()
                    }
                    compose.onNodeWithText(label).performScrollTo().performClick()
                    compose.waitForIdle()
                    if (!stale) {
                        // Once submitted, source guards must not veto the playback owner's own
                        // additive successor receipt. Binding/receipt validation stays in that owner.
                        compose.runOnIdle { feedback.value = feedback.value.copy(interactionKey = PlaybackInteractionKey(),
                            queueIntentBinding = binding.copy(revision = 8)) }
                        compose.runOnIdle { assertTrue("Admitted source survives output publication", admittedSources.all { it() }) }
                    }
                    compose.runOnIdle {
                        if (stale) assertTrue("Open More must never refresh a binding", accepted.isEmpty())
                        else {
                            assertEquals(expectedAction, accepted.last().second)
                            val selection = accepted.last().first
                            val context = if (collection) (selection as QueueIntentSelection.Collection).context
                                else (selection as QueueIntentSelection.Track).context!!
                            assertEquals(if (derived) "derived" else "playlist", context.kind)
                            assertEquals("fixture", context.id); assertEquals(5L, context.revision)
                            if (derived) assertEquals("exact-fence", context.fence)
                            if (!collection) {
                                val row = (selection as QueueIntentSelection.Track).track
                                assertEquals("same", row.getValue("id").jsonPrimitive.content)
                                assertFalse(row.containsKey("key")); assertFalse(row.containsKey("order"))
                                if (derived) {
                                    assertEquals("exact-path", row.getValue("occurrenceId").jsonPrimitive.content)
                                    assertEquals("opaque.signature", row.getValue("selectionToken").jsonPrimitive.content)
                                    assertFalse(row.containsKey("entryId"))
                                } else assertEquals("exact-entry", row.getValue("entryId").jsonPrimitive.content)
                            }
                        }
                        assertEquals("Receiver resolves the complete bounded context", 0, preparations.get())
                        assertEquals("Saved playlists use normal entries, never an Evaluate workflow", 0, evaluations.get())
                    }
                }
            } finally {
                compose.runOnIdle { shown.value = false }; compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }
    @Test fun manualAccessibilityPlayFallbackDeliversExactSecondDuplicateOnce() = fallback(derived = false)
    @Test fun derivedAccessibilityPlayFallbackDeliversExactSignedSecondDuplicateOnce() = fallback(derived = true)
    @Test fun manualAccessibilityPlayFallbackRejectsHeldResponseAfterSourceReplacement() = fallback(derived = false, replaceSource = true)
    @Test fun derivedAccessibilityPlayFallbackRejectsHeldResponseAfterSourceReplacement() = fallback(derived = true, replaceSource = true)

    private fun fallback(derived: Boolean, replaceSource: Boolean = false) {
        val release = CountDownLatch(if (replaceSource) 1 else 0)
        val preparations = AtomicInteger()
        val selected = mutableStateOf("fixture")
        val shown = mutableStateOf(true)
        val delivered = mutableListOf<Triple<PlaybackQueue, String?, String?>>()
        val track = """{"kind":"track","id":"same","name":"Fixture song","hasAudio":true,"durationMs":1000}"""
        fun summary(id: String) = """{"id":"$id","name":"Playlist $id","kind":"${if (derived) "derived" else "manual"}","countState":"${if (derived) "unevaluated" else "exact"}","trackCount":${if (derived) "null" else "2"},"availableCount":${if (derived) "null" else "2"},"revision":5}"""
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val id = if (path.contains("/replacement")) "replacement" else "fixture"
                    return when {
                        path == "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                        path == "/api/me/playlists" -> json("""{"items":[${summary("fixture")}],"total":1,"offset":0,"limit":40}""")
                        path.endsWith("/entries") -> if (derived) json("""{"items":[{"occurrenceId":"first-path","selectionToken":"opaque.first","position":0,"track":$track},{"occurrenceId":"second-path","selectionToken":"opaque.second","position":1,"track":$track}],"total":2,"availableTotal":2,"limit":40,"nextCursor":null,"revision":5,"fence":"fence","complete":true,"evaluationState":"ready"}""")
                            else json("""{"items":[{"id":"first-entry","position":0,"track":$track},{"id":"second-entry","position":1,"track":$track}],"total":2,"offset":0,"limit":40}""")
                        path.endsWith("/playback") -> {
                            preparations.incrementAndGet()
                            check(release.await(10, TimeUnit.SECONDS)) { "Owned preparation hold timed out" }
                            val context = """{"kind":"${if (derived) "derived" else "playlist"}","id":"fixture","name":"Playlist fixture","revision":5${if (derived) ",\"fence\":\"fence\"" else ""}}"""
                            if (derived) {
                                val body = ApiJson.parseToJsonElement(request.body.readUtf8()).toString()
                                assertTrue(body.contains("\"selectionToken\":\"opaque.second\""))
                                json("""{"context":$context,"items":[${track.dropLast(1)},"occurrenceId":"first-path","selectionToken":"opaque.first"},${track.dropLast(1)},"occurrenceId":"second-path","selectionToken":"opaque.second"}],"total":2,"limit":1000,"truncated":false,"revision":5,"fence":"fence","startOccurrenceId":"second-path","notice":null}""")
                            } else json("""{"context":$context,"items":[${track.dropLast(1)},"entryId":"first-entry"},${track.dropLast(1)},"entryId":"second-entry"}],"total":2,"limit":1000,"truncated":false,"revision":5}""")
                        }
                        path == "/api/me/playlists/$id" -> json(summary(id))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            web.start()
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            try {
                runBlocking { client.login("listener", "synthetic") }
                compose.setContent {
                    MaterialTheme {
                        if (shown.value) PlaylistsRoute(client, {}, { queue, trackId, entryId ->
                            delivered += Triple(queue, trackId, entryId)
                        }, {}, initialPlaylistId = selected.value) // Real supported fallback: no queue-intent callback.
                    }
                }
                compose.waitUntil(5_000) {
                    compose.waitForIdle()
                    compose.onAllNodesWithContentDescription("More actions for Fixture song").fetchSemanticsNodes().size == 2
                }
                val second = compose.onAllNodes(hasText("Fixture song") and
                    SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))[1]
                val play = second.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Play now" }
                compose.runOnIdle { assertTrue(delivered.isEmpty()); assertTrue(play.action()) }
                if (replaceSource) {
                    compose.waitUntil(5_000) { preparations.get() == 1 }
                    compose.runOnIdle { selected.value = "replacement" }
                    compose.waitUntil(5_000) {
                        compose.waitForIdle(); compose.onAllNodesWithText("Playlist replacement").fetchSemanticsNodes().size == 1
                    }
                    release.countDown()
                    compose.waitUntil(5_000) {
                        compose.waitForIdle()
                        client.httpClient.dispatcher.runningCallsCount() == 0 && client.httpClient.dispatcher.queuedCallsCount() == 0
                    }
                    compose.runOnIdle { assertTrue("Old source cannot deliver a late exact selection", delivered.isEmpty()) }
                } else {
                    compose.waitUntil(5_000) { compose.waitForIdle(); delivered.size == 1 }
                    compose.runOnIdle {
                        val (queue, trackId, entryId) = delivered.single()
                        assertEquals(if (derived) null else "same", trackId)
                        assertEquals(if (derived) "second-path" else "second-entry", entryId)
                        assertEquals(listOf("same", "same"), queue.items.map { it.id })
                        assertEquals(if (derived) listOf("first-path", "second-path") else listOf("first-entry", "second-entry"), queue.items.map { it.entryId })
                        assertEquals("fixture", queue.context?.id)
                        assertEquals(5L, queue.revision)
                    }
                }
                assertEquals(1, preparations.get())
            } finally {
                release.countDown()
                compose.runOnIdle { shown.value = false }; compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
