package dev.spofify.mobile

import dev.spofify.mobile.core.saved.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Projection tests only, not a claim that a fake port verified or played media. */
class SavedOfflineCollectionMapperTest {
    private val owner = object : OfflineLibraryAccess {
        override val revision = MutableStateFlow(0L)
        override suspend fun collections(origin: String, accountId: String) = emptyList<OfflineSavedCollection>()
        override fun retire() = Unit
    }
    private fun reference() = OfflineMediaReference(owner, "http://127.0.0.1:1", "account") { null }
    private fun media(trackId: String): SavedMediaDescriptor {
        val sha = "0".repeat(64)
        return SavedMediaDescriptor(trackId, "sha256:$sha", sha, 4, "\"sha256:$sha\"")
    }
    private fun snapshot(rows: List<SavedOccurrenceSnapshot>, state: SavedDownloadState = SavedDownloadState.PARTIAL) =
        SavedCollectionSnapshot(SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, "target"), "Saved", "exact-generation",
            rows, state, updating = false, transferredBytes = 0, failure = null)

    @Test fun preservesAllOrderedOccurrencesAndPassesOnlyExactVerifiedRowsToTheOwnerAdapter() {
        val descriptor = media("repeat")
        val rows = listOf(
            SavedOccurrenceSnapshot(SavedOccurrence("one", "repeat", "First"), descriptor, true),
            SavedOccurrenceSnapshot(SavedOccurrence("two", "repeat", "Second"), descriptor, true),
            SavedOccurrenceSnapshot(SavedOccurrence("three", "missing", "Missing"), media("missing"), false),
            SavedOccurrenceSnapshot(SavedOccurrence("four", "proofless", "No descriptor"), null, true),
            SavedOccurrenceSnapshot(SavedOccurrence("five", "metadata", "No audio", hasAudio = false), null, false),
        )
        val seen = mutableListOf<SavedOccurrenceSnapshot>()
        val mapped = savedOfflineCollection(snapshot(rows)) { row -> seen += row; reference() }
        assertEquals(listOf("one", "two", "three", "four", "five"), mapped.rows.map { it.entryId })
        assertEquals(listOf("repeat", "repeat", "missing", "proofless", "metadata"), mapped.rows.map { it.id })
        assertEquals(listOf("one", "two"), mapped.availableRows.map { it.entryId })
        assertSame(rows[0], seen[0]); assertSame(rows[1], seen[1])
        assertSame(descriptor, seen[0].media); assertSame(descriptor, seen[1].media)
        assertNotSame(mapped.mediaReferences["one"], mapped.mediaReferences["two"])
        assertFalse(mapped.complete); assertFalse(mapped.playableInFull)
    }

    @Test fun authoritativeCompleteIsIndependentOfUpdatingEmptyOrMetadataOnlyRows() {
        val rows = listOf(
            SavedOccurrenceSnapshot(SavedOccurrence("audio", "track", "Ready"), media("track"), true),
            SavedOccurrenceSnapshot(SavedOccurrence("metadata", "silent", "No audio", hasAudio = false), null, false),
        )
        val complete = savedOfflineCollection(snapshot(rows, SavedDownloadState.COMPLETE).copy(updating = true)) { reference() }
        assertTrue(complete.complete)
        assertFalse(complete.playableInFull) // Must explicitly select Play available; never shorten row Play.
        assertEquals(2, complete.rows.size)
        assertEquals(1, complete.availableRows.size)
        val empty = savedOfflineCollection(snapshot(emptyList(), SavedDownloadState.COMPLETE)) { error("No rows") }
        assertTrue(empty.complete); assertTrue(empty.rows.isEmpty())
        for (state in listOf(SavedDownloadState.DOWNLOADING, SavedDownloadState.PARTIAL, SavedDownloadState.FAILED)) {
            val notComplete = savedOfflineCollection(snapshot(rows.take(1), state)) { reference() }
            assertFalse(notComplete.complete); assertFalse(notComplete.playableInFull)
        }
    }
}
