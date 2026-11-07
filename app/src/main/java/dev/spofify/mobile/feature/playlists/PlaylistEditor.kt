package dev.spofify.mobile.feature.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.spofify.mobile.ui.AutomaticListDemand
import dev.spofify.mobile.ui.AppendStatus
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** One deliberately established, read-only evaluation chain. A request is reserved before dispatch. */
internal class EditorPreviewState(
    private val api: DerivedPlaylistsApi,
    private val client: SpofifyClient,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val currentEditVersion: () -> Long = { 0L },
) {
    var page by mutableStateOf<DerivedEvaluationPage?>(null); private set
    var pending by mutableStateOf(false); private set
    var stale by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var identity by mutableStateOf<Any>(Any()); private set
    var message by mutableStateOf(""); private set
    private var rules: JsonObject? = null
    private var target: PersonalPlaylistSummary? = null
    private var sourceRevision = 0L
    private var sourceLikes = 0L
    private var editVersion = 0L
    private var session: Session? = null
    private var job: Job? = null
    private var failedCursor: String? = null
    private var failed = false
    private val cursors = mutableSetOf<String>()
    val canAppend get() = !pending && !stale && !failed && page?.nextCursor != null &&
        editVersion == currentEditVersion() && session === client.currentSession()

    fun checkInputs(value: JsonObject, derived: Boolean, saved: PersonalPlaylistSummary?, version: Long = 0L,
        revision: Long = client.personalRevision.value.playlists) {
        if (rules != null && (editVersion != version || rules != value || !derived || target != saved ||
                session !== client.currentSession() || sourceRevision != revision || sourceLikes != client.personalRevision.value.likes)) invalidate()
    }

    fun invalidate() {
        identity = Any(); job?.cancel(); job = null
        rules = null; pending = false; stale = true; failed = false; error = null
        message = "Preview changed. Refresh preview."
    }

    fun refresh(value: JsonObject, derived: Boolean, saved: PersonalPlaylistSummary?, version: Long = 0L) {
        if (pending || !derived || recipeValidationError(value) != null) return
        invalidate()
        rules = value; target = saved; sourceRevision = client.personalRevision.value.playlists
        sourceLikes = client.personalRevision.value.likes
        editVersion = version; session = client.currentSession()
        page = null; entries = emptyList(); cursors.clear()
        request(null)
    }

    fun more() { if (canAppend) request(page!!.nextCursor) }
    fun retry() { if (failed && !pending && !stale) request(failedCursor) }

    private fun request(cursor: String?) {
        val candidate = rules ?: return
        if (session !== client.currentSession() || editVersion != currentEditVersion() ||
            sourceRevision != client.personalRevision.value.playlists || sourceLikes != client.personalRevision.value.likes) { invalidate(); return }
        val capturedSession = session
        val capturedRevision = sourceRevision
        val capturedLikes = sourceLikes
        val capturedTarget = target
        val ticket = identity
        val before = page
        val prefix = entries
        pending = true; failed = false; error = null
        if (before == null) message = ""
        job = scope.launch {
            try {
                // The source may change after reservation but before this coroutine dispatches.
                // Do not send an obsolete cursor while waiting for the composition-time fence.
                if (identity !== ticket) return@launch
                if (capturedSession !== client.currentSession() || editVersion != currentEditVersion() ||
                    capturedRevision != client.personalRevision.value.playlists || capturedLikes != client.personalRevision.value.likes) { invalidate(); return@launch }
                val result = api.preview(candidate, capturedTarget?.id, capturedTarget?.revision, limit = 40, cursor = cursor)
                if (identity !== ticket) return@launch
                if (editVersion != currentEditVersion()) { invalidate(); return@launch }
                check(capturedSession === client.currentSession() && capturedRevision == client.personalRevision.value.playlists &&
                    capturedLikes == client.personalRevision.value.likes)
                check(result.limit == 40)
                if (before != null) check(result.revision == before.revision && result.fence == before.fence &&
                    result.total == before.total && result.availableTotal == before.availableTotal)
                check(result.nextCursor == null || (result.nextCursor != cursor && result.nextCursor !in cursors))
                val old = prefix.associateBy { it.occurrenceId }
                val fresh = result.items.filter { old[it.occurrenceId] == null }
                check(result.items.all { old[it.occurrenceId]?.let { previous -> previous == it } ?: true })
                check(result.items.zipWithNext().all { (a, b) -> a.position < b.position })
                // Cursor progress must extend the exact positional prefix, not merely move
                // forward: an increasing page can still silently skip an occurrence.
                check(fresh.isEmpty() || fresh.first().position == (prefix.lastOrNull()?.position ?: -1) + 1)
                check(fresh.zipWithNext().all { (a, b) -> b.position == a.position + 1 })
                check(fresh.isNotEmpty() || (before == null && result.total == 0L && result.nextCursor == null))
                val all = prefix + fresh
                check(all.size.toLong() <= result.total)
                check(if (result.nextCursor == null) all.size.toLong() == result.total else all.size.toLong() < result.total)
                cursor?.let { cursors.add(it) }
                // Envelope DTOs cap a single batch; retain that envelope and publish rows separately below.
                entries = all
                page = result
                stale = false
                message = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (identity !== ticket) return@launch
                val invalid = e is IllegalStateException || (e is ApiException && (e.status in 200..299 || e.status == 409 || e.status == 410))
                stale = invalid
                if (invalid) message = "Preview changed. Refresh preview."
                else {
                    // Initial failures also retry the exact captured definition/null cursor.
                    stale = false; failed = true; failedCursor = cursor
                    error = "Preview could not load."
                }
            } finally { if (identity === ticket) pending = false }
        }
    }

    var entries by mutableStateOf<List<DerivedEvaluationEntry>>(emptyList()); private set
}

