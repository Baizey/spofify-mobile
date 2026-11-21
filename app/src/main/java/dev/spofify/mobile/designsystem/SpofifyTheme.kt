package dev.spofify.mobile.designsystem

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import dev.spofify.mobile.R

object Spacing {
    val page = 20.dp
    val group = 24.dp
    val fieldGap = 20.dp
    val action = 56.dp
    val target = 48.dp
}

private val mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = mono, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = mono, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = mono, fontSize = 36.sp, lineHeight = 44.sp),
    headlineSmall = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleSmall = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = mono, fontSize = 12.sp, lineHeight = 16.8.sp),
    labelSmall = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    headlineLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 33.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-1.2).sp),
    titleMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 17.4.sp, lineHeight = 21.75.sp),
    bodyLarge = TextStyle(fontFamily = mono, fontSize = 15.sp, lineHeight = 20.25.sp),
    bodyMedium = TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 17.55.sp),
    labelLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
)

internal fun spofifyColorScheme(color: (Int) -> Color): ColorScheme {
    val canvas = color(R.color.theme_canvas)
    val chrome = color(R.color.theme_chrome)
    val panel = color(R.color.theme_panel)
    val raised = color(R.color.theme_raised)
    val divider = color(R.color.theme_divider)
    val text = color(R.color.theme_text)
    val muted = color(R.color.theme_muted)
    val subtle = color(R.color.theme_subtle)
    val primary = color(R.color.theme_primary)
    val onPrimary = color(R.color.theme_on_primary)
    val tertiary = color(R.color.theme_tertiary)
    val error = color(R.color.theme_error)
    return darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = raised, onPrimaryContainer = primary, inversePrimary = primary,
        // Material components still ask for secondary roles, but they don't get another hue.
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = raised, onSecondaryContainer = primary,
        tertiary = tertiary, onTertiary = canvas,
        tertiaryContainer = raised, onTertiaryContainer = tertiary,
        background = canvas, onBackground = text,
        surface = panel, onSurface = text, surfaceTint = panel,
        surfaceVariant = raised, onSurfaceVariant = muted,
        inverseSurface = raised, inverseOnSurface = text,
        error = error, onError = canvas, errorContainer = raised, onErrorContainer = error,
        outline = subtle, outlineVariant = divider, scrim = Color.Black,
        surfaceBright = raised, surfaceDim = chrome,
        surfaceContainerLowest = canvas, surfaceContainerLow = chrome, surfaceContainer = panel,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        primaryFixed = primary, primaryFixedDim = primary,
        onPrimaryFixed = onPrimary, onPrimaryFixedVariant = onPrimary,
        secondaryFixed = primary, secondaryFixedDim = primary,
        onSecondaryFixed = onPrimary, onSecondaryFixedVariant = onPrimary,
        tertiaryFixed = tertiary, tertiaryFixedDim = tertiary,
        onTertiaryFixed = canvas, onTertiaryFixedVariant = canvas,
    )
}

@Composable
fun SpofifyTheme(content: @Composable () -> Unit) {
    val resources = LocalResources.current
    val colors = remember(resources) { spofifyColorScheme { Color(ResourcesCompat.getColor(resources, it, null)) } }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
