package dev.spofify.mobile.feature.personal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.playback.LivePersonal
import dev.spofify.mobile.core.personal.PersonalTrack
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.MusicArtwork
import dev.spofify.mobile.feature.library.MusicRow

@Composable
internal fun PersonalSongRow(
    track: PersonalTrack,
    loader: ImageLoader,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    onQueue: (() -> Unit)? = null,
    gestureBinding: Any = track,
    downloaded: Boolean = false,
    downloading: Boolean = false,
) {
    MusicRow(track.musicItem(), loader, onQueue = onQueue, onMore = onMore, gestureBinding = gestureBinding,
        availability = when {
            downloading -> "Downloading"
            downloaded -> "Downloaded"
            !track.hasAudio -> "Unavailable"
            else -> null
        }, onOpen = onPlay)
}

/** No permanent refresh control or healthy-state helper prose. */
@Composable
internal fun PersonalStatus(loading: Boolean, error: String?) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    if (error != null) Column(Modifier.padding(vertical = 12.dp)) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PersonalSongFilter(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val amount by animateFloatAsState(if (focused) 1f else 0f, tween(240), label = "filter illumination")
    val colors = MaterialTheme.colorScheme
    val glow = colors.primary.copy(alpha = .16f * amount)
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp)
        .spofifyField(focused, illumination = Brush.verticalGradient(0f to glow, .8f to Color.Transparent, 1f to Color.Transparent))) {
        BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }.padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = "Filter songs" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            cursorBrush = SolidColor(colors.primary),
            decorationBox = { field ->
                if (value.isEmpty()) Text("Filter songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                field()
            })
    }
}

@Composable
internal fun PersonalConfirmSheet(title: String, consequence: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    SpofifySheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            SheetHeading(title)
            Text(consequence, Modifier.padding(bottom = 20.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PersonalSheetButtons(confirm, true, onDismiss, onConfirm)
        }
    }
}

@Composable
private fun PersonalSheetButtons(label: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onDismiss, Modifier.weight(1f).heightIn(min = 52.dp), colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Cancel") }
        Button(onConfirm, Modifier.weight(1f).heightIn(min = 52.dp), enabled = enabled, shape = SpofifySurfaces.control) { Text(label) }
    }
}

/** FINAL name sheet edits Name only; PATCH still carries the unchanged server description. */
@Composable
internal fun PlaylistNameSheet(
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val valid = name.isNotBlank() && name.codePointCount(0, name.length) <= 100 && name.none(Char::isISOControl)
    val colors = MaterialTheme.colorScheme
    val amount by animateFloatAsState(if (focused) 1f else 0f, tween(240), label = "name illumination")
    val glow = colors.tertiary.copy(alpha = .16f * amount)
    LaunchedEffect(Unit) { requester.requestFocus() }
    SpofifySheet(onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 22.dp)) {
            Box(Modifier.padding(horizontal = 20.dp)) { SheetHeading(title) }
            Text("Name", Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 9.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.6.sp, fontWeight = FontWeight.Medium))
            BasicTextField(name, { name = it }, Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .focusRequester(requester).onFocusChanged { focused = it.isFocused }
                .spofifyField(focused, focusColor = colors.tertiary,
                    illumination = Brush.verticalGradient(0f to Color.Transparent, .2f to Color.Transparent, 1f to glow))
                .padding(horizontal = 20.dp, vertical = 14.dp).semantics { contentDescription = "Playlist name" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onSave(name, initialDescription) }),
                cursorBrush = SolidColor(colors.tertiary))
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                PersonalSheetButtons(if (initialName.isEmpty()) "Create" else "Save", valid, onDismiss) { onSave(name, initialDescription) }
            }
        }
    }
}

@Composable
internal fun PersonalSongActions(
    api: LivePersonal,
    track: PersonalTrack,
    loader: ImageLoader,
    playable: Boolean,
    metadataMissing: Boolean = false,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onNavigate: (MusicKind, String) -> Unit,
    onRemove: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    downloadAction: Pair<String, () -> Unit>? = null,
    downloading: Boolean = false,
) {
    var adding by remember(track.id) { mutableStateOf(false) }
    val personal by api.replica.view.collectAsState()
    val connected = personal.connected
    if (adding) {
        AddTrackToPlaylistSheet(api, track.musicItem(), onDismiss = { adding = false })
    } else SpofifySheet(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            item("heading") {
                Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    MusicArtwork(track.musicItem(), loader, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.name.ifBlank { "Unavailable track" }, style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 22.5.sp, fontWeight = FontWeight(650), lineHeight = 26.1.sp), modifier = Modifier.semantics { heading() })
                        val credits = track.artists.joinToString(", ") { it.name }
                        if (credits.isNotBlank()) Text(credits, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SheetGroup("Listen")
            }
            item("play") { SheetAction("Play now", SpofifyGlyph.Play, playable) {
                onDismiss()
                onPlay()
            } }
            item("queue") { SheetAction("Queue", SpofifyGlyph.Queue, playable) {
                onDismiss()
                onQueue()
            } }
            item("library") { SheetGroup("Library") }
            if (downloading) item("downloading") { PersonalStatus(true, "Downloading…") }
            downloadAction?.let { (label, action) -> item("download") {
                SheetAction(label, SpofifyGlyph.Down, !downloading) { action() }
            } }
            item("add") { SheetAction("Add to playlist", SpofifyGlyph.Plus, connected && track.hasAudio) { adding = true } }
            onRemove?.let { remove -> item("remove") { SheetAction("Remove this entry", SpofifyGlyph.Trash) {
                onDismiss()
                remove()
            } } }
            if (!metadataMissing && (track.artists.any { it.id.isNotBlank() } || track.albumId.isNotBlank())) {
                item("go-to") { SheetGroup("Go to") }
                items(track.artists.size, key = { "artist-$it" }) { index ->
                    val artist = track.artists[index]
                    if (artist.id.isNotBlank()) SheetAction(artist.name, SpofifyGlyph.Artist) {
                        onDismiss()
                        onNavigate(MusicKind.ARTIST, artist.id)
                    }
                }
                if (track.albumId.isNotBlank()) item("album") {
                    SheetAction(track.albumName.ifBlank { "Album" }, SpofifyGlyph.Album) {
                        onDismiss()
                        onNavigate(MusicKind.ALBUM, track.albumId)
                    }
                }
            }
        }
    }
}
