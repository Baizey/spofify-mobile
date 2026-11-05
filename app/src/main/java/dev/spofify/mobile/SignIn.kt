package dev.spofify.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
        // A saved login is one identity, never credentials joined to a later address hint.
        if (saved != null) useSaved(saved)
    }

    fun changeServer(value: String) {
        if (origin(value) == null || origin(value) != origin(server)) forgetFields()
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
) {
    // Initial prefill only. Busy/error/server publications must not replace an edited form.
    val form = remember { SignInForm(state.server, rememberedLogin) }
    var submitted by remember { mutableStateOf(false) }
    // Guard same-frame IME/button delivery, but do not require an intermediate busy
    // render: fast failures may publish busy and idle before Compose observes either.
    LaunchedEffect(submitted, state.busy) {
        if (submitted && !state.busy) submitted = false
    }
    val address = runCatching { ServerAddress.parse(form.server) }
    val addressError = address.exceptionOrNull()?.message?.takeIf { form.server.isNotBlank() }
    val enabled = !state.busy && !submitted
    val canSubmit = enabled && address.isSuccess && form.username.isNotBlank() && form.password.isNotEmpty()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (canSubmit && !submitted) {
            submitted = true
            form.showPassword = false
            keyboard?.hide()
            focus.clearFocus()
            signIn(form.server, form.username, form.password)
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sign in", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(form.server, form::changeServer, label = { Text("Server URL") },
                singleLine = true, enabled = enabled, isError = addressError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
                modifier = Modifier.fillMaxWidth())
            addressError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(form.username, form::changeUsername, label = { Text("Username") }, singleLine = true,
                enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(form.password, form::changePassword, label = { Text("Password") }, singleLine = true,
                enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                visualTransformation = if (form.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(modifier = Modifier.size(48.dp), enabled = enabled, onClick = { form.showPassword = !form.showPassword }) {
                    Icon(if (form.showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (form.showPassword) "Hide password" else "Show password")
                } }, modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (state.canRetryConnection) OutlinedButton(onClick = reconnect, enabled = enabled,
                modifier = Modifier.fillMaxWidth()) { Text("Retry saved connection") }
            Button(onClick = { submit() }, enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Sign in")
            }
        }
    }
}
