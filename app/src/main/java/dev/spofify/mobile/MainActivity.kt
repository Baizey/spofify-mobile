package dev.spofify.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.feature.library.MusicRoot
import dev.spofify.mobile.playback.PlaybackOwner
import dev.spofify.mobile.designsystem.SpofifyTheme
import dev.spofify.mobile.feature.auth.*
import dev.spofify.mobile.feature.settings.AccountPaletteController
import dev.spofify.mobile.feature.settings.accountPaletteTokens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val app = application as SpofifyApplication
        val repository = app.sessions
        setContent {
            val authentication by repository.state.collectAsStateWithLifecycle()
            val palette by app.palettes.state.collectAsStateWithLifecycle()
            val identity = (authentication as? AuthState.Authenticated)?.identity
            SpofifyTheme(accountPaletteTokens(palette, identity)) {
                val model: AuthViewModel = viewModel(factory = AuthViewModel.Factory(repository))
                AuthRoot(model, repository, app.playback, app.palettes)
            }
        }
    }
}

@Composable
private fun AuthRoot(
    model: AuthViewModel,
    sessions: SessionRepository,
    playback: PlaybackOwner,
    palettes: AccountPaletteController,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val inputNotice by model.inputNotice.collectAsStateWithLifecycle()
    val manual by sessions.manualOffline.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val pages = rememberSaveableStateHolder()
    var previousPageKey by rememberSaveable { mutableStateOf<String?>(null) }
    val pageKey = (state as? AuthState.Authenticated)?.identity?.let {
        "${it.origin.length}:${it.origin}${it.accountId.length}:${it.accountId}:${it.incarnation}"
    }
    LaunchedEffect(pageKey, state) {
        if (pageKey != null && pageKey != previousPageKey) {
            previousPageKey?.let(pages::removeState)
            previousPageKey = pageKey
        } else if (state is AuthState.SignedOut || state == AuthState.SigningIn) {
            previousPageKey?.let(pages::removeState)
            previousPageKey = null
        }
    }
    DisposableEffect(owner, model, playback, palettes) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (playback.current != null) playback.foreground() else model.foreground()
                palettes.refresh()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Single owner consumes system/cutout/gesture/IME safe drawing insets.
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val current = state) {
                is AuthState.Authenticated -> {
                    pages.SaveableStateProvider(requireNotNull(pageKey)) {
                        var credits by rememberSaveable { mutableStateOf(false) }
                        MusicRoot(current.identity, sessions, playback, palettes) {
                            AccountScreen(current, model::signOut) { credits = true }
                        }
                        if (credits) FontCredits { credits = false }
                    }
                }
                is AuthState.SignedOut -> LoginScreen(model.inputs, false, current.notice ?: inputNotice,
                    model::server, model::username, model::password, model::reveal, model::signIn, model::goOnline.takeIf { manual })
                AuthState.SigningIn -> LoginScreen(model.inputs, true, null,
                    model::server, model::username, model::password, model::reveal, model::signIn)
                AuthState.Starting, AuthState.Validating -> ConnectionScreen(null)
                is AuthState.Unavailable -> ConnectionScreen(current.notice, model::retry, model::manualLogin, model::signOut)
            }
        }
    }
}

@Composable
private fun FontCredits(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val license = remember { context.assets.open("licenses/JetBrainsMono-OFL.txt").bufferedReader().use { it.readText() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.font_name)) },
        text = { Text(license, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
