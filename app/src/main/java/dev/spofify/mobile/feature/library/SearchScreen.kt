package dev.spofify.mobile.feature.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
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
) {
    val state by model.state.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
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
        model.resume()
        onDispose { model.pause() }
    }
    LaunchedEffect(focusIntent) {
        if (model.consumeFocusIntent(focusIntent)) {
            model.query("")
            requester.requestFocus()
            keyboard?.show()
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
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            items(state.items, key = MusicItem::key) { item ->
                val playable = ready && if (item.kind == MusicKind.TRACK) item.hasAudio else item.availableTracks > 0
                MusicRow(item, loader,
                    onQueue = if (playable) ({ queue(item, false) }) else null,
                    onPlayNext = if (playable) ({ queue(item, true) }) else null) { onOpen(item) }
            }
            if (state.query.isNotBlank()) {
                item("search-status") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        state.groups.filterValues { it.failed }.forEach { (kind, _) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Can't load ${kind.plural}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton({ model.retry(kind) }) { Text("Retry") }
                            }
                        }
                        if (state.busy) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                        else if (state.empty) Text("No matches", Modifier.padding(vertical = 24.dp))
                        else if (state.hasMore) TextButton(model::more) { Text("More") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("⌕", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleLarge)
            BasicTextField(state.query, model::query, Modifier.weight(1f).padding(horizontal = 12.dp)
                .focusRequester(requester).onFocusChanged { editing = it.isFocused }
                .semantics { contentDescription = "Search music" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                decorationBox = { input ->
                    if (state.query.isEmpty()) Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    input()
                })
            if (state.query.isNotEmpty()) TextButton({ model.query("") }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "Clear search" }) { Text("×") }
        }
        HorizontalDivider(color = if (editing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)
    }
}
