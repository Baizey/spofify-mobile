package dev.spofify.mobile.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.spofify.mobile.OfflineLibraryAccess
import dev.spofify.mobile.OfflineMediaReference
import dev.spofify.mobile.OfflineSavedCollection
import dev.spofify.mobile.core.LibraryItem
import org.junit.Assert.*
import org.junit.After
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Real source open/range/read/close, not a cache-hit or availability-label assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class OfflinePlaybackMediaTest {
    private val item = LibraryItem("track", "opaque/id%", "Saved", hasAudio = true, entryId = "entry-a")
    private val media = PlaybackMedia.createLocal(item, UUID.randomUUID().toString())
    private val uri = requireNotNull(media.localConfiguration).uri
    private val owner = object : OfflineLibraryAccess {
        override val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
        override suspend fun collections(origin: String, accountId: String): List<OfflineSavedCollection> = emptyList()
        override fun retire() = Unit
    }
    private val loader = Executors.newSingleThreadExecutor()
    @After fun closeLoader() { loader.shutdownNow() }
    private fun onLoader(block: () -> Unit) {
        try { loader.submit(Callable { block() }).get(5, TimeUnit.SECONDS) }
        catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
    }
    private fun reference(opener: suspend () -> DataSource?) = OfflineMediaReference(owner, "https://saved.invalid", "account", opener)
    private fun source(reference: OfflineMediaReference, isCurrent: () -> Boolean = { true }) = VerifiedLocalPlaybackSource(
        isCurrent, { if (it.uri == uri && it.key == uri.toString()) reference else null }, { it.openExactSnapshotOccurrence() })
    private fun spec(uri: Uri = this.uri, key: String = this.uri.toString(), position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).setLength(length).build()

    private fun expectDenied(block: () -> Unit) {
        try { block(); fail("Expected denied local media access") } catch (_: IOException) { }
    }

    @Test fun consumesExactVerifiedBytesAndRangesWithoutAnyUriResolution() = onLoader {
        val bytes = ByteArray(257) { (it * 17).toByte() }
        var opens = 0
        val reference = reference { delay(5); opens++; ByteArrayDataSource(bytes) }
        val source = source(reference)
        assertEquals(101L, source.open(spec(position = 7, length = 101)))
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(13)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            result.write(buffer, 0, count)
        }
        assertArrayEquals(bytes.copyOfRange(7, 108), result.toByteArray())
        assertEquals(uri, source.getUri())
        assertTrue(source.getResponseHeaders().isEmpty())
        source.close()
        assertNull(source.getUri())
        assertEquals(1, opens)
    }

    @Test fun absentIncompleteOrDeletedSourceNeverFallsBackToHttpOrFile() = onLoader {
        var opens = 0
        val source = source(reference { opens++; null })
        expectDenied { source.open(spec()) }
        assertEquals(1, opens)
        for (address in listOf("file:///private/arbitrary.wav", "content://other/audio", "https://example.invalid/audio")) {
            expectDenied { source.open(spec(Uri.parse(address))) }
        }
        expectDenied { source.open(spec(key = item.id)) }
        assertEquals("Unapproved addresses/track-only keys never reach the exact-reference opener", 1, opens)
    }

    @Test fun retiredIncarnationCannotOpenOrReadAnAlreadyOpenSource() = onLoader {
        var current: Any = Any()
        val captured = current
        var reads = 0
        var closes = 0
        val source = source(reference {
            val actual = ByteArrayDataSource(byteArrayOf(1, 2, 3, 4))
            object : DataSource by actual {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    return actual.read(buffer, offset, length)
                }
                override fun close() { closes++; actual.close() }
            }
        }, { current === captured })
        source.open(spec())
        val buffer = ByteArray(1)
        assertEquals(1, source.read(buffer, 0, 1))
        assertEquals(1, buffer[0].toInt())
        current = Any()
        expectDenied { source.read(buffer, 0, 1) }
        assertEquals("Revocation is checked before calling an already-open reader", 1, reads)
        source.close()
        expectDenied { source.open(spec()) }
        assertEquals(1, closes)
    }

    @Test fun revocationDuringOpenAndReadCannotReturnNewlyReadBytesToMedia3() = onLoader {
        var permitted = true
        var closed = false
        val source = source(reference {
            val actual = ByteArrayDataSource(byteArrayOf(7, 8))
            object : DataSource by actual {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val result = actual.read(buffer, offset, length)
                    permitted = false
                    return result
                }
                override fun close() { closed = true; actual.close() }
            }
        }, { permitted })
        source.open(spec())
        expectDenied { source.read(ByteArray(1), 0, 1) }
        source.close()
        assertTrue(closed)
        permitted = true
        closed = false
        val revokedOpen = this.source(reference {
            val actual = ByteArrayDataSource(byteArrayOf(9))
            object : DataSource by actual {
                override fun open(dataSpec: DataSpec): Long {
                    val length = actual.open(dataSpec)
                    permitted = false
                    return length
                }
                override fun close() { closed = true; actual.close() }
            }
        }, { permitted })
        expectDenied { revokedOpen.open(spec()) }
        assertTrue("Failed open releases the verified source", closed)
    }

    @Test fun mainThreadCannotEnterTheSuspendSavedFactory() {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        var called = false
        val source = source(reference { called = true; ByteArrayDataSource(byteArrayOf(1)) })
        expectDenied { source.open(spec()) }
        assertFalse(called)
    }

    @Test fun suspendedFactoryRevocationClosesTheLateReturnedVerifiedSource() = onLoader {
        var permitted = true
        var closed = false
        val source = source(reference {
            delay(5)
            permitted = false
            val actual = ByteArrayDataSource(byteArrayOf(7))
            object : DataSource by actual {
                override fun close() { closed = true; actual.close() }
            }
        }, { permitted })
        expectDenied { source.open(spec()) }
        assertTrue("A source returned after revocation must be released without opening", closed)
        assertNull(source.getUri())
    }

    @Test fun interruptCancelsTheSuspendingFactoryAndPreservesTheLoadingThreadInterrupt() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val source = source(reference {
            try {
                entered.countDown()
                delay(60_000)
                ByteArrayDataSource(byteArrayOf(7))
            } finally { exited.countDown() }
        })
        val worker = Thread {
            try { source.open(spec()) }
            catch (error: Throwable) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted) }
            finally { source.close() }
        }
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000)
            assertFalse("Cancellation must release the Media3 loading thread", worker.isAlive)
            assertTrue(failure.get() is java.io.InterruptedIOException)
            assertTrue(interrupted.get())
            assertTrue(exited.await(2, TimeUnit.SECONDS))
        } finally { worker.interrupt(); worker.join(2_000) }
    }

    @Test fun duplicateSavedEntriesHaveDistinctOpaqueQueueOccurrencesAndNeverUseLibraryPaths() {
        val queue = UUID.randomUUID().toString()
        val first = PlaybackMedia.createLocal(item.copy(libraryPath = "/arbitrary/private/path"), queue)
        val second = PlaybackMedia.createLocal(item.copy(entryId = "entry-b"), queue)
        assertEquals(first.mediaId, second.mediaId)
        assertNotEquals(PlaybackMedia.occurrence(first), PlaybackMedia.occurrence(second))
        assertNotEquals(first.localConfiguration!!.uri, second.localConfiguration!!.uri)
        assertEquals("spofify-local", first.localConfiguration!!.uri.scheme)
        assertEquals(first.localConfiguration!!.uri.toString(), first.localConfiguration!!.customCacheKey)
        assertFalse(first.localConfiguration!!.uri.toString().contains("private"))
        assertEquals("entry-a", PlaybackMedia.libraryItem(first)?.entryId)
        assertEquals("entry-b", PlaybackMedia.libraryItem(second)?.entryId)
    }
}
