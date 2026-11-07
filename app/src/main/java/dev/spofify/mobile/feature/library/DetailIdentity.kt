package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem

/** Shared list-first identity. No hero reserve, invented metadata or action authority. */
@Composable
internal fun DetailIdentity(
    title: String,
    metadata: String,
    artwork: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    artist: Boolean = false,
    credits: @Composable () -> Unit = {},
    description: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val size = if (LocalDensity.current.fontScale > 1.3f) 64.dp else 88.dp
            artwork(Modifier.size(size).then(if (artist) Modifier.clip(CircleShape) else Modifier))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                credits()
                if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Inline known credits, wrapping only as needed; no stacked fixed-height artist rows. */
@Composable
internal fun DetailCredits(item: LibraryItem, onOpen: (LibraryItem) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item.artists.forEach { credit ->
            if (credit.id.isBlank()) Text(credit.name, style = MaterialTheme.typography.bodySmall)
            else dev.spofify.mobile.ui.MusicDetailLink(credit.name,
                onClick = { onOpen(LibraryItem("artist", credit.id, credit.name)) })
        }
    }
}
