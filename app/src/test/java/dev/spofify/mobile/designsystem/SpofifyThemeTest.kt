package dev.spofify.mobile.designsystem

import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SpofifyThemeTest {
    private val tokens = xml("values/theme_colors.xml").elements("color").associate {
        it.getAttribute("name") to it.textContent.lowercase()
    }
    private val tokenIds = mapOf(
        R.color.theme_canvas to "theme_canvas",
        R.color.theme_chrome to "theme_chrome",
        R.color.theme_panel to "theme_panel",
        R.color.theme_raised to "theme_raised",
        R.color.theme_divider to "theme_divider",
        R.color.theme_text to "theme_text",
        R.color.theme_muted to "theme_muted",
        R.color.theme_subtle to "theme_subtle",
        R.color.theme_primary to "theme_primary",
        R.color.theme_on_primary to "theme_on_primary",
        R.color.theme_tertiary to "theme_tertiary",
        R.color.theme_error to "theme_error",
    )

    @Test fun fixedThemeMatchesSharedRoleContract() {
        assertEquals(mapOf(
            "theme_canvas" to "#131617", "theme_chrome" to "#191d1e", "theme_panel" to "#1e2324",
            "theme_raised" to "#293032", "theme_divider" to "#303638",
            "theme_text" to "#f4f6fa", "theme_muted" to "#b4bdcb", "theme_subtle" to "#9ca9ba",
            "theme_primary" to "#66e3b4", "theme_on_primary" to "#08271d",
            "theme_tertiary" to "#dfbd78", "theme_error" to "#ffa2ac",
        ), tokens)
        val colors = spofifyColorScheme(::color)
        assertEquals(tokenIds.keys.map(::color), listOf(
            colors.background, colors.surfaceContainerLow, colors.surface, colors.surfaceVariant, colors.outlineVariant,
            colors.onSurface, colors.onSurfaceVariant, colors.outline,
            colors.primary, colors.onPrimary, colors.tertiary, colors.error,
        ))
    }

    @Test fun materialFallbacksAreNeutralOrSharePrimaryAndMaintainContrast() {
        val colors = spofifyColorScheme(::color)
        assertEquals(colors.primary, colors.secondary)
        assertEquals(colors.onPrimary, colors.onSecondary)
        assertEquals(colors.primary, colors.secondaryFixed)
        assertEquals(colors.primary, colors.secondaryFixedDim)
        assertEquals(colors.onPrimary, colors.onSecondaryFixed)
        assertEquals(colors.onPrimary, colors.onSecondaryFixedVariant)
        assertEquals(colors.surfaceVariant, colors.primaryContainer)
        assertEquals(colors.primaryContainer, colors.secondaryContainer)
        assertEquals(colors.primary, colors.onSecondaryContainer)
        assertEquals(colors.surfaceVariant, colors.surfaceContainerHigh)
        assertEquals(colors.surface, colors.surfaceColorAtElevation(6.dp))
        assertContrast(colors.onPrimary, colors.primary)
        for (surface in listOf(colors.background, colors.surfaceContainerLow, colors.surface, colors.surfaceVariant)) {
            listOf(colors.onSurface, colors.onSurfaceVariant, colors.outline, colors.primary, colors.tertiary, colors.error)
                .forEach { assertContrast(it, surface) }
        }
    }

    @Test fun splashAndLauncherShareTheTransparentDesktopMark() {
        val mark = xml("drawable/ic_spofify_mark.xml")
        assertEquals("vector", mark.tagName)
        assertEquals("108", mark.getAttribute("android:viewportWidth"))
        assertEquals("108", mark.getAttribute("android:viewportHeight"))
        val path = mark.elements("*").single()
        assertEquals("path", path.tagName)
        assertEquals("M28,38h8v32h-8zM43,25h8v58h-8zM58,34h8v40h-8zM73,43h8v22h-8z", path.getAttribute("android:pathData"))
        assertEquals("@color/theme_primary", path.getAttribute("android:fillColor"))
        val launcher = xml("mipmap-anydpi/ic_launcher.xml")
        assertEquals("@color/theme_chrome", launcher.elements("background").single().getAttribute("android:drawable"))
        assertEquals("@drawable/ic_spofify_mark", launcher.elements("foreground").single().getAttribute("android:drawable"))
        val startup = xml("values/styles.xml").items()
        for (name in listOf("windowBackground", "statusBarColor", "navigationBarColor")) {
            assertEquals("@color/theme_canvas", startup["android:$name"])
        }
        assertEquals("false", startup["android:windowLightStatusBar"])
        val splash = xml("values-v31/styles.xml").items()
        assertEquals("false", xml("values-v27/styles.xml").items()["android:windowLightNavigationBar"])
        assertEquals("false", splash["android:windowLightNavigationBar"])
        assertEquals("@color/theme_canvas", splash["android:windowSplashScreenBackground"])
        assertEquals("@drawable/ic_spofify_mark", splash["android:windowSplashScreenAnimatedIcon"])
    }

    private fun color(id: Int) = Color(0xff000000L or tokens.getValue(tokenIds.getValue(id)).drop(1).toLong(16))

    private fun assertContrast(foreground: Color, background: Color) {
        val (dark, light) = listOf(foreground.luminance(), background.luminance()).sorted()
        assertTrue("Insufficient contrast between $foreground and $background", (light + .05f) / (dark + .05f) >= 4.5f)
    }

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res", path)).documentElement

    private fun Element.elements(tag: String): List<Element> = getElementsByTagName(tag).let { nodes ->
        List(nodes.length) { nodes.item(it) as Element }
    }

    private fun Element.items() = elements("item").associate { it.getAttribute("name") to it.textContent }
}
