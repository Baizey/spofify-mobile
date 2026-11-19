package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.offline.OfflineMediaDescriptor
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal val bytes = "complete track".toByteArray()
internal fun descriptor(id: String): OfflineMediaDescriptor {
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return OfflineMediaDescriptor(id, "sha256:$sha", sha, bytes.size.toLong(), "mp3", "audio/mpeg", "unused")
}

class MusicBlobStoreTest {
    @get:Rule val root = TemporaryFolder()
    private val owner = MusicOwner("https://one.example", "123")
    private val key = SongKey(owner, "a")
    private fun MusicBlobStore.open(k: SongKey, offset: Long, current: () -> Boolean) = pin(k, current)?.use { it.open(offset) }
    private suspend fun MusicBlobStore.put(k: SongKey = key, data: ByteArray = bytes) =
        fill(k, "same title", 3, listOf(k.id), { true }, { descriptor(k.id) }) { _, consume -> consume(data.inputStream()) }

    @Test fun seekableReopenAndSessionScopedReadsNeverUseDisplayNames() = runBlocking {
        MusicBlobStore(root.root).put()
        val store = MusicBlobStore(root.root)
        var live = true
        val stream = store.open(key, 4) { live }!!
        stream.use {
            assertEquals(bytes.size - 4L, it.length)
            assertEquals(bytes[4].toInt(), it.input.read())
            live = false
            assertThrows(IOException::class.java) { it.input.read() }
        }
        assertNull(store.open(key, 0) { false })
        for (other in listOf(owner.copy(origin = "http://one.example"), owner.copy(accountId = "456"))) {
            assertNull(store.open(key.copy(owner = other), 0) { true })
            store.put(key.copy(owner = other))
        }
        store.clearAuto(owner)
        assertEquals(2, store.songs.value.size)
    }

