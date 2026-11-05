package dev.spofify.mobile.playback.cache

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real shared authenticated OkHttp -> Media3 -> private files; no mocked cache-hit assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SongCacheIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private lateinit var directory: File
    private lateinit var cache: SongCache
    private lateinit var handle: SongCache.SessionCache
    private lateinit var upstream: DataSource.Factory
    private val payloads = ConcurrentHashMap<String, ByteArray>()
    private val requests = ConcurrentHashMap<String, Int>()
    private val scheduledBytes = AtomicLong()
    private val readBytes = AtomicLong()
    private val responses = ConcurrentHashMap<String, () -> MockResponse>()
    @Volatile private var authorized = true
    private val audioRequests = mutableListOf<RecordedRequest>()

    @Before fun setup() = runBlocking {
        directory = temporary.newFolder("owned-audio")
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/auth/login") return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600; SameSite=Lax")
                    .setBody("""{"accountId":"synthetic-account","username":"synthetic","role":"user","csrfToken":"synthetic-csrf"}""")
                synchronized(audioRequests) { audioRequests += request }
                if (request.getHeader("Cookie") != "spofify_session=synthetic-cookie") return MockResponse().setResponseCode(401)
                val id = request.requestUrl!!.pathSegments.let { it[it.size - 2] }
                requests.compute(id) { _, previous -> (previous ?: 0) + 1 }
                responses[id]?.let { return it() }
                val body = payloads.getValue(id)
                val range = request.getHeader("Range")
                if (range != null) {
                    val match = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(range)!!
                    val start = match.groupValues[1].toInt()
                    val end = match.groupValues[2].toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
                    val slice = body.copyOfRange(start, end + 1)
                    scheduledBytes.addAndGet(slice.size.toLong())
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${body.size}")
                        .setHeader("Cache-Control", "no-store").setBody(Buffer().write(slice))
                }
                scheduledBytes.addAndGet(body.size.toLong())
                return MockResponse().setHeader("Cache-Control", "no-store").setBody(Buffer().write(body))
            }
        }
        web.start()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
            private var value: StoredSession? = null
            override fun read() = value
            override fun write(value: StoredSession?) { this.value = value }
        })
        client.login("synthetic", "synthetic-password")
        val authenticated = OkHttpDataSource.Factory(client.httpClient)
        upstream = DataSource.Factory {
            val delegate = authenticated.createDataSource()
            object : HttpDataSource by delegate {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    delegate.read(buffer, offset, length).also { if (it > 0) readBytes.addAndGet(it.toLong()) }
            }
        }
        startCache()
    }

    @After fun teardown() {
        cache.close()
        client.httpClient.dispatcher.cancelAll()
        client.httpClient.connectionPool.evictAll()
        web.shutdown()
    }

    private fun startCache(limits: SongCache.Limits = SongCache.Limits(), delete: (File) -> Boolean = { it.delete() }) {
        cache = SongCache(directory, limits, delete)
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
    }
    private fun bytes(id: String, size: Int = 4096): ByteArray = payloads.computeIfAbsent(id) {
        ByteArray(size) { position -> (position * 31 + id.hashCode()).toByte() }
    }
    private fun spec(id: String, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): DataSpec {
        bytes(id)
        return DataSpec.Builder().setUri(Uri.parse(client.audioUrl(LibraryItem("track", id, "private-title-$id"))))
            .setPosition(position).setLength(length).build()
    }
    private fun authorize(spec: DataSpec) {
        if (!authorized || client.currentSession() == null || spec.uri.host != "127.0.0.1" ||
            spec.uri.port != web.port || !spec.uri.path.orEmpty().endsWith("/audio")) throw IOException("Unauthorized audio")
    }
    private fun source() = handle.factory(upstream, { it.uri.pathSegments.let { parts -> parts[parts.size - 2] } }, ::authorize).createDataSource()
    private fun fetch(id: String, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): ByteArray {
        val source = source()
        try { source.open(spec(id, position, length)); return drain(source) } finally { source.close() }
    }
    private fun drain(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(997)
        while (true) {
            val read = source.read(chunk, 0, chunk.size)
            assertTrue("actual owned bytes including metadata", cache.inventory().ownedBytes <= cache.limits.maxBytes)
            assertTrue("reservations included", cache.inventory().entries <= cache.limits.maxTracks)
            if (read == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(chunk, 0, read)
        }
    }
    private fun assertBytes(expected: ByteArray, actual: ByteArray) {
        assertArrayEquals(expected, actual)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), MessageDigest.getInstance("SHA-256").digest(actual))
    }
    private fun ioFailure(action: () -> Unit) {
        try { action(); fail("Expected authorization/protocol IOException") } catch (_: IOException) { }
    }

    @Test fun authenticatedWarmPrefetchReuseAndRestartHaveNoFreshnessRequest() {
        assertBytes(bytes("A"), fetch("A"))
        assertTrue(handle.prefetch("B", spec("B"), upstream, ::authorize, SongCache.Cancellation()))
        val transferred = readBytes.get()
        assertEquals((bytes("A").size + bytes("B").size).toLong(), transferred)
        assertBytes(bytes("B"), fetch("B"))
        assertEquals(transferred, readBytes.get())
        cache.close()
        startCache()
        assertBytes(bytes("A"), fetch("A"))
        assertBytes(bytes("B"), fetch("B"))
        assertEquals(transferred, readBytes.get())
        assertEquals(1, requests["A"])
        assertEquals(1, requests["B"])
        synchronized(audioRequests) {
            assertTrue(audioRequests.all { it.method == "GET" && it.getHeader("Origin") == client.server.origin })
        }
        val files = directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".exo") }.toList()
        for (file in files) {
            val raw = file.readBytes().toString(Charsets.ISO_8859_1)
            for (secret in listOf("synthetic-cookie", "synthetic-csrf", "synthetic-password", "private-title", "http://", "synthetic-account")) {
                assertFalse("No private inventory/credentials in ${file.name}", raw.contains(secret))
            }
        }
    }

    @Test fun readsDoNotPromoteButExplicitSuccessfulPlayOrderPersists() {
        cache.close(); startCache(SongCache.Limits(maxTracks = 3))
        listOf("A", "B", "C").forEach { fetch(it) }
        fetch("A") // hit does NOT promote
        fetch("D")
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
        cache.retire(); cache.close(); requests.clear()
        startCache(SongCache.Limits(maxTracks = 3))
        listOf("A", "B", "C").forEach { fetch(it) }
        handle.promote("A")
        cache.close(); startCache(SongCache.Limits(maxTracks = 3))
        fetch("D")
        fetch("A")
        assertEquals(1, requests["A"])
        fetch("B")
        assertEquals(2, requests["B"])
    }

    @Test fun seeksReuseCompleteBytesButSeekOnlyFillAndInvalid206NeverBecomeHits() {
        fetch("A")
        assertBytes(bytes("A").copyOfRange(13, 113), fetch("A", 13, 100))
        assertEquals(1, requests["A"])
        assertBytes(bytes("B").copyOfRange(13, 113), fetch("B", 13, 100))
        fetch("B")
        assertEquals(2, requests["B"])
        responses["C"] = { MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 8-23/100").setBody("0123456789abcdef") }
        ioFailure { fetch("C") }
        responses.remove("C")
        assertBytes(bytes("C"), fetch("C"))
        assertEquals(2, requests["C"])
    }

    @Test fun allPinnedAdmissionStreamsNormallyThenEvictsOldestEligibleWholeTrack() {
        cache.close(); startCache(SongCache.Limits(maxTracks = 2))
        fetch("A"); fetch("B")
        val a = source(); a.open(spec("A"))
        val b = source(); b.open(spec("B"))
        try {
            assertEquals(2, cache.inventory().pinned)
            assertBytes(bytes("C"), fetch("C"))
            assertEquals(2, cache.inventory().entries)
            a.close()
            assertBytes(bytes("C"), fetch("C"))
            assertBytes(bytes("B"), drain(b))
            assertEquals(1, requests["B"])
            assertEquals(2, requests["C"])
        } finally { a.close(); b.close() }
    }

    @Test fun decodedPlaybackProtectionSkipsOldestWithoutPromotionReservationOrRetirementPin() {
        cache.close(); startCache(SongCache.Limits(maxTracks = 3))
        handle.protectPlayback("not-yet-admitted")
        assertEquals(0, cache.inventory().entries)
        listOf("A", "B", "C").forEach { fetch(it) }
        assertEquals(0, cache.inventory().pinned) // All actual DataSources have closed at EOF.
        handle.protectPlayback("A")
        fetch("D") // Skip oldest A, evict B, leave A/C/D in their original order.
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(1, requests["A"])
        handle.protectPlayback(null)
        fetch("E") // Releasing protection did not promote A: it is still the oldest.
        fetch("A")
        assertEquals(2, requests["A"])
        handle.protectPlayback("A")
        handle.retire()
        assertEquals(0L, cache.inventory().ownedBytes) // No I/O pins, so retirement is immediate.
    }

    @Test fun productionConstantsAndHundredAndFirstAdmissionIncludingDuplicateReservation() {
        assertEquals(100, SongCache.MAX_TRACKS)
        assertEquals(1_000_000_000L, SongCache.MAX_BYTES)
        repeat(101) { id -> bytes("T$id", 31); fetch("T$id") }
        assertEquals(100, cache.inventory().entries)
        fetch("T100")
        assertEquals(1, requests["T100"])
        fetch("T0")
        assertEquals(2, requests["T0"])
        val partial = source()
        partial.open(spec("partial"))
        try {
            assertEquals(100, cache.inventory().entries)
            fetch("partial") // Existing reservation is +0; never waits for its writer.
            assertEquals(100, cache.inventory().entries)
        } finally { partial.close() }
    }

    @Test fun byteBudgetEvictsDuringUnknownLengthFillAndOverlargeDemandStillProgresses() {
        cache.close(); startCache(SongCache.Limits(maxTracks = 4, maxBytes = 40_000, chunkBytes = 1024))
        bytes("A", 12_000); bytes("B", 12_000); bytes("C", 20_000)
        fetch("A"); fetch("B")
        responses["C"] = { MockResponse().setChunkedBody(Buffer().write(bytes("C")), 701) }
        assertBytes(bytes("C"), fetch("C"))
        assertTrue(cache.inventory().complete < 3)
        bytes("large", 80_000)
        assertBytes(bytes("large"), fetch("large"))
        assertTrue(cache.inventory().ownedBytes <= 40_000)
        assertBytes(bytes("large"), fetch("large"))
        assertEquals(2, requests["large"])
    }

    @Test fun cancelledPartialAndCorruptOrRemovedFilesFallBackWithoutFalseHits() {
        val incomplete = source()
        incomplete.open(spec("A"))
        incomplete.read(ByteArray(100), 0, 100)
        incomplete.close()
        assertEquals(0, cache.inventory().complete)
        fetch("A")
        assertEquals(2, requests["A"])
        val media = directory.walkTopDown().first { it.isFile && it.name.endsWith(".exo") }
        java.io.RandomAccessFile(media, "rw").use { it.writeByte(99) }
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(3, requests["A"])
        directory.walkTopDown().filter { it.isFile && it.name.endsWith(".exo") }.forEach { assertTrue(it.delete()) }
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(4, requests["A"])
    }

    @Test fun authorizationIsCheckedOnHitEveryReadAndSameScopeDormancyKeepsPinsSafe() {
        fetch("A")
        authorized = false
        ioFailure { fetch("A") }
        authorized = true
        val old = handle
        val reader = source(); reader.open(spec("A"))
        old.suspend()
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        ioFailure { reader.read(ByteArray(1), 0, 1) }
        assertBytes(bytes("A"), fetch("A"))
        reader.close()
        old.retire() // Late cleanup from the old incarnation must not retire its successor.
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(1, requests["A"])
        val current = source(); current.open(spec("A"))
        authorized = false
        try { ioFailure { current.read(ByteArray(1), 0, 1) } } finally { current.close() }
    }

    @Test fun retiredPinnedScopeCannotDeleteItsSuccessorAndDormantLogoutWipes() {
        fetch("A")
        val old = handle
        val reader = source(); reader.open(spec("A"))
        old.retire()
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        assertBytes(bytes("A"), fetch("A")) // Temporary bypass while old reader is pinned.
        reader.close()
        assertBytes(bytes("A"), fetch("A")) // Lazy successor can now retain safely.
        old.retire()
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(3, requests["A"])
        cache.close()
        cache = SongCache(directory)
        cache.retire() // No bind / no validated auth needed to erase a dormant namespace.
        assertEquals(0L, cache.inventory().ownedBytes)
    }

    @Test fun failedDeletionRemainsOwnedAndInaccessibleAcrossReopen() {
        cache.close()
        val limits = SongCache.Limits(maxTracks = 1)
        val refuseMedia: (File) -> Boolean = { if (it.name.endsWith(".exo")) false else it.delete() }
        startCache(limits, refuseMedia)
        fetch("A")
        assertBytes(bytes("B"), fetch("B"))
        assertTrue(cache.inventory().cleanupFailed)
        assertEquals(1, cache.inventory().entries)
        assertTrue(cache.inventory().ownedBytes >= bytes("A").size)
        cache.close(); startCache(limits, refuseMedia)
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
        assertEquals(1, cache.inventory().entries)
        assertTrue(cache.inventory().ownedBytes >= bytes("A").size)
    }

    @Test fun unwritableCachePathStreamsUncachedAndNeverDisablesDemand() {
        cache.close()
        val notDirectory = temporary.newFile("not-a-directory")
        cache = SongCache(notDirectory)
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        assertBytes(bytes("A"), fetch("A"))
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
        assertTrue(cache.inventory().bypassed)
    }

    @Test fun demandCancelsHeldSpeculationAndTransfersExactCurrentBytes() {
        val entered = CountDownLatch(1)
        responses["slow"] = {
            entered.countDown()
            MockResponse().setBody(Buffer().write(bytes("slow"))).setBodyDelay(5, TimeUnit.SECONDS)
        }
        val worker = Executors.newSingleThreadExecutor()
        val cancellation = SongCache.Cancellation()
        try {
            val pending = worker.submit<Boolean> { handle.prefetch("slow", spec("slow"), upstream, ::authorize, cancellation) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertBytes(bytes("current"), fetch("current"))
            assertTrue(cancellation.isCancelled)
            assertFalse(pending.get(3, TimeUnit.SECONDS))
            assertEquals(1, requests["current"])
        } finally {
            cancellation.cancel()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
