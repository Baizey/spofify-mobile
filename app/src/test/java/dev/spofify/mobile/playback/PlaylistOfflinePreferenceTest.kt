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
        fun owner(scope: CoroutineScope) = PlaylistOfflinePreference(scope, { wanted }, { wanted = it },
            { manifests.values.toList() }, { id, current -> if (current()) { removals++; manifests.remove(id) } })
    }

    @Test fun switchRemainsOnWhileAnInterruptedDownloadRetriesWithoutAnotherTap() = runTest {
        val disk = Disk()
        val worker = disk.owner(backgroundScope)
        var attempts = 0
        backgroundScope.launch {
            worker.download { id, current ->
                assertTrue(current())
                if (++attempts < 3) throw IOException("temporary")
                disk.save(id)
            }
        }
        runCurrent()
        worker.set("playlist", true); runCurrent()
        assertEquals(setOf("playlist"), disk.wanted)
        assertEquals(setOf("playlist"), worker.wanted.value)
        assertTrue(worker.complete().isEmpty())
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, attempts)
        assertTrue(worker.complete().isEmpty())
        advanceTimeBy(4_000); runCurrent()
        assertEquals(3, attempts)
        assertEquals(disk.manifests.values.toList(), worker.complete())
    }

    @Test fun bothPlaylistIntentsRelaunchAfterRestartWithoutAnyCompletedDownload() = runTest {
        val disk = Disk()
        val ids = setOf("manual", "derived")
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        val first = disk.owner(firstScope)
        val started = mutableSetOf<String>()
        firstScope.launch {
            first.download { id, _ ->
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
            next.download { id, current ->
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
            worker.download { id, current ->
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
