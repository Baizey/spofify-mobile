package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModelStore
import dev.spofify.mobile.core.library.LibraryApi
import dev.spofify.mobile.core.library.LibraryException
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.library.MusicPage
import dev.spofify.mobile.core.playback.PlaybackContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private class Api : LibraryApi {
        val offsets = mutableListOf<Int>()
        var detailReads = 0
        var readDetail: () -> MusicItem = { item(MusicKind.ALBUM, "collection") }
        var readPage: suspend (Int) -> MusicPage = { offset -> page(offset, 1) }
        override suspend fun detail(kind: MusicKind, id: String): MusicItem {
            detailReads++
            return readDetail()
        }
        override suspend fun albumTracks(id: String, offset: Int): MusicPage {
            offsets.add(offset)
            return readPage(offset)
        }
        override suspend fun artistAlbums(id: String, offset: Int): MusicPage {
            offsets.add(offset)
            return readPage(offset)
        }
        override suspend fun search(kind: MusicKind, query: String, offset: Int): MusicPage = error("Not used")
        override suspend fun artwork(kind: MusicKind, id: String): ByteArray? = null
    }

    private fun model(api: Api, kind: MusicKind = MusicKind.ALBUM) =
        DetailViewModel(api, kind, "collection").also { store.put("detail", it) }

    @Test fun entireAlbumLoadsWithoutPageCapInSourceOrder() = runTest(dispatcher) {
        val api = Api().apply { readPage = { offset -> page(offset, 201) } }
        val model = model(api)
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 80, 120, 160, 200), api.offsets)
        assertEquals((0..200).map(Int::toString), model.state.value.rows.map { it.id })
        assertEquals("200", model.state.value.rows.last().id)
        assertTrue(model.state.value.loaded)
        assertNull(model.state.value.nextOffset)
    }

    @Test fun incompleteAlbumCannotReplaceAConsistentSnapshot() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply { readPage = { offset ->
            if (offset > 0) gate.await()
            page(offset, 41)
        } }
        val model = model(api)
        runCurrent()
        assertNotNull(model.state.value.item)
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.loaded)
        assertTrue(model.state.value.rows.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(41, model.state.value.rows.size)
        assertEquals("40", model.state.value.rows.last().id)
    }

    @Test fun refreshFailurePreservesTheWholeOldSnapshotThenRetryRestartsAtZero() = runTest(dispatcher) {
        val api = Api().apply { readPage = { offset -> page(offset, 41) } }
        val model = model(api)
        advanceUntilIdle()
        val old = model.state.value
        api.readDetail = { item(MusicKind.ALBUM, "collection").copy(name = "New name") }
        api.readPage = { offset -> if (offset > 0) throw LibraryException(503) else page(offset, 42, revision = 2) }
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertEquals(old.item, model.state.value.item)
        assertEquals(old.rows, model.state.value.rows)
        assertEquals(old.revision, model.state.value.revision)
        api.readPage = { offset -> page(offset, 42, revision = 2) }
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 0, 40, 0, 40), api.offsets)
        assertEquals(42, model.state.value.rows.size)
        assertEquals("New name", model.state.value.item?.name)
        assertFalse(model.state.value.failed)
    }

    @Test fun mixedAlbumRevisionsNeverPublishAndRetryReadsAFreshCompleteAlbum() = runTest(dispatcher) {
        val api = Api().apply { readPage = { offset -> page(offset, 41, revision = if (offset == 0) 1 else 2) } }
        val model = model(api)
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.loaded)
        assertTrue(model.state.value.rows.isEmpty())
        api.readPage = { offset -> page(offset, 41, revision = 3) }
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 0, 40), api.offsets)
        assertEquals(3L, model.state.value.revision)
        assertEquals(41, model.state.value.rows.size)
    }

    @Test fun artistContinuationFailureRetriesTheFailedPageNotTheFirstPage() = runTest(dispatcher) {
        val api = Api().apply {
            readDetail = { item(MusicKind.ARTIST, "collection") }
            readPage = { offset ->
                if (offset > 0) throw LibraryException(503)
                page(offset, 41, kind = MusicKind.ALBUM)
            }
        }
        val model = model(api, MusicKind.ARTIST)
        advanceUntilIdle()
        assertEquals(listOf(0), api.offsets)
        model.more()
        advanceUntilIdle()
        assertEquals(40, model.state.value.rows.size)
        assertTrue(model.state.value.failed)
        api.readPage = { offset -> page(offset, 41, kind = MusicKind.ALBUM) }
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 40), api.offsets)
        assertEquals(1, api.detailReads)
        assertEquals(41, model.state.value.rows.size)
        assertFalse(model.state.value.failed)
    }

    @Test fun artistRevisionChangeRequiresRefreshInsteadOfAppendingOrRetryingAnIncompatiblePage() = runTest(dispatcher) {
        val api = Api().apply {
            readDetail = { item(MusicKind.ARTIST, "collection") }
            readPage = { offset -> page(offset, 41, kind = MusicKind.ALBUM) }
        }
        val model = model(api, MusicKind.ARTIST)
        advanceUntilIdle()
        api.readPage = { offset -> page(offset, 42, revision = 2, kind = MusicKind.ALBUM) }
        model.more()
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertEquals(1L, model.state.value.revision)
        assertEquals(40, model.state.value.rows.size)
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 0), api.offsets)
        assertEquals(2, api.detailReads)
        assertEquals(2L, model.state.value.revision)
        assertEquals(40, model.state.value.rows.size)
        assertEquals(40, model.state.value.nextOffset)
    }

    @Test fun duplicateRowsAndNonAdvancingPagesFailWithoutPublishingPartialAlbum() = runTest(dispatcher) {
        val api = Api().apply { readPage = { offset ->
            if (offset == 0) page(0, 41)
            else MusicPage(listOf(item(MusicKind.TRACK, "0")), 41, offset, 40, 1)
        } }
        val model = model(api)
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertTrue(model.state.value.rows.isEmpty())
        api.readPage = { offset -> MusicPage(emptyList(), 41, offset, 40, 1) }
        model.retry()
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertEquals(listOf(0, 40, 0), api.offsets)
    }

    @Test fun totalChangeWithinAlbumIsRejectedEvenWhenRevisionMatches() = runTest(dispatcher) {
        val api = Api().apply { readPage = { offset -> page(offset, if (offset == 0) 41 else 42) } }
        val model = model(api)
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.loaded)
        assertTrue(model.state.value.rows.isEmpty())
    }

    @Test fun presentationUsesRealMetadataAvailabilityAndCollectionIdentity() {
        val album = item(MusicKind.ALBUM, "album-id").copy(name = "Record", releaseDate = "1998-04-12")
        assertEquals("1998", releaseYear(album.releaseDate))
        assertEquals("", releaseYear("unknown"))
        assertEquals("", releaseYear(""))
        assertEquals(PlaybackContext("album", "album-id", "Record", 7), album.collectionContext(7))
        assertEquals("artist", album.copy(kind = MusicKind.ARTIST).collectionContext()?.kind)
        assertNull(album.copy(kind = MusicKind.TRACK).collectionContext())
        assertTrue(album.canPlay())
        assertFalse(album.copy(availableTracks = 0).canPlay())
        assertFalse(album.copy(kind = MusicKind.TRACK, hasAudio = false).canPlay())
        assertTrue(album.copy(kind = MusicKind.TRACK, hasAudio = true, availableTracks = 0).canPlay())
    }

    @Test fun rankingToggleChangesOnlyTheSelectedModeAndDoesNotInventRanksFromAlbums() = runTest(dispatcher) {
        val api = Api().apply {
            readDetail = { item(MusicKind.ARTIST, "collection") }
            readPage = { offset -> page(offset, 41, kind = MusicKind.ALBUM) }
        }
        val model = model(api, MusicKind.ARTIST)
        advanceUntilIdle()
        val rows = model.state.value.rows
        assertEquals("Catalogue", model.state.value.rankMode.label)
        model.toggleRankMode()
        assertEquals("Your plays", model.state.value.rankMode.label)
        assertSame(rows, model.state.value.rows)
        assertEquals(listOf(0), api.offsets)
        assertEquals(1, api.detailReads)
        assertEquals("Rankings unavailable", DETAIL_RANKINGS_UNAVAILABLE)
        model.more()
        advanceUntilIdle()
        assertEquals(DetailRankMode.PERSONAL, model.state.value.rankMode)
        model.refresh()
        advanceUntilIdle()
        assertEquals(DetailRankMode.PERSONAL, model.state.value.rankMode)
        model.toggleRankMode()
        assertEquals(DetailRankMode.CATALOGUE, model.state.value.rankMode)
    }

    @Test fun genreMetadataIsOptionalAndNeverDerivedFromUnrelatedRows() {
        val album = item(MusicKind.ALBUM, "record")
        assertTrue(album.genres.isEmpty())
        val known = album.copy(genres = listOf("Electronic", "Ambient"))
        assertEquals(listOf("Electronic", "Ambient"), known.genres)
    }

    companion object {
        private fun item(kind: MusicKind, id: String) = MusicItem(kind, id, "Song $id", emptyList(),
            "collection", "Record", "", 0, 0, kind == MusicKind.TRACK, 1, 1)
        private fun page(offset: Int, total: Int, revision: Long = 1, kind: MusicKind = MusicKind.TRACK) =
            MusicPage((offset until minOf(offset + 40, total)).map { item(kind, it.toString()) }, total.toLong(), offset, 40, revision)
    }
}
