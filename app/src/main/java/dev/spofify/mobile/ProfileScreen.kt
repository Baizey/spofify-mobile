package dev.spofify.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A normal shell destination. Identity is supplied by the authenticated client, never saved login. */
@Composable
internal fun ProfileScreen(
    username: String,
    origin: String,
    busy: Boolean,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.testTag("profile-screen"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Account and server", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text("Username", style = MaterialTheme.typography.labelMedium)
            Text(username, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        }
        item {
            Text("Server", style = MaterialTheme.typography.labelMedium)
            Text(origin, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        }
        item { ProfileAction("Sign out", enabled = !busy, onClick = onSignOut) }
    }
}

/** Attribution is local and remains readable without making any server request. */
@Composable
internal fun CreditsScreen(onFontLicense: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.testTag("credits-screen"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Credits", style = MaterialTheme.typography.titleMedium) }
        item {
            Text("JetBrains Mono", style = MaterialTheme.typography.titleSmall)
            Text("App typeface · SIL Open Font License 1.1", style = MaterialTheme.typography.bodyMedium)
        }
        item { ProfileAction("View full font license", onClick = onFontLicense) }
    }
}

@Composable
private fun ProfileAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label)
    }
}
