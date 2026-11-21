package dev.spofify.mobile.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SpofifyGlyphTest {
    @Test fun sharedVectorsRetain24DpAndDesktopPaint() {
        val ink = SolidColor(Color.Black)
        val filled = setOf(SpofifyGlyph.More, SpofifyGlyph.Play, SpofifyGlyph.Previous, SpofifyGlyph.Next)
        for (glyph in SpofifyGlyph.entries) {
            val vector = glyph.vector
            assertEquals(glyph.name, 24.dp, vector.defaultWidth)
            assertEquals(glyph.name, 24.dp, vector.defaultHeight)
            assertEquals(glyph.name, 24f, vector.viewportWidth, 0f)
            assertEquals(glyph.name, 24f, vector.viewportHeight, 0f)
            val strokeWidth = when (glyph) {
                SpofifyGlyph.More, SpofifyGlyph.Play -> 0f
                SpofifyGlyph.Pause -> 4f
                else -> 1.7f
            }
            val path = pathOf(glyph)
            assertEquals(glyph.name, ink.takeIf { glyph in filled }, path.fill)
            assertEquals(glyph.name, ink.takeIf { strokeWidth > 0f }, path.stroke)
            assertEquals(glyph.name, strokeWidth, path.strokeLineWidth, 0f)
            assertEquals(glyph.name, StrokeCap.Round, path.strokeLineCap)
            assertEquals(glyph.name, StrokeJoin.Round, path.strokeLineJoin)
        }
    }

    // Desktop assets/icons at 551e137bc90c8ae593386beae657f86f9836bfba.
    @Test fun moreMatchesThreeHorizontalCircles() {
        val dots = listOf(4, 12, 20).joinToString("") { x -> "M${x - 2} 12a2 2 0 0 1 4 0a2 2 0 0 1-4 0z" }
        assertPath(SpofifyGlyph.More, dots)
    }

    @Test fun transportAndQueueMatchDesktopPaths() {
        assertPath(SpofifyGlyph.Play, "M8 5v14l11-7z")
        assertPath(SpofifyGlyph.Pause, "M8 5v14m8-14v14")
        assertPath(SpofifyGlyph.Previous, "M7 5v14m12-14L9 12l10 7z")
        assertPath(SpofifyGlyph.Next, "M17 5v14M5 5l10 7-10 7z")
        assertPath(SpofifyGlyph.Queue, "M3 6h17M3 12h10M3 18h7m6-7v9l6-4.5z")
    }

    @Test fun playlistsKeepsItsNativePath() {
        assertPath(SpofifyGlyph.Playlists, "M4 5h16M4 11h16M4 17h10")
    }

    private fun assertPath(glyph: SpofifyGlyph, expected: String) {
        assertEquals(glyph.name, PathParser().parsePathString(expected).toNodes(), pathOf(glyph).pathData)
    }

    private fun pathOf(glyph: SpofifyGlyph): VectorPath {
        val root = glyph.vector.root
        assertEquals(glyph.name, 1, root.size)
        return root[0] as VectorPath
    }
}
