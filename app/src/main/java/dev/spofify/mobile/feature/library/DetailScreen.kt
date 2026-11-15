package dev.spofify.mobile.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.R
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackController
import dev.spofify.mobile.core.playback.PlaybackTrackReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScreen(
    model: DetailViewModel,
    loader: ImageLoader,
    onBack: () -> Unit,
    onOpen: (MusicItem) -> Unit,
    playback: PlaybackController? = null,
    onNavigate: ((MusicKind, String) -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val playbackView = playback?.state?.collectAsStateWithLifecycle()?.value
    val ready = playbackView?.connection == PlaybackConnectionState.READY
    var menu by remember(model) { mutableStateOf(false) }
    var actionError by remember(model) { mutableStateOf<String?>(null) }
    val item = state.item
    val context = item?.collectionContext(state.revision)
    val playable = ready && item?.canPlay() == true
    val send: (() -> String?) -> Unit = { action ->
        actionError = if (action() == null) "Playback action wasn't sent. Check the playback connection." else null
    }
    val playRow: (MusicItem) -> Unit = { row ->
        when {
            playback == null -> onOpen(row)
            !row.hasAudio -> actionError = "Audio unavailable for this song."
            !ready -> actionError = "Connect playback to listen."
            else -> send { playback.playTrack(PlaybackTrackReference(row.id), context) }
        }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        if (item?.kind == MusicKind.ARTIST) {
            val largeText = LocalDensity.current.fontScale >= 1.5f
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (largeText) 1 else 2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            ) {
                item("heading", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        DetailHeading(item, loader, onNavigate)
                        DetailControls(item, playable, { send { playback?.playCollection(requireNotNull(context)) } }, { menu = true })
                        DetailPlaybackStatus(item, ready, actionError ?: playbackView?.error)
                        Text("Albums", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 24.dp).semantics { heading() })
                    }
                }
                items(state.rows, key = MusicItem::key) { album ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(album) }) {
                        MusicArtwork(album, loader, Modifier.widthIn(max = 180.dp).fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                        Text(album.name.ifBlank { "Unknown title" }, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
                        releaseYear(album.releaseDate).takeIf(String::isNotBlank)?.let { year ->
                            Text(year, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item("footer", span = { GridItemSpan(maxLineSpan) }) { DetailFooter(state, model) }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp)) {
                if (item != null) {
                    item("heading") { Box(Modifier.padding(horizontal = 20.dp)) { DetailHeading(item, loader, onNavigate) } }
                    item("controls") {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            DetailControls(item, playable, {
                                send {
                                    if (context != null) playback?.playCollection(context)
                                    else playback?.playTrack(PlaybackTrackReference(item.id))
                                }
                            }, { menu = true })
                        }
                    }
                    item("status") { Box(Modifier.padding(horizontal = 20.dp)) {
                        DetailPlaybackStatus(item, ready, actionError ?: playbackView?.error)
                    } }
                    if (item.kind == MusicKind.ALBUM) {
                        item("filter") { DetailFilter(state.filter, model::filter) }
                    } else {
                        item("track-metadata") {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                if (item.albumName.isNotBlank()) {
                                    TextButton({ onNavigate?.invoke(MusicKind.ALBUM, item.albumId) },
                                        enabled = onNavigate != null && item.albumId.isNotBlank(), contentPadding = PaddingValues(0.dp)) {
                                        Text(item.albumName)
                                    }
                                }
                                if (item.durationMs > 0) Text(duration(item.durationMs), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(state.visibleRows, key = MusicItem::key) { row ->
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        MusicRow(row, loader,
                            onQueue = if (ready && row.hasAudio) ({ send { playback?.queueTrack(PlaybackTrackReference(row.id), context) } }) else null,
                            onPlayNext = if (ready && row.hasAudio) ({ send { playback?.queueTrack(PlaybackTrackReference(row.id), context, next = true) } }) else null,
                        ) { playRow(row) }
                    }
                }
                if (state.noMatches) {
                    item("no-matches") { Text("No matches", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item("footer") { Box(Modifier.padding(horizontal = 20.dp)) { DetailFooter(state, model) } }
            }
        }
    }

    if (menu && item != null) {
        ModalBottomSheet(onDismissRequest = { menu = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(item.name.ifBlank { "Unknown title" }, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp).semantics { heading() })
                DetailMenuAction("Play", playable) {
                    menu = false
                    send { if (context != null) playback?.playCollection(context) else playback?.playTrack(PlaybackTrackReference(item.id)) }
                }
                if (context != null) {
                    DetailMenuAction("Shuffle", playable) { menu = false; send { playback?.playCollection(context, shuffle = true) } }
                }
                DetailMenuAction("Queue", playable) {
                    menu = false
                    send { if (context != null) playback?.queueCollection(context) else playback?.queueTrack(PlaybackTrackReference(item.id)) }
                }
                DetailMenuAction("Play next", playable) {
                    menu = false
                    send { if (context != null) playback?.queueCollection(context, next = true)
                        else playback?.queueTrack(PlaybackTrackReference(item.id), next = true) }
                }
                DetailMenuAction("Refresh", !state.loading) { menu = false; model.refresh() }
            }
        }
    }
}

@Composable
private fun DetailHeading(item: MusicItem, loader: ImageLoader, onNavigate: ((MusicKind, String) -> Unit)?) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val shape = if (item.kind == MusicKind.ARTIST) CircleShape else RoundedCornerShape(5.dp)
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = if (largeText) Alignment.Top else Alignment.CenterVertically) {
        MusicArtwork(item, loader, Modifier.size(if (largeText) 52.dp else 76.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant))
        Column(Modifier.weight(1f)) {
            Text(item.name.ifBlank { "Unknown title" }, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            if (item.kind != MusicKind.ARTIST) {
                item.artists.forEach { artist ->
                    if (onNavigate != null && artist.id.isNotBlank()) {
                        TextButton({ onNavigate(MusicKind.ARTIST, artist.id) },
                            Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            Text(artist.name.ifBlank { "Unknown artist" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(artist.name.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
            val year = releaseYear(item.releaseDate)
            val metadata = when (item.kind) {
                MusicKind.ARTIST -> "Artist"
                MusicKind.ALBUM -> if (year.isBlank()) "Album" else "Album · $year"
                MusicKind.TRACK -> "Song"
            }
            Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailControls(item: MusicItem, playable: Boolean, onPlay: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            FilledIconButton(onPlay, Modifier.size(48.dp), enabled = playable) {
                Icon(painterResource(R.drawable.ic_detail_play), "Play ${item.name}", Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onMore, Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_detail_more), "More actions for ${item.name}", Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun DetailPlaybackStatus(item: MusicItem, ready: Boolean, error: String?) {
    val message = error ?: when {
        !item.canPlay() -> "Audio unavailable in library"
        !ready -> "Connect playback to listen"
        else -> null
    }
    if (message != null) Text(message, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailFilter(value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BasicTextField(value, onChange,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp)
                .semantics { contentDescription = "Filter songs" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            decorationBox = { input ->
                if (value.isEmpty()) Text("Filter songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                input()
            })
    }
}

@Composable
private fun DetailMenuAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = enabled,
        contentPadding = PaddingValues(vertical = 12.dp)) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailFooter(state: DetailState, model: DetailViewModel) {
    when {
        state.loading -> Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(if (state.loaded) "Refreshing music…" else "Loading music…", style = MaterialTheme.typography.bodySmall)
        }
        state.failed -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.loaded) "Couldn't update music" else "Can't load music", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(model::retry, Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
        state.nextOffset != null -> TextButton(model::more, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("More albums") }
        state.loaded && state.rows.isEmpty() && state.item?.kind != MusicKind.TRACK ->
            Text(if (state.item?.kind == MusicKind.ARTIST) "No albums in library" else "No songs in library",
                Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
