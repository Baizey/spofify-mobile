package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.offline.*
import dev.spofify.mobile.core.personal.PersonalCoverage
import dev.spofify.mobile.core.personal.PersonalTrack
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicStorageAccountingTest {
    @get:Rule val root = TemporaryFolder()

    @Test fun failedUnlinkKeepsBytesVisibleAndClearRetriesWithoutReclaimingDraftAudio() = runBlocking {
        val dir = root.root
        val owner = MusicOwner("https://one.example", "account")
        val auto = SongKey(owner, "auto")
        val draft = SongKey(owner, "draft")
        val size = bytes.size.toLong()
        var blocked: Path? = null
        fun openStore() = MusicBlobStore(dir, { dir.usableSpace }) { path ->
            if (path == blocked) throw IOException("fixture unlink error")
            Files.deleteIfExists(path)
        }
        val store = openStore()
        store.fill(auto, auto.id, 10, emptyList(), { true }, { descriptor(auto.id) }) { _, consume ->
            consume(bytes.inputStream())
        }
        val autoBlob = dir.listFiles()!!.single { it.extension == "blob" }
        val autoMeta = dir.listFiles()!!.single { it.extension == "meta" }
        store.stage("draft", draft, draft.id, 10, { true }, { descriptor(draft.id) }) { _, consume ->
            consume(bytes.inputStream())
        }
        val draftOnly = MusicStorageUsage(offlineBytes = size, pendingDownloadBytes = size)
        blocked = autoBlob.toPath()
        assertThrows(IOException::class.java) { store.clearAuto(owner) }
        assertArrayEquals(bytes, autoBlob.readBytes())
        assertNull(store.pin(auto) { true })
        assertEquals(draftOnly.copy(cacheBytes = size), store.storageUsage(owner).first())

        blocked = autoMeta.toPath()
        assertThrows(IOException::class.java) { store.clearAuto(owner) }
        assertFalse(autoBlob.exists()); assertTrue(autoMeta.exists())
        assertEquals(draftOnly, store.storageUsage(owner).first())

        // Even while the old metadata can't be unlinked, save fresh audio for the same track.
        val collection = OfflineCollection(OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, "saved"),
            "Saved", OfflinePlaylistKind.MANUAL, "", 1)
        val track = PersonalTrack("track", auto.id, auto.id, true, PersonalCoverage(1, 1, 0))
        val manifest = OfflineManifest(collection, "fence", listOf(OfflineOccurrence("entry", 0, track)), 1, 1)
        store.stage("saved", auto, auto.id, 10, { true }, { descriptor(auto.id) }) { _, consume ->
            consume(bytes.inputStream())
        }
        store.commit(owner, manifest, 10) {}
        assertTrue(autoMeta.exists())
        val reopened = openStore()
        assertEquals(manifest, reopened.select(owner, "saved"))
        assertEquals(draftOnly.copy(offlinePlaylistCount = 1, offlineBytes = 2 * size), reopened.storageUsage(owner).first())
        blocked = null
        reopened.clearAuto(owner)
        assertFalse(autoMeta.exists())
        assertEquals(setOf(draft), reopened.resumePlaylist(owner, "draft", setOf(draft)) {})
        reopened.pin(auto) { true }!!.use { pin ->
            pin.open(0).use { assertArrayEquals(bytes, it.input.readBytes()) }
        }
    }

    @Test fun clearFencesWaitingAutoWithoutReclaimingVerifiedOrInFlightPlaylistAudio() = runBlocking {
        val owner = MusicOwner("https://one.example", "account")
        val size = bytes.size.toLong()
        fun blobBytes() = root.root.listFiles()!!.filter { it.extension == "blob" }.sumOf { it.length() }
        val store = MusicBlobStore(root.root)
        suspend fun usage() = store.storageUsage(owner).first()
        val reused = SongKey(owner, "reused")
        val pending = SongKey(owner, "pending")
        store.fill(reused, reused.id, 10, emptyList(), { true }, { descriptor(reused.id) }) { _, consume ->
            consume(bytes.inputStream())
        }
        store.stage("draft", reused, reused.id, 10, { true }, { error("Expected to reuse blob") }) { _, _ ->
            error("Unexpected download")
        }
        assertEquals(MusicStorageUsage(offlineBytes = size, pendingDownloadBytes = size), usage())
        val proceed = CompletableDeferred<Unit>()
        val stage = launch(start = CoroutineStart.UNDISPATCHED) {
            store.stage("draft", pending, pending.id, 10, { true }, { descriptor(pending.id) }) { _, consume ->
                consume(bytes.inputStream())
                proceed.await()
            }
        }
        try {
            val joining = launch(start = CoroutineStart.UNDISPATCHED) {
                store.fill(pending, pending.id, 10, emptyList(), { true }, { error("Expected to join fill") }) { _, _ ->
                    error("Unexpected download")
                }
            }
            assertEquals(size, root.root.listFiles()!!.single { it.extension == "tmp" }.length())
            assertEquals(size, usage().totalBytes) // A not-yet-promoted .tmp is not counted.
            assertEquals(size, blobBytes())
            store.clearAuto(owner)
            assertNull(store.pin(reused) { true })
            assertEquals(MusicStorageUsage(offlineBytes = size, pendingDownloadBytes = size), usage())
            proceed.complete(Unit)
            joinAll(stage, joining)
        } finally { proceed.complete(Unit) }
        assertEquals(MusicStorageUsage(offlineBytes = 2 * size, pendingDownloadBytes = 2 * size), usage())
        assertEquals(2 * size, blobBytes())
        assertTrue(store.songs.value.isEmpty())
        val recovered = MusicBlobStore(root.root)
        assertEquals(usage(), recovered.storageUsage(owner).first())
        assertTrue(recovered.remove(owner, "draft", 10) {})
        assertEquals(MusicStorageUsage(), recovered.storageUsage(owner).first())
        assertEquals(0L, blobBytes())
    }
}
