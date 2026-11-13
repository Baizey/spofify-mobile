package dev.spofify.mobile.core

import dev.spofify.mobile.core.saved.SavedScope
import dev.spofify.mobile.core.saved.SavedScopeFence
import dev.spofify.mobile.core.saved.mediaKey
import dev.spofify.mobile.core.saved.SavedStorageFailure
import dev.spofify.mobile.core.saved.SavedOccurrence
import dev.spofify.mobile.core.saved.SavedRepresentationBackend
import dev.spofify.mobile.core.saved.PrivateSavedMediaBackend
import dev.spofify.mobile.core.saved.SavedDownloadTransport
import dev.spofify.mobile.core.saved.SavedMediaDescriptor
import dev.spofify.mobile.core.saved.SavedTransferResponse
import dev.spofify.mobile.core.saved.SavedLibraryStore
import dev.spofify.mobile.core.saved.SavedLocalEligibility
import dev.spofify.mobile.core.saved.SavedDownloader
import dev.spofify.mobile.core.saved.SavedDownloadState
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind
import dev.spofify.mobile.core.saved.SavedTransportException
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OfflineCollectionsApiTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private lateinit var store: MemorySessionStore
    private lateinit var api: OfflineCollectionsApi
    private val manual = SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, "saved")
    private val sha = "a".repeat(64)
    private val version get() = "sha256:$sha"

    @Before fun setup() {
        web = MockWebServer()
        web.start()
        store = MemorySessionStore()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), store)
        api = OfflineCollectionsApi(client)
    }
    @After fun teardown() { web.shutdown() }

    @Test fun completeManifestKeepsDuplicateOccurrencesUnavailableRowsAndSharedAuth() = runBlocking {
        login()
        web.enqueue(json(page(items = row("entry1", 0), total = 3, audio = 2, next = "cursor1", limit = 1)))
        web.enqueue(json(page(items = row("entry2", 1), total = 3, audio = 2, next = "cursor2", limit = 1)))
        web.enqueue(json(page(items = row("entry3", 2, audio = false), total = 3, audio = 2, limit = 1)))
        val manifest = api.manifest(manual, 1)
        assertEquals(manual, manifest.target)
        assertEquals("Saved music", manifest.title)
        assertEquals("f1_fence", manifest.generation)
        assertEquals(3L, manifest.total)
        assertEquals(listOf("entry1", "entry2", "entry3"), manifest.occurrences.map { it.occurrenceId })
        assertEquals(listOf("track", "track", "track"), manifest.occurrences.map { it.trackId })
        assertFalse(manifest.occurrences.last().hasAudio)
        repeat(3) { index ->
            val request = web.takeRequest()
            assertEquals("/api/me/offline/v1/playlists/saved/manifest", request.requestUrl!!.encodedPath)
            assertEquals("1", request.requestUrl!!.queryParameter("limit"))
            assertEquals(if (index == 0) null else "cursor$index", request.requestUrl!!.queryParameter("cursor"))
            assertEquals(client.server.origin, request.getHeader("Origin"))
            assertEquals("spofify_session=cookie-secret", request.getHeader("Cookie"))
            assertNull(request.getHeader("X-CSRF-Token"))
        }
    }

    @Test fun typedTargetsUseOnlyTheAgreedRoutesAndMetadataShapes() = runBlocking {
        login()
        for ((kind, resource, wire) in listOf(
            Triple(SavedTargetKind.DERIVED_PLAYLIST, "playlists", "derived"),
            Triple(SavedTargetKind.ALBUM, "albums", "album"),
            Triple(SavedTargetKind.ARTIST, "artists", "artist"),
        )) {
            val playlist = resource == "playlists"
            val summary = if (playlist) "\"playlist\":{\"id\":\"saved\",\"kind\":\"$wire\",\"name\":\"Saved music\",\"description\":\"\",\"revision\":2}"
                else "\"collection\":{\"kind\":\"$wire\",\"id\":\"saved\",\"name\":\"Saved music\"}"
            web.enqueue(json(page(items = "", total = 0, audio = 0).replace(playlistSummary(), summary)))
            val result = api.manifest(SavedTarget(kind, "saved"))
            assertEquals(0L, result.total)
            assertTrue(result.occurrences.isEmpty())
            assertEquals("/api/me/offline/v1/$resource/saved/manifest?limit=100", web.takeRequest().path)
        }
    }

    @Test fun rejectsChangedGenerationIdentityOrderingCountsAndNonprogressWithoutPublishingPrefixes() = runBlocking {
        login()
        val first = page(items = row("one", 0), total = 2, audio = 2, next = "next", limit = 1)
        val second = page(items = row("two", 1), total = 2, audio = 2, limit = 1)
        val invalid = listOf(
            second.replace("f1_fence", "f1_changed"),
            second.replace("\"revision\":2", "\"revision\":3"),
            second.replace("\"total\":2", "\"total\":3"),
            second.replace("\"recordedAudioTotal\":2", "\"recordedAudioTotal\":1"),
            second.replace("Saved music", "Changed title"),
            second.replace("\"kind\":\"manual\"", "\"kind\":\"derived\""),
            second.replace("\"id\":\"saved\"", "\"id\":\"other\""),
            second.replace("\"position\":1", "\"position\":0"),
            second.replace("\"occurrenceId\":\"two\"", "\"occurrenceId\":\"one\""),
            second.replace("\"limit\":1", "\"limit\":2"),
            second.replace("\"nextCursor\":null", "\"nextCursor\":\"next\""),
            page(items = "", total = 2, audio = 2, next = "next2", limit = 1),
        )
        for (body in invalid) {
            web.enqueue(json(first)); web.enqueue(json(body))
            assertSafeFailure { api.manifest(manual, 1) }
            web.takeRequest(); web.takeRequest()
        }
        // Exact duplicate (including escaped spelling) must fail before JsonObject loses it.
        for (body in listOf(
            page().replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            page().replace(",\"nextCursor\":null", ""),
            page().replace("\"position\":0", "\"position\":\"0\""),
            page().replace("\"hasAudio\":true", "\"hasAudio\":\"true\""),
            page().replace("\"recordedAudioTotal\":1", "\"recordedAudioTotal\":0"),
            page().replace("\"nextCursor\":null", "\"nextCursor\":\"\""),
        )) {
            web.enqueue(json(body)); assertSafeFailure { api.manifest(manual) }; web.takeRequest()
        }
    }

    @Test fun staleCursorIsNotSilentlyRestartedAndErrorsNeverRetainPrivateText() = runBlocking {
        login()
        web.enqueue(json(page(items = row("one", 0), total = 2, audio = 2, next = "cursor", limit = 1)))
        web.enqueue(json("""{"error":{"code":"STALE_EVALUATION","message":"/private/cookie-secret"}}""", 409))
        val error = failure { api.manifest(manual, 1) } as ApiException
        assertEquals(409, error.status)
        assertEquals("STALE_EVALUATION", error.code)
        assertEquals(3, web.requestCount) // login plus exactly two pages
        web.takeRequest(); web.takeRequest()
        web.enqueue(json(" ".repeat(8193) + """{"error":{"code":"MEDIA_BUSY","message":"/private/cookie-secret"}}""", 429))
        val oversized = failure { api.currentMedia("track") } as ApiException
        assertEquals(429, oversized.status)
        assertNull(oversized.code)
        assertEquals("Too many requests. Please try again later.", oversized.message)
        assertFalse(oversized.toString().contains("/private"))
        assertFalse(oversized.toString().contains("cookie-secret"))
        assertNull(oversized.cause)
        assertTrue(oversized.suppressed.isEmpty())
    }

    @Test fun currentDescriptorRejectsMaliciousUrlsDigestVersionsLengthsAndTypesBeforeAudio() = runBlocking {
        login()
        val valid = descriptor()
        val relative = "/api/me/offline/v1/tracks/track/audio?version=sha256%3A$sha"
        val invalid = listOf(
            valid.replace(relative, "https://evil.invalid/audio?version=$version"),
            valid.replace(relative, "//evil.invalid/audio"),
            valid.replace(relative, "$relative#fragment"),
            valid.replace(relative, "$relative&token=cookie-secret"),
            valid.replace(relative, "/api/library/tracks/track/audio?version=sha256%3A$sha"),
            valid.replace("tracks/track/audio", "tracks/other/audio"),
            valid.replace("tracks/track/audio", "tracks/other/../track/audio"),
            valid.replace("tracks/track/audio", "tracks/other/%2e%2e/track/audio"),
            valid.replace("tracks/track/audio", "tracks/track%zz/audio"),
            valid.replace("sha256%3A$sha", "sha256%3A${"b".repeat(64)}"),
            valid.replace("\"mediaVersion\":\"$version\"", "\"mediaVersion\":\"sha256:${"b".repeat(64)}\""),
            valid.replace("\"sha256\":\"$sha\"", "\"sha256\":\"${sha.uppercase()}\""),
            valid.replace("\"byteLength\":10", "\"byteLength\":0"),
            valid.replace("\"byteLength\":10", "\"byteLength\":\"10\""),
            valid.replace("\"byteLength\":10", "\"byteLength\":9223372036854775808"),
            valid.replace("audio/flac", "text/html"),
            valid.replace("\"trackId\":\"track\"", "\"trackId\":\"other\""),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
        )
        for (body in invalid) { web.enqueue(json(body)); assertSafeFailure { api.currentMedia("track") }; web.takeRequest() }
        assertEquals(1 + invalid.size, web.requestCount)
    }

    @Test fun rangeAndStrongIfRangeUseSharedAuthAnd200RestartsFromZero() = runBlocking {
        login()
        web.enqueue(json(descriptor()))
        val media = api.currentMedia("track"); web.takeRequest()
        web.enqueue(audio("3456789", 206).setHeader("Content-Range", "bytes 3-9/10"))
        val resumed = RecordingSink()
        val result = api.download(media, 3, resumed)
        assertEquals(7L, result.transferredBytes)
        assertEquals(3L, resumed.response!!.startOffset)
        assertEquals("3456789", resumed.data.readUtf8())
        val request = web.takeRequest()
        assertEquals("bytes=3-", request.getHeader("Range"))
        assertEquals("\"$version\"", request.getHeader("If-Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals("spofify_session=cookie-secret", request.getHeader("Cookie"))
        assertEquals(client.server.origin, request.getHeader("Origin"))
        web.enqueue(audio("0123456789"))
        val replacement = RecordingSink()
        api.download(media, 3, replacement)
        assertEquals(200, replacement.response!!.status)
        assertEquals(0L, replacement.response!!.startOffset)
        assertEquals("0123456789", replacement.data.readUtf8())
    }

    @Test fun rejectsResumeHeadersBeforeAnySinkBytesAndBoundsChunkedShortOrOversizedBodies() = runBlocking {
        login()
        web.enqueue(json(descriptor())); val media = api.currentMedia("track"); web.takeRequest()
        val invalid = listOf(
            audio("3456789", 206).setHeader("Content-Range", "bytes 2-8/10"),
            audio("3456789", 206).setHeader("Content-Range", "bytes 3-9/11"),
            audio("3456789", 206).setHeader("Content-Range", "bytes 3-9/10").setHeader("ETag", "W/\"$version\""),
            audio("3456789", 206).setHeader("Content-Range", "bytes 3-9/10").setHeader("Content-Type", "text/html"),
            audio("3456789", 206).setHeader("Content-Range", "bytes 3-9/10").setHeader("Content-Encoding", "gzip"),
            audio("0123456789").setHeader("Content-Range", "bytes 0-9/10"),
            audio("0123456789").addHeader("ETag", "\"$version\""),
            MockResponse().setResponseCode(204),
        )
        for (response in invalid) {
            web.enqueue(response)
            val sink = RecordingSink()
            assertSafeFailure { api.download(media, 3, sink) }
            assertNull(sink.response); assertEquals(0L, sink.data.size)
            web.takeRequest()
        }
        for (body in listOf("012345678", "01234567890")) {
            web.enqueue(audio("").setChunkedBody(body, 3))
            val sink = RecordingSink()
            assertSafeFailure { api.download(media, sink = sink) }
            assertNotNull(sink.response)
            assertEquals(minOf(body.length, 10).toLong(), sink.data.size)
            web.takeRequest()
        }
    }

    @Test fun originalResponseTransportKeepsCancellationAttachedDuringStreamConsumption() = runBlocking {
        login()
        web.enqueue(json(descriptor(length = 1024 * 1024)))
        val media = api.currentMedia("track"); web.takeRequest()
        web.enqueue(audio("").setBody(Buffer().write(ByteArray(1024 * 1024)))
            .throttleBody(1024, 1, TimeUnit.SECONDS))
        val began = CountDownLatch(1)
        val writes = AtomicInteger()
        val job = async(Dispatchers.Default) {
            api.download(media, sink = object : OfflineMediaSink {
                override fun begin(response: OfflineMediaResponse) { began.countDown() }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { writes.incrementAndGet() }
            })
        }
        assertTrue(withContext(Dispatchers.IO) { began.await(5, TimeUnit.SECONDS) })
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        // Header delivery did not detach/hand back an unowned response body.
        assertTrue(writes.get() < 1024)
    }

    @Test fun shared401FromMediaFencesTheOriginalAccountAndRejectsOldDescriptors() = runBlocking {
        login()
        web.enqueue(json(descriptor())); val media = api.currentMedia("track"); web.takeRequest()
        var notifications = 0
        client.onUnauthorized = { notifications++ }
        web.enqueue(json("""{"error":"/private/cookie-secret"}""", 401))
        val error = failure { api.download(media, sink = RecordingSink()) } as ApiException
        assertEquals(401, error.status)
        assertEquals(1, notifications)
        assertNull(client.currentSession())
        assertNull(store.value)
        val requests = web.requestCount
        assertSafeFailure { api.download(media, sink = RecordingSink()) }
        assertEquals(requests, web.requestCount)
    }

    @Test fun storeAdapterScopesItsStreamToTheCallbackAndFencesSubsequentWork() = runBlocking {
        login()
        var current = true
        val scope = SavedScope(client.server.origin, "account")
        val transport = api.savedTransport(scope, { current })
        web.enqueue(json(descriptor()))
        val descriptor = transport.prepare(scope, "track")
        web.takeRequest()
        web.enqueue(audio("3456789", 206).setHeader("Content-Range", "bytes 3-9/10"))
        var escaped: java.io.InputStream? = null
        transport.transfer(scope, descriptor, 3) { response ->
            response.use {
                assertEquals(206, response.status)
                assertEquals("bytes 3-9/10", response.contentRange)
                escaped = response.body
                val buffer = ByteArray(16)
                assertEquals(7, response.body!!.read(buffer))
                assertEquals(-1, response.body!!.read(buffer))
                assertEquals("3456789", buffer.copyOf(7).toString(Charsets.UTF_8))
            }
        }
        assertSafeFailure { escaped!!.read() }
        web.takeRequest()
        current = false
        val count = web.requestCount
        assertSafeFailure { transport.transfer(scope, descriptor, 0) {} }
        assertEquals(count, web.requestCount)
    }

    @Test fun completeManifestBoundsAggregateWireBytesAcrossIndividuallySmallPages() = runBlocking {
        login()
        val padding = "x".repeat(3 * 1024 * 1024)
        repeat(6) { index ->
            val body = page(items = row("entry$index", index), total = 7, audio = 7,
                next = "cursor$index", limit = 1)
            web.enqueue(json(body.dropLast(1) + ",\"padding\":\"" + padding + "\"}"))
        }
        assertSafeFailure { api.manifest(manual, 1) }
        assertEquals(7, web.requestCount) // login and six pages; no unbounded seventh read
    }

    @Test fun storeAdapterPreservesOnlyBoundedNumericBusyRetryAfter() = runBlocking {
        login()
        val scope = SavedScope(client.server.origin, "account")
        val transport = api.savedTransport(scope) { true }
        web.enqueue(json("""{"error":{"code":"MEDIA_BUSY","message":"/private/cookie-secret"}}""", 429)
            .setHeader("Retry-After", "1"))
        val busy = failure { transport.prepare(scope, "track") } as SavedTransportException
        assertEquals(429, busy.status)
        assertEquals(1000L, busy.retryAfterMillis)
        web.takeRequest()
        web.enqueue(json(descriptor()))
        val media = transport.prepare(scope, "track"); web.takeRequest()
        web.enqueue(json("{}", 503).setHeader("Retry-After", "99999"))
        val timeout = failure { transport.transfer(scope, media, 0) { fail("Error response must not expose a body") } } as SavedTransportException
        assertEquals(503, timeout.status)
        assertEquals(3_600_000L, timeout.retryAfterMillis)
        web.takeRequest()
        web.enqueue(json("{}", 429).addHeader("Retry-After", "1").addHeader("Retry-After", "2"))
        val ambiguous = failure { transport.prepare(scope, "track") } as SavedTransportException
        assertNull(ambiguous.retryAfterMillis)
    }

    @Test fun storeAdapterShares401AndNeverReturnsTheRemoteErrorPayload() = runBlocking {
        login()
        val scope = SavedScope(client.server.origin, "account")
        val transport = api.savedTransport(scope, { true })
        web.enqueue(json("""{"error":"/private/cookie-secret"}""", 401))
        val error = failure { withContext(Dispatchers.IO) { transport.prepare(scope, "track") } }
        assertTrue(error is SavedTransportException)
        assertEquals(401, (error as SavedTransportException).status)
        assertNull(client.currentSession())
    }

    @Test fun productionAdapterJoinsBlockedDiskWriteHashAndCloseBeforeReleasingStoreOwnership() = runBlocking {
        login()
        serveVerifiedMedia()
        val scope = SavedScope(client.server.origin, "account")
        for (phase in listOf("write", "hash", "close")) {
            val root = java.nio.file.Files.createTempDirectory("offline-owned-$phase-").toFile()
            val media = PrivateSavedMediaBackend(File(root, "media"))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbackExited = CountDownLatch(1)
            val blocked = AtomicBoolean()
            val removedWhileBlocked = AtomicInteger()
            val promoted = AtomicInteger()
            var available = Long.MAX_VALUE
            fun holdDisk() {
                blocked.set(true)
                entered.countDown()
                try { check(release.await(10, TimeUnit.SECONDS)) { "Blocked disk fixture was not released" } }
                finally { blocked.set(false) }
            }
            val backend = object : SavedRepresentationBackend by media {
                override fun writePartial(namespace: String, key: String, offset: Long): OutputStream {
                    val output = media.writePartial(namespace, key, offset)
                    return object : OutputStream() {
                        override fun write(b: Int) { output.write(b); if (phase == "write") holdDisk() }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            output.write(b, off, len)
                            if (phase == "write") holdDisk()
                        }
                        override fun close() { try { if (phase == "close") holdDisk() } finally { output.close() } }
                    }
                }
                override fun openPartial(namespace: String, key: String): InputStream {
                    val input = media.openPartial(namespace, key)
                    return object : FilterInputStream(input) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (phase == "hash") holdDisk()
                            return input.read(b, off, len)
                        }
                    }
                }
                override fun remove(namespace: String, key: String) {
                    if (blocked.get()) removedWhileBlocked.incrementAndGet()
                    media.remove(namespace, key)
                }
                override fun promote(namespace: String, key: String) { promoted.incrementAndGet(); media.promote(namespace, key) }
            }
            try {
                SavedLibraryStore(root, SavedLocalEligibility { true }, backend, availableBytes = { available }).use { library ->
                    library.bind(scope)
                    install(library, scope, manual, listOf(SavedOccurrence("entry", "track", "Synthetic")))
                    val other = SavedTarget(SavedTargetKind.ALBUM, "other-$phase")
                    install(library, scope, other, emptyList())
                    val actual = api.savedTransport(scope) { true }
                    val observed = object : SavedDownloadTransport by actual {
                        override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long,
                            consume: (SavedTransferResponse) -> Unit) {
                            actual.transfer(scope, descriptor, offset) { response ->
                                try { consume(response) } finally { callbackExited.countDown() }
                            }
                        }
                    }
                    // Inherit runBlocking's event loop so yield below deterministically runs the
                    // cancelled suspension's finally/join before checking held store admission.
                    val download = async { SavedDownloader(library, observed).download(scope, manual) }
                    try {
                        assertTrue("Disk phase $phase did not start", withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                        download.cancel()
                        yield()
                        assertFalse("Cancellation released the worker before $phase finished", download.isCompleted)
                        val admitted = library.beginWork(scope, other)
                        if (admitted != null) library.endWork(admitted, null)
                        assertNull("Another transfer stole admission during $phase", admitted)

                        // Black-box reservation proof: a ~96KiB metadata stage fits 128KiB free
                        // alone, but NOT while the blocked transfer still owns its 64KiB claim.
                        available = 128L * 1024
                        val probeTarget = SavedTarget(SavedTargetKind.ALBUM, "space-$phase")
                        val probe = library.beginManifest(scope, probeTarget, "Space probe", "probe", 1)
                        try {
                            try {
                                library.appendManifest(probe, 0, listOf(SavedOccurrence("probe", "probe", "x".repeat(96 * 1024))), true)
                                fail("Transfer reservation was released before blocked $phase finished")
                            } catch (_: SavedStorageFailure) { }
                        } finally {
                            available = Long.MAX_VALUE
                            library.cancel(scope, probeTarget)
                        }
                        library.unsave(scope, manual)
                        assertEquals("Unsave deleted bytes still pinned by blocked $phase", 0, removedWhileBlocked.get())
                        assertTrue(media.ownedBytes() > 0)
                    } finally {
                        release.countDown()
                        download.cancel()
                        withContext(NonCancellable) {
                            withTimeout(5_000) { download.join() }
                            if (entered.count == 0L) assertTrue(withContext(Dispatchers.IO) { callbackExited.await(5, TimeUnit.SECONDS) })
                        }
                    }
                    assertEquals(0, promoted.get())
                    assertEquals(0L, media.ownedBytes())
                    assertNull(library.snapshot(scope, manual))
                    val admitted = library.beginWork(scope, other)
                    assertNotNull("Transfer admission was not released after callback exit", admitted)
                    library.endWork(requireNotNull(admitted), null)
                }
            } finally { check(root.deleteRecursively()) { "Owned disk fixture cleanup failed" } }
        }
    }

    @Test fun productionAdapterPreservesOnlySafeTypedLocalStorageFailureWithoutPrivateCause() = runBlocking {
        login()
        serveVerifiedMedia()
        val scope = SavedScope(client.server.origin, "account")
        val transport = api.savedTransport(scope) { true }
        val descriptor = transport.prepare(scope, "track")
        val error = failure {
            transport.transfer(scope, descriptor, 0) { throw SavedStorageFailure(IOException("/private/disk/cookie-secret ENOSPC")) }
        }
        assertTrue(error is SavedStorageFailure)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        web.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                json("""{"error":{"code":"SavedStorageFailure","message":"/private/cookie-secret"}}""", 500)
        }
        val remote = failure { transport.transfer(scope, descriptor, 0) { fail("Remote error cannot deliver bytes") } }
        assertTrue(remote is SavedTransportException)
        assertFalse(remote is SavedStorageFailure)
    }

    @Test fun productionAdapterDiskFailuresStopWithoutRetryOrStartingTheNextTrack() = runBlocking {
        login()
        serveVerifiedMedia()
        val scope = SavedScope(client.server.origin, "account")
        for (phase in listOf("open", "write", "sync", "hash", "promote")) {
            val root = java.nio.file.Files.createTempDirectory("offline-storage-$phase-").toFile()
            val media = PrivateSavedMediaBackend(File(root, "media"))
            val failedOperations = AtomicInteger()
            fun failDisk(): Nothing {
                failedOperations.incrementAndGet()
                throw IOException("/private/disk $phase ENOSPC/EDQUOT")
            }
            val backend = object : SavedRepresentationBackend by media {
                override fun writePartial(namespace: String, key: String, offset: Long): OutputStream {
                    if (phase == "open") failDisk()
                    val output = media.writePartial(namespace, key, offset)
                    return object : OutputStream() {
                        override fun write(b: Int) { if (phase == "write") failDisk(); output.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (phase == "write") failDisk()
                            output.write(b, off, len)
                        }
                        override fun close() { output.close(); if (phase == "sync") failDisk() }
                    }
                }
                override fun openPartial(namespace: String, key: String): InputStream {
                    if (phase == "hash") failDisk()
                    return media.openPartial(namespace, key)
                }
                override fun promote(namespace: String, key: String) {
                    if (phase == "promote") failDisk()
                    media.promote(namespace, key)
                }
            }
            try {
                SavedLibraryStore(root, SavedLocalEligibility { true }, backend, availableBytes = { Long.MAX_VALUE }).use { library ->
                    library.bind(scope)
                    install(library, scope, manual, listOf(SavedOccurrence("first", "track", "First"), SavedOccurrence("later", "later", "Later")))
                    val before = web.requestCount
                    SavedDownloader(library, api.savedTransport(scope) { true }, maxAttemptsPerTrack = 3).download(scope, manual)
                    assertEquals("Storage failure $phase retried or began another track", 2, web.requestCount - before)
                    assertEquals("Storage operation $phase retried without HTTP", 1, failedOperations.get())
                    val result = requireNotNull(library.snapshot(scope, manual))
                    assertEquals("storage", result.failure)
                    assertTrue(result.occurrences.none { it.available })
                    assertNull(library.open(scope, manual, "first"))
                }
            } finally { check(root.deleteRecursively()) { "Owned storage fixture cleanup failed" } }
        }
    }

    @Test fun jobCancellationAloneDuringProductionAdapterDiskCloseOrHashCannotPromoteOrPublishReplacement() = runBlocking {
        login()
        serveVerifiedMedia(distinctOldTrack = true)
        val scope = SavedScope(client.server.origin, "account")
        for (phase in listOf("close", "hash")) {
            val root = java.nio.file.Files.createTempDirectory("offline-job-only-$phase-").toFile()
            val media = PrivateSavedMediaBackend(File(root, "media"))
            val armed = AtomicBoolean()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbackExited = CountDownLatch(1)
            val pinsAtCallbackExit = AtomicInteger(-1)
            val promoted = AtomicInteger()
            var safeToDelete = true
            fun holdDisk() {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Job-only disk fixture was not released" }
            }
            val backend = object : SavedRepresentationBackend by media {
                override fun writePartial(namespace: String, key: String, offset: Long): OutputStream {
                    val output = media.writePartial(namespace, key, offset)
                    return object : OutputStream() {
                        override fun write(b: Int) = output.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = output.write(b, off, len)
                        override fun close() {
                            try { if (armed.get() && phase == "close") holdDisk() }
                            finally { output.close() }
                        }
                    }
                }
                override fun openPartial(namespace: String, key: String): InputStream {
                    val input = media.openPartial(namespace, key)
                    return object : FilterInputStream(input) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (armed.get() && phase == "hash") holdDisk()
                            return input.read(b, off, len)
                        }
                    }
                }
                override fun promote(namespace: String, key: String) {
                    if (armed.get()) promoted.incrementAndGet()
                    media.promote(namespace, key)
                }
            }
            try {
                SavedLibraryStore(root, SavedLocalEligibility { true }, backend, availableBytes = { Long.MAX_VALUE }).use { library ->
                    library.bind(scope)
                    install(library, scope, manual, listOf(SavedOccurrence("old-entry", "old", "Old")), "before")
                    SavedDownloader(library, api.savedTransport(scope) { true }).download(scope, manual)
                    val previous = requireNotNull(library.snapshot(scope, manual))
                    assertEquals(SavedDownloadState.COMPLETE, previous.state)
                    val oldDescriptor = requireNotNull(previous.occurrences.single().media)
                    install(library, scope, manual, listOf(SavedOccurrence("new-entry", "track", "New")), "after")
                    val inspection = requireNotNull(library.beginWork(scope, manual))
                    val namespace = inspection.namespace
                    library.endWork(inspection, null)
                    val other = SavedTarget(SavedTargetKind.ALBUM, "job-only-other-$phase")
                    install(library, scope, other, emptyList())
                    val actual = api.savedTransport(scope) { true }
                    val replacementDescriptor = actual.prepare(scope, "track")
                    // Physical identity is content-addressed, not track-ID-addressed. Distinct
                    // IDs with equal bytes would correctly skip transfer and never reach IO.
                    assertNotEquals("Replacement fixture must require a new representation",
                        mediaKey(oldDescriptor), mediaKey(replacementDescriptor))
                    val observed = object : SavedDownloadTransport by actual {
                        override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long,
                            consume: (SavedTransferResponse) -> Unit) {
                            actual.transfer(scope, descriptor, offset) { response ->
                                try { consume(response) } finally {
                                    pinsAtCallbackExit.set(namespace.writers.values.sum())
                                    callbackExited.countDown()
                                }
                            }
                        }
                    }
                    armed.set(true)
                    safeToDelete = false
                    val beforeDownload = web.requestCount
                    val download = async { SavedDownloader(library, observed).download(scope, manual) }
                    try {
                        if (!withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }) {
                            val completed = download.isCompleted
                            val state = if (completed) library.snapshot(scope, manual) else null
                            fail("Disk phase=$phase not entered; completed=$completed requests=${web.requestCount - beforeDownload} " +
                                "generation=${state?.generation} state=${state?.state} failure=${state?.failure}")
                        }
                        // The ONLY revocation after work starts is Job.cancel(): deliberately no
                        // unsave, store.cancel, network-mode change, access fence or retirement.
                        download.cancel()
                        yield()
                        assertFalse("Job cancellation returned before disk $phase finished", download.isCompleted)
                        assertEquals("Writer pin escaped before callback exit", 1, namespace.writers.values.sum())
                        val admitted = library.beginWork(scope, other)
                        if (admitted != null) library.endWork(admitted, null)
                        assertNull("Transfer admission escaped while callback was blocked", admitted)
                    } finally {
                        release.countDown()
                        download.cancel()
                        withContext(NonCancellable) {
                            withTimeout(5_000) { download.join() }
                            if (entered.count == 0L) assertTrue(withContext(Dispatchers.IO) { callbackExited.await(5, TimeUnit.SECONDS) })
                        }
                        safeToDelete = true
                    }
                    assertEquals("Cancelled disk $phase still promoted media", 0, promoted.get())
                    assertEquals(1, pinsAtCallbackExit.get())
                    assertTrue(namespace.writers.isEmpty())
                    val installed = requireNotNull(library.snapshot(scope, manual))
                    assertEquals("before", installed.generation)
                    assertEquals(listOf("old-entry"), installed.occurrences.map { it.occurrence.occurrenceId })
                    assertTrue(installed.occurrences.single().available)
                    assertNull(library.open(scope, manual, "new-entry"))
                    val admitted = library.beginWork(scope, other)
                    assertNotNull(admitted)
                    library.endWork(requireNotNull(admitted), null)
                }
            } finally {
                check(safeToDelete) { "Unjoined owned callback; preserve $root" }
                check(root.deleteRecursively()) { "Owned Job-only fixture cleanup failed" }
            }
        }
    }

    @Test fun delayedOldCredential401KeepsVerifiedSavedMediaButFreshClientInvalidationFencesAndRetiresIt() = runBlocking {
        val now = AtomicLong(System.currentTimeMillis() / 1000 * 1000)
        val day = TimeUnit.DAYS.toMillis(1)
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), store, now::get)
        api = OfflineCollectionsApi(client)
        fun rolling(deadline: Long, body: String): MockResponse = json(body)
            .setHeader("X-Spofify-Session-Mode", "rolling-v1")
            .setHeader("Set-Cookie", "spofify_session=cookie-secret; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000; Expires=" +
                DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(deadline).atZone(ZoneOffset.UTC)))
        web.enqueue(rolling(now.get() + 30 * day,
            """{"accountId":"account","username":"listener","role":"user","csrfToken":"csrf-secret","sessionMode":"rolling-v1"}"""))
        val session = client.login("listener", "synthetic-password")
        web.takeRequest()
        val identity = client.validatedSessionIdentity()
        val scope = SavedScope(client.server.origin, "account")
        serveVerifiedMedia()
        val root = java.nio.file.Files.createTempDirectory("offline-rolling-401-").toFile()
        val media = PrivateSavedMediaBackend(File(root, "media"))
        var safeToDelete = true
        try {
            // Eligibility intentionally remains true even after server revocation: unavailable
            // reads below must be caused by the authoritative owner's atomic fence, not a test
            // shortcut that consults currentSession() or retires on a raw response status.
            SavedLibraryStore(root, SavedLocalEligibility { it == scope }, media, availableBytes = { Long.MAX_VALUE }).use { library ->
                library.bind(scope)
                install(library, scope, manual, listOf(SavedOccurrence("known", "track", "Known")))
                SavedDownloader(library, api.savedTransport(scope) { true }).download(scope, manual)
                assertEquals(SavedDownloadState.COMPLETE, library.snapshot(scope, manual)!!.state)
                val invalidations = AtomicInteger()
                val pendingRetirement = AtomicReference<SavedScopeFence?>()
                client.onAuthenticationChanged = { change ->
                    if (change.kind == AuthenticationChangeKind.INVALIDATED) {
                        pendingRetirement.set(library.fenceAccess()) // atomic, no IO in callback
                        invalidations.incrementAndGet()
                    }
                }
                try {
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val mediaDispatcher = web.dispatcher
                    now.addAndGet(day)
                    val renewed = now.get() + 30 * day
                    web.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                            "/api/me/offline/v1/tracks/delayed-denied/media" -> {
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                json("{}", 401)
                            }
                            "/renew" -> rolling(renewed, "{}")
                            "/api/me/offline/v1/tracks/fresh-denied/media" -> json("{}", 401)
                            else -> mediaDispatcher.dispatch(request)
                        }
                    }
                    val deniedTarget = SavedTarget(SavedTargetKind.ALBUM, "denied")
                    install(library, scope, deniedTarget, listOf(SavedOccurrence("denied", "delayed-denied", "Denied")))
                    safeToDelete = false
                    val denied = async(Dispatchers.IO) {
                        SavedDownloader(library, api.savedTransport(scope) { true }).download(scope, deniedTarget)
                    }
                    try {
                        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                        ApiTransport(client).get(listOf("renew"))
                        assertEquals(identity, client.validatedSessionIdentity())
                        assertSame(session, client.currentSession())
                        assertEquals(renewed, requireNotNull(Cookie.parse(client.server.url(), store.value!!.cookie)).expiresAt)
                    } finally {
                        release.countDown()
                        withContext(NonCancellable) { withTimeout(5_000) { denied.join() } }
                        safeToDelete = true
                    }
                    denied.await()
                    assertEquals(0, invalidations.get())
                    assertNull(pendingRetirement.get())
                    assertEquals(identity, client.validatedSessionIdentity())
                    assertSame(session, client.currentSession())
                    val known = requireNotNull(library.snapshot(scope, manual))
                    assertEquals(SavedDownloadState.COMPLETE, known.state)
                    assertTrue(known.occurrences.single().available)
                    library.open(scope, manual, "known").use { lease ->
                        requireNotNull(lease)
                        assertEquals("0123456789", lease.input.readNBytes(11).toString(Charsets.UTF_8))
                    }
                    val freshTarget = SavedTarget(SavedTargetKind.ALBUM, "fresh-denied")
                    install(library, scope, freshTarget, listOf(SavedOccurrence("fresh", "fresh-denied", "Fresh")))
                    library.open(scope, manual, "known").use { held ->
                        requireNotNull(held)
                        val beforeFresh = web.requestCount
                        SavedDownloader(library, api.savedTransport(scope) { true }, maxAttemptsPerTrack = 1).download(scope, freshTarget)
                        assertEquals(1, web.requestCount - beforeFresh)
                        assertEquals(1, invalidations.get())
                        assertNull(client.currentSession())
                        assertNotNull(pendingRetirement.get())
                        assertNull(library.snapshot(scope, manual))
                        assertTrue(library.snapshots(scope).isEmpty())
                        assertSafeFailure { held.input.read() }
                        assertTrue("Deferred retirement must not pretend bytes already disappeared", media.ownedBytes() > 0)
                    }
                    library.retire(requireNotNull(pendingRetirement.getAndSet(null)))
                    assertEquals(0L, media.ownedBytes())
                    assertTrue(library.snapshots(scope).isEmpty())
                } finally { client.onAuthenticationChanged = null }
            }
        } finally {
            check(safeToDelete) { "Unjoined stale-401 operation; preserve $root" }
            check(root.deleteRecursively()) { "Owned rolling-401 fixture cleanup failed" }
        }
    }

    private fun install(library: SavedLibraryStore, scope: SavedScope, target: SavedTarget, items: List<SavedOccurrence>, generation: String = "generation") {
        val stage = library.beginManifest(scope, target, "Synthetic", generation, items.size)
        library.appendManifest(stage, 0, items, true)
        library.finishManifest(stage)
    }
    private fun serveVerifiedMedia(distinctOldTrack: Boolean = false) {
        web.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val segments = request.requestUrl!!.pathSegments
                val trackId = segments[segments.lastIndex - 1]
                val bytes = if (distinctOldTrack && trackId == "old") "9876543210" else "0123456789"
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
                return when (segments.last()) {
                    "media" -> json(descriptor(bytes.length).replace(sha, digest)
                        .replace("\"trackId\":\"track\"", "\"trackId\":\"$trackId\"")
                        .replace("/tracks/track/audio", "/tracks/$trackId/audio"))
                    "audio" -> audio(bytes).setHeader("ETag", "\"sha256:$digest\"")
                    else -> json("{}", 404)
                }
            }
        }
    }

    private suspend fun login() {
        web.enqueue(json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"csrf-secret"}""")
            .addHeader("Set-Cookie", "spofify_session=cookie-secret; Path=/; HttpOnly; Max-Age=3600"))
        client.login("listener", "synthetic-password")
        web.takeRequest()
    }
    private fun playlistSummary() = "\"playlist\":{\"id\":\"saved\",\"kind\":\"manual\",\"name\":\"Saved music\",\"description\":\"\",\"revision\":2}"
    private fun row(occurrence: String = "entry", position: Int = 0, audio: Boolean = true) =
        """{"occurrenceId":"$occurrence","position":$position,"track":{"kind":"track","id":"track","name":"Synthetic song","hasAudio":$audio,"durationMs":10,"coverage":{"tracks":1,"inLibrary":1,"albums":1}}}"""
    private fun page(items: String = row(), total: Int = 1, audio: Int = 1, next: String? = null, limit: Int = 100) =
        """{"version":1,${playlistSummary()},"fence":"f1_fence","items":[$items],"total":$total,"recordedAudioTotal":$audio,"limit":$limit,"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}"""
    private fun descriptor(length: Int = 10) =
        """{"version":1,"trackId":"track","mediaVersion":"$version","sha256":"$sha","byteLength":$length,"format":"flac","contentType":"audio/flac","downloadUrl":"/api/me/offline/v1/tracks/track/audio?version=sha256%3A$sha"}"""
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun audio(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "audio/flac").setHeader("ETag", "\"$version\"").setBody(body)
    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block(); fail("Expected safe failure") } catch (error: Exception) {
            assertFalse(error.toString().contains("cookie-secret"))
            assertFalse(error.toString().contains("/private/"))
            return error
        }
        throw AssertionError()
    }
    private suspend fun assertSafeFailure(block: suspend () -> Unit) { assertTrue(failure(block) is IOException) }
    private class RecordingSink : OfflineMediaSink {
        var response: OfflineMediaResponse? = null
        val data = Buffer()
        override fun begin(response: OfflineMediaResponse) { this.response = response }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { data.write(bytes, offset, length) }
    }
}

