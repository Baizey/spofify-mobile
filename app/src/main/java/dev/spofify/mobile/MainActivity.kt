package dev.spofify.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.spofify.mobile.core.auth.AuthNotice
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.feature.library.MusicRoot
import dev.spofify.mobile.playback.PlaybackOwner
import dev.spofify.mobile.designsystem.SpofifySurfaces
import dev.spofify.mobile.designsystem.SpofifyTheme
import dev.spofify.mobile.feature.auth.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val canvas = getColor(R.color.theme_canvas)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(canvas),
            navigationBarStyle = SystemBarStyle.dark(canvas),
        )
        val app = application as SpofifyApplication
        val repository = app.sessions
        setContent {
            SpofifyTheme {
                val model: AuthViewModel = viewModel(factory = AuthViewModel.Factory(repository))
                AuthRoot(model, repository, app.playback,
                    updates = { app.updates.SettingsRow(this@MainActivity, app.playback::blocksAppUpdate) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as SpofifyApplication
        app.sessions.setAppForeground(true)
        app.playback.foreground()
        app.updates.setForeground(true)
    }

    override fun onStop() {
        val app = application as SpofifyApplication
        app.updates.setForeground(false)
        app.sessions.setAppForeground(false)
        super.onStop()
    }
}

@Composable
private fun AuthRoot(
    model: AuthViewModel,
    sessions: SessionRepository,
    playback: PlaybackOwner,
    updates: @Composable () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val inputNotice by model.inputNotice.collectAsStateWithLifecycle()
    val manual by sessions.manualOffline.collectAsStateWithLifecycle()
    val policyLoaded by sessions.offlinePolicyLoaded.collectAsStateWithLifecycle()
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
    // Single owner consumes system/cutout/gesture/IME safe drawing insets.
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val current = state) {
                is AuthState.Authenticated -> {
                    pages.SaveableStateProvider(requireNotNull(pageKey)) {
                        var credits by rememberSaveable { mutableStateOf(false) }
                        MusicRoot(current.identity, sessions, playback, updates = updates) {
                            AccountScreen(current, model::signOut) { credits = true }
                        }
                        if (credits) FontCredits { credits = false }
                    }
                }
                is AuthState.SignedOut -> if (policyLoaded) {
                    LoginScreen(model.inputs, false, current.notice ?: inputNotice,
                        model::server, model::username, model::password, model::reveal, model::signIn, model::goOnline.takeIf { manual })
                } else {
                    ConnectionScreen(current.notice ?: AuthNotice.STORAGE_UNAVAILABLE)
                }
                AuthState.SigningIn -> LoginScreen(model.inputs, true, null,
                    model::server, model::username, model::password, model::reveal, model::signIn)
                AuthState.Starting, AuthState.Validating -> ConnectionScreen(null)
                is AuthState.Unavailable -> ConnectionScreen(current.notice,
                    onManual = model::manualLogin.takeIf { policyLoaded }, onSignOut = model::signOut)
            }
        }
    }
}

@Composable
private fun FontCredits(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val license = remember { context.assets.open("licenses/JetBrainsMono-OFL.txt").bufferedReader().use { it.readText() } }
    val colors = MaterialTheme.colorScheme
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.font_name)) },
        modifier = Modifier.border(1.dp, colors.outlineVariant, SpofifySurfaces.panel),
        shape = SpofifySurfaces.panel, containerColor = colors.surface, tonalElevation = 0.dp,
        text = { Text(license, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
