package dev.spofify.mobile.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object SpofifySurfaces {
    private val controlRadius = 8.dp
    private val panelRadius = 12.dp
    val control = RoundedCornerShape(controlRadius)
    val artwork = RoundedCornerShape(controlRadius)
    val panel = RoundedCornerShape(panelRadius)
    val sheet = RoundedCornerShape(topStart = panelRadius, topEnd = panelRadius)
}

@Composable
internal fun Modifier.spofifyField(
    focused: Boolean,
    focusColor: Color = MaterialTheme.colorScheme.primary,
    illumination: Brush? = null,
    enabled: Boolean = true,
): Modifier {
    val colors = MaterialTheme.colorScheme
    val shape = SpofifySurfaces.control
    val borderColor = when {
        !enabled -> colors.outlineVariant
        focused -> focusColor
        else -> colors.outline
    }
    return background(colors.surfaceContainerHigh, shape)
        .then(if (illumination == null) Modifier else Modifier.background(illumination, shape))
        .border(if (focused && enabled) 2.dp else 1.dp, borderColor, shape)
}
