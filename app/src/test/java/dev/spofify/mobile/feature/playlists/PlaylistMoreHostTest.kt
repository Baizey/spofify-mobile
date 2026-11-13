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
            val binding = dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding("pc", "receiver", 7, 1, 1)
            val feedback = mutableStateOf(CatalogPlaybackFeedback(interactionKey = key, queueIntentBinding = binding))
            val shown = mutableStateOf(true)
            val accepted = mutableListOf<Pair<LibraryPlaybackSelection, PlaybackListAction>>()
            val admittedSources = mutableListOf<() -> Boolean>()
            try {
                runBlocking { client.login("listener", "synthetic") }
                compose.setContent {
                    MaterialTheme {
                        if (shown.value) PlaylistsRoute(client, {}, {},
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
                        queueIntentBinding = if (stale) binding.copy(stateVersion = 8, outputVersion = 2) else binding) }
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
                            queueIntentBinding = binding.copy(stateVersion = 8, outputVersion = 2)) }
                        compose.runOnIdle { assertTrue("Admitted source survives output publication", admittedSources.all { it() }) }
                    }
                    compose.runOnIdle {
                        if (stale) assertTrue("Open More must never refresh a binding", accepted.isEmpty())
                        else {
                            assertEquals(expectedAction, accepted.last().second)
                            val selection = accepted.last().first
                            val context = if (collection) (selection as LibraryPlaybackSelection.Collection).context
                                else (selection as LibraryPlaybackSelection.Track).context!!
                            assertEquals(if (derived) "derived" else "playlist", context.kind)
                            assertEquals("fixture", context.id); assertEquals(5L, context.revision)
                            if (derived) assertEquals("exact-fence", context.fence)
                            if (!collection) {
                                val row = (selection as LibraryPlaybackSelection.Track).track
                                assertEquals("same", row.id)
                                assertTrue(row is LibraryPlaybackTrackRef)
                                if (derived) {
                                    assertEquals("exact-path", row.occurrenceId)
                                    assertEquals("opaque.signature", row.selectionToken)
                                    assertNull(row.entryId)
                                } else assertEquals("exact-entry", row.entryId)
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

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
