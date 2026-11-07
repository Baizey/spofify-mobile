package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.spofify.mobile.core.PersonalPlaylistSummary

/** A finite picker body shared by the production modal and controlled layout checks. */
internal data class AddPlaylistUi(
    val trackName: String,
    val trackCredit: String = "",
    val targets: List<PersonalPlaylistSummary> = emptyList(),
    val selectedId: String? = null,
    val query: String = "",
    val searchable: Boolean = false,
    val searchIncomplete: Boolean = false,
    val canSearchMore: Boolean = false,
    val hiddenSelection: String? = null,
    val creating: Boolean = false,
    val name: String = "",
    val description: String = "",
    val busy: Boolean = false,
    val mutating: Boolean = false,
    val canAdd: Boolean = false,
    val canSelect: Boolean = false,
    val canCreate: Boolean = false,
    val stale: Boolean = false,
    val message: String = "",
    val targetError: String? = null,
    val blocked: Boolean = false,
    val reviewed: List<PersonalPlaylistSummary> = emptyList(),
    val reviewCount: Int? = null,
    val reviewError: String? = null,
)

internal class AddPlaylistActions(
    val select: (String) -> Unit = {},
    val query: (String) -> Unit = {},
    val searchMore: () -> Unit = {},
    val name: (String) -> Unit = {},
    val description: (String) -> Unit = {},
    val beginCreate: () -> Unit = {},
    val cancelCreate: () -> Unit = {},
    val create: () -> Unit = {},
    val add: () -> Unit = {},
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    val review: () -> Unit = {},
    val retryReview: () -> Unit = {},
    val dismiss: () -> Unit = {},
)

@Composable
internal fun AddToPlaylistContent(
    ui: AddPlaylistUi,
    actions: AddPlaylistActions,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(if (ui.creating && !ui.blocked) "New playlist" else "Add to playlist",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().selectableGroup(),
        ) {
            item(key = "identity") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(ui.trackName, style = MaterialTheme.typography.titleMedium)
                    if (ui.trackCredit.isNotBlank()) Text(ui.trackCredit,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.message.isNotEmpty()) item(key = "message") {
                Text(ui.message, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite })
            }
            when {
                ui.blocked -> {
                    if (ui.creating && ui.name.isNotEmpty()) item(key = "draft") {
                        Text(ui.name, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    }
                    items(ui.reviewed, key = { "review-${it.id}" }) { target ->
                        PlaylistIdentity(target)
                    }
                    ui.reviewCount?.let { count -> item(key = "review-count") {
                        Text("$count saved ${if (count == 1) "copy" else "copies"}",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    } }
                    if (ui.reviewError != null) item(key = "review-error") {
                        RecoveryRow(ui.reviewError, "Retry", actions.retryReview, !ui.busy)
                    }
                }
                ui.creating -> {
                    item(key = "form") {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(ui.name, actions.name, label = { Text("Name") },
                                singleLine = true, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(ui.description, actions.description,
                                label = { Text("Description (optional)") }, maxLines = 4,
                                enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                else -> {
                    if (ui.searchable) item(key = "search") {
                        OutlinedTextField(ui.query, actions.query, label = { Text("Search playlists") },
                            singleLine = true, enabled = !ui.mutating,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                    }
                    if (ui.canCreate) item(key = "new") {
                        TextButton(onClick = actions.beginCreate, enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("New playlist") }
                    }
                    items(ui.targets, key = { it.id }) { target ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .selectable(selected = ui.selectedId == target.id, enabled = ui.canSelect,
                                role = Role.RadioButton, onClick = { actions.select(target.id) })
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RadioButton(selected = ui.selectedId == target.id, onClick = null, enabled = ui.canSelect)
                            Column(Modifier.weight(1f)) {
                                Text(target.name, style = MaterialTheme.typography.titleMedium)
                                target.trackCount?.let { Text(songCount(it), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    if (ui.searchIncomplete && !ui.stale && ui.targetError == null) item(key = "search-more") {
                        RecoveryRow("Search incomplete", "Search more", actions.searchMore, ui.canSearchMore)
                    }
                    if (ui.stale) item(key = "refresh") {
                        TextButton(actions.refresh, enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh playlists") }
                    }
                    if (ui.targetError != null) item(key = "target-error") {
                        RecoveryRow(ui.targetError, "Retry", actions.retry, !ui.busy)
                    }
                    if (ui.targets.isEmpty() && !ui.busy && ui.targetError == null && !ui.stale && !ui.searchIncomplete) item(key = "empty") {
                        Text(if (ui.query.isBlank()) "No playlists" else "No matches", modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                    }
                }
            }
        }
        if (ui.hiddenSelection != null && !ui.creating && !ui.blocked) {
            Text(ui.hiddenSelection, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                ui.blocked -> {
                    TextButton(actions.dismiss, enabled = !ui.mutating,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Close") }
                    Button(actions.review, enabled = !ui.busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Review") }
                }
                ui.creating -> {
                    TextButton(actions.cancelCreate, enabled = !ui.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                    Button(actions.create, enabled = !ui.busy && ui.name.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Create") }
                }
                else -> Button(actions.add, enabled = ui.canAdd,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add") }
            }
        }
    }
}

@Composable
private fun PlaylistIdentity(target: PersonalPlaylistSummary) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(target.name, style = MaterialTheme.typography.titleMedium)
        target.trackCount?.let { Text(songCount(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RecoveryRow(message: String, label: String, retry: () -> Unit, enabled: Boolean) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(message)
        TextButton(retry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    }
}

private fun songCount(count: Long) = "$count ${if (count == 1L) "song" else "songs"}"
