package dev.spofify.mobile.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

internal data class PlayerLayoutBudget(
    val artwork: Float,
    val gap: Float,
    val contentHeight: Float,
    val wholePlayerScrolls: Boolean,
)

/** Logical dp. Text metrics are supplied after native font scaling, not estimated from character counts. */
internal fun playerLayoutBudget(
    width: Float,
    height: Float,
    header: Float,
    controls: Float,
    largeText: Boolean,
    reserveLyrics: Boolean,
    lyricLineHeight: Float,
    lyricCueHeight: Float,
): PlayerLayoutBudget {
    require(listOf(width, height, header, controls, lyricLineHeight, lyricCueHeight).all { it.isFinite() && it >= 0f })
    val artCap = if (largeText) 164f else 330f
    val artFloor = min(width, 164f)
    // Keep one full scaled line, padding and the overflow cue accessible in the fallback viewport.
    val minimumGap = if (reserveLyrics) max(96f, lyricLineHeight + 24f + lyricCueHeight) + 8f else 16f
    val fixed = header + 8f + controls
    val artwork = min(min(width, artCap), max(artFloor, height - fixed - minimumGap))
    val gap = max(minimumGap, height - fixed - artwork)
    val content = fixed + artwork + gap
    return PlayerLayoutBudget(artwork, gap, content, content > height + .01f)
}

/** Measures header/controls first in the same layout pass; lyric length never pushes transport down. */
@Composable
internal fun PlayerBodyLayout(
    availableHeight: Dp,
    largeText: Boolean,
    reserveLyrics: Boolean,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    artwork: @Composable (Dp) -> Unit,
    gap: @Composable (Dp) -> Unit,
    controls: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val natural = Constraints(minWidth = width, maxWidth = width, maxHeight = Constraints.Infinity)
        val headerPlaceable = subcompose("header") { Box(Modifier.fillMaxWidth()) { header() } }.single().measure(natural)
        val controlsPlaceable = subcompose("controls") { Box(Modifier.fillMaxWidth()) { controls() } }.single().measure(natural)
        val cueHeight = maxOf(20.dp, 15.4.sp.toDp())
        val budget = playerLayoutBudget(width.toDp().value, availableHeight.value,
            headerPlaceable.height.toDp().value, controlsPlaceable.height.toDp().value,
            largeText, reserveLyrics, playerLyricLineHeight.toDp().value, cueHeight.value)
        val artPixels = budget.artwork.dp.roundToPx()
        val headerGapPixels = 8.dp.roundToPx()
        // Use the exact pixel remainder in a fitted layout; rounding must not create a one-pixel scroll.
        val gapPixels = if (budget.wholePlayerScrolls) budget.gap.dp.roundToPx() else
            (availableHeight.roundToPx() - headerPlaceable.height - controlsPlaceable.height - headerGapPixels - artPixels).coerceAtLeast(0)
        val art = subcompose("artwork") { artwork(artPixels.toDp()) }.single()
            .measure(Constraints.fixed(artPixels, artPixels))
        val gapPlaceable = subcompose("gap") { Box(Modifier.fillMaxWidth()) { gap(gapPixels.toDp()) } }.single()
            .measure(Constraints.fixed(width, gapPixels))
        val total = headerPlaceable.height + headerGapPixels + artPixels + gapPixels + controlsPlaceable.height
        layout(width, total) {
            headerPlaceable.placeRelative(0, 0)
            val artTop = headerPlaceable.height + headerGapPixels
            art.placeRelative((width - artPixels) / 2, artTop)
            gapPlaceable.placeRelative(0, artTop + artPixels)
            controlsPlaceable.placeRelative(0, artTop + artPixels + gapPixels)
        }
    }
}
