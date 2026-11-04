package dev.spofify.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
    fun materialDefaultsAreUnchangedApartFromFontFamily() {
        val defaults = Typography().styles()
        val actual = AppTypography.styles()
        assertEquals(defaults.keys, actual.keys)
        defaults.forEach { (name, style) ->
            // Covers size, weight, line height, tracking, shaping features and platform options.
            assertEquals(name, style.copy(fontFamily = JetBrainsMono), actual.getValue(name))
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