/** Separately selected by -Pspofify.offlineApiIntegration=true; NEVER silently skips.
 * The parent/backend owns the pinned production Go fixture process, descriptor's private file,
 * stdin lifetime, source/binary provenance, and stopped receipt. This test launches nothing.
 * Env: SPOFIFY_TEST_OFFLINE_FIXTURE_DESCRIPTOR, SPOFIFY_TEST_OFFLINE_SOURCE_REVISION.
 * Matching a JSON source pin is not by itself binary provenance: the owner's launch evidence
 * must bind the built binary hash and exact source commit to the descriptor/process used here. */
class OfflineCollectionsGoIntegrationTest {
    @Test fun productionTransportReadsTypedPagedManifestsAndVerifiesRealGo200And206Media() = runBlocking {
        withTimeout(120_000) {
            fun required(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: throw AssertionError("Required selected offline API fixture input missing: $name")
            val source = required("SPOFIFY_TEST_OFFLINE_SOURCE_REVISION")
            require(source.matches(Regex("[0-9a-f]{40}"))) { "Invalid fixture source pin" }
            val descriptorFile = File(required("SPOFIFY_TEST_OFFLINE_FIXTURE_DESCRIPTOR"))
            require(descriptorFile.isAbsolute && descriptorFile.isFile &&
                !java.nio.file.Files.isSymbolicLink(descriptorFile.toPath())) { "Invalid fixture descriptor file" }
            val raw = descriptorFile.inputStream().use { it.readNBytes(64 * 1024 + 1) }
            require(raw.size <= 64 * 1024) { "Oversized fixture descriptor" }
            val ready = ApiJson.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw AssertionError("Incompatible fixture descriptor")
            fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: throw AssertionError("Missing fixture field: $key")
            fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)
                ?.takeIf { !it.isString }?.longOrNull ?: throw AssertionError("Missing fixture number: $key")
            assertEquals("ready", ready.text("event"))
            assertEquals(1L, ready.number("version"))
            assertEquals("The running Go fixture must match its selected exact source", source, ready.text("sourceRevision"))
            val server = ServerAddress.parse(ready.text("origin"))
            require(server.url().host == "127.0.0.1" && server.url().scheme == "http") { "Fixture must use numeric loopback" }
            val accountId = ready.text("userId")
            val sessionStore = object : SessionStore {
                private var value: StoredSession? = null
                override fun read() = value
                override fun write(value: StoredSession?) { this.value = value }
            }
            val client = SpofifyClient(server, sessionStore)
            client.login(ready.text("username"), ready.text("password"))
            assertEquals(accountId, client.validatedSessionIdentity()!!.accountId)
            val api = OfflineCollectionsApi(client)
            val targets = ready["targets"] as? JsonArray ?: throw AssertionError("Missing fixture targets")
            assertEquals(4, targets.size)
            val expectedTracks = (ready["trackIds"] as? JsonArray)?.map { (it as JsonPrimitive).content }
                ?: throw AssertionError("Missing fixture tracks")
            val duplicate = ready.text("duplicateTrackId")
            val manifests = targets.map { element ->
                val target = element as? JsonObject ?: throw AssertionError("Incompatible fixture target")
                val kind = when (target.text("kind")) {
                    "playlist" -> when (target.text("playlistKind")) {
                        "manual" -> SavedTargetKind.MANUAL_PLAYLIST
                        "derived" -> SavedTargetKind.DERIVED_PLAYLIST
                        else -> throw AssertionError("Unsupported fixture playlist kind")
                    }
                    "album" -> SavedTargetKind.ALBUM
                    "artist" -> SavedTargetKind.ARTIST
                    else -> throw AssertionError("Unsupported fixture collection kind")
                }
                // Seven rows forces eight real requests for each 50-row playlist, and seven
                // requests for each 48-row catalog target, including encoded spaces and plus.
                val manifest = api.manifest(SavedTarget(kind, target.text("id")), limit = 7)
                assertEquals(target.text("name"), manifest.title)
                assertEquals(target.number("total"), manifest.total)
                assertEquals(manifest.total, manifest.occurrences.size.toLong())
                assertEquals(manifest.occurrences.size, manifest.occurrences.map { it.occurrenceId }.toSet().size)
                assertEquals(1, manifest.occurrences.count { it.trackId == ready.text("metadataOnlyTrackId") && !it.hasAudio })
                assertEquals(1, manifest.occurrences.count { it.trackId == ready.text("missingFileTrackId") && it.hasAudio })
                if (kind == SavedTargetKind.MANUAL_PLAYLIST || kind == SavedTargetKind.DERIVED_PLAYLIST) {
                    assertEquals(listOf(duplicate, duplicate) + expectedTracks, manifest.occurrences.map { it.trackId })
                } else {
                    assertEquals(expectedTracks.toSet(), manifest.occurrences.map { it.trackId }.toSet())
                }
                manifest
            }
            assertEquals(SavedTargetKind.values().toSet(), manifests.map { it.target.kind }.toSet())
            suspend fun expected404(block: suspend () -> Unit) {
                try { block(); throw AssertionError("Expected private/unavailable 404") }
                catch (error: ApiException) { assertEquals(404, error.status) }
            }
            expected404 { api.manifest(SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, ready.text("otherPlaylistId"))) }
            expected404 { api.currentMedia(ready.text("metadataOnlyTrackId")) }
            expected404 { api.currentMedia(ready.text("missingFileTrackId")) }
            val expected = ready["media"] as? JsonObject ?: throw AssertionError("Missing fixture representation")
            val media = api.currentMedia(expected.text("trackId"))
            assertEquals(expected.text("mediaVersion"), media.mediaVersion)
            assertEquals(expected.text("sha256"), media.sha256)
            assertEquals(expected.number("byteLength"), media.byteLength)
            assertEquals(expected.text("downloadUrl"), media.downloadUrl)
            val prefix = ByteArray(257)
            var received = 0L
            val completeHash = MessageDigest.getInstance("SHA-256")
            val full = api.download(media, sink = object : OfflineMediaSink {
                override fun begin(response: OfflineMediaResponse) {
                    assertEquals(200, response.status); assertEquals(0L, response.startOffset)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (received < prefix.size) bytes.copyInto(prefix, received.toInt(), offset,
                        offset + minOf(length, prefix.size - received.toInt()))
                    completeHash.update(bytes, offset, length)
                    received += length
                }
            })
            assertEquals(media.byteLength, full.transferredBytes)
            assertEquals(media.byteLength, received)
            assertEquals(media.sha256, hex(completeHash.digest()))
            assertEquals("RIFF", prefix.copyOf(4).toString(Charsets.US_ASCII))
            val resumeHash = MessageDigest.getInstance("SHA-256").apply { update(prefix) }
            val resumed = api.download(media, prefix.size.toLong(), object : OfflineMediaSink {
                override fun begin(response: OfflineMediaResponse) {
                    assertEquals(206, response.status)
                    assertEquals(prefix.size.toLong(), response.startOffset)
                    assertEquals("bytes ${prefix.size}-${media.byteLength - 1}/${media.byteLength}", response.contentRange)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { resumeHash.update(bytes, offset, length) }
            })
            assertEquals(media.byteLength - prefix.size, resumed.transferredBytes)
            assertEquals(media.sha256, hex(resumeHash.digest()))
            // The concrete store-facing adapter also reads actual production Go bytes, without
            // exposing a leased network stream outside the original response callback.
            val scope = SavedScope(server.origin, accountId)
            val transport = api.savedTransport(scope) { client.validatedSessionIdentity()?.accountId == accountId }
            val saved = transport.prepare(scope, media.trackId)
            assertEquals(media.sha256, saved.sha256)
            val adapterHash = MessageDigest.getInstance("SHA-256")
            transport.transfer(scope, saved, 0) { response ->
                response.use {
                    assertEquals(200, response.status)
                    val body = response.body ?: throw AssertionError("Missing real Go audio body")
                    val buffer = ByteArray(8192)
                    var length = 0L
                    while (true) {
                        val count = body.read(buffer)
                        if (count == -1) break
                        adapterHash.update(buffer, 0, count)
                        length += count
                    }
                    assertEquals(saved.byteLength, length)
                }
            }
            assertEquals(media.sha256, hex(adapterHash.digest()))

            // Real terminal membership -> production adapter -> production file-backed store.
            // The coordinator places its private descriptor in the owned transport-runtime;
            // this exclusively created child is the only directory this test ever removes.
            val diskRoot = java.nio.file.Files.createTempDirectory(descriptorFile.parentFile.toPath(), "client-store-").toFile()
            try {
                val identity = client.validatedSessionIdentity()
                SavedLibraryStore(diskRoot, SavedLocalEligibility {
                    it == scope && client.validatedSessionIdentity() == identity
                }).use { library ->
                    library.bind(scope)
                    val manifest = manifests.first { it.target.kind == SavedTargetKind.MANUAL_PLAYLIST }
                    val stage = library.beginManifest(scope, manifest.target, manifest.title,
                        manifest.generation, manifest.total.toInt())
                    library.appendManifest(stage, 0, manifest.occurrences, terminal = true)
                    library.finishManifest(stage)
                    SavedDownloader(library, api.savedTransport(scope) {
                        client.validatedSessionIdentity() == identity
                    }, maxAttemptsPerTrack = 1).download(scope, manifest.target)
                    val installed = library.snapshot(scope, manifest.target)
                        ?: throw AssertionError("Missing installed saved membership")
                    assertEquals(manifest.occurrences.map { it.occurrenceId }, installed.occurrences.map { it.occurrence.occurrenceId })
                    assertEquals(50, installed.occurrences.size)
                    assertEquals(48, installed.occurrences.count { it.available })
                    assertEquals(SavedDownloadState.FAILED, installed.state)
                    assertEquals("unavailable", installed.failure)
                    assertFalse(installed.occurrences.first { it.occurrence.trackId == ready.text("missingFileTrackId") }.available)
                    assertFalse(installed.occurrences.first { it.occurrence.trackId == ready.text("metadataOnlyTrackId") }.available)
                    val occurrence = installed.occurrences.first { it.occurrence.trackId == media.trackId }
                    library.open(scope, manifest.target, occurrence.occurrence.occurrenceId).use { lease ->
                        requireNotNull(lease) { "Verified synthetic saved media is not playable" }
                        assertEquals(media.sha256, lease.descriptor.sha256)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        var length = 0L
                        while (true) {
                            val count = lease.input.read(buffer)
                            if (count == -1) break
                            digest.update(buffer, 0, count)
                            length += count
                        }
                        assertEquals(media.byteLength, length)
                        assertEquals(media.sha256, hex(digest.digest()))
                    }
                    assertTrue(library.ownedBytes() > media.byteLength)
                }
            } finally {
                check(diskRoot.deleteRecursively()) { "Owned selected-test store cleanup failed" }
            }
        }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
