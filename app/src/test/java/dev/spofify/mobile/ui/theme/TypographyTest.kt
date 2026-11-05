package dev.spofify.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import dev.spofify.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TypographyTest {
    @Test
    fun allFifteenMaterialStylesUseTheBundledFamily() {
        val styles = AppTypography.styles()
        assertEquals(15, styles.size)
        styles.forEach { (name, style) ->
            assertSame(name, JetBrainsMono, style.fontFamily)
        }
    }

    @Test
    fun compactScaleHasIntentionalSizesAndComfortableLineHeights() {
        // font size / line height in sp; small captions and labels are not density shortcuts.
        val expected = linkedMapOf(
            "displayLarge" to (57 to 64),
            "displayMedium" to (45 to 52),
            "displaySmall" to (36 to 44),
            "headlineLarge" to (30 to 38),
            "headlineMedium" to (26 to 34),
            "headlineSmall" to (22 to 30),
            "titleLarge" to (20 to 26),
            "titleMedium" to (14 to 22),
            "titleSmall" to (13 to 18),
            "bodyLarge" to (14 to 22),
            "bodyMedium" to (13 to 20),
            "bodySmall" to (12 to 16),
            "labelLarge" to (14 to 20),
            "labelMedium" to (12 to 16),
            "labelSmall" to (11 to 16),
        )
        val actual = AppTypography.styles()
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, dimensions) ->
            val style = actual.getValue(name)
            assertEquals("$name font size", dimensions.first.sp, style.fontSize)
            assertEquals("$name line height", dimensions.second.sp, style.lineHeight)
            assertTrue("$name has glyph breathing room", style.lineHeight > style.fontSize)
        }
    }

    @Test
    fun materialWeightsTrackingAndShapingRemainUnchanged() {
        val defaults = Typography().styles()
        val actual = AppTypography.styles()
        assertEquals(defaults.keys, actual.keys)
        defaults.forEach { (name, style) ->
            val compact = actual.getValue(name)
            // Only family, size and line height change; preserve weight and Android fallback/shaping.
            assertEquals(
                name,
                style.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = compact.fontSize,
                    lineHeight = compact.lineHeight,
                ),
                compact,
            )
        }
    }

    @Test
    fun everyFontSizeAndLineHeightUsesScaledSp() {
        val normal = Density(density = 1f, fontScale = 1f)
        val enlarged = Density(density = 1f, fontScale = 2f)
        AppTypography.styles().forEach { (name, style) ->
            listOf("font size" to style.fontSize, "line height" to style.lineHeight).forEach { (dimension, value) ->
                assertEquals("$name $dimension uses sp", TextUnitType.Sp, value.type)
                val normalPx = with(normal) { value.toPx() }
                val enlargedPx = with(enlarged) { value.toPx() }
                assertEquals("$name $dimension at default scale", value.value, normalPx, 0.001f)
                // Android 14+ scales large roles nonlinearly; font2 is not always 2x pixels.
                assertTrue("$name $dimension responds to font scale", enlargedPx > normalPx)
                assertTrue("$name $dimension respects the platform scale", enlargedPx <= normalPx * 2f + 0.001f)
                assertEquals(
                    "$name $dimension preserves its sp value through conversion",
                    value.value,
                    with(enlarged) { enlargedPx.toSp().value },
                    0.001f,
                )
            }
        }
    }

    @Test
    fun normalMediumSemiboldAndBoldMapToBundledNormalStaticFaces() {
        val resources = RuntimeEnvironment.getApplication().resources
        val faces = listOf(
            Triple(R.font.jetbrains_mono_regular, "jetbrains_mono_regular", FontWeight.Normal),
            Triple(R.font.jetbrains_mono_medium, "jetbrains_mono_medium", FontWeight.Medium),
            Triple(R.font.jetbrains_mono_semibold, "jetbrains_mono_semibold", FontWeight.SemiBold),
            Triple(R.font.jetbrains_mono_bold, "jetbrains_mono_bold", FontWeight.Bold),
        )
        assertTrue(JetBrainsMono is FontListFontFamily)
        val family = JetBrainsMono as FontListFontFamily
        assertEquals(4, family.size)
        faces.forEachIndexed { index, (resource, name, weight) ->
            assertEquals(name, resources.getResourceEntryName(resource))
            assertEquals("font", resources.getResourceTypeName(resource))
            // Resource Font equality checks the actual resource, not just a matching weight.
            assertEquals(name, Font(resource, weight = weight, style = FontStyle.Normal), family[index])
            assertEquals(name, weight, family[index].weight)
            assertEquals(name, FontStyle.Normal, family[index].style)
        }
        assertEquals(listOf(400, 500, 600, 700), family.map { it.weight.weight })
    }

    private fun Typography.styles(): Map<String, TextStyle> = linkedMapOf(
        "displayLarge" to displayLarge,
        "displayMedium" to displayMedium,
        "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge,
        "headlineMedium" to headlineMedium,
        "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge,
        "titleMedium" to titleMedium,
        "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge,
        "bodyMedium" to bodyMedium,
        "bodySmall" to bodySmall,
        "labelLarge" to labelLarge,
        "labelMedium" to labelMedium,
        "labelSmall" to labelSmall,
    )
}
