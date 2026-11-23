package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.cache.MusicBlobStore
import dev.spofify.mobile.core.cache.MusicOwner
import dev.spofify.mobile.core.cache.SongKey
import dev.spofify.mobile.core.cache.bytes
import dev.spofify.mobile.core.cache.descriptor
import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.offline.OfflineDiagnosticStage.*
import dev.spofify.mobile.core.personal.PersonalCoverage
import dev.spofify.mobile.core.personal.PersonalTrack
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineDownloadTraceTest {
    @get:Rule val root = TemporaryFolder()
    private val owner = MusicOwner("https://synthetic.example", "synthetic-account")
    private val ref = OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, "synthetic-playlist")

    @Test fun recordsMetadataRetriesResumeSharedReuseAndDurableCommit() = runBlocking {
        val store = MusicBlobStore(root.root)
        val manifest = manifest("resumed", "shared", "new", "resumed", "new")
        store.stage(ref.id, SongKey(owner, "resumed"), "Draft", 3, { true }, { descriptor("resumed") }) { _, consume ->
            consume(bytes.inputStream())
        }
        store.fill(SongKey(owner, "shared"), "Cached", 3, emptyList(), { true }, { descriptor("shared") }) { _, consume ->
            consume(bytes.inputStream())
        }
        var requests = 0
        var preparations = 0
        val opened = mutableListOf<String>()
        val repository = OfflinePlaylistRepository(store, owner, {
            if (++requests == 1) throw OfflineException(OfflineFailure.BUSY, 503)
            manifest
        }, {
            if (++preparations == 1) throw OfflineException(OfflineFailure.TIMEOUT)
            descriptor(it)
        }, { opened += it.trackId; audio(maxRead = 1) })
        val lines = mutableListOf<String>()
        val trace = OfflineDiagnostics(lines::add).attempt(operation = 1, number = 1)
        val progress = mutableListOf<OfflinePlaylistProgress>()
        assertEquals(manifest, repository.refresh(ref.id, 3, progress::add, trace) { true })

        assertEquals(2, requests)
        assertEquals(2, preparations)
        assertEquals(listOf("new"), opened)
        assertEquals(manifest, MusicBlobStore(root.root).select(owner, ref.id))
        val records = lines.map(::fields)
        val steps = records.filter { it["event"] == "STEP" }
        assertEquals(listOf(PLAYLIST_LOCK, ATTEMPT_SLOT, ATTEMPT_ADMITTED, MANIFEST_LOCK, MANIFEST_REQUEST,
            MANIFEST_LOCK, MANIFEST_REQUEST, MANIFEST_VALIDATION, RESUME, RESUME_SCAN, RESUMED, PROGRESS,
            MEDIA_LOOKUP, MEDIA_REUSE, PROGRESS, MEDIA_LOOKUP, MEDIA_PREPARE, MEDIA_PREPARE, MEDIA_SETUP, MEDIA_OPEN,
            MEDIA_BODY, MEDIA_WRITE_WAIT, MEDIA_WRITE, MEDIA_BODY, MEDIA_VERIFY, MEDIA_PUBLISH, MEDIA_RETAIN, PROGRESS,
            COMMIT, COMMIT_INDEX, COMMIT_DISK_WAIT, COMMIT_WRITE, COMMIT_CLEANUP, COMMITTED).map { it.name },
            steps.map { it["stage"] })
        assertEquals("2" to "5", steps.single { it["stage"] == RESUMED.name }.let { it["count"] to it["total"] })
        assertEquals(listOf(OfflinePlaylistProgress(2, 5), OfflinePlaylistProgress(3, 5), OfflinePlaylistProgress(5, 5)), progress)
        assertEquals(progress.map { it.verified.toString() to it.total.toString() },
            steps.filter { it["stage"] == PROGRESS.name }.map { it["count"] to it["total"] })
        assertEquals(listOf(listOf(MANIFEST_REQUEST.name, "BUSY", "503", "250"),
            listOf(MEDIA_PREPARE.name, "TIMEOUT", null, "250")), records.filter { it["event"] == "METADATA_RETRY" }
            .map { listOf(it["stage"], it["failure"], it["http"], it["wait_ms"]) })
        assertTrue(lines.none { owner.origin in it || owner.accountId in it || ref.id in it || manifest.fence in it })
    }

    @Test fun recordsJoinThenReuseWithoutOpeningAnotherStream() = runBlocking {
        val store = MusicBlobStore(root.root)
        val manifest = manifest("shared")
        val repository = OfflinePlaylistRepository(store, owner, { manifest }, { error("Unexpected prepare") },
            { error("Unexpected open") })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val joining = CompletableDeferred<Unit>()
        val lines = mutableListOf<String>()
        val trace = OfflineDiagnostics { line ->
            lines += line
            if (fields(line)["stage"] == MEDIA_JOIN.name) joining.complete(Unit)
        }.attempt(operation = 1, number = 1)
        val filling = launch {
            store.fill(SongKey(owner, "shared"), "Cached", 3, emptyList(), { true }, { descriptor("shared") }) { _, consume ->
                entered.complete(Unit)
                release.await()
                consume(bytes.inputStream())
            }
        }
        try {
            withTimeout(5_000) {
                entered.await()
                val saving = async { repository.refresh(ref.id, 3, trace = trace) { true } }
                joining.await()
                release.complete(Unit)
                assertEquals(manifest, saving.await())
            }
        } finally { filling.cancelAndJoin() }
        assertEquals(listOf(MEDIA_LOOKUP, MEDIA_JOIN, MEDIA_LOOKUP, MEDIA_REUSE).map { it.name },
            lines.mapNotNull { fields(it)["stage"] }.filter { it.startsWith("MEDIA_") })
    }

    @Test fun commitReportsDiskWaitWhileAnAudioWriteHoldsTheLock() = runBlocking {
        val holdNextSpaceCheck = AtomicBoolean(false)
        val holding = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val store = MusicBlobStore(root.root) {
            if (holdNextSpaceCheck.compareAndSet(true, false)) {
                holding.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
            Long.MAX_VALUE
        }
        val manifest = manifest()
        val repository = OfflinePlaylistRepository(store, owner, { manifest }, { error("Unexpected prepare") },
            { error("Unexpected open") })
        val waiting = CompletableDeferred<Unit>()
        val writing = CompletableDeferred<Unit>()
        val trace = OfflineDiagnostics { line ->
            when (fields(line)["stage"]) {
                COMMIT_DISK_WAIT.name -> waiting.complete(Unit)
                COMMIT_WRITE.name -> writing.complete(Unit)
                else -> Unit
            }
        }.attempt(operation = 1, number = 1)
        val filling = launch(Dispatchers.IO) {
            store.fill(SongKey(owner, "unrelated"), "Cached", 3, emptyList(), { true }, { descriptor("unrelated") }) { _, consume ->
                // Descriptor admission is done; the next space check is inside the body write lock.
                holdNextSpaceCheck.set(true)
                consume(bytes.inputStream())
            }
        }
        try {
            withTimeout(5_000) { holding.await() }
            val saving = async { repository.refresh(ref.id, 3, trace = trace) { true } }
            withTimeout(5_000) { waiting.await() }
            assertNull(withTimeoutOrNull(200) { writing.await() })
            release.countDown()
            assertEquals(manifest, withTimeout(5_000) { saving.await() })
            assertTrue(writing.isCompleted)
        } finally {
            release.countDown()
            filling.cancelAndJoin()
        }
    }

    @Test fun unsampledMediaFailureStillIdentifiesVerificationStage() = runBlocking {
        val store = MusicBlobStore(root.root)
        val manifest = manifest(*(1..9).map { "track-$it" }.toTypedArray())
        val repository = OfflinePlaylistRepository(store, owner, { manifest }, ::descriptor, {
            audio(if (it.trackId == "track-9") bytes.reversedArray() else bytes)
        })
        val lines = mutableListOf<String>()
        val trace = OfflineDiagnostics(lines::add).attempt(operation = 1, number = 1)
        val failure = runCatching { repository.refresh(ref.id, 3, trace = trace) { true } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        trace.outcome(OfflineDiagnosticEvent.FAILED, failure as Exception)

        val records = lines.map(::fields)
        assertEquals(8, records.count { it["event"] == "STEP" && it["stage"] == MEDIA_VERIFY.name })
        assertEquals(MEDIA_VERIFY.name, records.single { it["event"] == "FAILED" }["stage"])
        assertTrue(records.none { it["stage"] == COMMIT.name || it["stage"] == COMMITTED.name })
        assertNull(repository.select(ref.id))
    }

    private fun manifest(vararg ids: String): OfflineManifest {
        val collection = OfflineCollection(ref, "Synthetic collection", OfflinePlaylistKind.MANUAL, "", 1)
        val items = ids.mapIndexed { i, id -> OfflineOccurrence("entry-$i", i.toLong(),
            PersonalTrack("track", id, "Synthetic song", true, PersonalCoverage(1, 1, 1))) }
        return OfflineManifest(collection, "synthetic-fence", items, items.size.toLong(), items.size.toLong())
    }

    private fun audio(data: ByteArray = bytes, maxRead: Int = Int.MAX_VALUE): AudioStream {
        val source = object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, maxRead))
        }
        return AudioStream(source, data.size.toLong(), permitted = { true }) {}
    }
    private fun fields(line: String) = line.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
}
