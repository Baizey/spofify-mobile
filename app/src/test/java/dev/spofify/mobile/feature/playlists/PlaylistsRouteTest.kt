package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.DerivedEvaluationEntry
import dev.spofify.mobile.core.PersonalEntriesPage
import dev.spofify.mobile.core.PersonalPlaylistEntry
import dev.spofify.mobile.core.LibraryPlaybackContext
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

}
