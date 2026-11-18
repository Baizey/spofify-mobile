package dev.spofify.mobile.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.cache.CacheOptions
import dev.spofify.mobile.core.cache.StoredSong
import dev.spofify.mobile.feature.library.PageTitle
import dev.spofify.mobile.playback.AutomaticMusicCache

@Composable
internal fun cacheSummary(songs: List<StoredSong>?): String {
    if (songs == null) return "Storage unavailable"
    val n = songs.count { it.auto && !it.offline }
    val bytes = Formatter.formatShortFileSize(LocalContext.current, songs.sumOf { it.bytes })
    return "$n ${if (n == 1) "song" else "songs"} · $bytes stored"
}

@Composable
internal fun MusicCacheScreen(cache: AutomaticMusicCache) {
    val options by cache.options.collectAsStateWithLifecycle()
    val songs by cache.songs.collectAsStateWithLifecycle()
    val rows = remember(songs) { songs.orEmpty().filter { it.auto }.sortedBy { it.title } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            PageTitle("Automatic music cache")
            Text(cacheSummary(songs), style = MaterialTheme.typography.bodySmall)
            CacheSelector("Keep songs", options.cap, CacheOptions.caps) { cache.configure(options.copy(cap = it)) }
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cache on metered networks", Modifier.weight(1f))
                Switch(options.metered, { cache.configure(options.copy(metered = it)) })
            }
            TextButton({ cache.clear(null) }, enabled = songs != null) { Text("Clear all cached songs") }
        }
        items(rows, key = { it.key.id }) { song ->
            val bytes = Formatter.formatShortFileSize(LocalContext.current, song.bytes)
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(song.title); Text(if (song.offline) "$bytes · shared" else bytes, style = MaterialTheme.typography.bodySmall) }
                TextButton({ cache.clear(song.key.id) }) { Text("Clear") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
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
