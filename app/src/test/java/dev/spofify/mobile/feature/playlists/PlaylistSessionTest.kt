package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.ApiException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PlaylistSessionTest {
    @Test fun unusableSuccessAcknowledgementsMustNeverEnableWriteReplay() {
        listOf(200, 201, 204, 408, 500, 503).forEach { status ->
            assertTrue("HTTP $status", playlistWriteUncertain(ApiException(status, "safe local text")))
        }
        assertTrue(playlistWriteUncertain(IOException("disconnected")))
        assertFalse(playlistWriteUncertain(ApiException(400, "invalid")))
        assertFalse(playlistWriteUncertain(ApiException(409, "conflict")))
        assertFalse(playlistWriteUncertain(ApiException(422, "rejected")))
    }

    @Test fun navigationAndChangedInputsInvalidateDelayedResults() {
        val generation = PlaylistRequestGeneration()
        val old = generation.invalidate()
        assertTrue(generation.accepts(old))
        val refreshed = generation.invalidate()
        assertFalse(generation.accepts(old))
        assertTrue(generation.accepts(refreshed))
    }

    @Test fun occurrenceMovesDoNotDeduplicateRepeatedTracks() {
        data class Entry(val occurrence: String, val track: String)
        val entries = listOf(Entry("first", "same"), Entry("second", "same"), Entry("third", "other"))
        assertEquals(listOf("second", "third", "first"), movedOccurrence(entries, 0, 2).map { it.occurrence })
        assertEquals(listOf("same", "other", "same"), movedOccurrence(entries, 0, 2).map { it.track })
        assertEquals(entries, movedOccurrence(entries, 0, -1))
        assertEquals(entries, movedOccurrence(entries, 2, 3))
    }

    @Test fun metadataLimitsUseUnicodeCodePoints() {
        val emoji = String(Character.toChars(0x1f3b5))
        assertNull(playlistMetadataError(emoji.repeat(100), "description\nwith\ttabs"))
        assertNotNull(playlistMetadataError(emoji.repeat(101), ""))
        assertNotNull(playlistMetadataError("", ""))
        assertNotNull(playlistMetadataError("na\nme", ""))
        assertNotNull(playlistMetadataError("name", "\u0000"))
    }
}
