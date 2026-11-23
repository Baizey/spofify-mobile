package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModelStore
import dev.spofify.mobile.core.auth.AuthApi
import dev.spofify.mobile.core.auth.AuthApiFactory
import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.AuthReply
import dev.spofify.mobile.core.auth.LoginStore
import dev.spofify.mobile.core.auth.ManualOfflineStore
import dev.spofify.mobile.core.auth.RememberedLogin
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.auth.SessionStore
import dev.spofify.mobile.core.auth.SessionSummary
import dev.spofify.mobile.core.auth.StoredSession
import dev.spofify.mobile.core.library.Artist
import dev.spofify.mobile.core.library.LibraryApi
import dev.spofify.mobile.core.library.LibraryException
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.library.MusicPage
import dev.spofify.mobile.core.playback.PlaybackContext
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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

    private fun model(api: Api, kind: MusicKind = MusicKind.ALBUM, owner: LibraryReadOwner? = null) =
        DetailViewModel(api, kind, "collection", owner).also { store.put("detail", it) }

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

    @Test fun manualAlbumPublishesEveryTrackWithoutDiscardingNameOnlyCredits() = runTest(dispatcher) {
        val credits = listOf(Artist("", "Manual artist"), Artist("known-artist", "Known guest"))
        val api = Api().apply {
            readDetail = {
                item(MusicKind.ALBUM, "collection").copy(
                    artists = listOf(Artist("manual-artist-group", "Manual artist")),
                )
            }
            readPage = { offset ->
                val page = page(offset, 41)
                page.copy(items = page.items.map { it.copy(artists = credits) })
            }
        }
        val model = model(api)
        advanceUntilIdle()
        assertTrue(model.state.value.loaded)
        assertFalse(model.state.value.failed)
        assertEquals(listOf(0, 40), api.offsets)
        assertEquals((0..40).map(Int::toString), model.state.value.rows.map { it.id })
        assertTrue(model.state.value.rows.all { it.artists == credits && it.hasAudio })
        assertEquals("manual-artist-group", model.state.value.item?.artists?.single()?.id)
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

    @Test fun albumRefreshPreservesTheWholeOldSnapshotUntilTheDelayedReadSucceeds() = runTest(dispatcher) {
        val api = Api().apply { readPage = { offset -> page(offset, 41) } }
        val model = model(api)
        advanceUntilIdle()
        val old = model.state.value
        api.readDetail = { item(MusicKind.ALBUM, "collection").copy(name = "New name") }
        api.readPage = { offset ->
            if (api.offsets == listOf(0, 40, 0, 40)) throw LibraryException(503)
            page(offset, 42, revision = 2)
        }
        model.refresh()
        runCurrent()
        assertEquals(old.copy(loading = true), model.state.value)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(listOf(0, 40, 0, 40), api.offsets)
        assertEquals(old.copy(loading = true), model.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0, 40, 0, 40, 40), api.offsets)
        assertEquals(2, api.detailReads)
        assertEquals(42, model.state.value.rows.size)
        assertEquals(2L, model.state.value.revision)
        assertEquals("New name", model.state.value.item?.name)
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.failed)
    }

    @Test fun secondTransientFailureStopsAndPreservesTheOldSnapshot() = runTest(dispatcher) {
        val api = Api().apply { readPage = { offset -> page(offset, 41) } }
        val model = model(api)
        advanceUntilIdle()
        val old = model.state.value
        api.readDetail = { item(MusicKind.ALBUM, "collection").copy(name = "New name") }
        api.readPage = { offset ->
            if (offset > 0) throw LibraryException(503)
            page(offset, 42, revision = 2)
        }
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 0, 40, 40), api.offsets)
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(old.copy(failed = true), model.state.value)
        assertEquals(2, api.detailReads)
    }

    @Test fun recoveryBudgetIsSharedAcrossDetailAndPages() = runTest(dispatcher) {
        val api = Api()
        api.readDetail = {
            if (api.detailReads == 1) throw AuthFailure(AuthFailure.Kind.UNAVAILABLE, transient = true)
            item(MusicKind.ALBUM, "collection")
        }
        api.readPage = { throw LibraryException(503) }
        val model = model(api)
        runCurrent()
        assertEquals(1, api.detailReads)
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.failed)
        assertNull(model.state.value.item)
        advanceUntilIdle()
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(2, api.detailReads)
        assertEquals(listOf(0), api.offsets)
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.loading)
    }

    @Test fun permanentThrottledAndUntypedFailuresStopWithoutWaiting() = runTest(dispatcher) {
        val failures = listOf(LibraryException(404), LibraryException(429), LibraryException(503, transient = false),
            AuthFailure(AuthFailure.Kind.UNAVAILABLE), AuthFailure(AuthFailure.Kind.INVALID_RESPONSE), IOException())
        for (failure in failures) {
            val api = Api().apply { readDetail = { throw failure } }
            val model = model(api)
            runCurrent()
            assertEquals(DetailState(failed = true), model.state.value)
            advanceUntilIdle()
            assertEquals(1, api.detailReads)
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test fun mixedAlbumRevisionsStopWithoutPublishingOrReplaying() = runTest(dispatcher) {
        for (withTransientFailure in listOf(false, true)) {
            val api = Api().apply {
                readPage = { offset ->
                    if (withTransientFailure && offsets == listOf(0, 40)) throw LibraryException(503)
                    page(offset, 41, revision = if (offset == 0) 1 else 2)
                }
            }
            val model = model(api)
            advanceUntilIdle()
            assertTrue(model.state.value.failed)
            assertFalse(model.state.value.loaded)
            assertTrue(model.state.value.rows.isEmpty())
            assertEquals(if (withTransientFailure) listOf(0, 40, 40) else listOf(0, 40), api.offsets)
            assertEquals(if (withTransientFailure) 1_000L else 0L, testScheduler.currentTime)
        }
    }

    @Test fun artistPagesRetainReleaseRelationshipsAndTypesAcrossContinuation() = runTest(dispatcher) {
        val appearances = (0 until 40).map { index ->
            item(MusicKind.ALBUM, "appearance-$index").copy(
                albumType = if (index % 2 == 0) "album" else "single",
                relation = "appearance",
            )
        }
        val album = item(MusicKind.ALBUM, "own-album").copy(albumType = "album", relation = "release")
        val single = item(MusicKind.ALBUM, "own-single").copy(albumType = "single", relation = "release")
        val releases = appearances + album + single
        val api = Api().apply {
            readDetail = { item(MusicKind.ARTIST, "collection") }
            readPage = { offset ->
                MusicPage(releases.drop(offset).take(40), releases.size.toLong(), offset, 40, 1)
            }
        }
        val model = model(api, MusicKind.ARTIST)
        advanceUntilIdle()
        assertEquals(appearances, model.state.value.rows)
        assertEquals(40, model.state.value.nextOffset)
        model.more()
        advanceUntilIdle()
        assertEquals(listOf(0, 40), api.offsets)
        assertEquals(releases, model.state.value.rows)
        assertEquals(listOf("album", "single"), model.state.value.rows.takeLast(2).map { it.albumType })
        assertTrue(model.state.value.rows.takeLast(2).all { it.relation == "release" })
        assertNull(model.state.value.nextOffset)
    }

    @Test fun artistContinuationAutomaticallyRepeatsOnlyTheFailedPage() = runTest(dispatcher) {
        var failedOnce = false
        val api = Api().apply {
            readDetail = { item(MusicKind.ARTIST, "collection") }
            readPage = { offset ->
                if (offset > 0 && !failedOnce) {
                    failedOnce = true
                    throw LibraryException(503)
                }
                page(offset, 41, kind = MusicKind.ALBUM)
            }
        }
        val model = model(api, MusicKind.ARTIST)
        advanceUntilIdle()
        val old = model.state.value
        model.more()
        runCurrent()
        assertEquals(listOf(0, 40), api.offsets)
        assertEquals(old.copy(loading = true), model.state.value)
        model.more()
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 40), api.offsets)
        assertEquals(1, api.detailReads)
        assertEquals(41, model.state.value.rows.size)
        assertFalse(model.state.value.failed)
    }

    @Test fun artistRecoveryStillRequiresMatchingTheOldRevision() = runTest(dispatcher) {
        val api = Api().apply {
            readDetail = { item(MusicKind.ARTIST, "collection") }
            readPage = { offset -> page(offset, 41, kind = MusicKind.ALBUM) }
        }
        val model = model(api, MusicKind.ARTIST)
        advanceUntilIdle()
        val old = model.state.value
        api.readPage = { offset ->
            if (api.offsets == listOf(0, 40)) throw LibraryException(503)
            page(offset, 42, revision = 2, kind = MusicKind.ALBUM)
        }
        model.more()
        advanceUntilIdle()
        assertEquals(old.copy(failed = true), model.state.value)
        model.more() // The end-of-grid trigger must not silently restart a failed continuation.
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 40), api.offsets)
        assertEquals(1, api.detailReads)
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf(0, 40, 40, 0), api.offsets)
        assertEquals(2, api.detailReads)
        assertEquals(2L, model.state.value.revision)
        assertEquals(40, model.state.value.rows.size)
        assertEquals(40, model.state.value.nextOffset)
    }

    @Test fun duplicateRowsAndNonAdvancingPagesFailWithoutReplayOrPartialAlbum() = runTest(dispatcher) {
        for (invalidRows in listOf(listOf(item(MusicKind.TRACK, "0")), emptyList())) {
            val api = Api().apply { readPage = { offset ->
                if (offset == 0) page(0, 41)
                else MusicPage(invalidRows, 41, offset, 40, 1)
            } }
            val model = model(api)
            advanceUntilIdle()
            assertTrue(model.state.value.failed)
            assertTrue(model.state.value.rows.isEmpty())
            assertEquals(listOf(0, 40), api.offsets)
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test fun totalChangeWithinAlbumIsRejectedEvenWhenRevisionMatches() = runTest(dispatcher) {
        val api = Api().apply { readPage = { offset -> page(offset, if (offset == 0) 41 else 42) } }
        val model = model(api)
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.loaded)
        assertTrue(model.state.value.rows.isEmpty())
    }

    @Test fun clearingViewModelDuringBackoffPreventsTheSecondReadAndAnyPublication() = runTest(dispatcher) {
        val api = Api().apply { readPage = { throw LibraryException(503) } }
        val model = model(api)
        runCurrent()
        val pending = model.state.value
        assertTrue(pending.loading)
        assertEquals(listOf(0), api.offsets)
        store.clear()
        advanceUntilIdle()
        assertEquals(listOf(0), api.offsets)
        assertEquals(pending, model.state.value)
    }

    @Test fun nonCooperativeRecoveryCannotReadMoreOrPublishWhenCleared() = runTest(dispatcher) {
        for (lateFailure in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val api = Api().apply {
                readPage = { offset ->
                    if (offsets.size == 1) throw LibraryException(503)
                    withContext(NonCancellable) { gate.await() }
                    if (lateFailure) throw LibraryException(503)
                    page(offset, 41)
                }
            }
            val model = model(api)
            advanceTimeBy(1_000)
            runCurrent()
            val pending = model.state.value
            assertTrue(pending.loading)
            assertEquals(listOf(0, 0), api.offsets)
            store.clear()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(0, 0), api.offsets)
            assertEquals(pending, model.state.value)
        }
    }

    @Test fun retiringTheOnlineGrantDuringBackoffStopsTheExistingAndFutureOldOwnerLoads() = runTest(dispatcher) {
        val sessions = onlineSessions()
        val owner = LibraryReadOwner(sessions, requireNotNull(sessions.onlineGrant.value))
        val api = Api()
        val model = model(api, owner = owner)
        runCurrent()
        val old = model.state.value
        assertTrue(old.loaded)
        api.readPage = { throw LibraryException(503) }
        model.refresh()
        runCurrent()
        val pending = model.state.value
        assertEquals(old.copy(loading = true), pending)
        sessions.setManualOffline(true)
        advanceUntilIdle()
        assertNull(sessions.onlineGrant.value)
        assertEquals(listOf(0, 0), api.offsets)
        assertEquals(pending, model.state.value)
        model.refresh()
        model.toggleRankMode()
        advanceUntilIdle()
        assertEquals(2, api.detailReads)
        assertEquals(listOf(0, 0), api.offsets)
        assertEquals(pending, model.state.value)
    }

    private fun TestScope.onlineSessions(): SessionRepository {
        val account = SessionSummary("account", "user", "user", "csrf")
        val sessions = object : SessionStore {
            var saved: StoredSession? = StoredSession("https://music.example", "__Host-spofify_session=fixture; Secure; Path=/; HttpOnly", account)
            override fun read() = saved
            override fun write(value: StoredSession?) { saved = value }
        }
        val logins = object : LoginStore {
            override fun read() = null
            override fun write(value: RememberedLogin) = Unit
        }
        val apis = AuthApiFactory { server ->
            object : AuthApi {
                override val origin = server
                override suspend fun session(cookie: String) = AuthReply(account, null, null)
                override suspend fun login(username: String, password: String): AuthReply = error("Not used")
                override suspend fun logout(cookie: String, csrfToken: String) = Unit
                override fun cancel() = Unit
                override fun validateCookie(cookie: String) = Unit
            }
        }
        val offline = object : ManualOfflineStore {
            private var value = false
            override fun read() = value
            override fun write(enabled: Boolean) { value = enabled }
        }
        return SessionRepository(sessions, logins, apis, backgroundScope, dispatcher, offline).also {
            it.start()
            runCurrent()
        }
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
