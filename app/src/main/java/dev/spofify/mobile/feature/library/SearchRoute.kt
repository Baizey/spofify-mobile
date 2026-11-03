package dev.spofify.mobile.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Query-driven listener search. Empty queries never read the global catalog.
 * onOpenItem is a navigation notification, not a request for the host to render details:
 * artist/album/song details and credit navigation are owned by this route's Back stack.
 * Like callbacks must resolve unknown/failed status before attempting an actual toggle.
 */
@Composable
fun SearchRoute(
    client: SpofifyClient,
    initialQuery: String = "",
    onOpenItem: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem, Boolean) -> Unit,
    onShowActions: (LibraryItem) -> Unit,
    onEnqueueItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(),
    likes: Map<String, CatalogLikeState> = emptyMap(),
    onToggleLike: ((LibraryItem) -> Unit)? = null,
    onVisibleItems: (List<LibraryItem>) -> Unit = {},
    stateHolder: CatalogStateHolder? = null,
) = CatalogRoute(client, "all", initialQuery, null, onOpenItem, onPlayItem, onShowActions,
    onEnqueueItem, artwork, modifier, playback, likes, onToggleLike, onVisibleItems, stateHolder)

/** Compatibility entry point. Still query-driven; this is not a top-level catalog landing. */
@Composable
fun LibraryRoute(
    client: SpofifyClient,
    initialKind: String = "albums",
    initialQuery: String = "",
    onOpenItem: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem, Boolean) -> Unit,
    onShowActions: (LibraryItem) -> Unit,
    onEnqueueItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(),
    likes: Map<String, CatalogLikeState> = emptyMap(),
    onToggleLike: ((LibraryItem) -> Unit)? = null,
    onVisibleItems: (List<LibraryItem>) -> Unit = {},
    stateHolder: CatalogStateHolder? = null,
) = CatalogRoute(client, initialKind, initialQuery, null, onOpenItem, onPlayItem, onShowActions,
    onEnqueueItem, artwork, modifier, playback, likes, onToggleLike, onVisibleItems, stateHolder)

/** For explicit song/credit links from Home, playlists or Now Playing, without a catalog prefetch. */
@Composable
fun ItemDetailsRoute(
    client: SpofifyClient,
    item: LibraryItem,
    onOpenItem: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem, Boolean) -> Unit,
    onShowActions: (LibraryItem) -> Unit,
    onEnqueueItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(),
    likes: Map<String, CatalogLikeState> = emptyMap(),
    onToggleLike: ((LibraryItem) -> Unit)? = null,
    onVisibleItems: (List<LibraryItem>) -> Unit = {},
    stateHolder: CatalogStateHolder? = null,
) = CatalogRoute(client, "all", "", item, onOpenItem, onPlayItem, onShowActions,
    onEnqueueItem, artwork, modifier, playback, likes, onToggleLike, onVisibleItems, stateHolder)

@Composable
private fun CatalogRoute(
    client: SpofifyClient,
    initialKind: String,
    initialQuery: String,
    initialItem: LibraryItem?,
    onOpenItem: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem, Boolean) -> Unit,
    onShowActions: (LibraryItem) -> Unit,
    onEnqueueItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier,
    playback: CatalogPlaybackFeedback,
    likes: Map<String, CatalogLikeState>,
    onToggleLike: ((LibraryItem) -> Unit)?,
    onVisibleItems: (List<LibraryItem>) -> Unit,
    stateHolder: CatalogStateHolder?,
) {
    val scope = rememberCoroutineScope()
    val account = client.currentSession()?.accountId
    val holder = stateHolder ?: remember(client, account, initialKind, initialQuery, initialItem?.kind, initialItem?.id) {
        CatalogStateHolder(ClientCatalogReader(client), scope, initialKind, initialQuery, initialItem)
    }
    val navigation by holder.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(holder) { onDispose { holder.close() } }
    LaunchedEffect(holder, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            holder.setVisible(true)
            try {
                while (true) {
                    holder.checkRevision()
                    delay(15_000)
                }
            } finally { holder.setVisible(false) }
        }
    }
    BackHandler(navigation.canGoBack) { holder.back() }
    val visibleCallback by rememberUpdatedState(onVisibleItems)
    val screen = navigation.current
    LaunchedEffect(screen.item, screen.sections) {
        visibleCallback(listOfNotNull(screen.item) + screen.sections.values.flatMap { it.items })
    }
    CatalogScreenContent(navigation, holder, { item -> holder.open(item); onOpenItem(item) },
        onPlayItem, onShowActions, onEnqueueItem, artwork, modifier, playback, likes, onToggleLike)
}

