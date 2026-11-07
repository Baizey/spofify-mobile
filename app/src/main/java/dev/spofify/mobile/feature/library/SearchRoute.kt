package dev.spofify.mobile.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.ui.AutomaticListDemand
import dev.spofify.mobile.ui.AppendStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

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
    onSearchEditingChanged: (Boolean) -> Unit = {},
    searchActivationId: Long? = null,
    onSearchActivationConsumed: (Long) -> Unit = {},
    sourceEpoch: Any? = null,
) = CatalogRoute(client, "all", initialQuery, null, onOpenItem, onPlayItem, onShowActions,
    onEnqueueItem, artwork, modifier, playback, likes, onToggleLike, onVisibleItems, stateHolder,
    onSearchEditingChanged, searchActivationId, onSearchActivationConsumed, sourceEpoch)

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
    sourceEpoch: Any? = null,
) = CatalogRoute(client, initialKind, initialQuery, null, onOpenItem, onPlayItem, onShowActions,
    onEnqueueItem, artwork, modifier, playback, likes, onToggleLike, onVisibleItems, stateHolder, sourceEpoch = sourceEpoch)

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
    sourceEpoch: Any? = null,
) = CatalogRoute(client, "all", "", item, onOpenItem, onPlayItem, onShowActions,
    onEnqueueItem, artwork, modifier, playback, likes, onToggleLike, onVisibleItems, stateHolder, sourceEpoch = sourceEpoch)

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
    onSearchEditingChanged: (Boolean) -> Unit = {},
    searchActivationId: Long? = null,
    onSearchActivationConsumed: (Long) -> Unit = {},
    sourceEpoch: Any? = null,
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(navigation.canGoBack && !imeVisible) { holder.back() }
    val visibleCallback by rememberUpdatedState(onVisibleItems)
    val screen = navigation.current
    LaunchedEffect(screen.item, screen.sections) {
        visibleCallback(listOfNotNull(screen.item) + screen.sections.values.flatMap { it.items })
    }
    CatalogScreenContent(navigation, holder, { item ->
        focus.clearFocus(); keyboard?.hide()
        holder.open(item); onOpenItem(item)
    },
        { item, shuffle -> focus.clearFocus(); keyboard?.hide(); onPlayItem(item, shuffle) },
        { item -> focus.clearFocus(); keyboard?.hide(); onShowActions(item) },
        onEnqueueItem, artwork, modifier, playback, likes, onToggleLike, onSearchEditingChanged,
        searchActivationId, onSearchActivationConsumed, sourceEpoch)
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
    onSearchEditingChanged: (Boolean) -> Unit = {},
    searchActivationId: Long? = null,
    onSearchActivationConsumed: (Long) -> Unit = {},
    sourceEpoch: Any? = null,
) {
    val screen = navigation.current
    // Capture the source incarnation at composition, not from a latest callback at release.
    // Reading the holder again at dispatch also fences an unsampled query/navigation ABA.
    val sourceVersion = holder.navigationVersion
    val sourceCurrent = {
        val current = holder.state.value.current
        holder.navigationVersion == sourceVersion && current.key == screen.key &&
            current.resultGeneration == screen.resultGeneration && current.query == screen.query &&
            current.kind == screen.kind && current.includeMissing == screen.includeMissing &&
            current.releaseGroup == screen.releaseGroup && current.item?.kind == screen.item?.kind &&
            current.item?.id == screen.item?.id && !current.detailUnavailable
    }
    val sourceOpen: (LibraryItem) -> Unit = { if (sourceCurrent()) onOpen(it) }
    val sourcePlay: (LibraryItem, Boolean) -> Unit = { item, shuffle -> if (sourceCurrent()) onPlay(item, shuffle) }
    val sourceMore: (LibraryItem) -> Unit = { if (sourceCurrent()) onMore(it) }
    val sourceQueue: (LibraryItem) -> Unit = { if (sourceCurrent()) onQueue(it) }
    val sourceLike = onToggleLike?.let { toggle -> { item: LibraryItem -> if (sourceCurrent()) toggle(item) } }
    val sourcePlayback = playback.copy(
        onPlayNow = playback.onPlayNow?.let { dispatch -> { item, rowCurrent ->
            val isCurrent = { sourceCurrent() && rowCurrent() }
            if (isCurrent()) dispatch(item, isCurrent)
        } },
        onContextual = playback.onContextual?.let { dispatch -> { item, playerKey, gestureCurrent ->
            val isCurrent = { sourceCurrent() && gestureCurrent() }
            if (isCurrent()) dispatch(item, playerKey, isCurrent)
        } },
        onUnselectedHigh = playback.onUnselectedHigh?.let { dispatch -> { item, origin, gestureCurrent ->
            val isCurrent = { sourceCurrent() && gestureCurrent() }
            if (isCurrent()) dispatch(item, origin, isCurrent)
        } },
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
    val releaseColumns = artistReleaseColumns(
        (maxWidth - MusicListLayout.horizontalPadding * 2).value, LocalDensity.current.fontScale)
    fun layoutKey(kind: String, index: Int, item: LibraryItem): String =
        if (screen.item?.kind == "artist" && kind == "albums")
            catalogRowKey(screen, kind, screen.sections.getValue(kind).items[index / releaseColumns * releaseColumns])
        else catalogRowKey(screen, kind, item)
    val gridColumns = releaseColumns.takeIf { screen.item?.kind == "artist" } ?: 0
    val scroll = remember(screen.key, screen.resultGeneration, gridColumns) {
        LazyListState(artistReleaseScrollIndex(screen, releaseColumns), screen.scrollOffset)
    }
    val releaseAnchors = rememberUpdatedState(
        if (gridColumns == 0) emptyMap() else screen.sections["albums"]?.items.orEmpty()
            .chunked(releaseColumns).associate { row -> catalogRowKey(screen, "albums", row.first()) to row.first().id })
    val rowKinds = remember(screen.key, screen.resultGeneration, screen.sections, releaseColumns) {
        buildMap<Any, String> {
            screen.sectionKinds.forEach { kind ->
                val section = screen.sections[kind]
                section?.items.orEmpty().forEachIndexed { index, item -> put(layoutKey(kind, index, item), kind) }
                // A later group's visible Retry is a real boundary even when it has no rows.
                // It can release an earlier group's yield, but never supplies error demand.
                if (section?.error != null || section?.let { it.loading && it.loaded } == true)
                    put("$kind:status", kind)
            }
        }
    }
    val lastVisibleKind by remember(scroll, rowKinds) { derivedStateOf {
        val layout = scroll.layoutInfo
        layout.visibleItemsInfo.lastOrNull { row ->
            row.offset < layout.viewportEndOffset && row.offset + row.size > layout.viewportStartOffset && row.key in rowKinds
        }?.let { rowKinds[it.key] }
    } }
    LaunchedEffect(screen.key, screen.resultGeneration, lastVisibleKind) {
        holder.visibleSection(screen.key, screen.resultGeneration, lastVisibleKind)
    }
    screen.sectionKinds.forEach { kind ->
        val section = screen.sections[kind]
        val grouped = screen.item == null && screen.search && screen.kind == "all"
        val frontier = section?.items.orEmpty().let { items ->
            ((items.size - if (grouped) 1 else 3).coerceAtLeast(0) until items.size)
                .map { layoutKey(kind, it, items[it]) }.distinct()
        }
        AutomaticListDemand(scroll, Triple(screen.key, screen.resultGeneration, holder.navigationVersion), frontier,
            frontierProgress = if (gridColumns > 0 && kind == "albums") section?.nextOffset ?: 0 else frontier,
            enabled = lastVisibleKind == kind && section?.hasNext == true && !section.loading &&
                (!section.demandPaused || !screen.hasLaterGroups(kind)) &&
                section.error == null && !section.needsReload && !screen.stale && !screen.detailUnavailable,
            onLoad = { holder.demandMore(kind) })
    }
    LaunchedEffect(scroll, screen.key, screen.resultGeneration, screen.query, screen.releaseGroup) {
        snapshotFlow {
            val index = scroll.firstVisibleItemIndex
            // Padding-only rows may precede this index; pair identity with this exact offset.
            // An unmeasured replacement list must not erase its saved release anchor.
            val first = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                ?: return@snapshotFlow null
            Triple(index, scroll.firstVisibleItemScrollOffset, releaseAnchors.value[first.key])
        }.filterNotNull().distinctUntilChanged().collect { (index, offset, releaseId) ->
            holder.rememberScroll(screen.key, index, offset, screen.query, screen.releaseGroup, releaseId)
        }
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("catalog-results"), state = scroll,
        contentPadding = PaddingValues(horizontal = MusicListLayout.horizontalPadding, vertical = 8.dp),
        verticalArrangement = MusicListLayout.arrangement) {
        val detail = screen.item
        if (detail == null && !screen.emptySearch && screen.sections.values.any { it.loading && !it.loaded }) item(key = "search-loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Searching saved music" })
        }
        if (detail != null && screen.detailUnavailable) {
            item(key = "missing-detail") {
                Text(detail.name, style = MaterialTheme.typography.headlineSmall)
                LoadError(screen.detailError ?: "This music is no longer saved.", "Retry details", holder::retryDetail)
            }
        } else if (detail != null) {
            item(key = "hero") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (detail.kind == "track") Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        artwork(detail, Modifier.size(88.dp))
                        Column(Modifier.weight(1f)) {
                            Text(detail.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            MusicCredits(detail, sourceOpen)
                            itemMetadata(detail).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    } else DetailIdentity(detail.name, itemMetadata(detail),
                        artwork = { artwork(detail, it) }, artist = detail.kind == "artist",
                        credits = { DetailCredits(detail, sourceOpen) })
                    if (detail.genres.isNotEmpty()) Text(detail.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    if (detail.kind == "track" && (detail.discNumber > 0 || detail.trackNumber > 0)) Text(
                        listOfNotNull(detail.discNumber.takeIf { it > 0 }?.let { "Disc $it" },
                            detail.trackNumber.takeIf { it > 0 }?.let { "Track $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        // Listening controls wrap at large text; More always retains its own
                        // disjoint target instead of falling beyond a nonwrapping action row.
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val current = detail.kind == "track" && detail.id == playback.trackId
                            Button(onClick = {
                                if (current && playback.onToggle != null) playback.onToggle.invoke() else sourcePlay(detail, false)
                            }, enabled = detail.playable) { Text(if (current && playback.isPlaying) "Pause" else "Play") }
                            if (detail.kind != "track") OutlinedButton(onClick = { sourcePlay(detail, true) }, enabled = detail.playable) { Text("Shuffle") }
                        }
                        IconButton(onClick = { sourceMore(detail) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.MoreVert, "More actions for ${detail.name}")
                        }
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
            if (detail != null) item(key = "$kind:heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (detail?.kind == "artist") ReleaseGroups.first { it.first == screen.releaseGroup }.second else catalogLabel(kind),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (section.loaded) Text("${section.total}", style = MaterialTheme.typography.labelLarge)
                    if (section.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (detail != null && section.loaded && section.items.isEmpty()) item(key = "$kind:empty") {
                Text("No saved ${catalogLabel(kind).lowercase()} in this group.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
            if (detail?.kind == "artist" && kind == "albums") {
                val releaseRows = section.items.chunked(releaseColumns)
                itemsIndexed(releaseRows, key = { _, row -> catalogRowKey(screen, kind, row.first()) }) { _, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { release ->
                            key(release.id, sourceVersion, screen.resultGeneration, sourceEpoch) {
                                ArtistReleaseCard(release, sourceOpen, sourceMore, artwork, Modifier.weight(1f))
                            }
                        }
                        if (row.size < releaseColumns) Spacer(Modifier.weight(1f))
                    }
                }
            } else itemsIndexed(section.items, key = { _, item -> catalogRowKey(screen, kind, item) }) { _, item ->
                // Keep the existing shared song body, occurrence and held-action semantics.
                key(sourceVersion, screen.resultGeneration, sourceEpoch) {
                    MusicRow(item, sourceOpen, sourcePlay, sourceMore, sourceQueue, artwork,
                        playback = sourcePlayback, like = likes[item.id], onToggleLike = sourceLike)
                }
            }
            if (section.loading && section.loaded || section.error != null) item(key = "$kind:status") {
                AppendStatus(section.loading, section.error.takeUnless { section.loading }, { holder.retry(kind) },
                    loadingLabel = "Loading more ${catalogLabel(kind).lowercase()}",
                    retryLabel = "${if (section.refreshRequired) "Refresh" else "Retry"} ${catalogLabel(kind).lowercase()}")
            }
            if (detail != null && section.loaded && !section.loading && section.error == null && !section.needsReload && section.ended && section.items.isNotEmpty())
                item(key = "$kind:end") {
                    Text("End of ${catalogLabel(kind).lowercase()}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
        }
        if (detail == null && !screen.emptySearch && screen.sections.values.any { it.items.isNotEmpty() } &&
            screen.sectionKinds.all { kind -> screen.sections[kind]?.let {
                it.loaded && it.ended && !it.loading && !it.needsReload && it.error == null
            } == true }) item(key = "search-end") {
            Text("End of results", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (detail == null && !screen.emptySearch && screen.sectionKinds.all { kind ->
            screen.sections[kind]?.let { it.loaded && !it.loading && it.items.isEmpty() && it.error == null } == true
        }) item(key = "search-empty") {
            Text("No music matches this search.", Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (screen.item == null) Surface(color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().testTag("search-dock")) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            CommittedSearchField(screen.query, holder::setComposing,
                onSearchEditingChanged = onSearchEditingChanged,
                activationId = searchActivationId, onActivationConsumed = onSearchActivationConsumed,
                commit = holder::commitQuery)
        }
    }
    }
    }
}

/** Stable through append/revalidation; a result reset (including ABA) gets a new incarnation. */
internal fun catalogRowKey(screen: CatalogScreen, kind: String, item: LibraryItem): String =
    "${screen.key}:${screen.resultGeneration}:$kind:${item.id}"

@Composable
internal fun CommittedSearchField(
    query: String,
    onCompositionChanged: (Boolean) -> Unit = {},
    onSearchEditingChanged: (Boolean) -> Unit = {},
    activationId: Long? = null,
    onActivationConsumed: (Long) -> Unit = {},
    commit: (String, Boolean) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(query)) }
    var editing by remember { mutableStateOf(false) }
    var imeShownForFocus by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val restingFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var requestingRest by remember { mutableStateOf(false) }
    var resting by remember { mutableStateOf(false) }
    fun endEditing() {
        // Clearing the Android owner can immediately refocus its sole field outside touch mode.
        // Keep focus inside Compose instead, without introducing a keyboard traversal stop.
        requestingRest = true
        try { restingFocus.requestFocus(FocusDirection.Exit) }
        finally { requestingRest = false }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val editingChanged by rememberUpdatedState(onSearchEditingChanged)
    val compositionChanged by rememberUpdatedState(onCompositionChanged)
    val activationConsumed by rememberUpdatedState(onActivationConsumed)
    var deliveredActivation by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(activationId, lifecycle) {
        val activation = activationId ?: return@LaunchedEffect
        if (deliveredActivation == activation) return@LaunchedEffect
        // A deliberate nav tap overrides even an uncommitted composing draft. Ordinary query
        // reconciliation below intentionally does not. The owner clears accepted state first.
        field = TextFieldValue("")
        compositionChanged(false)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (deliveredActivation == activation) awaitCancellation()
            // Await attachment/layout, not the navigation item's lifetime: focusing hides that
            // item immediately, but must not cancel this field-owned delivery.
            withFrameNanos { }
            while (!fieldFocus.requestFocus()) withFrameNanos { }
            deliveredActivation = activation
            keyboard?.show()
            activationConsumed(activation)
            awaitCancellation()
        }
    }
    DisposableEffect(Unit) { onDispose { compositionChanged(false); editingChanged(false) } }
    LaunchedEffect(editing, imeVisible) {
        if (!editing) imeShownForFocus = false
        else if (imeVisible) imeShownForFocus = true
        else if (imeShownForFocus) endEditing()
    }
    // With a hardware keyboard there is no IME to dismiss; Back first ends field editing.
    BackHandler(editing && !imeVisible) { endEditing() }
    LaunchedEffect(query) {
        if (field.text != query && field.composition == null) field = TextFieldValue(query, TextRange(query.length))
    }
    Box(Modifier.fillMaxWidth().focusRequester(restingFocus)
        .onFocusChanged { resting = it.isFocused }
        .focusProperties {
            canFocus = requestingRest || resting
            // Route actions also clear focus before Play/More/navigation. Redirect only exits,
            // never ordinary Tab/arrow traversal, while the query is the active descendant.
            onExit = { if (requestedFocusDirection == FocusDirection.Exit && editing) endEditing() }
        }.focusTarget()) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(value = field, onValueChange = { next ->
        if (next.text.codePointCount(0, next.text.length) <= 200) {
            field = next
            if (next.composition == null) commit(next.text, next.text.isEmpty())
            compositionChanged(next.composition != null)
        }
    }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { input ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .drawBehind {
                    // A quiet baseline at rest and a clear mint focus cue; no raised tonal fill.
                    val thickness = (if (editing) 2.dp else 1.dp).toPx()
                    drawLine(if (editing) colors.primary else colors.outlineVariant,
                        Offset(0f, size.height - thickness / 2), Offset(size.width, size.height - thickness / 2), thickness)
                }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Search, null, tint = colors.onSurfaceVariant)
                }
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (field.text.isEmpty()) Text("Search your music", style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
                if (field.text.isNotEmpty()) IconButton(onClick = {
                    field = TextFieldValue(""); commit("", true); compositionChanged(false)
                }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Clear search", tint = colors.onBackground) }
                else Spacer(Modifier.width(48.dp))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            commit(field.text, true); compositionChanged(false); endEditing(); keyboard?.hide()
        }), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-query")
            .focusRequester(fieldFocus).onFocusChanged { state ->
                if (editing != state.isFocused) {
                    editing = state.isFocused
                    if (!state.isFocused) imeShownForFocus = false
                    editingChanged(state.isFocused)
                }
            }.semantics { contentDescription = "Search your music" })
    }
}

@Composable
internal fun LoadError(message: String, label: String, retry: () -> Unit) {
    Column { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = retry) { Text(label) } }
}
