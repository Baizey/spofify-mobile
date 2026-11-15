package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.PlaybackContext
import dev.spofify.mobile.core.playback.PlaybackQueueState
import dev.spofify.mobile.core.playback.PlaybackSnapshot
import dev.spofify.mobile.core.playback.PlaybackStatus
import dev.spofify.mobile.core.playback.PlaybackView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var nextKey = 0
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }
    private fun <T : ViewModel> keep(model: T) = model.also { store.put("model-${nextKey++}", it) }

    @Test fun summariesPublishAndNaturallySortOnlyAfterAllPagesArePresent() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            list = (0..201).map { summary("List $it", id = "p$it") }
            listGate = { offset -> if (offset > 0) gate.await() }
        }
        val model = keep(PlaylistsViewModel(api))
        runCurrent()
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.loaded)
        assertTrue(model.state.value.items.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0, 100, 200), api.listOffsets)
        assertEquals((0..201).map { "List $it" }, model.state.value.items.map { it.name })
        assertTrue(model.state.value.loaded)
    }

    @Test fun failedSummaryRefreshKeepsTheWholeOldListRatherThanReplacingItWithAPrefix() = runTest(dispatcher) {
        val api = Api().apply { list = listOf(summary("List 2"), summary("List 10", id = "ten")) }
        val model = keep(PlaylistsViewModel(api))
        advanceUntilIdle()
        val old = model.state.value.items
        api.list = (0..100).map { summary("New $it", id = "new$it") }
        api.listGate = { offset -> if (offset > 0) throw IllegalStateException("private/raw/path") }
        model.refresh()
        advanceUntilIdle()
        assertEquals(old, model.state.value.items)
        assertTrue(model.state.value.loaded)
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.error!!.contains("private"))
        assertFalse(model.state.value.loading)
    }

    @Test fun createWaitsForARealResponseAndDoesNotDoubleSubmitOrInventAPlaylist() = runTest(dispatcher) {
        val api = Api()
        val gate = CompletableDeferred<PersonalPlaylistSummary>()
        api.createAction = { _, _ -> gate.await() }
        val model = keep(PlaylistsViewModel(api))
        advanceUntilIdle()
        model.create("Name", "Description")
        model.create("Name", "Description")
        runCurrent()
        assertEquals(1, api.creates)
        assertNull(model.state.value.created)
        assertTrue(model.state.value.items.isEmpty())
        val server = summary("Canonical name", id = "server-created")
        gate.complete(server)
        advanceUntilIdle()
        assertEquals(server, model.state.value.created)
        assertFalse(model.state.value.writing)
        model.consumeCreated()
        api.createAction = { _, _ -> throw unavailable() }
        model.create("Another", "")
        advanceUntilIdle()
        assertNull(model.state.value.created)
        assertTrue(model.state.value.message!!.contains("wasn't resubmitted"))
    }

    @Test fun manualRowsKeepDuplicateTracksAndStoredSlotsAndFilterCanFindTheLastPage() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            entries = (0..100).map { index -> PersonalManualEntry("entry-$index", index * 3L,
                track(name = if (index == 100) "Last song" else "Song")) }
            current = summary(count = 101)
            manualGate = { offset -> if (offset > 0) gate.await() }
        }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        model.filter("Last")
        runCurrent()
        assertEquals("Playlist", model.state.value.summary?.name)
        assertNull(model.state.value.context)
        assertTrue(model.state.value.rows.isEmpty())
        assertFalse(model.state.value.noMatches)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0, 100), api.manualOffsets)
        assertEquals(101, model.state.value.rows.size)
        val row = model.state.value.visibleRows.single()
        assertEquals("entry-100", row.identity)
        assertEquals(300L, row.position)
        assertEquals("entry-100", row.reference(model.state.value.context)!!.entryId)
        assertEquals(PlaybackContext("playlist", "playlist", "Playlist"), model.state.value.context)
        assertEquals(1, model.state.value.rows.map { it.track.id }.toSet().size)
        model.filter("missing")
        assertTrue(model.state.value.noMatches)
    }

    @Test fun changedManualRevisionDuringReadDoesNotPublishAnInconsistentCollection() = runTest(dispatcher) {
        val api = Api().apply {
            entries = listOf(PersonalManualEntry("entry", 4, track()))
            current = summary(count = 1)
            summaryAction = { reads -> if (reads == 1) current else current.copy(revision = 2) }
        }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        advanceUntilIdle()
        assertFalse(model.state.value.loaded)
        assertTrue(model.state.value.rows.isEmpty())
        assertNull(model.state.value.context)
        assertNotNull(model.state.value.error)
        api.current = api.current.copy(revision = 2)
        api.summaryAction = { api.current }
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.completeCollection)
        assertEquals(2L, model.state.value.summary!!.revision)
    }

    @Test fun derivedCursorRowsUseOnlyTheReturnedFenceRevisionAndExactOccurrenceToken() = runTest(dispatcher) {
        val api = Api().apply {
            current = summary(kind = PersonalPlaylistKind.DERIVED, count = 101, revision = 7)
            derived = { cursor -> derivedPage(if (cursor == null) 0 until 100 else 100..100,
                next = if (cursor == null) "opaque-cursor" else null) }
        }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        advanceUntilIdle()
        assertEquals(listOf(null, "opaque-cursor"), api.cursors)
        val context = model.state.value.context!!
        assertEquals(PlaybackContext("derived", "playlist", "Playlist", 7, "returned-fence"), context)
        assertEquals(101, model.state.value.rows.size)
        val row = model.state.value.rows.last()
        val reference = row.reference(context)!!
        assertEquals("path/100", reference.occurrenceId)
        assertEquals("signed-100", reference.selectionToken)
        assertEquals("playlist", reference.targetId)
        assertNull(reference.entryId)
    }

    @Test fun staleDerivedPrefixIsNotAnEmptyCompleteCollectionOrPlaybackAuthority() = runTest(dispatcher) {
        val api = Api().apply {
            current = summary(kind = PersonalPlaylistKind.DERIVED, count = 500, revision = 7).copy(
                countState = PersonalCountState.STALE, evaluationState = PersonalEvaluationState.STALE)
            derived = { PersonalDerivedPage(listOf(PersonalDerivedEntry("old/path", null, 123, track())),
                500, 500, 100, null, 7, "", false, PersonalEvaluationState.STALE) }
        }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        model.filter("not in prefix")
        advanceUntilIdle()
        assertTrue(model.state.value.loaded)
        assertFalse(model.state.value.completeCollection)
        assertFalse(model.state.value.noMatches)
        assertNull(model.state.value.context)
        assertEquals(123L, model.state.value.rows.single().position)
        assertNull(model.state.value.rows.single().reference(model.state.value.context))
    }

    @Test fun mixedDerivedFencesOnRefreshPreserveOldRowsButRetireTheirActionContext() = runTest(dispatcher) {
        val api = Api().apply {
            current = summary(kind = PersonalPlaylistKind.DERIVED, count = 101, revision = 7)
            derived = { cursor -> derivedPage(if (cursor == null) 0 until 100 else 100..100,
                next = if (cursor == null) "cursor" else null) }
        }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        advanceUntilIdle()
        val old = model.state.value.rows
        api.derived = { cursor -> derivedPage(if (cursor == null) 0 until 100 else 100..100,
            next = if (cursor == null) "cursor" else null).copy(fence = if (cursor == null) "first" else "changed") }
        model.refresh()
        assertNull(model.state.value.context)
        advanceUntilIdle()
        assertEquals(old, model.state.value.rows)
        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.needsRefresh)
    }

    @Test fun lateUncancelableReadCannotOverwriteANewerRefresh() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            current = summary(name = "Old", count = 0)
            summaryAction = { reads ->
                if (reads == 1) {
                    withContext(NonCancellable) { gate.await() }
                    summary(name = "Old", count = 0)
                } else current
            }
        }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        runCurrent()
        api.current = summary(name = "New", count = 0, revision = 2)
        model.refresh()
        runCurrent()
        assertEquals("New", model.state.value.summary!!.name)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("New", model.state.value.summary!!.name)
        assertEquals(2L, model.state.value.summary!!.revision)
    }

    @Test fun renameConflictRefreshesWithoutReplayingAndRejectsAnOldDialogRevision() = runTest(dispatcher) {
        val api = Api().apply { current = summary(count = 0) }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        advanceUntilIdle()
        api.renameAction = { _, _, _ ->
            api.current = api.current.copy(name = "Changed elsewhere", revision = 2)
            throw PersonalLibraryException(409, PersonalLibraryErrorCode.REVISION_CONFLICT)
        }
        model.rename("My edit", "Description", 1)
        advanceUntilIdle()
        assertEquals(listOf(Write("rename", "playlist", null, 1)), api.writes)
        assertEquals("Changed elsewhere", model.state.value.summary!!.name)
        assertEquals(2L, model.state.value.summary!!.revision)
        assertTrue(model.state.value.message!!.contains("changed elsewhere"))
        model.rename("Stale dialog", "", 1)
        advanceUntilIdle()
        assertEquals(1, api.writes.size)
    }

    @Test fun removeAddressesTheExactDuplicateEntryAndDeleteWaitsForConfirmation() = runTest(dispatcher) {
        val api = Api().apply {
            entries = listOf(PersonalManualEntry("first", 0, track()), PersonalManualEntry("second", 3, track()))
            current = summary(count = 2)
        }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        advanceUntilIdle()
        model.remove(model.state.value.rows.last(), 1)
        advanceUntilIdle()
        assertEquals(Write("remove", "playlist", "second", 1), api.writes.single())
        assertEquals(listOf("first"), model.state.value.rows.map { it.entryId })
        val gate = CompletableDeferred<Unit>()
        api.deleteAction = { gate.await() }
        model.delete(2)
        runCurrent()
        assertFalse(model.state.value.deleted)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.deleted)
        assertEquals(Write("delete", "playlist", null, 2), api.writes.last())
    }

    @Test fun failedEntryMutationLeavesRowsAndRequiresReviewInsteadOfPretendingSuccess() = runTest(dispatcher) {
        val api = Api().apply {
            current = summary(count = 1)
            entries = listOf(PersonalManualEntry("entry", 0, track()))
            removeAction = { _, _ -> throw unavailable() }
        }
        val model = keep(PlaylistDetailViewModel(api, "playlist"))
        advanceUntilIdle()
        val old = model.state.value.rows
        model.remove(old.single(), 1)
        advanceUntilIdle()
        assertEquals(old, model.state.value.rows)
        assertTrue(model.state.value.needsRefresh)
        assertNull(model.state.value.context)
        assertFalse(model.state.value.canEdit)
        assertTrue(model.state.value.message!!.contains("couldn't be confirmed"))
        assertEquals(1, api.writes.size)
    }

    @Test fun pickerLoadsOnlyManualSummariesAndAddsWithTheSelectedExpectedRevisionOnce() = runTest(dispatcher) {
        val gate = CompletableDeferred<PersonalPlaylistSummary>()
        val api = Api().apply {
            list = listOf(summary(revision = 9))
            addAction = { _, _, _ -> gate.await() }
        }
        val model = keep(PlaylistsViewModel(api, manualOnly = true))
        advanceUntilIdle()
        assertEquals(listOf(PersonalPlaylistKind.MANUAL), api.kinds)
        model.add("track", model.state.value.items.single())
        model.add("track", model.state.value.items.single())
        runCurrent()
        assertNull(model.state.value.added)
        assertEquals(listOf(Write("add", "playlist", "track", 9)), api.writes)
        val response = summary(revision = 10, count = 1)
        gate.complete(response)
        advanceUntilIdle()
        assertEquals(response, model.state.value.added)
    }

    @Test fun recentUsesOneLimit100ReadAndRetainsRealUnavailableAndMissingMetadataFacts() = runTest(dispatcher) {
        val api = Api().apply {
            recentValue = PersonalRecent(listOf(
                PersonalHistoryEntry("new", "2026-01-02T00:00:00Z", track("new", audio = false)),
                PersonalHistoryEntry("old", "2026-01-01T00:00:00Z", track("old", name = "", audio = false), metadataMissing = true),
            ), 800, 100)
        }
        val model = keep(RecentViewModel(api))
        advanceUntilIdle()
        assertEquals(listOf(100), api.recentLimits)
        assertEquals(listOf("new", "old"), model.state.value.items.map { it.id })
        assertFalse(model.state.value.items.first().metadataMissing)
        assertTrue(model.state.value.items.last().metadataMissing)
        assertEquals(800L, model.state.value.total)
        val old = model.state.value.items
        api.recentFailure = unavailable()
        model.refresh()
        advanceUntilIdle()
        assertEquals(old, model.state.value.items)
        assertEquals(800L, model.state.value.total)
        assertNotNull(model.state.value.error)
    }

    @Test fun presentationDoesNotTurnUnknownCountsIntoZeroOrOverflowNaturalNumbers() {
        val pending = summary(kind = PersonalPlaylistKind.DERIVED).copy(trackCount = null, availableCount = null,
            countState = PersonalCountState.PENDING, evaluationState = PersonalEvaluationState.PENDING)
        assertEquals("Count pending", playlistCount(pending))
        val sorted = naturalPlaylistOrder(listOf(summary("Mix 2", id = "two"), summary("mix 10", id = "ten"),
            summary("Mix 9999999999999999999999999999999", id = "huge")))
        assertEquals(listOf("two", "ten", "huge"), sorted.map { it.id })
        assertFalse(track(audio = false).musicItem().hasAudio)
    }

    @Test fun collectionQueueUsesFreshReturnedDerivedFenceAndManualIdentityWithoutInventingRows() = runTest(dispatcher) {
        val api = Api().apply {
            current = summary(kind = PersonalPlaylistKind.DERIVED, revision = 7)
            derived = { PersonalDerivedPage(emptyList(), 0, 0, 1, null, 7, "fresh-fence", true, PersonalEvaluationState.READY) }
        }
        assertEquals(PlaybackContext("derived", "playlist", "Playlist", 7, "fresh-fence"), freshPlaylistContext(api, "playlist"))
        assertEquals(listOf(null), api.cursors)
        assertTrue(api.manualOffsets.isEmpty())
        api.derived = { PersonalDerivedPage(emptyList(), 0, 0, 1, null, 7, "", false, PersonalEvaluationState.PENDING) }
        val failure = runCatching { freshPlaylistContext(api, "playlist") }.exceptionOrNull()
        assertTrue(failure is PersonalLibraryException)
        assertEquals(PersonalLibraryErrorCode.STALE_EVALUATION, (failure as PersonalLibraryException).code)
        api.current = summary(revision = 9)
        assertEquals(PlaybackContext("playlist", "playlist", "Playlist"), freshPlaylistContext(api, "playlist"))
    }

    @Test fun gestureBindingDistinguishesDuplicateOccurrencesAndFreshFencesWithoutResettingForQuietProgress() {
        val row = PlaylistSong("first-entry", 0, track(), entryId = "first-entry")
        val context = PlaybackContext("derived", "playlist", "Playlist", 7, "fresh-fence")
        val status = PlaybackStatus(2, 1, 1, "", false, 0.0, 0.0, false, false, false, "")
        val snapshot = PlaybackSnapshot("session", 1, 2, PlaybackQueueState(), status, emptyList(), "", "", null, 0)
        val view = PlaybackView(sessionId = "session", snapshot = snapshot, playback = status)
        val binding = personalGestureBinding(row, context, view)
        assertNotEquals(binding, personalGestureBinding(row.copy(identity = "second-entry", entryId = "second-entry"), context, view))
        assertNotEquals(binding, personalGestureBinding(row, context.copy(fence = "new-fence"), view))
        assertNotEquals(binding, personalGestureBinding(row, context, view.copy(snapshot = snapshot.copy(outputVersion = 3))))
        assertNotEquals(binding, personalGestureBinding(row, context, view.copy(snapshot = snapshot.copy(stateVersion = 2))))
        assertNotEquals(binding, personalGestureBinding(row, context, view.copy(sessionId = "new-session")))
        assertEquals(binding, personalGestureBinding(row, context, view.copy(playback = status.copy(playbackSequence = 2, elapsed = 1.0))))
    }

    private data class Write(val type: String, val id: String, val member: String?, val revision: Long)

    private class Api : PersonalLibraryApi {
        var current = summary(count = 0)
        var list = emptyList<PersonalPlaylistSummary>()
        var entries = emptyList<PersonalManualEntry>()
        var recentValue = PersonalRecent(emptyList(), 0, 100)
        var recentFailure: Exception? = null
        var summaryReads = 0
        var creates = 0
        val writes = mutableListOf<Write>()
        val listOffsets = mutableListOf<Int>()
        val kinds = mutableListOf<PersonalPlaylistKind?>()
        val manualOffsets = mutableListOf<Int>()
        val cursors = mutableListOf<String?>()
        val recentLimits = mutableListOf<Int>()
        var summaryAction: suspend (Int) -> PersonalPlaylistSummary = { current }
        var listGate: suspend (Int) -> Unit = {}
        var manualGate: suspend (Int) -> Unit = {}
        var derived: suspend (String?) -> PersonalDerivedPage = { error("Unexpected derived read") }
        var createAction: suspend (String, String) -> PersonalPlaylistSummary = { name, _ -> summary(name) }
        var renameAction: suspend (String, String, Long) -> PersonalPlaylistSummary = { name, description, revision ->
            current.copy(name = name, description = description, revision = revision + 1).also { current = it }
        }
        var removeAction: suspend (String, Long) -> PersonalPlaylistSummary = { entryId, revision ->
            entries = entries.filterNot { it.id == entryId }
            current.copy(trackCount = entries.size.toLong(), availableCount = entries.size.toLong(), revision = revision + 1).also { current = it }
        }
        var addAction: suspend (String, String, Long) -> PersonalPlaylistSummary = { _, _, revision -> current.copy(revision = revision + 1) }
        var deleteAction: suspend (Long) -> Unit = {}
        override suspend fun playlists(limit: Int, offset: Int, kind: PersonalPlaylistKind?): PersonalPage<PersonalPlaylistSummary> {
            listOffsets.add(offset)
            kinds.add(kind)
            listGate(offset)
            return PersonalPage(list.drop(offset).take(limit), list.size.toLong(), limit, offset)
        }
        override suspend fun playlist(id: String): PersonalPlaylistSummary = summaryAction(++summaryReads)
        override suspend fun manualEntries(id: String, limit: Int, offset: Int): PersonalPage<PersonalManualEntry> {
            manualOffsets.add(offset)
            manualGate(offset)
            return PersonalPage(entries.drop(offset).take(limit), entries.size.toLong(), limit, offset)
        }
        override suspend fun derivedEntries(id: String, limit: Int, cursor: String?): PersonalDerivedPage {
            cursors.add(cursor)
            return derived(cursor)
        }
        override suspend fun createManualPlaylist(name: String, description: String): PersonalPlaylistSummary {
            creates++
            return createAction(name, description)
        }
        override suspend fun renamePlaylist(id: String, name: String, description: String, expectedRevision: Long): PersonalPlaylistSummary {
            writes.add(Write("rename", id, null, expectedRevision))
            return renameAction(name, description, expectedRevision)
        }
        override suspend fun deletePlaylist(id: String, expectedRevision: Long) {
            writes.add(Write("delete", id, null, expectedRevision))
            deleteAction(expectedRevision)
        }
        override suspend fun addTrack(id: String, trackId: String, expectedRevision: Long): PersonalPlaylistSummary {
            writes.add(Write("add", id, trackId, expectedRevision))
            return addAction(id, trackId, expectedRevision)
        }
        override suspend fun removeEntry(id: String, entryId: String, expectedRevision: Long): PersonalPlaylistSummary {
            writes.add(Write("remove", id, entryId, expectedRevision))
            return removeAction(entryId, expectedRevision)
        }
        override suspend fun recent(limit: Int): PersonalRecent {
            recentLimits.add(limit)
            recentFailure?.let { throw it }
            return recentValue
        }
        override suspend fun likes(limit: Int, offset: Int): PersonalLikesPage = error("Unexpected Likes read")
        override suspend fun likeStatus(trackIds: List<String>): List<PersonalLikeStatus> = error("Unexpected status read")
        override suspend fun setLike(trackId: String, liked: Boolean): PersonalLikeStatus = error("Unexpected Like write")
        override suspend fun toggleLike(trackId: String, currentlyLiked: Boolean): PersonalLikeStatus = error("Unexpected toggle")
        override suspend fun reorderEntries(id: String, entryIds: List<String>, expectedRevision: Long): PersonalPlaylistSummary = error("Unexpected reorder")
        override suspend fun history(limit: Int, offset: Int): PersonalPage<PersonalHistoryEntry> = error("History must not substitute for recent")
    }

    companion object {
        private fun unavailable() = PersonalLibraryException(503, PersonalLibraryErrorCode.HTTP_ERROR)
        private fun track(id: String = "track", name: String = "Song", audio: Boolean = true) =
            PersonalTrack("track", id, name, audio, PersonalCoverage(1, if (audio) 1 else 0, 0))
        private fun summary(name: String = "Playlist", id: String = "playlist", count: Long = 0, revision: Long = 1,
            kind: PersonalPlaylistKind = PersonalPlaylistKind.MANUAL) = PersonalPlaylistSummary(
            id, name, "Description", kind, PersonalCountState.EXACT, count, count,
            PersonalPlaylistCapabilities(true, kind == PersonalPlaylistKind.MANUAL, kind == PersonalPlaylistKind.DERIVED,
                true, kind == PersonalPlaylistKind.DERIVED, true, kind == PersonalPlaylistKind.MANUAL), revision,
            "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", if (kind == PersonalPlaylistKind.DERIVED) PersonalEvaluationState.READY else null)
        private fun derivedPage(range: IntRange, next: String? = null) = PersonalDerivedPage(
            range.map { PersonalDerivedEntry("path/$it", "signed-$it", it.toLong(), track()) }, 101, 101, 100, next,
            7, "returned-fence", true, PersonalEvaluationState.READY)
    }
}
