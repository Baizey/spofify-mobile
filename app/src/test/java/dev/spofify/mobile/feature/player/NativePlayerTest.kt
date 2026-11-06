package dev.spofify.mobile.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import android.graphics.Bitmap
import java.io.File
import dev.spofify.mobile.listeningColorScheme
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackActionResult
import dev.spofify.mobile.playback.PlaybackActionStatus
import dev.spofify.mobile.playback.PlaybackState
import dev.spofify.mobile.playback.QueueEntry
import dev.spofify.mobile.playback.remotePlaybackState
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
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
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
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
        compose.onNodeWithContentDescription("Next song").assertDoesNotExist()
        val host = compose.onNodeWithTag("mini-host").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("mini-bar").fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithContentDescription("Playback progress").fetchSemanticsNode().boundsInRoot
        val art = compose.onNodeWithTag("mini-art", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
        assertEquals(host.left, bar.left, 0.5f)
        assertEquals(host.right, bar.right, 0.5f)
        assertEquals(bar.left, progress.left, 0.5f)
        assertEquals(bar.right, progress.right, 0.5f)
        assertEquals(art.center.y, play.center.y, 0.5f)
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
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
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
        compose.onNodeWithContentDescription("Next song").assertDoesNotExist()
        compose.onNodeWithText(longTrack.name).performClick()
        compose.runOnIdle {
            assertEquals(1, expands)
            assertEquals(1, controls.toggles)
            assertEquals(0, controls.nexts)
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
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
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
            compose.onNodeWithContentDescription("Next song").assertDoesNotExist()
            for (label in listOf("Play")) {
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
        compose.onNodeWithContentDescription("Next song").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, controls.toggles)
            assertEquals(0, controls.nexts)
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

    @Test fun miniPhysicalLeftAndRightCommitOnceOnReleaseIncludingRtl() {
        val fixture = setMini()
        val key = checkNotNull(fixture.snapshot.value.transportInteractionKey)
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            fixture.update { fixture.layoutDirection.value = direction }
            val before = fixture.controls.keyedNext.size
            miniIdentity().performTouchInput {
                down(center)
                moveBy(Offset(-px(110), 0f))
                moveBy(Offset(px(35), 0f))
                moveBy(Offset(-px(35), 0f))
            }
            compose.runOnIdle { assertEquals("No transport before release", before, fixture.controls.keyedNext.size) }
            miniIdentity().performTouchInput { up() }
            miniIdentity().performTouchInput { swipe(center, center + Offset(px(100), 0f)) }
            compose.runOnIdle {
                assertEquals(before + 1, fixture.controls.keyedNext.size)
                assertEquals(before + 1, fixture.controls.keyedPrevious.size)
                assertEquals(key, fixture.controls.keyedNext.last())
                assertEquals(key, fixture.controls.keyedPrevious.last())
                assertEquals(0, fixture.expands)
                // No fallback to unguarded transport, including in RTL.
                assertEquals(0, fixture.controls.nexts)
                assertEquals(0, fixture.controls.previouses)
            }
        }
        fixture.assertPresentationOnly()
    }

    @Test fun miniTapJitterAndUpExpandButRealPlayTargetNeverExpands() {
        val fixture = setMini()
        miniIdentity().performTouchInput { click() }
        miniIdentity().performTouchInput {
            down(center)
            moveBy(Offset(px(1), px(-1)))
            up()
        }
        miniIdentity().performTouchInput { swipe(center, center + Offset(0f, -px(100))) }
        compose.onNodeWithContentDescription("Play").performTouchInput { click() }
        // Starting on the sibling control is not an identity swipe, even after leaving it.
        compose.onNodeWithContentDescription("Play").performTouchInput {
            swipe(center, center + Offset(-px(120), -px(100)))
        }
        compose.runOnIdle {
            assertEquals(3, fixture.expands)
            assertEquals(1, fixture.controls.toggles)
            assertTrue(fixture.controls.keyedNext.isEmpty())
            assertTrue(fixture.controls.keyedPrevious.isEmpty())
        }
        fixture.assertPresentationOnly()
    }

    @Test fun miniShortFastDiagonalDownAndReversedDragsDoNotSkipOrClick() {
        val fixture = setMini()
        // Distance, not velocity: a very fast drag still needs 64dp final displacement.
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(40), 0f), 16) }
        // Neither axis exceeds the 1.4 dominance ratio.
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(90), -px(75))) }
        miniIdentity().performTouchInput {
            down(center)
            moveBy(Offset(0f, px(30)))
            moveBy(Offset(-px(110), 0f))
            up()
        }
        miniIdentity().performTouchInput {
            down(center)
            moveBy(Offset(-px(110), 0f))
            moveBy(Offset(px(75), 0f))
            up()
        }
        fixture.assertCancelled()
        fixture.assertPresentationOnly()
    }

    @Test fun miniRatioAnd64DpReleaseThresholdKeepHorizontalAndUpIntentSeparate() {
        val fixture = setMini()
        // Just below the release threshold is not a commit even with dominant intent.
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(63), 0f)) }
        fixture.assertCancelled()
        // A diagonal which is clearly horizontal (100/60 > 1.4) skips, never expands.
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(100), -px(60))) }
        // A clearly upward diagonal expands and must not also skip.
        miniIdentity().performTouchInput { swipe(center, center + Offset(px(60), -px(100))) }
        compose.runOnIdle {
            assertEquals(1, fixture.controls.keyedNext.size)
            assertTrue(fixture.controls.keyedPrevious.isEmpty())
            assertEquals(1, fixture.expands)
        }
        fixture.assertPresentationOnly()
    }

    @Test fun miniPointerCancelAndMultitouchHaveNoTransportExpansionOrSuccessFeedback() {
        val fixture = setMini()
        miniIdentity().performTouchInput {
            down(center)
            moveBy(Offset(-px(100), 0f))
            cancel()
        }
        fixture.assertCancelled()
        miniIdentity().performTouchInput {
            down(0, center)
            moveBy(Offset(-px(100), 0f))
            down(1, center + Offset(px(10), 0f))
            up(1)
            up(0)
        }
        fixture.assertCancelled()
        // Cancellation cannot poison the next deliberate gesture.
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(100), 0f)) }
        compose.runOnIdle { assertEquals(1, fixture.controls.keyedNext.size); assertEquals(0, fixture.expands) }
        fixture.assertPresentationOnly()
    }

    @Test fun miniDisposalCancelsHeldPointerWithoutFeedbackOrDelayedReplay() {
        val fixture = setMini()
        val staleActions = miniCustomActions()
        miniIdentity().performTouchInput { down(center); moveBy(Offset(-px(100), 0f)) }
        fixture.update { fixture.visible.value = false }
        compose.runOnIdle { staleActions.forEach { assertFalse("Disposed identity cannot submit ${it.label}", it.action()) } }
        compose.onNodeWithTag("mini-player-identity").assertDoesNotExist()
        compose.onNodeWithTag("mini-test-host").performTouchInput { up() }
        fixture.update { fixture.visible.value = true }
        fixture.assertCancelled()
        fixture.assertPresentationOnly()
    }

    @Test fun miniPhysicalBackEdgesAreNotTransportEvenInRtl() {
        val fixture = setMini()
        // In LTR identity touches the physical left edge; in RTL it touches the right.
        miniIdentity().performTouchInput {
            swipe(Offset(px(2), center.y), Offset(px(120), center.y))
        }
        fixture.assertCancelled()
        fixture.update { fixture.layoutDirection.value = LayoutDirection.Rtl }
        miniIdentity().performTouchInput {
            swipe(Offset(width - px(2), center.y), Offset(width - px(120), center.y))
        }
        fixture.assertCancelled()
        // Leaving edge transport unconsumed must not disable subsequent accessible expansion.
        miniIdentity().performClick()
        compose.runOnIdle { assertEquals(1, fixture.expands) }
        fixture.assertPresentationOnly()
    }

    @Test fun miniProgressArtworkAndBufferingPublicationsPreservePointerDownKey() {
        val fixture = setMini()
        val key = checkNotNull(fixture.snapshot.value.transportInteractionKey)
        miniIdentity().performTouchInput { down(center) }
        repeat(3) { frame ->
            fixture.update {
                fixture.snapshot.value = fixture.snapshot.value.copy(positionMs = (frame + 1) * 1_000L,
                    isBuffering = frame % 2 == 0)
                fixture.artworkLoaded.value = true
            }
        }
        // Starting motion only AFTER recomposition proves capture really happened on down.
        miniIdentity().performTouchInput { moveBy(Offset(-px(100), 0f)); up() }
        compose.runOnIdle {
            assertEquals(listOf(key), fixture.controls.keyedNext)
            assertEquals(0, fixture.expands)
        }
        fixture.assertPresentationOnly()
    }

    @Test fun miniInteractionReplacementBeforeSlopAndDuringDragNeverRetargets() {
        val fixture = setMini()
        for (moveBeforeReplacement in listOf(false, true)) {
            miniIdentity().performTouchInput {
                down(center)
                if (moveBeforeReplacement) moveBy(Offset(-px(40), 0f))
            }
            fixture.update {
                fixture.snapshot.value = fixture.snapshot.value.copy(transportInteractionKey = PlaybackInteractionKey(),
                    item = track.copy(id = "replacement", name = "Replacement song"), currentEntryId = "replacement")
            }
            miniIdentity().performTouchInput { moveBy(Offset(-px(100), 0f)); up() }
            fixture.assertCancelled()
        }
        fixture.assertPresentationOnly()
    }

    @Test fun miniObservedKeyOrCapabilityAbaDoesNotReviveHeldGestureOrOldAccessibilityCallback() {
        val fixture = setMini()
        val initial = fixture.snapshot.value
        for (changeKey in listOf(true, false)) {
            val stale = miniCustomActions()
            miniIdentity().performTouchInput { down(center) }
            fixture.update {
                fixture.snapshot.value = if (changeKey) initial.copy(transportInteractionKey = PlaybackInteractionKey())
                    else initial.copy(canSkipNext = false)
            }
            fixture.update { fixture.snapshot.value = initial }
            miniIdentity().performTouchInput { moveBy(Offset(-px(100), 0f)); up() }
            compose.runOnIdle { stale.forEach { assertFalse("Observed ABA cannot revive ${it.label}", it.action()) } }
            fixture.assertCancelled()
        }
        fixture.assertPresentationOnly()
    }

    @Test fun miniControllerReplacementWithSameDisplayKeyCannotRetargetHeldGesture() {
        val fixture = setMini()
        val replacement = RecordingControls().apply { state.value = fixture.snapshot.value }
        val stale = miniCustomActions()
        miniIdentity().performTouchInput { down(center) }
        fixture.update { fixture.boundControls.value = replacement }
        miniIdentity().performTouchInput { moveBy(Offset(-px(100), 0f)); up() }
        compose.runOnIdle {
            stale.forEach { assertFalse(it.action()) }
            assertTrue(replacement.keyedNext.isEmpty())
            assertTrue(replacement.keyedPrevious.isEmpty())
            assertEquals(0, replacement.nexts)
            assertEquals(0, replacement.previouses)
            assertEquals(0, replacement.prepares)
            assertEquals(0, replacement.connects)
            assertEquals(0, replacement.stops)
        }
        fixture.assertCancelled()
        fixture.assertPresentationOnly()
    }

    @Test fun miniCapabilityLossWithStableKeyCancelsEachDirectionWithoutFeedback() {
        val fixture = setMini()
        val key = fixture.snapshot.value.transportInteractionKey
        for (left in listOf(true, false)) {
            fixture.update {
                fixture.snapshot.value = fixture.snapshot.value.copy(canSkipNext = true, canSkipPrevious = true)
            }
            miniIdentity().performTouchInput { down(center); moveBy(Offset(if (left) -px(40) else px(40), 0f)) }
            fixture.update {
                fixture.snapshot.value = fixture.snapshot.value.copy(canSkipNext = !left, canSkipPrevious = left)
            }
            miniIdentity().performTouchInput { moveBy(Offset(if (left) -px(70) else px(70), 0f)); up() }
            compose.runOnIdle { assertEquals(key, fixture.snapshot.value.transportInteractionKey) }
            fixture.assertCancelled()
        }
        fixture.assertPresentationOnly()
    }

    @Test fun miniUnavailableAtPointerDownCannotBecomeAvailableMidGestureButCanExpand() {
        val fixture = setMini(miniSnapshot().copy(canSkipNext = false, canSkipPrevious = false))
        miniIdentity().performTouchInput { down(center) }
        fixture.update { fixture.snapshot.value = fixture.snapshot.value.copy(canSkipNext = true) }
        miniIdentity().performTouchInput { moveBy(Offset(-px(100), 0f)); up() }
        fixture.assertCancelled()
        fixture.update { fixture.snapshot.value = fixture.snapshot.value.copy(transportInteractionKey = null) }
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(100), 0f)) }
        fixture.assertCancelled()
        miniIdentity().performTouchInput { click() }
        miniIdentity().performTouchInput { swipe(center, center + Offset(0f, -px(100))) }
        compose.runOnIdle { assertEquals(2, fixture.expands) }
        fixture.assertPresentationOnly()
    }

    @Test fun miniGenericKeyDoesNotEnableTransportWhenTransportKeyIsUnavailable() {
        val fixture = setMini(miniSnapshot().copy(transportInteractionKey = null))
        compose.runOnIdle {
            assertTrue("Generic queue interaction identity remains available", fixture.snapshot.value.interactionKey != null)
            assertEquals(null, fixture.snapshot.value.transportInteractionKey)
        }
        assertTrue(miniCustomActions().isEmpty())
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(100), 0f)) }
        miniIdentity().performTouchInput { swipe(center, center + Offset(px(100), 0f)) }
        fixture.assertCancelled()
        compose.runOnIdle { assertTrue("Never fall back to the generic key", fixture.controls.keyedAttempts.isEmpty()) }
        miniIdentity().performTouchInput { click() }
        miniIdentity().performTouchInput { swipe(center, center + Offset(0f, -px(100))) }
        compose.runOnIdle { assertEquals(2, fixture.expands) }
        fixture.assertPresentationOnly()
    }

    @Test fun miniAccessibilityKeepsIdentityExpandAndCapabilityGuardedNamedAlternatives() {
        val fixture = setMini()
        val identity = miniIdentity().fetchSemanticsNode().config
        assertEquals("Expand Now Playing", identity[SemanticsActions.OnClick].label)
        val text = identity[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue(text.contains(track.name))
        assertTrue(text.contains(track.subtitle))
        val accessibleContext = listOf(text, identity.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            identity.getOrNull(SemanticsProperties.StateDescription)).filterNotNull().joinToString(" ")
        assertTrue("Concise swipe guidance accompanies, not replaces, identity", accessibleContext.contains("swipe", ignoreCase = true))
        val actions = miniCustomActions()
        assertEquals(setOf("Next song", "Previous song"), actions.map { it.label }.toSet())
        val key = checkNotNull(fixture.snapshot.value.transportInteractionKey)
        fixture.update {
            fixture.snapshot.value = fixture.snapshot.value.copy(positionMs = 12_000, isBuffering = true)
            fixture.artworkLoaded.value = true
        }
        compose.runOnIdle { actions.forEach { assertTrue("Benign publications retain ${it.label}", it.action()) } }
        compose.runOnIdle {
            assertEquals(listOf(key), fixture.controls.keyedNext)
            assertEquals(listOf(key), fixture.controls.keyedPrevious)
            assertEquals(0, fixture.expands)
        }
        compose.onNodeWithContentDescription("Next song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertIsEnabled().assertWidthIsEqualTo(48.dp)
        fixture.assertPresentationOnly()
    }

    @Test fun miniUnavailableAccessibilityActionsAreOmittedAndOldCallbacksFailClosed() {
        val fixture = setMini()
        val stale = miniCustomActions()
        fixture.update { fixture.snapshot.value = fixture.snapshot.value.copy(transportInteractionKey = PlaybackInteractionKey()) }
        compose.runOnIdle { stale.forEach { assertFalse("Old callback cannot retarget ${it.label}", it.action()) } }
        val current = miniCustomActions()
        fixture.update { fixture.snapshot.value = fixture.snapshot.value.copy(canSkipNext = false, canSkipPrevious = false) }
        assertTrue(miniCustomActions().isEmpty())
        compose.runOnIdle { current.forEach { assertFalse("Capability loss rejects ${it.label}", it.action()) } }
        fixture.update { fixture.snapshot.value = fixture.snapshot.value.copy(canSkipPrevious = true) }
        assertEquals(listOf("Previous song"), miniCustomActions().map { it.label })
        fixture.update { fixture.snapshot.value = fixture.snapshot.value.copy(canSkipNext = true, canSkipPrevious = false) }
        assertEquals(listOf("Next song"), miniCustomActions().map { it.label })
        fixture.update { fixture.snapshot.value = fixture.snapshot.value.copy(transportInteractionKey = null, canSkipNext = true, canSkipPrevious = true) }
        assertTrue(miniCustomActions().isEmpty())
        fixture.assertCancelled()
        miniIdentity().performClick() // Expansion remains the non-swipe way to expanded controls.
        compose.runOnIdle { assertEquals(1, fixture.expands) }
        fixture.assertPresentationOnly()
    }

    @Test fun miniRejectedGuardedDispatchDoesNotClaimSuccessOrProduceHaptic() {
        val fixture = setMini()
        fixture.controls.acceptKeyedTransport = false
        val actions = miniCustomActions()
        compose.runOnIdle { actions.forEach { assertFalse(it.action()) } }
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(100), 0f)) }
        miniIdentity().performTouchInput { swipe(center, center + Offset(px(100), 0f)) }
        compose.runOnIdle {
            assertTrue("Exercise actual guarded rejection, not only UI gating", fixture.controls.keyedAttempts.isNotEmpty())
        }
        fixture.assertCancelled()
        fixture.assertPresentationOnly()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun absentCurrentItemKeepsDevicesAndDisabledPlayWithoutMusicalIdentityOrProgress() {
        val fixture = setMini(PlaybackState(), withPlaybackDevices = true)
        miniIdentity().assertDoesNotExist()
        compose.onNodeWithTag("mini-art", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback progress").assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback position").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous song").assertDoesNotExist()
        compose.onNodeWithTag("now-playing").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Playback devices").assertCountEquals(1)
        compose.onNodeWithText("No song selected").assertIsDisplayed().performTouchInput {
            click()
            swipe(center, center + Offset(0f, -px(100)))
            swipe(center, center + Offset(-px(100), 0f))
        }
        val devices = compose.onNodeWithContentDescription("Playback devices")
            .assertIsDisplayed().assertIsEnabled().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val play = compose.onNodeWithContentDescription("Play")
            .assertIsDisplayed().assertIsNotEnabled().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val devicesBounds = devices.fetchSemanticsNode().boundsInRoot
        val playBounds = play.fetchSemanticsNode().boundsInRoot
        val emptyBounds = compose.onNodeWithText("No song selected").fetchSemanticsNode().boundsInRoot
        assertTrue(emptyBounds.right <= devicesBounds.left)
        assertEquals(devicesBounds.right, playBounds.left, .5f)
        assertEquals(devicesBounds.center.y, playBounds.center.y, .5f)
        play.performTouchInput { click() }
        devices.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, fixture.deviceOpens)
            fixture.controls.assertNoCommands()
        }
        fixture.assertCancelled()
        fixture.assertPresentationOnly()
    }

    @Test fun missingDeviceCallbackOmitsDevicesTruthfullyButKeepsEmptyDock() {
        val fixture = setMini()
        compose.onNodeWithContentDescription("Playback devices").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertIsEnabled()
        fixture.update { fixture.snapshot.value = PlaybackState() }
        compose.onNodeWithText("No song selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playback devices").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled().performTouchInput { click() }
        miniIdentity().assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback progress").assertDoesNotExist()
        compose.runOnIdle { fixture.controls.assertNoCommands(); assertEquals(0, fixture.deviceOpens) }
        fixture.assertCancelled()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun narrowFont2MiniKeepsDevicesBetweenLongUnicodeIdentityAndPlayThroughPublications() {
        val controls = RecordingControls()
        val snapshot = mutableStateOf(miniSnapshot().copy(item = track.copy(
            name = "Long Unicode title 音楽 🎵 that must yield width to transport",
            artists = listOf(ArtistCredit("artist", "Long Unicode credit 演奏者 and another performer")),
        )))
        var deviceOpens = 0
        var expands = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Box(Modifier.width(320.dp).testTag("mini-host")) {
                        NativeMiniPlayer(snapshot.value, controls, { expands++ },
                            artwork = { _, modifier -> Box(modifier) },
                            modifier = Modifier.testTag("mini-bar"), onPlaybackDevices = { deviceOpens++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("mini-bar").assertWidthIsEqualTo(320.dp)
        compose.onAllNodesWithContentDescription("Playback devices").assertCountEquals(1)
        val identity = miniIdentity().fetchSemanticsNode().boundsInRoot
        val devices = compose.onNodeWithContentDescription("Playback devices")
            .assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Play")
            .assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Identity cannot own the device target", identity.right <= devices.left)
        assertEquals("Devices immediately precedes Play", devices.right, play.left, .5f)
        assertEquals(devices.center.y, play.center.y, .5f)
        val bar = compose.onNodeWithTag("mini-bar").fetchSemanticsNode().boundsInRoot
        for (bounds in listOf(identity, devices, play)) {
            assertTrue("Each sibling fits in the dock", bounds.top >= bar.top && bounds.bottom <= bar.bottom)
        }
        for (text in listOf(snapshot.value.item!!.name, snapshot.value.item!!.subtitle)) {
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Long identity yields width to Devices", bounds.right <= devices.left)
        }
        repeat(20) { frame ->
            compose.runOnIdle { snapshot.value = snapshot.value.copy(positionMs = frame * 1_000L) }
            assertEquals(identity, miniIdentity().fetchSemanticsNode().boundsInRoot)
            assertEquals(devices, compose.onNodeWithContentDescription("Playback devices").fetchSemanticsNode().boundsInRoot)
            assertEquals(play, compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithContentDescription("Playback devices").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, deviceOpens); assertEquals(0, expands); controls.assertNoCommands() }
        // Empty rendering must not borrow stale musical intent or progress from a publication.
        compose.runOnIdle {
            snapshot.value = snapshot.value.copy(item = null, isPlaying = true, playWhenReady = true, isBuffering = true)
        }
        miniIdentity().assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback progress").assertDoesNotExist()
        compose.onNodeWithText("No song selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        val emptyPlay = compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot
        val emptyDevices = compose.onNodeWithContentDescription("Playback devices").assertIsEnabled()
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        assertEquals(emptyDevices.fetchSemanticsNode().boundsInRoot.right, emptyPlay.left, .5f)
        emptyDevices.performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, deviceOpens); assertEquals(0, expands); controls.assertNoCommands() }
    }

    @Test fun devicesTouchAndCrossingDragsNeverAcquireMiniIdentityTransportInEitherLayoutDirection() {
        val fixture = setMini(withPlaybackDevices = true)
        val remote = remotePlaybackState(PlaybackSnapshot(position = 0, queue = listOf(
            PlaybackTrack("remote-song", "Remote song", emptyList(), "Remote album", "remote-album",
                100.0, key = 1, order = 0),
        ))).copy(transportInteractionKey = PlaybackInteractionKey(), canSkipNext = true, canSkipPrevious = true)
        var expectedOpens = 0
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            fixture.update { fixture.layoutDirection.value = direction }
            for (snapshot in listOf(miniSnapshot(), remote, miniSnapshot().copy(
                isBuffering = true, playWhenReady = true), miniSnapshot().copy(
                transportInteractionKey = null, canSkipNext = false, canSkipPrevious = false))) {
                fixture.update { fixture.snapshot.value = snapshot }
                val devices = compose.onNodeWithContentDescription("Playback devices").assertIsEnabled()
                devices.performTouchInput { click() }
                expectedOpens++
                val deviceBounds = devices.fetchSemanticsNode().boundsInRoot
                val playLabel = if (snapshot.isPlaying || snapshot.playWhenReady) "Pause" else "Play"
                val playBounds = compose.onNodeWithContentDescription(playLabel).fetchSemanticsNode().boundsInRoot
                val identityBounds = miniIdentity().fetchSemanticsNode().boundsInRoot
                assertEquals("Devices stays physically left of Play even in $direction", deviceBounds.right, playBounds.left, .5f)
                assertFalse("Devices is not inside identity in $direction", deviceBounds.overlaps(identityBounds))
                assertFalse("Play is not inside identity in $direction", playBounds.overlaps(identityBounds))
                val intoIdentity = identityBounds.center - deviceBounds.center
                // Release inside identity after crossing its boundary, then repeat with an
                // upward continuation. Hit ownership belongs to the pointer-down sibling.
                devices.performTouchInput { swipe(center, center + intoIdentity) }
                devices.performTouchInput {
                    down(center)
                    moveBy(intoIdentity)
                    moveBy(Offset(0f, -px(100)))
                    up()
                }
                devices.performTouchInput { down(center); moveBy(intoIdentity); cancel() }
                compose.runOnIdle {
                    assertEquals("Only the deliberate device tap opens the picker", expectedOpens, fixture.deviceOpens)
                    fixture.controls.assertNoCommands()
                }
                fixture.assertCancelled()
            }
        }
        // Device gestures do not poison subsequent identity tap or guarded music gestures.
        fixture.update {
            fixture.layoutDirection.value = LayoutDirection.Ltr
            fixture.snapshot.value = miniSnapshot()
        }
        miniIdentity().performTouchInput { click() }
        miniIdentity().performTouchInput { swipe(center, center + Offset(-px(100), 0f)) }
        compose.runOnIdle {
            assertEquals(1, fixture.expands)
            assertEquals(1, fixture.controls.keyedNext.size)
            assertEquals(expectedOpens, fixture.deviceOpens)
        }
        fixture.assertPresentationOnly()
    }

    @Test fun expandedDevicesTapIsPresentationOnlyDuringBufferingAndMissingCallbackOmitsIt() {
        val controls = RecordingControls()
        val available = mutableStateOf(true)
        var deviceOpens = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    NativeNowPlaying(PlaybackState(item = track, playWhenReady = true, isBuffering = true),
                        controls, false, false, false, {}, {}, {}, {},
                        artwork = { _, modifier -> Box(modifier) },
                        onPlaybackDevices = if (available.value) ({ deviceOpens++ }) else null)
                }
            }
        }
        compose.onAllNodesWithContentDescription("Playback devices").assertCountEquals(1)
        compose.onNodeWithContentDescription("Pause").assertIsEnabled()
        listOf("Previous song", "Playback devices", "Pause", "Next song").map {
            compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
        }.zipWithNext().forEach { (left, right) ->
            assertTrue("Expanded transport retains physical order in RTL", left.right <= right.left)
        }
        val devices = compose.onNodeWithContentDescription("Playback devices").assertIsEnabled()
        devices.performTouchInput { click() }
        devices.performTouchInput { swipe(center, center + Offset(-px(120), 0f)) }
        compose.runOnIdle { assertEquals(1, deviceOpens); controls.assertNoCommands(); available.value = false }
        compose.onNodeWithContentDescription("Playback devices").assertDoesNotExist()
        for (label in listOf("Previous song", "Pause", "Next song")) {
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        compose.runOnIdle { controls.assertNoCommands() }
    }

    @Test fun expandedBufferingPlaybackIntentKeepsPauseActionAvailable() {
        val controls = RecordingControls()
        setNowPlaying(PlaybackState(item = track, isPlaying = false, playWhenReady = true, isBuffering = true), controls)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, controls.toggles) }
    }

    @Test fun coverSwipeMatchesVisibleNextAndPreviousWithoutRestartingContext() {
        val controls = RecordingControls()
        setNowPlaying(PlaybackState(item = track), controls)
        compose.onNodeWithTag("cover").performTouchInput {
            swipeLeft(startX = width * 0.8f, endX = width * 0.2f)
        }
        compose.onNodeWithTag("cover").performTouchInput {
            swipeRight(startX = width * 0.2f, endX = width * 0.8f)
        }
        compose.onNodeWithContentDescription("Next song").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Previous song").assertIsDisplayed().performClick()
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
        compose.onNodeWithText("Current song").performClick()
        for (label in listOf("Playing from Listening context", "Artist credit", "Album credit", "Add to playlist")) {
            compose.onNodeWithContentDescription("Now Playing actions").performClick()
            compose.onNode(hasText(label) and SemanticsMatcher("single menu label") {
                it.config[SemanticsProperties.Text].size == 1
            }).performClick()
        }
        compose.onNodeWithText("Close player").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("track", "playlist", "artist", "album"), opened.map { it.kind })
            assertEquals(listOf("song", "context", "artist", "album"), opened.map { it.id })
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
        compose.onNodeWithContentDescription("Now Playing actions").performClick()
        for (label in listOf("Unidentified artist", "Another unidentified artist", "Unidentified album")) {
            compose.onNodeWithText(label).assertIsNotEnabled()
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
        compose.onNodeWithContentDescription("Checking like status").assertIsNotEnabled()
        compose.runOnIdle { failed.value = true }
        compose.onNodeWithContentDescription("Retry like status").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, toggles); pending.value = true }
        compose.onNodeWithContentDescription("Saving like").assertIsNotEnabled()
    }

    @Test fun playbackNoticeIsStableAlongsideTransportAndError() {
        val controls = RecordingControls()
        setNowPlaying(PlaybackState(item = track, notice = "Showing the first 1000 songs",
            error = "Playback unavailable"), controls)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Playback unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Now Playing actions").performClick()
        compose.onNodeWithText("Showing the first 1000 songs").assertExists()
    }

    @Test fun duplicateQueueTracksUseOccurrenceActionsAndUpcomingPositionsNotTimelineIndices() {
        val controls = RecordingControls()
        val duplicate = track.copy(id = "duplicate", name = "Duplicate song")
        val interactionKey = PlaybackInteractionKey()
        val snapshot = PlaybackState(item = track, interactionKey = interactionKey, canUndoQueueRemoval = true,
            upcoming = listOf(QueueEntry("first", duplicate, 19), QueueEntry("second", duplicate, 3)))
        setNowPlaying(snapshot, controls)
        compose.onNodeWithContentDescription("Upcoming queue").assertIsDisplayed()
        for (label in listOf("Queue Low", "Queue High", "Play next", "Play now")) {
            compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[1]
                .performScrollTo().performClick()
            compose.onNodeWithText(label).performClick()
        }
        compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[0]
            .performScrollTo().performClick()
        compose.onNodeWithText("Move up").assertIsNotEnabled()
        compose.onNodeWithText("Move down").performClick()
        compose.onAllNodesWithContentDescription("Queue actions for Duplicate song")[1]
            .performScrollTo().performClick()
        compose.onNodeWithText("Remove from queue").performClick()
        compose.onAllNodesWithContentDescription("Play queued song Duplicate song").assertCountEquals(0)
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Upcoming queue actions").performClick()
        compose.onNodeWithText("Clear upcoming").performClick()
        compose.runOnIdle {
            assertEquals(listOf("first" to 1), controls.moves)
            assertEquals(listOf(
                dev.spofify.mobile.playback.PlaybackListAction.QUEUE_LOW,
                dev.spofify.mobile.playback.PlaybackListAction.QUEUE_HIGH,
                dev.spofify.mobile.playback.PlaybackListAction.PLAY_NEXT,
                dev.spofify.mobile.playback.PlaybackListAction.PLAY_NOW,
            ).map { Triple(interactionKey, "second", it) }, controls.queueIntents)
            assertTrue("No legacy Play-next fallback", controls.playNexts.isEmpty())
            assertEquals(listOf("second"), controls.removed)
            assertTrue("Now must address the guarded raw occurrence, not unguarded legacy select", controls.selected.isEmpty())
            assertEquals(1, controls.undos)
            assertEquals(1, controls.clears)
            assertEquals(0, controls.stops)
        }
    }

    @Test fun dedicatedHandleDragAndLeftSwipeEditOnlyTheUpcomingOccurrence() {
        val controls = RecordingControls()
        val interactionKey = PlaybackInteractionKey()
        val first = track.copy(id = "first-track", name = "First queued")
        val second = track.copy(id = "second-track", name = "Second queued")
        setNowPlaying(PlaybackState(item = track, interactionKey = interactionKey,
            canMoveUpcomingAbsolutely = true, upcoming = listOf(
            QueueEntry("first-occurrence", first, 8), QueueEntry("second-occurrence", second, 2),
        )), controls)
        compose.onNodeWithContentDescription("Upcoming queue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reorder First queued").performScrollTo().performTouchInput {
            swipe(center, Offset(center.x, height * 4f))
        }
        compose.onNodeWithText("First queued").performScrollTo().performTouchInput {
            swipe(center, Offset(-width.toFloat(), center.y))
        }
        compose.runOnIdle {
            assertEquals(listOf("first-occurrence" to 1), controls.moves)
            assertEquals(listOf(Triple(interactionKey, "first-occurrence", 1)), controls.guardedMoves)
            assertEquals(listOf("first-occurrence"), controls.removed)
            assertEquals(0, controls.prepares)
            assertEquals(0, controls.stops)
        }
    }

    @Test fun emptyQueueHasNoRemovalOrUndoAndClearCannotDropCurrentSong() {
        val controls = RecordingControls()
        setNowPlaying(PlaybackState(item = track), controls)
        compose.onNodeWithText("No upcoming songs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Upcoming queue actions").performClick()
        compose.onNodeWithText("Reordering turns shuffle off").assertExists()
        compose.onNodeWithText("Queue edits turn shuffle off").assertDoesNotExist()
        compose.onNodeWithText("Clear upcoming").assertIsNotEnabled()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, controls.stops); assertEquals(0, controls.clears) }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], qualifiers = "w800dp-h1000dp-mdpi")
    @Test fun pinnedControlsAndReleasedQueueBottomStayStableAcrossProgressArtworkAndViewportChanges() {
        val controls = RecordingControls()
        var deviceOpens = 0
        var expectedDeviceOpens = 0
        val transportLabels = listOf("Previous song", "Playback devices", "Play", "Next song")
        val viewport = mutableStateOf(Triple(360, 800, 1f))
        val snapshot = mutableStateOf(PlaybackState(item = track.copy(
            name = "A long Unicode song title 音楽 with several credited performers",
            artists = listOf(ArtistCredit("a", "First very long credit"), ArtistCredit("b", "Second credit")),
        ), durationMs = 600_000, upcoming = (1..30).map {
            QueueEntry("occurrence-$it", track.copy(id = "queued-$it", name = "Queued song $it"), 100 - it)
        }))
        val loaded = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            val (width, height, scale) = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface(Modifier.size(width.dp, height.dp), color = MaterialTheme.colorScheme.background) {
                        // Synthetic status/navigation budgets are excluded before player measurement,
                        // matching the fullscreen host's safeDrawing contract (including landscape).
                        Box(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 24.dp).testTag("viewport")) {
                            NativeNowPlaying(snapshot.value, controls, false, false, false, {}, {}, {}, {},
                                artwork = { _, modifier ->
                                    Box(modifier.testTag("fixture-art").background(MaterialTheme.colorScheme.primaryContainer)) {
                                        if (loaded.value) androidx.compose.material3.Text("♪", style = MaterialTheme.typography.displayLarge)
                                    }
                                }, onPlaybackDevices = { deviceOpens++ })
                        }
                    }
                }
            }
        }
        val cases = listOf(Triple(360, 800, 1f), Triple(320, 640, 1.35f), Triple(320, 640, 2f),
            Triple(320, 360, 1f), Triple(320, 320, 2f), Triple(640, 320, 1f), Triple(640, 320, 2f))
        for (case in cases) {
            compose.runOnIdle { viewport.value = case; loaded.value = false }
            val host = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val player = compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot
            assertEquals("Player fills $case", host, player)
            compose.onAllNodesWithContentDescription("Playback devices").assertCountEquals(1)
            val primaryControls = transportLabels.map { label ->
                val node = compose.onNodeWithContentDescription(label).assertIsDisplayed()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue("48dp target $label at $case", bounds.width >= 48f && bounds.height >= 48f)
                org.junit.Assert.assertTrue("Visible $label at $case: $bounds in $host", host.contains(bounds.topLeft) && host.contains(bounds.bottomRight - Offset(.1f, .1f)))
                bounds
            }
            primaryControls.zipWithNext().forEach { (left, right) ->
                assertTrue("Transport stays Previous / Devices / Play / Next without overlap at $case", left.right <= right.left)
                assertEquals("Transport shares a baseline at $case", left.center.y, right.center.y, .5f)
            }
            compose.onNodeWithContentDescription("Playback devices")
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performTouchInput { click() }
            expectedDeviceOpens++
            val seekBounds = compose.onNodeWithContentDescription("Playback position").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            primaryControls.forEach { bounds ->
                assertFalse("Seek and transport are disjoint at $case", seekBounds.overlaps(bounds))
            }
            for (text in listOf(snapshot.value.item!!.name, snapshot.value.item!!.subtitle)) {
                val node = compose.onNodeWithText(text, useUnmergedTree = true)
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("One inline identity line at $case", 1, layout.lineCount)
                org.junit.Assert.assertTrue("No physical font clipping at $case", layout.size.height >= ceil(layout.multiParagraph.height.toDouble()).toInt())
            }
            val disclosed = case.first < 480 && case.second - 48 < 360
            if (disclosed) {
                compose.onNodeWithTag("now-playing-queue").assertDoesNotExist()
                compose.onNodeWithTag("now-playing-show-queue").assertIsDisplayed()
                    .assertHeightIsEqualTo(48.dp)
                assertEquals("Default short view does not scroll", 0, compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
                // Material Slider expands semantics by the thumb hit extension; require the
                // full content width AND containment, rather than mistaking that extension for
                // a half-width slider or asserting an incorrect exact padded semantics width.
                org.junit.Assert.assertTrue("Full-width seek target at $case: $seekBounds in $host",
                    seekBounds.width >= host.width - 16f && seekBounds.left >= host.left && seekBounds.right <= host.right)
                val timeBottom = compose.onNodeWithText("10:00").fetchSemanticsNode().boundsInRoot.bottom
                val disclosure = compose.onNodeWithTag("now-playing-show-queue").fetchSemanticsNode().boundsInRoot
                primaryControls.forEach { control ->
                    org.junit.Assert.assertTrue("Seek/times cannot overlap transport at $case", control.top >= timeBottom)
                    org.junit.Assert.assertTrue("Queue disclosure cannot overlap transport at $case", disclosure.top >= control.bottom)
                }
                assertSingleLineFits("Up next · 30")
                if (case == Triple(320, 320, 2f)) captureComponent(case)
                compose.onNodeWithTag("now-playing-show-queue").performClick()
                compose.onNodeWithTag("now-playing-queue-view").assertIsDisplayed()
                compose.onNodeWithContentDescription("Playback position").assertDoesNotExist()
                compose.onAllNodesWithContentDescription("Playback devices").assertCountEquals(1)
                compose.onNodeWithContentDescription("Playback devices")
                    .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performTouchInput { click() }
                expectedDeviceOpens++
                assertSingleLineFits("Up next · 30")
            }
            val controlsBefore = transportLabels.map { label ->
                val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue("Disclosed-view 48dp target $label at $case", bounds.width >= 48f && bounds.height >= 48f)
                org.junit.Assert.assertTrue("Disclosed-view control fits $case", host.contains(bounds.topLeft) && host.contains(bounds.bottomRight - Offset(.1f, .1f)))
                bounds
            }
            controlsBefore.zipWithNext().forEach { (left, right) ->
                assertTrue("Disclosed transport keeps Devices immediately before Play at $case", left.right <= right.left)
                assertEquals(left.center.y, right.center.y, .5f)
            }
            val queue = compose.onNodeWithTag("now-playing-queue").assertIsDisplayed()
            val queueBounds = queue.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("A usable independently scrolling queue at $case: $queueBounds", queueBounds.height >= 80f)
            assertEquals("Only the queue scrolls", 1, compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
            queue.performScrollToIndex(29)
            // Reach the real end and release a finger there, rather than only checking entry layout.
            queue.performTouchInput { swipe(Offset(width * .6f, height * .8f), Offset(width * .6f, 2f), 300) }
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            val initialNode = queue.fetchSemanticsNode()
            val range = initialNode.config[SemanticsProperties.VerticalScrollAxisRange]
            val initialOffset = range.value()
            assertEquals("Actually at terminal queue offset at $case", range.maxValue(), initialOffset, .01f)
            val lastAction = compose.onNodeWithContentDescription("Queue actions for Queued song 30")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("A complete last-row action fits at $case: $lastAction in $queueBounds",
                lastAction.height >= 48f && lastAction.top >= queueBounds.top && lastAction.bottom <= queueBounds.bottom)
            val initialLast = compose.onNodeWithContentDescription("Queue actions for Queued song 30")
                .fetchSemanticsNode().positionInRoot
            compose.mainClock.autoAdvance = false
            repeat(60) { frame ->
                compose.runOnIdle {
                    snapshot.value = snapshot.value.copy(positionMs = (frame + 1) * 1_000L)
                    if (frame == 10) loaded.value = true
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals("No spontaneous released-end scroll at $case frame $frame", initialOffset,
                    queue.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
                assertEquals("Queue bounds stable at $case frame $frame", queueBounds, queue.fetchSemanticsNode().boundsInRoot)
                assertEquals("Last occurrence stable at $case frame $frame", initialLast,
                    compose.onNodeWithContentDescription("Queue actions for Queued song 30").fetchSemanticsNode().positionInRoot)
                transportLabels.forEachIndexed { index, label ->
                    assertEquals("Pinned $label at $case frame $frame", controlsBefore[index],
                        compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot)
                }
            }
            compose.mainClock.autoAdvance = true
            queue.performScrollToIndex(0)
            compose.onNodeWithContentDescription("Queue actions for Queued song 1").assertIsDisplayed()
            if (disclosed) {
                assertSingleLineFits("Queued song 1")
                val firstTitle = compose.onNodeWithText("Queued song 1").fetchSemanticsNode().boundsInRoot
                val firstAction = compose.onNodeWithContentDescription("Queue actions for Queued song 1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue("One complete readable queue row at $case", firstTitle.top >= queueBounds.top && firstAction.bottom <= queueBounds.bottom)
                org.junit.Assert.assertTrue("Queue cannot overlap pinned transport at $case", controlsBefore.all { it.top >= queueBounds.bottom })
                if (case == Triple(320, 320, 2f)) captureComponent(case, "-queue")
                compose.onNodeWithContentDescription("Close upcoming queue").performClick()
                compose.onNodeWithTag("now-playing-main-view").assertIsDisplayed()
                compose.onNodeWithTag("now-playing-queue").assertDoesNotExist()
                transportLabels.forEachIndexed { index, label ->
                    assertEquals("Explicit queue close restores full-width primary layout", primaryControls[index],
                        compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot)
                }
            } else if (case == cases.first()) captureComponent(case)
        }
        compose.runOnIdle {
            assertEquals("One callback per intentional device tap in every presentation", expectedDeviceOpens, deviceOpens)
            controls.assertNoCommands()
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun shortLargeFontDisclosureKeepsFailuresVisibleAndBackDismissesQueueBeforePlayer() {
        val controls = RecordingControls()
        val presentation = NowPlayingPresentationState()
        val snapshot = mutableStateOf(PlaybackState(item = track, error = "Playback unavailable", durationMs = 600_000))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface(Modifier.size(320.dp, 320.dp)) {
                        Box(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 24.dp)) {
                            NativeNowPlaying(snapshot.value, controls, false, false, false, {}, {}, {}, {},
                                artwork = { _, modifier -> Box(modifier) }, presentation = presentation)
                        }
                    }
                }
            }
        }
        val initialControls = compose.onNodeWithTag("now-playing-controls").fetchSemanticsNode().boundsInRoot
        val initialSeek = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Playback unavailable").assertIsDisplayed()
        compose.onNodeWithTag("now-playing-queue").assertDoesNotExist()
        captureComponent(Triple(320, 320, 2f), "-error")
        compose.onNodeWithTag("now-playing-show-queue").performClick()
        compose.onNodeWithText("Playback unavailable").assertIsDisplayed()
        compose.onNodeWithText("No upcoming songs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed().assertHeightIsEqualTo(48.dp)
        captureComponent(Triple(320, 320, 2f), "-queue-empty-error")
        // This is the exact presentation-only operation used by Dialog.onDismissRequest;
        // actual native Back/window dispatch remains a future working-AVD gate.
        compose.runOnIdle { org.junit.Assert.assertTrue(presentation.dismissQueue()) }
        compose.onNodeWithTag("now-playing-main-view").assertIsDisplayed()
        compose.onNodeWithText("Playback unavailable").assertIsDisplayed()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(error = null, notice = "Queue update failed") }
        compose.onNodeWithText("Queue update failed").assertIsDisplayed()
        assertEquals("Failure/notice swaps do not move transport", initialControls,
            compose.onNodeWithTag("now-playing-controls").fetchSemanticsNode().boundsInRoot)
        assertEquals("Failure/notice swaps do not move seek", initialSeek,
            compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            org.junit.Assert.assertFalse("Next Back belongs to player dismissal", presentation.dismissQueue())
            assertEquals(0, controls.prepares); assertEquals(0, controls.connects); assertEquals(0, controls.stops)
            assertEquals(0, controls.toggles); assertEquals(0, controls.seeks)
            assertEquals(0, controls.nexts); assertEquals(0, controls.previouses)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun productionDialogBackClosesDisclosedQueueBeforePlayerAndCompactUndoIsReachable() {
        val controls = RecordingControls()
        val expanded = mutableStateOf(true)
        var collapses = 0
        val state = PlaybackState(item = track, durationMs = 600_000, canUndoQueueRemoval = true,
            upcoming = listOf(QueueEntry("queued-occurrence", track.copy(name = "Queued song"), 1)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    if (expanded.value) NativeNowPlayingDialog(onCollapse = { collapses++; expanded.value = false }) { presentation ->
                        Surface(Modifier.size(320.dp, 320.dp)) {
                            Box(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 24.dp)) {
                                NativeNowPlaying(state, controls, false, false, false, {}, {}, {}, {},
                                    artwork = { _, modifier -> Box(modifier) }, presentation = presentation)
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("now-playing-show-queue").performClick()
        compose.onNodeWithTag("now-playing-queue-view").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Upcoming queue actions").performClick()
        compose.onNodeWithText("Undo").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, controls.undos) }
        // Dispatch through the REAL production Compose Dialog's ComponentDialog dispatcher,
        // exercising its registered Back callback and onDismissRequest, not dismissQueue().
        // This is Robolectric, not native AVD/predictive-gesture approval.
        val dialog = compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog }
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("now-playing-main-view").assertIsDisplayed()
        compose.onNodeWithTag("now-playing-queue-view").assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, collapses)
            org.junit.Assert.assertTrue("First Back retains the actual Dialog", dialog.isShowing)
            org.junit.Assert.assertSame(dialog, org.robolectric.shadows.ShadowDialog.getLatestDialog())
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("now-playing").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, collapses)
            org.junit.Assert.assertFalse("Second Back collapses the Dialog", dialog.isShowing)
            assertEquals(0, controls.prepares); assertEquals(0, controls.connects); assertEquals(0, controls.stops)
            assertEquals(0, controls.toggles); assertEquals(0, controls.seeks)
            assertEquals(0, controls.nexts); assertEquals(0, controls.previouses)
            assertEquals(emptyList<String>(), controls.removed)
            assertEquals(emptyList<Pair<String, Int>>(), controls.moves)
        }
    }

    private fun miniSnapshot() = PlaybackState(item = track, currentEntryId = "current-occurrence",
        durationMs = 100_000, interactionKey = PlaybackInteractionKey(), transportInteractionKey = PlaybackInteractionKey(),
        canSkipNext = true, canSkipPrevious = true)

    private fun miniIdentity() = compose.onNodeWithTag("mini-player-identity")
    private fun px(dp: Int) = with(compose.density) { dp.dp.toPx() }
    private fun miniCustomActions(): List<CustomAccessibilityAction> =
        miniIdentity().fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun setMini(initial: PlaybackState = miniSnapshot(), withPlaybackDevices: Boolean = false): MiniFixture {
        val fixture = MiniFixture(initial)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides fixture.layoutDirection.value,
                LocalHapticFeedback provides fixture.haptics) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().testTag("mini-test-host")) {
                        if (fixture.visible.value) NativeMiniPlayer(fixture.snapshot.value, fixture.boundControls.value,
                            { fixture.expands++ }, artwork = { _, modifier ->
                                Box(modifier.testTag("mini-art")) {
                                    if (fixture.artworkLoaded.value) androidx.compose.material3.Text("♪")
                                }
                            }, onPlaybackDevices = if (withPlaybackDevices) ({ fixture.deviceOpens++ }) else null)
                    }
                }
            }
        }
        return fixture
    }

    private inner class MiniFixture(initial: PlaybackState) {
        val snapshot = mutableStateOf(initial)
        val visible = mutableStateOf(true)
        val artworkLoaded = mutableStateOf(false)
        val layoutDirection = mutableStateOf(LayoutDirection.Ltr)
        val controls = RecordingControls().apply { state.value = initial }
        val boundControls = mutableStateOf(controls)
        var expands = 0
        var deviceOpens = 0
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback += hapticFeedbackType }
        }

        fun update(block: () -> Unit) {
            compose.runOnIdle {
                block()
                controls.state.value = snapshot.value
                boundControls.value.state.value = snapshot.value
            }
            compose.waitForIdle()
        }

        fun assertCancelled() = compose.runOnIdle {
            assertTrue(controls.keyedNext.isEmpty())
            assertTrue(controls.keyedPrevious.isEmpty())
            assertEquals(0, controls.nexts)
            assertEquals(0, controls.previouses)
            assertEquals(0, expands)
            assertTrue("Cancelled/rejected work must not emit success haptics", feedback.isEmpty())
        }

        fun assertPresentationOnly() = compose.runOnIdle {
            assertEquals(0, controls.prepares)
            assertEquals(0, controls.connects)
            assertEquals(0, controls.stops)
            assertEquals(0, controls.seeks)
        }
    }

    private fun assertSingleLineFits(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("One readable line: $text", 1, layout.lineCount)
        org.junit.Assert.assertFalse("No ellipsis for essential short label: $text", layout.isLineEllipsized(0))
        org.junit.Assert.assertTrue("Font height fits: $text", layout.size.height >= ceil(layout.multiParagraph.height.toDouble()).toInt())
    }

    /** Bounded development aid only; NOT AVD/native-runtime capture or visual acceptance. */
    private fun captureComponent(case: Triple<Int, Int, Float>, suffix: String = "") {
        val renderDir = System.getenv("SPOFIFY_PLAYER_RENDER_DIR") ?: return
        runCatching {
            val directory = File(renderDir).apply { mkdirs() }
            val file = File(directory, "robolectric-component-${case.first}x${case.second}-font${case.third}$suffix.png")
            val bitmap = compose.onNodeWithTag("now-playing").captureToImage().asAndroidBitmap()
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            println("Robolectric native-graphics component render, NOT AVD/native-runtime validation: ${file.absolutePath}")
        }.onFailure { println("Robolectric component capture unsupported/failed: $it") }
    }

    @Test fun albumIdentityWithoutDisplayNameRemainsNavigable() {
        val opened = mutableListOf<LibraryItem>()
        setNowPlaying(PlaybackState(item = track.copy(albumName = null)), RecordingControls(), onOpenItem = { opened += it })
        compose.onNodeWithContentDescription("Now Playing actions").performClick()
        compose.onNodeWithText("Open album").performClick()
        compose.runOnIdle { assertEquals(listOf("album"), opened.map { it.id }); assertEquals(listOf("album"), opened.map { it.kind }) }
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
        var acceptKeyedTransport = true
        val keyedAttempts = mutableListOf<Pair<String, PlaybackInteractionKey>>()
        val keyedNext = mutableListOf<PlaybackInteractionKey>()
        val keyedPrevious = mutableListOf<PlaybackInteractionKey>()
        var seeks = 0
        var shuffles = 0
        var repeats = 0
        var undos = 0
        var clears = 0
        val moves = mutableListOf<Pair<String, Int>>()
        val guardedMoves = mutableListOf<Triple<PlaybackInteractionKey, String, Int>>()
        val playNexts = mutableListOf<Pair<PlaybackInteractionKey, String>>()
        val queueIntents = mutableListOf<Triple<PlaybackInteractionKey, String, dev.spofify.mobile.playback.PlaybackListAction>>()
        val removed = mutableListOf<String>()
        val selected = mutableListOf<String>()
        val selectedIndices = mutableListOf<Int>()
        fun assertNoCommands() {
            assertEquals(0, prepares); assertEquals(0, connects); assertEquals(0, stops)
            assertEquals(0, toggles); assertEquals(0, nexts); assertEquals(0, previouses)
            assertEquals(0, seeks); assertEquals(0, shuffles); assertEquals(0, repeats)
            assertEquals(0, undos); assertEquals(0, clears)
            assertTrue(keyedAttempts.isEmpty()); assertTrue(moves.isEmpty()); assertTrue(guardedMoves.isEmpty())
            assertTrue(queueIntents.isEmpty()); assertTrue(playNexts.isEmpty()); assertTrue(removed.isEmpty()); assertTrue(selected.isEmpty())
            assertTrue(selectedIndices.isEmpty())
        }

        override fun connect() { connects++ }
        override fun release() = Unit
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?,
            shuffle: Boolean, startEntryId: String?) { prepares++ }
        override fun toggle() { toggles++ }
        override fun next() { nexts++ }
        override fun previous() { previouses++ }
        override fun next(expectedKey: PlaybackInteractionKey): Boolean {
            keyedAttempts += "next" to expectedKey
            if (!acceptKeyedTransport || state.value.transportInteractionKey != expectedKey || !state.value.canSkipNext) return false
            keyedNext += expectedKey
            return true
        }
        override fun previous(expectedKey: PlaybackInteractionKey): Boolean {
            keyedAttempts += "previous" to expectedKey
            if (!acceptKeyedTransport || state.value.transportInteractionKey != expectedKey || !state.value.canSkipPrevious) return false
            keyedPrevious += expectedKey
            return true
        }
        override fun select(index: Int) { selectedIndices += index }
        override fun seekTo(positionMs: Long) { seeks++ }
        override fun toggleShuffle() { shuffles++ }
        override fun cycleRepeat() { repeats++ }
        override fun stop() { stops++ }
        override fun moveUpcoming(occurrenceId: String, toIndex: Int) { moves += occurrenceId to toIndex }
        override fun moveUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, toIndex: Int) {
            guardedMoves += Triple(expectedKey, occurrenceId, toIndex)
            moves += occurrenceId to toIndex
        }
        override fun queueIntentOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
            action: dev.spofify.mobile.playback.PlaybackListAction, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            org.junit.Assert.assertTrue("Raw queue intent retains the originating host guard", isCurrent())
            queueIntents += Triple(expectedKey, occurrenceId, action)
            onResult(PlaybackActionResult(PlaybackActionStatus.NO_OP))
        }
        override fun playNextOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
            isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            org.junit.Assert.assertTrue("Play next retains the originating row guard", isCurrent())
            playNexts += expectedKey to occurrenceId
            onResult(PlaybackActionResult(PlaybackActionStatus.NO_OP))
        }
        override fun removeUpcoming(occurrenceId: String) { removed += occurrenceId }
        override fun removeUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, isCurrent: () -> Boolean) {
            if (isCurrent()) removed += occurrenceId
        }
        override fun selectOccurrence(occurrenceId: String) { selected += occurrenceId }
        override fun undoQueueRemoval() { undos++ }
        override fun clearUpcoming() { clears++ }
    }
}
