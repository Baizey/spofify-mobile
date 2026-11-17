package dev.spofify.mobile.designsystem

import androidx.compose.ui.graphics.Color
import dev.spofify.mobile.core.palette.PaletteTokens
import org.junit.Assert.*
import org.junit.Test

class SpofifyThemeTest {
    @Test fun baseUsesTheReferenceDarkSurfaces() {
        val base = spofifyColorScheme()
        assertEquals(Color(0xFF101216), base.background)
        assertEquals(Color(0xFFF4F6FA), base.onBackground)
        assertEquals(Color(0xFF1A1E24), base.surface)
    }

    @Test fun accountPaletteOnlyChangesItsFourOwnedSemanticTokens() {
        val tokens = PaletteTokens("#ffad89", "#31170d", "#c4b0ff", "#a4d7bd")
        val base = spofifyColorScheme()
        val selected = spofifyColorScheme(tokens)
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
