package dev.spofify.mobile.designsystem

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spofify.mobile.R
import dev.spofify.mobile.core.palette.PaletteTokens

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

private val dark = darkColorScheme(
    background = Color(0xFF101216), onBackground = Color(0xFFF4F6FA),
    surface = Color(0xFF1A1E24), onSurface = Color(0xFFF4F6FA),
    surfaceVariant = Color(0xFF252B34), onSurfaceVariant = Color(0xFFB4BDCB),
    primary = Color(0xFF66E3B4), onPrimary = Color(0xFF08271D),
    secondary = Color(0xFF91BFFF), tertiary = Color(0xFFDFBD78),
    outline = Color(0xFF68778A), outlineVariant = Color(0xFF353E49),
)

private val light = lightColorScheme(
    background = Color(0xFFF8FAFC), onBackground = Color(0xFF1B2636),
    surface = Color(0xFFEDF1F5), onSurface = Color(0xFF1B2636),
    surfaceVariant = Color(0xFFE1E8EF), onSurfaceVariant = Color(0xFF4C5E74),
    primary = Color(0xFF08704E), onPrimary = Color.White,
    secondary = Color(0xFF2759A4), tertiary = Color(0xFF77551B),
    outline = Color(0xFF728097), outlineVariant = Color(0xFFC0CBD6),
)

/** Palette tokens have already passed the coordinated v1 catalog validation in core. */
internal fun paletteColor(hex: String): Color = Color(0xFF000000L or hex.removePrefix("#").toLong(16))

internal fun spofifyColorScheme(lightTheme: Boolean, palette: PaletteTokens? = null): ColorScheme {
    val base = if (lightTheme) light else dark
    return if (palette == null) base else base.copy(
        primary = paletteColor(palette.primary), onPrimary = paletteColor(palette.onPrimary),
        secondary = paletteColor(palette.secondary), tertiary = paletteColor(palette.tertiary),
    )
}

@Composable
fun SpofifyTheme(lightTheme: Boolean = false, palette: PaletteTokens? = null, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = spofifyColorScheme(lightTheme, palette), typography = typography, content = content)
}
