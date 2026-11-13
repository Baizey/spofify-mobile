package dev.spofify.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding
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
import java.util.concurrent.atomic.AtomicInteger

/** Identifier-only admission. These tests do not establish native execution or rendered UX. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackListActionViewModelTest {
    private class Store : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private data class Pending(val selection: LibraryPlaybackSelection, val action: PlaybackListAction,
        val binding: LibraryPlaybackActionBinding?, val shuffle: Boolean?, val current: () -> Boolean,
        val complete: (PlaybackActionResult) -> Unit)
    private class Controls : PlaybackControls {
        val key = PlaybackInteractionKey()
        val binding = LibraryPlaybackActionBinding("room", "socket", 5, 1, 1)
        override val state = MutableStateFlow(PlaybackState(interactionKey = key, queueIntentBinding = binding, canQueueIntent = true))
        val pending = mutableListOf<Pending>()
        override fun queueIntent(selection: LibraryPlaybackSelection, client: SpofifyClient, action: PlaybackListAction,
            expectedKey: PlaybackInteractionKey?, expectedBinding: LibraryPlaybackActionBinding?, shuffle: Boolean?,
            isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            pending += Pending(selection, action, expectedBinding, shuffle, isCurrent, onResult)
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
    private data class Fixture(val model: LibraryViewModel, val client: SpofifyClient, val controls: Controls, val reads: AtomicInteger) {
        val row = LibraryItem("track", "same", "Song", hasAudio = true, albumId = "not-implicit")
        fun submit(action: PlaybackListAction = PlaybackListAction.QUEUE_HIGH) = model.queueIntentFrom(client, row, action)
    }
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        assertTrue("Timed out awaiting VM", condition())
    }
    private fun fixture(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val web = MockWebServer()
        val reads = AtomicInteger()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.encodedPath.endsWith("/playback")) reads.incrementAndGet()
                val body = when (request.requestUrl!!.encodedPath) {
                    "/api/auth/login", "/api/auth/session" -> """{"accountId":"id","username":"listener","role":"user","csrfToken":"csrf"}"""
                    else -> """{"items":[],"total":0,"revision":1}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                    .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AppGraph(app, Store())
        val store = ViewModelStore()
        try {
            web.start()
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            client.login("listener", "synthetic")
            val controls = Controls()
            val model = LibraryViewModel(app, graph, controls)
            store.put("subject", model)
            until { model.state.value.session != null && !model.state.value.restoring }
            block(Fixture(model, client, controls, reads))
            assertEquals("No REST preparation", 0, reads.get())
        } finally { store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain() }
    }
    @Test fun trackAndCollectionDefaultsDispatchIdentifiersWithoutPreparation() = fixture { f ->
        f.model.enqueueFrom(f.client, f.row)
        val track = f.controls.pending.last()
        assertEquals(PlaybackListAction.QUEUE_HIGH, track.action)
        assertNull((track.selection as LibraryPlaybackSelection.Track).context)
        assertEquals("same", track.selection.track.id)
        assertEquals(f.controls.binding, track.binding)
        f.model.enqueueFrom(f.client, LibraryItem("album", "album", "Album", coverage = Coverage(inLibrary = 2)))
        assertEquals(PlaybackListAction.QUEUE_LOW, f.controls.pending.last().action)
        assertTrue(f.controls.pending.last().selection is LibraryPlaybackSelection.Collection)
    }
    @Test fun collectionShuffleIsAtomicAndNeverASeparateToggle() = fixture { f ->
        f.model.playFrom(f.client, LibraryItem("album", "album", "Album", coverage = Coverage(inLibrary = 2)), true)
        assertEquals(1, f.controls.pending.size)
        val pending = f.controls.pending.single()
        assertEquals(PlaybackListAction.PLAY_NOW, pending.action)
        assertEquals(true, pending.shuffle)
        assertTrue(pending.selection is LibraryPlaybackSelection.Collection)
    }
    @Test fun exactManualAndSignedDerivedSelectionsAreNotExpanded() = fixture { f ->
        val manual = queueIntentSelection(f.row.copy(entryId = "duplicate-two"), LibraryPlaybackContext("playlist", "manual", "Mix"))
        f.model.queueIntentSelectionFrom(f.client, manual, PlaybackListAction.PLAY_NOW)
        assertSame(manual, f.controls.pending.last().selection)
        val context = LibraryPlaybackContext("derived", "rules", "Rules", 9, "fresh")
        val derived = queueIntentSelection(f.row, context, "path/second", "signed")
        f.model.queueIntentSelectionFrom(f.client, derived, PlaybackListAction.PLAY_NOW)
        assertSame(derived, f.controls.pending.last().selection)
        val selected = f.controls.pending.last().selection as LibraryPlaybackSelection.Track
        assertEquals("signed", selected.track.selectionToken)
        assertEquals("path/second", selected.track.occurrenceId)
        assertNull(selected.track.entryId)
    }
    @Test fun nestedAlbumContextAndNavigationABAStayCaptured() = fixture { f ->
        val artist = LibraryItem("artist", "artist", "Artist")
        val album = LibraryItem("album", "album", "Album")
        f.model.open(Page.Detail(artist)); runCurrent()
        val page = f.model.state.value.page as Page.Detail
        val holder = requireNotNull(f.model.catalogHolder(page.item, page.navigationId))
        holder.open(album)
        f.submit()
        val pending = f.controls.pending.last()
        assertEquals(LibraryPlaybackContext("album", "album", "Album"), (pending.selection as LibraryPlaybackSelection.Track).context)
        assertTrue(pending.current())
        holder.open(album.copy(id = "other")); holder.back()
        assertFalse(pending.current())
    }
    @Test fun routeABAInvalidatesHeldCompletion() = fixture { f ->
        f.submit(); val held = f.controls.pending.single()
        f.model.switchTab(Tab.SEARCH); f.model.switchTab(Tab.HOME)
        assertFalse(held.current())
        val prior = f.model.state.value.feedback
        held.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        assertEquals(prior, f.model.state.value.feedback)
    }
    @Test fun equalValuedReauthenticationCannotBorrowHeldAction() = fixture { f ->
        f.submit(); val held = f.controls.pending.single()
        val before = f.client.currentSession()
        f.client.login("listener", "synthetic")
        assertEquals(before, f.client.currentSession())
        assertNotSame(before, f.client.currentSession())
        assertFalse(held.current())
    }
    @Test fun epochAndNewNowRetireOldActionsWithoutReplay() = fixture { f ->
        f.submit(); val old = f.controls.pending.single()
        f.submit(PlaybackListAction.PLAY_NOW)
        assertFalse(old.current())
        val latest = f.controls.pending.last()
        PlaybackRequests.invalidate()
        assertFalse(latest.current())
        assertEquals(2, f.controls.pending.size)
    }
    @Test fun progressRetainsOriginalBindingButOutputReplacementDoesNotRefreshIt() = fixture { f ->
        f.submit(); val held = f.controls.pending.single()
        f.controls.state.value = f.controls.state.value.copy(positionMs = 1234)
        assertEquals(f.controls.binding, held.binding)
        assertTrue(held.current())
        f.controls.state.value = f.controls.state.value.copy(interactionKey = PlaybackInteractionKey(),
            queueIntentBinding = f.controls.binding.copy(outputVersion = 2))
        assertEquals(1L, held.binding!!.outputVersion)
        // The controls/coordinator, not ViewModel, owns authority rejection.
        held.complete(PlaybackActionResult(PlaybackActionStatus.STALE))
        assertNull(f.model.state.value.feedback)
    }
    @Test fun callerGuardIsRecheckedAndOnlyNewestReceiptPublishesFeedback() = fixture { f ->
        var current = true
        f.model.queueIntentFrom(f.client, f.row, PlaybackListAction.QUEUE_HIGH, callerIsCurrent = { current })
        val first = f.controls.pending.single()
        f.submit()
        first.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        assertNull(f.model.state.value.feedback)
        f.controls.pending.last().complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 2))
        assertEquals("2 tracks queued", f.model.state.value.feedback?.message)
        current = false
        assertFalse(first.current())
    }
}
