package dev.spofify.mobile

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.core.saved.SavedDownloadTransport
import dev.spofify.mobile.core.saved.SavedDownloader
import dev.spofify.mobile.core.saved.SavedLibraryStore
import dev.spofify.mobile.core.saved.SavedLocalEligibility
import dev.spofify.mobile.core.saved.SavedMediaDescriptor
import dev.spofify.mobile.core.saved.SavedOccurrence
import dev.spofify.mobile.core.saved.SavedScope
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind
import dev.spofify.mobile.core.saved.SavedTransferResponse
import dev.spofify.mobile.core.saved.SavedTransportException
import dev.spofify.mobile.feature.playlists.SavedDownloadsOwner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Source-only Robolectric tests (not run for this change): real owner/access/store and a trusted
 * local-policy seam. No Session, validated live identity, HTTP server, network transport or Graph
 * fixture is created. Blocking source opens/reads/closes run on IO, as they do in Media3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SavedOfflineLibraryAdapterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = SavedScope("https://saved.example", "account")
    private val target = SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, "playlist")
    private val sibling = SavedTarget(SavedTargetKind.ALBUM, "album")
    private val bytes = "abcdef".toByteArray()
    private val rows = listOf(
        SavedOccurrence("one", "repeat", "First"),
        SavedOccurrence("missing", "unavailable", "Not downloaded"),
        SavedOccurrence("two", "repeat", "Second"),
        SavedOccurrence("metadata", "silent", "Metadata only", hasAudio = false),
    )

    private suspend fun seed(testDir: File) = withContext(Dispatchers.IO) {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val media = SavedMediaDescriptor("repeat", "sha256:$sha", sha, bytes.size.toLong(), "\"sha256:$sha\"")
        SavedLibraryStore(testDir, SavedLocalEligibility { it == scope }).use { store ->
            store.bind(scope)
            val transport = object : SavedDownloadTransport {
                override suspend fun prepare(scope: SavedScope, trackId: String): SavedMediaDescriptor {
                    if (trackId != media.trackId) throw SavedTransportException(404)
                    return media
                }
                override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long,
                    consume: (SavedTransferResponse) -> Unit) {
                    assertEquals(media, descriptor)
                    assertEquals(0L, offset)
                    SavedTransferResponse(200, ByteArrayInputStream(bytes), bytes.size.toLong(),
                        etag = media.strongETag).use(consume)
                }
            }
            for ((savedTarget, occurrences) in listOf(target to rows,
                sibling to listOf(SavedOccurrence("other-entry", "repeat", "Same track elsewhere")))) {
                val stage = store.beginManifest(scope, savedTarget, savedTarget.id, "generation-${savedTarget.id}", occurrences.size)
                store.appendManifest(stage, 0, occurrences, terminal = true)
                store.finishManifest(stage)
                SavedDownloader(store, transport).download(scope, savedTarget)
            }
            assertEquals(listOf("one", "two"), store.snapshot(scope, target)!!.occurrences
                .filter { it.available }.map { it.occurrence.occurrenceId })
        }
    }

    private inner class Fixture(val testDir: File, beforeRoot: () -> Unit, bind: Boolean,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
        val eligibility = OfflineEligibility(scope.origin, scope.accountId, "listener", 0L)
        val selected = AtomicReference(eligibility)
        val readEpoch = AtomicLong()
        val source = SpofifyClient(ServerAddress.parse(scope.origin), object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        })
        val owner = SavedDownloadsOwner(ioDispatcher) { beforeRoot(); testDir }
        init {
            owner.configure(source)
            // Explicit trusted offline-owner delegation: bytes/account strings do not attach it.
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            if (bind) assertTrue(owner.bindLocal(source, scope))
            assertNull(source.validatedSessionIdentity())
        }
        fun adapter(): SavedOfflineLibraryAdapter {
            val captured = selected.get()
            val epoch = readEpoch.get()
            return SavedOfflineLibraryAdapter(owner, captured) {
                selected.get() === captured && readEpoch.get() == epoch
            }
        }
        suspend fun close() {
            try { owner.close() } finally {
                source.httpClient.connectionPool.evictAll()
                source.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    private suspend fun fixture(beforeRoot: () -> Unit = {}, bind: Boolean = true,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO): Fixture {
        val testDir = temporary.newFolder("saved-${System.nanoTime()}")
        seed(testDir)
        return Fixture(testDir, beforeRoot, bind, ioDispatcher)
    }
    private suspend fun SavedOfflineLibraryAdapter.read() = collections(scope.origin, scope.accountId)
    private fun spec() = DataSpec.Builder().setUri(Uri.parse("spofify-saved://test/opaque")).build()
    private fun expectIo(action: () -> Unit) {
        try { action(); fail("Expected unavailable saved media") } catch (_: IOException) { }
    }

    @Test fun transientInitializationFailureRequiresExplicitRetryAndThenRecovers() = runBlocking(Dispatchers.IO) {
        val unavailable = java.util.concurrent.atomic.AtomicBoolean(true)
        val roots = AtomicLong()
        val f = fixture(beforeRoot = {
            roots.incrementAndGet()
            if (unavailable.get()) throw IOException("Synthetic storage unavailable")
        })
        val adapter = f.adapter()
        try {
            try { adapter.read(); fail("Storage failure must be real") }
            catch (failure: IOException) { assertEquals("Synthetic storage unavailable", failure.message) }
            val attempts = roots.get()
            unavailable.set(false)
            try { adapter.read(); fail("A revision/read must not automatically retry initialization") }
            catch (_: IOException) { }
            assertEquals(attempts, roots.get())
            adapter.retryFailedPreparation()
            assertTrue(withTimeout(5_000) { adapter.read() }.any { it.target.id == target.id })
            val prepared = roots.get()
            adapter.retryFailedPreparation() // Successful access/accepted streams are not reset.
            adapter.read()
            assertEquals(prepared, roots.get())
        } finally { adapter.retire(); f.close() }
    }

    @Test fun cancelledFirstCollectorDoesNotRestartOrderedAdmission() = runBlocking(Dispatchers.IO) {
        val rootEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accessEntered = CountDownLatch(1)
        val checks = AtomicLong()
        val f = fixture(beforeRoot = { rootEntered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        val captured = f.selected.get()
        val adapter = SavedOfflineLibraryAdapter(f.owner, captured) {
            if (checks.incrementAndGet() >= 2L) accessEntered.countDown()
            f.selected.get() === captured
        }
        val first = async { adapter.read() }
        try {
            assertTrue(rootEntered.await(5, TimeUnit.SECONDS))
            assertTrue(accessEntered.await(5, TimeUnit.SECONDS))
            first.cancelAndJoin() // The UI collector dies, but incarnation initialization survives.
            release.countDown()
            assertTrue(withTimeout(5_000) { adapter.read() }.any { it.target.id == target.id })
            assertTrue(adapter.read().any { it.target.id == target.id })
        } finally {
            release.countDown(); first.cancelAndJoin(); adapter.retire(); f.close()
        }
    }

    @Test fun repeatedRevisionDrivenReadsReuseAdmissionWithoutRepublishingButStillSeeUnsave() = runBlocking {
        val f = fixture(ioDispatcher = Dispatchers.Unconfined)
        try {
            val adapter = f.adapter()
            assertTrue(adapter.read().any { it.target.id == target.id })
            val revision = f.owner.reader.revision.value
            repeat(4) {
                assertTrue(adapter.read().any { it.target.id == target.id })
                assertEquals("A read must not cause its own revision/reload", revision, f.owner.reader.revision.value)
            }
            f.owner.unsave(target)
            assertTrue(f.owner.reader.revision.value > revision)
            assertFalse(adapter.read().any { it.target.id == target.id })
            adapter.retire()
        } finally { f.close() }
    }

    @Test fun realOwnerProjectsAllOrderedOccurrencesAndOnlyAvailableExactReferences() = runBlocking(Dispatchers.IO) {
        val f = fixture()
        try {
            val adapter = f.adapter()
            assertSame(f.owner.reader.revision, adapter.revision)
            val collections = adapter.read()
            assertEquals(setOf(target.id, sibling.id), collections.map { it.target.id }.toSet())
            val saved = collections.single { it.target.id == target.id }
            assertEquals(listOf("one", "missing", "two", "metadata"), saved.rows.map { it.entryId })
            assertEquals(listOf("repeat", "unavailable", "repeat", "silent"), saved.rows.map { it.id })
            assertEquals(setOf("one", "two"), saved.availableEntryIds)
            assertFalse(saved.complete)
            assertTrue(collections.single { it.target.id == sibling.id }.playableInFull)
            val first = saved.mediaReferences.getValue("one")
            val second = saved.mediaReferences.getValue("two")
            assertNotSame(first, second)
            assertSame(adapter, first.owner)
            assertEquals(scope.origin, first.origin)
            assertEquals(scope.accountId, first.accountId)
            // Deliberately discard unopened factories: orderly real store close below must find
            // no reader leases. References themselves must not reserve or open retained media.
            assertNull(requireNotNull(first.openExactSnapshotOccurrence()).uri)
            assertNull(requireNotNull(second.openExactSnapshotOccurrence()).uri)
        } finally { f.close() }
    }

    @Test fun wrongScopeAndStaleCapturedReadReturnEmptyButCurrentUnavailableAccessThrows() = runBlocking(Dispatchers.IO) {
        val f = fixture(bind = false)
        try {
            val adapter = f.adapter()
            assertTrue(adapter.collections("https://other.example", scope.accountId).isEmpty())
            assertTrue(adapter.collections(scope.origin, "another-account").isEmpty())
            try {
                adapter.read()
                fail("An eligible but unattached owner is unavailable, not an empty saved library")
            } catch (error: IOException) { assertEquals("Saved library is unavailable", error.message) }
            f.readEpoch.incrementAndGet()
            assertTrue(adapter.read().isEmpty())
        } finally { f.close() }
    }

    @Test fun immediateReadWaitsForOrderedColdBindRatherThanReturningFalseEmpty() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = fixture(beforeRoot = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            assertTrue(withContext(Dispatchers.Default) { entered.await(5, TimeUnit.SECONDS) })
            val pending = async(Dispatchers.Default) { f.adapter().read() }
            assertNull(withTimeoutOrNull(100) { pending.await() })
            release.countDown()
            assertEquals(4, withTimeout(5_000) { pending.await() }.single { it.target.id == target.id }.rows.size)
        } finally { release.countDown(); f.close() }
    }

    private suspend fun staleWait(retire: Boolean) = kotlinx.coroutines.coroutineScope {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = fixture(beforeRoot = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val adapter = f.adapter()
        val pending = async(Dispatchers.Default) { adapter.read() }
        try {
            assertTrue(withContext(Dispatchers.Default) { entered.await(5, TimeUnit.SECONDS) })
            assertNull(withTimeoutOrNull(100) { pending.await() })
            if (retire) adapter.retire()
            else f.selected.set(OfflineEligibility(scope.origin, scope.accountId, "listener", 0L))
            release.countDown()
            assertTrue(withTimeout(5_000) { pending.await() }.isEmpty())
            assertTrue(adapter.read().isEmpty())
            assertEquals(2, f.adapter().read().size) // Successor authority cannot revive the old read.
        } finally { release.countDown(); pending.cancelAndJoin(); f.close() }
    }

    @Test fun sameAccountSuccessorCannotBeCapturedAfterTheOldReadWaits() = runBlocking(Dispatchers.IO) { staleWait(retire = false) }

    @Test fun retirementDuringOrderedBindFencesDeliveryWithoutPurgingSuccessor() = runBlocking(Dispatchers.IO) { staleWait(retire = true) }

    @Test fun unopenedReferenceCannotRefetchSameTrackFromAnotherCollectionAfterUnsave() = runBlocking(Dispatchers.IO) {
        val f = fixture()
        try {
            val adapter = f.adapter()
            val saved = adapter.read().single { it.target.id == target.id }
            val reference = saved.mediaReferences.getValue("two")
            val unopened = requireNotNull(reference.openExactSnapshotOccurrence())
            try {
                f.owner.unsave(target)
                f.owner.ownedBytes() // Ordered maintenance barrier, not an authority refresh.
                assertNull(reference.openExactSnapshotOccurrence())
                expectIo { unopened.open(spec()) }
                val remaining = adapter.read().single()
                assertEquals(sibling.id, remaining.target.id)
                assertEquals(setOf("other-entry"), remaining.availableEntryIds)
            } finally { unopened.close() }
        } finally { f.close() }
    }

    @Test fun acceptedStreamSurvivesUnsaveButAdapterRetirementFencesHeldAccessWithoutPurging() = runBlocking(Dispatchers.IO) {
        val f = fixture()
        try {
            val adapter = f.adapter()
            val reference = adapter.read().single { it.target.id == target.id }.mediaReferences.getValue("one")
            val source = requireNotNull(reference.openExactSnapshotOccurrence())
            try {
                assertEquals(bytes.size.toLong(), source.open(spec()))
                val buffer = ByteArray(1)
                assertEquals(1, source.read(buffer, 0, 1))
                assertEquals('a'.code.toByte(), buffer[0])
                f.owner.unsave(target)
                f.owner.ownedBytes()
                assertEquals(1, source.read(buffer, 0, 1))
                assertEquals('b'.code.toByte(), buffer[0])
                adapter.retire()
                adapter.retire()
                expectIo { source.read(buffer, 0, 1) }
                assertNull(reference.openExactSnapshotOccurrence())
                assertTrue(adapter.read().isEmpty())
                assertEquals(scope, f.owner.currentScope())
                assertEquals(sibling.id, f.adapter().read().single().target.id)
            } finally { source.close() }
        } finally { f.close() }
    }

    @Test fun capturedModeReadEpochFencesAcceptedStreamAndCannotBeRefreshedInPlace() = runBlocking(Dispatchers.IO) {
        val f = fixture()
        try {
            val adapter = f.adapter()
            val reference = adapter.read().single { it.target.id == sibling.id }.mediaReferences.getValue("other-entry")
            val source = requireNotNull(reference.openExactSnapshotOccurrence())
            try {
                source.open(spec())
                f.readEpoch.incrementAndGet() // Leaving/returning to offline mode needs a NEW read guard.
                expectIo { source.read(ByteArray(1), 0, 1) }
                assertNull(reference.openExactSnapshotOccurrence())
                assertTrue(adapter.read().isEmpty())
                assertEquals(2, f.adapter().read().size)
            } finally { source.close() }
        } finally { f.close() }
    }
}