/** In-memory only. Closing rules keeps the draft, never its preview authority. */
internal class PlaylistEditorDraft(summary: PersonalPlaylistSummary? = null, initial: JsonObject? = null) {
    var name by mutableStateOf(summary?.name.orEmpty())
    var description by mutableStateOf(summary?.description.orEmpty())
    var editVersion by mutableLongStateOf(0L); private set
    private var recipe by mutableStateOf(initial ?: blankRecipe())
    var rules: JsonObject
        get() = recipe
        set(value) { if (recipe != value) { editVersion++; recipe = value } }
    private var derivedMode by mutableStateOf(summary?.kind == PlaylistKind.DERIVED)
    var derived: Boolean
        get() = derivedMode
        set(value) { if (derivedMode != value) { editVersion++; derivedMode = value } }
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
    val evaluation = remember(client, target, draft) { EditorPreviewState(derived, client, scope) { draft.editVersion } }
    val listState = rememberLazyListState()
    val preview = evaluation.page
    val previewPending = evaluation.pending
    // Subscribe in composition, not only inside SideEffect: idle source changes must recompose.
    val playlistRevision = client.personalRevision.collectAsState().value.playlists
    val metadataError = playlistMetadataError(draft.name, draft.description)
    val rulesError = if (draft.derived) recipeValidationError(draft.rules) else null
    BackHandler { if (!saving) onBack() }
    DisposableEffect(evaluation) { onDispose { evaluation.invalidate() } }
    // Also fence programmatic/ABA edits, not only callbacks from this form.
    SideEffect {
        evaluation.checkInputs(draft.rules, draft.derived, target, draft.editVersion, playlistRevision)
    }
    LaunchedEffect(client, sourceRefresh) {
        try { sources = api.playlists(limit = 100).items.map { it.id to it.name }; sourceFailed = false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { sourceFailed = true }
    }
    fun invalidatePreview() {
        evaluation.invalidate()
    }
    AutomaticListDemand(listState, evaluation.identity,
        evaluation.entries.takeLast(3).map { it.occurrenceId },
        enabled = draft.derived && !saving && !draft.blocked && evaluation.canAppend,
        onLoad = evaluation::more)
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, enabled = !saving) { Text("Cancel") }
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
            } else Text(target.name)
            if (draft.derived) {
                if (sourceFailed) {
                    Text("Saved sources could not load.")
                    TextButton(onClick = { sourceRefresh++ }, enabled = !saving) { Text("Reload source choices") }
                }
                RecipeBuilder(draft.rules, { draft.rules = it; invalidatePreview() }, sources, client, enabled = !saving)
                rulesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(onClick = { evaluation.refresh(draft.rules, draft.derived, target, draft.editVersion) }, enabled = !saving && !previewPending && !draft.blocked && rulesError == null) { Text("Refresh preview") }
                    TextButton(onClick = { invalidatePreview() }, enabled = previewPending) { Text("Cancel preview") }
                }
                if (evaluation.message.isNotBlank()) Text(evaluation.message)
                if (previewPending && preview == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            draft.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (draft.blocked) {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            api.playlists(limit = 100)
                            draft.message = "Review saved playlists."
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { draft.message = "Could not check saved playlists. Try again." }
                        finally { saving = false }
                    }
                }) { Text("Check") }
                TextButton(enabled = !saving, onClick = onDiscard) { Text("Discard draft") }
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
                            conflict -> "Playlist changed. Check saved playlists."
                            uncertain -> "Save unconfirmed. Check saved playlists."
                            else -> "Could not save playlist. Check its name and rules."
                        }
                    } finally { saving = false; onMutationPendingChange(false) }
                }
            }) { Text(if (saving) "Saving…" else if (target == null) "Create playlist" else "Save rules") }
        }
        if (draft.derived) {
            items(evaluation.entries, key = { it.occurrenceId }) { entry ->
                Column { Text("${entry.position + 1}. ${entry.track.name}"); Text(entry.track.subtitle, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                AppendStatus(previewPending && preview != null, evaluation.error, {
                    if (!saving && !draft.blocked) evaluation.retry()
                }, retryLabel = "Retry preview")
            }
        }
    }
}
