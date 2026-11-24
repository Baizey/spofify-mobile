package dev.spofify.mobile.playback

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.cache.*
import dev.spofify.mobile.core.offline.*
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.personal.AccountPersonalReplica.Resource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AccountPersonalSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val origin = ServerOrigin.parse("https://offline-fixture.test")
    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private val summary = PersonalPlaylistSummary("p", "Saved name", "", PersonalPlaylistKind.MANUAL,
        PersonalCountState.EXACT, 0, 0, PersonalPlaylistCapabilities(true, true, false, true, false, true, true), 1, "", "")

    @Test fun staleViewsRenderFromDiskShareOneFetchAndKeepSnapshotOnPartialResponse() = runTest {
        val fixture = Fixture(this, temporary.root, stale = true)
        runCurrent()
        val view = fixture.source.replica.view.value
        assertEquals("Saved name", view.playlists.single().name)
        assertTrue(view.historyKnown)
        repeat(6) { fixture.source.checkFreshness() }
        runCurrent()
        assertEquals(1, fixture.listCalls)
        assertEquals(1, fixture.historyCalls)
        fixture.list.complete(PersonalPage(listOf(summary.copy(name = "Partial response")), 2, 100, 0))
        runCurrent()
        assertEquals("Saved name", fixture.source.replica.view.value.playlists.single().name)
        assertEquals(at.toEpochMilli() - 300_000, fixture.source.replica.snapshots.value.listsAt)
        assertFalse(fixture.source.replica.stale(Resource.History))
        assertTrue(fixture.source.replica.stale(Resource.Lists))
        assertEquals(1, fixture.listCalls)
    }

    @Test fun freshListIsNotFetchedAndRetiredResponseCannotOverwriteDisk() = runTest {
        val fixture = Fixture(this, temporary.root, stale = false)
        runCurrent()
        repeat(6) { fixture.source.checkFreshness() }
        runCurrent()
        assertEquals(0, fixture.listCalls)
        fixture.clock = Clock.offset(fixture.clock, java.time.Duration.ofMinutes(5))
        fixture.source.refresh(Resource.Lists)
        runCurrent()
        assertEquals(1, fixture.listCalls)
        fixture.sessions.signOut()
        fixture.list.complete(PersonalPage(listOf(summary.copy(name = "Late response")), 1, 100, 0))
        runCurrent()
        assertEquals("Saved name", fixture.source.replica.view.value.playlists.single().name)
        assertEquals("Saved name", fixture.saved.read().playlists.single().name)
    }

    @Test fun watchedInvalidationRepairsAfterOlderRequestFailsWithoutAnotherScreenEntry() = runTest {
        val fixture = Fixture(this, temporary.root, stale = false)
        runCurrent()
        val owner = requireNotNull(fixture.sessions.onlineGrant.value)
        fixture.source.applyConfirmed(owner, PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary), emptyList())))
        val watch = fixture.source.watch(summary.id)
        try {
            runCurrent()
            assertEquals(1, fixture.detailCalls)
            val changed = summary.copy(name = "Confirmed", revision = 2, trackCount = 1, availableCount = 1)
            fixture.liveSummary = changed
            fixture.source.applyConfirmed(owner, PersonalUpdate(change = PersonalChange(playlist = changed, rowsInvalid = true)))
            assertEquals("Confirmed", fixture.source.replica.view.value.playlists.single().name)
            fixture.oldDetail.completeExceptionally(PersonalLibraryException(503, PersonalLibraryErrorCode.HTTP_ERROR))
            runCurrent()
            assertEquals(1, fixture.detailCalls)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(3, fixture.detailCalls)
            val repaired = fixture.source.replica.snapshots.value.details.getValue(summary.id)
            assertEquals(listOf("new-occurrence"), repaired.rows.map { it.id })
            assertEquals(2L, repaired.revision)
            assertFalse(fixture.source.replica.stale(Resource.Detail(summary.id)))
        } finally { watch.close() }
    }

    @Test fun selectedTrackIsNotDownloadedUntilTheDetailContainingItsOccurrenceIsDurable() = runTest {
        val initial = PersonalCacheSnapshot(listOf(summary), details = mapOf(summary.id to ReplicaPlaylist(1, emptyList())),
            listsAt = at.toEpochMilli(), recentAt = at.toEpochMilli())
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(this, temporary.root, stale = false, initial = initial, beforePersist = {
            if (it.details[summary.id]?.revision == 2L) { held.complete(Unit); release.await() }
        })
        runCurrent()
        val grant = requireNotNull(fixture.sessions.onlineGrant.value)
        val row = PersonalManualEntry("added", 0, PersonalTrack("track", "track", "Song", true, PersonalCoverage(1, 1, 1)))
        fixture.source.applyConfirmed(grant, PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary), emptyList())))
        fixture.source.applyConfirmed(grant, PersonalUpdate(change = PersonalChange(
            playlist = summary.copy(revision = 2, trackCount = 1, availableCount = 1), entries = EntryChange(added = row))))
        runCurrent()
        assertTrue(held.isCompleted)
        val store = MusicBlobStore(temporary.newFolder(), freeBytes = { Long.MAX_VALUE })
        val key = SongKey(MusicOwner(origin.value, grant.identity.accountId), row.track.id)
        val transfer = launch {
            fixture.sessions.withOnlineGrant(grant) {
                fixture.source.persistPlaylistForDownload(summary.id, row.id, row.track.id, grant)
                store.download(key, row.track.name, 3, { true }, { descriptor(key.id) },
                    commit = { fixture.sessions.publishIfCurrent(grant, it) }) { _, consume -> consume(bytes.inputStream()) }
            }
        }
        runCurrent()
        assertFalse(transfer.isCompleted)
        assertTrue(store.songs.value.isEmpty())
        assertTrue(fixture.saved.read().details.getValue(summary.id).rows.isEmpty())
        release.complete(Unit)
        transfer.join()
        assertEquals(listOf(row), fixture.saved.read().details.getValue(summary.id).rows)
        assertTrue(store.songs.value.single().direct)
    }

    @Test fun playlistManifestCannotPublishAfterAuthorityRetiresAtRename() = runTest {
        for (manual in listOf(false, true)) {
            val fixture = Fixture(this, temporary.newFolder(), stale = false)
            val grant = requireNotNull(fixture.sessions.onlineGrant.value)
            val owner = MusicOwner(origin.value, grant.identity.accountId)
            val path = temporary.newFolder()
            val store = MusicBlobStore(path, freeBytes = { Long.MAX_VALUE })
            val track = PersonalTrack("track", "track", "Song", true, PersonalCoverage(1, 1, 1))
            val manifest = OfflineManifest(OfflineCollection(OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, summary.id),
                summary.name, OfflinePlaylistKind.MANUAL, "", 1), "fixture", listOf(OfflineOccurrence("one", 0, track)), 1, 1)
            val repository = OfflinePlaylistRepository(store, owner, { manifest }, ::descriptor) { error("Use the staged AUTO blob") }
            store.fill(SongKey(owner, track.id), track.name, 3, emptyList(), { true }, { descriptor(track.id) }) { _, read ->
                read(bytes.inputStream())
            }
            val reachedRename = CompletableDeferred<Unit>()
            val releaseRename = CountDownLatch(1)
            val saved = backgroundScope.async(Dispatchers.IO) {
                runCatching {
                    repository.refresh(summary.id, 3, commit = { rename ->
                        reachedRename.complete(Unit)
                        check(releaseRename.await(10, TimeUnit.SECONDS))
                        fixture.sessions.publishIfCurrent(grant, rename)
                    }) { fixture.sessions.onlineGrant.value === grant }
                }
            }
            try {
                withContext(Dispatchers.IO) { withTimeout(10_000) { reachedRename.await() } }
                if (manual) fixture.sessions.setManualOffline(true) else fixture.sessions.signOut()
                assertNull(fixture.sessions.onlineGrant.value)
            } finally { releaseRename.countDown() }
            assertTrue(saved.await().exceptionOrNull() is CancellationException)
            assertTrue(repository.complete().isEmpty())
            assertFalse(store.songs.value.single().offline)
            val reopened = MusicBlobStore(path)
            val checkDisk = OfflinePlaylistRepository(reopened, owner, { error("offline") }, ::descriptor) { error("offline") }
            assertTrue(checkDisk.complete().isEmpty())
            assertNull(reopened.pin(SongKey(owner, track.id), downloadOnly = true) { true })
        }
    }

    private val bytes = byteArrayOf(1, 2, 3)
    private fun descriptor(id: String): OfflineMediaDescriptor {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return OfflineMediaDescriptor(id, "sha256:$sha", sha, bytes.size.toLong(), "mp3", "audio/mpeg", "")
    }

    @Test fun retiredCatalogWriterCannotUndoNewerSameAccountMetadata() = runTest {
        val fixture = Fixture(this, temporary.root, stale = false)
        val old = requireNotNull(fixture.sessions.onlineGrant.value).identity
        val reachedRename = CompletableDeferred<Unit>()
        val releaseRename = CountDownLatch(1)
        val replace = AtomicBoolean(false)
        val settled = CompletableDeferred<Throwable?>()
        val metadata = { title: String -> LocalTrackMetadata.Cached("track", title, "", "", emptyList(), 0) }
        val oldCatalog = LocalMusicCatalog(temporary.root, old, commit = { write ->
            reachedRename.complete(Unit)
            check(releaseRename.await(10, TimeUnit.SECONDS))
            fixture.sessions.commitLocalIfCurrent(old) { write(); replace.set(true) }
        }) { setOf("track") }
        val oldWrite = backgroundScope.launch(Dispatchers.IO) {
            settled.complete(runCatching { oldCatalog.rememberTracks(listOf(metadata("Old"))) }.exceptionOrNull())
        }
        try {
            // Real IO is outside the virtual clock. Wait with a real-time deadline.
            withContext(Dispatchers.IO) { withTimeout(10_000) { reachedRename.await() } }
            fixture.sessions.signOut()
            fixture.sessions.signIn(origin.value, old.username, "fixture")
            runCurrent()
            val successor = requireNotNull(fixture.sessions.onlineGrant.value).identity
            assertEquals(old.accountId, successor.accountId)
            assertNotEquals(old.incarnation, successor.incarnation)
            val currentCatalog = LocalMusicCatalog(temporary.root, successor,
                commit = { fixture.sessions.commitLocalIfCurrent(successor, it) }) { setOf("track") }
            currentCatalog.rememberTracks(listOf(metadata("New")))
            releaseRename.countDown()
            oldWrite.join()
            assertTrue(settled.await() is CancellationException)
            assertFalse(replace.get())
            val reopened = LocalMusicCatalog(temporary.root, successor) { setOf("track") }
            assertEquals("New", reopened.availableTracks().single().name)
        } finally { releaseRename.countDown() }
    }

    private inner class Fixture(test: TestScope, root: java.io.File, stale: Boolean,
        initial: PersonalCacheSnapshot? = null, beforePersist: suspend (PersonalCacheSnapshot) -> Unit = {}) {
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        var clock = Clock.fixed(at, ZoneOffset.UTC)
        private val clockProxy = object : Clock() {
            override fun instant() = clock.instant()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = clock.withZone(zone)
        }
        private val identity = AccountIdentity(origin.value, "account", "Listener", 1)
        private val account = SessionSummary(identity.accountId, identity.username, "user", "csrf-fixture")
        private val session = StoredSession(origin.value, "__Host-spofify_session=fixture; Path=/; HttpOnly; Secure", account, null)
        val sessions = SessionRepository(object : SessionStore {
            override fun read() = session
            override fun write(value: StoredSession?) = Unit
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin) = Unit
        }, AuthApiFactory { server ->
            object : AuthApi {
                override val origin = server
                override fun cancel() = Unit
                override fun validateCookie(cookie: String) = Unit
                override suspend fun login(username: String, password: String) = AuthReply(account, session.cookie, null)
                override suspend fun session(cookie: String) = AuthReply(account, null, null)
                override suspend fun logout(cookie: String, csrfToken: String) = Unit
            }
        }, test.backgroundScope, dispatcher, manualOfflineStore = object : ManualOfflineStore {
            override fun read() = false
            override fun write(enabled: Boolean) = Unit
        })
        val saved = PersonalCacheFile(root, identity)
        val list = CompletableDeferred<PersonalPage<PersonalPlaylistSummary>>()
        var listCalls = 0
        var historyCalls = 0
        var detailCalls = 0
        var liveSummary = summary
        val oldDetail = CompletableDeferred<PersonalPlaylistSummary>()
        private val client = object : PersonalLibraryApi by PersonalLibraryClient(sessions, identity) {
            override suspend fun playlists(limit: Int, offset: Int, kind: PersonalPlaylistKind?): PersonalPage<PersonalPlaylistSummary> {
                listCalls++
                return withContext(NonCancellable) { list.await() }
            }
            override suspend fun playlist(id: String): PersonalPlaylistSummary =
                if (++detailCalls == 1) oldDetail.await() else liveSummary
            override suspend fun manualEntries(id: String, limit: Int, offset: Int): PersonalPage<PersonalManualEntry> =
                PersonalPage(listOf(PersonalManualEntry("new-occurrence", 0,
                    PersonalTrack("track", "new-track", "New track", true, PersonalCoverage(1, 1, 1)))), 1, limit, offset)
            override suspend fun history(limit: Int, offset: Int): PersonalPage<PersonalHistoryEntry> {
                historyCalls++
                return PersonalPage(emptyList(), 0, limit, offset)
            }
        }
        val source: AccountPersonalSource
        init {
            val stamp = at.toEpochMilli() - if (stale) 300_000 else 0
            saved.write(initial ?: PersonalCacheSnapshot(listOf(summary), listsAt = stamp, recentAt = stamp))
            sessions.start()
            test.runCurrent()
            assertEquals(identity, sessions.onlineGrant.value?.identity)
            source = AccountPersonalSource(root, identity, sessions, test.backgroundScope, client,
                AccountPersonalReplica(clockProxy), dispatcher,
                monotonicMs = { test.testScheduler.currentTime }, beforePersist = beforePersist)
        }
    }
}
