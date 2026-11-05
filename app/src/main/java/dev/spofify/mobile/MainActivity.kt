package dev.spofify.mobile

import android.os.Bundle
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.feature.library.*
import dev.spofify.mobile.feature.devices.PlaybackDevicesSheet
import dev.spofify.mobile.feature.player.NativeMiniPlayer
import dev.spofify.mobile.feature.player.NativeNowPlaying
import dev.spofify.mobile.feature.player.NativeNowPlayingDialog
import dev.spofify.mobile.feature.playlists.AddToPlaylistSheet
import dev.spofify.mobile.feature.playlists.PlaylistsRoute
import dev.spofify.mobile.ui.ListeningChrome
import dev.spofify.mobile.ui.CompactNavigation
import dev.spofify.mobile.ui.CompactNavigationItem
import dev.spofify.mobile.ui.MusicActionsContent
import dev.spofify.mobile.ui.RowActionLifetime
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.ui.hideListeningChrome
import dev.spofify.mobile.ui.rememberSearchEditing
import dev.spofify.mobile.ui.theme.AppFontLicenseDialog
import dev.spofify.mobile.ui.theme.AppTypography
import java.util.Locale

private val Mint = Color(0xFF78E0AC)
private val Background = Color(0xFF101412)
private val Surface = Color(0xFF1A211D)
private val TonalContainer = Mint.copy(alpha = .16f).compositeOver(Surface)

private data class CatalogMoreSelection(
    val item: LibraryItem,
    val interactionKey: PlaybackInteractionKey?,
    val lifetime: RowActionLifetime,
    val queueIntentBinding: dev.spofify.mobile.core.QueueIntentBinding?,
    val unselectedOrigin: dev.spofify.mobile.playback.PlaybackQueueOrigin?,
)

