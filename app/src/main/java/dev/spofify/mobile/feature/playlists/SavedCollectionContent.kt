package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.saved.SavedCollectionSnapshot
import dev.spofify.mobile.core.saved.SavedOccurrenceSnapshot
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind
import dev.spofify.mobile.feature.library.MusicRow
import dev.spofify.mobile.ui.MusicActionsContent
import dev.spofify.mobile.feature.library.MusicListLayout
import dev.spofify.mobile.ui.SongActionSheet

internal fun LibraryItem.savedTarget(): SavedTarget? = when (kind) {
    "album" -> SavedTarget(SavedTargetKind.ALBUM, id)
    "artist" -> SavedTarget(SavedTargetKind.ARTIST, id)
    "playlist" -> SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, id)
    "derived" -> SavedTarget(SavedTargetKind.DERIVED_PLAYLIST, id)
    else -> null
}

@Composable
internal fun SavedCollectionControl(owner: SavedDownloadsOwner, target: SavedTarget, title: String,
    modifier: Modifier = Modifier, actionsEnabled: Boolean = true, isCurrent: () -> Boolean = { true },
    expectedBindingRevision: Long? = null) {
    val library = owner.state.collectAsState().value
    val frameRevision = expectedBindingRevision ?: library.bindingRevision
    val accountCurrent = owner.captureAction(frameRevision)
    val frameCurrent = accountCurrent() && isCurrent()
    val snapshot = library.collections.firstOrNull { it.target == target }
    val base = snapshot?.presentation() ?: SavedDownloadPresentation(SavedDownloadPhase.NOT_SAVED)
    val presentation = base.copy(
        phase = when {
            library.activeTarget == target -> SavedDownloadPhase.DOWNLOADING
            target in library.working -> SavedDownloadPhase.QUEUED
            else -> base.phase
        },
        failure = library.failures[target] ?: base.failure,
    )
    SavedDownloadControl(presentation,
        onSave = { if (actionsEnabled && accountCurrent() && isCurrent()) owner.save(target, title, frameRevision) },
        onUpdate = { if (actionsEnabled && accountCurrent() && isCurrent()) owner.save(target, title, frameRevision) },
        onCancel = { if (actionsEnabled && accountCurrent() && isCurrent()) owner.cancel(target, frameRevision) },
        onUnsave = { if (actionsEnabled && accountCurrent() && isCurrent()) owner.unsave(target, frameRevision) }, modifier = modifier,
        networkEnabled = library.networkEnabled && actionsEnabled && frameCurrent,
        actionsEnabled = actionsEnabled && frameCurrent)
}

/** A true-kind saved row. Opening it reads installed local membership, never server playback. */
@Composable
internal fun SavedCollectionIndexRow(snapshot: SavedCollectionSnapshot, owner: SavedDownloadsOwner,
    bindingRevision: Long, onOpenServerPlaylist: (() -> Unit)? = null) {
    var openingRevision by remember(snapshot.target) { mutableStateOf<Long?>(null) }
    val accountCurrent = owner.captureAction(bindingRevision)
    MusicRow(snapshot.libraryItem(), onOpenItem = { if (accountCurrent()) openingRevision = bindingRevision }, onPlayItem = { _, _ -> },
        onShowActions = { if (accountCurrent()) openingRevision = bindingRevision }, onEnqueueItem = {}, artwork = { item, modifier -> SavedArtwork(item, modifier) },
        playbackEnabled = false, rowIdentity = snapshot.target,
        subtitleOverride = "${snapshot.target.kind.label()} · ${snapshot.occurrences.count { it.available }}/${snapshot.occurrences.size} downloaded")
    openingRevision?.let { opened ->
        SavedCollectionDialog(owner, snapshot.target, expectedBindingRevision = opened,
            onDismiss = { openingRevision = null },
            onOpenServerPlaylist = onOpenServerPlaylist?.let { callback -> { openingRevision = null; callback() } })
    }
}

private data class SavedRowAction(val row: SavedOccurrenceSnapshot, val snapshot: SavedCollectionSnapshot,
    val bindingRevision: Long, val accountCurrent: () -> Boolean)

