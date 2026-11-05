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

    @Test fun boundedPrefixAndModificationTimeDetectKnownCorruptionAcrossRestart() {
        assertEquals(16 * 1024, SongCache.VERIFY_PREFIX_BYTES)
        bytes("A", 48 * 1024)
        assertBytes(bytes("A"), fetch("A"))
        cache.close(); startCache()
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
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(3, requests["A"])
    }

    @Test fun explicitRetirementAfterFenceWipesReleasedScopeBeforeNextManager() {
        fetch("A")
        cache.fence()
        assertTrue(cache.inventory().ownedBytes > 0)
        cache.retire()
        assertEquals(0L, cache.inventory().ownedBytes)
        cache.close(); startCache()
        assertBytes(bytes("A"), fetch("A"))
        assertEquals(2, requests["A"])
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
        cache.close()
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

    @Test fun osUnlinkedCompletedInodeRemainsOwnedThroughInvalidationUntilReaderCloses() {
        cache.close(); startCache(SongCache.Limits(maxTracks = 3, maxBytes = 24_000, chunkBytes = 512))
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
            assertEquals(0, cache.inventory().complete)
            ioFailure { hit.read(ByteArray(1), 0, 1) }
            ioFailure { fill.read(ByteArray(1), 0, 1) }
            ioFailure { fetch("A") }
            assertEquals(1, requests["A"])
            assertEquals(1, requests["B"])
        } finally { hit.close(); fill.close() }
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
        cache.close() // Leave the second account's completed namespace dormant across restart.
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
                if (active) assertFalse("Block scheduler before cancelling speculation", cancellation.isCancelled)
            }.createDataSource()
            try { demand.open(spec("current")); assertBytes(bytes("current"), drain(demand)) }
            finally { demand.close() }
            assertEquals(listOf(true, false), notifications)
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
