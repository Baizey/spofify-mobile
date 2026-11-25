package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.playback.LivePersonal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun cleanUp() { store.clear(); Dispatchers.resetMain() }
    private fun <T : ViewModel> keep(model: T) = model.also { store.put("model", it) }
    private val track = PersonalTrack("track", "track", "Song", true, PersonalCoverage(1, 1, 1))
    private val summary = PersonalPlaylistSummary("list", "Mix 2", "", PersonalPlaylistKind.MANUAL,
        PersonalCountState.EXACT, 2, 2, PersonalPlaylistCapabilities(true, true, false, true, false, true, true), 1, "", "")

    @Test fun listUsesOnlyTheReplicaAndDoesNotInventUnconfirmedEdits() = runTest(dispatcher) {
        val source = Source()
        source.replica.receiveSnapshot(PersonalSnapshot(listOf(summary.copy(id = "other", name = "Mix 10"), summary), emptyList()))
        val model = keep(PlaylistsViewModel(source, manualOnly = true))
        model.refresh()
        model.create("Not yet saved", "")
        runCurrent()
        assertEquals(1, source.accountRefreshes)
        assertEquals(listOf("Mix 2", "Mix 10"), model.state.value.items.map { it.name })
        assertEquals(PersonalIntent("createPlaylist", name = "Not yet saved", description = ""), source.commands.single())
        model.add(track.id, summary)
        assertEquals(PersonalIntent("addEntry", playlistId = summary.id, trackId = track.id), source.commands.last())
    }

    @Test fun onlyAcceptedDetailBecomesLoadedAndTheExactDerivedDuplicateHasPlaybackIdentity() = runTest(dispatcher) {
        val source = Source()
        val model = keep(PlaylistDetailViewModel(source, summary.id))
        model.watch().use {
            assertEquals(summary.id, source.watched)
            val derived = summary.copy(kind = PersonalPlaylistKind.DERIVED, evaluationState = PersonalEvaluationState.READY)
            source.replica.receiveSnapshot(PersonalSnapshot(listOf(derived), emptyList()))
            runCurrent()
            assertFalse(model.state.value.loaded)
            source.replica.availability(true, emptySet())
            val rows = listOf(PersonalManualEntry("first", 3, track), PersonalManualEntry("second", 8, track))
            source.replica.replaceDetail(source.replica.version(summary.id), derived, rows)
            runCurrent()
            val state = model.state.value
            assertEquals(listOf("first", "second"), state.rows.map { it.identity })
            assertEquals("second", state.rows.last().reference(state.context)?.occurrenceId)
            source.replica.receiveChange(PersonalChange(deleted = summary.id))
            runCurrent()
            assertTrue(model.state.value.deleted)
            assertNull(model.state.value.context)
        }
        assertNull(source.watched)
    }

    @Test fun pendingRulesAreNotPresentedAsAnEmptyPlaylistAndStoredRowsAreNotOnlineActions() = runTest(dispatcher) {
        val source = Source()
        source.replica.availability(true, emptySet())
        val model = keep(PlaylistDetailViewModel(source, summary.id))
        val pending = summary.copy(kind = PersonalPlaylistKind.DERIVED, countState = PersonalCountState.PENDING,
            evaluationState = PersonalEvaluationState.PENDING, trackCount = null, availableCount = null)
        source.replica.receiveSnapshot(PersonalSnapshot(listOf(pending), emptyList()))
        source.replica.replaceDetail(source.replica.version(summary.id), pending, emptyList())
        runCurrent()
        assertFalse(model.state.value.loaded)
        assertFalse(model.state.value.showEmpty)
        assertNull(model.state.value.context)
        val stale = pending.copy(countState = PersonalCountState.STALE, evaluationState = PersonalEvaluationState.ERROR,
            trackCount = 1, availableCount = 1)
        source.replica.replaceDetail(source.replica.version(summary.id), stale, listOf(PersonalManualEntry("stored", 0, track)))
        runCurrent()
        assertEquals(listOf("stored"), model.state.value.rows.map { it.identity })
        assertFalse(model.state.value.showEmpty)
        assertFalse(model.state.value.canSaveOnline)
        assertEquals(false, model.state.value.summary?.capabilities?.play)
        val ready = stale.copy(countState = PersonalCountState.EXACT, evaluationState = PersonalEvaluationState.READY,
            trackCount = 0, availableCount = 0)
        source.replica.replaceDetail(source.replica.version(summary.id), ready, emptyList())
        runCurrent()
        assertTrue(model.state.value.showEmpty)
        assertTrue(model.state.value.canSaveOnline)
    }

    @Test fun recentUsesOccurrencesAndDistinguishesUnknownFromEmpty() = runTest(dispatcher) {
        val source = Source()
        val model = keep(RecentViewModel(source))
        runCurrent()
        assertNull(model.state.value.items)
        val play = PersonalHistoryEntry("one", "2026-01-01T00:00:00Z", track)
        source.replica.receiveSnapshot(PersonalSnapshot(emptyList(), listOf(play, play.copy(id = "two"))))
        runCurrent()
        assertEquals(listOf("one", "two"), model.state.value.items?.map { it.id })
        source.replica.receiveSnapshot(PersonalSnapshot(emptyList(), emptyList()))
        runCurrent()
        assertEquals(emptyList<PersonalHistoryEntry>(), model.state.value.items)
    }

    private class Source : LivePersonal {
        override val replica = AccountPersonalReplica().apply { restore(PersonalCacheSnapshot()) }
        var accountRefreshes = 0
        var watched: String? = null
        val commands = mutableListOf<PersonalIntent>()
        override fun refreshAccount() { accountRefreshes++ }
        override fun command(intent: PersonalIntent) = commands.add(intent)
        override fun watch(id: String): AutoCloseable { watched = id; return AutoCloseable { watched = null } }
        override suspend fun likes(limit: Int, offset: Int): PersonalLikesPage = error("Unexpected Likes access")
        override suspend fun likeStatus(trackIds: List<String>): List<PersonalLikeStatus> = error("Unexpected Likes access")
        override suspend fun setLike(trackId: String, liked: Boolean): PersonalLikeStatus = error("Unexpected Likes access")
    }
}
