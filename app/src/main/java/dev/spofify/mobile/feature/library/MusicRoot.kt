package dev.spofify.mobile.feature.library

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.content.TrackContentClient
import dev.spofify.mobile.core.library.*
import dev.spofify.mobile.core.personal.PersonalLibraryClient
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.designsystem.SpofifyGlyph
import dev.spofify.mobile.designsystem.SpofifyIcon
import dev.spofify.mobile.feature.personal.PlaylistsScreen
import dev.spofify.mobile.feature.personal.PlaylistDetailScreen
import dev.spofify.mobile.feature.personal.RecentScreen
import dev.spofify.mobile.feature.settings.*
import dev.spofify.mobile.core.search.SearchClient
import dev.spofify.mobile.playback.*

private enum class MusicTab(val route: String, val title: String, val glyph: SpofifyGlyph) {
    HISTORY("history", "History", SpofifyGlyph.History),
    SEARCH("search", "Search", SpofifyGlyph.Search),
    PLAYLISTS("playlists", "Playlists", SpofifyGlyph.Playlists),
    SETTINGS("settings", "Settings", SpofifyGlyph.Settings),
}

@Composable
internal fun MusicRoot(
    identity: AccountIdentity,
    sessions: SessionRepository,
    playbackOwner: PlaybackOwner,
    palettes: AccountPaletteController,
    account: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val native by playbackOwner.state.collectAsStateWithLifecycle()
    val player = native?.takeIf { it.identity == identity } ?: return
    val source by player.music.sources.collectAsStateWithLifecycle()
    val loader = remember(source) { artworkLoader(context.applicationContext, source.library, source.local) }
    val stores = remember(source) { SourceModels() }
    DisposableEffect(loader, stores) { onDispose { stores.retire(); loader.shutdown() } }
    val currentStores by rememberUpdatedState(stores)
    val currentSource by rememberUpdatedState(source)
    val currentLoader by rememberUpdatedState(loader)
    val currentPlayer = rememberUpdatedState(player)
    val target = player.target.collectAsStateWithLifecycle().value
    val currentController = rememberUpdatedState(target)
    val navigation = rememberNavController()
    val currentAccount = rememberUpdatedState(account)
    var tab by rememberSaveable { mutableStateOf(MusicTab.HISTORY) }
    var focusIntent by rememberSaveable { mutableIntStateOf(0) }
    val currentFocusIntent = rememberUpdatedState(focusIntent)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var devices by rememberSaveable { mutableStateOf(false) }
    var actions by remember { mutableStateOf<MusicActionSelection?>(null) }
    val entry by navigation.currentBackStackEntryAsState()
    var rowPlaybackNotice by remember(navigation) { mutableStateOf<Pair<String, String>?>(null) }
    val search = entry?.destination?.route == "search"
    val imeOcclusion = WindowInsets.ime.getBottom(LocalDensity.current)
    val navigate: (MusicKind, String) -> Unit = remember(navigation) {
        { kind, id ->
            val current = navigation.currentBackStackEntry
            val sameDetail = current?.destination?.route == "music/{kind}/{id}" &&
                current.arguments?.getString("kind") == kind.name && current.arguments?.getString("id") == id
            // Different entities share a destination pattern, not a model or Back-stack entry.
            if (!sameDetail) {
                rowPlaybackNotice = null
                navigation.navigate("music/${kind.name}/${Uri.encode(id)}")
            }
        }
    }
    val openActions: (MusicItem, PlaybackContext?) -> Unit = remember(focus, keyboard) {
        { item, collection ->
            focus.clearFocus()
            keyboard?.hide()
            actions = MusicActionSelection(item, collection)
        }
    }
    val open: (MusicItem) -> Unit = remember(navigate, focus, keyboard, target) {
        { item ->
            focus.clearFocus()
            keyboard?.hide()
            if (item.kind == MusicKind.TRACK) activateTrackRow(item.id, item.hasAudio,
                play = { target?.playTrack(it) },
                openDetails = { navigate(item.kind, item.id) },
                notice = { text -> rowPlaybackNotice = text?.let { message ->
                    navigation.currentBackStackEntry?.id?.let { it to message }
                } })
            else navigate(item.kind, item.id)
        }
    }
    // The graph and its models must not be replaced by playback ticks or IME recomposition.
    val currentOpen by rememberUpdatedState(open)
    val graph: NavGraphBuilder.() -> Unit = remember(navigation, navigate, openActions, palettes) {
        {
            composable("history") { currentStores.Content {
                RecentScreen(currentSource.personal, currentLoader, currentController.value, navigate)
            } }
            composable("search") { owner ->
                val api = currentSource.search
                val model: SearchViewModel = viewModel(owner, factory = viewModelFactory { initializer { SearchViewModel(api) } })
                model.bind(api, currentSource.local)
                SearchScreen(model, currentLoader, currentFocusIntent.value, currentOpen, currentController.value,
                    onMore = { openActions(it, null) })
            }
            composable("playlists") { currentStores.Content {
                PlaylistsScreen(currentSource.personal, currentLoader, currentController.value, currentPlayer.value.music,
                    { id -> navigation.navigate("playlist/${Uri.encode(id)}") }, navigate)
            } }
            composable("playlist/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { owner ->
                currentStores.Content {
                    PlaylistDetailScreen(currentSource.personal, requireNotNull(owner.arguments?.getString("id")), currentLoader,
                        currentController.value, currentPlayer.value.music, { navigation.popBackStack() }, navigate)
                }
            }
            composable("music/{kind}/{id}", arguments = listOf(
                navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType },
            )) { owner ->
                val kind = MusicKind.valueOf(requireNotNull(owner.arguments?.getString("kind")))
                val id = requireNotNull(owner.arguments?.getString("id"))
                val api = currentSource.library
                currentStores.Content { models ->
                    val model: DetailViewModel = viewModel(models, key = "detail-${kind.name}-$id",
                        factory = viewModelFactory { initializer { DetailViewModel(api, kind, id) } })
                    DetailScreen(model, currentLoader, {
                        val returnOccurrence = owner.savedStateHandle.get<String>("returnPlayerOccurrence")
                        navigation.popBackStack()
                        if (returnOccurrence != null && returnOccurrence == currentController.value?.presentation?.queue?.occurrence?.eventId)
                            expanded = true
                    }, currentOpen, playback = currentController.value, onNavigate = navigate, onMore = openActions)
                }
            }
            composable("settings") {
                SettingsScreen(identity, palettes, currentPlayer.value.cache, modeSetting = { ManualModeSetting(identity, sessions, playbackOwner) }, onCache = { navigation.navigate("music-cache") }, onAccount = { navigation.navigate("account") },
                    onColours = { navigation.navigate("colours") }, onCredits = { navigation.navigate("credits") })
            }
            composable("music-cache") { dev.spofify.mobile.feature.settings.MusicCacheScreen(currentPlayer.value.cache) }
            composable("colours") { AccountColoursScreen(identity, palettes) }
            composable("credits") { CreditsScreen { navigation.navigate("font-license") } }
            composable("font-license") { FontLicenseScreen() }
            composable("account") { currentAccount.value() }
        }
    }
    val hasTrack = target?.observe()?.queue?.current != null
    LaunchedEffect(target, source) { devices = false; actions = null; rowPlaybackNotice = null }
    LaunchedEffect(hasTrack) { if (!hasTrack) expanded = false }
    Column(Modifier.fillMaxSize()) {
        PlayerConnectionStatus(target, rowPlaybackNotice?.takeIf { it.first == entry?.id }?.second)
        NavHost(navigation, startDestination = "history", modifier = Modifier.weight(1f), builder = graph)
        if (!search || imeOcclusion == 0) {
            target?.let {
                MiniPlayer(it, loader, {
                    focus.clearFocus()
                    keyboard?.hide()
                    expanded = true
                }, { devices = true })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 4.dp, end = 4.dp, top = 5.dp, bottom = 3.dp)) {
                MusicTab.entries.forEach { destination ->
                    val active = tab == destination
                    TextButton({
                        focus.clearFocus()
                        keyboard?.hide()
                        expanded = false
                        actions = null
                        rowPlaybackNotice = null
                        tab = destination
                        if (destination == MusicTab.SEARCH) focusIntent++
                        navigation.navigate(destination.route) {
                            popUpTo(navigation.graph.id)
                            launchSingleTop = true
                        }
                    }, Modifier.weight(1f).heightIn(min = 60.dp).semantics {
                        role = Role.Tab
                        selected = active
                    }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 5.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = if (active)
                            MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            SpofifyIcon(destination.glyph, null, Modifier.size(24.dp))
                            Text(destination.title, style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal))
                        }
                    }
                }
            }
        }
    }
    stores.Content {
      target?.let { active ->
        if (expanded) FullPlayer(active, loader, source.content, onNavigate = { kind, id ->
            val occurrence = active.presentation.queue?.occurrence?.eventId
            expanded = false
            navigate(kind, id)
            navigation.currentBackStackEntry?.savedStateHandle?.set("returnPlayerOccurrence", occurrence)
        }) { expanded = false }
        if (devices && active is AppPlayback.Online) DevicesSheet(active.controller) { devices = false }
      }
      actions?.let { selection ->
          MusicActionsSheet(selection, currentController.value, source.personal, loader, navigate) { actions = null }
      }
    }
}

@Composable
private fun PlayerConnectionStatus(player: AppPlayback?, rowNotice: String?) {
    if (player !is AppPlayback.Online) return
    val state = player.observe().online
    val notice = listOfNotNull(state?.let(::playbackNotice), rowNotice).joinToString(" · ")
    if (rowNotice == null && state?.connection == PlaybackConnectionState.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (notice.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(notice, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
