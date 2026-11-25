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
import dev.spofify.mobile.core.playback.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
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

    @Test fun diskFirstAndAccountSyncCoalescesWithoutAcceptingRetiredGrant() = runTest {
        val f = Fixture(this, temporary.root, stale = true)
        runCurrent()
        assertEquals("Saved name", f.source.replica.view.value.playlists.single().name)
        assertTrue(f.source.replica.view.value.historyKnown)
        f.source.checkFreshness()
        assertTrue(f.socket.sent.isEmpty())
        f.open()
        f.update(PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary), emptyList())))
        repeat(6) { f.source.checkFreshness() }
        assertEquals(0, f.socket.sent.count { it["type"] == JsonPrimitive("sync") })
        f.clock = Clock.offset(f.clock, java.time.Duration.ofMinutes(5))
        repeat(6) { f.source.checkFreshness() }
        assertEquals(1, f.socket.sent.count { it["type"] == JsonPrimitive("sync") })
        f.sessions.setManualOffline(true)
        f.update(PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary.copy(name = "Late response")), emptyList())))
        assertEquals("Saved name", f.source.replica.view.value.playlists.single().name)
        assertEquals(at.toEpochMilli(), f.saved.read().listsAt)
    }

    @Test fun watchedInvalidationConsumesObsoleteContinuationBeforeRepairingAndNeverPublishesPrefix() = runTest {
        val f = Fixture(this, temporary.root, stale = false)
        f.open()
        val initial = summary.copy(trackCount = 2, availableCount = 2)
        val row = PersonalManualEntry("one", 3, PersonalTrack("track", "track", "Song", true, PersonalCoverage(1, 1, 1)))
        val token = "a".repeat(64)
        f.update(PersonalUpdate(snapshot = PersonalSnapshot(listOf(initial), emptyList())))
        val one = f.source.watch(summary.id)
        val two = f.source.watch(summary.id)
        assertEquals(1, f.socket.commands().size)
        f.update(PersonalUpdate(part = PlaylistPart(initial, token = token, offset = 0, rows = listOf(row), more = true)))
        assertTrue(f.source.replica.snapshots.value.details.isEmpty())
        assertEquals(JsonPrimitive(1), f.socket.commands().last()["offset"])
        assertEquals(JsonPrimitive(token), f.socket.commands().last()["known"])
        val changed = initial.copy(name = "Confirmed", revision = 2)
        f.update(PersonalUpdate(change = PersonalChange(playlist = changed, rowsInvalid = true)))
        assertEquals(2, f.socket.commands().size)
        f.update(PersonalUpdate(part = PlaylistPart(initial, token = token, offset = 1, rows = listOf(row.copy(id = "two", position = 7)), more = false)))
        assertEquals(3, f.socket.commands().size)
        assertNull(f.socket.commands().last()["known"])
        assertTrue(f.source.replica.snapshots.value.details.isEmpty())
        f.update(PersonalUpdate(part = PlaylistPart(changed, token = "b".repeat(64), offset = 0,
            rows = listOf(row, row.copy(id = "two", position = 7)), more = false)))
        assertEquals(listOf("one", "two"), f.saved.read().details.getValue(summary.id).rows.map { it.id })
        assertEquals(2L, f.source.replica.snapshots.value.details.getValue(summary.id).revision)
        one.close(); two.close()
    }

    @Test fun selectedTrackIsNotDownloadedUntilTheDetailContainingItsOccurrenceIsDurable() = runTest {
        val initial = PersonalCacheSnapshot(listOf(summary), details = mapOf(summary.id to ReplicaPlaylist(1, emptyList(), at.toEpochMilli())),
            listsAt = at.toEpochMilli(), recentAt = at.toEpochMilli())
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(this, temporary.root, stale = false, initial = initial, beforePersist = {
            if (it.details[summary.id]?.revision == 2L) { held.complete(Unit); release.await() }
        })
        runCurrent()
        val grant = requireNotNull(fixture.sessions.onlineGrant.value)
        val row = PersonalManualEntry("added", 0, PersonalTrack("track", "track", "Song", true, PersonalCoverage(1, 1, 1)))
        fixture.open()
        fixture.update(PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary), emptyList())))
        fixture.update(PersonalUpdate(change = PersonalChange(
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

    @Test fun retiredRemovalCannotDeleteSuccessorDownloadForTheSameAccount() = runTest {
        val fixture = Fixture(this, temporary.root, stale = false)
        val retired = requireNotNull(fixture.sessions.onlineGrant.value).identity
        val key = SongKey(MusicOwner(origin.value, retired.accountId), "track")
        val dir = temporary.newFolder()
        val store = MusicBlobStore(dir, freeBytes = { Long.MAX_VALUE })
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val removal = async {
            assertTrue(fixture.sessions.isCurrent(retired))
            admitted.complete(Unit)
            release.await()
            store.removeDownload(key, 3) { replace -> fixture.sessions.commitLocalIfCurrent(retired, replace) }
        }
        admitted.await()
        fixture.sessions.signOut()
        fixture.sessions.signIn(origin.value, retired.username, "fixture")
        runCurrent()
        val successor = requireNotNull(fixture.sessions.onlineGrant.value)
        assertEquals(retired.accountId, successor.identity.accountId)
        assertNotEquals(retired.incarnation, successor.identity.incarnation)
        store.download(key, "Successor's song", 3, { true }, { descriptor(key.id) },
            commit = { fixture.sessions.publishIfCurrent(successor, it) }) { _, consume -> consume(bytes.inputStream()) }
        release.complete(Unit)
        assertFalse(removal.await())
        assertTrue(store.songs.value.single().direct)
        val reopened = MusicBlobStore(dir)
        reopened.pin(key, downloadOnly = true) { true }!!.use { pin ->
            pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }
        }
        assertTrue(reopened.removeDownload(key, 3) { replace -> fixture.sessions.commitLocalIfCurrent(successor.identity, replace) })
        assertNull(MusicBlobStore(dir).pin(key, downloadOnly = true) { true })
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

    @Test fun lateOnlineCatalogWritesCannotPublishAfterManualModeEvenWithSameIdentity() = runTest {
        val f = Fixture(this, temporary.root, stale = false)
        val grant = requireNotNull(f.sessions.onlineGrant.value)
        val identity = grant.identity
        val catalog = LocalMusicCatalog(temporary.root, identity, commit = { f.sessions.commitLocalIfCurrent(identity, it) }) { setOf("track") }
        fun metadata(name: String) = LocalTrackMetadata.Cached("track", name)
        catalog.rememberTracks(listOf(metadata("Saved")))
        catalog.rememberArtwork(dev.spofify.mobile.core.library.MusicKind.TRACK, "track", byteArrayOf(4))
        val reachedCommit = CompletableDeferred<Unit>()
        val releaseCommit = CountDownLatch(1)
        val gated = f.sessions.catalogCommit(grant)
        val work = backgroundScope.async(Dispatchers.IO) {
            runCatching { catalog.rememberTracks(listOf(LocalTrackMetadata.Library(dev.spofify.mobile.core.library.MusicItem(
                dev.spofify.mobile.core.library.MusicKind.TRACK, "track", "Late", emptyList(), "", "", "", 0, 0, true, 1, 1))), commit = { rename ->
                reachedCommit.complete(Unit)
                check(releaseCommit.await(10, TimeUnit.SECONDS))
                gated(rename)
            }) }.exceptionOrNull()
        }
        try {
            withContext(Dispatchers.IO) { withTimeout(10_000) { reachedCommit.await() } }
            f.sessions.setManualOffline(true)
            assertTrue(f.sessions.isCurrent(identity))
            assertNull(f.sessions.onlineGrant.value)
        } finally { releaseCommit.countDown() }
        assertTrue(work.await() is CancellationException)
        val art = runCatching { catalog.rememberArtwork(dev.spofify.mobile.core.library.MusicKind.TRACK, "track", null, gated) }
        assertTrue(art.exceptionOrNull() is CancellationException)
        val reopened = LocalMusicCatalog(temporary.root, identity) { setOf("track") }
        assertEquals("Saved", reopened.availableTracks().single().name)
        assertArrayEquals(byteArrayOf(4), reopened.artwork(dev.spofify.mobile.core.library.MusicKind.TRACK, "track"))
    }

    private inner class Fixture(private val test: TestScope, root: java.io.File, stale: Boolean,
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
        val socket = TestSocket()
        private val channel = AccountChannel(PlaybackTransport { _, _, _, _ -> socket }, object : AudioExecutor {
            override val observations = emptyFlow<AudioObservation>()
            override fun facts() = AudioFacts()
            override fun pause() = Unit
            override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double) = AudioResult.OK
            override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
                desiredPlaying: Boolean, isAuthorized: () -> Boolean) = AudioResult.OK
        }, test.backgroundScope)

        fun open() {
            channel.connect("Phone", "phone", requireNotNull(sessions.onlineGrant.value))
            socket.offer("""{"type":"snapshot","sessionId":"room","connectionId":"conn","revision":0,"owner":{"generation":0},"devices":[{"deviceId":"phone","name":"Phone","canPlay":true}],"player":{"queue":[],"cursor":{"key":0,"positionMs":0,"desiredPlaying":false},"repeat":"off","shuffle":false},"serverTimeMs":${at.toEpochMilli()}}""")
            test.runCurrent()
        }

        fun update(update: PersonalUpdate) {
            socket.offer(buildJsonObject {
                put("type", "personal")
                put("update", Json { explicitNulls = false }.encodeToJsonElement(update))
            }.toString())
            test.runCurrent()
        }
        val source: AccountPersonalSource
        init {
            val stamp = at.toEpochMilli() - if (stale) 300_000 else 0
            saved.write(initial ?: PersonalCacheSnapshot(listOf(summary), listsAt = stamp, recentAt = stamp))
            sessions.start()
            test.runCurrent()
            assertEquals(identity, sessions.onlineGrant.value?.identity)
            source = AccountPersonalSource(root, identity, sessions, test.backgroundScope,
                replica = AccountPersonalReplica(clockProxy), io = dispatcher,
                monotonicMs = { test.testScheduler.currentTime }, beforePersist = beforePersist)
            source.attach(channel)
        }
    }

    private class TestSocket : PlaybackConnection {
        private val inbox = Channel<String>(Channel.UNLIMITED)
        override val messages = inbox.receiveAsFlow()
        override val isOpen get() = open
        private var open = true
        val sent = mutableListOf<JsonObject>()
        fun offer(frame: String) { check(inbox.trySend(frame).isSuccess) }
        fun commands() = sent.mapNotNull { it["command"]?.jsonObject }
        override fun close() { open = false; inbox.close() }
        override fun send(text: String): Boolean = open.also { if (it) sent += Json.parseToJsonElement(text).jsonObject }
    }
}
