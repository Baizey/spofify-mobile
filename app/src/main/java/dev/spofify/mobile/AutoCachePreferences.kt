package dev.spofify.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.playback.cache.AutoCacheSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class AutoCachePreference(val title: String) {
    MAX_SONGS("Maximum songs"), STORAGE("Storage"),
}

internal fun autoCacheSummary(preference: AutoCachePreference, settings: AutoCacheSettings): String =
    when (preference) {
        AutoCachePreference.MAX_SONGS -> if (settings.maxSongs == AutoCacheSettings.MIN_SONGS) "Minimum (2)" else settings.maxSongs.toString()
        AutoCachePreference.STORAGE -> settings.storageGb?.let { "$it GB" }
            ?: "Minimum · ${settings.minimumBytes / 1_000_000L} MB"
    }

/** Index the controls themselves, not a speculative cache route or download setting. */
internal fun matchingAutoCachePreferences(settings: AutoCacheSettings, query: String): List<AutoCachePreference> =
    AutoCachePreference.entries.filter { preference ->
        matchesSettingsQuery(query, "Automatic cache", preference.title, autoCacheSummary(preference, settings),
            when (preference) {
                AutoCachePreference.MAX_SONGS -> "songs tracks count limit"
                AutoCachePreference.STORAGE -> "space minimum GB size limit"
            })
    }

private val autoCacheSongPresets = listOf(AutoCacheSettings.MIN_SONGS, 10, 100)

internal fun autoCacheSongCount(text: String): Int? =
    text.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()?.takeIf { it >= AutoCacheSettings.MIN_SONGS }

/** Decimal GB, with no rounding, unlimited mode or implicit increase of a fixed target. */
internal fun autoCacheStorageError(maxSongs: Int, storageGb: String): String? {
    val minimum = AutoCacheSettings(maxSongs = maxSongs).minimumBytes
    val bytesPerGb = AutoCacheSettings.BYTES_PER_GB
    val minimumGb = (minimum + bytesPerGb - 1) / bytesPerGb
    val gb = storageGb.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
    return if (gb == null || gb.toLong() * bytesPerGb < minimum) {
        "Enter a whole number of GB (at least $minimumGb)."
    } else null
}

/**
 * Only dialog drafts live here. Committed values always come from the application cache owner.
 * The suspend callback must persist/apply atomically off the UI thread and return safe local copy
 * for capacity/free-space failures. Editing never reads media, starts work or changes playback.
 */
@Composable
internal fun AutoCachePreferences(
    settings: AutoCacheSettings,
    onSave: suspend (AutoCacheSettings) -> String?,
    preferences: List<AutoCachePreference> = AutoCachePreference.entries,
) {
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var songs by rememberSaveable { mutableIntStateOf(settings.maxSongs) }
    var customSongs by rememberSaveable { mutableStateOf(settings.maxSongs !in autoCacheSongPresets) }
    var songText by rememberSaveable { mutableStateOf(settings.maxSongs.toString()) }
    var minimum by rememberSaveable { mutableStateOf(settings.storageGb == null) }
    var gb by rememberSaveable { mutableStateOf(settings.storageGb?.toString().orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val save = rememberUpdatedState(onSave)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column {
        Text("Automatic cache", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() })
        preferences.forEach { preference ->
            SettingsPreferenceRow(preference.title, autoCacheSummary(preference, settings),
                if (preference == AutoCachePreference.MAX_SONGS) Icons.Default.MusicNote else Icons.Default.Storage,
                onClick = {
                    songs = settings.maxSongs
                    customSongs = settings.maxSongs !in autoCacheSongPresets
                    songText = settings.maxSongs.toString()
                    minimum = settings.storageGb == null
                    gb = settings.storageGb?.toString().orEmpty()
                    error = null
                    editor = preference.name
                })
        }
    }

    val active = editor?.let { AutoCachePreference.valueOf(it) } ?: return
    AlertDialog(
        modifier = Modifier.testTag("auto-cache-dialog"),
        onDismissRequest = { if (!saving) editor = null },
        title = { Text(active.title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.selectableGroup()) {
                    if (active == AutoCachePreference.MAX_SONGS) {
                        autoCacheSongPresets.forEach { count ->
                            AutoCacheChoice(if (count == AutoCacheSettings.MIN_SONGS) "Minimum (2)" else "$count",
                                !customSongs && songs == count, !saving, "auto-cache-songs-$count") {
                                songs = count; customSongs = false; error = null
                            }
                        }
                        AutoCacheChoice("Custom", customSongs, !saving, "auto-cache-songs-custom") {
                            customSongs = true; error = null
                        }
                    } else {
                        AutoCacheChoice("Minimum", minimum, !saving, "auto-cache-minimum") {
                            minimum = true; error = null
                        }
                        AutoCacheChoice("Fixed GB", !minimum, !saving, "auto-cache-fixed") {
                            minimum = false; error = null
                        }
                    }
                }
                if (active == AutoCachePreference.MAX_SONGS && customSongs) {
                    OutlinedTextField(value = songText, onValueChange = { songText = it; error = null },
                        label = { Text("Songs") }, singleLine = true, enabled = !saving,
                        isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("auto-cache-song-count"))
                }
                if (active == AutoCachePreference.STORAGE && !minimum) {
                    OutlinedTextField(value = gb, onValueChange = { gb = it; error = null },
                        label = { Text("Storage in GB") }, singleLine = true, enabled = !saving,
                        isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("auto-cache-gb"))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("auto-cache-error"))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag("auto-cache-save"),
                onClick = saveClick@{
                    if (saving) return@saveClick
                    focus.clearFocus()
                    keyboard?.hide()
                    // Preserve the other, latest owner-supplied field rather than a stale dialog snapshot.
                    val nextSongs = if (active == AutoCachePreference.MAX_SONGS) {
                        if (customSongs) autoCacheSongCount(songText) else songs
                    } else settings.maxSongs
                    if (nextSongs == null) {
                        error = "Enter a whole number of songs (at least 2)."
                        return@saveClick
                    }
                    val nextGb = if (active == AutoCachePreference.STORAGE) {
                        if (minimum) null else gb
                    } else settings.storageGb?.toString()
                    val validation = nextGb?.let { autoCacheStorageError(nextSongs, it) }
                    if (validation != null) {
                        error = validation
                    } else {
                        val candidate = AutoCacheSettings(nextSongs, nextGb?.toInt())
                        saving = true
                        scope.launch {
                            try {
                                error = save.value(candidate)
                                if (error == null) editor = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "Couldn’t save. Try again."
                            } finally {
                                saving = false
                            }
                        }
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = { editor = null }, enabled = !saving,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}

@Composable
private fun AutoCacheChoice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected,
        enabled = enabled, role = Role.RadioButton, onClick = onClick).testTag(tag)
        .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
