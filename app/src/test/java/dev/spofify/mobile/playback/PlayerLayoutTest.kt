package dev.spofify.mobile.playback

import dev.spofify.mobile.core.content.LyricLine
import dev.spofify.mobile.core.content.LyricsState
import dev.spofify.mobile.core.content.TrackLyrics
import org.junit.Assert.*
import org.junit.Test

class PlayerLayoutTest {
    @Test fun tallLayoutCapsArtworkAndAssignsOnlyTheResidualGapToLyrics() {
        val layout = budget(height = 760f)
        assertEquals(330f, layout.artwork, .01f)
        assertEquals(134f, layout.gap, .01f)
        assertEquals(760f, layout.contentHeight, .01f)
        assertFalse(layout.wholePlayerScrolls)
    }

    @Test fun shorterLayoutShrinksArtworkBeforeMovingTheMeasuredTransportOffscreen() {
        val layout = budget(height = 680f)
        assertEquals(280f, layout.artwork, .01f)
        assertEquals(104f, layout.gap, .01f)
        assertEquals(680f, layout.contentHeight, .01f)
        assertFalse(layout.wholePlayerScrolls)
    }

    @Test fun veryShortLayoutScrollsAsAWholeWithoutCollapsingArtworkOrTheLyricViewport() {
        val layout = budget(height = 500f)
        assertEquals(164f, layout.artwork, .01f)
        assertEquals(104f, layout.gap, .01f)
        assertEquals(564f, layout.contentHeight, .01f)
        assertTrue(layout.wholePlayerScrolls)
    }

    @Test fun measuredAlbumHeaderAndWrappedIdentityAreIncludedRatherThanOverlappingControls() {
        val normal = budget(height = 760f)
        val largerHeader = budget(height = 760f, header = 100f)
        assertEquals(330f, normal.artwork, .01f)
        assertEquals(308f, largerHeader.artwork, .01f)
        assertEquals(104f, largerHeader.gap, .01f)
        val largerControls = budget(height = 760f, controls = 360f)
        assertEquals(240f, largerControls.artwork, .01f)
        assertEquals(760f, largerControls.contentHeight, .01f)
        assertFalse(largerControls.wholePlayerScrolls)
    }

    @Test fun largeTextUsesThe164CapAndKeepsTheNativeScaledLineAndCueBudget() {
        val layout = budget(height = 760f, largeText = true, controls = 320f,
            lineHeight = 80.52f, cueHeight = 30.8f)
        assertEquals(164f, layout.artwork, .01f)
        assertEquals(220f, layout.gap, .01f)
        assertFalse(layout.wholePlayerScrolls)
        val short = budget(height = 500f, largeText = true, controls = 360f,
            lineHeight = 80.52f, cueHeight = 30.8f)
        assertTrue(short.wholePlayerScrolls)
        assertEquals(80.52f + 24f + 30.8f, short.gap - 8f, .01f)
    }

    @Test fun narrowScreensNeverProduceArtworkWiderThanTheAvailableContent() {
        val layout = budget(width = 120f, height = 500f)
        assertEquals(120f, layout.artwork, .01f)
        assertTrue(layout.wholePlayerScrolls)
        assertEquals(48f + 8f + 120f + layout.gap + 240f, layout.contentHeight, .01f)
    }

    @Test fun noTimedLyricsNeedsOnlyBreathingSpaceButTimedSilentGapsKeepTheSameBudget() {
        val without = budget(height = 680f, reserveLyrics = false)
        assertEquals(330f, without.artwork, .01f)
        assertEquals(54f, without.gap, .01f)
        val timedLine = budget(height = 680f, reserveLyrics = true)
        val timedSilentGap = budget(height = 680f, reserveLyrics = true)
        assertEquals(timedLine, timedSilentGap)
    }

    @Test fun overflowCueReflectsActualScrollRangeIncludingBothEnds() {
        assertEquals(LyricOverflow(false, false), lyricOverflow(0, 0))
        assertEquals(LyricOverflow(false, true), lyricOverflow(0, 400))
        assertEquals(LyricOverflow(true, true), lyricOverflow(200, 400))
        assertEquals(LyricOverflow(true, false), lyricOverflow(400, 400))
        assertEquals(LyricOverflow(false, true), lyricOverflow(-10, 400))
        assertEquals(LyricOverflow(true, false), lyricOverflow(900, 400))
    }

    @Test fun progressTicksAndSeekingWithinTheSameTimedLineDoNotResetItsViewport() {
        val content = timedContent()
        val initial = lyricViewportKey(content, 1_000, 60_000)
        assertNotNull(initial)
        assertEquals(initial, lyricViewportKey(content, 7_000, 60_000))
        assertEquals(initial, lyricViewportKey(content, 2_000, 60_000))
    }

    @Test fun repeatedWordsAtANewTimedIndexStillResetTheViewport() {
        val content = timedContent()
        val first = lyricViewportKey(content, 1_000, 60_000)!!
        val second = lyricViewportKey(content, 11_000, 60_000)!!
        assertEquals(first.text, second.text)
        assertNotEquals(first, second)
        assertNotEquals(first.index, second.index)
    }

    @Test fun occurrenceTrackOrContentVersionChangesResetTheViewport() {
        val content = timedContent()
        val initial = lyricViewportKey(content, 1_000, 60_000)
        assertNotEquals(initial, lyricViewportKey(content.copy(binding = "new occurrence"), 1_000, 60_000))
        assertNotEquals(initial, lyricViewportKey(content.copy(trackId = "other track"), 1_000, 60_000))
        assertNotEquals(initial, lyricViewportKey(content.copy(lyrics = content.lyrics!!.copy(recordingVersion = "new recording")), 1_000, 60_000))
        assertNotEquals(initial, lyricViewportKey(content.copy(lyrics = content.lyrics!!.copy(sourceGeneration = "new source")), 1_000, 60_000))
    }

    @Test fun silentClearingLinesAndUnconfirmedDurationDoNotInventInlineLyrics() {
        val content = timedContent()
        assertNull(lyricViewportKey(content, 1_000, 0))
        assertNull(lyricViewportKey(content, 21_000, 60_000))
        assertNull(lyricViewportKey(content.copy(lyrics = content.lyrics!!.copy(state = LyricsState.PLAIN)), 1_000, 60_000))
        assertNull(lyricViewportKey(TrackContentState(), 1_000, 60_000))
    }

    @Test fun longMultilingualTextIsPreservedInFullRatherThanShortenedForTheViewport() {
        val text = "窓辺の青い紙 — A line across the quiet room — نترك مساحة صغيرة للضوء\n".repeat(100)
        val content = timedContent().let { it.copy(lyrics = it.lyrics!!.copy(lines = listOf(LyricLine(0, text)))) }
        assertEquals(text, lyricViewportKey(content, 1_000, 60_000)?.text)
    }

    private fun budget(width: Float = 360f, height: Float, header: Float = 48f, controls: Float = 240f,
        largeText: Boolean = false, reserveLyrics: Boolean = true, lineHeight: Float = 40.26f, cueHeight: Float = 20f) =
        playerLayoutBudget(width, height, header, controls, largeText, reserveLyrics, lineHeight, cueHeight)

    private fun timedContent() = TrackContentState(trackId = "track", binding = "occurrence:output", lyrics = TrackLyrics(
        "track", LyricsState.TIMED, "recording", "source", lines = listOf(
            LyricLine(0, "Repeated chorus"), LyricLine(10_000, "Repeated chorus"), LyricLine(20_000, ""))))
}
