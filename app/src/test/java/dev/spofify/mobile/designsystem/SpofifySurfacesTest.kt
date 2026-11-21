package dev.spofify.mobile.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class SpofifySurfacesTest {
    @Test fun cornersShareDesktopLogicalUnitsWithoutRoundingTheDockedSheetEdge() {
        for (density in listOf(1f, 2.5f)) {
            assertCorners(SpofifySurfaces.control, density, listOf(8f, 8f, 8f, 8f))
            assertCorners(SpofifySurfaces.artwork, density, listOf(8f, 8f, 8f, 8f))
            assertCorners(SpofifySurfaces.panel, density, listOf(12f, 12f, 12f, 12f))
            assertCorners(SpofifySurfaces.sheet, density, listOf(12f, 12f, 0f, 0f))
        }
    }

    private fun assertCorners(shape: RoundedCornerShape, density: Float, expectedDp: List<Float>) {
        val corners = listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart)
        assertEquals(expectedDp.map { it * density }, corners.map { it.toPx(Size(120f, 96f), Density(density)) })
    }
}
