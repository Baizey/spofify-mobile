package dev.spofify.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ServerAddress

/** Ephemeral form memory only: never rememberSaveable or put passwords in a StateFlow. */
internal class SignInForm(initialServer: String, saved: RememberedLogin?) {
    var server by mutableStateOf(initialServer)
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var showPassword by mutableStateOf(false)

    init {
        if (saved != null && origin(initialServer) == saved.server) useSaved(saved)
    }

    fun changeServer(value: String) {
        if (origin(value) != origin(server)) forgetFields()
        server = value
    }

    fun changeUsername(value: String) {
        if (value != username) clearPassword()
        username = value
    }

    fun changePassword(value: String) { password = value }
    fun clearPassword() { password = ""; showPassword = false }
    fun forgetFields() { username = ""; clearPassword() }
    fun useSaved(saved: RememberedLogin) {
        server = saved.server
        username = saved.username
        password = saved.password
        showPassword = false
    }

    override fun toString() = "SignInForm([REDACTED])"
    private fun origin(value: String) = runCatching { ServerAddress.parse(value).origin }.getOrNull()
}

@Composable
internal fun SignIn(
    state: LibraryState,
    rememberedLogin: RememberedLogin?,
    signIn: (String, String, String) -> Unit,
    reconnect: () -> Unit,
    setRememberLogin: (Boolean) -> Unit,
    forgetSavedLogin: () -> Unit,
) {
    val form = remember(state.server) { SignInForm(state.server, rememberedLogin) }
    val address = runCatching { ServerAddress.parse(form.server) }
    val addressError = address.exceptionOrNull()?.message?.takeIf { form.server.isNotBlank() }
    val cleartext = form.server.trim().startsWith("http://", ignoreCase = true)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.GraphicEq, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Your music.\nYour server.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Connect to Spofify and make yourself at home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(form.server, form::changeServer, label = { Text("Server URL") },
                placeholder = { Text("https://music.example.com") }, singleLine = true, enabled = !state.busy,
                isError = addressError != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth())
            Text("Use your server's full origin, including its port if needed. No /listen or /api path.",
                style = MaterialTheme.typography.bodySmall)
            addressError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (cleartext) {
                Text("HTTP is not encrypted. Your password and music travel openly over the network. Use only a trusted local network.",
                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(form.username, form::changeUsername, label = { Text("Username") }, singleLine = true,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(form.password, form::changePassword, label = { Text("Password") }, singleLine = true,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (form.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(enabled = !state.busy, onClick = { form.showPassword = !form.showPassword }) {
                    Icon(if (form.showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (form.showPassword) "Hide password" else "Show password")
                } }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().toggleable(value = state.rememberLogin, enabled = !state.busy, role = Role.Checkbox,
                onValueChange = { enabled ->
                    if (!enabled) form.clearPassword()
                    setRememberLogin(enabled)
                }).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.rememberLogin, onCheckedChange = null, enabled = !state.busy)
                Text("Remember sign-in on this device", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Text("Saves the server, username and password encrypted on this device after a successful sign-in. Turn off on shared devices.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hasSavedLogin && rememberedLogin != null) {
                Text("Saved for ${rememberedLogin.server}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !state.busy, onClick = { form.useSaved(rememberedLogin) }) { Text("Use saved login") }
                    TextButton(enabled = !state.busy, onClick = { form.forgetFields(); forgetSavedLogin() }) { Text("Forget saved login") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (state.canRetryConnection) OutlinedButton(onClick = reconnect, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Retry saved connection") }
            Button(onClick = { form.showPassword = false; signIn(form.server, form.username, form.password) },
                enabled = !state.busy && address.isSuccess && form.username.isNotBlank() && form.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(54.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Connect & listen", fontWeight = FontWeight.Bold)
            }
            Text("Use an existing Spofify account. No Spotify sign-in required.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