@Composable
internal fun CatalogScreenContent(
    navigation: CatalogNavigationState,
    holder: CatalogStateHolder,
    onOpen: (LibraryItem) -> Unit,
    onPlay: (LibraryItem, Boolean) -> Unit,
    onMore: (LibraryItem) -> Unit,
    onQueue: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(),
    likes: Map<String, CatalogLikeState> = emptyMap(),
    onToggleLike: ((LibraryItem) -> Unit)? = null,
) {
    val screen = navigation.current
    val scroll = remember(screen.key, screen.kind, screen.query, screen.includeMissing, screen.releaseGroup) {
        LazyListState(screen.scrollIndex, screen.scrollOffset)
    }
    LaunchedEffect(scroll, screen.key) {
        snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged()
            .collect { (index, offset) -> holder.rememberScroll(screen.key, index, offset) }
    }
    Column(modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (navigation.canGoBack) item(key = "back") {
            TextButton(onClick = { holder.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("Back") }
        }
        val detail = screen.item
        if (detail == null) {
            if (!screen.emptySearch) item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("all") + CatalogKinds).forEach { kind ->
                        FilterChip(selected = screen.kind == kind, onClick = { holder.selectKind(kind) }, label = { Text(catalogLabel(kind)) })
                    }
                    FilterChip(selected = screen.includeMissing, onClick = { holder.includeMissing(!screen.includeMissing) },
                        label = { Text("Include missing") })
                }
            }
            if (screen.emptySearch) item(key = "prompt") {
                Text("Find an artist, album or song in your saved music. Start typing to search.",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (screen.detailUnavailable) {
            item(key = "missing-detail") {
                Text(detail.name, style = MaterialTheme.typography.headlineSmall)
                LoadError(screen.detailError ?: "This music is no longer saved.", "Retry details", holder::retryDetail)
            }
        } else {
            item(key = "hero") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        artwork(detail, Modifier.size(88.dp))
                        Column(Modifier.weight(1f)) {
                            Text(detail.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            MusicCredits(detail, onOpen)
                            itemMetadata(detail).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (detail.genres.isNotEmpty()) Text(detail.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    if (detail.kind == "track" && (detail.discNumber > 0 || detail.trackNumber > 0)) Text(
                        listOfNotNull(detail.discNumber.takeIf { it > 0 }?.let { "Disc $it" },
                            detail.trackNumber.takeIf { it > 0 }?.let { "Track $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val current = detail.kind == "track" && detail.id == playback.trackId
                        Button(onClick = {
                            if (current && playback.onToggle != null) playback.onToggle.invoke() else onPlay(detail, false)
                        }, enabled = detail.playable) { Text(if (current && playback.isPlaying) "Pause" else "Play") }
                        if (detail.kind != "track") OutlinedButton(onClick = { onPlay(detail, true) }, enabled = detail.playable) { Text("Shuffle") }
                        IconButton(onClick = { onMore(detail) }) { Icon(Icons.Default.MoreVert, "More actions for ${detail.name}") }
                    }
                    if (detail.kind == "track" && onToggleLike != null) {
                        val like = likes[detail.id]
                        OutlinedButton(onClick = { onToggleLike(detail) }, enabled = like?.pending != true) {
                            Text(when { like?.pending == true -> "Saving…"; like?.failed == true -> "Retry like status";
                                like?.liked == true -> "Unlike"; like?.liked == false -> "Like"; else -> "Load like status" })
                        }
                    }
                    if (screen.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    screen.detailError?.let { LoadError(it, "Retry details", holder::retryDetail) }
                }
            }
            if (detail.kind == "artist") item(key = "release-groups") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReleaseGroups.forEach { (group, label) ->
                        FilterChip(screen.releaseGroup == group, onClick = { holder.selectReleaseGroup(group) }, label = { Text(label) })
                    }
                }
            }
        }
        if (!screen.emptySearch && !screen.detailUnavailable) screen.sectionKinds.forEach { kind ->
            val section = screen.sections[kind] ?: CatalogSection(limit = screen.request(kind).limit)
            item(key = "$kind:heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (detail?.kind == "artist") ReleaseGroups.first { it.first == screen.releaseGroup }.second else catalogLabel(kind),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (section.loaded) Text("${section.total}", style = MaterialTheme.typography.labelLarge)
                    if (section.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            section.error?.let { error -> item(key = "$kind:error") { LoadError(error, "Retry ${catalogLabel(kind).lowercase()}") { holder.retry(kind) } } }
            if (section.loaded && section.items.isEmpty()) item(key = "$kind:empty") {
                Text(if (detail != null) "No saved ${catalogLabel(kind).lowercase()} in this group." else "No ${catalogLabel(kind).lowercase()} match this search.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
            itemsIndexed(section.items, key = { index, item -> "$kind:${section.offset}:$index:${item.id}" }) { _, item ->
                MusicRow(item, onOpen, onPlay, onMore, onQueue, artwork, playback = playback,
                    like = likes[item.id], onToggleLike = onToggleLike)
            }
            if (section.hasPrevious || section.hasNext) item(key = "$kind:pager") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { holder.page(kind, -1) }, enabled = section.hasPrevious && !section.loading) { Text("Previous") }
                    Text("${if (section.total == 0) 0 else section.offset + 1}–${minOf(section.offset + section.limit, section.total)} of ${section.total}",
                        style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { holder.page(kind, 1) }, enabled = section.hasNext && !section.loading) { Text("Next") }
                }
            }
        }
    }
    if (screen.item == null) Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("search-dock")) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            CommittedSearchField(screen.query, holder::setComposing, holder::commitQuery)
        }
    }
    }
}

@Composable
internal fun CommittedSearchField(query: String, onCompositionChanged: (Boolean) -> Unit = {}, commit: (String, Boolean) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(query)) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { onCompositionChanged(false) } }
    LaunchedEffect(query) {
        if (field.text != query && field.composition == null) field = TextFieldValue(query, TextRange(query.length))
    }
    OutlinedTextField(value = field, onValueChange = { next ->
        if (next.text.codePointCount(0, next.text.length) <= 200) {
            field = next
            if (next.composition == null) commit(next.text, next.text.isEmpty())
            onCompositionChanged(next.composition != null)
        }
    }, singleLine = true, placeholder = { Text("Search your music") }, shape = MaterialTheme.shapes.extraLarge,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (field.text.isNotEmpty()) IconButton(onClick = { field = TextFieldValue(""); commit("", true); onCompositionChanged(false) }) { Icon(Icons.Default.Close, "Clear search") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { commit(field.text, true); onCompositionChanged(false); keyboard?.hide() }), modifier = Modifier.fillMaxWidth().testTag("search-query").semantics { contentDescription = "Search your music" })
}

@Composable
internal fun LoadError(message: String, label: String, retry: () -> Unit) {
    Column { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = retry) { Text(label) } }
}
