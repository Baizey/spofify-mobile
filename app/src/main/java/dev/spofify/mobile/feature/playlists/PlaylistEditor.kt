package dev.spofify.mobile.feature.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** In-memory only. Closing rules keeps the draft, never its preview authority. */
internal class PlaylistEditorDraft(summary: PersonalPlaylistSummary? = null, initial: JsonObject? = null) {
    var name by mutableStateOf(summary?.name.orEmpty())
    var description by mutableStateOf(summary?.description.orEmpty())
    var rules by mutableStateOf(initial ?: blankRecipe())
    var derived by mutableStateOf(summary?.kind == PlaylistKind.DERIVED)
    var blocked by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
}

@Composable
internal fun PlaylistEditor(
    client: SpofifyClient, target: PersonalPlaylistSummary?, draft: PlaylistEditorDraft,
    onBack: () -> Unit, onSaved: (PersonalPlaylistSummary) -> Unit, modifier: Modifier = Modifier,
    onDiscard: () -> Unit = onBack,
    onMutationPendingChange: (Boolean) -> Unit = {},
) {
    val api = remember(client) { PersonalCollectionsApi(client) }
    val derived = remember(client) { DerivedPlaylistsApi(client) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    ReportPlaylistMutationPending(saving, onMutationPendingChange)
    var sources by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var sourceFailed by remember { mutableStateOf(false) }
    var sourceRefresh by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<DerivedEvaluationPage?>(null) }
    var previewStale by remember { mutableStateOf(true) }
    var previewPending by remember { mutableStateOf(false) }
    var previewMessage by remember { mutableStateOf("Not evaluated. Preview is read-only and never plays music.") }
    var cursors by remember { mutableStateOf(listOf<String?>(null)) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val generation = remember { PlaylistRequestGeneration() }
    var previewSource by remember { mutableStateOf<PlaylistSourceStamp?>(null) }
    val personal = client.personalRevision.collectAsState().value
    val metadataError = playlistMetadataError(draft.name, draft.description)
    val rulesError = if (draft.derived) recipeValidationError(draft.rules) else null
    BackHandler { if (!saving) onBack() }
    DisposableEffect(Unit) { onDispose { generation.invalidate() } }
    LaunchedEffect(client, sourceRefresh) {
        try { sources = api.playlists(limit = 100).items.map { it.id to it.name }; sourceFailed = false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { sourceFailed = true }
    }
    fun invalidatePreview() {
        generation.invalidate(); job?.cancel(); job = null; previewSource = null
        previewPending = false; previewStale = true; cursors = listOf(null); pageIndex = 0
        previewMessage = "Not current — rules changed. Your draft is retained. Refresh preview explicitly."
    }
    LaunchedEffect(personal.likes, personal.playlists) {
        // Observe confirmed rule inputs without automatically evaluating an unsaved draft.
        if (previewSource?.isCurrent() == false) invalidatePreview()
    }
    fun loadPreview(index: Int) {
        if (previewPending || saving || draft.blocked || rulesError != null) return
        if (index > 0 && previewSource?.isCurrent() != true) { invalidatePreview(); return }
        val candidate = draft.rules
        val source = PlaylistSourceStamp(client)
        previewSource = source
        val ticket = generation.invalidate()
        previewPending = true; previewStale = true
        previewMessage = "Evaluating the complete result… Previous rows are not current."
        val cursor = if (index == 0) null else cursors[index]
        job = scope.launch {
            try {
                if (!source.isCurrent()) return@launch
                val result = derived.preview(candidate, target?.id, target?.revision, limit = 40, cursor = cursor)
                if (generation.accepts(ticket) && source.isCurrent()) {
                    preview = result; previewStale = false; pageIndex = index
                    cursors = cursors.take(index + 1) + listOfNotNull(result.nextCursor)
                    previewMessage = "${result.total} matches · ${result.availableTotal} with recorded audio. Complete result evaluated; ${result.items.size} shown. Save to use this playlist. Saved contents update automatically."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation.accepts(ticket) && source.isCurrent()) {
                    previewStale = true
                    previewMessage = if (e is ApiException && e.status == 409) "Inputs changed or this preview expired. Draft retained; refresh preview explicitly."
                    else "Preview could not finish. No partial result is current. Your draft is retained; refresh explicitly."
                }
            } finally { if (generation.accepts(ticket)) previewPending = false }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, enabled = !saving) { Text("Back — keep draft") }
            Text(if (target == null) "Create playlist" else "Edit rules", style = MaterialTheme.typography.headlineMedium)
            if (target == null) {
                OutlinedTextField(draft.name, { draft.name = it }, label = { Text("Name") }, enabled = !saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.description, { draft.description = it }, label = { Text("Description (optional)") }, enabled = !saving, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                Row {
                    FilterChip(selected = !draft.derived, enabled = !saving, onClick = { draft.derived = false; invalidatePreview() }, label = { Text("Manual") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = draft.derived, enabled = !saving, onClick = { draft.derived = true; invalidatePreview() }, label = { Text("Live rules") })
                }
                metadataError?.let { Text(it) }
            } else Text("${target.name} · revision ${target.revision}. Rule changes do not alter the current playback queue.")
            if (draft.derived) {
                Text("Rules stay live as saved inputs change. Choosing first/last songs from a source happens before conditions; final playlist size happens afterwards.")
                if (sourceFailed) {
                    Text("Saved source collections could not load. Existing rules are retained.")
                    TextButton(onClick = { sourceRefresh++ }, enabled = !saving) { Text("Reload source choices") }
                }
                RecipeBuilder(draft.rules, { draft.rules = it; invalidatePreview() }, sources, client, enabled = !saving)
                rulesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Local checks are structural only. The server checks ownership, saved references, cycles and graph limits on preview and save.", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { cursors = listOf(null); pageIndex = 0; loadPreview(0) }, enabled = !saving && !previewPending && !draft.blocked && rulesError == null) { Text("Refresh preview") }
                    TextButton(onClick = { invalidatePreview(); previewMessage = "Preview cancelled. Draft retained; previous rows are stale." }, enabled = previewPending) { Text("Cancel preview") }
                }
                Text(previewMessage)
                if (previewPending) LinearProgressIndicator(Modifier.fillMaxWidth())
            } else Text("Manual playlists keep an editable ordered list. Duplicate tracks remain separate occurrences.")
            draft.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (draft.blocked) {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val saved = api.playlists(limit = 100)
                            draft.message = "Saved playlists: ${saved.items.joinToString { it.name }}. A matching name cannot prove whether a previous request committed. Close and review before making another change."
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { draft.message = "Verification could not load. No change was resubmitted. Try verification again explicitly." }
                        finally { saving = false }
                    }
                }) { Text("Verify saved playlists") }
                TextButton(enabled = !saving, onClick = onDiscard) { Text("Discard draft and return to saved playlists") }
            } else Button(enabled = !saving && metadataError == null && rulesError == null, onClick = {
                invalidatePreview(); saving = true; onMutationPendingChange(true)
                scope.launch {
                    try {
                        val result = if (target != null) derived.updateDefinition(target.id, draft.rules, target.revision)
                        else if (draft.derived) derived.create(draft.name.trim(), draft.description, draft.rules)
                        else api.createPlaylist(draft.name.trim(), draft.description)
                        onSaved(result)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val conflict = e is ApiException && e.status == 409
                        val uncertain = playlistWriteUncertain(e)
                        draft.blocked = conflict || uncertain
                        draft.message = when {
                            conflict -> "Playlist changed elsewhere. Draft retained. Verify saved playlists and review the current rules before trying again; nothing was resubmitted."
                            uncertain -> "The save response was lost. A playlist or rule change may already be saved. Verify before another write; nothing was resubmitted."
                            else -> "The playlist could not be saved. Check its name, rules and references; your draft is retained."
                        }
                    } finally { saving = false; onMutationPendingChange(false) }
                }
            }) { Text(if (saving) "Saving…" else if (target == null) "Create playlist" else "Save rules") }
        }
        if (draft.derived) {
            items(preview?.items.orEmpty(), key = { it.occurrenceId }) { entry ->
                Column { Text("${entry.position + 1}. ${entry.track.name}"); Text(entry.track.subtitle, style = MaterialTheme.typography.bodySmall) }
            }
            item { Row {
                TextButton(enabled = !saving && !previewPending && !draft.blocked && !previewStale && pageIndex > 0, onClick = { loadPreview(pageIndex - 1) }) { Text("Previous preview") }
                TextButton(enabled = !saving && !previewPending && !draft.blocked && !previewStale && preview?.nextCursor != null, onClick = { loadPreview(pageIndex + 1) }) { Text("Next preview") }
            } }
        }
    }
}
