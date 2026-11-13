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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.spofify.mobile.feature.player.selectedOutputLabel
import dev.spofify.mobile.feature.playlists.AddToPlaylistSheet
import dev.spofify.mobile.feature.playlists.PlaylistsRoute
import dev.spofify.mobile.feature.playlists.SavedCollectionControl
import dev.spofify.mobile.feature.playlists.savedTarget
import dev.spofify.mobile.ui.PlaybackFeedbackPresentation
import dev.spofify.mobile.ui.PlaybackFeedbackTimer
import dev.spofify.mobile.ui.PlaybackFeedbackHost
import dev.spofify.mobile.ui.AutomaticListDemand
import dev.spofify.mobile.ui.AppendStatus
import dev.spofify.mobile.ui.ListeningChrome
import dev.spofify.mobile.ui.CompactNavigation
import dev.spofify.mobile.ui.CompactNavigationItem
import dev.spofify.mobile.ui.MusicActionsContent
import dev.spofify.mobile.playback.defaultQueueAction
import dev.spofify.mobile.ui.SongActionSheet
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
    val queueIntentBinding: dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding?,
    val sourceCurrent: () -> Boolean,
    val savedBindingRevision: Long,
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
    val retainedAccount by model.graph.localEligibilityState.collectAsStateWithLifecycle()
    val coldClient = model.graph.client
    val rootFocus = LocalFocusManager.current
    val rootKeyboard = LocalSoftwareKeyboardController.current
    val enterOffline: (() -> Unit)? = retainedAccount?.takeIf {
        !state.offline && (!state.busy || state.restoring)
    }?.let { account -> {
        if (model.graph.localEligibility === account && model.graph.client === coldClient) {
            rootFocus.clearFocus(); rootKeyboard?.hide(); model.setOfflineMode(true)
        }
    } }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.session) { if (state.session == null) expanded = false }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(state.canGoBack && !expanded && state.session != null && !imeVisible) { model.back() }

    if (state.restoring) {
        Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(32.dp))
                enterOffline?.let { offline ->
                    TextButton(onClick = offline, modifier = Modifier.heightIn(min = 48.dp)) { Text("Offline mode") }
                }
            }
        }
        return
    }
    if (state.offline) {
        OfflineListeningApp(model)
        return
    }
    if (state.session == null) {
        SignIn(state, model.rememberedLogin, model::signIn, model::reconnect, onOfflineMode = enterOffline)
        return
    }
    val client = model.graph.client ?: return
    val cacheSettings by model.graph.songCache.settings.collectAsStateWithLifecycle()
    val playlistBrowse = model.playlistBrowseMemory() ?: return
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
        val presentation = remember { PlaybackFeedbackPresentation() }
        val consumeFeedback: (Long) -> Unit = { id ->
            model.consumeFeedback(id)
            model.playback.consumeFeedback(id)
        }
        val currentFeedback = presentation.current
        val feedbackValid = currentFeedback?.isCurrent?.invoke() != false
        LaunchedEffect(state.feedback?.id, player.feedback?.id, feedbackValid) {
            if (!feedbackValid) currentFeedback?.let { presentation.consume(it.id); consumeFeedback(it.id) }
            listOfNotNull(state.feedback, player.feedback).sortedBy { it.id }.forEach { event ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) presentation.offer(event)
                else consumeFeedback(event.id)
            }
        }
        PlaybackFeedbackTimer(presentation, onConsumed = consumeFeedback)
        DisposableEffect(lifecycle, presentation) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    presentation.current?.let { consumeFeedback(it.id) }
                    presentation.discardSuccess()
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                presentation.current?.let { consumeFeedback(it.id) }
            }
        }
        val pages = rememberSaveableStateHolder()
        val deviceCoordinator = model.graph.playbackCoordinator
        val observedOutput = deviceCoordinator?.state?.collectAsStateWithLifecycle()?.value
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
        val accountId = state.session?.accountId
        val sourceCurrent = model.captureBrowseSource(client)
        val sourceEpoch = model.browseSourceEpoch
        val art: @Composable (LibraryItem, Modifier) -> Unit = { item, modifier -> Artwork(item, model, modifier) }
        // Freeze values now: reading the delegated player inside a retained callback could
        // otherwise borrow a successor publication before the row recomposes.
        val playNowKey = player.interactionKey
        val playNowBinding = player.queueIntentBinding
        val feedback = CatalogPlaybackFeedback(player.item?.id, player.isPlaying, player.isBuffering, model.playback::toggle,
            interactionKey = player.interactionKey,
            playWhenReady = player.playWhenReady,
            onPlayNow = { item, rowCurrent ->
                if (sourceCurrent()) model.queueIntentFrom(client, item,
                    dev.spofify.mobile.playback.PlaybackListAction.PLAY_NOW, playNowKey,
                    { sourceCurrent() && rowCurrent() }, playNowBinding)
            },
            onContextual = { item, binding, isCurrent ->
                if (sourceCurrent()) model.queueIntentFrom(client, item, item.defaultQueueAction(), binding,
                    { sourceCurrent() && isCurrent() }, player.queueIntentBinding)
            },
            queueIntentBinding = player.queueIntentBinding,
        )
        val likeStates = state.likes.mapValues { (id, liked) ->
            CatalogLikeState(liked, id in state.pendingLikes, id in state.failedLikeStatus)
        } + state.failedLikeStatus.filterNot { it in state.likes }.associateWith { CatalogLikeState(failed = true) }
        val play: (LibraryItem, Boolean) -> Unit = { item, shuffle -> if (sourceCurrent()) model.playFrom(client, item, shuffle) }
        val queue: (LibraryItem) -> Unit = { item ->
            if (sourceCurrent()) model.queueIntentFrom(client, item, item.defaultQueueAction())
        }
        val toggleLike: (LibraryItem) -> Unit = { if (sourceCurrent()) model.likeAction(it) }
        val catalog = when (val page = state.page) {
            is Page.Search, is Page.Catalog -> model.catalogHolder()
            is Page.Detail -> model.catalogHolder(page.item, page.navigationId)
            else -> null
        }
        val catalogScreen = catalog?.state?.collectAsStateWithLifecycle()?.value?.current
        val catalogRoute = catalogScreen?.let { Triple(catalog.navigationVersion, it.key, it.query) }
        // Own accepted intents outside the sheet: dismissal is presentation, not cancellation.
        // Inner catalog navigation/query changes also retire the old route (including A→B→A).
        val likesSource = state.likesGeneration.takeIf { state.page == Page.Likes }
        val actionLifetime = remember(state.page, catalog, catalogRoute, likesSource, sourceEpoch) { RowActionLifetime() }
        DisposableEffect(actionLifetime) { onDispose { actionLifetime.invalidate() } }
        val showMore: (LibraryItem) -> Unit = { item ->
            val rowSourceCurrent = { sourceCurrent() && (likesSource == null || model.state.value.let {
                it.page == Page.Likes && it.likesGeneration == likesSource && !it.likesStale
            }) }
            if (rowSourceCurrent()) {
                val savedBindingRevision = model.graph.savedDownloads.state.value.bindingRevision
                if (rowSourceCurrent()) more = CatalogMoreSelection(item, player.interactionKey, actionLifetime,
                    player.queueIntentBinding, rowSourceCurrent, savedBindingRevision)
            }
        }
        val openExternal: (LibraryItem) -> Unit = { item ->
            if (sourceCurrent()) {
                expanded = false
                when (item.kind) {
                    "playlist", "derived" -> { playlistBrowse.openPlaylist(item.id); model.switchTab(Tab.PLAYLISTS) }
                    "likes" -> model.open(Page.Likes)
                    "artist", "album", "track" -> model.open(Page.Detail(item))
                }
            }
        }
        ListeningChrome(
            chromeHidden = hideListeningChrome(searchPage, searchEditing.value, imeVisible),
            snackbarHost = {
                if (!expanded || player.item == null) {
                    PlaybackFeedbackHost(presentation.current, announcementOwner = presentation)
                }
            },
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
                        Tab.SETTINGS to Icons.Default.Settings).forEach { (tab, icon) ->
                        CompactNavigationItem(selected = state.tab == tab,
                            onClick = {
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    if (tab == Tab.SEARCH) model.activateSearch() else {
                                        focus.clearFocus(); keyboard?.hide()
                                        model.switchTab(tab)
                                    }
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
                    Page.Settings -> "settings"
                    Page.Profile -> "account"
                    Page.Credits -> "credits"
                    else -> state.tab.name
                }
                pages.SaveableStateProvider(pageKey) {
                    when (val page = state.page) {
                        Page.Home -> model.homeHolder()?.let { home ->
                            LibraryHome(home, openExternal, play, showMore, queue,
                                onOpenLikes = { model.open(Page.Likes) },
                                onOpenPlaylists = { id -> playlistBrowse.openPlaylist(id); model.switchTab(Tab.PLAYLISTS) },
                                artwork = art, modifier = contentModifier, playback = feedback, likes = likeStates,
                                onToggleLike = toggleLike, onVisibleItems = model::checkVisibleLikes)
                        }
                        is Page.Search, is Page.Catalog -> SearchRoute(client,
                            onOpenItem = {}, onPlayItem = play, onShowActions = showMore, onEnqueueItem = queue,
                            artwork = art, modifier = contentModifier, playback = feedback, likes = likeStates,
                            onToggleLike = toggleLike, onVisibleItems = model::checkVisibleLikes,
                            stateHolder = model.catalogHolder(), sourceEpoch = sourceEpoch,
                            onSearchEditingChanged = onSearchEditingChanged,
                            searchActivationId = state.searchActivationId,
                            onSearchActivationConsumed = { model.consumeSearchActivation(client, accountId, it) })
                        is Page.Detail -> ItemDetailsRoute(client, page.item,
                            onOpenItem = {}, onPlayItem = play, onShowActions = showMore, onEnqueueItem = queue,
                            artwork = art, modifier = contentModifier, playback = feedback, likes = likeStates,
                            onToggleLike = toggleLike, onVisibleItems = model::checkVisibleLikes,
                            stateHolder = model.catalogHolder(page.item, page.navigationId), sourceEpoch = sourceEpoch)
                        Page.Playlists, is Page.Playlist -> PlaylistsRoute(client,
                            onOpenTrack = openExternal,
                            onShowTrackActions = showMore, modifier = contentModifier, playback = feedback, artwork = art,
                            onQueueIntent = { selection, action, binding, expectedBinding, isCurrent ->
                                if (sourceCurrent()) model.queueIntentSelectionFrom(client, selection, action, binding,
                                    { sourceCurrent() && isCurrent() }, expectedBinding)
                            },
                            playbackEntryId = player.item?.entryId, playbackContext = player.context,
                            browseMemory = playlistBrowse, sourceEpoch = sourceEpoch,
                            savedDownloads = model.graph.savedDownloads)
                        Page.Likes -> LikedSongs(state, model, openExternal, play, showMore, queue, art,
                            feedback, likeStates, contentModifier)
                        Page.Settings -> SettingsScreen(
                            username = state.session?.username.orEmpty(), origin = client.server.origin,
                            cacheSettings = cacheSettings,
                            onSaveCacheSettings = { value ->
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    model.graph.songCache.updateSettings(value)
                                }
                            },
                            onOpen = { destination ->
                                focus.clearFocus(); keyboard?.hide()
                                model.open(when (destination) {
                                    SettingsDestination.ACCOUNT -> Page.Profile
                                    SettingsDestination.CREDITS -> Page.Credits
                                    SettingsDestination.OFFLINE -> Page.Settings
                                })
                            }, modifier = contentModifier, offlineMode = state.offline,
                            onOfflineModeChanged = model::setOfflineMode)
                        Page.Profile -> ProfileScreen(
                            username = state.session?.username.orEmpty(), origin = client.server.origin,
                            busy = state.busy,
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
            // Finite player window with one stable overflow body. Queue and Devices own native
            // overlays above it; Back dismisses the top window without touching the detail route.
            NativeNowPlayingDialog(onCollapse = { expanded = false }) { playerPresentation ->
                Surface(Modifier.fillMaxSize(), color = Background) {
                    Box(Modifier.fillMaxSize()) {
                        NativeNowPlaying(player, model.playback, state.likes[item.id], item.id in state.pendingLikes,
                            item.id in state.failedLikeStatus, { model.toggleLike(item) }, { model.checkLike(item) },
                            onAddToPlaylist = { expanded = false; addToPlaylist = it }, onOpenItem = openExternal,
                            artwork = art, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                            presentation = playerPresentation, onPlaybackDevices = openDevices,
                            selectedOutputLabel = selectedOutputLabel(observedOutput),
                            queueFeedback = { PlaybackFeedbackHost(presentation.current, announcementOwner = presentation) })
                        // One picker and one feedback host in the visible player window.
                        deviceSheet()
                        if (!playerPresentation.queueOpen) {
                            Box(Modifier.align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))) {
                                PlaybackFeedbackHost(presentation.current, announcementOwner = presentation)
                            }
                        }
                    }
                }
            }
        }
        more?.let { selection ->
            val item = selection.item
            val binding = selection.interactionKey
            val isCurrent = {
                selection.lifetime.isCurrent() && selection.sourceCurrent() && (catalog?.state?.value?.current?.let {
                    Triple(catalog.navigationVersion, it.key, it.query)
                } == catalogRoute)
            }
            LaunchedEffect(item.id) { if (isCurrent() && item.kind == "track") model.checkLike(item) }
            SongActionSheet(onDismiss = { more = null }) {
                MusicActionsContent(item, state.likes[item.id], item.id in state.pendingLikes, item.id in state.failedLikeStatus,
                    onPlay = { more = null; model.queueIntentFrom(client, item, dev.spofify.mobile.playback.PlaybackListAction.PLAY_NOW, binding, isCurrent, selection.queueIntentBinding) },
                    onPlayNext = { more = null; model.queueIntentFrom(client, item, dev.spofify.mobile.playback.PlaybackListAction.PLAY_NEXT, binding, isCurrent, selection.queueIntentBinding) },
                    onQueue = {
                        more = null
                        model.queueIntentFrom(client, item, item.defaultQueueAction(), binding, isCurrent,
                            selection.queueIntentBinding)
                    },
                    onToggleLike = { if (isCurrent()) model.likeAction(item) },
                    onRetryLike = { if (isCurrent()) model.checkLike(item) },
                    onAddToPlaylist = { if (isCurrent()) { more = null; addToPlaylist = item } },
                    onOpenItem = { target -> if (isCurrent()) { more = null; openExternal(target) } }, artwork = art,
                    libraryContent = if (item.savedTarget() != null) ({
                        SavedCollectionControl(model.graph.savedDownloads, checkNotNull(item.savedTarget()), item.name,
                            actionsEnabled = isCurrent(), isCurrent = isCurrent,
                            expectedBindingRevision = selection.savedBindingRevision)
                    }) else null)
            }
        }
        addToPlaylist?.let { item -> AddToPlaylistSheet(client, item, onDismiss = {
            addToPlaylist = null
        }) }
        if (!expanded || player.item == null) deviceSheet()
    }
}

