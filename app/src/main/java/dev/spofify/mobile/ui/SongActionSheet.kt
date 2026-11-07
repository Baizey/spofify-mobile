package dev.spofify.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem

/** Presentation only: callers retain their captured selection, execution guards and dismissal policy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongActionSheet(onDismiss: () -> Unit, dismissEnabled: Boolean = true, content: @Composable () -> Unit) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    ModalBottomSheet(
        onDismissRequest = { if (canDismiss) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || canDismiss }),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissEnabled),
        content = { content() },
    )
}

/** One finite scroll surface; the native sheet supplies safe drawing/gesture insets once. */
@Composable
internal fun SongActionLayout(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    artwork: @Composable (LibraryItem, Modifier) -> Unit = { _, bounds ->
        Box(bounds.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, Modifier.size(28.dp))
        }
    },
    content: @Composable ColumnScope.() -> Unit,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(modifier.fillMaxWidth().heightIn(max = windowHeight).testTag("music-actions")
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            artwork(item, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                val identity = item.artists.joinToString(" · ") { it.name }.ifBlank { item.subtitle }
                if (identity.isNotBlank()) Text(identity, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
internal fun SongActionGroup(label: String, divider: Boolean = true, content: @Composable () -> Unit) {
    if (divider) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(label, Modifier.padding(top = 4.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    content()
}

/** Neutral, wrapping, disjoint touch targets. Icons are decorative; text supplies TalkBack names. */
@Composable
internal fun SongActionRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    destructive: Boolean = false,
    supportingText: String? = null,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = color)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color) }
        }
    }
}

/** Presence is decided by the caller's exact editable occurrence capability, never by song identity. */
@Composable
internal fun SongActionRemoval(enabled: Boolean, onRemove: (() -> Unit)?) {
    onRemove?.let { action ->
        SongActionGroup("This playlist") {
            SongActionRow("Remove from this playlist", Icons.Default.RemoveCircleOutline,
                enabled, destructive = true, onClick = action)
        }
    }
}

/** Preserve every known opaque credit ID, without hover submenus or a single-artist shortcut. */
@Composable
internal fun SongActionNavigation(item: LibraryItem, enabled: Boolean = true, onOpenItem: (LibraryItem) -> Unit) {
    item.artists.forEach { artist ->
        SongActionRow(artist.name, Icons.Default.Person, enabled && artist.id.isNotBlank(),
            supportingText = if (artist.id.isNotBlank()) "Go to artist" else "Artist",
            onClick = { onOpenItem(LibraryItem("artist", artist.id, artist.name)) })
    }
    val album = item.albumName
    val albumId = item.albumId
    if (item.kind == "track" && !album.isNullOrBlank()) {
        SongActionRow(album, Icons.Default.Album, enabled && !albumId.isNullOrBlank(),
            supportingText = if (albumId.isNullOrBlank()) "Album" else "Go to album",
            onClick = { if (!albumId.isNullOrBlank()) onOpenItem(LibraryItem("album", albumId, album)) })
    }
}
