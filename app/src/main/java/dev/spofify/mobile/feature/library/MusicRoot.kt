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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.library.*

@Composable
internal fun MusicRoot(identity: AccountIdentity, sessions: SessionRepository, account: @Composable () -> Unit) {
    val context = LocalContext.current
    val api = remember(identity, sessions) { LibraryClient(sessions, identity) }
    val loader = remember(api) { artworkLoader(context.applicationContext, api) }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    val navigation = rememberNavController()
    val currentAccount = rememberUpdatedState(account)
    var focusIntent by rememberSaveable { mutableIntStateOf(0) }
    val entry by navigation.currentBackStackEntryAsState()
    val search = entry?.destination?.route != "account"
    val imeOcclusion = WindowInsets.ime.getBottom(LocalDensity.current)
    val open: (MusicItem) -> Unit = { item ->
        navigation.navigate("music/${item.kind.name}/${Uri.encode(item.id)}") { launchSingleTop = true }
    }
    Column(Modifier.fillMaxSize()) {
        NavHost(navigation, startDestination = "search", modifier = Modifier.weight(1f)) {
            composable("search") { owner ->
                val model: SearchViewModel = viewModel(owner, factory = viewModelFactory { initializer { SearchViewModel(api) } })
                SearchScreen(model, loader, focusIntent, open)
            }
            composable("music/{kind}/{id}", arguments = listOf(
                navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType },
            )) { owner ->
                val kind = MusicKind.valueOf(requireNotNull(owner.arguments?.getString("kind")))
                val id = requireNotNull(owner.arguments?.getString("id"))
                val model: DetailViewModel = viewModel(owner,
                    factory = viewModelFactory { initializer { DetailViewModel(api, kind, id) } })
                DetailScreen(model, loader, { navigation.popBackStack() }, open)
            }
            composable("account") { currentAccount.value() }
        }
        if (!search || imeOcclusion == 0) {
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
}
