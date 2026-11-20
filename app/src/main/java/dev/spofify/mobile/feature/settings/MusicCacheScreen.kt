package dev.spofify.mobile.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.cache.CacheOptions
import dev.spofify.mobile.core.cache.MusicStorageUsage
import dev.spofify.mobile.designsystem.SpofifySwitchIndicator
import dev.spofify.mobile.playback.AutomaticMusicCache
import dev.spofify.mobile.playback.CacheClearState

@Composable
private fun storageSize(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
internal fun musicStorageSummary(usage: MusicStorageUsage?) =
    if (usage == null) "Storage unavailable" else "${storageSize(usage.totalBytes)} total"

@Composable
internal fun MusicCacheScreen(cache: AutomaticMusicCache) {
    val options by cache.options.collectAsStateWithLifecycle()
    val usage by cache.usage.collectAsStateWithLifecycle()
    val clear by cache.clearState.collectAsStateWithLifecycle()
    val storage = usage
    SettingsPage("Music storage") {
        StorageCaption(musicStorageSummary(storage))

        SettingsGroup("Offline playlists")
        if (storage != null) {
            Text("${storageSize(storage.offlineBytes)} · ${storage.offlinePlaylistCount} " +
                if (storage.offlinePlaylistCount == 1) "playlist" else "playlists")
        }

        SettingsGroup("Automatic cache")
        if (storage != null) {
            Text("${storageSize(storage.cacheBytes)} · ${storage.cachedSongCount} " +
                if (storage.cachedSongCount == 1) "song" else "songs")
        }
        TextButton(cache::clearAutomaticCache, enabled = storage != null && clear != CacheClearState.CLEARING) {
            Text(if (clear == CacheClearState.CLEARING) "Clearing…" else "Clear cache")
        }
        if (clear == CacheClearState.FAILED) {
            Text("Couldn’t clear cache", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        CacheSelector("Keep cached songs", options.cap, CacheOptions.caps) { cache.configure(options.copy(cap = it)) }
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp)
            .toggleable(options.metered, role = Role.Switch) { cache.configure(options.copy(metered = it)) },
            verticalAlignment = Alignment.CenterVertically) {
            Text("Cache on metered networks", Modifier.weight(1f))
            SpofifySwitchIndicator(options.metered, enabled = true)
        }
    }
}

@Composable
private fun StorageCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun CacheSelector(label: String, value: Int, choices: List<Int>, select: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsLink(label, value.toString()) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { DropdownMenuItem(text = { Text(it.toString()) }, onClick = { expanded = false; select(it) }) }
        }
    }
}
