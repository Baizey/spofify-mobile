package dev.spofify.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import dev.spofify.mobile.core.PlaybackQueue as PreparedQueue
import dev.spofify.mobile.playback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host-boundary regressions. The fake records dispatch, but only an explicit later completion
 * acknowledges it; queue/service mutation itself belongs to the service bridge tests. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackListActionViewModelTest {
    private class MemoryStore : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }

    private class PendingAction(
        val queue: PreparedQueue,
        val client: SpofifyClient,
        val action: PlaybackListAction,
        val expectedKey: PlaybackInteractionKey?,
        val startTrackId: String?,
        val startEntryId: String?,
        val context: LibraryItem?,
        val isCurrent: () -> Boolean,
        private var callback: ((PlaybackActionResult) -> Unit)?,
    ) {
        var dispatches = 0
            private set
        fun dispatchIfCurrent(): Boolean {
            if (!isCurrent()) return false
            dispatches++
            return true
        }
        fun complete(result: PlaybackActionResult) {
            val deliver = checkNotNull(callback) { "Action already completed" }
            callback = null
            deliver(result)
        }
    }

    private class DelayedPlayback : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState(interactionKey = PlaybackInteractionKey()))
        val actions = mutableListOf<PendingAction>()
        var replacementPlays = 0
            private set
        override fun performPreparedAction(queue: PreparedQueue, client: SpofifyClient,
            action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, startTrackId: String?,
            startEntryId: String?, context: LibraryItem?, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            actions += PendingAction(queue, client, action, expectedKey, startTrackId, startEntryId, context, isCurrent, onResult)
        }
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?,
            shuffle: Boolean, startEntryId: String?) {
            replacementPlays++
            throw AssertionError("List actions must use the acknowledged boundary, without a late replacement Play")
        }
        override fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean) {
            throw AssertionError("Legacy enqueue cannot acknowledge list acceptance")
        }
        override fun connect() = Unit
        override fun release() = Unit
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() { PlaybackRequests.invalidate() }
    }

    private class FetchGate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responded = CountDownLatch(1)
    }

    private class Fixture(
        val model: LibraryViewModel,
        val client: SpofifyClient,
        val graph: AppGraph,
        val playback: DelayedPlayback,
    ) {
        val selected = LibraryItem("track", "same-song", "Selected duplicate", hasAudio = true, entryId = "entry-2")
        val queue = PreparedQueue(listOf(selected.copy(entryId = "entry-1"), selected), total = 2,
            context = LibraryPlaybackContext("playlist", "manual", "Manual playlist"), revision = 7)
        val key get() = requireNotNull(playback.state.value.interactionKey)
        fun prepared(action: PlaybackListAction = PlaybackListAction.ADD_TO_QUEUE,
            preparedQueue: PreparedQueue = queue, startEntryId: String? = selected.entryId) {
            model.preparedListAction(client, client.currentSession()!!.accountId, preparedQueue,
                selected.id, startEntryId, action, if (action == PlaybackListAction.CONTEXTUAL) key else null)
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun loginResponse() = json("""{"accountId":"id","username":"listener","role":"user","csrfToken":"csrf"}""")
        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
    private fun track(entry: String) = """{"kind":"track","id":"same-song","name":"Song","hasAudio":true,"entryId":"$entry"}"""
    private fun queueResponse() = json("""{"items":[${track("entry-1")},${track("entry-2")}],"total":2,"context":{"kind":"playlist","id":"manual","name":"Manual playlist"},"revision":7}""")
    private fun derivedResponse(): MockResponse {
        fun occurrence(id: String) = """{"kind":"track","id":"same-song","name":"Song","hasAudio":true,"occurrenceId":"$id","selectionToken":"returned-$id"}"""
        return json("""{"context":{"kind":"derived","id":"rules","name":"Rules","revision":5,"fence":"fixture-fence"},"items":[${occurrence("occ-1")},${occurrence("occ-2")}],"total":2,"limit":1000,"truncated":false,"revision":5,"fence":"fixture-fence","startOccurrenceId":"occ-2","notice":null}""")
    }

    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue("Timed out waiting for host action", condition())
    }

    private fun fixture(gate: FetchGate? = null, block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/login" -> loginResponse()
                "/api/auth/logout" -> MockResponse().setResponseCode(204)
                "/api/library/albums/album/playback", "/api/me/playlists/manual/playback" -> {
                    gate?.started?.countDown()
                    gate?.release?.await(5, TimeUnit.SECONDS)
                    queueResponse().also { gate?.responded?.countDown() }
                }
                "/api/me/playlists/rules/playback" -> derivedResponse()
                else -> MockResponse().setResponseCode(404)
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AppGraph(app, MemoryStore())
        val store = ViewModelStore()
        try {
            web.start()
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            client.login("listener", "password")
            val playback = DelayedPlayback()
            val model = LibraryViewModel(app, graph, playback)
            store.put("subject", model)
            until { model.state.value.session != null && !model.state.value.busy }
            block(Fixture(model, client, graph, playback))
        } finally {
            gate?.release?.countDown()
            store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain()
        }
    }

    @Test fun enqueueAndPlayNextPreserveManualEntryAndWaitForAcceptance() = fixture { f ->
        for (playNext in listOf(false, true)) {
            f.model.clearNotice()
            val before = f.playback.actions.size
            f.model.enqueueFrom(f.client, f.selected, playNext)
            until { f.playback.actions.size == before + 1 }
            assertTrue(f.model.state.value.preparing)
            val pending = f.playback.actions.last()
            assertEquals(if (playNext) PlaybackListAction.PLAY_NEXT else PlaybackListAction.ADD_TO_QUEUE, pending.action)
            assertSame(f.client, pending.client)
            assertSame(f.selected, pending.queue.items.single())
            assertEquals("same-song", pending.startTrackId)
            assertEquals("entry-2", pending.startEntryId)
            assertNull(pending.expectedKey)
            assertNull("Queued work is not acceptance", f.model.state.value.notice)
            assertTrue(pending.dispatchIfCurrent())
            assertEquals(1, pending.dispatches)
            assertNull("Dispatch is not acceptance", f.model.state.value.notice)
            pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            until { !f.model.state.value.preparing }
            assertEquals("Added to queue", f.model.state.value.notice)
        }
    }

    @Test fun twoManualAddsBeforePreparationRunsBothReachTheAcknowledgedBoundary() = fixture { f ->
        val first = f.selected
        val second = f.selected.copy(entryId = "entry-3")
        f.model.enqueueFrom(f.client, first)
        f.model.enqueueFrom(f.client, second)
        assertTrue("Neither preparation coroutine has run yet", f.playback.actions.isEmpty())
        runCurrent()
        assertEquals("Only the first Add may dispatch before its acceptance", 1, f.playback.actions.size)
        val firstPending = f.playback.actions.single()
        assertEquals("entry-2", firstPending.startEntryId)
        assertTrue(firstPending.dispatchIfCurrent())
        assertTrue(f.model.state.value.preparing)
        assertNull("Independent requests still wait for acceptance", f.model.state.value.notice)
        firstPending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        until { f.playback.actions.size == 2 }
        assertEquals(listOf("entry-2", "entry-3"), f.playback.actions.map { it.startEntryId })
        assertEquals(listOf(first, second), f.playback.actions.map { it.queue.items.single() })
        assertTrue(f.playback.actions.all { it.action == PlaybackListAction.ADD_TO_QUEUE })
        assertTrue("The second request is still awaiting acceptance", f.model.state.value.preparing)
        assertNull("The older result must not replace the newer pending action's feedback", f.model.state.value.notice)
        val secondPending = f.playback.actions.last()
        assertTrue(secondPending.dispatchIfCurrent())
        secondPending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        until { !f.model.state.value.preparing }
        assertEquals("Added to queue", f.model.state.value.notice)
    }

    @Test fun acceptedAddRetiresAnOlderHttpBlockedReplacementPlay() =
        acceptedAddRetiresReplacement(prepared = false)

    @Test fun acceptedPreparedAddRetiresAnOlderHttpBlockedReplacementPlay() =
        acceptedAddRetiresReplacement(prepared = true)

    private fun acceptedAddRetiresReplacement(prepared: Boolean) {
        val gate = FetchGate()
        fixture(gate) { f ->
            val replacement = LibraryItem("album", "album", "Queue Q", coverage = Coverage(inLibrary = 1))
            val addition = f.selected.copy(id = "song-y", name = "Song Y", entryId = "entry-y")
            f.model.play(replacement)
            runCurrent()
            assertTrue("Replacement Q must be awaiting its HTTP response", gate.started.await(5, TimeUnit.SECONDS))
            val replacementGeneration = f.model.state.value.playbackIntentGeneration
            assertTrue(f.model.state.value.preparing)
            assertEquals(0, f.playback.replacementPlays)

            if (prepared) {
                f.model.preparedListAction(f.client, f.client.currentSession()!!.accountId,
                    PreparedQueue(listOf(addition), total = 1), addition.id, addition.entryId,
                    PlaybackListAction.ADD_TO_QUEUE, null)
            } else f.model.enqueueFrom(f.client, addition)
            until { f.playback.actions.size == 1 }
            assertTrue("A later explicit Add must retire the older replacement generation",
                f.model.state.value.playbackIntentGeneration > replacementGeneration)
            val pending = f.playback.actions.single()
            assertEquals(PlaybackListAction.ADD_TO_QUEUE, pending.action)
            assertEquals(addition, pending.queue.items.single())
            assertEquals("song-y", pending.startTrackId)
            assertEquals("entry-y", pending.startEntryId)
            assertTrue(pending.dispatchIfCurrent())
            assertNull(f.model.state.value.notice)
            pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            until { !f.model.state.value.preparing }
            assertEquals("Added to queue", f.model.state.value.notice)

            // Release Q only after Y has been accepted. Drain the HTTP call and main work so
            // an old queue response cannot hide behind the newer action's completed busy flag.
            gate.release.countDown()
            assertTrue(gate.responded.await(5, TimeUnit.SECONDS))
            until { f.client.httpClient.dispatcher.runningCallsCount() == 0 }
            runCurrent()
            assertEquals("Late Q must never replace the accepted Add with Play", 0, f.playback.replacementPlays)
            assertEquals("Only Y may reach the playback action boundary", 1, f.playback.actions.size)
            assertFalse(f.model.state.value.preparing)
            assertEquals("Added to queue", f.model.state.value.notice)
        }
    }

    @Test fun contextualRequestBlockedInHttpIsNotDroppedOrOvertakenByTheNextGesture() {
        val gate = FetchGate()
        fixture(gate) { f ->
            val first = f.selected.copy(entryId = null, albumId = "album", albumName = "Album")
            val second = f.selected.copy(id = "second-song", entryId = null)
            val key = f.key
            f.model.contextualFrom(f.client, first, key)
            runCurrent()
            assertTrue("First selection must be awaiting the synthetic HTTP response",
                gate.started.await(5, TimeUnit.SECONDS))
            assertTrue(f.playback.actions.isEmpty())
            f.model.contextualFrom(f.client, second, key)
            runCurrent()
            assertTrue("The second gesture must not overtake blocked HTTP preparation", f.playback.actions.isEmpty())
            gate.release.countDown()
            until { f.playback.actions.size == 1 }
            val firstPending = f.playback.actions.single()
            assertEquals(first.id, firstPending.startTrackId)
            assertSame(key, firstPending.expectedKey)
            assertTrue("A later gesture alone must not invalidate the earlier mutation", firstPending.dispatchIfCurrent())
            runCurrent()
            assertEquals("The next request waits for actual acceptance, not just HTTP preparation", 1, f.playback.actions.size)
            firstPending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            until { f.playback.actions.size == 2 }
            assertEquals("Preparation latency must not reverse the user's FIFO requests",
                listOf(first.id, second.id), f.playback.actions.map { it.startTrackId })
            val secondPending = f.playback.actions.last()
            assertTrue(f.playback.actions.all { it.action == PlaybackListAction.CONTEXTUAL })
            assertSame(key, secondPending.expectedKey)
            assertTrue(secondPending.dispatchIfCurrent())
            assertTrue(f.model.state.value.preparing)
            assertNull("The newest request has not been acknowledged", f.model.state.value.notice)
            secondPending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            until { !f.model.state.value.preparing }
            assertEquals("Added to queue", f.model.state.value.notice)
        }
    }

    @Test fun everyTypedResultPublishesOnlyAfterTheDelayedCallback() = fixture { f ->
        for (status in PlaybackActionStatus.values()) {
            f.model.clearNotice()
            val before = f.playback.actions.size
            f.model.enqueueFrom(f.client, f.selected)
            until { f.playback.actions.size == before + 1 }
            assertTrue(f.model.state.value.preparing)
            assertTrue(f.playback.actions.last().dispatchIfCurrent())
            assertNull("No optimistic Added/Playing for $status", f.model.state.value.notice)
            val result = PlaybackActionResult(status, if (status == PlaybackActionStatus.LIMITED) 1 else 0)
            f.playback.actions.last().complete(result)
            until { !f.model.state.value.preparing }
            assertEquals(result.userMessage, f.model.state.value.notice)
            if (status !in listOf(PlaybackActionStatus.ADDED, PlaybackActionStatus.PLAYING, PlaybackActionStatus.LIMITED)) {
                assertNotEquals("Added to queue", f.model.state.value.notice)
                assertNotEquals("Playing", f.model.state.value.notice)
            }
        }
    }

    @Test fun contextualSelectionForwardsCapturedKeyAndExactManualOccurrence() = fixture { f ->
        f.model.open(Page.Playlist(PlaylistSummary("manual", "Manual playlist")))
        val key = f.key
        f.model.contextualFrom(f.client, f.selected, key)
        until { f.playback.actions.size == 1 }
        assertTrue(f.model.state.value.preparing)
        val pending = f.playback.actions.single()
        assertEquals(PlaybackListAction.CONTEXTUAL, pending.action)
        assertSame(key, pending.expectedKey)
        assertEquals(listOf("entry-1", "entry-2"), pending.queue.items.map { it.entryId })
        assertEquals("entry-2", pending.startEntryId)
        assertEquals("same-song", pending.startTrackId)
        assertEquals("manual", pending.queue.context?.id)
        assertTrue(pending.dispatchIfCurrent())
        assertNull(f.model.state.value.notice)
        pending.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING, 1))
        until { !f.model.state.value.preparing }
        assertEquals("Playing", f.model.state.value.notice)
    }

    @Test fun contextualSelectionCarriesAlbumContextWithoutUsingUiPlayingFlag() = fixture { f ->
        val item = f.selected.copy(entryId = null, albumId = "album", albumName = "Album")
        val key = f.key
        f.model.contextualFrom(f.client, item, key)
        // A harmless progress/state publication does not replace the binding or select a host action.
        f.playback.state.value = f.playback.state.value.copy(positionMs = 500, isBuffering = true, playWhenReady = true)
        until { f.playback.actions.size == 1 }
        val pending = f.playback.actions.single()
        assertSame(key, pending.expectedKey)
        assertEquals(PlaybackListAction.CONTEXTUAL, pending.action)
        assertEquals(LibraryItem("album", "album", "Album"), pending.context)
        assertNull(f.model.state.value.notice)
    }

    @Test fun contextualSelectionRejectsAnAlreadyReplacedBinding() = fixture { f ->
        val old = f.key
        f.playback.state.value = f.playback.state.value.copy(interactionKey = PlaybackInteractionKey())
        f.model.contextualFrom(f.client, f.selected, old)
        runCurrent()
        assertTrue(f.playback.actions.isEmpty())
        assertNull(f.model.state.value.notice)
    }

    @Test fun contextualSelectionKeepsOriginalBindingAfterOutputChangesDuringPreparation() {
        val gate = FetchGate()
        fixture(gate) { f ->
            val capturedKey = f.key
            f.model.contextualFrom(f.client, f.selected.copy(albumId = "album"), capturedKey)
            runCurrent()
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            f.playback.state.value = f.playback.state.value.copy(interactionKey = PlaybackInteractionKey())
            gate.release.countDown()
            until { f.playback.actions.size == 1 }
            val pending = f.playback.actions.single()
            assertSame("The service must receive the original key, never the replacement output's key",
                capturedKey, pending.expectedKey)
            assertNotSame(f.key, pending.expectedKey)
            assertNull(f.model.state.value.notice)
            // Actual key rejection belongs to the service boundary, whose bridge tests cover
            // mutation. The host must preserve that binding and relay its typed stale result.
            val result = PlaybackActionResult(PlaybackActionStatus.STALE)
            pending.complete(result)
            until { !f.model.state.value.preparing }
            assertEquals(result.userMessage, f.model.state.value.notice)
        }
    }

    @Test fun preparedManualQueueAndSelectedEntryAreForwardedWithoutFlattening() = fixture { f ->
        f.prepared()
        until { f.playback.actions.size == 1 }
        assertTrue(f.model.state.value.preparing)
        val pending = f.playback.actions.single()
        assertSame(f.queue, pending.queue)
        assertEquals(7L, pending.queue.revision)
        assertEquals("manual", pending.queue.context?.id)
        assertEquals("entry-2", pending.startEntryId)
        assertNull(f.model.state.value.notice)
        pending.complete(PlaybackActionResult(PlaybackActionStatus.LIMITED, 1))
        until { !f.model.state.value.preparing }
        assertEquals("Added 1 to queue; queue limited to 1,000 songs.", f.model.state.value.notice)
    }

    @Test fun preparedDerivedSelectionKeepsAuthorityAndNeverUsesOccurrenceAsManualEntryId() = fixture { f ->
        val queue = DerivedPlaylistsApi(f.client).playback("rules", 5, "fixture-fence", "original-click-token").queue
        val state = requireNotNull(queue.preparedState)
        val authority = requireNotNull(queue.selectionAuthority)
        assertEquals("occ-2", state.queue[state.position].occurrenceId)
        f.prepared(PlaybackListAction.CONTEXTUAL, queue, startEntryId = null)
        until { f.playback.actions.size == 1 }
        val pending = f.playback.actions.single()
        assertEquals(PlaybackListAction.CONTEXTUAL, pending.action)
        assertSame(f.key, pending.expectedKey)
        assertSame(queue, pending.queue)
        assertSame(state, pending.queue.preparedState)
        assertSame(authority, pending.queue.selectionAuthority)
        assertTrue(authority.isValidFor(f.client, state))
        assertEquals("original-click-token", authority.selectionTokenFor(f.client, state))
        assertEquals("same-song", pending.startTrackId)
        assertNull(pending.startEntryId)
        assertEquals(listOf("occ-1", "occ-2"), pending.queue.items.map { it.entryId })
        assertTrue("Derived occurrence IDs must not enter the bounded wire manual-entry field",
            state.queue.all { it.entryId.isEmpty() })
        assertNull(f.model.state.value.notice)
    }

    @Test fun derivedSelectionCannotBeReusedAfterEqualValuedReauthentication() = fixture { f ->
        val queue = DerivedPlaylistsApi(f.client).playback("rules", 5, "fixture-fence", "original-click-token").queue
        val original = f.client.currentSession()
        f.client.login("listener", "password")
        assertEquals(original, f.client.currentSession())
        assertNotSame(original, f.client.currentSession())
        f.prepared(preparedQueue = queue, startEntryId = null)
        assertTrue(f.playback.actions.isEmpty())
        assertEquals("This prepared selection expired. Reload the collection and choose again.", f.model.state.value.notice)
    }

    @Test fun callerRouteGuardIsRecheckedAtQueuedContextualDispatchAndCompletion() = fixture { f ->
        var rowStillBound = true
        f.model.contextualFrom(f.client, f.selected, f.key, callerIsCurrent = { rowStillBound })
        until { f.playback.actions.size == 1 }
        assertTrue(f.model.state.value.preparing)
        val pending = f.playback.actions.single()
        assertTrue(pending.isCurrent())
        rowStillBound = false // The screen's nested route changed without changing the host Page.
        assertFalse(pending.isCurrent())
        assertFalse(pending.dispatchIfCurrent())
        assertEquals(0, pending.dispatches)
        pending.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING, 1))
        runCurrent()
        assertNull(f.model.state.value.notice)
    }

    @Test fun callerRouteGuardIsRecheckedAtQueuedPreparedDispatchAndCompletion() = fixture { f ->
        var rowStillBound = true
        f.model.preparedListAction(f.client, f.client.currentSession()!!.accountId, f.queue,
            f.selected.id, f.selected.entryId, PlaybackListAction.ADD_TO_QUEUE, null,
            callerIsCurrent = { rowStillBound })
        until { f.playback.actions.size == 1 }
        assertTrue(f.model.state.value.preparing)
        val pending = f.playback.actions.single()
        assertTrue(pending.isCurrent())
        rowStillBound = false
        assertFalse(pending.isCurrent())
        assertFalse(pending.dispatchIfCurrent())
        assertEquals(0, pending.dispatches)
        pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        runCurrent()
        assertNull(f.model.state.value.notice)
    }

    @Test fun preparedActionRejectsWrongAccountOldIntentAndReplacedContextualKey() = fixture { f ->
        val generation = f.model.state.value.playbackIntentGeneration
        f.model.preparedListAction(f.client, "another-account", f.queue, f.selected.id, f.selected.entryId,
            PlaybackListAction.ADD_TO_QUEUE, null)
        f.model.stopPlayback()
        f.model.preparedListAction(f.client, "id", f.queue, f.selected.id, f.selected.entryId,
            PlaybackListAction.ADD_TO_QUEUE, null, expectedGeneration = generation)
        val oldKey = f.key
        f.playback.state.value = f.playback.state.value.copy(interactionKey = PlaybackInteractionKey())
        f.model.preparedListAction(f.client, "id", f.queue, f.selected.id, f.selected.entryId,
            PlaybackListAction.CONTEXTUAL, oldKey)
        runCurrent()
        assertTrue(f.playback.actions.isEmpty())
        assertNull(f.model.state.value.notice)
    }

    private enum class Invalidation { ROUTE, ACCOUNT, CLIENT, INTENT, EPOCH, ROUTE_ROUND_TRIP }

    private fun staleCompletion(prepared: Boolean, invalidation: Invalidation) = fixture { f ->
        if (prepared) f.prepared() else f.model.enqueueFrom(f.client, f.selected)
        until { f.playback.actions.size == 1 }
        assertTrue(f.model.state.value.preparing)
        val pending = f.playback.actions.single()
        assertTrue("Fresh work must be dispatchable", pending.isCurrent())
        assertNull(f.model.state.value.notice)
        when (invalidation) {
            Invalidation.ROUTE -> f.model.switchTab(Tab.SEARCH)
            Invalidation.ROUTE_ROUND_TRIP -> {
                f.model.switchTab(Tab.SEARCH)
                f.model.switchTab(Tab.HOME)
                assertEquals(Page.Home, f.model.state.value.page)
            }
            Invalidation.ACCOUNT -> {
                // Same account and equal Session values are still a new authentication identity.
                val old = f.client.currentSession()
                f.client.login("listener", "password")
                assertEquals(old, f.client.currentSession())
                assertNotSame(old, f.client.currentSession())
            }
            Invalidation.CLIENT -> {
                val replacement = f.graph.configure(f.client.server)
                replacement.login("listener", "password")
                assertNotSame(f.client, replacement)
            }
            Invalidation.INTENT -> f.model.stopPlayback()
            Invalidation.EPOCH -> PlaybackRequests.invalidate()
        }
        runCurrent()
        assertFalse("Queued work must recheck $invalidation before mutation (prepared=$prepared)", pending.isCurrent())
        assertFalse(pending.dispatchIfCurrent())
        assertEquals("No old-route mutation may be dispatched", 0, pending.dispatches)
        val notice = f.model.state.value.notice
        // Also model a result already accepted before invalidation but delivered afterward.
        pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        runCurrent()
        assertEquals("Old acceptance must not publish after $invalidation (prepared=$prepared)", notice, f.model.state.value.notice)
    }

    @Test fun enqueueCompletionCannotPublishIntoAnotherRoute() = staleCompletion(false, Invalidation.ROUTE)
    @Test fun preparedCompletionCannotPublishIntoAnotherRoute() = staleCompletion(true, Invalidation.ROUTE)
    @Test fun enqueueCompletionCannotPublishAfterReauthentication() = staleCompletion(false, Invalidation.ACCOUNT)
    @Test fun preparedCompletionCannotPublishAfterReauthentication() = staleCompletion(true, Invalidation.ACCOUNT)
    @Test fun enqueueCompletionCannotPublishIntoReplacementClient() = staleCompletion(false, Invalidation.CLIENT)
    @Test fun preparedCompletionCannotPublishIntoReplacementClient() = staleCompletion(true, Invalidation.CLIENT)
    @Test fun enqueueCompletionCannotPublishAfterNewIntent() = staleCompletion(false, Invalidation.INTENT)
    @Test fun preparedCompletionCannotPublishAfterNewIntent() = staleCompletion(true, Invalidation.INTENT)
    @Test fun enqueueCompletionCannotPublishAfterAuthorityEpochChanges() = staleCompletion(false, Invalidation.EPOCH)
    @Test fun preparedCompletionCannotPublishAfterAuthorityEpochChanges() = staleCompletion(true, Invalidation.EPOCH)
    @Test fun enqueueCompletionCannotReviveAfterRouteRoundTrip() = staleCompletion(false, Invalidation.ROUTE_ROUND_TRIP)
    @Test fun preparedCompletionCannotReviveAfterRouteRoundTrip() = staleCompletion(true, Invalidation.ROUTE_ROUND_TRIP)

    @Test fun olderEnqueueAcceptanceCannotPublishWhileANewerActionIsPending() = fixture { f ->
        f.model.enqueueFrom(f.client, f.selected)
        until { f.playback.actions.size == 1 }
        val older = f.playback.actions.single()
        f.model.enqueueFrom(f.client, f.selected.copy(entryId = "entry-3"))
        runCurrent()
        assertEquals("Second dispatch waits for the first callback", 1, f.playback.actions.size)
        assertTrue("A newer Add supersedes feedback, not the older queued mutation", older.dispatchIfCurrent())
        older.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        until { f.playback.actions.size == 2 }
        assertNull("Older acceptance must not publish into the pending newer action", f.model.state.value.notice)
        assertTrue(f.model.state.value.preparing)
        val newer = f.playback.actions.last()
        assertTrue(newer.dispatchIfCurrent())
        val newest = PlaybackActionResult(PlaybackActionStatus.FULL)
        newer.complete(newest)
        until { !f.model.state.value.preparing }
        assertEquals(newest.userMessage, f.model.state.value.notice)
    }

    @Test fun repeatedPreparedAddsDispatchInFifoButOnlyNewestFeedbackPublishes() = fixture { f ->
        f.prepared()
        f.prepared()
        assertTrue("Prepared requests also wait for their lane coroutines", f.playback.actions.isEmpty())
        runCurrent()
        assertEquals("The second prepared action must await first acceptance", 1, f.playback.actions.size)
        val older = f.playback.actions.single()
        assertSame(f.queue, older.queue)
        assertEquals(PlaybackListAction.ADD_TO_QUEUE, older.action)
        assertEquals("entry-2", older.startEntryId)
        assertTrue("A repeated Add must not invalidate the earlier mutation", older.dispatchIfCurrent())
        assertEquals(1, older.dispatches)
        assertTrue(f.model.state.value.preparing)
        assertNull(f.model.state.value.notice)
        older.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        until { f.playback.actions.size == 2 }
        assertNull("An older result must not publish while newer acceptance is pending", f.model.state.value.notice)
        assertTrue(f.model.state.value.preparing)
        val newer = f.playback.actions.last()
        assertSame(f.queue, newer.queue)
        assertEquals(PlaybackListAction.ADD_TO_QUEUE, newer.action)
        assertEquals("entry-2", newer.startEntryId)
        assertTrue(newer.dispatchIfCurrent())
        val newestResult = PlaybackActionResult(PlaybackActionStatus.FULL)
        newer.complete(newestResult)
        until { !f.model.state.value.preparing }
        assertEquals(newestResult.userMessage, f.model.state.value.notice)
    }
}
