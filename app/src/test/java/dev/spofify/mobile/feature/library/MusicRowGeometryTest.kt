package dev.spofify.mobile.feature.library

import org.junit.Assert.*
import org.junit.Test

class MusicRowGeometryTest {
    @Test fun ordinaryRowsKeepArtworkAndMoreInFull48DpColumnsInsideTheGutters() {
        val row = MusicRowGeometry()
        assertEquals(20, row.artworkStart)
        assertEquals(48, MusicRowGeometry.ARTWORK_SLOT)
        assertEquals(48, MusicRowGeometry.ACTION_SLOT)
        assertEquals(344, row.moreStart(412))
        assertEquals(263, row.identityWidth(412))
        assertEquals(72, MusicRowGeometry.MIN_HEIGHT)
    }

    @Test fun albumNumberAddsExactly22Plus10BeforeArtworkWithoutMovingMore() {
        val numbered = MusicRowGeometry(numbered = true)
        assertEquals(32, numbered.leadingWidth)
        assertEquals(52, numbered.artworkStart)
        assertEquals(10, numbered.identityGap)
        assertEquals(MusicRowGeometry().moreStart(412), numbered.moreStart(412))
        assertEquals(210, numbered.identityWidth(412, reservePopularity = true))
    }

    @Test fun controlsInsideAnAlreadyPaddedHeadingAlignWithFullWidthRows() {
        for (width in listOf(240, 320, 412, 600)) for (numbered in listOf(false, true)) {
            val row = MusicRowGeometry(numbered)
            val controls = MusicRowGeometry(numbered, gutter = 0)
            val headingWidth = width - 2 * MusicRowGeometry.EDGE_GUTTER
            assertEquals(row.artworkStart, MusicRowGeometry.EDGE_GUTTER + controls.artworkStart)
            assertEquals(row.moreStart(width), MusicRowGeometry.EDGE_GUTTER + controls.moreStart(headingWidth))
        }
    }

    @Test fun fullWidthPlaylistControlsAndRowsUseIdenticalColumns() {
        val row = MusicRowGeometry()
        val controls = MusicRowGeometry()
        for (width in listOf(240, 320, 412, 600)) {
            assertEquals(row.artworkStart, controls.artworkStart)
            assertEquals(row.moreStart(width), controls.moreStart(width))
            assertEquals(20, width - controls.moreStart(width) - MusicRowGeometry.ACTION_SLOT)
        }
    }

    @Test fun swipeExcludesTheWholeMoreTargetAndEndGutterNotOnlyTheOld48DpTail() {
        val row = MusicRowGeometry()
        assertEquals(68, row.swipeEndExclusion(hasMore = true))
        for (width in listOf(240, 320, 412, 600)) {
            assertEquals(row.moreStart(width), width - row.swipeEndExclusion(hasMore = true))
        }
        assertEquals(0, row.swipeEndExclusion(hasMore = false))
    }

    @Test fun popularityReservationUsesIdentitySpaceAndNeverShrinksTargets() {
        val row = MusicRowGeometry(numbered = true)
        for (width in listOf(240, 320, 412, 600)) {
            assertEquals(24, row.identityWidth(width) - row.identityWidth(width, reservePopularity = true))
            assertTrue(row.artworkStart + MusicRowGeometry.ARTWORK_SLOT <= row.moreStart(width))
        }
        assertEquals(0, row.identityWidth(120, reservePopularity = true))
        assertEquals(48, MusicRowGeometry.ARTWORK_SLOT)
        assertEquals(48, MusicRowGeometry.ACTION_SLOT)
    }
}
