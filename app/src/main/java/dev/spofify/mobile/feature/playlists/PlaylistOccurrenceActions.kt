package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Generic track/album playback must not replace a signed derived occurrence selection. */
@Composable
internal fun PlaylistOccurrenceActions(
    client: SpofifyClient, track: LibraryItem, onDismiss: () -> Unit,
    onOpen: () -> Unit, onAdd: () -> Unit, onPersonalChange: () -> Unit,
    playbackEnabled: Boolean, contextualLabel: String, onPlayNow: () -> Unit,
    onContextual: (() -> Unit)?, onEnqueue: (() -> Unit)?, onPlayNext: (() -> Unit)?,
    onOpenItem: (LibraryItem) -> Unit,
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true,
    isCurrent: () -> Boolean = { true },
) {
    var liked by remember(client, track.id) { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var statusRefresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val actionsEnabled = !busy && isCurrent()
    fun perform(action: () -> Unit) { if (current()) action() }
    LaunchedEffect(client, track.id, statusRefresh) {
        busy = true
        try { liked = client.likeStatus(track.id); message = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { liked = null; message = "Like status could not load. Check it again explicitly." }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(track.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { perform(onPlayNow) }, enabled = playbackEnabled && actionsEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Play now") }
            onContextual?.let { action ->
                TextButton(onClick = { perform(action) }, enabled = playbackEnabled && actionsEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Queue High") }
            }
            onPlayNext?.let { action -> TextButton(onClick = { perform(action) }, enabled = playbackEnabled && actionsEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Play next") } }
            // Priority is explicit and independent of the output's play/pause state.
            onEnqueue?.let { action ->
                TextButton(onClick = { perform(action) }, enabled = playbackEnabled && actionsEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Queue Low") }
            }
            TextButton(onClick = { perform(onOpen) }, enabled = actionsEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open track details") }
            TextButton(onClick = { perform(onAdd) }, enabled = actionsEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add to playlist") }
            if (liked == null) TextButton(onClick = { perform { statusRefresh++ } }, enabled = actionsEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check like status") }
            else TextButton(enabled = actionsEnabled, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                if (!current()) return@TextButton
                val wanted = !liked!!
                busy = true
                scope.launch {
                    try {
                        client.setLiked(track.id, wanted)
                        liked = wanted; onPersonalChange(); message = null
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        liked = null
                        message = "The like response was not confirmed. Check saved status before another change; nothing was resubmitted."
                    } finally { busy = false }
                }
            }) { Text(if (liked == true) "Unlike song" else "Like song") }
            dev.spofify.mobile.feature.library.MusicCredits(track, { item -> perform { onOpenItem(item) } })
            onRemove?.let { action ->
                TextButton(onClick = { perform(action) }, enabled = removeEnabled && actionsEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove this occurrence") }
            }
            message?.let { Text(it) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") } })
}
