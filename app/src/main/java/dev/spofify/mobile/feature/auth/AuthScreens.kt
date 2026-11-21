package dev.spofify.mobile.feature.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import dev.spofify.mobile.R
import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.designsystem.Spacing
import dev.spofify.mobile.designsystem.SpofifyTheme

@Composable
internal fun LoginScreen(
    inputs: LoginInputs,
    busy: Boolean,
    notice: AuthNotice?,
    onServer: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onReveal: () -> Unit,
    onSignIn: () -> Unit,
    goOnline: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val submit = { if (goOnline == null && !busy && inputs.server.isNotEmpty() && inputs.username.isNotEmpty() && inputs.password.isNotEmpty()) {
        focus.clearFocus(); onSignIn()
    } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            LoginHeading()
            LoginFields(inputs, busy, onServer, onUsername, onPassword, onReveal, submit)
        }
        if (goOnline == null) LoginActions(busy, notice, inputs, submit)
        else Column(Modifier.fillMaxWidth().padding(Spacing.page)) {
            Notice(notice)
            Button(goOnline, Modifier.fillMaxWidth().heightIn(min = Spacing.action)) { Text("Go online") }
        }
    }
}

@Composable
private fun LoginHeading() {
    Column(Modifier.padding(horizontal = Spacing.page).padding(top = Spacing.group)) {
        BrandWordmark()
        Spacer(Modifier.height(Spacing.group))
        Text(stringResource(R.string.sign_in), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun BrandWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_spofify_mark), contentDescription = null, modifier = Modifier.size(44.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LoginFields(
    inputs: LoginInputs,
    busy: Boolean,
    onServer: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onReveal: () -> Unit,
    submit: () -> Unit,
) {
    Column(Modifier.padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(Spacing.fieldGap)) {
        LoginField(stringResource(R.string.server_url), inputs.server, onServer, busy,
            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false))
        LoginField(stringResource(R.string.username), inputs.username, onUsername, busy,
            KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false))
        Column {
            val passwordLabel = stringResource(R.string.password)
            Text(passwordLabel, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = Spacing.page))
            TextField(
                value = inputs.password, onValueChange = onPassword, enabled = !busy,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = passwordLabel },
                singleLine = true,
                visualTransformation = if (inputs.revealed) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    val action = stringResource(if (inputs.revealed) R.string.hide_password_accessibility else R.string.show_password_accessibility)
                    TextButton(onClick = onReveal, enabled = !busy,
                        modifier = Modifier.sizeIn(minWidth = Spacing.target, minHeight = Spacing.target)
                            .semantics { contentDescription = action }) {
                        Text(stringResource(if (inputs.revealed) R.string.hide_password else R.string.show_password))
                    }
                },
                colors = loginFieldColors(),
            )
        }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    change: (String) -> Unit,
    busy: Boolean,
    keyboard: KeyboardOptions,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.page))
        TextField(value, change, enabled = !busy, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            keyboardOptions = keyboard, colors = loginFieldColors())
    }
}

@Composable
private fun loginFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.background,
    unfocusedContainerColor = MaterialTheme.colorScheme.background,
    disabledContainerColor = MaterialTheme.colorScheme.background,
    focusedIndicatorColor = MaterialTheme.colorScheme.tertiary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
)

@Composable
private fun LoginActions(busy: Boolean, notice: AuthNotice?, inputs: LoginInputs, submit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Spacing.page)) {
        Notice(notice)
        Button(onClick = submit, enabled = !busy && inputs.server.isNotEmpty() &&
            inputs.username.isNotEmpty() && inputs.password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.action),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
            Text(stringResource(if (busy) R.string.signing_in else R.string.sign_in))
        }
    }
}

@Composable
internal fun AccountScreen(state: AuthState.Authenticated, onSignOut: () -> Unit, onCredits: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(Spacing.page)) {
            Text(stringResource(R.string.account), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Spacing.group))
            Text(stringResource(R.string.username), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.identity.username)
            Spacer(Modifier.height(Spacing.group))
            Text(stringResource(R.string.server_url), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.identity.origin)
            Spacer(Modifier.height(Spacing.group))
            Notice(state.notice)
            TextButton(onClick = onCredits, modifier = Modifier.heightIn(min = Spacing.target)) {
                Text(stringResource(R.string.credits))
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.action),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                Text(stringResource(R.string.sign_out))
            }
        }
    }
}

@Composable
internal fun ConnectionScreen(notice: AuthNotice?, onRetry: (() -> Unit)? = null,
    onManual: (() -> Unit)? = null, onSignOut: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.page),
        verticalArrangement = Arrangement.spacedBy(Spacing.fieldGap)) {
        BrandWordmark()
        if (notice == null) {
            CircularProgressIndicator()
            Text(stringResource(R.string.connecting), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else {
            Notice(notice)
            onRetry?.let { Button(onClick = it) { Text(stringResource(R.string.retry)) } }
            onManual?.let { TextButton(onClick = it) { Text(stringResource(R.string.sign_in)) } }
            onSignOut?.let { TextButton(onClick = it) { Text(stringResource(R.string.sign_out)) } }
        }
    }
}

@Composable
internal fun Notice(notice: AuthNotice?) {
    if (notice == null) return
    val resource = when (notice) {
        AuthNotice.INVALID_ORIGIN -> R.string.invalid_origin
        AuthNotice.INVALID_CREDENTIALS -> R.string.invalid_credentials
        AuthNotice.CONNECTION_FAILED -> R.string.connection_failed
        AuthNotice.STORAGE_UNAVAILABLE -> R.string.storage_unavailable
        AuthNotice.SAVE_FAILED -> R.string.save_failed
        AuthNotice.SESSION_EXPIRED -> R.string.session_expired
        AuthNotice.REMOTE_LOGOUT_FAILED -> R.string.remote_logout_failed
        AuthNotice.LOCAL_LOGOUT_FAILED -> R.string.local_logout_failed
    }
    Text(stringResource(resource), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

@Preview(widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable
private fun LoginLargeTextPreview() {
    SpofifyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoginScreen(remember { LoginInputs() }, false, null, {}, {}, {}, {}, {})
        }
    }
}
