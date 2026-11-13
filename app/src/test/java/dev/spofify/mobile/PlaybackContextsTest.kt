package dev.spofify.mobile

import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.queueIntentSelection
import org.junit.Assert.*
import org.junit.Test

class PlaybackContextsTest {
    private val row = LibraryItem("track", "same", "Song", albumId = "unrelated", hasAudio = true)
    @Test fun manualDuplicateRetainsOnlyItsExactEntryAndExplicitContext() {
        val context = LibraryPlaybackContext("playlist", "manual", "Mix")
        val selected = queueIntentSelection(row.copy(entryId = "second"), context) as LibraryPlaybackSelection.Track
        assertSame(context, selected.context)
        assertEquals("same", selected.track.id)
        assertEquals("second", selected.track.entryId)
        assertNull(selected.track.targetId)
        assertNull(selected.track.selectionToken)
        assertThrows(IllegalArgumentException::class.java) { queueIntentSelection(row, context) }
    }
    @Test fun derivedSelectionKeepsExactSignedPathAndRevisionFenceWithoutManualEntryId() {
        val context = LibraryPlaybackContext("derived", "rules", "Rules", 7, "fresh-fence")
        val selected = queueIntentSelection(row.copy(entryId = "not-manual"), context, "path/second", "signed") as LibraryPlaybackSelection.Track
        assertSame(context, selected.context)
        assertEquals("rules", selected.track.targetId)
        assertEquals("path/second", selected.track.occurrenceId)
        assertEquals("signed", selected.track.selectionToken)
        assertNull(selected.track.entryId)
        assertFalse(selected.track.toString().contains("signed"))
        assertThrows(IllegalArgumentException::class.java) { queueIntentSelection(row, context, "path/second", null) }
        assertThrows(IllegalArgumentException::class.java) { queueIntentSelection(row, context.copy(fence = null), "path", "signed") }
    }
    @Test fun bareTrackDoesNotInferAnAlbumOrForwardUnrelatedEntryIdentity() {
        val selected = queueIntentSelection(row.copy(entryId = "foreign")) as LibraryPlaybackSelection.Track
        assertNull(selected.context)
        assertNull(selected.track.entryId)
    }
    @Test fun likesCollectionUsesCanonicalContextIdentifier() {
        val selected = queueIntentSelection(LibraryItem("likes", "", "Liked songs")) as LibraryPlaybackSelection.Collection
        assertEquals("liked-songs", selected.context.id)
    }
    @Test fun recentShelfDoesNotOfferOffsetPagination() {
        assertEquals(40, displayedTotal(Page.Home, 40, 90))
        assertEquals(90, displayedTotal(Page.Likes, 40, 90))
    }
}
