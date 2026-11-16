package dev.spofify.mobile.playback

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Album, artist and inline lyrics share the same supporting-text scale. */
internal val playerSupportingTextStyle = TextStyle(
    fontSize = 13.2.sp,
    lineHeight = 17.82.sp,
    fontWeight = FontWeight.Normal,
)
