package dev.spofify.mobile.feature.playlists

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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

/** Actual manual/derived Source -> More path, never a stand-alone menu callback test.
 * The tokens here are opaque wire fixtures, not valid signed evaluator integration tokens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistMoreHostTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher(scheduler))
    @Test fun manualMorePreservesExactDuplicateEntryForAllFourIntentsWithoutControllerPreparation() = check(derived = false)
    @Test fun derivedMorePreservesSignedExactOccurrenceAndFenceForAllFourIntentsWithoutPreparation() = check(derived = true)
    @Test fun manualCollectionMoreSendsWholeBoundedContextForAllFourIntents() = check(derived = false, collection = true)
    @Test fun derivedCollectionMorePreservesEvaluatedRevisionAndFence() = check(derived = true, collection = true)
    @Test fun manualOpenMoreCannotRebindAfterOutputReplacement() = check(derived = false, stale = true)
    @Test fun derivedOpenMoreCannotRebindAfterOutputReplacement() = check(derived = true, stale = true)
    private fun check(derived: Boolean, collection: Boolean = false, stale: Boolean = false) {
        val preparations = AtomicInteger()
        val kind = if (derived) "derived" else "manual"
        val summary = """{"id":"fixture","kind":"$kind","name":"Playlist fixture","description":"","countState":"${if (derived) "unevaluated" else "exact"}","trackCount":${if (derived) "null" else "1"},"availableCount":${if (derived) "null" else "1"},"revision":5,"capabilities":{"play":true,"editMetadata":false,"editEntries":false,"editDefinition":false,"delete":false,"copyToManual":false,"addTracks":false}}"""
        val track = """{"kind":"track","id":"same","name":"Fixture song","hasAudio":true}"""
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path.endsWith("/playback")) preparations.incrementAndGet()
                    return when (path) {
                        "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                        "/api/me/playlists" -> json("""{"items":[$summary],"total":1,"offset":0,"limit":40}""")
                        "/api/me/playlists/fixture" -> json(summary)
                        "/api/me/playlists/fixture/entries" -> json("""{"items":[{"id":"exact-entry","position":0,"track":$track}],"total":1,"offset":0,"limit":40}""")
                        "/api/me/playlists/fixture/evaluation" -> json("""{"items":[{"occurrenceId":"exact-path","selectionToken":"opaque.signature","position":1001,"track":$track}],"total":1002,"availableTotal":1002,"limit":40,"nextCursor":null,"revision":5,"fence":"exact-fence","complete":true}""")
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
            try {
                runBlocking { client.login("listener", "synthetic") }
                compose.setContent {
                    MaterialTheme {
                        if (shown.value) PlaylistsRoute(client, {}, { _, _, _ -> fail("No controller preparation") }, {},
                            initialPlaylistId = "fixture", playback = feedback.value,
                            onQueueIntent = { selection, action, capturedKey, capturedBinding, current ->
                                assertSame(key, capturedKey); assertEquals(binding, capturedBinding); assertTrue(current())
                                accepted += selection to action
                            })
                    }
                }
                compose.waitUntil(5_000) {
                    compose.waitForIdle()
                    compose.onAllNodesWithContentDescription("More actions for Fixture song").fetchSemanticsNodes().size == 1
                }
                val labels = listOf("Queue Low" to PlaybackListAction.QUEUE_LOW, "Queue High" to PlaybackListAction.QUEUE_HIGH,
                    "Play next" to PlaybackListAction.PLAY_NEXT, "Play now" to PlaybackListAction.PLAY_NOW)
                for ((label, expectedAction) in if (stale) labels.take(1) else labels) {
                    compose.onNodeWithContentDescription(if (collection) "More actions for Playlist fixture" else "More actions for Fixture song").performClick()
                    if (!collection) compose.waitUntil(5_000) {
                        compose.waitForIdle(); compose.onAllNodesWithText("Like song").fetchSemanticsNodes().size == 1
                    }
                    compose.onNodeWithText(label).assertIsEnabled()
                    compose.runOnIdle { feedback.value = feedback.value.copy(buffering = true, playWhenReady = true,
                        interactionKey = if (stale) PlaybackInteractionKey() else key,
                        queueIntentBinding = if (stale) binding.copy(revision = 8) else binding) }
                    compose.onNodeWithText(label).performScrollTo().performClick()
                    compose.waitForIdle()
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
                    }
                }
            } finally {
                compose.runOnIdle { shown.value = false }; compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
