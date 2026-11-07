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
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
    @Volatile private var loginAccount = "synthetic-account"
    private val audioRequests = mutableListOf<RecordedRequest>()

    @Before fun setup() = runBlocking {
        directory = temporary.newFolder("owned-audio")
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/auth/login") return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600; SameSite=Lax")
                    .setBody("""{"accountId":"$loginAccount","username":"synthetic","role":"user","csrfToken":"synthetic-csrf"}""")
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
        upstream = observedFactory(client)
        startCache()
    }

    private fun observedFactory(sharedClient: SpofifyClient): DataSource.Factory {
        val authenticated = OkHttpDataSource.Factory(sharedClient.httpClient)
        return DataSource.Factory {
            val delegate = authenticated.createDataSource()
            object : HttpDataSource by delegate {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    delegate.read(buffer, offset, length).also { if (it > 0) readBytes.addAndGet(it.toLong()) }
            }
        }
    }

    @After fun teardown() {
        closeCache()
        client.httpClient.dispatcher.cancelAll()
        client.httpClient.connectionPool.evictAll()
        web.shutdown()
    }

    private fun closeCache() { cache.close(); cache.awaitStorageForTest() }

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

    @Test fun committedLimitsReconcileFifoPreservePinsAndSurviveRestart() {
        closeCache()
        val store = AutoCacheSettingsStore(File(temporary.root, "settings"))
        cache = SongCache(directory, settingsStore = store)
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        listOf("A", "B", "C", "D").forEach { fetch(it) }
        handle.promote("A")
        cache.awaitStorageForTest()
        val reader = source()
        reader.open(spec("B"))
        assertNull(cache.updateSettings(AutoCacheSettings(maxSongs = 2)))
        assertEquals(2, cache.inventory().entries)
        assertEquals(1, cache.inventory().pinned)
        reader.close()
        assertBytes(bytes("A"), fetch("A"))
        assertBytes(bytes("B"), fetch("B"))
        assertEquals(1, requests["A"])
        assertEquals(1, requests["B"])
        closeCache()
        cache = SongCache(directory, settingsStore = store)
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        assertEquals(2, cache.limits.maxTracks)
        assertEquals(AutoCacheSettings(2), cache.settings.value)
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(1, requests["A"])
    }

    @Test fun loweringBelowActiveReadersDefersDeletionAndNeverTurnsReadsIntoErrors() {
        closeCache()
        cache = SongCache(directory, settingsStore = AutoCacheSettingsStore(File(temporary.root, "settings")))
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        listOf("A", "B", "C").forEach { fetch(it) }
        val readers = listOf("A", "B", "C").map { source().apply { open(spec(it)) } }
        assertNull(cache.updateSettings(AutoCacheSettings(2)))
        assertEquals(3, cache.inventory().entries)
        readers.forEach { reader ->
            assertTrue(reader.read(ByteArray(16), 0, 16) > 0)
        }
        readers.first().close()
        assertEquals(2, cache.inventory().entries)
        readers.drop(1).forEach { it.close() }
        assertFalse(cache.inventory().bypassed)
    }

    @Test fun settingsEditsAreLocalAndFailedDeletionDoesNotInventReclaimedCapacity() {
        closeCache()
        cache = SongCache(directory, deleteFile = { false },
            settingsStore = AutoCacheSettingsStore(File(temporary.root, "settings")))
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        listOf("A", "B", "C").forEach { fetch(it) }
        val beforeRequests = web.requestCount
        val beforeBytes = cache.inventory().ownedBytes
        assertNull(cache.updateSettings(AutoCacheSettings(2)))
        assertEquals(beforeRequests, web.requestCount)
        assertEquals(2, cache.limits.maxTracks)
        assertEquals(3, cache.inventory().entries)
        assertTrue(cache.inventory().ownedBytes >= beforeBytes - 512)
        assertTrue(cache.inventory().cleanupFailed)
    }

    @Test fun decreaseWhileWriterIsReservedNeverDeletesItsStagingAndDemandStillFinishes() {
        closeCache()
        cache = SongCache(directory, settingsStore = AutoCacheSettingsStore(File(temporary.root, "settings")))
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        listOf("A", "B").forEach { fetch(it) }
        val readerA = source().apply { open(spec("A")) }
        val readerB = source().apply { open(spec("B")) }
        val demand = source().apply { open(spec("C")) }
        assertEquals(3, cache.inventory().entries)
        assertNull(cache.updateSettings(AutoCacheSettings(2)))
        assertEquals(3, cache.inventory().pinned)
        assertEquals(3, cache.inventory().entries)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val n = demand.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, n)
        }
        demand.close()
        assertBytes(bytes("C"), output.toByteArray())
        assertTrue(readerA.read(buffer, 0, buffer.size) > 0)
        assertTrue(readerB.read(buffer, 0, buffer.size) > 0)
        readerA.close(); readerB.close()
        assertEquals(2, cache.inventory().entries)
        assertFalse(cache.inventory().bypassed)
    }

    @Test fun unavailableFilesystemSpaceBypassesAdmissionWithoutDeletingHealthyMedia() {
        closeCache()
        var free = 1_000_000_000L
        cache = SongCache(directory, filesystemFree = { free })
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        fetch("A")
        free = 0
        assertBytes(bytes("B"), fetch("B"))
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(1, cache.inventory().entries)
        assertEquals(1, requests["A"])
        assertFalse(cache.inventory().bypassed)
    }

    @Test fun cachedOnlyContinuationRepeatsWarmCurrentAndNextButFirstMissNeverTouchesNetwork() {
        assertBytes(bytes("A"), fetch("A"))
        assertBytes(bytes("B"), fetch("B"))
        val networkBytes = readBytes.get()
        val factory = SongCache.authorizedFactory(handle, upstream,
            { it.uri.pathSegments.let { parts -> parts[parts.size - 2] } }, ::authorize,
            authorizeNetwork = { throw IOException("Cached music only") })
        fun cached(id: String): ByteArray {
            val source = factory.createDataSource()
            try { source.open(spec(id)); return drain(source) } finally { source.close() }
        }
        // Same Media3 read seam for automatic next and repeat; no new cache implementation.
        for (id in listOf("A", "B", "A", "B")) assertBytes(bytes(id), cached(id))
        ioFailure { cached("C") }
        assertNull(requests["C"])
        assertEquals(networkBytes, readBytes.get())
        authorized = false
        ioFailure { cached("A") }
        assertEquals(networkBytes, readBytes.get())
    }

    @Test fun cachedOnlyTransitionFencesAnAlreadyOpenNetworkRead() {
        var live = true
        val factory = SongCache.authorizedFactory(handle, upstream,
            { it.uri.pathSegments.let { parts -> parts[parts.size - 2] } }, ::authorize,
            authorizeNetwork = { if (!live) throw IOException("Lease expired") })
        val source = factory.createDataSource()
        try {
            source.open(spec("uncached"))
            live = false
            ioFailure { source.read(ByteArray(128), 0, 128) }
            assertEquals(0L, readBytes.get())
        } finally { source.close() }
    }

    @Test fun authenticatedWarmPrefetchReuseAndRestartHaveNoFreshnessRequest() {
        assertBytes(bytes("A"), fetch("A"))
        assertTrue(handle.prefetch("B", spec("B"), upstream, ::authorize, SongCache.Cancellation()))
        val transferred = readBytes.get()
        assertEquals((bytes("A").size + bytes("B").size).toLong(), transferred)
        assertBytes(bytes("B"), fetch("B"))
        assertEquals(transferred, readBytes.get())
        closeCache()
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
        closeCache(); startCache(SongCache.Limits(maxTracks = 3))
        listOf("A", "B", "C").forEach { fetch(it) }
        fetch("A") // hit does NOT promote
        fetch("D")
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
        cache.retire(); closeCache(); requests.clear()
        startCache(SongCache.Limits(maxTracks = 3))
        listOf("A", "B", "C").forEach { fetch(it) }
        handle.promote("A")
        closeCache(); startCache(SongCache.Limits(maxTracks = 3))
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
        closeCache(); startCache(SongCache.Limits(maxTracks = 2))
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
        closeCache(); startCache(SongCache.Limits(maxTracks = 3))
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
        cache.awaitStorageForTest()
        assertEquals(0L, cache.inventory().ownedBytes) // No lifetime pin remains after maintenance.
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
        closeCache(); startCache(SongCache.Limits(maxTracks = 4, maxBytes = 40_000, chunkBytes = 1024))
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

    @Test fun boundedPrefixAndModificationTimeDetectKnownCorruptionAcrossRestart() {
        assertEquals(16 * 1024, SongCache.VERIFY_PREFIX_BYTES)
        bytes("A", 48 * 1024)
        assertBytes(bytes("A"), fetch("A"))
        closeCache(); startCache()
        val transferred = readBytes.get()
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(transferred, readBytes.get())

        var media = directory.walkTopDown().single { it.isFile && it.name.endsWith(".exo") }
        val beyondPrefix = SongCache.VERIFY_PREFIX_BYTES + 7
        val previousTime = media.lastModified()
        java.io.RandomAccessFile(media, "rw").use {
            it.seek(beyondPrefix.toLong())
            it.writeByte(bytes("A")[beyondPrefix].toInt() xor 0xff)
        }
        assertTrue(media.setLastModified(previousTime + 2_000))
        assertBytes(bytes("A"), fetch("A")) // Known metadata change outside the checked prefix.
        assertEquals(2, requests["A"])

        media = directory.walkTopDown().single { it.isFile && it.name.endsWith(".exo") }
        val committedTime = media.lastModified()
        java.io.RandomAccessFile(media, "rw").use { it.writeByte(bytes("A")[0].toInt() xor 0xff) }
        assertTrue(media.setLastModified(committedTime))
        assertEquals(committedTime, media.lastModified())
        assertBytes(bytes("A"), fetch("A")) // Prefix digest catches corruption with preserved mtime.
        assertEquals(3, requests["A"])
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
        cache.awaitStorageForTest()
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
        cache.awaitStorageForTest()
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(3, requests["A"])
        closeCache()
        cache = SongCache(directory)
        cache.retire() // No bind / no validated auth needed to erase a dormant namespace.
        cache.awaitStorageForTest()
        assertEquals(0L, cache.inventory().ownedBytes)
    }

    @Test fun fencedReleasedScopeIsRetiredOnAccountSwitchAndCannotReappear() = runBlocking {
        fetch("A")
        val originalHandle = handle
        cache.fence()
        loginAccount = "second-account"
        client.login("second-listener", "synthetic-password")
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
        cache.fence()
        loginAccount = "synthetic-account"
        client.login("synthetic", "synthetic-password")
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(3, requests["A"])
        originalHandle.retire() // Released predecessor no longer owns this same-scope path.
        cache.awaitStorageForTest()
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(3, requests["A"])
    }

    @Test fun explicitRetirementAfterFenceWipesReleasedScopeBeforeNextManager() {
        fetch("A")
        cache.fence()
        assertTrue(cache.inventory().ownedBytes > 0)
        cache.retire()
        cache.awaitStorageForTest()
        assertEquals(0L, cache.inventory().ownedBytes)
        closeCache(); startCache()
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
    }

    @Test fun failedDeletionRemainsOwnedAndInaccessibleAcrossReopen() {
        closeCache()
        val limits = SongCache.Limits(maxTracks = 1)
        val refuseMedia: (File) -> Boolean = { if (it.name.endsWith(".exo")) false else it.delete() }
        startCache(limits, refuseMedia)
        fetch("A")
        assertBytes(bytes("B"), fetch("B"))
        assertTrue(cache.inventory().cleanupFailed)
        assertEquals(1, cache.inventory().entries)
        assertTrue(cache.inventory().ownedBytes >= bytes("A").size)
        closeCache(); startCache(limits, refuseMedia)
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
        assertEquals(1, cache.inventory().entries)
        assertTrue(cache.inventory().ownedBytes >= bytes("A").size)
    }

    @Test fun unwritableCachePathStreamsUncachedAndNeverDisablesDemand() {
        closeCache()
        val notDirectory = temporary.newFile("not-a-directory")
        cache = SongCache(notDirectory)
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        assertBytes(bytes("A"), fetch("A"))
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
        assertTrue(cache.inventory().bypassed)
    }

    @Test fun malformedAndTruncatedLengthsNeverCompleteButClampedRangesAreExact() {
        responses["malformed"] = {
            MockResponse().setBody("not-complete").setHeader("Content-Length", "not-a-length")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        }
        responses["truncated"] = {
            MockResponse().setBody("short-body").setHeader("Content-Length", "4096")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        }
        responses["contradictory"] = {
            MockResponse().setResponseCode(206).setBody("short-body")
                .setHeader("Content-Range", "bytes 0-31/32")
        }
        for (id in listOf("malformed", "truncated", "contradictory")) {
            ioFailure { fetch(id) }
            assertEquals(0, cache.inventory().complete)
            assertEquals(0, cache.inventory().entries)
        }
        responses.clear()
        assertBytes(bytes("truncated"), fetch("truncated"))
        assertEquals(2, requests["truncated"])
        bytes("range", 64)
        assertBytes(bytes("range").copyOfRange(60, 64), fetch("range", 60, 100))
        assertEquals(1, cache.inventory().complete) // Range-only is not a whole-song hit.
        fetch("range")
        val actualUpstream = readBytes.get()
        assertBytes(bytes("range").copyOfRange(60, 64), fetch("range", 60, 100))
        assertEquals(actualUpstream, readBytes.get())
    }

    @Test fun injectedMidWriteFailurePreservesExactDemandAndSkipsFurtherSpeculation() {
        closeCache()
        var writes = 0
        cache = SongCache(directory, SongCache.Limits(maxBytes = 40_000, maxTracks = 4, chunkBytes = 512), openWrite = { file ->
            val output = FileOutputStream(file)
            object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (++writes == 4) throw IOException("Synthetic storage write failure")
                    output.write(buffer, offset, length)
                }
                override fun close() = output.close()
            }
        })
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        bytes("A", 12_000)
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(4, writes)
        assertEquals(12_000L, readBytes.get())
        assertEquals(1, requests["A"])
        assertEquals(0, cache.inventory().complete)
        assertEquals(0, cache.inventory().entries)
        assertTrue(cache.inventory().bypassed)
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(24_000L, readBytes.get())
        assertEquals(4, writes)
        assertFalse(handle.prefetch("future", spec("future"), upstream, ::authorize, SongCache.Cancellation()))
        assertNull(requests["future"])
    }

    @Test fun logoutDuringHeldWriteCannotReturnAnAuthorizedChunk() =
        assertHeldStorageRevocation(holdClose = false, revocation = HeldRevocation.LOGOUT)

    @Test fun logoutDuringHeldCompletionCloseCannotReturnAnAuthorizedChunk() =
        assertHeldStorageRevocation(holdClose = true, revocation = HeldRevocation.LOGOUT)

    @Test fun shared401DuringHeldWriteCannotReturnAnAuthorizedChunk() =
        assertHeldStorageRevocation(holdClose = false, revocation = HeldRevocation.SHARED_401)

    @Test fun shared401DuringHeldCompletionCloseCannotReturnAnAuthorizedChunk() =
        assertHeldStorageRevocation(holdClose = true, revocation = HeldRevocation.SHARED_401)

    @Test fun authorityLossDuringHeldWriteCannotReturnAnAuthorizedChunk() =
        assertHeldStorageRevocation(holdClose = false, revocation = HeldRevocation.AUTHORITY)

    @Test fun authorityLossDuringHeldCompletionCloseCannotReturnAnAuthorizedChunk() =
        assertHeldStorageRevocation(holdClose = true, revocation = HeldRevocation.AUTHORITY)

    @Test fun heldSpeculativeWriteCannotDelayDemandAdmissionOrExistingWriterCleanup() =
        assertSpeculativeDiskContention(holdClose = false)

    @Test fun heldSpeculativeCloseCommitCannotDelayDemandAdmissionOrExistingWriterCleanup() =
        assertSpeculativeDiskContention(holdClose = true)

    /** Unlike the ordinary drain helper, this intentionally does not take an inventory lock
     * while disk is held: progress must be actual authorized bytes, not a storage observation.
     */
    private fun drainDuringContention(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(997)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    private class HeldStorage(private val holdClose: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val arm = AtomicBoolean()
        fun open(file: File): OutputStream {
            val hold = arm.compareAndSet(true, false)
            val output = FileOutputStream(file)
            return object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (hold && !holdClose) awaitRelease()
                    output.write(bytes, offset, length)
                }
                override fun close() {
                    if (hold && holdClose) awaitRelease()
                    output.close()
                }
            }
        }
        private fun awaitRelease() {
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var interrupted = false
            try {
                while (release.count != 0L) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw IOException("Synthetic storage hold timed out")
                    try { release.await(remaining, TimeUnit.NANOSECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    private fun assertSpeculativeDiskContention(holdClose: Boolean) {
        closeCache()
        val disk = HeldStorage(holdClose)
        cache = SongCache(directory, SongCache.Limits(maxTracks = 8, maxBytes = 100_000, chunkBytes = 512),
            openWrite = disk::open)
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        val workers = Executors.newFixedThreadPool(2)
        val writing = source()
        val finishing = source()
        val cached = source()
        val cancellation = SongCache.Cancellation()
        try {
            // Already-admitted demand must bypass contention too, not only fresh open().
            writing.open(spec("writing"))
            bytes("finishing", 512)
            responses["finishing"] = { MockResponse().setChunkedBody(Buffer().write(bytes("finishing")), 512) }
            finishing.open(spec("finishing"))
            val prefix = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (prefix.size() < 512) {
                val count = finishing.read(buffer, 0, buffer.size)
                assertTrue(count > 0)
                prefix.write(buffer, 0, count)
            }
            assertBytes(bytes("finishing"), prefix.toByteArray())
            assertBytes(bytes("cached"), fetch("cached"))
            cached.open(spec("cached"))
            // A corrupt active hit exercises invalidate + unpin + upstream range fallback.
            val span = directory.walkTopDown().single { it.isFile && it.name.endsWith(".exo") }
            java.io.RandomAccessFile(span, "rw").use { it.setLength(0) }
            disk.arm.set(true)
            val speculative = workers.submit<Boolean> {
                handle.prefetch("speculative", spec("speculative"), upstream, ::authorize, cancellation)
            }
            assertTrue(disk.entered.await(3, TimeUnit.SECONDS))
            val before = readBytes.get()
            val demand = workers.submit {
                val fresh = source()
                try {
                    fresh.open(spec("fresh"))
                    assertBytes(bytes("fresh"), drainDuringContention(fresh))
                    assertBytes(bytes("writing"), drainDuringContention(writing))
                    assertEquals(C.RESULT_END_OF_INPUT, finishing.read(buffer, 0, buffer.size))
                    assertBytes(bytes("cached"), drainDuringContention(cached))
                } finally {
                    fresh.close(); writing.close(); finishing.close(); cached.close()
                }
            }
            demand.get(2, TimeUnit.SECONDS)
            assertEquals("Every demanded body byte arrived while disk was still held",
                (bytes("fresh").size + bytes("writing").size + bytes("cached").size).toLong(), readBytes.get() - before)
            assertEquals(1L, disk.release.count)
            assertFalse("Speculative disk has not cooperated with cancellation", speculative.isDone)
            assertTrue(cancellation.isCancelled)
            // The demand EOF already queued finish(true). Lease loss before that task runs
            // must prevent publication even though the cache session itself remains active.
            authorized = false
            disk.release.countDown()
            assertFalse(speculative.get(3, TimeUnit.SECONDS))
        } finally {
            disk.release.countDown()
            cancellation.cancel()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            writing.close(); finishing.close(); cached.close()
        }
        // Graceful close drains retained writer pins; never pretend deferred bytes were reclaimed.
        closeCache()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (cache.inventory().pinned != 0 && System.nanoTime() < deadline) Thread.yield()
        val inventory = cache.inventory()
        assertEquals(0, inventory.pinned)
        assertEquals("Neither cancelled speculation nor authority-revoked deferred EOF is complete", 0, inventory.complete)
        authorized = true
        assertTrue(inventory.ownedBytes <= cache.limits.maxBytes)
        assertTrue(inventory.entries <= cache.limits.maxTracks)
        assertFalse(inventory.cleanupFailed)
    }

    @Test fun idleSuccessfulPlayPersistsWithoutWaitingForAdmissionOrClose() {
        listOf("A", "B", "C").forEach { fetch(it) }
        handle.promote("A")
        cache.awaitStorageForTest()
        // Observe disk directly: inventory/admission/close must not be the thing flushing it.
        val order = directory.walkTopDown().single { it.isFile && it.name == "order" }
            .readBytes().toString(Charsets.ISO_8859_1)
        val positions = listOf("B", "C", "A").map { order.indexOf(handle.key(it)) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test fun successfulPlayDuringHeldCommitIsNonblockingAndPersistsBeforeNextEviction() {
        closeCache()
        val disk = HeldStorage(holdClose = true)
        val limits = SongCache.Limits(maxTracks = 4, maxBytes = 100_000, chunkBytes = 512)
        cache = SongCache(directory, limits, openWrite = disk::open)
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        listOf("A", "B", "C").forEach { fetch(it) }
        val workers = Executors.newFixedThreadPool(2)
        val cancellation = SongCache.Cancellation()
        try {
            disk.arm.set(true)
            val speculative = workers.submit<Boolean> { handle.prefetch("D", spec("D"), upstream, ::authorize, cancellation) }
            assertTrue(disk.entered.await(3, TimeUnit.SECONDS))
            workers.submit {
                handle.protectPlayback("A")
                repeat(1000) { handle.promote("A") } // Bounded coalescing, not a callback queue.
                handle.protectPlayback(null)
            }.get(1, TimeUnit.SECONDS)
            assertEquals(1L, disk.release.count)
            disk.release.countDown()
            assertTrue(speculative.get(3, TimeUnit.SECONDS))
        } finally {
            disk.release.countDown(); cancellation.cancel()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
        closeCache(); startCache(limits)
        fetch("E")
        listOf("A", "C", "D").forEach { assertBytes(bytes(it), fetch(it)); assertEquals(1, requests[it]) }
        fetch("B")
        assertEquals("B, not successfully played A, was oldest", 2, requests["B"])
    }

    @Test fun revocationAndTeardownFenceImmediatelyWhileSpeculativeDiskIsHeld() {
        closeCache()
        val disk = HeldStorage(holdClose = false)
        cache = SongCache(directory, openWrite = disk::open)
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        val workers = Executors.newFixedThreadPool(2)
        val cancellation = SongCache.Cancellation()
        try {
            disk.arm.set(true)
            val speculative = workers.submit<Boolean> { handle.prefetch("held", spec("held"), upstream, ::authorize, cancellation) }
            assertTrue(disk.entered.await(3, TimeUnit.SECONDS))
            workers.submit {
                cache.fence()
                cache.retire()
                cache.close()
                ioFailure { handle.check() }
            }.get(1, TimeUnit.SECONDS)
            assertTrue(cancellation.isCancelled)
            assertEquals(1L, disk.release.count)
            assertFalse(speculative.isDone)
            disk.release.countDown()
            assertFalse(speculative.get(3, TimeUnit.SECONDS))
        } finally {
            disk.release.countDown(); cancellation.cancel()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (cache.inventory().ownedBytes != 0L && System.nanoTime() < deadline) Thread.yield()
        assertEquals(0L, cache.inventory().ownedBytes)
        assertEquals(0, cache.inventory().pinned)
    }

    private enum class HeldRevocation { LOGOUT, SHARED_401, AUTHORITY }

    private fun assertHeldStorageRevocation(holdClose: Boolean, revocation: HeldRevocation) {
        closeCache()
        val enteredStorage = CountDownLatch(1)
        val releaseStorage = CountDownLatch(1)
        val authCleared = CountDownLatch(1)
        val allowRetirement = CountDownLatch(1)
        fun holdStorage() {
            enteredStorage.countDown()
            if (!releaseStorage.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic held storage timed out")
        }
        cache = SongCache(directory, SongCache.Limits(chunkBytes = 512), openWrite = { file ->
            val output = FileOutputStream(file)
            object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (!holdClose) holdStorage()
                    output.write(buffer, offset, length)
                }
                override fun close() {
                    if (holdClose) holdStorage()
                    output.close()
                }
            }
        })
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        bytes("held", if (holdClose) 512 else 4096)
        val demand = source()
        val workers = Executors.newFixedThreadPool(3)
        var invalidation: java.util.concurrent.Future<*>? = null
        try {
            demand.open(spec("held"))
            val reading = workers.submit<Int> {
                val buffer = ByteArray(512)
                var delivered = 0
                do {
                    val count = demand.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    delivered += count
                } while (holdClose && delivered < 512)
                delivered
            }
            assertTrue("Writer holds the storage lock", enteredStorage.await(3, TimeUnit.SECONDS))
            if (revocation == HeldRevocation.AUTHORITY) {
                authorized = false // The caller's independently changing lease/local-owner guard.
                assertNotNull(client.currentSession())
            } else {
                // The real client clears runtime auth BEFORE notifying outside its auth lock.
                // Hold retirement until read finishes to deterministically prevent a faster
                // SessionCache fence from masking the missing final per-read auth check.
                client.onAuthenticationChanged = { change ->
                    if (change.identity == null) {
                        authCleared.countDown()
                        check(allowRetirement.await(5, TimeUnit.SECONDS))
                        cache.retire()
                    }
                }
                invalidation = workers.submit {
                    if (revocation == HeldRevocation.LOGOUT) client.clearSession()
                    else {
                        responses["denied"] = { MockResponse().setResponseCode(401).setBody("synthetic-denial") }
                        val request = okhttp3.Request.Builder()
                            .url(client.audioUrl(LibraryItem("track", "denied", "synthetic"))).build()
                        client.httpClient.newCall(request).execute().use { assertEquals(401, it.code) }
                    }
                }
                assertTrue(authCleared.await(3, TimeUnit.SECONDS))
                // This observer must finish while the retirement callback is still blocked.
                // Using onUnauthorized under the client's auth lock would make this deadlock.
                val observed = workers.submit<Boolean> { client.currentSession() == null }
                assertTrue(observed.get(2, TimeUnit.SECONDS))
                assertFalse(invalidation.isDone)
            }
            releaseStorage.countDown()
            val failure = try {
                reading.get(3, TimeUnit.SECONDS)
                null // A positive read result is the regression, even though its HTTP read was valid.
            } catch (error: java.util.concurrent.ExecutionException) { error.cause }
            assertTrue("Revoked chunk must throw, not return its byte count", failure is IOException)
            assertEquals(1, requests["held"])
            allowRetirement.countDown()
            invalidation?.get(3, TimeUnit.SECONDS)
        } finally {
            releaseStorage.countDown()
            allowRetirement.countDown()
            workers.shutdownNow()
            try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)) }
            finally {
                client.onAuthenticationChanged = null
                demand.close()
            }
        }
        cache.awaitStorageForTest()
        if (revocation != HeldRevocation.AUTHORITY) assertEquals(0L, cache.inventory().ownedBytes)
    }

    @Test fun osUnlinkedCompletedInodeRemainsOwnedThroughInvalidationUntilReaderCloses() {
        closeCache(); startCache(SongCache.Limits(maxTracks = 3, maxBytes = 24_000, chunkBytes = 512))
        bytes("A", 9_000); fetch("A")
        val reader = source(); reader.open(spec("A"))
        fun visibleBytes() = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        try {
            val media = directory.walkTopDown().single { it.isFile && it.name.endsWith(".exo") }
            assertTrue(media.delete())
            assertEquals(visibleBytes() + 9_000, cache.inventory().ownedBytes)
            // A second open discovers the missing file and tombstones it, but the first reader
            // still owns the unlinked inode. Do not erase its accounting with completion length.
            assertBytes(bytes("A"), fetch("A"))
            assertEquals(visibleBytes() + 9_000, cache.inventory().ownedBytes)
            assertBytes(bytes("A"), drain(reader))
            assertEquals(2, requests["A"])
        } finally { reader.close() }
        cache.awaitStorageForTest()
        assertEquals(visibleBytes(), cache.inventory().ownedBytes)
        assertEquals(0, cache.inventory().pinned)
    }

    @Test fun shared401FencesBothWarmReaderAndInFlightFillAndCannotServeStaleBytes() {
        fetch("A")
        val hit = source(); hit.open(spec("A"))
        val fill = source(); fill.open(spec("B")); fill.read(ByteArray(512), 0, 512)
        client.onUnauthorized = { cache.retire() }
        responses["denied"] = { MockResponse().setResponseCode(401).setBody("synthetic-denial") }
        try {
            ioFailure { fetch("denied") }
            assertNull(client.currentSession())
            cache.awaitStorageForTest()
            assertEquals(0, cache.inventory().complete)
            ioFailure { hit.read(ByteArray(1), 0, 1) }
            ioFailure { fill.read(ByteArray(1), 0, 1) }
            ioFailure { fetch("A") }
            assertEquals(1, requests["A"])
            assertEquals(1, requests["B"])
        } finally { hit.close(); fill.close() }
        cache.awaitStorageForTest()
        assertEquals(0L, cache.inventory().ownedBytes)
    }

    @Test fun warmOffOriginIsGuardedAndAuthenticatedRedirectNeverFollows() {
        fetch("A")
        val other = MockWebServer()
        other.start()
        try {
            val destination = other.url("/api/library/tracks/A/audio").toString()
            val warm = handle.factory(upstream, { "A" }, ::authorize).createDataSource()
            try { ioFailure { warm.open(DataSpec.Builder().setUri(destination).build()) } } finally { warm.close() }
            // Defense in depth: even a cold miss with a permissive caller guard cannot make the
            // unchanged exact-origin authenticated client send an off-origin request.
            val cold = handle.factory(upstream, { "not-cached" }, {}).createDataSource()
            try { ioFailure { cold.open(DataSpec.Builder().setUri(destination).build()) } } finally { cold.close() }
            responses["redirect"] = { MockResponse().setResponseCode(302).setHeader("Location", destination) }
            ioFailure { fetch("redirect") }
            assertEquals(0, other.requestCount)
            assertEquals(1, requests["redirect"])
            val before = readBytes.get()
            assertBytes(bytes("A"), fetch("A"))
            assertEquals(before, readBytes.get())
            assertEquals(1, requests["A"])
        } finally { other.shutdown() }
    }

    @Test fun validatedAccountAndOriginSwitchesCannotReuseSameTrackIdentity() = runBlocking {
        fetch("A")
        val old = source(); old.open(spec("A"))
        loginAccount = "second-account"
        client.login("second-listener", "synthetic-password")
        handle = cache.bind(client.server.origin, client.currentSession()!!.accountId)
        payloads["A"] = bytes("A").reversedArray()
        try {
            ioFailure { old.read(ByteArray(1), 0, 1) }
            assertBytes(bytes("A"), fetch("A"))
            assertEquals(2, requests["A"])
        } finally { old.close() }
        closeCache() // Leave the second account's completed namespace dormant across restart.
        val other = MockWebServer().apply { dispatcher = web.dispatcher; start() }
        val nextClient = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${other.port}"), object : SessionStore {
            private var value: StoredSession? = null
            override fun read() = value
            override fun write(value: StoredSession?) { this.value = value }
        })
        try {
            nextClient.login("second-listener", "synthetic-password")
            cache = SongCache(directory)
            val next = cache.bind(nextClient.server.origin, nextClient.currentSession()!!.accountId)
            payloads["A"] = ByteArray(4096) { (it xor 0x5a).toByte() }
            val approved = nextClient.audioUrl(LibraryItem("track", "A", "synthetic"))
            val source = next.factory(observedFactory(nextClient), { "A" }, {
                if (nextClient.currentSession() == null || it.uri.toString() != approved) throw IOException("Unauthorized audio")
            }).createDataSource()
            try { source.open(DataSpec.Builder().setUri(approved).build()); assertBytes(bytes("A"), drain(source)) }
            finally { source.close() }
            assertEquals(3, requests["A"])
            assertEquals(3L * 4096, readBytes.get())
            assertEquals(1, File(directory, "song-cache-v1").listFiles()!!.count { it.isDirectory })
        } finally {
            nextClient.httpClient.dispatcher.cancelAll()
            nextClient.httpClient.connectionPool.evictAll()
            other.shutdown()
        }
    }

    @Test fun idleOpenDemandDoesNotBlockFuturePrefetchAndReadActivityStaysBalanced() {
        val notifications = mutableListOf<Boolean>()
        var activeOperations = 0
        val demand = handle.factory(upstream, { "current" }, ::authorize) { active ->
            notifications += active
            activeOperations += if (active) 1 else -1
            assertTrue("No duplicate decrement or overlapping operation", activeOperations in 0..1)
        }.createDataSource()
        try {
            demand.open(spec("current"))
            assertEquals(listOf(true, false), notifications)
            assertEquals(0, activeOperations)
            assertEquals(0L, readBytes.get()) // Body unread; the current DataSource remains open.
            assertTrue(handle.prefetch("B", spec("B"), upstream, ::authorize, SongCache.Cancellation()))
            assertEquals(bytes("B").size.toLong(), readBytes.get())
            assertEquals(listOf(true, false), notifications) // Speculation is not demand activity.
            assertBytes(bytes("current"), drain(demand))
            assertEquals(0, activeOperations)
            assertEquals((bytes("B").size + bytes("current").size).toLong(), readBytes.get())
        } finally { demand.close() }
        assertEquals(0, activeOperations)
        assertTrue(notifications.size > 2)
        assertTrue(notifications.chunked(2).all { it == listOf(true, false) })
        val observed = readBytes.get()
        assertBytes(bytes("B"), fetch("B"))
        assertEquals(observed, readBytes.get())
        assertEquals(1, requests["B"])
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
            val notifications = mutableListOf<Boolean>()
            val demand = handle.factory(upstream, { "current" }, ::authorize) { active ->
                notifications += active
                if (active && notifications.size == 1) {
                    assertFalse("Block scheduler before cancelling speculation", cancellation.isCancelled)
                }
            }.createDataSource()
            try { demand.open(spec("current")); assertBytes(bytes("current"), drain(demand)) }
            finally { demand.close() }
            assertTrue(notifications.size > 2)
            assertTrue(notifications.chunked(2).all { it == listOf(true, false) })
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
