package dev.spofify.mobile.feature.library

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import dev.spofify.mobile.core.library.*
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.playback.*

@Composable
internal fun MusicRoot(
    identity: AccountIdentity,
    sessions: SessionRepository,
    playbackOwner: PlaybackOwner,
    account: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val api = remember(identity, sessions) { LibraryClient(sessions, identity) }
    val loader = remember(api) { artworkLoader(context.applicationContext, api) }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    val native by playbackOwner.state.collectAsStateWithLifecycle()
    val player = native?.takeIf { it.identity == identity }
    val currentPlayer = rememberUpdatedState(player)
    val currentController = rememberUpdatedState(player?.controller)
    val navigation = rememberNavController()
    val currentAccount = rememberUpdatedState(account)
    var focusIntent by rememberSaveable { mutableIntStateOf(0) }
    val currentFocusIntent = rememberUpdatedState(focusIntent)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var devices by rememberSaveable { mutableStateOf(false) }
    val entry by navigation.currentBackStackEntryAsState()
    val search = entry?.destination?.route != "account"
    val imeOcclusion = WindowInsets.ime.getBottom(LocalDensity.current)
    val navigate: (MusicKind, String) -> Unit = remember(navigation) {
        { kind, id -> navigation.navigate("music/${kind.name}/${Uri.encode(id)}") { launchSingleTop = true } }
    }
    val open: (MusicItem) -> Unit = remember(navigate) {
        { item ->
            val controller = currentController.value
            if (item.kind == MusicKind.TRACK && item.hasAudio && controller?.state?.value?.connection == PlaybackConnectionState.READY)
                controller.playTrack(PlaybackTrackReference(item.id))
            else navigate(item.kind, item.id)
        }
    }
    // Progress, IME and player recomposition must never replace the navigation graph or its models.
    val graph: NavGraphBuilder.() -> Unit = remember(navigation, api, loader, open, navigate) {
        {
            composable("search") { owner ->
                val model: SearchViewModel = viewModel(owner, factory = viewModelFactory { initializer { SearchViewModel(api) } })
                SearchScreen(model, loader, currentFocusIntent.value, open, currentController.value)
            }
            composable("music/{kind}/{id}", arguments = listOf(
                navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType },
            )) { owner ->
                val kind = MusicKind.valueOf(requireNotNull(owner.arguments?.getString("kind")))
                val id = requireNotNull(owner.arguments?.getString("id"))
                val model: DetailViewModel = viewModel(owner,
                    factory = viewModelFactory { initializer { DetailViewModel(api, kind, id) } })
                DetailScreen(model, loader, { navigation.popBackStack() }, open,
                    playback = currentController.value, onNavigate = navigate)
            }
            composable("account") { currentAccount.value() }
        }
    }
    LaunchedEffect(player) {
        if (player == null) {
            expanded = false
            devices = false
        } else player.controller.state.collect { if (it.current == null) expanded = false }
    }
    Column(Modifier.fillMaxSize()) {
        player?.let { PlayerConnectionStatus(it) }
        NavHost(navigation, startDestination = "search", modifier = Modifier.weight(1f), builder = graph)
        if (!search || imeOcclusion == 0) {
            player?.let { MiniPlayer(it.controller, loader, { expanded = true }, { devices = true }) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
                TextButton({
                    focusIntent++
                    navigation.navigate("search") { popUpTo("search"); launchSingleTop = true }
                }, Modifier.weight(1f).heightIn(min = 64.dp)) { Text("Search", color = if (search)
                    MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton({ navigation.navigate("account") { launchSingleTop = true } },
                    Modifier.weight(1f).heightIn(min = 64.dp)) { Text("Settings", color = if (!search)
                    MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    currentPlayer.value?.let { active ->
        if (expanded) FullPlayer(active.controller, loader) { expanded = false }
        if (devices) DevicesSheet(active.controller) { devices = false }
    }
}

@Composable
private fun PlayerConnectionStatus(player: NativePlayer) {
    val state by player.controller.state.collectAsStateWithLifecycle()
    if (state.connection == PlaybackConnectionState.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (state.connection != PlaybackConnectionState.READY || state.error != null) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(state.error ?: "Player disconnected", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.connection != PlaybackConnectionState.READY) TextButton({ player.connect() }) { Text("Reconnect") }
        }
    }
}
