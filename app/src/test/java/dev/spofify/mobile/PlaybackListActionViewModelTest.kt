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
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.jsonPrimitive

/** Captured raw selection -> acknowledged control boundary; no HTTP preparation on controller.
 * These JVM tests do not establish native rendering/audio or cross-endpoint acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackListActionViewModelTest {
    private class MemoryStore : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private data class Pending(val selection: QueueIntentSelection, val action: PlaybackListAction,
        val key: PlaybackInteractionKey?, val binding: QueueIntentBinding?, val current: () -> Boolean,
        val complete: (PlaybackActionResult) -> Unit)
    private data class PreparedPending(val queue: PreparedQueue, val startTrackId: String?, val startEntryId: String?,
        val key: PlaybackInteractionKey?, val current: () -> Boolean, val complete: (PlaybackActionResult) -> Unit)
    private class FetchGate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responded = CountDownLatch(1)
    }
    private class Controls : PlaybackControls {
        val key = PlaybackInteractionKey()
        val binding = QueueIntentBinding("phone", "receiver", 5, 1, "event-A")
        override val state = MutableStateFlow(PlaybackState(interactionKey = key, queueIntentBinding = binding, canQueueIntent = true))
        val pending = mutableListOf<Pending>()
        var prepared: PreparedQueue? = null
        var selectedEntry: String? = null
        var holdPrepared = false
        var replacementPlays = 0
        val preparedPending = mutableListOf<PreparedPending>()
        override fun queueIntent(selection: QueueIntentSelection, client: SpofifyClient, action: PlaybackListAction,
            expectedKey: PlaybackInteractionKey?, expectedBinding: QueueIntentBinding?, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            pending += Pending(selection, action, expectedKey, expectedBinding, isCurrent, onResult)
        }
        override fun performPreparedAction(queue: PreparedQueue, client: SpofifyClient, action: PlaybackListAction,
            expectedKey: PlaybackInteractionKey?, startTrackId: String?, startEntryId: String?, context: LibraryItem?,
            isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            prepared = queue; selectedEntry = startEntryId
            preparedPending += PreparedPending(queue, startTrackId, startEntryId, expectedKey, isCurrent, onResult)
            if (!holdPrepared) onResult(PlaybackActionResult(PlaybackActionStatus.NO_OP))
        }
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?, shuffle: Boolean,
            startEntryId: String?) {
            replacementPlays++
            error("No sampled Play fallback")
        }
        override fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean) = error("No legacy enqueue fallback")
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
    private data class Fixture(val model: LibraryViewModel, val client: SpofifyClient, val graph: AppGraph,
        val controls: Controls, val playbackReads: AtomicInteger, val loginAccount: AtomicReference<String>) {
        val row = LibraryItem("track", "same", "Duplicate", hasAudio = true, albumId = "album")
        fun submit(action: PlaybackListAction = PlaybackListAction.QUEUE_HIGH, item: LibraryItem = row) =
            model.queueIntentFrom(client, item, action)
        val manualQueue get() = PreparedQueue(listOf(row.copy(entryId = "one"), row.copy(entryId = "two")), total = 2,
            context = LibraryPlaybackContext("playlist", "manual", "Manual"), revision = 7)
        fun prepare(queue: PreparedQueue = manualQueue, callerIsCurrent: () -> Boolean = { true }) {
            controls.holdPrepared = true
            model.preparedListAction(client, client.currentSession()!!.accountId, queue, row.id,
                if (queue.context?.kind == "derived") null else "two", PlaybackListAction.ADD_TO_QUEUE, null,
                callerIsCurrent = callerIsCurrent)
        }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent(); assertTrue("Timed out awaiting VM", condition())
    }
    private fun fixture(gate: FetchGate? = null, block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val web = MockWebServer()
        val reads = AtomicInteger()
        val account = AtomicReference("id")
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.encodedPath.endsWith("/playback")) reads.incrementAndGet()
                return when (request.requestUrl!!.encodedPath) {
                    "/api/auth/login" -> json("""{"accountId":"${account.get()}","username":"listener","role":"user","csrfToken":"csrf"}""")
                        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                    "/api/auth/logout" -> MockResponse().setResponseCode(204)
                    "/api/library/albums/album/playback" -> {
                        gate?.started?.countDown()
                        if (gate != null) check(gate.release.await(5, TimeUnit.SECONDS)) { "Owned synthetic playback gate timed out" }
                        json("""{"items":[{"kind":"track","id":"old-play","name":"Old Play","hasAudio":true}],"total":1}""")
                            .also { gate?.responded?.countDown() }
                    }
                    "/api/me/playlists/rules/playback" -> json("""{"context":{"kind":"derived","id":"rules","name":"Rules","revision":5,"fence":"fixture-fence"},"items":[{"kind":"track","id":"same","name":"Song","hasAudio":true,"occurrenceId":"occ-1","selectionToken":"returned-1"},{"kind":"track","id":"same","name":"Song","hasAudio":true,"occurrenceId":"occ-2","selectionToken":"returned-2"}],"total":2,"limit":1000,"truncated":false,"revision":5,"fence":"fixture-fence","startOccurrenceId":"occ-2","notice":null}""")
                    else -> json("""{"items":[],"total":0,"revision":1}""")
                }
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AppGraph(app, MemoryStore())
        val store = ViewModelStore()
        try {
            web.start(); val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            client.login("listener", "synthetic")
            val controls = Controls(); val model = LibraryViewModel(app, graph, controls)
            store.put("subject", model)
            until { model.state.value.session != null && !model.state.value.restoring }
            block(Fixture(model, client, graph, controls, reads, account))
        } finally { gate?.release?.countDown(); store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain() }
    }
    @Test fun fourRawIntentsPreserveOriginalBindingAndDoNotPrepareOnController() = fixture { f ->
        for (action in listOf(PlaybackListAction.QUEUE_LOW, PlaybackListAction.QUEUE_HIGH,
            PlaybackListAction.PLAY_NEXT, PlaybackListAction.PLAY_NOW)) {
            f.model.clearNotice(); val count = f.controls.pending.size + 1
            f.submit(action); until { f.controls.pending.size == count }
            val pending = f.controls.pending.last()
            assertEquals(action, pending.action); assertSame(f.controls.key, pending.key)
            assertEquals(f.controls.binding, pending.binding)
            assertTrue(pending.selection is QueueIntentSelection.Track)
            assertTrue(f.model.state.value.preparing); assertNull(f.model.state.value.notice)
            pending.complete(queueIntentActionResult(QueueIntentResult("queued", 1, 6, "high", "unchanged", null, ""), action))
            until { !f.model.state.value.preparing }
            assertEquals(if (action == PlaybackListAction.PLAY_NOW) "Selection accepted." else "Queued", f.model.state.value.notice)
        }
        assertEquals(0, f.playbackReads.get())
    }
    @Test fun rowHighIsOneTrackButAlbumHighIsWholeBoundedContext() = fixture { f ->
        f.submit(); runCurrent(); val row = f.controls.pending.single()
        assertTrue(row.selection is QueueIntentSelection.Track)
        row.complete(PlaybackActionResult(PlaybackActionStatus.NO_OP)); runCurrent()
        f.submit(item = LibraryItem("album", "album", "Album", coverage = Coverage(inLibrary = 20)))
        runCurrent(); val collection = f.controls.pending.last().selection as QueueIntentSelection.Collection
        assertEquals(PlaybackContext("album", "album", "Album"), collection.context)
        assertEquals(0, f.playbackReads.get())
    }
    @Test fun committedRawLocalTicketsFollowOnlyExactPredecessorKeyAndBindingDespiteTransportChurn() = fixture { f ->
        f.submit(); f.submit(item = f.row.copy(id = "Y")); runCurrent()
        assertEquals(1, f.controls.pending.size)
        val first = f.controls.pending.single()
        val successorKey = PlaybackInteractionKey(); val successorBinding = f.controls.binding.copy(revision = 6)
        f.controls.state.value = f.controls.state.value.copy(interactionKey = successorKey,
            queueIntentBinding = successorBinding, transportInteractionKey = PlaybackInteractionKey())
        first.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1,
            PlaybackAdditiveTransition(f.controls.key, successorKey, PlaybackListActionTickets.committedThrough,
                f.controls.binding, successorBinding)))
        until { f.controls.pending.size == 2 }
        val second = f.controls.pending.last()
        assertSame(successorKey, second.key); assertEquals(successorBinding, second.binding)
        assertTrue(second.current())
        second.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)); runCurrent()
    }
    @Test fun remoteTerminalDetailCannotRefreshAnotherAlreadyCommittedBinding() = fixture { f ->
        f.submit(); f.submit(item = f.row.copy(id = "Y")); runCurrent()
        val first = f.controls.pending.single()
        f.controls.state.value = f.controls.state.value.copy(interactionKey = PlaybackInteractionKey(),
            queueIntentBinding = f.controls.binding.copy(revision = 6))
        first.complete(queueIntentActionResult(QueueIntentResult("queued", 1, 6, "high", "unchanged", null, ""), first.action))
        until { f.controls.pending.size == 2 }
        assertSame(f.controls.key, f.controls.pending.last().key)
        assertEquals(f.controls.binding, f.controls.pending.last().binding)
    }
    @Test fun receiptWithDifferentBindingCannotUpgradeCommittedLocalTicket() = fixture { f ->
        f.submit(); f.submit(item = f.row.copy(id = "Y")); runCurrent()
        f.controls.pending.single().complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1,
            PlaybackAdditiveTransition(f.controls.key, PlaybackInteractionKey(), PlaybackListActionTickets.committedThrough,
                f.controls.binding.copy(revision = 4), f.controls.binding.copy(revision = 6))))
        until { f.controls.pending.size == 2 }
        assertSame(f.controls.key, f.controls.pending.last().key)
        assertEquals(f.controls.binding, f.controls.pending.last().binding)
    }
    @Test fun pausedAH1LowBReceiptIsQueuedNotStartedOrPlaying() = fixture { f ->
        f.submit(PlaybackListAction.QUEUE_LOW, LibraryItem("album", "B", "B", coverage = Coverage(inLibrary = 2)))
        runCurrent(); val pending = f.controls.pending.single()
        val result = QueueIntentResult("queued", 2, 6, "low", "unchanged", null, "")
        pending.complete(queueIntentActionResult(result, pending.action)); runCurrent()
        assertEquals("Queued 2 songs.", f.model.state.value.notice)
        assertFalse(f.controls.state.value.isPlaying)
        assertNull(result.playbackKey)
    }
    @Test fun acceptedBlockedMediaAndSupersededResultsAreRetainedWithoutReplay() = fixture { f ->
        for (playback in listOf("blocked", "media", "superseded")) {
            f.submit(PlaybackListAction.PLAY_NOW); runCurrent()
            val pending = f.controls.pending.last()
            val detail = QueueIntentResult(if (playback == "superseded") "queued" else playback, 2, 6,
                "replace", playback, if (playback == "superseded") null else 4, "")
            val result = queueIntentActionResult(detail, pending.action)
            assertSame(detail, result.queueIntentResult); assertEquals(2, result.acceptedCount)
            val count = f.controls.pending.size
            pending.complete(result); runCurrent()
            assertEquals(count, f.controls.pending.size)
            assertTrue(f.model.state.value.notice!!.contains("accepted"))
            assertFalse(f.model.state.value.notice!!.contains("Playback started"))
        }
    }
    @Test fun unknownControlsFailClosedInsteadOfLegacyFallback() = fixture { f ->
        f.submit(); runCurrent()
        f.controls.pending.single().complete(PlaybackActionResult(PlaybackActionStatus.UNSUPPORTED)); runCurrent()
        assertTrue(f.model.state.value.notice!!.contains("Update"))
    }
    @Test fun manualPreparedCompatibilityRetainsExactQueueAndDuplicateEntry() = fixture { f ->
        val queue = PreparedQueue(listOf(f.row.copy(entryId = "one"), f.row.copy(entryId = "two")), total = 2,
            context = LibraryPlaybackContext("playlist", "manual", "Manual"), revision = 7)
        f.model.preparedListAction(f.client, "id", queue, "same", "two", PlaybackListAction.ADD_TO_QUEUE, null)
        runCurrent(); assertSame(queue, f.controls.prepared); assertEquals("two", f.controls.selectedEntry)
    }
    @Test fun heldRawActionRetiresAcrossRouteABAOutputEpochAndSessionChanges() = fixture { f ->
        f.submit(); runCurrent(); val pending = f.controls.pending.single()
        assertTrue(pending.current())
        f.model.switchTab(Tab.SEARCH); f.model.switchTab(Tab.HOME)
        assertFalse(pending.current())
        pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)); runCurrent()
        assertNull(f.model.state.value.notice)
        f.submit(); runCurrent(); val afterRoute = f.controls.pending.last()
        PlaybackRequests.invalidate(); assertFalse(afterRoute.current())
        afterRoute.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)); runCurrent()
        assertNull(f.model.state.value.notice)
        f.submit(); runCurrent(); val beforeOutputChange = f.controls.pending.last()
        val capturedBinding = beforeOutputChange.binding
        f.controls.state.value = f.controls.state.value.copy(interactionKey = PlaybackInteractionKey(),
            queueIntentBinding = f.controls.binding.copy(outputDeviceId = "replacement", receiverId = "replacement_receiver"))
        assertSame(f.controls.key, beforeOutputChange.key)
        assertEquals("Output authority must be rejected downstream, never refreshed by VM", capturedBinding, beforeOutputChange.binding)
        val stale = PlaybackActionResult(PlaybackActionStatus.STALE)
        beforeOutputChange.complete(stale); runCurrent()
        assertEquals(stale.userMessage, f.model.state.value.notice)
        f.model.clearNotice()
        f.submit(); runCurrent(); val beforeSessionChange = f.controls.pending.last()
        val oldSession = f.client.currentSession()
        f.client.login("listener", "synthetic")
        assertEquals(oldSession, f.client.currentSession()); assertNotSame(oldSession, f.client.currentSession())
        assertFalse(beforeSessionChange.current())
        beforeSessionChange.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)); runCurrent()
        assertNull(f.model.state.value.notice)
    }
    private data class Held(val current: () -> Boolean, val complete: (PlaybackActionResult) -> Unit) {
        var dispatches = 0
        fun dispatchIfCurrent(): Boolean {
            if (!current()) return false
            dispatches++
            return true
        }
    }
    private fun TestScope.admit(f: Fixture, prepared: Boolean, caller: () -> Boolean = { true }): Held {
        if (prepared) {
            val count = f.controls.preparedPending.size + 1
            f.prepare(callerIsCurrent = caller)
            until { f.controls.preparedPending.size == count }
            return f.controls.preparedPending.last().let { Held(it.current, it.complete) }
        }
        val count = f.controls.pending.size + 1
        f.model.queueIntentFrom(f.client, f.row, PlaybackListAction.QUEUE_HIGH, callerIsCurrent = caller)
        until { f.controls.pending.size == count }
        return f.controls.pending.last().let { Held(it.current, it.complete) }
    }
    private enum class Invalidation { ROUTE, ROUTE_ABA, CLIENT, NEW_INTENT, REAUTH, SESSION, ACCOUNT, EPOCH, STOP, SIGN_OUT }
    private fun staleCompletionMatrix(prepared: Boolean, invalidation: Invalidation) = fixture { f ->
        val held = admit(f, prepared)
        assertTrue(held.current()); assertTrue(f.model.state.value.preparing)
        assertNull("Admission is not acceptance", f.model.state.value.notice)
        when (invalidation) {
            Invalidation.ROUTE -> f.model.switchTab(Tab.SEARCH)
            Invalidation.ROUTE_ABA -> {
                f.model.switchTab(Tab.SEARCH); f.model.switchTab(Tab.HOME)
                assertEquals(Page.Home, f.model.state.value.page)
            }
            Invalidation.CLIENT -> {
                val replacement = f.graph.configure(f.client.server)
                replacement.login("listener", "synthetic")
                assertNotSame(f.client, replacement)
            }
            Invalidation.NEW_INTENT -> f.submit(PlaybackListAction.PLAY_NOW, f.row.copy(id = "new-now"))
            Invalidation.REAUTH -> {
                val old = f.client.currentSession()
                f.client.login("listener", "synthetic")
                assertEquals("Equal values do not reuse session identity", old, f.client.currentSession())
                assertNotSame(old, f.client.currentSession())
            }
            Invalidation.SESSION -> { f.client.clearSession(); assertNull(f.client.currentSession()) }
            Invalidation.ACCOUNT -> {
                f.loginAccount.set("other-account")
                f.client.login("listener", "synthetic")
                assertEquals("other-account", f.client.currentSession()!!.accountId)
            }
            Invalidation.EPOCH -> PlaybackRequests.invalidate()
            Invalidation.STOP -> f.model.stopPlayback()
            Invalidation.SIGN_OUT -> { f.model.logout(); until { f.model.state.value.session == null } }
        }
        runCurrent()
        assertFalse("Old $prepared work must lose its $invalidation origin before mutation", held.current())
        assertFalse(held.dispatchIfCurrent())
        assertEquals(0, held.dispatches)
        val noticeAfterInvalidation = f.model.state.value.notice
        held.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        runCurrent()
        assertEquals("Late accepted detail must not publish after $invalidation (prepared=$prepared)",
            noticeAfterInvalidation, f.model.state.value.notice)
        assertEquals(0, f.controls.replacementPlays)
    }
    @Test fun rawCompletionCannotPublishIntoAnotherRoute() = staleCompletionMatrix(false, Invalidation.ROUTE)
    @Test fun preparedCompletionCannotPublishIntoAnotherRoute() = staleCompletionMatrix(true, Invalidation.ROUTE)
    @Test fun rawCompletionCannotReviveAfterUnsampledRouteABA() = staleCompletionMatrix(false, Invalidation.ROUTE_ABA)
    @Test fun preparedCompletionCannotReviveAfterUnsampledRouteABA() = staleCompletionMatrix(true, Invalidation.ROUTE_ABA)
    @Test fun rawCompletionCannotPublishIntoReplacementClient() = staleCompletionMatrix(false, Invalidation.CLIENT)
    @Test fun preparedCompletionCannotPublishIntoReplacementClient() = staleCompletionMatrix(true, Invalidation.CLIENT)
    @Test fun rawCompletionCannotPublishAfterNewNowIntent() = staleCompletionMatrix(false, Invalidation.NEW_INTENT)
    @Test fun preparedCompletionCannotPublishAfterNewNowIntent() = staleCompletionMatrix(true, Invalidation.NEW_INTENT)
    @Test fun rawCompletionCannotPublishAfterEqualValuedReauthentication() = staleCompletionMatrix(false, Invalidation.REAUTH)
    @Test fun preparedCompletionCannotPublishAfterEqualValuedReauthentication() = staleCompletionMatrix(true, Invalidation.REAUTH)
    @Test fun rawCompletionCannotPublishAfterSessionClearing() = staleCompletionMatrix(false, Invalidation.SESSION)
    @Test fun preparedCompletionCannotPublishAfterSessionClearing() = staleCompletionMatrix(true, Invalidation.SESSION)
    @Test fun rawCompletionCannotPublishAfterAccountReplacement() = staleCompletionMatrix(false, Invalidation.ACCOUNT)
    @Test fun preparedCompletionCannotPublishAfterAccountReplacement() = staleCompletionMatrix(true, Invalidation.ACCOUNT)
    @Test fun rawCompletionCannotPublishAfterAuthorityEpochChanges() = staleCompletionMatrix(false, Invalidation.EPOCH)
    @Test fun preparedCompletionCannotPublishAfterAuthorityEpochChanges() = staleCompletionMatrix(true, Invalidation.EPOCH)
    @Test fun rawCompletionCannotPublishAfterStop() = staleCompletionMatrix(false, Invalidation.STOP)
    @Test fun preparedCompletionCannotPublishAfterStop() = staleCompletionMatrix(true, Invalidation.STOP)
    @Test fun rawCompletionCannotPublishAfterSignOut() = staleCompletionMatrix(false, Invalidation.SIGN_OUT)
    @Test fun preparedCompletionCannotPublishAfterSignOut() = staleCompletionMatrix(true, Invalidation.SIGN_OUT)

    private fun callerGuardAtDispatchAndCompletion(prepared: Boolean) = fixture { f ->
        var callerCurrent = true
        if (prepared) f.prepare(callerIsCurrent = { callerCurrent })
        else f.model.queueIntentFrom(f.client, f.row, PlaybackListAction.QUEUE_HIGH, callerIsCurrent = { callerCurrent })
        callerCurrent = false // Before the preparation coroutine/dispatch can run.
        runCurrent()
        assertTrue(f.controls.pending.isEmpty()); assertTrue(f.controls.preparedPending.isEmpty())
        assertFalse(f.model.state.value.preparing)
        assertNull(f.model.state.value.notice)
        callerCurrent = true
        val held = admit(f, prepared) { callerCurrent }
        assertTrue(held.current())
        callerCurrent = false // After boundary admission, before serialized mutation/completion.
        assertFalse(held.dispatchIfCurrent()); assertEquals(0, held.dispatches)
        held.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING, 1)); runCurrent()
        assertFalse(f.model.state.value.preparing)
        assertNull("An accepted late callback cannot borrow a fresh caller lifetime", f.model.state.value.notice)
    }
    @Test fun rawCallerGuardIsRecheckedBeforeDispatchAndAtLateCompletion() = callerGuardAtDispatchAndCompletion(false)
    @Test fun preparedCallerGuardIsRecheckedBeforeDispatchAndAtLateCompletion() = callerGuardAtDispatchAndCompletion(true)

    private fun fifoAndNewestFeedback(prepared: Boolean) = fixture { f ->
        val queue = f.manualQueue
        if (prepared) { f.prepare(queue); f.prepare(queue) }
        else { f.submit(); f.submit(item = f.row.copy(id = "Y")) }
        assertTrue(f.controls.pending.isEmpty()); assertTrue(f.controls.preparedPending.isEmpty())
        runCurrent()
        val first = if (prepared) {
            assertEquals(1, f.controls.preparedPending.size)
            f.controls.preparedPending.single().let { assertSame(queue, it.queue); Held(it.current, it.complete) }
        } else {
            assertEquals(1, f.controls.pending.size)
            f.controls.pending.single().let { Held(it.current, it.complete) }
        }
        assertTrue("A newer additive request cannot cancel its predecessor", first.dispatchIfCurrent())
        assertEquals(1, first.dispatches)
        first.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        until { if (prepared) f.controls.preparedPending.size == 2 else f.controls.pending.size == 2 }
        assertNull("Older acceptance cannot replace the newer pending action's feedback", f.model.state.value.notice)
        assertTrue(f.model.state.value.preparing)
        val second = if (prepared) f.controls.preparedPending.last().let {
            assertSame(queue, it.queue); assertEquals("two", it.startEntryId); Held(it.current, it.complete)
        } else f.controls.pending.last().let {
            assertEquals(listOf("same", "Y"), f.controls.pending.map { p ->
                (p.selection as QueueIntentSelection.Track).track.getValue("id").jsonPrimitive.content
            })
            Held(it.current, it.complete)
        }
        assertTrue(second.dispatchIfCurrent())
        val latest = PlaybackActionResult(PlaybackActionStatus.FULL)
        second.complete(latest); until { !f.model.state.value.preparing }
        assertEquals(latest.userMessage, f.model.state.value.notice)
        assertEquals("Raw receiver preparation never fetches controller context", 0, f.playbackReads.get())
    }
    @Test fun rawHeldReceiverPreparationPreservesFifoAndOnlyNewestFeedbackPublishes() = fifoAndNewestFeedback(false)
    @Test fun repeatedPreparedAddsPreserveFifoAndOnlyNewestFeedbackPublishes() = fifoAndNewestFeedback(true)

    private fun retireOlderHttpReplacement(prepared: Boolean) {
        val gate = FetchGate()
        fixture(gate) { f ->
            f.model.play(LibraryItem("album", "album", "Old replacement", coverage = Coverage(inLibrary = 1)))
            runCurrent(); assertTrue("Old replacement is held in actual HTTP", gate.started.await(5, TimeUnit.SECONDS))
            val oldGeneration = f.model.state.value.playbackIntentGeneration
            val addition = admit(f, prepared)
            assertTrue(f.model.state.value.playbackIntentGeneration > oldGeneration)
            assertTrue(addition.dispatchIfCurrent())
            addition.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            until { !f.model.state.value.preparing }
            val acceptedNotice = f.model.state.value.notice
            assertEquals("Added to queue", acceptedNotice)
            gate.release.countDown()
            assertTrue(gate.responded.await(5, TimeUnit.SECONDS))
            until { f.client.httpClient.dispatcher.runningCallsCount() == 0 }
            runCurrent()
            assertEquals("Late old Play must not replace accepted additions", 0, f.controls.replacementPlays)
            assertEquals(1, f.controls.pending.size + f.controls.preparedPending.size)
            assertEquals(acceptedNotice, f.model.state.value.notice)
            assertFalse(f.model.state.value.preparing)
        }
    }
    @Test fun admittedRawAddRetiresOlderReplacementPlayBlockedInHttp() = retireOlderHttpReplacement(false)
    @Test fun admittedPreparedAddRetiresOlderReplacementPlayBlockedInHttp() = retireOlderHttpReplacement(true)

    @Test fun preparedDerivedSelectionRetainsAuthorityExactStartAndOriginalToken() = fixture { f ->
        val queue = DerivedPlaylistsApi(f.client).playback("rules", 5, "fixture-fence", "original-click-token").queue
        val snapshot = requireNotNull(queue.preparedState)
        val authority = requireNotNull(queue.selectionAuthority)
        f.prepare(queue); until { f.controls.preparedPending.size == 1 }
        val held = f.controls.preparedPending.single()
        assertSame(queue, held.queue); assertSame(snapshot, held.queue.preparedState)
        assertSame(authority, held.queue.selectionAuthority)
        assertEquals("occ-2", snapshot.queue[snapshot.position].occurrenceId)
        assertTrue(snapshot.queue.all { it.entryId.isEmpty() })
        assertEquals(listOf("occ-1", "occ-2"), queue.items.map { it.entryId })
        assertNull(held.startEntryId)
        assertEquals("original-click-token", authority.selectionTokenFor(f.client, snapshot))
        assertTrue(authority.isValidFor(f.client, snapshot)); assertNull(f.model.state.value.notice)
    }
    @Test fun preparedSignedDerivedSelectionCannotBeReusedAfterEqualValuedReauthentication() = fixture { f ->
        val queue = DerivedPlaylistsApi(f.client).playback("rules", 5, "fixture-fence", "original-click-token").queue
        val old = f.client.currentSession()
        f.client.login("listener", "synthetic")
        assertEquals(old, f.client.currentSession()); assertNotSame(old, f.client.currentSession())
        f.prepare(queue); runCurrent()
        assertTrue(f.controls.preparedPending.isEmpty())
        assertEquals("This prepared selection expired. Reload the collection and choose again.", f.model.state.value.notice)
    }
    @Test fun heldRawSignedDerivedSelectionCannotBorrowEqualValuedNewSessionAtDispatchOrCompletion() = fixture { f ->
        val selection = queueIntentSelection(f.row, PlaybackContext("derived", "rules", "Rules", 5, "fence"), "exact-path", "opaque.signed")
        f.model.queueIntentSelectionFrom(f.client, selection, PlaybackListAction.QUEUE_HIGH)
        runCurrent(); val pending = f.controls.pending.single()
        val old = f.client.currentSession()
        f.client.login("listener", "synthetic")
        assertEquals(old, f.client.currentSession()); assertNotSame(old, f.client.currentSession())
        assertFalse(pending.current())
        pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)); runCurrent()
        assertSame(selection, pending.selection); assertNull(f.model.state.value.notice)
        assertEquals(0, f.playbackReads.get())
    }
    @Test fun preparedAdmissionRejectsWrongAccountOldGenerationAndReplacedContextualKey() = fixture { f ->
        val queue = f.manualQueue
        val generation = f.model.state.value.playbackIntentGeneration
        f.model.preparedListAction(f.client, "wrong-account", queue, "same", "two", PlaybackListAction.ADD_TO_QUEUE, null)
        f.model.stopPlayback()
        f.model.preparedListAction(f.client, "id", queue, "same", "two", PlaybackListAction.ADD_TO_QUEUE, null,
            expectedGeneration = generation)
        val oldKey = f.controls.state.value.interactionKey!!
        f.controls.state.value = f.controls.state.value.copy(interactionKey = PlaybackInteractionKey())
        f.model.preparedListAction(f.client, "id", queue, "same", "two", PlaybackListAction.CONTEXTUAL, oldKey)
        runCurrent(); assertTrue(f.controls.preparedPending.isEmpty()); assertNull(f.model.state.value.notice)
    }
    @Test fun rawAdmissionRejectsReplacedKeyWithoutRefetchOrFallback() = fixture { f ->
        val oldKey = f.controls.state.value.interactionKey!!
        f.controls.state.value = f.controls.state.value.copy(interactionKey = PlaybackInteractionKey())
        f.model.contextualFrom(f.client, f.row, oldKey)
        runCurrent(); assertTrue(f.controls.pending.isEmpty()); assertNull(f.model.state.value.notice)
        assertEquals(0, f.playbackReads.get())
    }
    @Test fun rawManualDuplicateSelectionForwardsOnlyExactEntryWithOriginalContextAndBinding() = fixture { f ->
        f.model.open(Page.Playlist(PlaylistSummary("manual", "Manual")))
        val selected = f.row.copy(entryId = "two")
        f.model.contextualFrom(f.client, selected, f.controls.key)
        until { f.controls.pending.size == 1 }
        val held = f.controls.pending.single()
        val selection = held.selection as QueueIntentSelection.Track
        assertEquals("two", selection.track.getValue("entryId").jsonPrimitive.content)
        assertEquals("same", selection.track.getValue("id").jsonPrimitive.content)
        assertEquals(PlaybackContext("playlist", "manual", "Manual"), selection.context)
        assertSame(f.controls.key, held.key); assertEquals(f.controls.binding, held.binding)
        assertEquals(0, f.playbackReads.get()); assertNull(f.model.state.value.notice)
    }
    @Test fun rawDefaultManualQueueAndPlayNextKeepExactEntryAndWaitForAcceptance() = fixture { f ->
        f.model.open(Page.Playlist(PlaylistSummary("manual", "Manual")))
        for (next in listOf(false, true)) {
            f.model.clearNotice()
            val count = f.controls.pending.size + 1
            f.model.enqueueFrom(f.client, f.row.copy(entryId = "two"), playNext = next)
            until { f.controls.pending.size == count }
            val held = f.controls.pending.last()
            assertEquals(if (next) PlaybackListAction.PLAY_NEXT else PlaybackListAction.QUEUE_HIGH, held.action)
            val selected = held.selection as QueueIntentSelection.Track
            assertEquals("two", selected.track.getValue("entryId").jsonPrimitive.content)
            assertEquals("manual", selected.context!!.id)
            assertTrue(held.current()); assertTrue(f.model.state.value.preparing)
            assertNull("Preparing/submitting a manual duplicate is not acceptance", f.model.state.value.notice)
            held.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            until { !f.model.state.value.preparing }
            assertEquals("Added to queue", f.model.state.value.notice)
        }
        assertEquals(0, f.playbackReads.get())
    }
    @Test fun delayedPreparedManualCompatibilityRetainsCompleteQueueRevisionAndSelectedDuplicate() = fixture { f ->
        val queue = f.manualQueue
        f.prepare(queue); until { f.controls.preparedPending.size == 1 }
        val held = f.controls.preparedPending.single()
        assertSame(queue, held.queue); assertEquals(7L, held.queue.revision)
        assertEquals("manual", held.queue.context!!.id)
        assertEquals(listOf("one", "two"), held.queue.items.map { it.entryId })
        assertEquals("same", held.startTrackId); assertEquals("two", held.startEntryId)
        assertTrue(held.current()); assertTrue(f.model.state.value.preparing)
        assertNull(f.model.state.value.notice)
        val accepted = PlaybackActionResult(PlaybackActionStatus.LIMITED, 1)
        held.complete(accepted); until { !f.model.state.value.preparing }
        assertEquals(accepted.userMessage, f.model.state.value.notice)
    }
    @Test fun rawHeldReceiverPreparationKeepsOriginalBindingAfterOutputReplacement() = fixture { f ->
        f.submit(); f.submit(item = f.row.copy(id = "Y")); runCurrent()
        assertEquals(1, f.controls.pending.size)
        val first = f.controls.pending.single()
        f.controls.state.value = f.controls.state.value.copy(interactionKey = PlaybackInteractionKey(),
            queueIntentBinding = f.controls.binding.copy(outputDeviceId = "other", receiverId = "other_receiver", revision = 1))
        first.complete(PlaybackActionResult(PlaybackActionStatus.STALE))
        until { f.controls.pending.size == 2 }
        val second = f.controls.pending.last()
        assertSame(f.controls.key, first.key); assertSame(f.controls.key, second.key)
        assertEquals(f.controls.binding, first.binding); assertEquals(f.controls.binding, second.binding)
        assertNotEquals(f.controls.state.value.queueIntentBinding, second.binding)
        assertNull("The second pending action has no acceptance yet", f.model.state.value.notice)
        val rejected = PlaybackActionResult(PlaybackActionStatus.STALE)
        second.complete(rejected); until { !f.model.state.value.preparing }
        assertEquals(rejected.userMessage, f.model.state.value.notice)
    }
    @Test fun rawBenignTransportPublicationPreservesCapturedSelectionKeyAndBinding() = fixture { f ->
        f.model.contextualFrom(f.client, f.row, f.controls.key)
        f.controls.state.value = f.controls.state.value.copy(positionMs = 500, isBuffering = true, playWhenReady = true,
            transportInteractionKey = PlaybackInteractionKey())
        runCurrent(); val held = f.controls.pending.single()
        assertSame(f.controls.key, held.key); assertEquals(f.controls.binding, held.binding)
        assertEquals(PlaybackListAction.QUEUE_HIGH, held.action); assertTrue(held.current())
        val selection = held.selection as QueueIntentSelection.Track
        assertEquals("album", selection.track.getValue("albumId").jsonPrimitive.content)
        assertNull("Receiver infers Now's album context; row High stays one raw row", selection.context)
        assertNull(f.model.state.value.notice)
    }
    private fun allTypedResultsWaitForTerminalCallback(prepared: Boolean) = fixture { f ->
        for (status in PlaybackActionStatus.entries) {
            f.model.clearNotice()
            val held = admit(f, prepared)
            assertTrue(held.dispatchIfCurrent()); assertTrue(f.model.state.value.preparing)
            assertNull("No optimistic acceptance for $status", f.model.state.value.notice)
            val result = PlaybackActionResult(status, if (status == PlaybackActionStatus.LIMITED) 1 else 0)
            held.complete(result); until { !f.model.state.value.preparing }
            assertEquals(result.userMessage, f.model.state.value.notice)
        }
    }
    @Test fun everyRawTypedResultWaitsForTerminalCallback() = allTypedResultsWaitForTerminalCallback(false)
    @Test fun everyPreparedTypedResultWaitsForTerminalCallback() = allTypedResultsWaitForTerminalCallback(true)

    @Test fun signedDerivedSelectionRemainsOpaqueAndNeverFallsBackToAlbum() = fixture { f ->
        val selection = queueIntentSelection(f.row, PlaybackContext("derived", "rules", "Rules", 5, "fence"), "exact-path", "opaque.signed")
        f.model.queueIntentSelectionFrom(f.client, selection, PlaybackListAction.QUEUE_LOW)
        runCurrent(); assertSame(selection, f.controls.pending.single().selection)
        assertEquals(0, f.playbackReads.get())
    }
}
