package dev.spofify.mobile.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.PlaybackContext
import dev.spofify.mobile.playback.AppPlayback
import dev.spofify.mobile.core.playback.PlaybackTrackReference
import dev.spofify.mobile.designsystem.SheetAction
import dev.spofify.mobile.designsystem.SheetGroup
import dev.spofify.mobile.designsystem.SheetHeading
import dev.spofify.mobile.designsystem.SpofifyGlyph
import dev.spofify.mobile.designsystem.SpofifyIcon
import dev.spofify.mobile.designsystem.SpofifySheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScreen(
    model: DetailViewModel,
    loader: ImageLoader,
    onBack: () -> Unit,
    onOpen: (MusicItem) -> Unit,
    playback: AppPlayback? = null,
    onNavigate: ((MusicKind, String) -> Unit)? = null,
    onMore: ((MusicItem, PlaybackContext?) -> Unit)? = null,
) {
    val state by key(model) { model.state.collectAsStateWithLifecycle() }
    val ready = playback?.observe()?.canLoadTrack == true
    var menuItem by remember(model) { mutableStateOf<MusicItem?>(null) }
    var actionError by remember(model) { mutableStateOf<String?>(null) }
    val item = state.item
    val context = item?.collectionContext(state.revision)
    val send: (() -> Boolean?) -> Unit = { action ->
        action(); actionError = null
    }
    val playRow: (MusicItem) -> Unit = { row ->
        when {
            playback == null -> onOpen(row)
            !row.hasAudio -> actionError = "Audio unavailable for this song."
            !ready -> onOpen(row)
            // The server selects/rotates the complete album, not a local page or subset.
            else -> send { playback.playTrack(PlaybackTrackReference(row.id), context) }
        }
    }
    val playCollection = {
        send {
            if (context != null) playback?.playCollection(context)
            else item?.let { playback?.playTrack(PlaybackTrackReference(it.id)) }
        }
    }

    BackHandler(onBack = onBack)
    if (item?.kind == MusicKind.ARTIST) {
        ArtistDiscographyGrid(
            artistId = item.id,
            releases = state.rows,
            loader = loader,
            onOpen = onOpen,
            header = {
                Column {
                    DetailHeading(item, loader, onNavigate)
                    DetailControls(item, ready && item.canPlay(), playCollection, { menuItem = item })
                    DetailActionError(actionError)
                    DetailTopTracks(state.rankMode, model::toggleRankMode)
                }
            },
            loadState = { DetailLoadState(state, model) },
        )
    } else {
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp)) {
            if (item != null) {
                item("heading") {
                    Column(Modifier.padding(horizontal = MusicRowGeometry.EDGE_GUTTER.dp)) {
                        DetailHeading(item, loader, onNavigate)
                        DetailControls(item, ready && item.canPlay(), playCollection, { menuItem = item })
                        DetailActionError(actionError)
                    }
                }
                if (item.kind == MusicKind.TRACK) {
                    item("track-metadata") {
                        if (item.albumName.isNotBlank() && onNavigate != null && item.albumId.isNotBlank()) {
                            TextButton({ onNavigate(MusicKind.ALBUM, item.albumId) },
                                Modifier.padding(horizontal = MusicRowGeometry.EDGE_GUTTER.dp), contentPadding = PaddingValues(0.dp)) {
                                Text(item.albumName)
                            }
                        }
                    }
                }
            }
            itemsIndexed(state.rows, key = { _, row -> row.key }) { index, row ->
                MusicRow(row, loader,
                    onQueue = if (ready && row.hasAudio) ({ send { playback?.queueTrack(PlaybackTrackReference(row.id), context) } }) else null,
                    trackNumber = index + 1,
                    reservePopularity = true,
                    onMore = { if (onMore != null) onMore(row, context) else menuItem = row },
                ) { playRow(row) }
            }
            item("load-state") {
                Box(Modifier.padding(horizontal = MusicRowGeometry.EDGE_GUTTER.dp)) { DetailLoadState(state, model) }
            }
        }
    }

    val selected = menuItem
    if (selected != null) {
        val isTrack = selected.kind == MusicKind.TRACK
        val playable = ready && selected.canPlay()
        val selectedContext = selected.collectionContext(state.revision)
        SpofifySheet(onDismiss = { menuItem = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
                if (isTrack) DetailSongMenuHeading(selected, loader)
                else SheetHeading(selected.name.ifBlank { "Unknown title" })
                if (isTrack) SheetGroup("Listen")
                SheetAction("Play now", SpofifyGlyph.Play, playable) {
                    menuItem = null
                    send {
                        if (selectedContext != null) playback?.playCollection(selectedContext)
                        else playback?.playTrack(PlaybackTrackReference(selected.id), context)
                    }
                }
                SheetAction("Queue", SpofifyGlyph.Queue, playable) {
                    menuItem = null
                    send {
                        if (selectedContext != null) playback?.queueCollection(selectedContext)
                        else playback?.queueTrack(PlaybackTrackReference(selected.id), context)
                    }
                }
                if (isTrack && onNavigate != null && (selected.artists.isNotEmpty() || selected.albumId.isNotBlank())) {
                    SheetGroup("Go to")
                    selected.artists.filter { it.id.isNotBlank() }.forEach { artist ->
                        SheetAction(artist.name, SpofifyGlyph.Artist) {
                            menuItem = null
                            onNavigate(MusicKind.ARTIST, artist.id)
                        }
                    }
                    if (selected.albumId.isNotBlank() && selected.albumName.isNotBlank()) {
                        SheetAction(selected.albumName, SpofifyGlyph.Album) {
                            menuItem = null
                            onNavigate(MusicKind.ALBUM, selected.albumId)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeading(item: MusicItem, loader: ImageLoader, onNavigate: ((MusicKind, String) -> Unit)?) {
    val largeText = LocalDensity.current.fontScale > 1.4f
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = if (largeText) Alignment.Top else Alignment.CenterVertically) {
        MusicArtwork(item, loader, Modifier.size(if (largeText) 52.dp else 76.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name.ifBlank { "Unknown title" }, style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 22.5.sp, lineHeight = 26.1.sp, fontWeight = FontWeight(650)),
                modifier = Modifier.semantics { heading() })
            if (item.kind != MusicKind.ARTIST) {
                item.artists.forEach { artist ->
                    val creditStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.6.sp, lineHeight = 17.01.sp)
                    if (onNavigate != null && artist.id.isNotBlank()) {
                        TextButton({ onNavigate(MusicKind.ARTIST, artist.id) },
                            Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            Text(artist.name.ifBlank { "Unknown artist" }, style = creditStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(artist.name.ifBlank { "Unknown artist" }, style = creditStyle,
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
            Text(metadata, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            if (item.genres.isNotEmpty()) {
                Text(item.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.4.sp, lineHeight = 15.39.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
private fun DetailSongMenuHeading(item: MusicItem, loader: ImageLoader) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        MusicArtwork(item, loader, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name.ifBlank { "Unknown title" }, style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 22.5.sp, lineHeight = 26.1.sp, fontWeight = FontWeight(650)))
            if (item.artists.isNotEmpty()) {
                Text(item.artists.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.8.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun DetailControls(item: MusicItem, playable: Boolean, onPlay: () -> Unit, onMore: () -> Unit) {
    // Heading content already has the same edge gutter as the full-width rows below it.
    MusicCollectionControls(Modifier.padding(bottom = 12.dp), numbered = item.kind == MusicKind.ALBUM, gutter = 0,
        play = {
            if (item.canPlay()) {
                FilledIconButton(onPlay, Modifier.size(MusicRowGeometry.ARTWORK_SLOT.dp), enabled = playable, shape = CircleShape) {
                    SpofifyIcon(SpofifyGlyph.Play, "Play ${item.name}")
                }
            }
        },
        more = {
            IconButton(onMore, Modifier.size(MusicRowGeometry.ACTION_SLOT.dp)) {
                SpofifyIcon(SpofifyGlyph.More, "More actions for ${item.name}")
            }
        })
}

@Composable
private fun DetailTopTracks(mode: DetailRankMode, onToggle: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Top tracks", style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 16.5.sp, lineHeight = 20.63.sp, fontWeight = FontWeight(620)),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { heading() })
        TextButton(onToggle, Modifier.width((82.2f * fontScale).dp).heightIn(min = 48.dp).semantics {
            contentDescription = "Top tracks: ${mode.label}. Switch to ${mode.next.label}"
            selected = mode == DetailRankMode.PERSONAL
        }, contentPadding = PaddingValues(vertical = 8.dp)) {
            Text(mode.label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 12.45.sp, lineHeight = 16.81.sp, textDecoration = TextDecoration.Underline),
                textAlign = TextAlign.End, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
    Text(DETAIL_RANKINGS_UNAVAILABLE, Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.25.sp))
}

@Composable
private fun DetailActionError(error: String?) {
    if (error != null) Text(error, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailLoadState(state: DetailState, model: DetailViewModel) {
    // This item is composed at the end of the visible grid, so continuation is demand-driven.
    LaunchedEffect(state.nextOffset, state.loading, state.failed) {
        if (state.nextOffset != null && !state.loading && !state.failed) model.more()
    }
    when {
        state.loading -> CircularProgressIndicator(Modifier.padding(vertical = 24.dp).size(24.dp)
            .semantics { contentDescription = "Loading music" })
        state.failed -> Text("Can’t load music", Modifier.padding(vertical = 24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.loaded && state.rows.isEmpty() && state.item?.kind != MusicKind.TRACK ->
            Text(if (state.item?.kind == MusicKind.ARTIST) "No releases yet" else "No songs yet",
                Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