internal fun listeningColorScheme() = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF08351F),
    primaryContainer = TonalContainer, onPrimaryContainer = Mint,
    secondaryContainer = TonalContainer, onSecondaryContainer = Mint,
    background = Background, surface = Surface, secondary = Mint, surfaceContainer = Surface,
)

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                SpofifyApp(model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpofifyApp(model: LibraryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val player by model.playback.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); model.clearNotice() } }
    LaunchedEffect(state.session) { if (state.session == null) expanded = false }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(state.canGoBack && !expanded && state.session != null && !imeVisible) { model.back() }

    if (state.restoring) {
        Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp))
        }
        return
    }
    if (state.session == null) {
        SignIn(state, model.rememberedLogin, model::signIn, model::reconnect)
        return
    }
    val client = model.graph.client ?: return
    val searchPage = state.page is Page.Search || state.page is Page.Catalog
    val searchEditing = rememberSearchEditing(client, state.session?.accountId, searchPage)
    val onSearchEditingChanged = remember(searchEditing) { { editing: Boolean -> searchEditing.value = editing } }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, client, state.session?.accountId, lifecycle) {
        val owner = state.session?.accountId
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                model.cancelSearchActivation(client, owner)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.cancelSearchActivation(client, owner)
        }
    }
    key(client, state.session?.accountId) {
        val pages = rememberSaveableStateHolder()
        val deviceCoordinator = model.graph.playbackCoordinator
        var devices by remember(deviceCoordinator) { mutableStateOf(false) }
        val openDevices: (() -> Unit)? = deviceCoordinator?.let { { devices = true } }
        val deviceSheet: @Composable () -> Unit = {
            if (devices) deviceCoordinator?.let { coordinator ->
                PlaybackDevicesSheet(coordinator, onDismiss = { devices = false })
            }
        }
        var creditsLicense by remember(state.page) { mutableStateOf(false) }
        var more by remember { mutableStateOf<CatalogMoreSelection?>(null) }
        var addToPlaylist by remember { mutableStateOf<LibraryItem?>(null) }
        var initialPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
        val accountId = state.session?.accountId
        val preparedGeneration = state.playbackIntentGeneration
        val art: @Composable (LibraryItem, Modifier) -> Unit = { item, modifier -> Artwork(item, model, modifier) }
        val feedback = CatalogPlaybackFeedback(player.item?.id, player.isPlaying, player.isBuffering, model.playback::toggle,
            interactionKey = player.interactionKey,
            playWhenReady = player.playWhenReady,
            onContextual = { item, binding, isCurrent -> model.contextualFrom(client, item, binding, isCurrent) },
            queueIntentBinding = player.queueIntentBinding,
            unselectedQueueOrigin = player.unselectedQueueOrigin,
            onUnselectedHigh = { item, origin, isCurrent -> model.queueIntentFrom(client, item,
                dev.spofify.mobile.playback.PlaybackListAction.QUEUE_HIGH, null, isCurrent, null, origin) })
        val likeStates = state.likes.mapValues { (id, liked) ->
            CatalogLikeState(liked, id in state.pendingLikes, id in state.failedLikeStatus)
        } + state.failedLikeStatus.filterNot { it in state.likes }.associateWith { CatalogLikeState(failed = true) }
        val play: (LibraryItem, Boolean) -> Unit = { item, shuffle -> model.playFrom(client, item, shuffle) }
        val queue: (LibraryItem) -> Unit = { model.enqueueFrom(client, it) }
        val catalog = when (val page = state.page) {
            is Page.Search, is Page.Catalog -> model.catalogHolder()
            is Page.Detail -> model.catalogHolder(page.item, page.navigationId)
            else -> null
        }
        val catalogScreen = catalog?.state?.collectAsStateWithLifecycle()?.value?.current
        val catalogRoute = catalogScreen?.let { Triple(catalog.navigationVersion, it.key, it.query) }
        // Own accepted intents outside the sheet: dismissal is presentation, not cancellation.
        // Inner catalog navigation/query changes also retire the old route (including A→B→A).
        val actionLifetime = remember(state.page, catalog, catalogRoute) { RowActionLifetime() }
        DisposableEffect(actionLifetime) { onDispose { actionLifetime.invalidate() } }
        val showMore: (LibraryItem) -> Unit = { more = CatalogMoreSelection(it, player.interactionKey, actionLifetime, player.queueIntentBinding, player.unselectedQueueOrigin) }
        val openExternal: (LibraryItem) -> Unit = { item ->
            expanded = false
            when (item.kind) {
                "playlist", "derived" -> { initialPlaylistId = item.id; model.switchTab(Tab.PLAYLISTS) }
                "likes" -> model.open(Page.Likes)
                "artist", "album", "track" -> model.open(Page.Detail(item))
            }
        }
        ListeningChrome(
            chromeHidden = hideListeningChrome(searchPage, searchEditing.value, imeVisible),
            snackbarHost = { SnackbarHost(snackbar) },
            connectionActions = null,
            miniPlayer = {
                NativeMiniPlayer(player, model.playback, {
                    model.cancelSearchActivation(client, accountId)
                    focus.clearFocus(); keyboard?.hide()
                    expanded = true
                }, art, Modifier.fillMaxWidth(), onPlaybackDevices = openDevices)
            },
            navigation = {
                CompactNavigation(modifier = Modifier.testTag("primary-navigation")) {
                    listOf(Tab.HOME to Icons.Default.Home, Tab.SEARCH to Icons.Default.Search,
                        Tab.PLAYLISTS to Icons.AutoMirrored.Filled.QueueMusic,
                        Tab.PROFILE to Icons.Default.AccountCircle).forEach { (tab, icon) ->
                        CompactNavigationItem(selected = state.tab == tab,
                            onClick = {
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    if (tab == Tab.SEARCH) model.activateSearch() else model.switchTab(tab)
                                }
                            },
                            icon = { Icon(icon, null) }, label = tab.name.lowercase().replaceFirstChar { it.titlecase() },
                            modifier = Modifier.testTag("nav-${tab.name.lowercase()}"))
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val contentModifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
                val pageKey = when (val page = state.page) {
                    is Page.Detail -> "detail:${page.item.kind}:${page.item.id}:${page.navigationId}"
                    Page.Likes -> "likes"
                    Page.Credits -> "credits"
                    else -> state.tab.name
                }
                pages.SaveableStateProvider(pageKey) {
                    when (val page = state.page) {
                        Page.Home -> model.homeHolder()?.let { home ->
                            LibraryHome(home, openExternal, play, showMore, queue,
                                onOpenLikes = { model.open(Page.Likes) },
                                onOpenPlaylists = { id -> initialPlaylistId = id; model.switchTab(Tab.PLAYLISTS) },
                                artwork = art, modifier = contentModifier, playback = feedback, likes = likeStates,
                                onToggleLike = model::likeAction, onVisibleItems = model::checkVisibleLikes)
                        }
                        is Page.Search, is Page.Catalog -> SearchRoute(client,
                            onOpenItem = {}, onPlayItem = play, onShowActions = showMore, onEnqueueItem = queue,
                            artwork = art, modifier = contentModifier, playback = feedback, likes = likeStates,
                            onToggleLike = model::likeAction, onVisibleItems = model::checkVisibleLikes,
                            stateHolder = model.catalogHolder(), onSearchEditingChanged = onSearchEditingChanged,
                            searchActivationId = state.searchActivationId,
                            onSearchActivationConsumed = { model.consumeSearchActivation(client, accountId, it) })
                        is Page.Detail -> ItemDetailsRoute(client, page.item,
                            onOpenItem = {}, onPlayItem = play, onShowActions = showMore, onEnqueueItem = queue,
                            artwork = art, modifier = contentModifier, playback = feedback, likes = likeStates,
                            onToggleLike = model::likeAction, onVisibleItems = model::checkVisibleLikes,
                            stateHolder = model.catalogHolder(page.item, page.navigationId))
                        Page.Playlists, is Page.Playlist -> PlaylistsRoute(client,
                            onOpenTrack = openExternal,
                            onPlayQueue = { prepared, trackId, entryId -> model.playPrepared(client, accountId, prepared, trackId, entryId, preparedGeneration) },
                            onShowTrackActions = showMore, modifier = contentModifier, playback = feedback, artwork = art,
                            onQueueIntent = { selection, action, binding, expectedBinding, isCurrent ->
                                model.queueIntentSelectionFrom(client, selection, action, binding, isCurrent, expectedBinding)
                            },
                            onUnselectedHigh = { selection, origin, isCurrent ->
                                model.queueIntentSelectionFrom(client, selection, dev.spofify.mobile.playback.PlaybackListAction.QUEUE_HIGH,
                                    null, isCurrent, null, origin)
                            },
                            playbackEntryId = player.item?.entryId, playbackContext = player.context,
                            onPreparedAction = { prepared, trackId, entryId, action, binding, isCurrent ->
                                model.preparedListAction(client, accountId, prepared, trackId, entryId, action, binding, preparedGeneration, isCurrent)
                            },
                            initialPlaylistId = (page as? Page.Playlist)?.playlist?.id ?: initialPlaylistId)
                        Page.Likes -> LikedSongs(state, model, openExternal, play, showMore, queue, art,
                            feedback, likeStates, contentModifier)
                        Page.Profile -> ProfileScreen(
                            username = state.session?.username.orEmpty(), origin = client.server.origin,
                            busy = state.busy, onCredits = { model.open(Page.Credits) },
                            onSignOut = model::logout, modifier = contentModifier)
                        Page.Credits -> CreditsScreen(onFontLicense = { creditsLicense = true }, modifier = contentModifier)
                    }
                }
            }
        }
        if (creditsLicense && state.page == Page.Credits) {
            AppFontLicenseDialog(onDismiss = { creditsLicense = false })
        }
        if (expanded && player.item != null) {
            val item = player.item!!
            LaunchedEffect(item.id) { model.checkLike(item) }
            // No draggable sheet/nested-scroll anchors: the player has stable window bounds and
            // only its queue scrolls. Dialog Back dismisses presentation, never playback.
            NativeNowPlayingDialog(onCollapse = { expanded = false }) { presentation ->
                Surface(Modifier.fillMaxSize(), color = Background) {
                    NativeNowPlaying(player, model.playback, state.likes[item.id], item.id in state.pendingLikes,
                        item.id in state.failedLikeStatus, { model.toggleLike(item) }, { model.checkLike(item) },
                        onAddToPlaylist = { expanded = false; addToPlaylist = it }, onOpenItem = openExternal,
                        artwork = art, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                        presentation = presentation, onPlaybackDevices = openDevices)
                    // Compose the single picker in the current player window, above its Dialog.
                    // The underlying player and its queue/list state remain mounted on dismissal.
                    deviceSheet()
                }
            }
        }
        more?.let { selection ->
            val item = selection.item
            val binding = selection.interactionKey
            val isCurrent = {
                selection.lifetime.isCurrent() && (catalog?.state?.value?.current?.let {
                    Triple(catalog.navigationVersion, it.key, it.query)
                } == catalogRoute)
            }
            LaunchedEffect(item.id) { if (item.kind == "track") model.checkLike(item) }
            ModalBottomSheet(onDismissRequest = { more = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                MusicActionsContent(item, state.likes[item.id], item.id in state.pendingLikes, item.id in state.failedLikeStatus,
                    onPlay = { more = null; model.queueIntentFrom(client, item, dev.spofify.mobile.playback.PlaybackListAction.PLAY_NOW, binding, isCurrent, selection.queueIntentBinding) },
                    onPlayNext = { more = null; model.queueIntentFrom(client, item, dev.spofify.mobile.playback.PlaybackListAction.PLAY_NEXT, binding, isCurrent, selection.queueIntentBinding) },
                    onEnqueue = { more = null; model.queueIntentFrom(client, item, dev.spofify.mobile.playback.PlaybackListAction.QUEUE_HIGH, binding, isCurrent, selection.queueIntentBinding, selection.unselectedOrigin) },
                    onQueueLow = { more = null; model.queueIntentFrom(client, item, dev.spofify.mobile.playback.PlaybackListAction.QUEUE_LOW, binding, isCurrent, selection.queueIntentBinding) },
                    contextualLabel = feedback.contextualLabel,
                    onContextual = binding?.let { capturedKey -> {
                        more = null
                        model.queueIntentFrom(client, item, dev.spofify.mobile.playback.PlaybackListAction.QUEUE_HIGH,
                            capturedKey, isCurrent, selection.queueIntentBinding)
                    } },
                    onToggleLike = { model.likeAction(item) }, onRetryLike = { model.checkLike(item) },
                    onAddToPlaylist = { more = null; addToPlaylist = item },
                    onOpenItem = { target -> more = null; openExternal(target) })
            }
        }
        addToPlaylist?.let { item -> AddToPlaylistSheet(client, item, onDismiss = {
            addToPlaylist = null
        }) }
        if (!expanded || player.item == null) deviceSheet()
    }
}

@Composable
private fun LikedSongs(
    state: LibraryState, model: LibraryViewModel,
    onOpen: (LibraryItem) -> Unit, onPlay: (LibraryItem, Boolean) -> Unit,
    onMore: (LibraryItem) -> Unit, onQueue: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    feedback: CatalogPlaybackFeedback, likes: Map<String, CatalogLikeState>, modifier: Modifier,
) {
    LaunchedEffect(state.items) { model.checkVisibleLikes(state.items) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Liked songs", style = MaterialTheme.typography.headlineLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { model.playPage(false) }, enabled = state.items.any { it.playable } && !state.preparing) { Text("Play") }
                OutlinedButton(onClick = { model.playPage(true) }, enabled = state.items.any { it.playable } && !state.preparing) { Text("Shuffle") }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { LoadError(it, "Retry liked songs", model::refresh) }
            if (!state.loading && state.items.isEmpty() && state.error == null) Text("No liked songs yet.")
        }
        itemsIndexed(state.items, key = { index, item -> "${item.id}:$index" }) { _, item ->
            MusicRow(item, onOpen, onPlay, onMore, onQueue, artwork, playback = feedback,
                like = likes[item.id], onToggleLike = model::likeAction)
        }
        if (!state.loading && state.offset < state.total) item {
            TextButton(onClick = model::more, modifier = Modifier.fillMaxWidth()) { Text("Show more (${state.offset} of ${state.total})") }
        }
    }
}

/** Kept for existing host/UI-test callers; the main search route uses IME-aware committed text. */
@Composable
internal fun SearchField(initial: String, search: (String) -> Unit) {
    CommittedSearchField(initial) { text, _ -> search(text) }
}

@Composable
private fun Artwork(item: LibraryItem, model: LibraryViewModel, modifier: Modifier) {
    val loader = model.graph.imageLoader
    val client = model.graph.client
    val hue = ((item.id.hashCode().toLong() and 0x7FFFFFFF) % 360).toFloat()
    Box(modifier.background(Color.hsl(hue, .23f, .22f)), contentAlignment = Alignment.Center) {
        Text(item.name.take(1).uppercase(Locale.ROOT), style = MaterialTheme.typography.headlineLarge,
            color = Color.White.copy(alpha = .65f), fontWeight = FontWeight.Bold)
        if (loader != null && client != null) AsyncImage(model = client.artworkUrl(item), imageLoader = loader,
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
