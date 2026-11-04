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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production playlist route, not a stand-alone dialog callback fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistMoreHostTest {
    private val effectScheduler = TestCoroutineScheduler()
    private val effectDispatcher = StandardTestDispatcher(effectScheduler, name = "PlaylistMoreHost-effects")
    @get:Rule val compose = createComposeRule(effectContext = effectDispatcher)

    @Test fun contextualMoreKeepsOpeningBindingAcrossHarmlessTransportPublication() = checkBinding(stale = false)
    @Test fun contextualMoreCannotRebindAfterExternalQueueOrOutputChange() = checkBinding(stale = true)
    @Test fun contextualMoreCannotReviveAfterEqualQueueReturnsWithNewBinding() = checkBinding(stale = true, roundTrip = true)

    private fun checkBinding(stale: Boolean, roundTrip: Boolean = false) {
        val preparations = AtomicInteger()
        val requests = ArrayBlockingQueue<String>(32)
        val likeStatusRequests = AtomicInteger()
        val lastLikeStatusRequest = AtomicReference("not dispatched")
        val summary = """{"id":"manual","kind":"manual","name":"Manual fixture","description":"","countState":"exact","trackCount":1,"availableCount":1,"revision":1,"capabilities":{"play":true,"editMetadata":false,"editEntries":false,"editDefinition":false,"delete":false,"copyToManual":false,"addTracks":false}}"""
        val track = """{"kind":"track","id":"song","name":"Fixture song","hasAudio":true}"""
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // Bounded fixture-only trace: no query strings, bodies or credential values.
                    requests.offer("${request.method} ${request.requestUrl!!.encodedPath}")
                    return when (request.requestUrl!!.encodedPath) {
                        "/api/auth/login" -> json("""{"accountId":"fixture","username":"listener","role":"user","csrfToken":"synthetic"}""")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                        "/api/me/playlists" -> json("""{"items":[$summary],"total":1,"offset":0,"limit":40}""")
                        "/api/me/playlists/manual" -> json(summary)
                        "/api/me/playlists/manual/entries" -> json("""{"items":[{"id":"exact-entry","position":0,"track":$track}],"total":1,"offset":0,"limit":40}""")
                        "/api/me/playlists/manual/playback" -> {
                            preparations.incrementAndGet()
                            json("""{"items":[{"kind":"track","id":"song","name":"Fixture song","hasAudio":true,"entryId":"exact-entry"}],"total":1,"limit":1000,"truncated":false,"revision":1,"context":{"kind":"playlist","id":"manual","name":"Manual fixture"}}""")
                        }
                        "/api/me/likes/status" -> {
                            likeStatusRequests.incrementAndGet()
                            lastLikeStatusRequest.set("method=${request.method}, " +
                                "cookieMatches=${request.getHeader("Cookie") == "spofify_session=synthetic"}, " +
                                "csrfMatches=${request.getHeader("X-CSRF-Token") == "synthetic"}, response=200")
                            json("""{"items":[{"trackId":"song","liked":false}]}""")
                        }
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
            val feedback = mutableStateOf(CatalogPlaybackFeedback(isPlaying = true, interactionKey = key))
            val shown = mutableStateOf(true)
            val accepted = mutableListOf<Pair<PlaybackInteractionKey?, String?>>()
            try {
                runBlocking { client.login("listener", "synthetic") }
                compose.setContent {
                    MaterialTheme {
                        if (shown.value) PlaylistsRoute(client, {}, { _, _, _ -> fail("Not an explicit Play") }, {},
                            initialPlaylistId = "manual", playback = feedback.value,
                            onPreparedAction = { _, _, entry, action, binding, current ->
                                assertEquals(PlaybackListAction.CONTEXTUAL, action)
                                assertTrue(current())
                                accepted += binding to entry
                            })
                    }
                }
                compose.waitUntil(5_000) {
                    // V2 synchronizes the owned standard effect dispatcher before querying
                    // semantics; HTTP continuations never run unconfined on OkHttp's worker.
                    compose.waitForIdle()
                    compose.onAllNodesWithContentDescription("More actions for Fixture song").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithContentDescription("More actions for Fixture song").performClick()
                try {
                    compose.waitUntil(5_000) {
                        compose.waitForIdle()
                        compose.onAllNodesWithText("Like song").fetchSemanticsNodes().size == 1
                    }
                } catch (timeout: ComposeTimeoutException) {
                    // Preserve the failure, but distinguish pending IO from a failed read
                    // (shown as Check like) or an unexpected sheet/semantics state.
                    val semantics = runCatching {
                        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                        roots.fetchSemanticsNodes().indices.take(4).joinToString("\n") { index ->
                            "Root $index:\n${roots[index].printToString(maxDepth = 12)}"
                        }.take(24_000)
                    }.getOrElse { "Semantics unavailable: ${it.javaClass.simpleName}: ${it.message}" }
                    throw AssertionError("Like song readiness timed out after 5000 ms; " +
                        "stale=$stale, roundTrip=$roundTrip, requests=$requests, " +
                        "likeStatusRequests=${likeStatusRequests.get()}, lastLikeStatus=${lastLikeStatusRequest.get()}, " +
                        "sessionPresent=${client.currentSession() != null}, effectTime=${effectScheduler.currentTime}, " +
                        "queuedCalls=${client.httpClient.dispatcher.queuedCallsCount()}, " +
                        "runningCalls=${client.httpClient.dispatcher.runningCallsCount()}\n$semantics", timeout)
                }
                compose.onNodeWithText("Add to queue (swipe action)").assertIsEnabled()
                compose.runOnIdle { feedback.value = feedback.value.copy(buffering = true, playWhenReady = true,
                    interactionKey = if (stale) PlaybackInteractionKey() else key) }
                if (roundTrip) {
                    // Same displayed queue/output values do not recreate the original key.
                    compose.runOnIdle { feedback.value = CatalogPlaybackFeedback(isPlaying = true, interactionKey = PlaybackInteractionKey()) }
                }
                compose.onNodeWithText("Add to queue (swipe action)").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    compose.waitForIdle()
                    val actionReady = compose.runOnIdle { stale || accepted.size == 1 }
                    compose.onAllNodesWithText("Add to queue (swipe action)").fetchSemanticsNodes().isEmpty() &&
                        actionReady
                }
                compose.runOnIdle {
                    if (stale) {
                        assertTrue("An open More must not acquire the latest playback key", accepted.isEmpty())
                        assertEquals("Reject before preparing against the new binding", 0, preparations.get())
                    } else {
                        assertEquals(listOf(key to "exact-entry"), accepted)
                        assertEquals(1, preparations.get())
                    }
                }
            } finally {
                compose.runOnIdle { shown.value = false }
                compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll()
                client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
