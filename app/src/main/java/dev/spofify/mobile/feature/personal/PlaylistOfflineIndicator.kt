package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.designsystem.SpofifyGlyph
import dev.spofify.mobile.designsystem.SpofifyIcon
import dev.spofify.mobile.playback.PlaylistOfflineStatus

/** The list keeps the old cover footprint; detail uses the same status without a tile. */
@Composable
internal fun PlaylistOfflineIndicator(status: PlaylistOfflineStatus?, modifier: Modifier = Modifier, tile: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val frame = if (tile && status != null) Modifier.background(colors.surfaceVariant, shape)
        .border(1.dp, colors.outlineVariant, shape) else Modifier
    Box(modifier.then(frame), contentAlignment = Alignment.Center) {
        when (status) {
            null -> Unit
            PlaylistOfflineStatus.Saved -> SpofifyIcon(SpofifyGlyph.Downloaded, "Saved for offline", Modifier.size(20.dp), colors.primary)
            PlaylistOfflineStatus.Pending -> SpofifyIcon(SpofifyGlyph.DownloadPending, "Offline download pending", Modifier.size(20.dp), colors.onSurfaceVariant)
            is PlaylistOfflineStatus.Downloading -> {
                val (verified, total) = status.progress
                val count = "$verified/$total"
                val maxChars = (7 / LocalDensity.current.fontScale.coerceAtLeast(1f)).toInt().coerceAtLeast(3)
                val label = if (tile && count.length > maxChars) "${if (total == 0) 100 else verified * 100 / total}%" else count
                Text(label, Modifier.padding(horizontal = 2.dp).clearAndSetSemantics {
                    contentDescription = "Saving for offline: $verified of $total songs verified"
                }, style = MaterialTheme.typography.labelSmall, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
