package dev.spofify.mobile.playback

import dev.spofify.mobile.core.cache.MusicBlobStore
import dev.spofify.mobile.core.cache.MusicOwner
import dev.spofify.mobile.core.cache.MusicSourceChoice
import dev.spofify.mobile.core.cache.SongKey
import dev.spofify.mobile.core.offline.OfflineMediaDescriptor
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineMusicRangesTest {
    @get:Rule val root = TemporaryFolder()
    private val key = SongKey(MusicOwner("https://music.example", "account"), "track")
    private val bytes = byteArrayOf(1, 2, 3, 4)

    private suspend fun saveForOffline(store: MusicBlobStore) {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        store.fill(key, "Song", 3, listOf(key.id), { true }, {
            OfflineMediaDescriptor(key.id, "sha256:$sha", sha, bytes.size.toLong(), "mp3", "audio/mpeg", "unused")
        }) { _, consume -> consume(bytes.inputStream()) }
        assertTrue(store.offlineReference(key, "playlist", retain = true, cap = 3))
        store.clearAuto(key.owner)
        assertTrue(store.hasOfflineReference(key))
    }

    @Test fun onlineRangePrefersOfflineOnlyBytesEvenDuringStorageRecovery() = runTest {
        saveForOffline(MusicBlobStore(root.root))
        val recovered = MusicBlobStore(root.root)
        val ready = CompletableDeferred<MusicBlobStore>()
        val opening = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val source = AudioSourceOwner("period", key.id)
        OnlineMusicRanges(ready, MusicSourceChoice({ id ->
            assertTrue("A pin isn't looked up before store recovery", ready.isCompleted)
            recovered.pin(key.copy(id = id), current::get)
        }), current::get).use { ranges ->
            val loaded = async(Dispatchers.IO) {
                opening.countDown()
                requireNotNull(ranges.openLocal(source, 0)).use { it.input.readBytes() }
            }
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            ready.complete(recovered)
            assertArrayEquals(bytes, loaded.await())
            requireNotNull(ranges.openLocal(source, 2)).use { stream ->
                assertArrayEquals(byteArrayOf(3, 4), stream.input.readBytes())
                current.set(false)
                assertThrows(IOException::class.java) { stream.input.read() }
            }
            assertThrows(IOException::class.java) { ranges.openLocal(source, 0) }
        }
    }

    @Test fun firstRangeAtExactEofIsLocalAndPastEofCannotFallBackToHttp() = runTest {
        val store = MusicBlobStore(root.root)
        saveForOffline(store)
        fun ranges() = OnlineMusicRanges(CompletableDeferred(store), MusicSourceChoice({ id ->
            store.pin(key.copy(id = id)) { true }
        })) { true }
        val source = AudioSourceOwner("period", key.id)
        val eof = bytes.size.toLong()
        ranges().use { atEnd ->
            requireNotNull(atEnd.openLocal(source, eof)).use {
                assertEquals(0L, it.length)
                assertEquals(-1, it.input.read())
            }
        }
        ranges().use { beyondEnd ->
            assertThrows(IOException::class.java) { beyondEnd.openLocal(source, eof + 1) }
            assertThrows(IOException::class.java) { beyondEnd.openLocal(source, -1) }
            requireNotNull(beyondEnd.openLocal(source, 0)).use { assertArrayEquals(bytes, it.input.readBytes()) }
        }
        ranges().use { missing ->
            assertNull(missing.openLocal(AudioSourceOwner("uncached-period", "uncached"), eof + 1))
        }
    }

    @Test fun missingSavedBlobAllowsOnlineFallbackButDoesNotMixRepresentations() = runTest {
        val store = MusicBlobStore(root.root)
        saveForOffline(store)
        val blob = root.root.listFiles()!!.single { it.extension == "blob" }
        val moved = File(root.root, "temporarily-missing")
        assertTrue(blob.renameTo(moved))
        fun ranges() = OnlineMusicRanges(CompletableDeferred(store), MusicSourceChoice({ store.pin(key) { true } })) { true }
        val source = AudioSourceOwner("period", key.id)
        ranges().use { ranges ->
            assertNull(ranges.openLocal(source, 0)) // The online data source may now use HTTP.
            assertTrue(moved.renameTo(blob))
            assertNull(ranges.openLocal(source, 2)) // Same extractor: keep its first representation.
        }
        ranges().use { next ->
            requireNotNull(next.openLocal(source, 2)).use { assertEquals(3, it.input.read()) }
        }
    }
}
