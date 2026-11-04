package dev.spofify.mobile

import android.os.Bundle
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.feature.library.*
import dev.spofify.mobile.feature.devices.PlaybackDevicesSheet
import dev.spofify.mobile.feature.player.NativeMiniPlayer
import dev.spofify.mobile.feature.player.NativeNowPlaying
import dev.spofify.mobile.feature.playlists.AddToPlaylistSheet
import dev.spofify.mobile.feature.playlists.PlaylistsRoute
import dev.spofify.mobile.ui.theme.AppTypography
import java.util.Locale

private val Mint = Color(0xFF78E0AC)
private val Background = Color(0xFF101412)
private val Surface = Color(0xFF1A211D)

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Color(0xFF08351F),
                background = Background, surface = Surface, secondary = Mint, surfaceContainer = Surface),
                typography = AppTypography) {
                SpofifyApp(model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpofifyApp(model: LibraryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val player by model.playback.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var account by remember { mutableStateOf(false) }
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
        SignIn(state, model.rememberedLogin, model::signIn, model::reconnect, model::setRememberLogin, model::forgetSavedLogin)
        return
    }
    val client = model.graph.client ?: return
    key(client, state.session?.accountId) {
        val pages = rememberSaveableStateHolder()
        var devices by remember { mutableStateOf(false) }
        var more by remember { mutableStateOf<LibraryItem?>(null) }
        var addToPlaylist by remember { mutableStateOf<LibraryItem?>(null) }
        var initialPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
        val accountId = state.session?.accountId
        val preparedGeneration = state.playbackIntentGeneration
        val art: @Composable (LibraryItem, Modifier) -> Unit = { item, modifier -> Artwork(item, model, modifier) }
        val feedback = CatalogPlaybackFeedback(player.item?.id, player.isPlaying, player.isBuffering, model.playback::toggle)
        val likeStates = state.likes.mapValues { (id, liked) ->
            CatalogLikeState(liked, id in state.pendingLikes, id in state.failedLikeStatus)
        } + state.failedLikeStatus.filterNot { it in state.likes }.associateWith { CatalogLikeState(failed = true) }
        val play: (LibraryItem, Boolean) -> Unit = { item, shuffle -> model.playFrom(client, item, shuffle) }
        val queue: (LibraryItem) -> Unit = { model.enqueueFrom(client, it) }
        val showMore: (LibraryItem) -> Unit = { more = it }
        val openExternal: (LibraryItem) -> Unit = { item ->
            expanded = false
            when (item.kind) {
                "playlist", "derived" -> { initialPlaylistId = item.id; model.switchTab(Tab.PLAYLISTS) }
                "likes" -> model.open(Page.Likes)
                "artist", "album", "track" -> model.open(Page.Detail(item))
            }
        }
        Scaffold(
            modifier = Modifier.imePadding(),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 48.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    if (model.graph.playbackCoordinator != null) IconButton(onClick = { devices = true }) {
                        Icon(Icons.Default.DevicesOther, "Playback devices · read-only")
                    }
                    IconButton(onClick = { account = true }) { Icon(Icons.Default.AccountCircle, "Account and server") }
                }
            },
            bottomBar = {
                Column {
                    NativeMiniPlayer(player, model.playback, { expanded = true }, art,
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                    NavigationBar(containerColor = Background) {
                        listOf(Tab.HOME to Icons.Default.Home, Tab.SEARCH to Icons.Default.Search,
                            Tab.PLAYLISTS to Icons.AutoMirrored.Filled.QueueMusic).forEach { (tab, icon) ->
                            NavigationBarItem(selected = state.tab == tab, onClick = { model.switchTab(tab) },
                                icon = { Icon(icon, null) }, label = { Text(tab.name.lowercase().replaceFirstChar { it.titlecase() }) })
                        }
                    }
                }
            }, containerColor = Background,
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val contentModifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
                val pageKey = when (val page = state.page) {
                    is Page.Detail -> "detail:${page.item.kind}:${page.item.id}:${page.navigationId}"
                    Page.Likes -> "likes"
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
                            stateHolder = model.catalogHolder())
                        is Page.Detail -> ItemDetailsRoute(client, page.item,
                            onOpenItem = {}, onPlayItem = play, onShowActions = showMore, onEnqueueItem = queue,
                            artwork = art, modifier = contentModifier, playback = feedback, likes = likeStates,
                            onToggleLike = model::likeAction, onVisibleItems = model::checkVisibleLikes,
                            stateHolder = model.catalogHolder(page.item, page.navigationId))
                        Page.Playlists, is Page.Playlist -> PlaylistsRoute(client,
                            onOpenTrack = openExternal,
                            onPlayQueue = { prepared, trackId, entryId -> model.playPrepared(client, accountId, prepared, trackId, entryId, preparedGeneration) },
                            onShowTrackActions = showMore, modifier = contentModifier,
                            initialPlaylistId = (page as? Page.Playlist)?.playlist?.id ?: initialPlaylistId)
                        Page.Likes -> LikedSongs(state, model, openExternal, play, showMore, queue, art,
                            feedback, likeStates, contentModifier)
                    }
                }
            }
        }
        if (account) AlertDialog(onDismissRequest = { account = false }, title = { Text("Your connection") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.session?.username.orEmpty(), fontWeight = FontWeight.Bold)
                Text(state.server, style = MaterialTheme.typography.bodyMedium)
                Text("Sign out to change servers. Your music and playlists stay on your server.", style = MaterialTheme.typography.bodySmall)
                if (state.hasSavedLogin) {
                    Text("Sign out keeps your saved login on this device for next time.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !state.busy, onClick = model::forgetSavedLogin) { Text("Forget saved login") }
                }
            } }, confirmButton = { TextButton(enabled = !state.busy, onClick = { account = false; model.logout() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { account = false }) { Text("Done") } })
        if (expanded && player.item != null) {
            val item = player.item!!
            LaunchedEffect(item.id) { model.checkLike(item) }
            ModalBottomSheet(onDismissRequest = { expanded = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Background) {
                NativeNowPlaying(player, model.playback, state.likes[item.id], item.id in state.pendingLikes,
                    item.id in state.failedLikeStatus, { model.toggleLike(item) }, { model.checkLike(item) },
                    onAddToPlaylist = { expanded = false; addToPlaylist = it }, onOpenItem = openExternal,
                    artwork = art, modifier = Modifier.fillMaxWidth().fillMaxHeight(.94f))
            }
        }
        more?.let { item ->
            LaunchedEffect(item.id) { if (item.kind == "track") model.checkLike(item) }
            ModalBottomSheet(onDismissRequest = { more = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { more = null; play(item, false) }, enabled = item.playable) { Text("Play") }
                    TextButton(onClick = { more = null; model.enqueueFrom(client, item, playNext = true) }, enabled = item.playable) { Text("Play next") }
                    TextButton(onClick = { more = null; queue(item) }, enabled = item.playable) { Text("Add to queue") }
                    if (item.kind == "track") {
                        LikeButton(state.likes[item.id], item.id in state.pendingLikes, item.id in state.failedLikeStatus,
                            toggle = { model.likeAction(item) }, retry = { model.checkLike(item) })
                        TextButton(onClick = { more = null; addToPlaylist = item }) { Text("Add to playlist") }
                    }
                    TextButton(onClick = { more = null; openExternal(item) }) { Text("Open details") }
                    MusicCredits(item, { credit -> more = null; openExternal(credit) })
                }
            }
        }
        addToPlaylist?.let { item -> AddToPlaylistSheet(client, item, onDismiss = {
            addToPlaylist = null
        }) }
        if (devices) model.graph.playbackCoordinator?.let { coordinator ->
            PlaybackDevicesSheet(coordinator, onDismiss = { devices = false })
        }
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
            if (!state.loading && state.items.isEmpty() && state.error == null) Text("Songs you like will find a home here. Use a heart on any song.")
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
