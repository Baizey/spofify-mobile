package dev.spofify.mobile.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import dev.spofify.mobile.designsystem.SpofifyGlyph
import dev.spofify.mobile.designsystem.SpofifyIcon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.library.*
import dev.spofify.mobile.core.playback.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(
    model: SearchViewModel, loader: ImageLoader, focusIntent: Int, onOpen: (MusicItem) -> Unit,
    playback: PlaybackController? = null,
    onMore: ((MusicItem) -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    val illumination by animateFloatAsState(if (editing) 1f else 0f, tween(240), label = "Search focus illumination")
    val player = playback?.state?.collectAsStateWithLifecycle()?.value
    val ready = player?.connection == PlaybackConnectionState.READY
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) focus.clearFocus()
        imeWasVisible = imeVisible
    }
    fun queue(item: MusicItem, next: Boolean) {
        if (item.kind == MusicKind.TRACK) playback?.queueTrack(PlaybackTrackReference(item.id), next = next)
        else playback?.queueCollection(PlaybackContext(item.kind.name.lowercase(java.util.Locale.ROOT), item.id, item.name), next)
    }
    DisposableEffect(model) {
        onDispose { model.pause() }
    }
    LaunchedEffect(model, focusIntent) {
        if (model.consumeFocusIntent(focusIntent)) {
            model.query("")
            requester.requestFocus()
            keyboard?.show()
        } else {
            model.resume()
        }
    }
    LaunchedEffect(state.query) { scroll.scrollToItem(0) }
    BackHandler(editing) {
        focus.clearFocus()
        keyboard?.hide()
    }
    Column(Modifier.fillMaxSize()) {
        if (!editing) Box(Modifier.padding(horizontal = 20.dp)) { PageTitle("Search") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, reverseLayout = true,
            contentPadding = PaddingValues(top = 8.dp)) {
            items(state.items, key = MusicItem::key) { item ->
                val playable = ready && if (item.kind == MusicKind.TRACK) item.hasAudio else item.availableTracks > 0
                MusicRow(item, loader,
                    onQueue = if (playable) ({ queue(item, false) }) else null,
                    onMore = onMore?.let { more -> { more(item) } },
                    gestureBinding = Triple(item.key, state.query, player?.snapshot?.stateVersion)) { onOpen(item) }
            }
            if (state.query.isNotBlank()) {
                item("search-status") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = MusicRowGeometry.EDGE_GUTTER.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        state.error?.let { error ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(searchErrorMessage(error), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(model::retry) { Text(if (state.retryStartsOver) "Search again" else "Retry") }
                            }
                        }
                        if (state.busy) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                        else if (state.empty) Text("No matches", Modifier.padding(vertical = 24.dp))
                        else if (state.hasMore) TextButton(model::more, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("More results") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Brush.verticalGradient(
            0f to Color.Transparent, .2f to Color.Transparent,
            1f to MaterialTheme.colorScheme.secondary.copy(alpha = .16f * illumination)))
            .padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SpofifyIcon(SpofifyGlyph.Search, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(state.query, model::query, Modifier.weight(1f).heightIn(min = 54.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .focusRequester(requester).onFocusChanged { editing = it.isFocused }
                .semantics { contentDescription = "Search music" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                decorationBox = { input ->
                    if (state.query.isEmpty()) Text("Search your music", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    input()
                })
            if (state.query.isNotEmpty()) IconButton({ model.query(""); requester.requestFocus(); keyboard?.show() }, Modifier.size(48.dp)) {
                SpofifyIcon(SpofifyGlyph.Close, "Clear search", Modifier.size(20.dp))
            }
        }
        HorizontalDivider(thickness = if (editing) 2.dp else 1.dp,
            color = if (editing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
    }
}
