package dev.spofify.mobile.playback

import dev.spofify.mobile.core.cache.*
import dev.spofify.mobile.core.offline.OfflineMediaDescriptor
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMusicRangesTest {
    @get:Rule val root = TemporaryFolder()
    private val key = SongKey(MusicOwner("https://music.example", "a"), "opaque.mpd")
    private val installed = AudioSourceOwner("generation", key.id)

    @Test fun holdsVerifiedBytesAcrossEvictionButRetirementFencesEvenOpenRanges() = runTest {
        val store = MusicBlobStore(root.root)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        store.fill(key, "track", 3, listOf(key.id), { true }, {
            OfflineMediaDescriptor(key.id, "sha256:$sha", sha, 4, "mp3", "audio/mpeg", "unused")
        }) { _, consume -> consume(bytes.inputStream()) }
        assertTrue(store.offlineReference(key, "saved", true, 3))
        var allowed = true
        val ranges = LocalMusicRanges(installed, { allowed }) { current -> MusicSourceChoice({ store.pin(key, current) }) }
        ranges.open(installed.key, 0).use { first ->
            assertEquals(1, first.input.read())
            store.clearAuto(key.owner)
            ranges.open(installed.key, 2).use { seek ->
                assertEquals(3, seek.input.read())
                assertTrue(store.offlineReference(key, "saved", false, 3))
                assertFalse(installed.verifiedDownload)
                assertThrows(IOException::class.java) { first.input.read() }
                allowed = false
                assertThrows(IOException::class.java) { seek.input.read() }
                assertThrows(IOException::class.java) { ranges.open(installed.key, 0) }
                ranges.close()
            }
            assertThrows(IOException::class.java) { first.input.read() }
            assertEquals(1, store.songs.value.size)
        }
        assertTrue(store.songs.value.isEmpty())
    }

    @Test fun wrongGenerationRetiredAndMissingRangesFailClosed() {
        var lookups = 0
        val ranges = LocalMusicRanges(installed, { true }) { MusicSourceChoice({ lookups++; null }) }
        assertThrows(IOException::class.java) { ranges.open("another-generation", 0) }
        assertEquals(0, lookups)
        assertThrows(IOException::class.java) { ranges.open(installed.key, 0) }
        assertThrows(IOException::class.java) { ranges.open(installed.key, 2) }
        assertEquals(1, lookups)
        installed.job.cancel()
        assertThrows(IOException::class.java) { ranges.open(installed.key, 0) }
        ranges.close()
    }
}
