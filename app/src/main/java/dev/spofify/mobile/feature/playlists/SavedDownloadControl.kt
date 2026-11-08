package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Presentation only. A completed manifest is not evidence that its audio is downloaded. */
internal data class SavedDownloadPresentation(
    val phase: SavedDownloadPhase,
    val ready: Int = 0,
    val total: Int = 0,
    val transferredBytes: Long = 0,
    val expectedBytes: Long? = null,
    val failure: String? = null,
    val updating: Boolean = false,
) {
    init {
        require(ready >= 0 && total >= ready && transferredBytes >= 0)
        require(expectedBytes == null || expectedBytes >= transferredBytes)
    }
}

internal enum class SavedDownloadPhase { NOT_SAVED, QUEUED, DOWNLOADING, PARTIAL, COMPLETE, FAILED, PAUSED }

/** Compact explicit retention controls; callbacks never select an output or prepare audio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedDownloadControl(
    state: SavedDownloadPresentation,
    onSave: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onUnsave: () -> Unit,
    modifier: Modifier = Modifier,
    networkEnabled: Boolean = true,
    actionsEnabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = when (state.phase) {
                SavedDownloadPhase.NOT_SAVED -> "Save"
                SavedDownloadPhase.QUEUED, SavedDownloadPhase.DOWNLOADING -> "Cancel"
                SavedDownloadPhase.COMPLETE -> "Update"
                SavedDownloadPhase.PARTIAL, SavedDownloadPhase.FAILED -> "Retry"
                SavedDownloadPhase.PAUSED -> "Resume"
            }
            TextButton(onClick = when (state.phase) {
                SavedDownloadPhase.NOT_SAVED -> onSave
                SavedDownloadPhase.QUEUED, SavedDownloadPhase.DOWNLOADING -> onCancel
                else -> onUpdate
            }, enabled = actionsEnabled && (state.phase in setOf(SavedDownloadPhase.QUEUED, SavedDownloadPhase.DOWNLOADING) || networkEnabled),
                modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            if (state.phase != SavedDownloadPhase.NOT_SAVED) {
                TextButton(onClick = onUnsave, enabled = actionsEnabled,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Unsave") }
            }
        }
        val status = when (state.phase) {
            SavedDownloadPhase.NOT_SAVED -> null
            SavedDownloadPhase.QUEUED -> "Waiting to download · ${state.ready}/${state.total} ready"
            SavedDownloadPhase.DOWNLOADING -> if (state.updating) "Updating · ${state.ready}/${state.total} available"
                else if (state.total == 0) "Saving…" else "Downloading · ${state.ready}/${state.total} ready"
            SavedDownloadPhase.PARTIAL -> "${state.ready}/${state.total} downloaded" + if (state.updating) " · Update incomplete" else ""
            SavedDownloadPhase.COMPLETE -> "Downloaded · ${state.ready}/${state.total} ready"
            SavedDownloadPhase.FAILED -> "${state.ready}/${state.total} downloaded · Failed"
            SavedDownloadPhase.PAUSED -> "${state.ready}/${state.total} downloaded · Paused"
        }
        status?.let { Text(it + if (state.transferredBytes > 0) " · ${savedByteLabel(state.transferredBytes)} transferred" else "",
            style = MaterialTheme.typography.bodySmall) }
        if (state.phase == SavedDownloadPhase.DOWNLOADING) {
            val expected = state.expectedBytes
            if (expected != null && expected > 0L) {
                LinearProgressIndicator(progress = { (state.transferredBytes.toDouble() / expected).toFloat() },
                    modifier = Modifier.fillMaxWidth())
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.failure?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error) }
    }
}
