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

    @Test fun persistedIntentResumesAcrossRestartEvenWithoutAnyCompletedDownload() = runTest {
        val disk = Disk()
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        val first = disk.owner(firstScope)
        runCurrent()
        first.set("pending", true); runCurrent()
        firstScope.cancel(); runCurrent()
        val next = disk.owner(backgroundScope)
        backgroundScope.launch { next.download { id, _ -> disk.save(id) } }
        runCurrent()
        assertEquals(setOf("pending"), next.wanted.value)
        assertEquals(1, next.complete().size)
    }

    @Test fun turningOffCancelsPendingDownloadAndFencesItsCommit() = runTest {
        val disk = Disk()
        val worker = disk.owner(backgroundScope)
        var stopped = false
        var attempts = 0
        backgroundScope.launch {
            worker.download { _, current ->
                attempts++
                try { awaitCancellation() }
                finally { stopped = true; assertFalse(current()) }
            }
        }
        runCurrent()
        worker.set("pending", true); runCurrent()
        assertEquals(1, attempts)
        worker.set("pending", false); runCurrent()
        assertTrue(stopped)
        assertEquals(emptySet<String>(), disk.wanted)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, attempts)
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
