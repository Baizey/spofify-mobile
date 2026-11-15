package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModelStore
import dev.spofify.mobile.core.library.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private class Api : LibraryApi {
        val requests = mutableListOf<Triple<MusicKind, String, Int>>()
        var read: suspend (MusicKind, String, Int) -> MusicPage = { kind, query, offset ->
            MusicPage(listOf(item(kind, query)), 1, offset, 10, 1)
        }
        override suspend fun search(kind: MusicKind, query: String, offset: Int): MusicPage {
            requests.add(Triple(kind, query, offset))
            return read(kind, query, offset)
        }
        override suspend fun artistAlbums(id: String, offset: Int): MusicPage = error("Not used")
        override suspend fun albumTracks(id: String, offset: Int): MusicPage = error("Not used")
        override suspend fun detail(kind: MusicKind, id: String): MusicItem = error("Not used")
        override suspend fun artwork(kind: MusicKind, id: String): ByteArray? = null
    }
    private fun model(api: Api) = SearchViewModel(api).also { store.put("search", it) }

    @Test fun blankInputMakesNoRequestsAndTypingIsDebounced() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        model.query("   ")
        advanceUntilIdle()
        assertTrue(api.requests.isEmpty())
        model.query("lo")
        advanceTimeBy(150)
        model.query("love")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(3, api.requests.size)
        assertTrue(api.requests.all { it.second == "love" && it.third == 0 })
    }

    @Test fun oneSlowKindDoesNotHoldBackOtherResults() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply { read = { kind, query, offset ->
            if (kind == MusicKind.TRACK) gate.await()
            MusicPage(listOf(item(kind, query)), 1, offset, 10, 1)
        } }
        val model = model(api)
        model.query("love")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(setOf(MusicKind.ARTIST, MusicKind.ALBUM), model.state.value.items.map { it.kind }.toSet())
        assertTrue(model.state.value.busy)
        gate.complete(Unit)
        runCurrent()
        assertEquals(3, model.state.value.items.size)
    }

    @Test fun failureOfOneKindKeepsSuccessfulKindsAndRetryIsIndependent() = runTest(dispatcher) {
        val api = Api().apply { read = { kind, query, offset ->
            if (kind == MusicKind.ALBUM) throw LibraryException(503)
            MusicPage(listOf(item(kind, query)), 1, offset, 10, 1)
        } }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        assertEquals(2, model.state.value.items.size)
        assertTrue(model.state.value.groups.getValue(MusicKind.ALBUM).failed)
        api.read = { kind, query, offset -> MusicPage(listOf(item(kind, query)), 1, offset, 10, 1) }
        model.retry(MusicKind.ALBUM)
        advanceUntilIdle()
        assertEquals(4, api.requests.size)
        assertEquals(3, model.state.value.items.size)
    }

    @Test fun continuationOnlyRequestsKindsWithRemainingRows() = runTest(dispatcher) {
        val api = Api().apply { read = { kind, query, offset ->
            if (kind == MusicKind.TRACK) {
                val count = if (offset == 0) 10 else 1
                MusicPage((offset until offset + count).map { item(kind, "$query $it") }, 11, offset, 10, 1)
            } else MusicPage(emptyList(), 0, offset, 10, 1)
        } }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        model.more()
        advanceUntilIdle()
        assertEquals(Triple(MusicKind.TRACK, "love", 10), api.requests.last())
        assertEquals(4, api.requests.size)
        assertEquals(11, model.state.value.items.size)
        assertFalse(model.state.value.hasMore)
    }

    @Test fun clearCancelsPendingReadsAndDoesNotRefetch() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply { read = { kind, query, offset ->
            gate.await()
            MusicPage(listOf(item(kind, query)), 1, offset, 10, 1)
        } }
        val model = model(api)
        model.query("love")
        advanceTimeBy(300)
        runCurrent()
        model.query("")
        gate.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.items.isEmpty())
        assertFalse(model.state.value.busy)
        assertEquals(3, api.requests.size)
    }

    @Test fun exactTrackThenAlbumThenArtistAreNearestBottom() {
        val state = SearchState(query = "love", groups = MusicKind.entries.associateWith {
            SearchGroup(listOf(item(it, "love")), loaded = true)
        })
        assertEquals(listOf(MusicKind.TRACK, MusicKind.ALBUM, MusicKind.ARTIST), state.items.map { it.kind })
    }

    companion object {
        private fun item(kind: MusicKind, name: String) = MusicItem(kind, name, name, emptyList(), "", "", "", 0, 0,
            kind == MusicKind.TRACK, 1, 1)
    }
}
