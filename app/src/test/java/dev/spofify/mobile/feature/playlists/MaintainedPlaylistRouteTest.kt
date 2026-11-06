package dev.spofify.mobile.feature.playlists

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.PlaybackListAction
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.jsonPrimitive
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

/** Real route + authenticated HTTP fixture. Not installed-device or audible-output acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class MaintainedPlaylistRouteTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    @Test fun unchangedRulesCancelAndAddDismissKeepSavedResultUsableWithoutAnotherRead() = fixture { f ->
        awaitText("1 tracks · 1 with audio")
        compose.onNodeWithText("Edit rules").performScrollTo().performClick()
        awaitText("Back — keep draft")
        compose.onNodeWithText("Back — keep draft").performClick()
        compose.onNodeWithText("Play").performScrollTo().assertIsEnabled()
        assertEquals(1, f.entries.get())
        more("Song 0")
        awaitText("Like song")
        compose.onNodeWithText("Add to playlist").performScrollTo().performClick()
        awaitText("Manual playlists only. Each add saves a separate occurrence, including duplicates.")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Play").performScrollTo().assertIsEnabled()
        assertEquals(1, f.entries.get())
        assertTrue(f.actions.isEmpty())
        assertTrue(f.paths.none { it.contains("/evaluation") || it.contains("/preview") || it.contains("/playback") })
    }

    @Test fun confirmedLikeReadsCurrentRowsOnceAndNeverRenewsHeldActionOrStartsMusic() = fixture { f ->
        awaitText("Song 0")
        more("Song 0")
        awaitText("Like song")
        compose.onNodeWithText("Queue Low").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(PlaybackListAction.QUEUE_LOW, f.actions.single().action)
            assertTrue(f.actions.single().current())
        }
        more("Song 0")
        awaitText("Like song")
        compose.onNodeWithText("Like song").performScrollTo().performClick()
        awaitText("Unlike song")
        compose.waitUntil(5_000) { compose.waitForIdle(); f.entries.get() == 2 }
        compose.onNodeWithText("Play now").performScrollTo().assertIsNotEnabled() // Old menu stays old.
        compose.onNodeWithText("Done").performClick()
        awaitText("Song 1")
        compose.runOnIdle {
            assertEquals(1, f.actions.size)
            assertFalse(f.actions.single().current())
        }
        more("Song 1")
        awaitText("Unlike song")
        compose.onNodeWithText("Queue High").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val selected = f.actions.last().selection as QueueIntentSelection.Track
            assertEquals("fence-1", selected.context?.fence)
            assertEquals("selection-1", selected.track["selectionToken"]?.jsonPrimitive?.content)
            assertEquals("occ-1", selected.track["occurrenceId"]?.jsonPrimitive?.content)
            assertEquals(PlaybackListAction.QUEUE_HIGH, f.actions.last().action)
        }
        assertEquals(1, f.likes.get())
        assertEquals(2, f.entries.get())
        assertTrue(f.paths.none { it.contains("/evaluation") || it.contains("/playback") })
    }

    @Test fun pendingAndFailedReadKeepUsefulRowsButCannotAuthorizePlaybackThenNavigationReadsAgain() = fixture { f ->
        awaitText("Song 0")
        f.resultState = "pending"
        compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.PLAYLISTS) }
        awaitText("1 tracks · 1 with audio · Updating; previous result")
        compose.onNodeWithText("Play").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Song 0").performScrollTo().assertExists()
        more("Song 0")
        awaitText("Like song")
        compose.onNodeWithText("Play now").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Done").performClick()
        f.resultState = "error"
        compose.onNodeWithText("Refresh").performScrollTo().performClick()
        awaitText("1 tracks · 1 with audio · Previous result; update failed")
        compose.onNodeWithText("Play").performScrollTo().assertIsNotEnabled()
        assertTrue(f.actions.isEmpty())
        f.resultState = "ready"
        compose.runOnIdle { f.shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { f.shown.value = true }
        awaitText("1 tracks · 1 with audio")
        compose.onNodeWithText("Play").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(PlaybackListAction.PLAY_NOW, f.actions.single().action) }
        assertEquals(4, f.entries.get())
    }

    @Test fun copyDialogDoesNotBorrowNewFenceAfterConfirmedInputChange() = fixture { f ->
        awaitText("Song 0")
        compose.onNodeWithText("Make editable copy").performScrollTo().performClick()
        awaitText("Save")
        f.likes.incrementAndGet()
        compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.LIKES) }
        awaitText("Playlist changed. Close and choose Make editable copy again after it is current.")
        compose.onAllNodesWithText("Save").assertCountEquals(0)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); f.entries.get() == 2 }
        compose.onNodeWithText("Make editable copy").performScrollTo().performClick()
        awaitText("Save")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { f.copies.get() == 1 }
        assertEquals(1, f.copyBodies.size)
        assertTrue(f.copyBodies.single().contains("\"fence\":\"fence-1\""))
        assertTrue(f.actions.isEmpty())
    }

    @Test fun obsoleteReadCompletionCannotPublishOverNewerConfirmedInputRead() = fixture { f ->
        awaitText("Song 0")
        f.holdRead = f.entries.get() + 1
        compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.LIKES) }
        compose.waitUntil(5_000) { compose.waitForIdle(); f.readStarted.count == 0L }
        try {
            f.likes.incrementAndGet()
            compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.LIKES) }
            awaitText("Song 1")
            f.releaseRead.countDown()
            compose.waitForIdle()
            compose.onAllNodesWithText("Song 0").assertCountEquals(0)
            assertEquals(3, f.entries.get())
            assertTrue(f.actions.isEmpty())
        } finally { f.releaseRead.countDown() }
    }

    private fun more(song: String) = compose.onNodeWithContentDescription("More actions for $song").performScrollTo().performClick()
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private data class Action(val selection: QueueIntentSelection, val action: PlaybackListAction, val current: () -> Boolean)

    private inner class Fixture(val web: MockWebServer) {
        val entries = AtomicInteger()
        val likes = AtomicInteger()
        val copies = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        val copyBodies = CopyOnWriteArrayList<String>()
        val actions = mutableListOf<Action>()
        val shown = mutableStateOf(true)
        lateinit var client: SpofifyClient
        @Volatile var resultState = "ready"
        @Volatile var holdRead = -1
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)

        fun summary(): String = """{"id":"rules","name":"Rules","kind":"derived","revision":7,"countState":"${if (resultState == "ready") "exact" else "stale"}","trackCount":1,"availableCount":1,"evaluationState":"$resultState","capabilities":{"editEntries":false,"addTracks":false,"editDefinition":true,"copyToManual":true}}"""
        fun page(): String {
            val version = likes.get()
            val ready = resultState == "ready"
            return """{"items":[{"occurrenceId":"occ-$version","selectionToken":${if (ready) "\"selection-$version\"" else "null"},"position":0,"track":{"kind":"track","id":"song","name":"Song $version","hasAudio":true}}],"total":1,"availableTotal":1,"limit":40,"revision":7,"fence":"${if (ready) "fence-$version" else ""}","nextCursor":null,"complete":$ready,"evaluationState":"$resultState"}"""
        }
    }

    private fun fixture(body: (Fixture) -> Unit) {
        MockWebServer().use { web ->
            val f = Fixture(web)
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    f.paths += path
                    if (path == "/api/auth/login") return json("""{"accountId":"fixture","username":"listener","role":"user","csrfToken":"csrf"}""")
                        .setHeader("Set-Cookie", "spofify_session=fixture-session; Path=/; HttpOnly; Max-Age=3600")
                    if (request.getHeader("Cookie") != "spofify_session=fixture-session") return MockResponse().setResponseCode(401)
                    return when (path) {
                        "/api/me/playlists" -> json("""{"items":[${if (request.requestUrl!!.queryParameter("kind") == "manual") manual else f.summary()}],"total":1,"limit":40,"offset":0}""")
                        "/api/me/playlists/rules" -> json(f.summary())
                        "/api/me/playlists/rules/entries" -> {
                            val read = f.entries.incrementAndGet()
                            val result = f.page()
                            if (read == f.holdRead) { f.readStarted.countDown(); f.releaseRead.await(5, TimeUnit.SECONDS) }
                            json(result)
                        }
                        "/api/me/playlists/rules/definition" -> json("""{"revision":7,"definition":{"version":1,"sources":[{"slotId":"s_0123456789abcdef0123456789abcdef","kind":"likes"}],"duplicates":"keep","sort":"sourceOrder"}}""")
                        "/api/me/likes/status" -> json("""{"items":[{"trackId":"song","liked":${f.likes.get() > 0}}]}""")
                        "/api/me/likes" -> if (request.method == "PUT" && request.getHeader("X-CSRF-Token") == "csrf") {
                            f.likes.incrementAndGet(); json("{}")
                        } else MockResponse().setResponseCode(405)
                        "/api/me/playlists/rules/copy" -> {
                            f.copyBodies += request.body.readUtf8(); f.copies.incrementAndGet(); json(manual)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            web.start(InetAddress.getByName("127.0.0.1"), 0)
            f.client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            try {
                runBlocking { f.client.login("listener", "synthetic") }
                compose.setContent { MaterialTheme {
                    if (f.shown.value) PlaylistsRoute(f.client, onOpenTrack = {}, onPlayQueue = { _, _, _ -> fail("Passive queue replacement") },
                        onShowTrackActions = {}, initialPlaylistId = "rules",
                        onQueueIntent = { selection, action, _, _, current -> f.actions += Action(selection, action, current) })
                } }
                body(f)
            } finally {
                f.releaseRead.countDown()
                compose.runOnIdle { f.shown.value = false }
                compose.waitForIdle()
                f.client.httpClient.dispatcher.cancelAll()
                f.client.httpClient.connectionPool.evictAll()
                f.client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }
    private val manual = """{"id":"manual","name":"Editable","kind":"manual","revision":1,"trackCount":1,"availableCount":1}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
