package dev.spofify.mobile.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackState
import dev.spofify.mobile.playback.QueueEntry
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativePlayerTest {
    @get:Rule val compose = createComposeRule()
    private val track = LibraryItem("track", "song", "Current song",
        artists = listOf(ArtistCredit("artist", "Artist credit")),
        albumId = "album", albumName = "Album credit", hasAudio = true)

    // Legacy ShadowPaint uses fixed synthetic ascent/descent, not bundled-font metrics.
    // Native text measurement is required for meaningful compact/large-font geometry.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun miniBarAndProgressFillNarrowHostWithTightRowAndStableTargets() {
        val controls = RecordingControls()
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(320.dp).testTag("mini-host")) {
                    NativeMiniPlayer(PlaybackState(item = track, positionMs = 25_000, durationMs = 100_000),
                        controls, {}, artwork = { _, modifier -> Box(modifier.testTag("mini-art")) },
                        modifier = Modifier.testTag("mini-bar"))
                }
            }
        }
        compose.onNodeWithTag("mini-bar").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(66.dp)
        compose.onNodeWithTag("mini-art", useUnmergedTree = true)
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Play").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Next song").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val host = compose.onNodeWithTag("mini-host").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("mini-bar").fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithContentDescription("Playback progress").fetchSemanticsNode().boundsInRoot
        val art = compose.onNodeWithTag("mini-art", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription("Next song").fetchSemanticsNode().boundsInRoot
        assertEquals(host.left, bar.left, 0.5f)
        assertEquals(host.right, bar.right, 0.5f)
        assertEquals(bar.left, progress.left, 0.5f)
        assertEquals(bar.right, progress.right, 0.5f)
        assertEquals(art.center.y, play.center.y, 0.5f)
        assertEquals(play.center.y, next.center.y, 0.5f)
        compose.runOnIdle { assertEquals(0, controls.prepares); assertEquals(0, controls.connects) }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun narrowLargeTextKeepsAlignedSingleLineIdentitySeparateFromTransport() {
        val controls = RecordingControls()
        val longTrack = track.copy(name = "A very long song title that must not push transport off the bar",
            artists = listOf(ArtistCredit("artist", "A long credited artist name that remains on one line")))
        var expands = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(320.dp)) {
                        NativeMiniPlayer(PlaybackState(item = longTrack), controls, { expands++ },
                            artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.testTag("mini-bar"))
                    }
                }
            }
        }
        compose.onNodeWithTag("mini-bar").assertHeightIsEqualTo(66.dp)
        val title = compose.onNodeWithText(longTrack.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val credit = compose.onNodeWithText(longTrack.subtitle, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
        assertEquals(title.left, credit.left, 0.5f)
        org.junit.Assert.assertTrue(title.right <= play.left && credit.right <= play.left)
        org.junit.Assert.assertTrue(title.bottom <= credit.top + 0.5f)
        compose.onNodeWithContentDescription("Play").assertWidthIsEqualTo(48.dp).performClick()
        compose.onNodeWithContentDescription("Next song").assertWidthIsEqualTo(48.dp).performClick()
        compose.onNodeWithText(longTrack.name).performClick()
        compose.runOnIdle {
            assertEquals(1, expands)
            assertEquals(1, controls.toggles)
            assertEquals(1, controls.nexts)
            assertEquals(0, controls.prepares)
            assertEquals(0, controls.connects)
            assertEquals(0, controls.stops)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun miniGrowsFor135And200PercentFontsWithoutClippingIdentityOrShrinkingControls() {
        val controls = RecordingControls()
        val fontScale = mutableStateOf(1.35f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale.value)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(320.dp)) {
                        NativeMiniPlayer(PlaybackState(item = track), controls, {},
                            artwork = { _, modifier -> Box(modifier.testTag("mini-art")) },
                            modifier = Modifier.testTag("mini-bar"))
                    }
                }
            }
        }
        var previousHeight = 0f
        for (scale in listOf(1.35f, 2f)) {
            compose.runOnIdle { fontScale.value = scale }
            val bar = compose.onNodeWithTag("mini-bar").fetchSemanticsNode().boundsInRoot
            val inset = with(compose.density) { 8.dp.toPx() }
            val rowBottom = bar.bottom - with(compose.density) { 2.dp.toPx() }
            val rowCenter = (bar.top + rowBottom) / 2f
            val title = compose.onNodeWithText(track.name, useUnmergedTree = true)
            val credit = compose.onNodeWithText(track.subtitle, useUnmergedTree = true)
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            val creditBounds = credit.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Title stays within row at $scale", titleBounds.top >= bar.top + inset - 0.5f)
            org.junit.Assert.assertTrue("Credit stays within row at $scale", creditBounds.bottom <= rowBottom - inset + 0.5f)
            org.junit.Assert.assertTrue("Text lines do not overlap at $scale", titleBounds.bottom <= creditBounds.top + 0.5f)
            assertEquals(titleBounds.left, creditBounds.left, 0.5f)
            for (text in listOf(title, credit)) {
                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                val paragraphHeight = ceil(layout.multiParagraph.height.toDouble()).toInt()
                val diagnostic = "scale=$scale, text=${layout.layoutInput.text.text}, size=${layout.size}, " +
                    "constraints=${layout.layoutInput.constraints}, paragraphHeight=$paragraphHeight, " +
                    "paragraphWidth=${layout.multiParagraph.width}, intrinsicWidth=${layout.multiParagraph.maxIntrinsicWidth}, " +
                    "exceededMaxLines=${layout.multiParagraph.didExceedMaxLines}, ellipsized=${layout.isLineEllipsized(0)}"
                assertEquals(diagnostic, 1, layout.lineCount)
                assertEquals(diagnostic, 1, layout.layoutInput.maxLines)
                assertEquals(diagnostic, TextOverflow.Ellipsis, layout.layoutInput.overflow)
                // didOverflowHeight also reports maxLines/ellipsis truncation. Compare physical
                // paragraph height instead, preserving the vertical-clipping regression check.
                org.junit.Assert.assertTrue("Physical paragraph height fits: $diagnostic", layout.size.height >= paragraphHeight)
                assertEquals("Full text layout fits rendered bounds: $diagnostic",
                    layout.size.height.toFloat(), text.fetchSemanticsNode().boundsInRoot.height, 1f)
                if (layout.multiParagraph.didExceedMaxLines ||
                    layout.multiParagraph.maxIntrinsicWidth > layout.multiParagraph.width + 1f) {
                    org.junit.Assert.assertTrue("Width-truncated identity must ellipsize: $diagnostic", layout.isLineEllipsized(0))
                }
            }
            for (label in listOf("Play", "Next song")) {
                val action = compose.onNodeWithContentDescription(label)
                action.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                val bounds = action.fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue(bounds.top >= bar.top && bounds.bottom <= rowBottom)
                assertEquals(rowCenter, bounds.center.y, 0.5f)
                org.junit.Assert.assertTrue(titleBounds.right <= bounds.left && creditBounds.right <= bounds.left)
            }
            val art = compose.onNodeWithTag("mini-art", useUnmergedTree = true)
            art.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            assertEquals(rowCenter, art.fetchSemanticsNode().boundsInRoot.center.y, 0.5f)
            org.junit.Assert.assertTrue("Larger text grows the bar", bar.height > previousHeight)
            previousHeight = bar.height
        }
        compose.runOnIdle { assertEquals(0, controls.prepares); assertEquals(0, controls.connects); assertEquals(0, controls.stops) }
    }

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
