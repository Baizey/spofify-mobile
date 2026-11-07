package dev.spofify.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.playback.cache.AutoCacheSettings
import java.util.Locale

internal enum class SettingsDestination { ACCOUNT, CREDITS }

/**
 * Only implemented destinations are indexed here. Inline automatic-cache controls have their own
 * filtered index; the shell owns neither their policy nor committed preference state.
 * MainActivity routes [destination].
 */
internal data class SettingsEntry(
    val destination: SettingsDestination,
    val group: String,
    val title: String,
    val summary: String,
    val searchTerms: String,
)

internal fun settingsEntries(username: String, origin: String) = listOf(
    SettingsEntry(SettingsDestination.ACCOUNT, "Account", "Account and server", username,
        "profile username server connection sign out $username $origin"),
    SettingsEntry(SettingsDestination.CREDITS, "About", "Credits", "JetBrains Mono · SIL Open Font License 1.1",
        "about app typeface font license attribution offline notices"),
)

internal fun matchesSettingsQuery(query: String, vararg fields: String): Boolean {
    val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
    val text = fields.joinToString(" ").lowercase(Locale.ROOT)
    return words.all(text::contains)
}

internal fun matchingSettings(entries: List<SettingsEntry>, query: String): List<SettingsEntry> =
    entries.filter { matchesSettingsQuery(query, it.group, it.title, it.summary, it.searchTerms) }

/** A full shell destination; local search never calls music search or the server. */
@Composable
internal fun SettingsScreen(
    username: String,
    origin: String,
    onOpen: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
    cacheSettings: AutoCacheSettings? = null,
    onSaveCacheSettings: suspend (AutoCacheSettings) -> String? = { "Couldn’t save. Try again." },
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val entries = remember(username, origin) { settingsEntries(username, origin) }
    val matches = remember(entries, query) { matchingSettings(entries, query) }
    val cacheMatches = remember(cacheSettings, query) {
        cacheSettings?.let { matchingAutoCachePreferences(it, query) }.orEmpty()
    }
    LazyColumn(modifier.testTag("settings-screen"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "title") {
            Text("Settings", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() })
        }
        item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, label = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear settings search") }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .testTag("settings-query"),
            )
        }
        if (matches.isEmpty() && cacheMatches.isEmpty()) item {
            Text("No matching settings", style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp))
        }
        if (cacheSettings != null && cacheMatches.isNotEmpty()) item(key = "automatic-cache") {
            AutoCachePreferences(cacheSettings, onSaveCacheSettings, cacheMatches)
        }
        matches.groupBy(SettingsEntry::group).forEach { (group, rows) ->
            item(key = "group:$group") {
                Text(group, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() })
            }
            items(rows, key = { it.destination }) { entry ->
                SettingsPreferenceRow(entry.title, entry.summary,
                    if (entry.destination == SettingsDestination.ACCOUNT) Icons.Default.AccountCircle else Icons.Default.Info,
                    onClick = { onOpen(entry.destination) })
            }
        }
    }
}

/** Wrapping text and a minimum (not fixed) height keep preferences usable at large font sizes. */
@Composable
internal fun SettingsPreferenceRow(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
