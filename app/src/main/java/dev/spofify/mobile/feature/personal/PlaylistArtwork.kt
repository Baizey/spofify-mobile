package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Reference placeholder only: summaries contain no catalog-art identity to request. */
@Composable
internal fun PlaylistArtwork(modifier: Modifier, large: Boolean = false) {
    val shape = RoundedCornerShape(if (large) 5.dp else 4.dp)
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
    Canvas(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
        val center = Offset(size.width / 2, size.height / 2)
        val halfDiagonal = size.minDimension * .56f / 1.41421356f
        val diamond = Path().apply {
            moveTo(center.x, center.y - halfDiagonal)
            lineTo(center.x + halfDiagonal, center.y)
            lineTo(center.x, center.y + halfDiagonal)
            lineTo(center.x - halfDiagonal, center.y)
            close()
        }
        drawPath(diamond, ink, style = Stroke(1.dp.toPx()))
        drawCircle(ink, size.minDimension * .16f, center, style = Stroke(1.dp.toPx()))
    }
}
