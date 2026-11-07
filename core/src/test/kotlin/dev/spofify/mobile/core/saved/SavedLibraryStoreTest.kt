package dev.spofify.mobile.core.saved

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SavedLibraryStoreTest {
    private lateinit var root: File
    private lateinit var store: SavedLibraryStore
    private lateinit var media: PrivateSavedMediaBackend
    private val scope = SavedScope("https://synthetic.invalid", "account-a")
    private val target = SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, "playlist-a")
    private var eligible = true

    @Before fun setup() {
        root = Files.createTempDirectory("saved-store-").toFile()
        media = PrivateSavedMediaBackend(File(root, "representations"))
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, media)
        store.bind(scope)
    }
    @After fun cleanup() {
        store.close()
        check(root.deleteRecursively())
    }

    @Test fun completeManifestPreservesDuplicateOccurrenceOrderAndTypedTargets() = runBlocking {
        val api = Fixture(mapOf("one" to bytes("one"), "two" to bytes("two")))
        install(target, "v1", listOf(row("a", "one"), row("b", "two"), row("c", "one")), pageSize = 1)
        SavedDownloader(store, api).download(scope, target)
        val value = store.snapshot(scope, target)!!
        assertEquals(listOf("a", "b", "c"), value.occurrences.map { it.occurrence.occurrenceId })
        assertEquals(SavedDownloadState.COMPLETE, value.state)
        assertEquals(2, api.offsets.size)
        assertEquals("one", store.open(scope, target, "c")!!.use { it.input.readBytes().toString(Charsets.UTF_8) })
        for (kind in SavedTargetKind.entries) {
            val typed = SavedTarget(kind, "same-id")
            install(typed, "empty", emptyList())
            assertEquals(kind, store.snapshot(scope, typed)!!.target.kind)
            assertEquals(SavedDownloadState.COMPLETE, store.snapshot(scope, typed)!!.state)
        }
    }

    @Test fun saveIntentAndFailedManifestRemainVisibleButNeverComplete() {
        store.requestSave(scope, target, "Pending")
        assertEquals(SavedDownloadState.PARTIAL, store.snapshot(scope, target)!!.state)
        store.failManifest(scope, target)
        assertEquals(SavedDownloadState.FAILED, store.snapshot(scope, target)!!.state)
        val stage = store.beginManifest(scope, target, "Title", "v1", 2)
        store.appendManifest(stage, 0, listOf(row("first", "one")), false)
        expectFailure { store.finishManifest(stage) }
        assertEquals(0, store.snapshot(scope, target)!!.occurrences.size)
        store.cancel(scope, target)
        expectFailure { store.appendManifest(stage, 1, listOf(row("last", "two")), true) }
    }

    @Test fun missingAudioStaysVisibleAndNeverCompletes() = runBlocking {
        install(target, "v1", listOf(row("one", "one"), row("missing", "gone").copy(hasAudio = false)))
        val api = Fixture(mapOf("one" to bytes("ok")))
        SavedDownloader(store, api).download(scope, target)
        val value = store.snapshot(scope, target)!!
        assertEquals(listOf(true, false), value.occurrences.map { it.available })
        assertEquals(SavedDownloadState.PARTIAL, value.state)
        assertEquals(listOf("one"), api.prepared)
    }

    @Test fun interruptedTransferResumesOnlyStrict206AndSurvivesRestart() = runBlocking {
        val full = bytes("abcdefghij")
        val api = Fixture(mapOf("one" to full))
        install(target, "v1", listOf(row("a", "one")))
        api.handler = { d, _ -> response(d, failingAfter(full, 4)) }
        SavedDownloader(store, api, 1).download(scope, target)
        assertFalse(store.snapshot(scope, target)!!.occurrences.single().available)
        store.close()
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, media)
        store.bind(scope)
        api.handler = { d, offset -> response(d, ByteArrayInputStream(full.copyOfRange(offset.toInt(), full.size)), offset) }
        SavedDownloader(store, api, 1).download(scope, target)
        assertEquals(listOf(0L, 4L), api.offsets)
        assertEquals(SavedDownloadState.COMPLETE, store.snapshot(scope, target)!!.state)
    }

    @Test fun ignoredRange200ResetsRatherThanAppending() = runBlocking {
        val full = bytes("abcdefghij")
        val api = Fixture(mapOf("one" to full))
        install(target, "v1", listOf(row("a", "one")))
        api.handler = { d, _ -> response(d, failingAfter(full, 3)) }
        SavedDownloader(store, api, 1).download(scope, target)
        api.handler = { d, _ -> response(d, ByteArrayInputStream(full)) }
        SavedDownloader(store, api, 1).download(scope, target)
        assertEquals(listOf(0L, 3L), api.offsets)
        assertArrayEquals(full, store.open(scope, target, "a")!!.use { it.input.readBytes() })
    }

    @Test fun malformedRangeWeakEtagShortOversizedAndCorruptBodiesNeverPromote() = runBlocking {
        val expected = bytes("expected-media")
        val variants: List<(SavedMediaDescriptor) -> SavedTransferResponse> = listOf(
            { d -> SavedTransferResponse(206, ByteArrayInputStream(expected), expected.size.toLong(), "bytes 1-${expected.size - 1}/${expected.size}", d.strongETag) },
            { d -> SavedTransferResponse(200, ByteArrayInputStream(expected), d.byteLength, etag = "W/${d.strongETag}") },
            { d -> response(d, ByteArrayInputStream(expected.dropLast(1).toByteArray())) },
            { d -> response(d, ByteArrayInputStream(expected + byteArrayOf(1))) },
            { d -> response(d, ByteArrayInputStream(ByteArray(expected.size))) },
            { d -> SavedTransferResponse(200, ByteArrayInputStream(expected), d.byteLength + 1, etag = d.strongETag) },
        )
        for ((i, variant) in variants.withIndex()) {
            val t = SavedTarget(SavedTargetKind.ALBUM, "case-$i")
            install(t, "v1", listOf(row("a", "track-$i")))
            val api = Fixture(mapOf("track-$i" to expected))
            api.handler = { d, _ -> variant(d) }
            SavedDownloader(store, api, 1).download(scope, t)
            assertFalse("variant $i", store.snapshot(scope, t)!!.occurrences.single().available)
            assertNull(store.open(scope, t, "a"))
        }
    }

    @Test fun updateKeepsPreviousCompleteUntilVerifiedReplacementAndOldLeaseSurvivesSwap() = runBlocking {
        val api = Fixture(mapOf("old" to bytes("old-media"), "new" to bytes("new-media")))
        install(target, "v1", listOf(row("old-occurrence", "old")))
        SavedDownloader(store, api).download(scope, target)
        val lease = store.open(scope, target, "old-occurrence")!!
        try {
            install(target, "v2", listOf(row("new-occurrence", "new"), row("absent", "missing").copy(hasAudio = false)))
            SavedDownloader(store, api).download(scope, target)
            assertEquals("v1", store.snapshot(scope, target)!!.generation)
            assertTrue(store.snapshot(scope, target)!!.updating)
            install(target, "v3", listOf(row("new-occurrence", "new")))
            SavedDownloader(store, api).download(scope, target)
            assertEquals("v3", store.snapshot(scope, target)!!.generation)
            assertFalse(store.snapshot(scope, target)!!.updating)
            assertEquals("old-media", lease.input.readBytes().toString(Charsets.UTF_8))
        } finally { lease.close() }
    }

    @Test fun sharedExactMediaUnsaveDoesNotDeleteOtherReferencesOrAnActiveReader() = runBlocking {
        val other = SavedTarget(SavedTargetKind.ARTIST, "other")
        val api = Fixture(mapOf("one" to bytes("same-exact-media")))
        install(target, "v1", listOf(row("a", "one")))
        install(other, "v1", listOf(row("b", "one")))
        SavedDownloader(store, api).download(scope, target)
        SavedDownloader(store, api).download(scope, other)
        assertEquals(1, api.offsets.size)
        val lease = store.open(scope, other, "b")!!
        try {
            store.unsave(scope, target)
            assertTrue(store.snapshot(scope, other)!!.occurrences.single().available)
            store.unsave(scope, other)
            assertTrue(store.snapshots(scope).isEmpty())
            assertEquals("same-exact-media", lease.input.readBytes().toString(Charsets.UTF_8))
        } finally { lease.close() }
        assertFalse(File(root, "representations").walkTopDown().any { it.isFile })
    }

    @Test fun retirementFencesReadersAndFreshSameAccountBindIsPossibleWithoutStaleCleanupABA() = runBlocking {
        val api = Fixture(mapOf("one" to bytes("media")))
        install(target, "v1", listOf(row("a", "one")))
        SavedDownloader(store, api).download(scope, target)
        val lease = store.open(scope, target, "a")!!
        try {
            store.bind(scope) // ordinary UI reattach must not fence the live reader
            assertEquals('m'.code, lease.input.read())
            val retirement = store.fenceAccess()
            assertTrue(store.snapshots(scope).isEmpty())
            expectFailure { lease.input.read() }
            store.bind(scope) // explicit successful reauthentication gets a fresh opaque incarnation
            store.requestSave(scope, target, "Successor")
            store.retire(retirement) // delayed old cleanup cannot retire this successor
            assertEquals("Successor", store.snapshot(scope, target)!!.title)
        } finally { lease.close() }
    }

    @Test fun fencedDormantIncarnationCannotBeReusedAcrossAnotherScopeBeforeDelayedRetirement() {
        store.requestSave(scope, target, "old A")
        val oldFence = store.fenceAccess()
        store.bind(SavedScope(scope.origin, "account-b"))
        store.bind(scope)
        assertTrue(store.snapshots(scope).isEmpty())
        store.requestSave(scope, target, "new A")
        store.retire(oldFence)
        assertEquals("new A", store.snapshot(scope, target)!!.title)
    }

    @Test fun originAccountAndLocalEligibilityAreIndependentFences() = runBlocking {
        install(target, "v1", emptyList())
        assertTrue(store.snapshots(SavedScope(scope.origin, "other")).isEmpty())
        assertTrue(store.snapshots(SavedScope("https://other.invalid", scope.accountId)).isEmpty())
        eligible = false
        assertTrue(store.snapshots(scope).isEmpty())
        eligible = true
        assertEquals(1, store.snapshots(scope).size)
    }

    @Test fun unavailable404AndStaleWorkCompletionCannotAffectAuthoritativeSuccessor() = runBlocking {
        install(target, "v1", listOf(row("a", "one")))
        val unavailable = object : SavedDownloadTransport {
            override suspend fun prepare(scope: SavedScope, trackId: String): SavedMediaDescriptor = throw SavedTransportException(404)
            override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long, consume: (SavedTransferResponse) -> Unit) = error("not reached")
        }
        SavedDownloader(store, unavailable).download(scope, target)
        assertEquals("unavailable", store.snapshot(scope, target)!!.failure)
        val old = store.beginWork(scope, target)!!
        val fence = store.fenceAccess()
        store.bind(scope)
        store.requestSave(scope, target, "new-login")
        store.endWork(old, null)
        store.retire(fence)
        assertEquals("new-login", store.snapshot(scope, target)!!.title)
    }

    @Test fun raw401StopsDownloadWithoutRetiringHealthyRetainedNamespace() = runBlocking {
        install(target, "v1", listOf(row("old", "one")))
        SavedDownloader(store, Fixture(mapOf("one" to bytes("retained-media")))).download(scope, target)
        install(target, "v2", listOf(row("new", "request"), row("later", "later")))
        val prepared = mutableListOf<String>()
        val unauthorized = object : SavedDownloadTransport {
            override suspend fun prepare(scope: SavedScope, trackId: String): SavedMediaDescriptor {
                prepared.add(trackId)
                throw SavedTransportException(401)
            }
            override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long, consume: (SavedTransferResponse) -> Unit) = error("not reached")
        }
        SavedDownloader(store, unauthorized).download(scope, target)
        assertEquals(listOf("request"), prepared)
        val value = store.snapshot(scope, target)!!
        assertEquals("v1", value.generation)
        assertEquals("auth", value.failure)
        assertTrue(value.updating)
        assertEquals("retained-media", store.open(scope, target, "old")!!.use { it.input.readBytes().toString(Charsets.UTF_8) })
        val other = SavedTarget(SavedTargetKind.ALBUM, "healthy-namespace")
        store.requestSave(scope, other, "Still eligible")
        assertNotNull(store.snapshot(scope, other))
    }

    @Test fun failedOrCancelledWorkNeverPublishesEvenAnAlreadyVerifiedReplacement() = runBlocking {
        install(target, "v1", listOf(row("old", "one")))
        SavedDownloader(store, Fixture(mapOf("one" to bytes("same-media")))).download(scope, target)
        val descriptor = store.snapshot(scope, target)!!.occurrences.single().media!!
        for ((index, failure) in listOf("cancelled", "auth", "storage", null).withIndex()) {
            install(target, "replacement-$index", listOf(row("new", "one")))
            val work = store.beginWork(scope, target)!!
            store.bindDescriptor(work, descriptor)
            assertTrue(store.isVerified(work, descriptor))
            store.endWork(work, failure, canPublish = { failure != null })
            val value = store.snapshot(scope, target)!!
            assertEquals("v1", value.generation)
            assertTrue(value.updating)
            assertEquals(failure ?: "cancelled", value.failure)
        }
    }

    @Test fun jobCancellationDuringCompletePartialHashPreventsPromotionWithoutStoreFence() = runBlocking {
        store.close()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var permitPromotion = false
        var blockHash = false
        var promotionAttempts = 0
        val blocking = object : SavedRepresentationBackend by media {
            override fun promote(namespace: String, key: String) {
                promotionAttempts++
                if (!permitPromotion) throw IOException("Synthetic promotion failure leaves a complete partial")
                media.promote(namespace, key)
            }
            override fun openPartial(namespace: String, key: String): InputStream {
                val input = media.openPartial(namespace, key)
                if (!blockHash) return input
                return object : java.io.FilterInputStream(input) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        entered.countDown()
                        assertTrue(release.await(2, TimeUnit.SECONDS))
                        return super.read(b, off, len)
                    }
                }
            }
        }
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, blocking)
        store.bind(scope)
        install(target, "v1", listOf(row("a", "one")))
        val api = Fixture(mapOf("one" to bytes("full-partial-media")))
        SavedDownloader(store, api).download(scope, target)
        assertEquals(1, promotionAttempts)
        permitPromotion = true
        blockHash = true
        val work = async(Dispatchers.IO) { SavedDownloader(store, api).download(scope, target) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        work.cancel() // no unsave, offline transition, auth event or store fence
        assertFalse(work.isCompleted)
        release.countDown()
        withTimeout(2_000) { work.join() }
        assertEquals(1, promotionAttempts)
        assertEquals(1, api.offsets.size)
        assertFalse(store.snapshot(scope, target)!!.occurrences.single().available)
        assertEquals("cancelled", store.snapshot(scope, target)!!.failure)
    }

    @Test fun retryAdmissionIsBoundedAndRetryAfterDefersWithoutSleeping() = runBlocking {
        val api = Fixture(mapOf("one" to bytes("media")))
        install(target, "v1", listOf(row("a", "one")))
        api.handler = { _, _ -> SavedTransferResponse(503) }
        SavedDownloader(store, api, 3).download(scope, target)
        assertEquals(3, api.offsets.size)
        api.offsets.clear()
        api.handler = { _, _ -> SavedTransferResponse(429, retryAfterMillis = 60_000) }
        SavedDownloader(store, api, 3).download(scope, target)
        assertEquals(1, api.offsets.size)
        assertEquals("retry_later", store.snapshot(scope, target)!!.failure)
    }

    @Test fun offlineCancelsPendingPrepareAndNeverStartsATransfer() = runBlocking {
        install(target, "v1", listOf(row("a", "one")))
        val entered = CountDownLatch(1)
        val api = object : SavedDownloadTransport {
            override suspend fun prepare(scope: SavedScope, trackId: String): SavedMediaDescriptor {
                entered.countDown()
                awaitCancellation()
            }
            override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long, consume: (SavedTransferResponse) -> Unit) = error("not reached")
        }
        val work = async(Dispatchers.IO) { SavedDownloader(store, api).download(scope, target) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        store.setNetworkAllowed(false)
        withTimeout(2_000) { work.join() }
        assertTrue(work.isCancelled)
        assertFalse(store.snapshot(scope, target)!!.occurrences.single().available)
        store.setNetworkAllowed(true)
    }

    @Test fun unsaveClosesABlockedBodyAndCannotPromoteAfterCancellation() = runBlocking {
        val full = bytes("media")
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int { entered.countDown(); released.await(2, TimeUnit.SECONDS); throw IOException("closed") }
            override fun read(b: ByteArray, off: Int, len: Int): Int = read()
            override fun close() { released.countDown() }
        }
        val api = Fixture(mapOf("one" to full))
        api.handler = { d, _ -> response(d, stream) }
        install(target, "v1", listOf(row("a", "one")))
        val work = async(Dispatchers.IO) { SavedDownloader(store, api).download(scope, target) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        store.unsave(scope, target)
        withTimeout(2_000) { work.join() }
        assertEquals(0L, released.count)
        assertNull(store.snapshot(scope, target))
        assertFalse(File(root, "representations").walkTopDown().any { it.isFile })
    }

    @Test fun initialMissingManifestReplacesPendingIntentInsteadOfHidingRows() {
        store.requestSave(scope, target, "Pending")
        install(target, "missing-generation", listOf(row("gone", "missing").copy(hasAudio = false)))
        val value = store.snapshot(scope, target)!!
        assertEquals("missing-generation", value.generation)
        assertEquals(listOf("gone"), value.occurrences.map { it.occurrence.occurrenceId })
        assertEquals(SavedDownloadState.PARTIAL, value.state)
        assertFalse(value.updating)
    }

    @Test fun coldRetireResolvesPersistedScopeWithoutReopeningItsBytes() = runBlocking {
        val api = Fixture(mapOf("one" to bytes("retire-on-cold-start")))
        install(target, "v1", listOf(row("a", "one")))
        SavedDownloader(store, api).download(scope, target)
        store.close()
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, media)
        store.retire(scope) // no bind in this process
        store.bind(scope)
        assertTrue(store.snapshots(scope).isEmpty())
        assertFalse(File(root, "representations").walkTopDown().any { it.isFile })
    }

    @Test fun failedRetiredCleanupStaysCountedAndDetachedIncarnationIsRetriedAfterRestart() = runBlocking {
        val api = Fixture(mapOf("one" to bytes("failed-delete-owned-media")))
        install(target, "v1", listOf(row("a", "one")))
        SavedDownloader(store, api).download(scope, target)
        store.close()
        var fail = true
        val failing = object : SavedRepresentationBackend by media {
            override fun remove(namespace: String, key: String) {
                if (fail) throw IOException("Synthetic deletion failure")
                media.remove(namespace, key)
            }
        }
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, failing)
        store.bind(scope)
        val fence = store.fenceAccess()
        store.retire(fence)
        val retained = media.ownedBytes()
        assertTrue(retained > 0)
        assertTrue(store.ownedBytes() >= retained)
        assertTrue(store.snapshots(scope).isEmpty())
        store.bind(scope)
        store.requestSave(scope, target, "fresh incarnation")
        store.close()
        fail = false
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, failing)
        store.bind(scope)
        assertEquals("fresh incarnation", store.snapshot(scope, target)!!.title)
        assertEquals(0L, media.ownedBytes())
    }

    @Test fun insufficientSpaceDefersTransferWithoutEvictingPreviousCompleteGeneration() = runBlocking {
        store.close()
        var available = Long.MAX_VALUE
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, media, availableBytes = { available })
        store.bind(scope)
        val api = Fixture(mapOf("old" to bytes("retained"), "new" to bytes("replacement")))
        install(target, "v1", listOf(row("a", "old")))
        SavedDownloader(store, api).download(scope, target)
        install(target, "v2", listOf(row("b", "new")))
        val calls = api.offsets.size
        available = 16 * 1024L // enough for error/index metadata, not reserved full media + metadata
        SavedDownloader(store, api).download(scope, target)
        val value = store.snapshot(scope, target)!!
        assertEquals("v1", value.generation)
        assertEquals(SavedDownloadState.COMPLETE, value.state)
        assertEquals("storage", value.failure)
        assertTrue(value.updating)
        assertEquals(calls, api.offsets.size)
        available = Long.MAX_VALUE
        SavedDownloader(store, api).download(scope, target)
        assertEquals("v2", store.snapshot(scope, target)!!.generation)
    }

    @Test fun reservationOverflowIsStorageFailureAndDoesNotStartTransfer() = runBlocking {
        install(target, "v1", listOf(row("a", "one")))
        var transfers = 0
        val hash = "0".repeat(64)
        val api = object : SavedDownloadTransport {
            override suspend fun prepare(scope: SavedScope, trackId: String) =
                SavedMediaDescriptor(trackId, "sha256:$hash", hash, Long.MAX_VALUE, "\"sha256:$hash\"")
            override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long, consume: (SavedTransferResponse) -> Unit) {
                transfers++
                error("Overflowed reservation must not admit transfer")
            }
        }
        SavedDownloader(store, api).download(scope, target)
        assertEquals(0, transfers)
        assertEquals("storage", store.snapshot(scope, target)!!.failure)
    }

    @Test fun diskOpenWriteSyncAndPromotionFailuresDoNotRetryNetworkOrBecomePlayable() = runBlocking {
        store.close()
        var phase = "open"
        val failing = object : SavedRepresentationBackend by media {
            override fun writePartial(namespace: String, key: String, offset: Long): java.io.OutputStream {
                if (phase == "open") throw IOException("Synthetic disk full")
                val output = media.writePartial(namespace, key, offset)
                return object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        if (phase == "write") throw IOException("Synthetic quota failure")
                        output.write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (phase == "write") throw IOException("Synthetic quota failure")
                        output.write(b, off, len)
                    }
                    override fun close() {
                        output.close()
                        if (phase == "sync") throw IOException("Synthetic sync failure")
                    }
                }
            }
            override fun promote(namespace: String, key: String) {
                if (phase == "promote") throw IOException("Synthetic promotion failure")
                media.promote(namespace, key)
            }
        }
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, failing)
        store.bind(scope)
        for (value in listOf("open", "write", "sync", "promote")) {
            phase = value
            val t = SavedTarget(SavedTargetKind.ALBUM, "disk-$phase")
            install(t, "v1", listOf(row("a", phase)))
            val api = Fixture(mapOf(phase to bytes("media-$phase")))
            SavedDownloader(store, api, 3).download(scope, t)
            assertEquals("phase $phase", 1, api.offsets.size)
            assertEquals("storage", store.snapshot(scope, t)!!.failure)
            assertNull(store.open(scope, t, "a"))
        }
    }

    @Test fun storageFailureStopsTheWholeAdmittedDownloadBeforePreparingLaterTracks() = runBlocking {
        store.close()
        val failing = object : SavedRepresentationBackend by media {
            override fun writePartial(namespace: String, key: String, offset: Long): java.io.OutputStream =
                throw IOException("Synthetic disk full")
        }
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, failing)
        store.bind(scope)
        install(target, "v1", listOf(row("a", "first"), row("b", "second")))
        val api = Fixture(mapOf("first" to bytes("first"), "second" to bytes("second")))
        SavedDownloader(store, api, 3).download(scope, target)
        assertEquals(listOf("first"), api.prepared)
        assertEquals(1, api.offsets.size)
        assertEquals("storage", store.snapshot(scope, target)!!.failure)
        assertTrue(store.snapshot(scope, target)!!.occurrences.none { it.available })
    }

    @Test fun retirementWithZeroAdmittedSpacePersistsFenceWithoutMetadataReservation() = runBlocking {
        store.close()
        var available = Long.MAX_VALUE
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, media, availableBytes = { available })
        store.bind(scope)
        install(target, "v1", listOf(row("a", "one")))
        SavedDownloader(store, Fixture(mapOf("one" to bytes("private-media")))).download(scope, target)
        available = 0
        store.retire(scope)
        assertTrue(File(root, "metadata").walkTopDown().any { it.isFile && it.name == "retired" })
        assertFalse(File(root, "metadata").listFiles()!!.any { it.name.endsWith(".current") })
        store.abandonWithoutCleanupForTest()
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, media)
        store.bind(scope)
        assertTrue(store.snapshots(scope).isEmpty())
        assertEquals(0L, media.ownedBytes())
    }

    @Test fun failedRetirementMarkerStillInvalidatesPointerAndRecoversOwnedOrphanWithoutOrderlyClose() = runBlocking {
        store.close()
        var available = Long.MAX_VALUE
        var failRemoval = true
        var removals = 0
        val failing = object : SavedRepresentationBackend by media {
            override fun remove(namespace: String, key: String) {
                removals++
                if (failRemoval) throw IOException("Synthetic cleanup failure")
                media.remove(namespace, key)
            }
        }
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, failing, availableBytes = { available })
        store.bind(scope)
        install(target, "v1", listOf(row("a", "one")))
        SavedDownloader(store, Fixture(mapOf("one" to bytes("retired-private-media")))).download(scope, target)
        var markerAttempts = 0
        store.retirementMarkerWriter = { markerAttempts++; throw IOException("Synthetic marker persistence failure") }
        available = 0
        expectFailure { store.retire(scope) }
        assertEquals(1, markerAttempts) // not rejected by discretionary metadata-space admission
        assertTrue(removals > 0) // cleanup executes despite marker-write failure
        assertTrue(media.ownedBytes() > 0)
        assertTrue(store.ownedBytes() >= media.ownedBytes())
        assertTrue(store.snapshots(scope).isEmpty())
        assertFalse(File(root, "metadata").listFiles()!!.any { it.name.endsWith(".current") })
        assertFalse(File(root, "metadata").walkTopDown().any { it.name == "retired" })
        store.abandonWithoutCleanupForTest() // no close()/cleanup may conceal the restart defect
        val attemptedBeforeRestart = removals
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, failing, availableBytes = { available })
        assertTrue(removals > attemptedBeforeRestart) // ownership proof survives the failed marker
        available = Long.MAX_VALUE
        store.bind(scope)
        assertTrue(store.snapshots(scope).isEmpty()) // a fresh incarnation, never the old rows/media
        assertTrue(store.ownedBytes() >= media.ownedBytes())
        failRemoval = false
        store.abandonWithoutCleanupForTest()
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, failing)
        store.bind(scope)
        assertTrue(store.snapshots(scope).isEmpty())
        assertEquals(0L, media.ownedBytes())
    }

    @Test fun stagingSpaceFailurePreservesPreviousManifestAndRequiresExplicitRetry() {
        store.close()
        var available = Long.MAX_VALUE
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, media, availableBytes = { available })
        store.bind(scope)
        install(target, "v1", emptyList())
        val stage = store.beginManifest(scope, target, "Replacement", "v2", 1)
        available = 0
        expectFailure { store.appendManifest(stage, 0, listOf(row("a", "one")), true) }
        assertEquals("v1", store.snapshot(scope, target)!!.generation)
        available = Long.MAX_VALUE
        store.cancel(scope, target)
    }

    @Test fun fullHashRunsOutsideMonitorAndImmediateFenceCannotWaitForIt() = runBlocking {
        val api = Fixture(mapOf("one" to bytes("hash-fixture")))
        install(target, "v1", listOf(row("a", "one")))
        SavedDownloader(store, api).download(scope, target)
        store.close()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : SavedRepresentationBackend by media {
            override fun open(namespace: String, key: String): InputStream {
                val input = media.open(namespace, key)
                return object : java.io.FilterInputStream(input) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        entered.countDown()
                        assertTrue(release.await(2, TimeUnit.SECONDS))
                        return super.read(b, off, len)
                    }
                }
            }
        }
        store = SavedLibraryStore(root, SavedLocalEligibility { eligible }, blocking)
        store.bind(scope)
        val snapshot = async(Dispatchers.IO) { store.snapshots(scope) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val fence = store.fenceAccess() // would deadlock here if hashing held the mutation monitor
        release.countDown()
        assertTrue(withTimeout(2_000) { snapshot.await() }.isEmpty())
        store.retire(fence)
    }

    @Test fun invalidPageIdentityOffsetAndNonterminalTotalsDoNotInstall() {
        val stage = store.beginManifest(scope, target, "Title", "v1", 2)
        store.appendManifest(stage, 0, listOf(row("same", "one")), false)
        expectFailure { store.appendManifest(stage, 1, listOf(row("same", "one")), true) }
        expectFailure { store.appendManifest(stage, 0, listOf(row("other", "one")), true) }
        expectFailure { store.appendManifest(stage, 1, emptyList(), false) }
        assertNull(store.snapshot(scope, target))
        store.cancel(scope, target)
    }

    private fun install(target: SavedTarget, generation: String, items: List<SavedOccurrence>, pageSize: Int = 2) {
        val stage = store.beginManifest(scope, target, "Synthetic title", generation, items.size)
        if (items.isEmpty()) store.appendManifest(stage, 0, emptyList(), true)
        else items.chunked(pageSize).forEachIndexed { index, page ->
            val offset = index * pageSize
            store.appendManifest(stage, offset, page, offset + page.size == items.size)
        }
        store.finishManifest(stage)
    }
    private fun row(id: String, track: String) = SavedOccurrence(id, track, "Synthetic $track")
    private fun bytes(value: String) = value.toByteArray()
    private fun response(d: SavedMediaDescriptor, body: InputStream, offset: Long = 0): SavedTransferResponse =
        SavedTransferResponse(if (offset == 0L) 200 else 206, body, d.byteLength - offset,
            if (offset == 0L) null else "bytes $offset-${d.byteLength - 1}/${d.byteLength}", d.strongETag)
    private fun failingAfter(bytes: ByteArray, count: Int): InputStream = object : ByteArrayInputStream(bytes, 0, count) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (available() == 0) throw IOException("Synthetic interruption")
            return super.read(b, off, len)
        }
    }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } catch (_: IOException) { }
    }
    private inner class Fixture(private val media: Map<String, ByteArray>) : SavedDownloadTransport {
        val prepared = mutableListOf<String>()
        val offsets = mutableListOf<Long>()
        var handler: ((SavedMediaDescriptor, Long) -> SavedTransferResponse)? = null
        override suspend fun prepare(scope: SavedScope, trackId: String): SavedMediaDescriptor {
            prepared.add(trackId)
            val body = media[trackId] ?: throw SavedTransportException(404)
            val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            return SavedMediaDescriptor(trackId, "sha256:$hash", hash, body.size.toLong(), "\"sha256:$hash\"")
        }
        override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long, consume: (SavedTransferResponse) -> Unit) {
            offsets.add(offset)
            withContext(Dispatchers.IO) {
                val result = handler?.invoke(descriptor, offset) ?: response(descriptor,
                    ByteArrayInputStream(media.getValue(descriptor.trackId).copyOfRange(offset.toInt(), descriptor.byteLength.toInt())), offset)
                result.use(consume)
            }
        }
    }
}
