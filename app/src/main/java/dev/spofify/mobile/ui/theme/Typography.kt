package dev.spofify.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.spofify.mobile.R

/** Bundled static faces; missing Unicode/emoji glyphs retain Android's system fallback. */
val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(R.font.jetbrains_mono_medium, weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(R.font.jetbrains_mono_semibold, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(R.font.jetbrains_mono_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
)

/** Material 3's type scale, changing only the family (including default shaping/ligature behavior). */
val AppTypography: Typography = Typography().let { defaults ->
    defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = JetBrainsMono),
        displayMedium = defaults.displayMedium.copy(fontFamily = JetBrainsMono),
        displaySmall = defaults.displaySmall.copy(fontFamily = JetBrainsMono),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = JetBrainsMono),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = JetBrainsMono),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = JetBrainsMono),
        titleLarge = defaults.titleLarge.copy(fontFamily = JetBrainsMono),
        titleMedium = defaults.titleMedium.copy(fontFamily = JetBrainsMono),
        titleSmall = defaults.titleSmall.copy(fontFamily = JetBrainsMono),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = JetBrainsMono),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = JetBrainsMono),
        bodySmall = defaults.bodySmall.copy(fontFamily = JetBrainsMono),
        labelLarge = defaults.labelLarge.copy(fontFamily = JetBrainsMono),
        labelMedium = defaults.labelMedium.copy(fontFamily = JetBrainsMono),
        labelSmall = defaults.labelSmall.copy(fontFamily = JetBrainsMono),
    )
}
