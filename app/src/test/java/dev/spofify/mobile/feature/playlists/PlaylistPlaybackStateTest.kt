package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaylistKind
import dev.spofify.mobile.feature.library.CatalogPlaybackFeedback
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistPlaybackStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun duplicateOccurrenceRequiresExactEntryAndPlaylistContextRatherThanOnlyTrackId() {
        val context = LibraryItem("playlist", "mine", "My playlist")
        fun current(entry: String, playbackEntry: String? = "second", owner: LibraryItem? = context,
            trackId: String? = "song", kind: PlaylistKind = PlaylistKind.MANUAL) =
            playlistOccurrenceIsCurrent("mine", kind, entry, "song", trackId, playbackEntry, owner)
        assertFalse(current("first"))
        assertTrue(current("second"))
        assertFalse(current("second", playbackEntry = null))
        assertFalse(current("second", owner = null))
        assertFalse(current("second", owner = context.copy(id = "another")))
        assertFalse(current("second", owner = context.copy(kind = "album")))
        assertFalse(current("second", trackId = "different"))
        assertTrue(current("second", owner = context.copy(kind = "derived"), kind = PlaylistKind.DERIVED))
        assertFalse(current("second", kind = PlaylistKind.DERIVED))
    }

    @Test fun onlyExactCurrentOccurrenceAnnouncesPlayingBufferingAndPausedWithoutInlinePlay() {
        val feedback = mutableStateOf(CatalogPlaybackFeedback(trackId = "song", isPlaying = true))
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Column {
                    listOf("first", "second").forEach { entry ->
                        PlaylistTrackRow(LibraryItem("track", "song", "Same song", hasAudio = true, entryId = entry),
                            {}, { _, _ -> }, enabled = true, onPlay = {}, playback = feedback.value,
                            currentOccurrence = playlistOccurrenceIsCurrent("mine", PlaylistKind.MANUAL, entry,
                                "song", feedback.value.trackId, "second", LibraryItem("playlist", "mine", "Mine")))
                    }
                }
            }
        }
        fun state(value: String) = hasText("Same song") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
        fun assertExactNamedOccurrence(value: String) {
            val songs = compose.onAllNodesWithText("Same song").fetchSemanticsNodes()
            assertEquals(2, songs.size)
            assertNull(songs[0].config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(value, songs[1].config[SemanticsProperties.StateDescription])
        }
        compose.onAllNodes(state("Playing")).assertCountEquals(1)
        assertExactNamedOccurrence("Playing")
        compose.onNodeWithContentDescription("Play Same song").assertDoesNotExist()
        compose.runOnIdle { feedback.value = feedback.value.copy(isPlaying = false, buffering = true, playWhenReady = true) }
        compose.onAllNodes(state("Buffering")).assertCountEquals(1)
        assertExactNamedOccurrence("Buffering")
        compose.onAllNodes(state("Playing")).assertCountEquals(0)
        compose.runOnIdle { feedback.value = feedback.value.copy(buffering = false, playWhenReady = false) }
        compose.onAllNodes(state("Paused")).assertCountEquals(1)
        assertExactNamedOccurrence("Paused")
    }

    @Test fun bufferingUsesActualPlayWhenReadyRatherThanAnInventedActiveFlag() {
        assertTrue(CatalogPlaybackFeedback(buffering = true, playWhenReady = true).activeIntent)
        assertFalse(CatalogPlaybackFeedback(buffering = true, playWhenReady = false).activeIntent)
        assertTrue(CatalogPlaybackFeedback(isPlaying = true, playWhenReady = false).activeIntent)
    }
}
