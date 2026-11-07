package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.DerivedEvaluationEntry
import dev.spofify.mobile.core.PersonalEntriesPage
import dev.spofify.mobile.core.PersonalPlaylistEntry
import dev.spofify.mobile.core.LibraryPlaybackContext
import dev.spofify.mobile.core.PlaybackQueue
import dev.spofify.mobile.playback.PlaybackRequests
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsRouteTest {
    private data class Delivery(val queue: PlaybackQueue, val trackId: String?, val entryId: String?)

    /** The route's suspended fetch -> synchronous host callback/error -> busy cleanup boundary. */
    private class Preparation(
        val generation: PlaylistRequestGeneration = PlaylistRequestGeneration(),
        private val trackId: String? = "same-song",
        private val entryId: String? = "second",
    ) {
        private val request = PlaylistPlaybackRequest(generation) // Before launch, like the Play tap.
        val response = CompletableDeferred<PlaybackQueue>()
        val deliveries = mutableListOf<Delivery>()
        var failures = 0
        var stale = false
        var busy = true
        var started = false

        private fun onPlayQueue(queue: PlaybackQueue, trackId: String?, entryId: String?) {
            // The real host can treat this callback as a NEW playback intent. It must not run late.
            deliveries += Delivery(queue, trackId, entryId)
        }

        fun start(scope: CoroutineScope) = scope.launch {
            started = true
            try {
                val queue = response.await()
                request.deliver { onPlayQueue(queue, trackId, entryId) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { request.deliver { failures++; stale = true } }
            finally { request.finish { busy = false } }
        }
    }

    private fun queue(derived: Boolean = false) = PlaybackQueue(
        items = listOf(
            LibraryItem("track", "same-song", "Song", hasAudio = true, entryId = "first"),
            LibraryItem("track", "same-song", "Song", hasAudio = true, entryId = "second"),
        ),
        total = 2,
        context = LibraryPlaybackContext(if (derived) "derived" else "playlist", "collection", "Collection"),
        revision = 7,
    )

    @Test fun pauseEpochDropsDelayedManualCallbackInsteadOfCreatingANewIntent() = runTest {
        val preparation = Preparation()
        val job = preparation.start(this)
        runCurrent()
        assertTrue(preparation.started)
        PlaybackRequests.invalidate() // The same global cancellation emitted by Pause.
        preparation.response.complete(queue())
        job.join()
        assertTrue(preparation.deliveries.isEmpty())
        assertEquals(0, preparation.failures)
        assertFalse(preparation.stale)
        assertFalse(preparation.busy)
    }

    @Test fun handoffEpochDropsDelayedDerivedOccurrenceCallbackInsteadOfCreatingANewIntent() = runTest {
        val preparation = Preparation(trackId = null)
        val job = preparation.start(this)
        runCurrent()
        PlaybackRequests.invalidate() // Output revocation/handoff uses this same global epoch.
        preparation.response.complete(queue(derived = true))
        job.join()
        assertTrue(preparation.deliveries.isEmpty())
        assertEquals(0, preparation.failures)
        assertFalse(preparation.busy)
    }

    @Test fun epochIsCapturedAtTheTapNotWhenTheFetchCoroutineEventuallyStarts() = runTest {
        val preparation = Preparation()
        val job = preparation.start(this)
        assertFalse(preparation.started)
        PlaybackRequests.invalidate()
        runCurrent()
        preparation.response.complete(queue())
        job.join()
        assertTrue(preparation.deliveries.isEmpty())
        assertFalse(preparation.busy)
    }

    @Test fun logoutEpochSuppressesDelayedErrorsAndDoesNotMarkThePlaylistStale() = runTest {
        val preparation = Preparation()
        val job = preparation.start(this)
        runCurrent()
        PlaybackRequests.invalidate()
        preparation.response.completeExceptionally(IOException("synthetic delayed failure"))
        job.join()
        assertTrue(preparation.deliveries.isEmpty())
        assertEquals(0, preparation.failures)
        assertFalse(preparation.stale)
        assertFalse(preparation.busy)
    }

    @Test fun changedPlaylistGenerationStillRejectsCallbackAndCannotClearNewerBusyState() = runTest {
        val generation = PlaylistRequestGeneration()
        val preparation = Preparation(generation)
        val job = preparation.start(this)
        runCurrent()
        generation.invalidate() // Navigation, refresh or changed playlist inputs.
        preparation.response.complete(queue())
        job.join()
        assertTrue(preparation.deliveries.isEmpty())
        assertTrue(preparation.busy) // Cleanup belongs to the new generation now.
    }

    @Test fun aFreshTapAfterCancellationDeliversManualAndDerivedQueueIdentityUnchanged() = runTest {
        for (derived in listOf(false, true)) {
            val generation = PlaylistRequestGeneration()
            val old = Preparation(generation)
            val oldJob = old.start(this)
            runCurrent()
            PlaybackRequests.invalidate()
            val fresh = Preparation(generation, trackId = if (derived) null else "same-song")
            val freshJob = fresh.start(this)
            runCurrent()
            val prepared = queue(derived)
            old.response.complete(prepared)
            oldJob.join()
            assertTrue(old.deliveries.isEmpty())
            assertTrue(fresh.busy)
            fresh.response.complete(prepared)
            freshJob.join()
            val delivery = fresh.deliveries.single()
            assertSame(prepared, delivery.queue)
            assertEquals(if (derived) null else "same-song", delivery.trackId)
            assertEquals("second", delivery.entryId)
            assertEquals(listOf("first", "second"), delivery.queue.items.map { it.entryId })
            assertEquals(listOf("same-song", "same-song"), delivery.queue.items.map { it.id })
            assertEquals(7L, delivery.queue.revision)
            assertEquals(0, fresh.failures)
            assertFalse(fresh.busy)
        }
    }

    @Test fun manualBatchValidationKeepsDuplicateTracksButRejectsPositionAndCountDrift() {
        val track = LibraryItem("track", "same", "Song", hasAudio = true)
        val entries = listOf(PersonalPlaylistEntry("first", 40, track), PersonalPlaylistEntry("second", 41, track))
        val valid = PersonalEntriesPage(entries, total = 42, offset = 40)
        validatePlaylistEntryBatch(valid, 40, 42, afterPosition = 39)
        validatePlaylistEntryBatch(valid.copy(items = listOf(entries[0], entries[1].copy(position = 99))), 40, 42, afterPosition = 39)
        // Catalog deletion may leave gaps, including before the first surviving entry.
        validatePlaylistEntryBatch(PersonalEntriesPage(listOf(entries[0].copy(position = 4), entries[1].copy(position = 9)), total = 2), 0, 2)
        for (page in listOf(valid.copy(offset = 0), valid.copy(total = 43),
            valid.copy(items = listOf(entries[0], entries[0])),
            valid.copy(items = listOf(entries[0].copy(position = 39))),
            valid.copy(items = entries.reversed()), valid.copy(items = emptyList()))) {
            assertThrows(ApiException::class.java) { validatePlaylistEntryBatch(page, 40, 42, afterPosition = 39) }
        }
    }

    @Test fun derivedTerminalRequiresCompleteContiguousOccurrencePrefixNotJustANullCursor() {
        val track = LibraryItem("track", "same", "Song", hasAudio = true)
        val entries = (0..1).map { DerivedEvaluationEntry("path-$it", "signed-$it", it.toLong(), track) }
        validatePlaylistEvaluationPrefix(entries, 2, null)
        validatePlaylistEvaluationPrefix(entries.take(1), 2, "cursor")
        validatePlaylistEvaluationPrefix(emptyList(), 0, null)
        for ((items, total, cursor) in listOf(
            Triple(entries.take(1), 2L, null), Triple(entries.drop(1), 2L, null),
            Triple(entries, 2L, "cursor"), Triple(emptyList(), 2L, null),
            Triple(emptyList(), 2L, "cursor"), Triple(listOf(entries[0], entries[1].copy(position = 2)), 3L, "cursor"),
        )) assertThrows(ApiException::class.java) { validatePlaylistEvaluationPrefix(items, total, cursor) }
    }

    @Test fun equalSessionValuesCannotAuthorizeADelayedPlaybackAfterIncarnationChange() = runTest {
        val generation = PlaylistRequestGeneration()
        val session = Any()
        var liveSession = session
        val request = PlaylistPlaybackRequest(generation) { liveSession === session }
        liveSession = Any() // Same-account reauthentication has a new reference even with equal fields.
        var delivered = false
        request.deliver { delivered = true }
        assertFalse(delivered)
        var finished = false
        request.finish { finished = true }
        assertTrue(finished)
    }

    @Test fun aCurrentFetchStillPublishesItsErrorAndClearsBusy() = runTest {
        val preparation = Preparation()
        val job = preparation.start(this)
        runCurrent()
        preparation.response.completeExceptionally(IOException("synthetic current failure"))
        job.join()
        assertTrue(preparation.deliveries.isEmpty())
        assertEquals(1, preparation.failures)
        assertTrue(preparation.stale)
        assertFalse(preparation.busy)
    }
}
