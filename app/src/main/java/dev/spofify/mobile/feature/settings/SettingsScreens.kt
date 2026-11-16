package dev.spofify.mobile.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.palette.PaletteTokens
import dev.spofify.mobile.core.palette.PaletteId
import dev.spofify.mobile.designsystem.SpofifyGlyph
import dev.spofify.mobile.designsystem.SpofifyIcon
import dev.spofify.mobile.designsystem.paletteColor
import dev.spofify.mobile.feature.library.PageTitle

@Composable
internal fun SettingsScreen(
    identity: AccountIdentity,
    theme: DeviceThemeController,
    palettes: AccountPaletteController,
    onAccount: () -> Unit,
    onColours: () -> Unit,
    onCredits: () -> Unit,
) {
    val appearance by theme.state.collectAsStateWithLifecycle()
    val paletteState by palettes.state.collectAsStateWithLifecycle()
    val palette = paletteState.takeIf { it.owner == identity }?.confirmed
    SettingsPage("Settings") {
        SettingsGroup("Account")
        SettingsLink("Account and server", identity.username, onAccount)
        SettingsLink("Account colours", palette?.selected?.name, onColours)
        SettingsGroup("Appearance")
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp)
            .toggleable(value = appearance.light, enabled = appearance.loaded && !appearance.saving,
                role = Role.Switch, onValueChange = theme::choose),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Light theme", Modifier.weight(1f))
            Switch(checked = appearance.light, onCheckedChange = null, enabled = appearance.loaded && !appearance.saving)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        appearance.notice?.let { SettingsNotice(it) }
        SettingsLink("Credits", null, onCredits)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountColoursScreen(identity: AccountIdentity, palettes: AccountPaletteController, lightTheme: Boolean) {
    val current by palettes.state.collectAsStateWithLifecycle()
    // The graph may outlive an auth callback for a frame; never render another owner's state.
    val state = current.takeIf { it.owner == identity }
    val confirmed = state?.confirmed
    val selection = state?.draft ?: confirmed?.paletteId
    val enabled = state != null && confirmed != null && !state.loading && !state.saving && !state.needsRefresh
    SettingsPage("Account colours") {
        Text(identity.username, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        if (state?.loading == true || state?.saving == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        state?.notice?.let { SettingsNotice(it) }
        if (confirmed == null && state?.loading != true) {
            SettingsNotice("Account colours are unavailable. Reload before choosing a palette.")
        }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            confirmed?.presets?.forEach { preset ->
                val tokens = if (lightTheme) preset.light else preset.dark
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp)
                    .selectable(selected = selection == preset.id, enabled = enabled, role = Role.RadioButton,
                        onClick = { palettes.choose(preset.id) }).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaletteSwatches(tokens)
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text(when (preset.id) {
                            PaletteId.JADE -> "Sky / gold"
                            PaletteId.EMBER -> "Lilac / mint"
                            PaletteId.VIOLET -> "Teal / rose"
                        },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        if (selection == preset.id) SpofifyIcon(SpofifyGlyph.Check, null, tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                HorizontalDivider(thickness = if (selection == preset.id) 2.dp else 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (confirmed != null) {
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpofifyIcon(SpofifyGlyph.Play, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                    Text("Playing", color = MaterialTheme.colorScheme.primary)
                }
                Text("Search", color = MaterialTheme.colorScheme.secondary)
                Text("Offline", color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (state?.loading != true && state?.saving != true) {
            if (confirmed == null || state.needsRefresh || state.notice != null) {
                TextButton(palettes::refresh, contentPadding = PaddingValues(vertical = 12.dp)) { Text("Reload colours") }
            }
        }
    }
}

@Composable
private fun PaletteSwatches(tokens: PaletteTokens) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(tokens.primary, tokens.secondary, tokens.tertiary).forEach { token ->
            Box(Modifier.size(width = 12.dp, height = 18.dp).clip(RoundedCornerShape(4.dp)).background(paletteColor(token)))
        }
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
private fun SettingsPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        PageTitle(title)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsGroup(label: String) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp).semantics { heading() })
}

@Composable
private fun SettingsLink(label: String, value: String?, onClick: () -> Unit) {
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

@Composable
private fun SettingsNotice(message: String) {
    Text(message, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
