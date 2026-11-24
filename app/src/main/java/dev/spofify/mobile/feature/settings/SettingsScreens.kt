package dev.spofify.mobile.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.feature.library.PageTitle
import dev.spofify.mobile.playback.AutomaticMusicCache

@Composable
internal fun SettingsScreen(
    identity: AccountIdentity,
    cache: AutomaticMusicCache?,
    modeSetting: @Composable () -> Unit,
    onCache: () -> Unit,
    onAccount: () -> Unit,
    onCredits: () -> Unit,
    playbackSetting: @Composable () -> Unit = {},
    updates: @Composable () -> Unit = {},
) {
    SettingsPage("Settings") {
        SettingsGroup("Account")
        SettingsLink("Account and server", identity.username, onAccount)
        SettingsGroup("Playback")
        playbackSetting()
        SettingsGroup("Storage")
        modeSetting()
        SettingsLink("Music storage", musicStorageSummary(cache?.usage?.collectAsStateWithLifecycle()?.value), onCache)
        SettingsGroup("About")
        updates()
        SettingsLink("Credits", null, onCredits)
    }
}

@Composable
internal fun CreditsScreen(onFontLicense: () -> Unit) {
    SettingsPage("Credits") { SettingsLink("JetBrains Mono", "SIL Open Font License 1.1", onFontLicense) }
}

@Composable
internal fun FontLicenseScreen() {
    val context = LocalContext.current
    val license = remember(context) {
        context.assets.open("licenses/JetBrainsMono-OFL.txt").bufferedReader().use { it.readText() }
    }
    SettingsPage("Font license") { Text(license, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp)) }
}

@Composable
internal fun SettingsPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        PageTitle(title)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun SettingsGroup(label: String) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp).semantics { heading() })
}

@Composable
internal fun SettingsLink(label: String, value: String?, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label)
                if (!value.isNullOrBlank()) Text(value, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
