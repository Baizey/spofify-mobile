package dev.spofify.mobile.playback

import dev.spofify.mobile.core.offline.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistOfflinePreferenceTest {
    private class Disk {
        var wanted: Set<String>? = null
        val manifests = linkedMapOf<String, OfflineManifest>()
        var removals = 0
        fun save(id: String) {
            val ref = OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, id)
            manifests[id] = OfflineManifest(OfflineCollection(ref, id), "fence", emptyList(), 0, 0)
        }
        fun owner(scope: CoroutineScope, diagnostics: OfflineDiagnostics? = null) =
            PlaylistOfflinePreference(scope, { wanted }, { wanted = it }, { manifests.values.toList() },
                { id, current -> if (current()) { removals++; manifests.remove(id) } }, diagnostics = diagnostics)
    }

    @Test fun switchRemainsOnWhileAnInterruptedDownloadRetriesWithoutAnotherTap() = runTest {
        val disk = Disk()
        val lines = mutableListOf<String>()
        val worker = disk.owner(backgroundScope, OfflineDiagnostics(lines::add))
        val id = "private-playlist-id"
        var attempts = 0
        backgroundScope.launch {
            worker.download { playlist, current, trace ->
                assertTrue(current())
                trace?.step(OfflineDiagnosticStage.MEDIA_BODY)
                when (++attempts) {
                    1 -> throw IOException("private-cookie and https://private.example/private/path")
                    2 -> throw OfflineException(OfflineFailure.UNAVAILABLE, 503)
                    else -> disk.save(playlist)
                }
            }
        }
        runCurrent()
        worker.set(id, true); runCurrent()
        assertEquals(setOf(id), disk.wanted)
        assertEquals(setOf(id), worker.wanted.value)
        assertTrue(worker.complete().isEmpty())
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, attempts)
        assertTrue(worker.complete().isEmpty())
        advanceTimeBy(4_000); runCurrent()
        assertEquals(3, attempts)
        assertEquals(disk.manifests.values.toList(), worker.complete())

        val intent = lines.single { it.startsWith("event=WORKER_INTENT ") }.substringAfter(' ')
        val attemptsLog = lines.filter { " $intent " in it }
        assertEquals(listOf("ATTEMPT", "STEP", "FAILED", "BACKOFF", "ATTEMPT", "STEP", "FAILED", "BACKOFF",
            "ATTEMPT", "STEP", "FINISHED"), attemptsLog.map { it.substringBefore(' ').substringAfter('=') })
        assertTrue(attemptsLog.any { "attempt=1 stage=MEDIA_BODY" in it && "failure=IO" in it })
        assertTrue(attemptsLog.any { "attempt=2 stage=MEDIA_BODY" in it && "failure=UNAVAILABLE http=503" in it })
        assertEquals(listOf("2000", "4000"), attemptsLog.filter { "wait_ms=" in it }.map { it.substringAfter("wait_ms=") })
        assertFalse(lines.any { "private" in it })
    }

    @Test fun cancellingBackoffDoesNotRetryAndDiagnosticSinkCannotCancelWorker() = runTest {
        val disk = Disk()
        val lines = mutableListOf<String>()
        val worker = disk.owner(backgroundScope, OfflineDiagnostics {
            lines += it
            throw CancellationException("from diagnostic sink only")
        })
        var calls = 0
        backgroundScope.launch {
            worker.download { _, current, _ ->
                assertTrue(current())
                calls++
                throw IOException("synthetic")
            }
        }
        runCurrent()
        worker.set("wanted", true); runCurrent()
        assertEquals(1, calls)
        worker.set("wanted", false); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, calls)
        assertTrue(lines.any { it.startsWith("event=CANCELLED ") && "stage=BACKOFF" in it })
        assertTrue(lines.none { "from diagnostic sink" in it })
        assertTrue(worker.wanted.value!!.isEmpty())
    }

    @Test fun intentPublicationIsDistinguishedFromBlockedPreferenceWrite() = runTest {
        val lines = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val worker = PlaylistOfflinePreference(backgroundScope, { emptySet() }, { release.await() },
            { emptyList() }, { _, _ -> }, diagnostics = OfflineDiagnostics(lines::add))
        var calls = 0
        backgroundScope.launch { worker.download { _, _, _ -> calls++ } }
        runCurrent()
        worker.set("synthetic-id", true); runCurrent()
        fun writeEvents() = lines.filter { it.startsWith("event=INTENT_WRITE") || it.startsWith("event=INTENT_PUBLISHED") }
            .map { it.substringBefore(' ') }
        assertEquals(listOf("event=INTENT_WRITE_WAIT", "event=INTENT_WRITE"), writeEvents())
        assertEquals(emptySet<String>(), worker.wanted.value)
        assertEquals(0, calls)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("event=INTENT_WRITE_WAIT", "event=INTENT_WRITE", "event=INTENT_PUBLISHED"), writeEvents())
        assertEquals(setOf("synthetic-id"), worker.wanted.value)
        assertEquals(1, calls)
        assertTrue(lines.none { "synthetic-id" in it })
    }

    @Test fun bothPlaylistIntentsRelaunchAfterRestartWithoutAnyCompletedDownload() = runTest {
        val disk = Disk()
        val ids = setOf("manual", "derived")
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        val first = disk.owner(firstScope)
        val started = mutableSetOf<String>()
        firstScope.launch {
            first.download { id, _, _ ->
                assertTrue(started.add(id))
                awaitCancellation()
            }
        }
        runCurrent()
        first.set("manual", true)
        first.set("derived", true)
        runCurrent()
        assertEquals(ids, started)
        assertEquals(ids, disk.wanted)
        assertTrue(disk.manifests.isEmpty())
        firstScope.cancel(); runCurrent()

        val next = disk.owner(backgroundScope)
        val relaunched = mutableSetOf<String>()
        backgroundScope.launch {
            next.download { id, current, _ ->
                assertTrue(current())
                assertTrue(relaunched.add(id))
                disk.save(id)
            }
        }
        runCurrent()
        assertEquals(ids, next.wanted.value)
        assertEquals(ids, relaunched)
        assertEquals(ids, next.complete().map { it.collection.reference.id }.toSet())
    }

    @Test fun secondPlaylistCompletesWhileFirstIsSuspendedAndClearingFirstOnlyFencesIt() = runTest {
        val disk = Disk()
        val worker = disk.owner(backgroundScope)
        val attempts = mutableListOf<String>()
        var manualCurrentOnCancellation: Boolean? = null
        backgroundScope.launch {
            worker.download { id, current, _ ->
                attempts += id
                assertTrue(current())
                if (id == "manual") {
                    try { awaitCancellation() }
                    finally { manualCurrentOnCancellation = current() }
                }
                disk.save(id)
            }
        }
        runCurrent()
        worker.set("manual", true); runCurrent()
        assertEquals(listOf("manual"), attempts)
        assertTrue(worker.complete().isEmpty())

        worker.set("derived", true); runCurrent()
        assertEquals(listOf("manual", "derived"), attempts)
        assertNull(manualCurrentOnCancellation)
        assertEquals(setOf("manual", "derived"), disk.wanted)
        val derived = disk.manifests.getValue("derived")
        assertEquals(listOf(derived), worker.complete())

        worker.set("manual", false); runCurrent()
        assertEquals(false, manualCurrentOnCancellation)
        assertEquals(setOf("derived"), disk.wanted)
        assertEquals(setOf("derived"), worker.wanted.value)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(listOf("manual", "derived"), attempts)
        assertEquals(setOf("derived"), disk.manifests.keys)
        assertSame(derived, disk.manifests["derived"])
        assertEquals(listOf(derived), worker.complete())
    }

    @Test fun legacySavedPlaylistsStartOnAndOffRemovesOnlyThatPlaylistEvenWithoutNetwork() = runTest {
        val disk = Disk().apply { save("one"); save("two") }
        val worker = disk.owner(backgroundScope)
        runCurrent()
        assertEquals(setOf("one", "two"), worker.wanted.value)
        worker.set("one", false); runCurrent()
        assertEquals(setOf("two"), disk.wanted)
        assertEquals(setOf("two"), disk.manifests.keys)
        assertEquals(1, disk.removals)
        assertEquals(1, worker.complete().size)
        // Simulate a process stopping before it could finish local removal.
        disk.save("one")
        val restarted = disk.owner(backgroundScope)
        runCurrent()
        assertEquals(setOf("two"), restarted.wanted.value)
        assertEquals(setOf("two"), disk.manifests.keys)
        assertEquals(1, restarted.complete().size)
    }
}
