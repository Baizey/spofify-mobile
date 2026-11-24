package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.auth.FeatureAuthority
import dev.spofify.mobile.core.cache.*
import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.personal.*
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflinePlaylistRepositoryTest {
    @get:Rule val root = TemporaryFolder()
    private val owner = MusicOwner("https://one.example", "account")
    private fun key(id: String) = SongKey(owner, id)
    private fun playlist(id: String, rev: Long, vararg ids: String, kind: OfflinePlaylistKind = OfflinePlaylistKind.DERIVED): OfflineManifest {
        val ref = OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, id)
        val c = OfflineCollection(ref, "name $rev", kind, "description $rev", rev)
        val rows = ids.mapIndexed { i, track -> OfflineOccurrence("entry-$rev-$i", i.toLong(),
            PersonalTrack("track", track, "title $rev", true, PersonalCoverage(12, 9, 2),
                listOf(PersonalArtist("artist", "artist name")), listOf("jazz"), "album", "album name",
                "single", "2026", "relation", 123456, 1, 2)) }
        return OfflineManifest(c, "fence-$rev", rows, rows.size.toLong(), rows.size.toLong())
    }
    private fun audio(data: ByteArray = bytes) = AudioStream(data.inputStream(), data.size.toLong(), permitted = { true }) {}
    private fun repo(store: MusicBlobStore, account: MusicOwner = owner,
        manifest: suspend (OfflineCollectionRef) -> OfflineManifest = { error("unexpected manifest call") },
        prepare: suspend (String) -> OfflineMediaDescriptor = ::descriptor,
        open: suspend (OfflineMediaDescriptor) -> AudioStream = { audio() },
    ) = OfflinePlaylistRepository(store, account, manifest, prepare, open)
    private suspend fun OfflinePlaylistRepository.save(id: String = "p") = refresh(id, 3) { true }
    private fun blobCount(dir: File) = dir.listFiles()!!.count { it.extension == "blob" }

    @Test fun replacementSyncFailureKeepsOldHoldsUntilSameProcessOrReopenRecovers() = runBlocking {
        for (reopenFirst in listOf(false, true)) {
            val dir = root.newFolder()
            var failNextSync = false
            var insideFence = false
            var failures = 0
            val store = MusicBlobStore(dir, { Long.MAX_VALUE }, java.nio.file.Files::deleteIfExists) { path ->
                assertFalse("Directory sync must not hold the session fence", insideFence)
                if (failNextSync) {
                    failNextSync = false
                    failures++
                    throw IOException("Injected post-rename directory sync failure")
                }
                syncPlaylistDirectory(path)
            }
            val previous = playlist("p", 1, "old")
            val replacement = playlist("p", 2, "new")
            var manifest = previous
            val repository = repo(store, manifest = { manifest })
            repository.save()
            manifest = replacement
            val failure = runCatching {
                repository.refresh("p", 3, commit = { rename ->
                    insideFence = true
                    try { rename(); failNextSync = true; true } finally { insideFence = false }
                }) { true }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(1, failures)
            assertEquals(setOf(key("old")), store.songs.value.filter { it.offline }.map { it.key }.toSet())
            assertTrue(store.hasOfflineReference(key("old")))
            assertFalse(store.hasOfflineReference(key("new")))
            assertNull(store.pin(key("new"), downloadOnly = true) { true })
            assertEquals(2, blobCount(dir))
            if (!reopenFirst) {
                assertEquals(replacement, repository.select("p"))
                assertEquals(setOf(key("new")), store.songs.value.filter { it.offline }.map { it.key }.toSet())
                assertFalse(store.hasOfflineReference(key("old")))
                assertEquals(1, blobCount(dir))
            }
            val reopened = MusicBlobStore(dir)
            assertEquals(listOf(replacement), repo(reopened).complete())
            assertEquals(setOf(key("new")), reopened.songs.value.filter { it.offline }.map { it.key }.toSet())
            assertEquals(1, blobCount(dir))
            reopened.pin(key("new"), downloadOnly = true) { true }!!.use { pin ->
                pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }
            }
        }
    }

    @Test fun deltaKeepsEveryOccurrenceAndMetadataWhileSharingAutoPlaylistsAndPins() = runBlocking {
        val store = MusicBlobStore(root.root)
        var p = playlist("p", 1, "b", "a", "b")
        val q = playlist("q", 7, "a", "a")
        val prepared = mutableListOf<String>(); val opened = mutableListOf<String>()
        val saved = repo(store, manifest = { if (it.id == "p") p else q },
            prepare = { prepared.add(it); descriptor(it) }, open = { opened.add(it.trackId); audio() })
        store.fill(key("a"), "auto", 3, emptyList(), { true }, { descriptor("a") }) { _, consume -> consume(bytes.inputStream()) }
        saved.save(); saved.save("q")
        store.clearAuto(owner); store.limit(owner, 3, emptyList())
        val pin = store.pin(key("b")) { true }!!
        store.pin(key("b"), downloadOnly = true) { true }!!.use { offlinePin ->
            offlinePin.open(0).use { stream ->
                assertEquals(bytes.first().toInt(), stream.input.read())
                p = playlist("p", 2, "c", "a", "c")
                saved.save()
                assertFalse(offlinePin.isDownloaded())
                assertThrows(IOException::class.java) { stream.input.read() }
                assertThrows(IOException::class.java) { offlinePin.open(0) }
            }
        }
        assertEquals(listOf("b", "c"), prepared); assertEquals(prepared, opened)
        assertEquals(p, saved.select("p")); assertNull(store.pin(key("b")) { true })
        assertEquals(3, blobCount(root.root)); pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }; pin.close()
        val reopened = repo(MusicBlobStore(root.root))
        assertEquals(setOf(p, q), reopened.complete().toSet()); assertEquals(2, blobCount(root.root))
        assertTrue(reopened.remove("p", 3) { true }); assertEquals(1, blobCount(root.root))
        assertEquals(listOf(q), reopened.complete()); assertTrue(reopened.remove("q", 3) { true })
        assertFalse(reopened.remove("q", 3) { true }); assertTrue(root.root.listFiles()!!.isEmpty())
    }

    @Test fun savedPlaylistPinDoesNotBecomeAnHttpMissWhileAnotherManifestIsCommitting() = runBlocking {
        val locked = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val holdCommit = AtomicBoolean(false)
        val store = MusicBlobStore(root.root) {
            if (holdCommit.get()) {
                locked.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
            Long.MAX_VALUE
        }
        repo(store, manifest = { playlist("p", 1, "saved") }).save()
        assertFalse(store.songs.value.single().auto)
        holdCommit.set(true)
        val committing = launch { repo(store, manifest = { playlist("q", 1) }).save("q") }
        try {
            withTimeout(5_000) { locked.await() }
            val readerThread = CompletableDeferred<Thread>()
            MusicSourceChoice({ id -> store.pin(key(id)) { true } }).use { choice ->
                val opening = async(Dispatchers.IO) {
                    readerThread.complete(Thread.currentThread())
                    choice.openLocal("saved", 1)?.use { it.input.readBytes() }
                }
                try {
                    val reader = withTimeout(5_000) { readerThread.await() }
                    withTimeout(5_000) {
                        while (!opening.isCompleted && reader.state != Thread.State.WAITING) delay(1)
                    }
                } finally { release.countDown() }
                assertArrayEquals(bytes.copyOfRange(1, bytes.size), opening.await())
            }
        } finally { release.countDown(); committing.join() }
    }

    @Test fun storageUsageDeduplicatesSharedAudioAndClearOnlyReclaimsAutomaticRetention() = runBlocking {
        val store = MusicBlobStore(root.root)
        val other = owner.copy(accountId = "other")
        val size = bytes.size.toLong()
        val p = playlist("p", 1, "shared", "offline", "shared")
        val q = playlist("q", 1, "shared")
        val saved = repo(store, manifest = { if (it.id == "p") p else q })
        suspend fun auto(key: SongKey) = store.fill(key, key.id, 3, emptyList(), { true }, { descriptor(key.id) }) {
            _, consume -> consume(bytes.inputStream())
        }
        suspend fun usage() = store.storageUsage(owner).first()
        fun diskBytes() = root.root.listFiles()!!.filter { it.extension == "blob" }.sumOf(File::length)
        auto(key("shared")); saved.save(); saved.save("q")
        auto(key("evict")); auto(key("playing")); auto(key("draft-shared")); auto(SongKey(other, "evict"))
        val draft = repo(store, manifest = { playlist("unfinished", 1, "draft-shared", "draft-only", "fail") },
            open = { if (it.trackId == "fail") throw IOException("fixture interrupted") else audio() })
        assertTrue(runCatching { draft.save("unfinished") }.isFailure)
        val before = MusicStorageUsage(offlinePlaylistCount = 2, offlineBytes = 4 * size,
            pendingDownloadBytes = 2 * size, cachedSongCount = 2, cacheBytes = 2 * size)
        assertEquals(before, usage()); assertEquals(6 * size, usage().totalBytes)
        assertEquals(size, store.storageUsage(other).first().totalBytes)
        assertEquals(diskBytes(), usage().totalBytes + size)

        store.pin(key("playing")) { true }!!.use { pin ->
            store.clearAuto(owner)
            assertEquals(before.copy(cachedSongCount = 0, cacheBytes = size, inUseBytes = size), usage())
            assertEquals(5 * size, usage().totalBytes); assertEquals(diskBytes(), usage().totalBytes + size)
            assertNull(store.pin(key("evict")) { true }); assertNull(store.pin(key("playing")) { true })
            assertNull(store.pin(key("draft-shared")) { true }); assertNull(store.pin(key("draft-only")) { true })
            pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }
        }
        assertEquals(before.copy(cachedSongCount = 0, cacheBytes = 0), usage())
        assertEquals(diskBytes(), usage().totalBytes + size)
        val reopened = MusicBlobStore(root.root)
        assertEquals(usage(), reopened.storageUsage(owner).first())
        assertEquals(setOf(p, q), repo(reopened).complete().toSet())
        reopened.pin(key("shared")) { true }!!.use { pin ->
            pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }
        }
        reopened.pin(SongKey(other, "evict")) { true }!!.close()
        assertEquals(setOf("p", "q", "unfinished"), repo(reopened).retainedIds())
        for (id in listOf("p", "q", "unfinished")) assertTrue(repo(reopened).remove(id, 3) { true })
        assertEquals(MusicStorageUsage(), reopened.storageUsage(owner).first()); assertEquals(size, diskBytes())
    }

    @Test fun samePlaylistAndTrackIdsIsolateOriginsAndAccountsIncludingRemovalAndReplacement() = runBlocking {
        val accounts = listOf(owner, owner.copy(accountId = "other"), owner.copy(origin = "http://one.example"))
        var store = MusicBlobStore(root.root)
        for ((i, account) in accounts.withIndex()) {
            val m = playlist("p", i + 1L, "a")
            val r = repo(store, account, { m }); assertNull(r.select("p")); r.save()
        }
        assertEquals(3, blobCount(root.root)); store = MusicBlobStore(root.root)
        val empty = playlist("p", 4)
        repo(store, manifest = { empty }).save()
        assertEquals(listOf(empty), repo(store).complete()); assertEquals(2, blobCount(root.root))
        assertTrue(repo(store).remove("p", 3) { true })
        accounts.drop(1).forEachIndexed { i, account ->
            assertEquals(listOf(playlist("p", i + 2L, "a")), repo(store, account).complete())
            store.pin(SongKey(account, "a")) { true }!!.close()
        }
    }

    @Test fun progressCountsVerifiedOccurrencesButNeverPublishesAPartialPlaylist() = runBlocking {
        val store = MusicBlobStore(root.root)
        val manifest = playlist("p", 1, "a", "b", "a")
        val updates = mutableListOf<OfflinePlaylistProgress>()
        val touched = mutableListOf<String>()
        var fail = true
        val repository = repo(store, manifest = { manifest }, open = {
            touched += it.trackId
            audio(if (fail && it.trackId == "b") bytes.reversedArray() else bytes)
        })
        assertTrue(runCatching { repository.refresh("p", 3, onProgress = { updates += it }) { true } }.isFailure)
        assertEquals(listOf(OfflinePlaylistProgress(0, 3), OfflinePlaylistProgress(2, 3)), updates)
        assertEquals(listOf("a", "b"), touched)
        assertTrue(repository.completeIds().first().isEmpty())
        fail = false
        updates.clear()
        repository.refresh("p", 3, onProgress = { updates += it }) { true }
        assertEquals(listOf(OfflinePlaylistProgress(2, 3), OfflinePlaylistProgress(3, 3)), updates)
        assertEquals(listOf("a", "b", "b"), touched)
        assertEquals(setOf("p"), repository.completeIds().first())
    }

    @Test fun completedIdsFollowDurableMembershipEvenWhenNoAudioBlobsChange() = runBlocking {
        val store = MusicBlobStore(root.root)
        val first = repo(store, manifest = { playlist(it.id, 1, "shared") })
        val other = repo(store, owner.copy(accountId = "other"))
        val completed = first.completeIds()
        assertEquals(emptySet<String>(), completed.first())
        first.save("one")
        val audio = store.songs.value
        first.save("two")
        assertEquals(audio, store.songs.value)
        assertEquals(setOf("one", "two"), completed.first())
        assertEquals(emptySet<String>(), other.completeIds().first())
        first.remove("one", 3) { true }
        assertEquals(audio, store.songs.value)
        assertEquals(setOf("two"), completed.first())
        assertEquals(setOf("two"), repo(MusicBlobStore(root.root)).completeIds().first())
    }

    @Test fun eitherPlaylistKindCanFinishWhileTheOtherDisjointDownloadIsSuspended() = runBlocking {
        val manual = playlist("manual", 1, "m1", "m2", "m3", "m4", kind = OfflinePlaylistKind.MANUAL)
        val derived = playlist("derived", 2, "d1", "d2", "d3", "d4")
        for ((first, second) in listOf(manual to derived, derived to manual)) {
            val dir = root.newFolder()
            val store = MusicBlobStore(dir)
            val firstId = first.collection.reference.id
            val secondId = second.collection.reference.id
            val blockedTrack = first.items[1].track.id
            val suspended = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val repository = repo(store, manifest = { if (it.id == firstId) first else second }, open = {
                if (it.trackId == blockedTrack) { suspended.complete(Unit); resume.await() }
                audio()
            })
            val firstProgress = mutableListOf<OfflinePlaylistProgress>()
            val worker = async { repository.refresh(firstId, 3, firstProgress::add) { true } }
            try {
                withTimeout(5_000) { suspended.await() }
                assertEquals(listOf(OfflinePlaylistProgress(0, 4), OfflinePlaylistProgress(1, 4)), firstProgress)
                val secondProgress = mutableListOf<OfflinePlaylistProgress>()
                assertEquals(second, withTimeout(5_000) { repository.refresh(secondId, 3, secondProgress::add) { true } })
                assertEquals((0..4).map { OfflinePlaylistProgress(it, 4) }, secondProgress)
                assertEquals(setOf(secondId), repository.completeIds().first())
                assertNull(store.pin(key(first.items[0].track.id)) { true })
                assertEquals(bytes.size.toLong(), store.storageUsage(owner).first().pendingDownloadBytes)

                val pinnedKey = key(second.items[0].track.id)
                store.pin(pinnedKey) { true }!!.use { pin ->
                    store.clearAuto(owner)
                    store.limit(owner, 3, emptyList())
                    assertEquals(5, blobCount(dir))
                    pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }
                }
                resume.complete(Unit)
                assertEquals(first, withTimeout(5_000) { worker.await() })
                assertEquals((0..4).map { OfflinePlaylistProgress(it, 4) }, firstProgress)
                assertEquals(MusicStorageUsage(offlinePlaylistCount = 2, offlineBytes = 8L * bytes.size),
                    store.storageUsage(owner).first())
                assertEquals(setOf(first, second), repo(MusicBlobStore(dir)).complete().toSet())
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun disjointPlaylistsTakeTurnsReadingOneAccountsManifests() = runBlocking {
        val store = MusicBlobStore(root.root)
        val firstReading = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondReading = CompletableDeferred<Unit>()
        val repository = repo(store, manifest = {
            when (it.id) {
                "manual" -> { firstReading.complete(Unit); releaseFirst.await() }
                "derived" -> secondReading.complete(Unit)
            }
            if (it.id == "manual") playlist(it.id, 1, "m", kind = OfflinePlaylistKind.MANUAL) else playlist(it.id, 2, "d")
        })
        val first = async { repository.save("manual") }
        try {
            withTimeout(5_000) { firstReading.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.save("derived") }
            try {
                assertNull(withTimeoutOrNull(200) { secondReading.await() })
                releaseFirst.complete(Unit)
                withTimeout(5_000) { first.await(); second.await() }
                assertEquals(setOf("manual", "derived"), repository.completeIds().first())
            } finally { second.cancelAndJoin() }
        } finally { first.cancelAndJoin() }
    }

    @Test fun repeatedMediaBusyDoesNotBlockAnotherPlaylistOrDiscardVerifiedDrafts() = runBlocking {
        val store = MusicBlobStore(root.root)
        val manual = playlist("manual", 1, "m1", "m2", kind = OfflinePlaylistKind.MANUAL)
        val derived = playlist("derived", 2, "d")
        val opened = mutableListOf<String>()
        var attempts = 0
        val repository = repo(store, manifest = { if (it.id == "manual") manual else derived }, prepare = {
            if (it == "m2" && ++attempts <= 2) throw OfflineException(OfflineFailure.BUSY)
            descriptor(it)
        }, open = { opened += it.trackId; audio() })
        assertEquals(OfflineFailure.BUSY, (runCatching { repository.save("manual") }.exceptionOrNull() as OfflineException).failure)
        assertEquals(2, attempts)
        assertEquals(setOf("manual"), repository.retainedIds())
        assertNull(repository.select("manual"))
        assertEquals(derived, repository.save("derived"))
        val progress = mutableListOf<OfflinePlaylistProgress>()
        assertEquals(manual, repository.refresh("manual", 3, progress::add) { true })
        assertEquals(listOf("m1", "d", "m2"), opened)
        assertEquals(listOf(OfflinePlaylistProgress(1, 2), OfflinePlaylistProgress(2, 2)), progress)
        assertEquals(setOf(manual, derived), repository.complete().toSet())
    }

    @Test fun lateSpacePressureRejectsAnAdmittedTrackWithoutReplacingExistingAudio() = runBlocking {
        val reserve = 64L * 1024 * 1024
        val free = AtomicLong(Long.MAX_VALUE)
        val store = MusicBlobStore(root.root, free::get)
        val old = playlist("p", 1, "old")
        repo(store, manifest = { old }).save()
        val openFirst = CompletableDeferred<Unit>()
        val resumeFirst = CompletableDeferred<Unit>()
        val next = playlist("p", 2, "new")
        val other = playlist("q", 1, "disjoint")
        val repository = repo(store, manifest = { if (it.id == "p") next else other }, open = {
            if (it.trackId == "new") { openFirst.complete(Unit); resumeFirst.await() }
            audio()
        })
        val worker = async { runCatching { repository.save() } }
        try {
            withTimeout(5_000) { openFirst.await() } // Passed descriptor admission with enough space.
            assertEquals(other, withTimeout(5_000) { repository.save("q") })
            free.set(reserve + bytes.size - 1)
            resumeFirst.complete(Unit)
            assertTrue(withTimeout(5_000) { worker.await() }.exceptionOrNull() is IOException)
            assertEquals(setOf(old, other), repository.complete().toSet())
            assertEquals(2, blobCount(root.root))
            assertFalse(root.root.listFiles()!!.any { it.extension == "tmp" })
            assertNull(store.pin(key("new")) { true })
            store.pin(key("old")) { true }!!.use { pin ->
                pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }
            }
        } finally { worker.cancelAndJoin() }
    }

    @Test fun pendingIsNotPlayableAndReopenResumesVerifiedTracksWithoutAnotherTransfer() = runBlocking {
        val dir = root.newFolder("music"); val crash = root.newFolder("process-image")
        val store = MusicBlobStore(dir)
        val original = playlist("p", 1, "a")
        val saved = repo(store, manifest = { original }); saved.save()
        val entered = CompletableDeferred<Unit>()
        val next = playlist("p", 2, "b", "c")
        val updating = repo(store, manifest = { next }, prepare = {
            if (it == "c") { entered.complete(Unit); awaitCancellation() }; descriptor(it)
        })
        val task = launch { updating.save() }
        try {
            withTimeout(5000) { entered.await() }
            assertEquals(listOf(original), saved.complete()); assertEquals(2, blobCount(dir))
            assertEquals(setOf("p"), saved.completeIds().first())
            assertNull(store.pin(key("b")) { true }); assertFalse(store.hasOfflineReference(key("b")))
            assertEquals(listOf("a"), store.songs.value.map { it.key.id })
            store.clearAuto(owner) // Clearing during an incomplete update preserves its verified draft.
            assertEquals(bytes.size.toLong(), store.storageUsage(owner).first().pendingDownloadBytes)
            dir.copyRecursively(crash, overwrite = true) // Process death after verified .meta, before manifest rename.
        } finally { task.cancelAndJoin() }
        val recovered = MusicBlobStore(crash)
        assertEquals(listOf(original), repo(recovered).complete()); assertEquals(2, blobCount(crash))
        assertNull(recovered.pin(key("b")) { true })
        assertEquals(2, blobCount(dir)); assertEquals(original, repo(MusicBlobStore(dir)).select("p"))
        val transfers = mutableListOf<String>()
        val progress = mutableListOf<OfflinePlaylistProgress>()
        val resumed = repo(recovered, manifest = { next }, open = { transfers += it.trackId; audio() })
        resumed.refresh("p", 3, onProgress = { progress += it }) { true }
        assertEquals(listOf("c"), transfers)
        assertEquals(listOf(OfflinePlaylistProgress(1, 2), OfflinePlaylistProgress(2, 2)), progress)
        assertEquals(next, resumed.select("p")); assertEquals(2, blobCount(crash))
        assertEquals(MusicStorageUsage(offlinePlaylistCount = 1, offlineBytes = 2L * bytes.size),
            recovered.storageUsage(owner).first())
        recovered.pin(key("b")) { true }!!.use { pin ->
            pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }
        }
    }

    @Test fun deathAfterReplacementRenameBeforeReclaimRecoversOnlyNewMembership() = runBlocking {
        val dir = root.newFolder("music"); val oldFiles = root.newFolder("unreclaimed")
        val store = MusicBlobStore(dir)
        repo(store, manifest = { playlist("p", 1, "removed") }).save()
        dir.listFiles()!!.filter { it.extension in setOf("blob", "meta") }.forEach { it.copyTo(File(oldFiles, it.name)) }
        val next = playlist("p", 2, "new", "new")
        repo(store, manifest = { next }).save()
        // Image immediately after the atomic rename: old .blob/.meta still there, with no durable playlist ref.
        oldFiles.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }; assertEquals(2, blobCount(dir))
        val reopened = MusicBlobStore(dir)
        assertEquals(listOf(next), repo(reopened).complete()); assertEquals(1, blobCount(dir))
        assertNull(reopened.pin(key("removed")) { true }); reopened.pin(key("new")) { true }!!.close()
    }

    @Test fun badMetadataBadBytesOrManifestSpaceFailureNeverReleaseThePriorCommit() = runBlocking {
        var free = Long.MAX_VALUE
        val store = MusicBlobStore(root.root) { free }
        val old = playlist("p", 1, "a"); repo(store, manifest = { old }).save()
        val next = playlist("p", 2, "a", "b", "c")
        val broken = listOf(
            repo(store, manifest = { throw OfflineException(OfflineFailure.STALE_MANIFEST) }),
            repo(store, manifest = { next.copy(total = 1) }),
            repo(store, manifest = { next }, open = { audio(if (it.trackId == "c") bytes.reversedArray() else bytes) }),
            repo(store, manifest = { playlist("p", 3, "a") }), // no transfers, manifest write still needs space
        )
        for ((i, failing) in broken.withIndex()) {
            if (i == broken.lastIndex) free = 0
            assertTrue(runCatching { failing.save() }.isFailure)
            assertEquals(old, failing.select("p")); assertEquals(if (i == 2) 2 else 1, blobCount(root.root))
            assertFalse(root.root.listFiles()!!.any { it.extension == "tmp" })
        }
        assertEquals(listOf(old), repo(MusicBlobStore(root.root)).complete())
    }

    @Test fun removingIncompletePlaylistReleasesOnlyItsPrivateDraftHolds() = runBlocking {
        val store = MusicBlobStore(root.root)
        val repository = repo(store, manifest = { playlist(it.id, 1, "shared", it.id, "fail") }, open = {
            if (it.trackId == "fail") throw IOException("fixture interrupted")
            audio()
        })
        for (id in listOf("p", "q")) assertTrue(runCatching { repository.save(id) }.isFailure)
        val reopened = repo(MusicBlobStore(root.root))
        assertEquals(setOf("p", "q"), reopened.retainedIds())
        assertTrue(reopened.complete().isEmpty())
        assertTrue(reopened.remove("p", 3) { true })
        assertEquals(setOf("q"), reopened.retainedIds())
        assertEquals(2, blobCount(root.root))
        assertTrue(reopened.remove("q", 3) { true })
        assertEquals(0, blobCount(root.root))
    }

    @Test fun laterRetirementFencesNoncooperativePrepareAndTransientFailureIsBounded() = runBlocking {
        val store = MusicBlobStore(root.root)
        val live = AtomicBoolean(true)
        val m = playlist("p", 1, "a")
        var calls = 0
        val r = repo(store, manifest = { m }, prepare = { calls++; live.set(false); descriptor(it) },
            open = { error("retired body opened") })
        assertTrue(runCatching { r.refresh("p", 3, current = live::get) }.exceptionOrNull() is CancellationException)
        assertEquals(1, calls); assertNull(r.select("p"))
        calls = 0
        val busy = repo(store, manifest = { calls++; throw OfflineException(OfflineFailure.BUSY) })
        assertTrue(runCatching { busy.save() }.exceptionOrNull() is OfflineException)
        assertEquals(2, calls); assertTrue(root.root.listFiles()!!.isEmpty())
    }

    @Test fun joinsAutoFillAndAutoCanAdoptAnInFlightPlaylistFillWithoutDuplicateNetwork() = runBlocking {
        for (autoFirst in listOf(true, false)) {
            val dir = root.newFolder(); val store = MusicBlobStore(dir)
            val entered = CompletableDeferred<Unit>(); val go = CompletableDeferred<Unit>()
            var opens = 0
            suspend fun gate() { opens++; entered.complete(Unit); go.await() }
            val r = repo(store, manifest = { playlist("p", 1, "a") }, open = { gate(); audio() })
            suspend fun auto() = store.fill(key("a"), "auto", 3, emptyList(), { true }, { descriptor("a") }) { _, consume ->
                gate(); consume(bytes.inputStream())
            }
            val first = launch { if (autoFirst) auto() else r.save() }; entered.await()
            val second = launch(start = CoroutineStart.UNDISPATCHED) { if (autoFirst) r.save() else auto() }
            go.complete(Unit); joinAll(first, second)
            assertEquals(1, opens); assertEquals(1, blobCount(dir))
            val row = store.songs.value.single(); assertTrue(row.auto && row.offline)
            assertTrue(r.remove("p", 3) { true }); assertTrue(store.songs.value.single().auto)
        }
    }

    @Test fun cancelDuringOrdinaryVersionedHttpBodyInterruptsSocketWithoutRetiringSessionOrRetrying() = runBlocking {
        val store = MusicBlobStore(root.root)
        val old = playlist("p", 1, "old"); repo(store, manifest = { old }).save()
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
            val d = descriptor("new")
            server.enqueue(MockResponse().setBody(bytes.decodeToString()).setHeader("ETag", "\"${d.mediaVersion}\"")
                .throttleBody(1, 4, TimeUnit.SECONDS))
            val saved = repo(store, manifest = { playlist("p", 2, "new") }, open = {
                api.versionedAudio(it, "spofify_session=fixture; Path=/; HttpOnly", FeatureAuthority(Any()) { true }) { _, _ -> }
            })
            val worker = launch { saved.save() }
            try {
                withTimeout(5000) {
                    while (root.root.listFiles()!!.none { it.extension == "tmp" && it.length() == 1L }) delay(10)
                }
                withTimeout(1500) { worker.cancelAndJoin() }
                assertEquals(1, server.requestCount); assertEquals(old, saved.select("p"))
                assertFalse(root.root.listFiles()!!.any { it.extension == "tmp" })
            } finally { api.cancel(); worker.cancelAndJoin() }
        }
        assertEquals(old, repo(MusicBlobStore(root.root)).select("p")); assertEquals(1, blobCount(root.root))
    }
}
