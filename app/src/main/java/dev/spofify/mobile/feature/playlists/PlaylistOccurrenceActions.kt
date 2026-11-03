package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
) {
    var liked by remember(client, track.id) { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var statusRefresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(client, track.id, statusRefresh) {
        busy = true
        try { liked = client.likeStatus(track.id); message = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { liked = null; message = "Like status could not load. Check it again explicitly." }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(track.name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Use the playlist row’s Play button to select this exact occurrence. Generic track playback cannot select a signed rules occurrence.")
            TextButton(onClick = onOpen, enabled = !busy) { Text("Open track details") }
            TextButton(onClick = onAdd, enabled = !busy) { Text("Add to playlist") }
            if (liked == null) TextButton(onClick = { statusRefresh++ }, enabled = !busy) { Text("Check like status") }
            else TextButton(enabled = !busy, onClick = {
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
            message?.let { Text(it) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Done") } })
}
