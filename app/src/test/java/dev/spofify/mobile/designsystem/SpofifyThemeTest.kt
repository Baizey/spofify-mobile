package dev.spofify.mobile.designsystem

import androidx.compose.ui.graphics.Color
import dev.spofify.mobile.core.palette.PaletteTokens
import org.junit.Assert.*
import org.junit.Test

class SpofifyThemeTest {
    @Test fun modeChoosesTheReferenceNeutralSurfaces() {
        val light = spofifyColorScheme(true)
        val dark = spofifyColorScheme(false)
        assertEquals(Color(0xFFF8FAFC), light.background)
        assertEquals(Color(0xFF1B2636), light.onBackground)
        assertEquals(Color(0xFFEDF1F5), light.surface)
        assertEquals(Color(0xFF101216), dark.background)
        assertEquals(Color(0xFFF4F6FA), dark.onBackground)
    }

    @Test fun accountPaletteOnlyChangesItsFourOwnedSemanticTokens() {
        val tokens = PaletteTokens("#ffad89", "#31170d", "#c4b0ff", "#a4d7bd")
        for (light in listOf(false, true)) {
            val base = spofifyColorScheme(light)
            val selected = spofifyColorScheme(light, tokens)
            assertEquals(paletteColor(tokens.primary), selected.primary)
            assertEquals(paletteColor(tokens.onPrimary), selected.onPrimary)
            assertEquals(paletteColor(tokens.secondary), selected.secondary)
            assertEquals(paletteColor(tokens.tertiary), selected.tertiary)
            assertEquals(base.background, selected.background)
            assertEquals(base.surface, selected.surface)
            assertEquals(base.onSurface, selected.onSurface)
            assertEquals(base.outline, selected.outline)
        }
    }
}
