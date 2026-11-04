package dev.spofify.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.spofify.mobile.R

/** Bundled static faces; missing Unicode/emoji glyphs retain Android's system fallback. */
val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(R.font.jetbrains_mono_medium, weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(R.font.jetbrains_mono_semibold, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(R.font.jetbrains_mono_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
)

/**
 * Compact shared scale: primary body text is 14sp, secondary text stays readable at 12sp.
 * Keep Material weights/tracking/shaping, display sizes and labels; all sizes scale with Android.
 */
val AppTypography: Typography = Typography().let { defaults ->
    defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = JetBrainsMono),
        displayMedium = defaults.displayMedium.copy(fontFamily = JetBrainsMono),
        displaySmall = defaults.displaySmall.copy(fontFamily = JetBrainsMono),
        headlineLarge = defaults.headlineLarge.copy(
            fontFamily = JetBrainsMono, fontSize = 30.sp, lineHeight = 38.sp,
        ),
        headlineMedium = defaults.headlineMedium.copy(
            fontFamily = JetBrainsMono, fontSize = 26.sp, lineHeight = 34.sp,
        ),
        headlineSmall = defaults.headlineSmall.copy(
            fontFamily = JetBrainsMono, fontSize = 22.sp, lineHeight = 30.sp,
        ),
        titleLarge = defaults.titleLarge.copy(
            fontFamily = JetBrainsMono, fontSize = 20.sp, lineHeight = 26.sp,
        ),
        titleMedium = defaults.titleMedium.copy(
            fontFamily = JetBrainsMono, fontSize = 14.sp, lineHeight = 22.sp,
        ),
        titleSmall = defaults.titleSmall.copy(
            fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 18.sp,
        ),
        bodyLarge = defaults.bodyLarge.copy(
            fontFamily = JetBrainsMono, fontSize = 14.sp, lineHeight = 22.sp,
        ),
        bodyMedium = defaults.bodyMedium.copy(
            fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 20.sp,
        ),
        bodySmall = defaults.bodySmall.copy(fontFamily = JetBrainsMono),
        labelLarge = defaults.labelLarge.copy(fontFamily = JetBrainsMono),
        labelMedium = defaults.labelMedium.copy(fontFamily = JetBrainsMono),
        labelSmall = defaults.labelSmall.copy(fontFamily = JetBrainsMono),
    )
}
