package dev.spofify.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import dev.spofify.mobile.designsystem.SpofifyTheme
import dev.spofify.mobile.feature.auth.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val repository = (application as SpofifyApplication).sessions
        setContent {
            SpofifyTheme {
                val model: AuthViewModel = viewModel(factory = AuthViewModel.Factory(repository))
                AuthRoot(model, repository)
            }
        }
    }
}

@Composable
private fun AuthRoot(model: AuthViewModel, sessions: SessionRepository) {
    val state by model.state.collectAsStateWithLifecycle()
    val inputNotice by model.inputNotice.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.foreground()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Single owner consumes system/cutout/gesture/IME safe drawing insets.
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val current = state) {
                is AuthState.Authenticated -> {
                    var credits by remember { mutableStateOf(false) }
                    MusicRoot(current.identity, sessions) {
                        AccountScreen(current, model::signOut) { credits = true }
                    }
                    if (credits) FontCredits { credits = false }
                }
                is AuthState.SignedOut -> LoginScreen(model.inputs, false, current.notice ?: inputNotice,
                    model::server, model::username, model::password, model::reveal, model::signIn)
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