@Composable
internal fun LikedSongs(
    state: LibraryState, model: LibraryViewModel,
    onOpen: (LibraryItem) -> Unit, onPlay: (LibraryItem, Boolean) -> Unit,
    onMore: (LibraryItem) -> Unit, onQueue: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    feedback: CatalogPlaybackFeedback, likes: Map<String, CatalogLikeState>, modifier: Modifier,
) {
    LaunchedEffect(state.items) { model.checkVisibleLikes(state.items) }
    val list = rememberLazyListState()
    val source = state.likesGeneration
    val sourceCurrent = { model.state.value.let { it.page == Page.Likes && it.likesGeneration == source && !it.likesStale } }
    val rowFeedback = feedback.copy(
        onPlayNow = feedback.onPlayNow?.let { action -> { item, rowCurrent ->
            if (sourceCurrent()) action(item, { sourceCurrent() && rowCurrent() })
        } },
        onContextual = feedback.onContextual?.let { action -> { item, binding, rowCurrent ->
            if (sourceCurrent()) action(item, binding, { sourceCurrent() && rowCurrent() })
        } },
    )
    AutomaticListDemand(list, model to state.likesGeneration, state.items.takeLast(3).map { "liked:${it.id}" },
        state.page == Page.Likes && !state.loading && state.error == null && !state.likesStale &&
            !state.likesEnded && state.offset < state.total, model::more)
    LazyColumn(modifier.testTag("liked-songs-list"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Liked songs", style = MaterialTheme.typography.headlineLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { model.playPage(false) }, enabled = state.items.any { it.playable } && !state.preparing && !state.likesStale) { Text("Play") }
                OutlinedButton(onClick = { model.playPage(true) }, enabled = state.items.any { it.playable } && !state.preparing && !state.likesStale) { Text("Shuffle") }
            }
            if (state.items.isEmpty()) AppendStatus(state.loading, state.error, model::retryLikes,
                loadingLabel = "Loading liked songs", retryLabel = "Retry liked songs")
            if (state.likesStale) TextButton(onClick = model::refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh liked songs") }
            if (!state.loading && state.items.isEmpty() && state.error == null) Text("No liked songs yet.")
        }
        itemsIndexed(state.items, key = { _, item -> "liked:${item.id}" }) { _, item ->
            // Preserve the lazy anchor through append; only source changes retire held gestures.
            key(source) {
                MusicRow(item, onOpen,
                    { track, shuffle -> if (sourceCurrent()) onPlay(track, shuffle) },
                    { track -> if (sourceCurrent()) onMore(track) },
                    { track -> if (sourceCurrent()) onQueue(track) }, artwork, playback = rowFeedback,
                    like = likes[item.id], onToggleLike = { if (sourceCurrent()) model.likeAction(it) })
            }
        }
        if (state.items.isNotEmpty()) item(key = "likes-continuation") {
            if (state.likesStale) state.error?.let { Text(it) }
            else AppendStatus(state.loading, state.error, model::retryLikes,
                loadingLabel = "Loading more liked songs", retryLabel = "Retry liked songs")
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