    @Test fun badCompleteShortAndLongBodiesNeverPublishOrLeaveStages() = runBlocking {
        val store = MusicBlobStore(root.root)
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf(bytes.size + 1), bytes.reversedArray())) {
            assertTrue(runCatching { store.put(data = bad) }.isFailure)
            assertNull(store.open(key, 0) { true })
            assertEquals(0, root.root.listFiles()!!.size)
        }
    }

    @Test fun recoveryDiscardsOrphanStagingAndSameLengthCorruption() = runBlocking {
        MusicBlobStore(root.root).put()
        root.root.listFiles()!!.single { it.extension == "blob" }.writeBytes(bytes.reversedArray())
        File(root.root, "interrupted.tmp").writeText("partial")
        File(root.root, "orphan.blob").writeText("orphan")
        assertTrue(MusicBlobStore(root.root).songs.value.isEmpty())
        assertEquals(0, root.root.listFiles()!!.size)
    }

    @Test fun autoTwoPersistentReferencesAndAnActiveReaderShareOneBlob() = runBlocking {
        val store = MusicBlobStore(root.root)
        store.put(); store.offlineReference(key, "first", true, 3); store.offlineReference(key, "second", true, 3)
        store.clearAuto(owner, "a")
        val reopened = MusicBlobStore(root.root)
        val held = reopened.songs.value.single()
        assertFalse(held.auto); assertTrue(held.offline); assertEquals(bytes.size.toLong(), held.bytes)
        reopened.put() // adopt the shared bytes, no second fill
        val reader = reopened.open(key, 1) { true }!!
        reopened.clearAuto(owner); reopened.offlineReference(key, "first", false, 3)
        assertTrue(reopened.hasOfflineReference(key))
        reopened.offlineReference(key, "second", false, 3)
        assertNull(reopened.open(key, 0) { true }); assertFalse(reopened.songs.value.single().offline)
        assertEquals(1, root.root.listFiles()!!.count { it.extension == "blob" })
        reader.use { assertArrayEquals(bytes.drop(1).toByteArray(), it.input.readBytes()) }
        assertTrue(reopened.songs.value.isEmpty()); assertEquals(0, root.root.listFiles()!!.size)
    }

    @Test fun lruSurvivesReopenAndBudgetExcludesOfflineHolds() = runBlocking {
        var store = MusicBlobStore(root.root)
        for (id in listOf("a", "b", "c")) store.put(key.copy(id = id))
        store.open(key, 0) { true }!!.close(); store = MusicBlobStore(root.root)
        store.put(key.copy(id = "d"))
        assertEquals(setOf("a", "c", "d"), store.songs.value.map { it.key.id }.toSet())
        store.offlineReference(key, "playlist", true, 3)
        store.put(key.copy(id = "e")); assertEquals(4, store.songs.value.count { it.auto })
        store.offlineReference(key, "playlist", false, 3); assertEquals(3, store.songs.value.size)
    }

    @Test fun everyAdvertisedCapTrimsRealDistinctFiles() = runBlocking {
        val store = MusicBlobStore(root.root)
        for (i in 0..1000) {
            val id = i.toString()
            store.fill(key.copy(id = id), "title", 1000, listOf(id), { true }, { descriptor(id) }) { _, consume -> consume(bytes.inputStream()) }
        }
        for (cap in CacheOptions.caps.reversed()) {
            store.limit(owner, cap, emptyList())
            assertEquals(cap, store.songs.value.size)
            assertEquals(cap, root.root.listFiles()!!.count { it.extension == "blob" })
            assertEquals(1000.toString(), store.songs.value.last().key.id)
        }
    }

    @Test fun duplicateDemandCoalescesAndClearFencesBothDescriptorAndTransfer() = runBlocking {
        for (clearAtDescriptor in listOf(true, false)) {
            val store = MusicBlobStore(root.root)
            val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
            suspend fun gate() { entered.complete(Unit); proceed.await() }
            val fill = launch { store.fill(key, "one", 3, listOf("a"), { true }, {
                if (clearAtDescriptor) gate(); descriptor("a")
            }) { _, consume -> consume(bytes.inputStream()); if (!clearAtDescriptor) gate() } }
            entered.await()
            store.fill(key, "two", 3, listOf("a"), { true }, { error("duplicate prepare") }) { _, _ -> error("duplicate transfer") }
            store.clearAuto(owner, if (clearAtDescriptor) null else key.id)
            proceed.complete(Unit); fill.join()
            assertTrue(store.songs.value.isEmpty()); assertEquals(0, root.root.listFiles()!!.size)
        }
    }

    @Test fun diskCeilingsAndRetirementFailClosedWithoutDisturbingExistingBytes() = runBlocking {
        var space = Long.MAX_VALUE
        val store = MusicBlobStore(root.root) { space }
        store.put(); store.offlineReference(key, "keep", true, 3)
        val other = key.copy(id = "b")
        for ((free, length) in listOf(65_536L to 3L, Long.MAX_VALUE to Long.MAX_VALUE)) {
            space = free
            var opened = false
            assertTrue(runCatching { store.fill(other, "b", 3, listOf("b"), { true }, { descriptor("b").copy(byteLength = length) }) { _, _ -> opened = true } }.isFailure)
            assertFalse(opened)
        }
        var live = true
        store.fill(other, "b", 3, listOf("b"), { live }, { descriptor("b") }) { _, consume -> consume(bytes.inputStream()); live = false }
        assertNull(store.open(other, 0) { true }); assertTrue(store.hasOfflineReference(key))
    }

    @Test fun truncatedPinnedFileFailsLocallyAndNeverBecomesANetworkMiss() {
        val file = root.newFile().apply { writeBytes(bytes) }
        var pins = 0
        var releases = 0
        MusicSourceChoice({ pins++; PinnedSong(file, bytes.size.toLong(), { true }) { releases++ } }).use { choice ->
            choice.openLocal("a", 0)!!.use { local ->
                file.writeBytes(byteArrayOf())
                // Local EOF is terminal, not an HTTP interruption that Media3 could retry.
                assertThrows(java.io.EOFException::class.java) { local.input.read() }
            }
            assertTrue(file.delete())
            assertThrows(IOException::class.java) { choice.openLocal("a", 0) }
            assertEquals(1, pins)
        }
        assertEquals(1, releases)
    }

    @Test fun freshSourceHitsAtOffsetButAnExistingMissDoesNotSwitchRepresentation() = runBlocking {
        val store = MusicBlobStore(root.root)
        fun source() = MusicSourceChoice({ id -> store.pin(key.copy(id = id)) { true } })
        val first = source()
        assertNull(first.openLocal("a", 0))
        store.put()
        assertNull(first.openLocal("a", 3))
        val local = source()
        local.openLocal("a", 3)!!.use { assertArrayEquals(bytes.copyOfRange(3, bytes.size), it.input.readBytes()) }
        store.clearAuto(owner) // Between range opens: source hold survives, new sources cannot acquire it.
        assertNull(source().use { it.openLocal("a", 0) }); assertFalse(store.songs.value.single().auto)
        val last = local.openLocal("a", 5)!!
        local.close(); assertEquals(1, store.songs.value.size)
        last.use { assertArrayEquals(bytes.copyOfRange(5, bytes.size), it.input.readBytes()) }
        assertTrue(store.songs.value.isEmpty())
        assertThrows(IOException::class.java) { local.openLocal("a", 0) }
        first.close()
    }
}
