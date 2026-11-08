package dev.spofify.mobile.feature.playlists

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import dev.spofify.mobile.core.saved.SavedCollectionSnapshot
import dev.spofify.mobile.core.saved.SavedDownloadState
import dev.spofify.mobile.core.saved.SavedLibraryReader
import dev.spofify.mobile.core.saved.SavedMediaDescriptor
import dev.spofify.mobile.core.saved.SavedMediaLease
import dev.spofify.mobile.core.saved.SavedOccurrence
import dev.spofify.mobile.core.saved.SavedOccurrenceSnapshot
import dev.spofify.mobile.core.saved.SavedScope
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SavedLibraryAccessTest {
    private val scope = SavedScope("https://saved.example", "account")
    private val target = SavedTarget(SavedTargetKind.ALBUM, "album-id")
    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) = DataSpec.Builder()
        .setUri(Uri.parse("https://untrusted.invalid/not-used")) // Handle, never URI, selects the lease.
        .setPosition(position).setLength(length).build()

    private inner class Reader(val bytes: ByteArray = "abcdef".toByteArray()) : SavedLibraryReader {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val media = SavedMediaDescriptor("track", "sha256:$sha", sha, bytes.size.toLong(), "\"sha256:$sha\"")
        var leaseMedia = media
        var current: SavedCollectionSnapshot? = SavedCollectionSnapshot(target, "Album", "generation",
            listOf(SavedOccurrenceSnapshot(SavedOccurrence("entry", "track", "Song"), media, true)),
            SavedDownloadState.COMPLETE, false, bytes.size.toLong(), null)
        val request = SavedMediaRequest(target, "generation", "entry", media)
        var openedLeases = 0
        var closedLeases = 0
        val readSizes = mutableListOf<Int>()
        var beforeSnapshot: (() -> Unit)? = null
        var afterLease: (() -> Unit)? = null
        var afterRead: (() -> Unit)? = null
        override val revision = MutableStateFlow(0L)
        override fun snapshots(scope: SavedScope): List<SavedCollectionSnapshot> {
            assertEquals(this@SavedLibraryAccessTest.scope, scope)
            return listOfNotNull(current)
        }
        override fun snapshot(scope: SavedScope, target: SavedTarget): SavedCollectionSnapshot? {
            assertEquals(this@SavedLibraryAccessTest.scope, scope)
            beforeSnapshot?.invoke()
            return current?.takeIf { it.target == target }
        }
        override fun open(scope: SavedScope, target: SavedTarget, occurrenceId: String): SavedMediaLease? {
            assertEquals(this@SavedLibraryAccessTest.scope, scope)
            if (current?.target != target || occurrenceId != "entry") return null
            openedLeases++
            val stream = ByteArrayInputStream(bytes)
            val closed = AtomicBoolean(false)
            val result = object : SavedMediaLease {
                override val descriptor = leaseMedia
                override val input = object : InputStream() {
                    override fun read(): Int = stream.read().also { afterRead?.invoke() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        readSizes += len
                        return stream.read(b, off, len).also { afterRead?.invoke() }
                    }
                }
                override fun close() { if (closed.compareAndSet(false, true)) { closedLeases++; stream.close() } }
            }
            afterLease?.invoke()
            return result
        }
    }

    @Test fun exactLeaseSurvivesUnsaveAndBoundsReadWithoutNetworkOrPath() = runBlocking {
        val reader = Reader()
        val access = SavedLibraryAccess(reader, scope) { true }
        val source = requireNotNull(access.openVerifiedMedia(reader.request))
        assertEquals(0, reader.openedLeases)
        assertEquals(3L, source.open(spec(position = 2, length = 3)))
        reader.current = null // Actually opened lease, not continuing retention membership.
        val buffer = ByteArray(16)
        assertEquals(3, source.read(buffer, 0, buffer.size))
        assertEquals("cde", String(buffer, 0, 3))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        assertEquals("spofify-saved", source.uri?.scheme)
        assertFalse(source.uri.toString().contains("album-id"))
        assertTrue(source.responseHeaders.isEmpty())
        assertEquals(0, reader.closedLeases)
        source.close()
        assertEquals(1, reader.closedLeases)
    }

    @Test fun mismatchedGenerationOccurrenceDescriptorAndUnavailableRowsNeverAcquire() = runBlocking {
        val reader = Reader()
        val access = SavedLibraryAccess(reader, scope) { true }
        assertNull(access.openVerifiedMedia(reader.request.copy(generation = "other")))
        assertNull(access.openVerifiedMedia(reader.request.copy(occurrenceId = "other")))
        assertNull(access.openVerifiedMedia(reader.request.copy(target = SavedTarget(SavedTargetKind.ARTIST, "album-id"))))
        assertNull(access.openVerifiedMedia(reader.request.copy(media = reader.media.copy(byteLength = 10))))
        reader.current = reader.current!!.copy(occurrences = reader.current!!.occurrences.map { it.copy(available = false) })
        assertNull(access.openVerifiedMedia(reader.request))
        assertEquals(0, reader.openedLeases)
    }

    @Test fun updateDuringAcquisitionRejectsAndReleasesLease() = runBlocking {
        val reader = Reader()
        reader.afterLease = { reader.current = reader.current!!.copy(generation = "replacement") }
        val access = SavedLibraryAccess(reader, scope) { true }
        val source = requireNotNull(access.openVerifiedMedia(reader.request))
        assertEquals(0, reader.openedLeases)
        expectIo { source.open(spec()) }
        assertEquals(1, reader.openedLeases)
        assertEquals(1, reader.closedLeases)
    }

    @Test fun mismatchedLeaseDescriptorIsNeverAccepted() = runBlocking {
        val reader = Reader()
        reader.leaseMedia = reader.media.copy(byteLength = 10)
        val source = requireNotNull(SavedLibraryAccess(reader, scope) { true }.openVerifiedMedia(reader.request))
        assertEquals(0, reader.openedLeases)
        expectIo { source.open(spec()) }
        assertEquals(1, reader.closedLeases)
    }

    @Test fun retireFencesReadsWithoutClosingOrBlockingUntilCallerCloses() = runBlocking {
        val reader = Reader()
        val access = SavedLibraryAccess(reader, scope) { true }
        val source = requireNotNull(access.openVerifiedMedia(reader.request))
        source.open(spec())
        access.retire()
        assertEquals(0, reader.closedLeases)
        expectIo { source.read(ByteArray(1), 0, 1) }
        assertTrue(access.collections().isEmpty())
        assertNull(access.openVerifiedMedia(reader.request))
        source.close()
        source.close()
        assertEquals(1, reader.closedLeases)
    }

    @Test fun callerIncarnationGuardIsCheckedAfterBlockedRead() = runBlocking {
        val reader = Reader()
        var current = true
        val access = SavedLibraryAccess(reader, scope) { current }
        val source = requireNotNull(access.openVerifiedMedia(reader.request))
        source.open(spec())
        reader.afterRead = { current = false }
        expectIo { source.read(ByteArray(1), 0, 1) }
        assertEquals(0, reader.closedLeases)
        source.close()
        assertEquals(1, reader.closedLeases)
    }

    @Test fun skipUsesBoundedReadsAndLengthIsClampedToVerifiedRepresentation() = runBlocking {
        val reader = Reader(ByteArray(100_000) { (it % 127).toByte() })
        val source = requireNotNull(SavedLibraryAccess(reader, scope) { true }.openVerifiedMedia(reader.request))
        assertEquals(30_000L, source.open(spec(position = 70_000, length = Long.MAX_VALUE)))
        assertTrue(reader.readSizes.all { it <= 32 * 1024 })
        assertTrue(reader.readSizes.size >= 3)
        source.close()
        val invalid = requireNotNull(SavedLibraryAccess(reader, scope) { true }.openVerifiedMedia(reader.request))
        expectIo { invalid.open(spec(position = 100_001)) }
        assertEquals(1, reader.openedLeases)
        assertEquals(1, reader.closedLeases)
    }

    @Test fun discardedAndNeverOpenedFactoriesOwnNoLeaseActualOpenOwnsExactlyOne() = runBlocking {
        val reader = Reader()
        val access = SavedLibraryAccess(reader, scope) { true }
        access.openVerifiedMedia(reader.request) // Intentionally discard without close: owns no lease.
        val source = requireNotNull(access.openVerifiedMedia(reader.request))
        assertEquals(0, reader.openedLeases)
        source.close()
        assertEquals(0, reader.closedLeases)
        source.open(spec())
        assertEquals(1, reader.openedLeases)
        source.close()
        assertEquals(1, reader.closedLeases)
        reader.current = null
        expectIo { source.open(spec()) }
        assertEquals(1, reader.openedLeases)
    }

    @Test fun cancellationBeforeFactoryDeliveryNeverAcquiresLease() = runBlocking {
        val reader = Reader()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        reader.beforeSnapshot = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val access = SavedLibraryAccess(reader, scope) { true }
        val factory = async(Dispatchers.Default) { access.openVerifiedMedia(reader.request) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            factory.cancel()
        } finally {
            release.countDown()
            factory.cancelAndJoin()
        }
        assertEquals(0, reader.openedLeases)
        assertEquals(0, reader.closedLeases)
    }

    @Test fun actualOpenRechecksRequestAfterFactoryPrefilter() = runBlocking {
        val reader = Reader()
        val source = requireNotNull(SavedLibraryAccess(reader, scope) { true }.openVerifiedMedia(reader.request))
        reader.current = reader.current!!.copy(generation = "replacement")
        expectIo { source.open(spec()) }
        assertEquals(0, reader.openedLeases)
        assertEquals(0, reader.closedLeases)
    }

    private fun expectIo(action: () -> Unit) {
        try { action(); fail("Expected fenced or unavailable local media") } catch (_: IOException) { }
    }
}
