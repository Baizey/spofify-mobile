package dev.spofify.mobile.feature.playlists

import androidx.activity.ComponentDialog
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
import org.robolectric.shadows.ShadowDialog
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
        awaitText("1 song")
        openPlaylistMore()
        compose.onNodeWithText("Edit rules").performScrollTo().performClick()
        awaitText("Cancel")
        compose.onNodeWithText("Cancel").performClick()
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
        compose.onNodeWithText("Queue").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(PlaybackListAction.QUEUE_HIGH, f.actions.single().action)
            assertTrue(f.actions.single().current())
        }
        more("Song 0")
        awaitText("Like song")
        compose.onNodeWithText("Like song").performScrollTo().performClick()
        awaitText("Unlike song")
        compose.waitUntil(5_000) { compose.waitForIdle(); f.entries.get() == 2 }
        compose.onNodeWithText("Play now").performScrollTo().assertIsNotEnabled() // Old menu stays old.
        dismissActions()
        awaitText("Song 1")
        compose.runOnIdle {
            assertEquals(1, f.actions.size)
            assertFalse(f.actions.single().current())
        }
        more("Song 1")
        awaitText("Unlike song")
        compose.onNodeWithText("Queue").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val selected = f.actions.last().selection as LibraryPlaybackSelection.Track
            assertEquals("fence-1", selected.context?.fence)
            assertEquals("selection-1", selected.track.selectionToken)
            assertEquals("occ-1", selected.track.occurrenceId)
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
        dismissActions()
        f.resultState = "error"
        compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.PLAYLISTS) }
        awaitText("1 tracks · 1 with audio · Previous result; update failed")
        compose.onNodeWithText("Play").performScrollTo().assertIsNotEnabled()
        assertTrue(f.actions.isEmpty())
        f.resultState = "ready"
        compose.runOnIdle { f.shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { f.shown.value = true }
        awaitText("1 song")
        compose.onNodeWithText("Play").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(PlaybackListAction.PLAY_NOW, f.actions.single().action) }
        assertEquals(4, f.entries.get())
        assertTrue(f.paths.none { it.contains("/evaluation") || it.contains("/preview") || it.contains("/playback") })
        assertEquals(0, f.mutations.get())
    }

    @Test fun pendingResultBecomesReadyFromServerStatusWithoutLocalChangeOrRefresh() = fixture(initialState = "pending") { f ->
        awaitText("1 tracks · 1 with audio · Updating; previous result")
        compose.onNodeWithText("Play").assertIsNotEnabled()
        assertEquals(1, f.entries.get())
        f.resultState = "ready"
        compose.runOnIdle { scheduler.advanceTimeBy(2_001) }
        compose.waitUntil(5_000) { compose.waitForIdle(); f.entries.get() == 2 }
        awaitText("1 song")
        compose.onNodeWithText("Play").assertIsEnabled()
        assertEquals(0, f.mutations.get())
        assertTrue(f.paths.none { it.contains("/evaluation") || it.contains("/preview") || it.contains("/playback") })
    }

    @Test fun leavingPendingPlaylistCancelsStatusObservation() = fixture(initialState = "pending") { f ->
        awaitText("1 tracks · 1 with audio · Updating; previous result")
        val reads = f.paths.count { it == "/api/me/playlists/rules" }
        compose.runOnIdle { f.shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { scheduler.advanceTimeBy(20_000) }
        compose.waitForIdle()
        assertEquals(reads, f.paths.count { it == "/api/me/playlists/rules" })
        assertEquals(1, f.entries.get())
        assertEquals(0, f.mutations.get())
    }

    @Test fun failedFetchOffersRetryWithoutRepeatingMutationOrRegenerating() = fixture { f ->
        awaitText("Song 0")
        f.failSummaryReads = true
        compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.PLAYLISTS) }
        awaitText(readFailure)
        compose.onNodeWithText("Retry loading playlist").assertIsEnabled()
        compose.onNodeWithText("Song 0").assertExists()
        f.failSummaryReads = false
        compose.onNodeWithText("Retry loading playlist").performClick()
        awaitText("1 song")
        compose.onAllNodesWithText("Retry loading playlist").assertCountEquals(0)
        assertEquals(0, f.mutations.get())
        assertTrue(f.paths.none { it.contains("/evaluation") || it.contains("/preview") || it.contains("/playback") })
    }

    @Test fun copyDialogDoesNotBorrowNewFenceAfterConfirmedInputChange() = fixture { f ->
        awaitText("Song 0")
        openPlaylistMore()
        compose.onNodeWithText("Make editable copy").performScrollTo().performClick()
        awaitText("Create")
        f.likes.incrementAndGet()
        compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.LIKES) }
        awaitText("Playlist changed. Reopen copy.")
        compose.onAllNodesWithText("Create").assertCountEquals(0)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); f.entries.get() == 2 }
        openPlaylistMore()
        compose.onNodeWithText("Make editable copy").performScrollTo().performClick()
        awaitText("Create")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { f.copies.get() == 1 }
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithText("Copy playlist").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Created editable playlist:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Original rules", substring = true).assertDoesNotExist()
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

    @Test fun uncertainMutationStaysBlockedAfterFailedVerificationAndPassiveRefreshUntilExplicitSuccess() = fixture { f ->
        makeMetadataWriteUncertain(f)
        f.failSummaryReads = true
        compose.onNodeWithText("Verify saved playlist").performClick()
        awaitText(readFailure)
        assertWriteControlsBlocked(f)
        compose.onNodeWithText("Song 0").performScrollTo().assertExists()

        // A successful passive Like refresh may update display rows, not reconcile the write.
        f.failSummaryReads = false
        f.likes.incrementAndGet()
        compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.LIKES) }
        awaitText("Song 1")
        assertWriteControlsBlocked(f)
        assertEquals(1, f.mutations.get())

        compose.onNodeWithText("Verify saved playlist").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(readFailure).fetchSemanticsNodes().isEmpty()
        }
        openPlaylistMore()
        compose.onNodeWithText("Edit details").performScrollTo().assertIsEnabled()
        dismissActions()
        assertEquals("Reconciliation is read-only; the uncertain write is never replayed", 1, f.mutations.get())
    }

    @Test fun cancelledExplicitVerificationCannotPassWriteUnlockToItsPassiveSuccessor() = fixture { f ->
        makeMetadataWriteUncertain(f)
        f.holdRead = f.entries.get() + 1
        compose.onNodeWithText("Verify saved playlist").performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); f.readStarted.count == 0L }
        try {
            f.likes.incrementAndGet()
            compose.runOnIdle { f.client.notifyPersonalChange(PersonalCollection.LIKES) }
            awaitText("Song 1")
            f.releaseRead.countDown()
            compose.waitForIdle()
            assertWriteControlsBlocked(f)
        } finally { f.releaseRead.countDown() }
    }

    private fun makeMetadataWriteUncertain(f: Fixture) {
        awaitText("Song 0")
        openPlaylistMore()
        compose.onNodeWithText("Edit details").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        awaitText("Verify saved playlist")
        assertEquals("The production metadata PATCH must receive the intended uncertain 503", 1, f.uncertainMetadataResponses.get())
        assertEquals("No unexpected mutation method may substitute for that PATCH", 1, f.mutations.get())
    }

    private fun assertWriteControlsBlocked(f: Fixture) {
        openPlaylistMore()
        compose.onNodeWithText("Edit details").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Delete").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Edit rules").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Make editable copy").performScrollTo().assertIsNotEnabled()
        dismissActions()
        compose.onAllNodesWithText("Save").assertCountEquals(0)
        assertEquals("Failed/cancelled verification cannot admit a second mutation", 1, f.mutations.get())
    }

    private fun dismissActions() {
        val dialog = compose.runOnIdle { ShadowDialog.getLatestDialog() as ComponentDialog }
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private val readFailure = "Playlist could not load."
    private fun openPlaylistMore() = compose.onNodeWithContentDescription("More actions for Rules").performScrollTo().performClick()
    private fun more(song: String) = compose.onNodeWithContentDescription("More actions for $song").performScrollTo().performClick()
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private data class Action(val selection: LibraryPlaybackSelection, val action: PlaybackListAction, val current: () -> Boolean)

    private inner class Fixture(val web: MockWebServer) {
        val entries = AtomicInteger()
        val likes = AtomicInteger()
        val copies = AtomicInteger()
        val mutations = AtomicInteger()
        val uncertainMetadataResponses = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        val copyBodies = CopyOnWriteArrayList<String>()
        val actions = mutableListOf<Action>()
        val shown = mutableStateOf(true)
        lateinit var client: SpofifyClient
        @Volatile var resultState = "ready"
        @Volatile var failSummaryReads = false
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

    private fun fixture(initialState: String = "ready", body: (Fixture) -> Unit) {
        MockWebServer().use { web ->
            val f = Fixture(web)
            f.resultState = initialState
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    f.paths += path
                    if (path == "/api/auth/login") return json("""{"accountId":"fixture","username":"listener","role":"user","csrfToken":"csrf"}""")
                        .setHeader("Set-Cookie", "spofify_session=fixture-session; Path=/; HttpOnly; Max-Age=3600")
                    if (request.getHeader("Cookie") != "spofify_session=fixture-session") return MockResponse().setResponseCode(401)
                    return when (path) {
                        "/api/me/playlists" -> json("""{"items":[${if (request.requestUrl!!.queryParameter("kind") == "manual") manual else f.summary()}],"total":1,"limit":40,"offset":0}""")
                        "/api/me/playlists/rules" -> when (request.method) {
                            "PATCH" -> {
                                f.mutations.incrementAndGet()
                                f.uncertainMetadataResponses.incrementAndGet()
                                json("""{"error":{"code":"UNAVAILABLE","message":"Synthetic uncertain response"}}""").setResponseCode(503)
                            }
                            "GET" -> if (f.failSummaryReads) MockResponse().setResponseCode(503) else json(f.summary())
                            else -> { f.mutations.incrementAndGet(); MockResponse().setResponseCode(405) }
                        }
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
                    if (f.shown.value) PlaylistsRoute(f.client, onOpenTrack = {},
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
