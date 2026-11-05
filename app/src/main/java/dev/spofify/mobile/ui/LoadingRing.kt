package dev.spofify.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

/** Native current-color, quarter-open ring. The caller owns footprint and contextual semantics.
 * Compose's infinite transition observes Android's animator duration scale: zero leaves a static ring.
 * Keep this composition in place across ordinary state publications (no heartbeat animation keys).
 */
@Composable
internal fun LoadingRing(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val transition = rememberInfiniteTransition(label = "Loading ring")
    val angle by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "Ring rotation")
    Canvas(modifier) {
        val diameter = size.minDimension
        val stroke = diameter * .14f
        val inset = stroke / 2f
        drawArc(color, angle + 45f, 270f, useCenter = false,
            topLeft = Offset(inset, inset), size = Size(diameter - stroke, diameter - stroke),
            style = Stroke(stroke))
    }
}
