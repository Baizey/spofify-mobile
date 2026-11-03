package dev.spofify.mobile.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackState
import dev.spofify.mobile.playback.QueueEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativePlayerTest {
    @get:Rule val compose = createComposeRule()
    private val track = LibraryItem("track", "song", "Current song",
        artists = listOf(ArtistCredit("artist", "Artist credit")),
        albumId = "album", albumName = "Album credit", hasAudio = true)

    @Test fun miniIdentityExpandsWhileTransportDoesNotExpandOrPrepare() {
        val controls = RecordingControls()
        var expands = 0
        compose.setContent {
            MaterialTheme {
                NativeMiniPlayer(PlaybackState(item = track), controls, { expands++ },
                    artwork = { _, modifier -> Box(modifier) })
            }
        }
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Next song").performClick()
        compose.runOnIdle {
            assertEquals(1, controls.toggles)
            assertEquals(1, controls.nexts)
            assertEquals(0, expands)
        }
        compose.onNodeWithText("Current song").performClick()
        compose.runOnIdle {
            assertEquals(1, expands)
            assertEquals(0, controls.prepares)
            assertEquals(0, controls.connects)
            assertEquals(0, controls.stops)
        }
    }

    @Test fun miniSwipeUpOnlyExpandsAndBufferingKeepsPauseActionAvailable() {
        val controls = RecordingControls()
        var expands = 0
        compose.setContent {
            MaterialTheme {
                NativeMiniPlayer(PlaybackState(item = track, isPlaying = false, playWhenReady = true, isBuffering = true),
                    controls, { expands++ }, artwork = { _, modifier -> Box(modifier) })
            }
        }
        compose.onNodeWithContentDescription("Pause").assertIsEnabled().performClick()
        compose.onNodeWithText("Current song").performTouchInput {
            swipe(Offset(center.x, height - 2f), Offset(center.x, -200f))
        }
        compose.runOnIdle {
            assertEquals(1, expands)
            assertEquals(1, controls.toggles)
            assertEquals(0, controls.prepares)
            assertEquals(0, controls.stops)
        }
    }

    @Test fun expandedBufferingPlaybackIntentKeepsPauseActionAvailable() {
        val controls = RecordingControls()
        setNowPlaying(PlaybackState(item = track, isPlaying = false, playWhenReady = true, isBuffering = true), controls)
        compose.onNodeWithContentDescription("Pause").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, controls.toggles) }
    }

    @Test fun coverSwipeMatchesVisibleNextAndPreviousWithoutRestartingContext() {
        val controls = RecordingControls()
        setNowPlaying(PlaybackState(item = track), controls)
        compose.onNodeWithTag("cover").performScrollTo().performTouchInput {
            swipeLeft(startX = width * 0.8f, endX = width * 0.2f)
        }
        compose.onNodeWithTag("cover").performTouchInput {
            swipeRight(startX = width * 0.2f, endX = width * 0.8f)
        }
        compose.onNodeWithContentDescription("Next song").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Previous song").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, controls.nexts)
            assertEquals(2, controls.previouses)
            assertEquals(0, controls.prepares)
            assertEquals(0, controls.stops)
        }
    }

    @Test fun currentSongCreditsContextAndPlaylistActionHaveIndependentNavigation() {
        val controls = RecordingControls()
        val opened = mutableListOf<LibraryItem>()
        val added = mutableListOf<LibraryItem>()
        val context = LibraryItem("playlist", "context", "Listening context")
        setNowPlaying(PlaybackState(item = track, context = context), controls,
            onOpenItem = { opened += it }, onAddToPlaylist = { added += it })
        for (label in listOf("Playing from Listening context", "Current song", "Artist credit", "Album credit")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        compose.onNodeWithText("Add to playlist").performScrollTo().performClick()
        compose.onNodeWithText("Close player").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("playlist", "track", "artist", "album"), opened.map { it.kind })
            assertEquals(listOf("context", "song", "artist", "album"), opened.map { it.id })
            assertEquals(listOf(track), added)
            assertEquals(0, controls.prepares)
            assertEquals(0, controls.stops)
        }
    }

    @Test fun creditsWithoutIdentityRemainReadableButCannotNavigate() {
        val controls = RecordingControls()
        val opened = mutableListOf<LibraryItem>()
        val unidentifiedCredits = track.copy(
            artists = listOf(ArtistCredit("", "Unidentified artist"), ArtistCredit("  ", "Another unidentified artist")),
            albumId = "  ", albumName = "Unidentified album",
        )
        setNowPlaying(PlaybackState(item = unidentifiedCredits), controls, onOpenItem = { opened += it })
        for (label in listOf("Unidentified artist", "Another unidentified artist", "Unidentified album")) {
            compose.onNodeWithText(label).performScrollTo().assertHasNoClickAction()
        }
        compose.runOnIdle { assertEquals(emptyList<LibraryItem>(), opened) }
    }

    @Test fun playerReusesUnknownLikeLoadingAndRetrySemantics() {
        val failed = mutableStateOf(false)
        val pending = mutableStateOf(false)
        var retries = 0
        var toggles = 0
        val controls = RecordingControls()
        compose.setContent {
            MaterialTheme {
                NativeNowPlaying(PlaybackState(item = track), controls, liked = null,
                    likePending = pending.value, likeFailed = failed.value,
                    onToggleLike = { toggles++ }, onRetryLike = { retries++ },
                    onAddToPlaylist = {}, onOpenItem = {}, artwork = { _, modifier -> Box(modifier) })
            }
        }
        compose.onNodeWithContentDescription("Checking like status").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { failed.value = true }
        compose.onNodeWithContentDescription("Retry like status").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, toggles); pending.value = true }
        compose.onNodeWithContentDescription("Saving like").assertIsNotEnabled()
    }

    @Test fun playbackNoticeIsStableAlongsideTransportAndError() {
        val controls = RecordingControls()
        setNowPlaying(PlaybackState(item = track, notice = "Showing the first 1000 songs",
            error = "Playback unavailable"), controls)
        compose.onNodeWithText("Showing the first 1000 songs").performScrollTo().assertExists()
        compose.onNodeWithText("Playback unavailable").assertExists()
        compose.onNodeWithContentDescription("Play").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Showing the first 1000 songs").assertExists()
    }

    @Test fun duplicateQueueTracksUseOccurrenceActionsAndUpcomingPositionsNotTimelineIndices() {
        val controls = RecordingControls()
        val duplicate = track.copy(id = "duplicate", name = "Duplicate song")
        val snapshot = PlaybackState(item = track, canUndoQueueRemoval = true,
            upcoming = listOf(QueueEntry("first", duplicate, 19), QueueEntry("second", duplicate, 3)))
        setNowPlaying(snapshot, controls)
        compose.onNodeWithContentDescription("Upcoming queue").performScrollTo()
        compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[1]
            .performScrollTo().performClick()
        compose.onNodeWithText("Play next").performClick()
        compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[0]
            .performScrollTo().performClick()
        compose.onNodeWithText("Move up").assertIsNotEnabled()
        compose.onNodeWithText("Move down").performClick()
        compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[1]
            .performScrollTo().performClick()
        compose.onNodeWithText("Remove from queue").performClick()
        compose.onAllNodesWithContentDescription("Play queued song Duplicate song")[1]
            .performScrollTo().performClick()
        compose.onNodeWithText("Undo").performScrollTo().performClick()
        compose.onNodeWithText("Clear upcoming").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("second" to 0, "first" to 1), controls.moves)
            assertEquals(listOf("second"), controls.removed)
            assertEquals(listOf("second"), controls.selected)
            assertEquals(1, controls.undos)
            assertEquals(1, controls.clears)
            assertEquals(0, controls.stops)
        }
    }

    @Test fun dedicatedHandleDragAndLeftSwipeEditOnlyTheUpcomingOccurrence() {
        val controls = RecordingControls()
        val first = track.copy(id = "first-track", name = "First queued")
        val second = track.copy(id = "second-track", name = "Second queued")
        setNowPlaying(PlaybackState(item = track, upcoming = listOf(
            QueueEntry("first-occurrence", first, 8), QueueEntry("second-occurrence", second, 2),
        )), controls)
        compose.onNodeWithContentDescription("Upcoming queue").performScrollTo()
        compose.onNodeWithContentDescription("Reorder First queued").performScrollTo().performTouchInput {
            swipe(center, Offset(center.x, height * 4f))
        }
        compose.onNodeWithText("First queued").performScrollTo().performTouchInput {
            swipe(center, Offset(-width.toFloat(), center.y))
        }
        compose.runOnIdle {
            assertEquals(listOf("first-occurrence" to 1), controls.moves)
            assertEquals(listOf("first-occurrence"), controls.removed)
            assertEquals(0, controls.prepares)
            assertEquals(0, controls.stops)
        }
    }

    @Test fun emptyQueueHasNoRemovalOrUndoAndClearCannotDropCurrentSong() {
        val controls = RecordingControls()
        setNowPlaying(PlaybackState(item = track), controls)
        compose.onNodeWithText("No upcoming songs").performScrollTo()
        compose.onNodeWithText("Reordering turns shuffle off").assertExists()
        compose.onNodeWithText("Queue edits turn shuffle off").assertDoesNotExist()
        compose.onNodeWithText("Clear upcoming").assertIsNotEnabled()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, controls.stops); assertEquals(0, controls.clears) }
    }

    private fun setNowPlaying(
        snapshot: PlaybackState,
        controls: RecordingControls,
        onOpenItem: (LibraryItem) -> Unit = {},
        onAddToPlaylist: (LibraryItem) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                NativeNowPlaying(snapshot, controls, liked = false, likePending = false, likeFailed = false,
                    onToggleLike = {}, onRetryLike = {}, onAddToPlaylist = onAddToPlaylist,
                    onOpenItem = onOpenItem,
                    artwork = { _, modifier -> Box(modifier.testTag("cover")) })
            }
        }
    }

    private class RecordingControls : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        var prepares = 0
        var connects = 0
        var stops = 0
        var toggles = 0
        var nexts = 0
        var previouses = 0
        var undos = 0
        var clears = 0
        val moves = mutableListOf<Pair<String, Int>>()
        val removed = mutableListOf<String>()
        val selected = mutableListOf<String>()
        override fun connect() { connects++ }
        override fun release() = Unit
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?,
            shuffle: Boolean, startEntryId: String?) { prepares++ }
        override fun toggle() { toggles++ }
        override fun next() { nexts++ }
        override fun previous() { previouses++ }
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() { stops++ }
        override fun moveUpcoming(occurrenceId: String, toIndex: Int) { moves += occurrenceId to toIndex }
        override fun removeUpcoming(occurrenceId: String) { removed += occurrenceId }
        override fun selectOccurrence(occurrenceId: String) { selected += occurrenceId }
        override fun undoQueueRemoval() { undos++ }
        override fun clearUpcoming() { clears++ }
    }
}