/** Full ordered installed snapshot; unavailable/missing rows stay visible. No network artwork. */
@Composable
private fun SavedCollectionDialog(owner: SavedDownloadsOwner, target: SavedTarget, expectedBindingRevision: Long,
    onDismiss: () -> Unit, onOpenServerPlaylist: (() -> Unit)?) {
    val library = owner.state.collectAsState().value
    if (library.bindingRevision != expectedBindingRevision) {
        LaunchedEffect(library.bindingRevision, expectedBindingRevision) { onDismiss() }
        return
    }
    val snapshot = library.collections.firstOrNull { it.target == target }
    val play by owner.localPlayback.collectAsState()
    val accountCurrent = owner.captureAction(expectedBindingRevision)
    val frameCurrent = accountCurrent()
    var more by remember(target) { mutableStateOf<SavedRowAction?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            if (snapshot == null) Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                Text("Not saved")
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
            } else LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentPadding = PaddingValues(horizontal = MusicListLayout.horizontalPadding, vertical = 16.dp),
                verticalArrangement = MusicListLayout.arrangement) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(snapshot.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${target.kind.label()} · ${snapshot.occurrences.count { it.available }}/${snapshot.occurrences.size} downloaded",
                            style = MaterialTheme.typography.bodySmall)
                        SavedCollectionControl(owner, target, snapshot.title,
                            expectedBindingRevision = expectedBindingRevision)
                        if (frameCurrent && play != null && snapshot.occurrences.any { it.available }) TextButton(
                            onClick = { if (accountCurrent()) owner.playAvailable(snapshot,
                                expectedBindingRevision = expectedBindingRevision) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Play available") }
                        if (frameCurrent && onOpenServerPlaylist != null && library.networkEnabled) TextButton(onClick = {
                            if (accountCurrent()) onOpenServerPlaylist()
                        }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Open server playlist")
                        }
                        if (library.storageFailure) Text("Saved storage needs attention", color = MaterialTheme.colorScheme.error)
                    }
                }
                items(snapshot.occurrences, key = { it.occurrence.occurrenceId }) { row ->
                    MusicRow(row.localItem(), onOpenItem = {},
                        onPlayItem = { _, _ -> if (accountCurrent() && row.available && play != null)
                            owner.playAvailable(snapshot, row.occurrence.occurrenceId, expectedBindingRevision) },
                        onShowActions = { if (accountCurrent()) more = SavedRowAction(row, snapshot, expectedBindingRevision, accountCurrent) }, onEnqueueItem = {},
                        artwork = { item, modifier -> SavedArtwork(item, modifier) },
                        rowIdentity = Triple(target, snapshot.generation, row.occurrence.occurrenceId),
                        playbackEnabled = frameCurrent && row.available && play != null,
                        subtitleOverride = listOf(row.occurrence.artist, row.availabilityLabel()).filter { it.isNotBlank() }.joinToString(" · "))
                }
            }
        }
        more?.let { selected ->
            val selectedRow = selected.row
            val current = if (selected.accountCurrent() && snapshot != null && snapshot.generation == selected.snapshot.generation)
                snapshot.occurrences.firstOrNull { it.occurrence.occurrenceId == selectedRow.occurrence.occurrenceId } else null
            SongActionSheet(onDismiss = { more = null }) {
                Text(current?.availabilityLabel() ?: "No longer in this download",
                    style = MaterialTheme.typography.bodySmall)
                MusicActionsContent(selectedRow.localItem(),
                    onPlay = if (current?.available == true && play != null) ({
                        more = null
                        if (selected.accountCurrent()) owner.playAvailable(selected.snapshot,
                            selectedRow.occurrence.occurrenceId, selected.bindingRevision)
                    }) else null,
                    playbackEnabled = current?.available == true,
                    artwork = { item, modifier -> SavedArtwork(item, modifier) })
            }
        }
    }
}

private fun SavedOccurrenceSnapshot.availabilityLabel(): String = when {
    !occurrence.hasAudio -> "Audio unavailable"
    !available -> "Not downloaded"
    else -> "Downloaded"
}

private fun SavedOccurrenceSnapshot.localItem(): LibraryItem = LibraryItem(
    kind = "track", id = occurrence.trackId, name = occurrence.title, entryId = occurrence.occurrenceId,
    durationMs = occurrence.durationMs, hasAudio = available,
    artists = occurrence.artist.takeIf { it.isNotBlank() }?.let { listOf(ArtistCredit("", it)) }.orEmpty(),
    albumId = occurrence.albumId, albumName = occurrence.albumTitle,
)

internal fun SavedTargetKind.label(): String = when (this) {
    SavedTargetKind.MANUAL_PLAYLIST -> "Playlist"
    SavedTargetKind.DERIVED_PLAYLIST -> "Derived playlist"
    SavedTargetKind.ALBUM -> "Album"
    SavedTargetKind.ARTIST -> "Artist"
}

@Composable
private fun SavedArtwork(item: LibraryItem, modifier: Modifier) {
    Icon(when (item.kind) { "artist" -> Icons.Default.Person; "album" -> Icons.Default.Album; else -> Icons.Default.MusicNote },
        contentDescription = null, modifier = modifier)
}
