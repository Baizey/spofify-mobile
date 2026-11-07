package dev.spofify.mobile.feature.playlists

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.ui.MusicActionsContent
import dev.spofify.mobile.ui.SongActionGroup
import dev.spofify.mobile.ui.SongActionRow

/** Production collection body, also available to component render hosts without opening a modal. */
@Composable
internal fun PlaylistCollectionActionsContent(
    item: LibraryItem,
    playbackEnabled: Boolean,
    managementEnabled: Boolean,
    copyEnabled: Boolean = managementEnabled,
    verifyEnabled: Boolean = managementEnabled,
    allowNewCopyEnabled: Boolean = managementEnabled,
    onPlayNow: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onEditDetails: (() -> Unit)? = null,
    onEditRules: (() -> Unit)? = null,
    onMakeEditableCopy: (() -> Unit)? = null,
    onVerifySavedCopies: (() -> Unit)? = null,
    onAllowNewCopy: (() -> Unit)? = null,
    onReorder: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    artwork: (@Composable (LibraryItem, Modifier) -> Unit)? = null,
) {
    val hasManagement = listOf(onEditDetails, onEditRules, onMakeEditableCopy,
        onVerifySavedCopies, onAllowNewCopy, onReorder).any { it != null }
    MusicActionsContent(item,
        onPlay = onPlayNow, onPlayNext = onPlayNext, onQueue = onQueue,
        playbackEnabled = playbackEnabled, artwork = artwork,
        libraryContent = if (hasManagement) ({
            onEditDetails?.let { SongActionRow("Edit details", Icons.Default.Edit, managementEnabled, onClick = it) }
            onEditRules?.let { SongActionRow("Edit rules", Icons.Default.Tune, managementEnabled, onClick = it) }
            onMakeEditableCopy?.let { SongActionRow("Make editable copy", Icons.Default.ContentCopy, copyEnabled, onClick = it) }
            onVerifySavedCopies?.let { SongActionRow("Verify saved copies", Icons.Default.FactCheck, verifyEnabled, onClick = it) }
            onAllowNewCopy?.let { SongActionRow("I reviewed saved copies — allow a new copy", Icons.Default.Check,
                allowNewCopyEnabled, onClick = it) }
            onReorder?.let { SongActionRow("Reorder all occurrences", Icons.Default.Reorder, managementEnabled, onClick = it) }
        }) else null,
        contextualContent = {
            onDelete?.let { action ->
                SongActionGroup("This playlist") {
                    SongActionRow("Delete", Icons.Default.DeleteOutline, managementEnabled, destructive = true, onClick = action)
                }
            }
        })
}
