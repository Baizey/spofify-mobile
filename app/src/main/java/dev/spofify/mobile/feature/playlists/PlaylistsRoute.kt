package dev.spofify.mobile.feature.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.spofify.mobile.feature.library.CatalogPlaybackFeedback
import dev.spofify.mobile.feature.library.compactMusicSubtitle
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import dev.spofify.mobile.ui.ContextualSwipeRow
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.PlaybackRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Capture at the Play tap, before launching/fetching; a late queue is never a fresh intent. */
internal class PlaylistPlaybackRequest(private val generation: PlaylistRequestGeneration) {
    private val ticket = generation.invalidate()
    private val playbackEpoch = PlaybackRequests.epoch

    /** Success/error publication and queue delivery are synchronous on the Compose main coroutine. */
    fun deliver(action: () -> Unit) {
        if (generation.accepts(ticket) && playbackEpoch == PlaybackRequests.epoch) action()
    }

    /** Cancellation must still clear busy, but never clear a newer request's busy state. */
    fun finish(action: () -> Unit) {
        if (generation.accepts(ticket)) action()
    }
}

private data class PlaylistRowSelection(val track: LibraryItem, val selectionToken: String? = null,
    val entryId: String? = null, val interactionKey: PlaybackInteractionKey?, val isCurrent: () -> Boolean)

/** Account-local navigation. Browsing never prepares or starts a queue. */
@Composable
fun PlaylistsRoute(
    client: SpofifyClient,
    onOpenTrack: (LibraryItem) -> Unit,
    onPlayQueue: (PlaybackQueue, String?, String?) -> Unit,
    onShowTrackActions: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    initialPlaylistId: String? = null,
    onMutationPendingChange: (Boolean) -> Unit = {},
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(),
    onPreparedAction: ((PlaybackQueue, String?, String?, PlaybackListAction, PlaybackInteractionKey?, () -> Boolean) -> Unit)? = null,
    playbackEntryId: String? = null,
    playbackContext: LibraryItem? = null,
) {
    key(client, client.currentSession()?.accountId) {
        var selectedId by remember { mutableStateOf(initialPlaylistId) }
        LaunchedEffect(initialPlaylistId) { selectedId = initialPlaylistId }
        var creating by remember { mutableStateOf(false) }
        var newDraft by remember { mutableStateOf(PlaylistEditorDraft()) }
        var refresh by remember { mutableIntStateOf(0) }
        var offset by remember { mutableIntStateOf(0) }
        var page by remember { mutableStateOf<PersonalPlaylistPage?>(null) }
        var loading by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val api = remember(client) { PersonalCollectionsApi(client) }
        val listState = rememberLazyListState()
        LaunchedEffect(client, offset, refresh) {
            loading = true
            try { page = api.playlists(limit = 40, offset = offset); message = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Playlists could not load. Refresh explicitly to try again." }
            finally { loading = false }
        }
        when {
            creating -> PlaylistEditor(client, null, newDraft, onBack = { creating = false }, onSaved = {
                creating = false; newDraft = PlaylistEditorDraft(); selectedId = it.id; refresh++
            }, modifier = modifier, onDiscard = { newDraft = PlaylistEditorDraft(); creating = false; refresh++ },
                onMutationPendingChange = onMutationPendingChange)
            selectedId != null -> PlaylistDetail(client, selectedId!!, onOpenTrack, onPlayQueue, onShowTrackActions,
                onBack = { selectedId = null; refresh++ }, modifier = modifier, onMutationPendingChange = onMutationPendingChange,
                playback = playback, onPreparedAction = onPreparedAction,
                playbackEntryId = playbackEntryId, playbackContext = playbackContext)
            else -> LazyColumn(modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row {
                        Button(onClick = { creating = true }) { Text("Create playlist") }
                        TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                items(page?.items.orEmpty(), key = { it.id }) { playlist ->
                    PlaylistCard(playlist, onClick = { selectedId = playlist.id })
                }
                if (offset > 0 || offset + (page?.items?.size ?: 0) < (page?.total ?: 0)) item {
                    Row {
                        TextButton(onClick = { offset = (offset - 40).coerceAtLeast(0) }, enabled = offset > 0 && !loading) { Text("Previous") }
                        TextButton(onClick = { offset += 40 }, enabled = !loading && offset + (page?.items?.size ?: 0) < (page?.total ?: 0)) { Text("Next playlists") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: PersonalPlaylistSummary, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Stable local fallback; no unauthenticated or arbitrary artwork URLs.
            Text(if (playlist.kind == PlaylistKind.DERIVED) "♫\nRules" else "♫", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                Text(if (playlist.kind == PlaylistKind.DERIVED) "Rules · not evaluated" else "${playlist.trackCount} tracks · ${playlist.availableCount} with audio")
                if (playlist.description.isNotBlank()) Text(playlist.description, maxLines = 3)
            }
        }
    }
}

private fun playlistFailure(error: Exception, mutation: Boolean = false): String = when {
    error is ApiException && error.code == "SOURCE_REFERENCED" -> "Another recipe references this playlist. Remove those references explicitly before deleting it."
    error is ApiException && error.code == "PLAYLIST_KIND_MISMATCH" -> "This operation is not supported by this playlist type. Refresh and review the playlist."
    error is ApiException && error.code == "COPY_LIMIT_EXCEEDED" -> "The complete result exceeds 1,000 occurrences. No editable copy was created."
    error is ApiException && error.code == "RECIPE_CYCLE" -> "These collection references form a cycle. Choose different sources or membership conditions."
    error is ApiException && error.code == "RECIPE_LIMIT_EXCEEDED" -> "The recipe exceeds supported graph or condition limits. Simplify it."
    error is ApiException && error.code == "OCCURRENCE_UNAVAILABLE" -> "This occurrence has no recorded audio. No queue was replaced."
    error is ApiException && error.status == 409 -> "The playlist or its inputs changed. Refresh and review before trying again. Nothing was resubmitted."
    error is ApiException && error.status == 429 -> "Evaluation is busy. Refresh explicitly in a moment."
    error is ApiException && error.status == 422 -> "The request could not be accepted. Check the rules and limits. Copies require at most 1,000 complete occurrences; referenced playlists cannot be deleted."
    error is ApiException && error.status == 404 -> "This playlist or a referenced collection is unavailable."
    mutation && playlistWriteUncertain(error) -> "The response was lost. This change may already be saved. Verify saved playlists before making another change; nothing was resubmitted."
    else -> "The request could not finish. Refresh explicitly; no partial result is current."
}

@Composable
private fun PlaylistDetail(
    client: SpofifyClient, id: String, onOpenTrack: (LibraryItem) -> Unit,
    onPlayQueue: (PlaybackQueue, String?, String?) -> Unit, onShowTrackActions: (LibraryItem) -> Unit,
    onBack: () -> Unit, modifier: Modifier, onMutationPendingChange: (Boolean) -> Unit,
    playback: CatalogPlaybackFeedback,
    onPreparedAction: ((PlaybackQueue, String?, String?, PlaybackListAction, PlaybackInteractionKey?, () -> Boolean) -> Unit)?,
    playbackEntryId: String?, playbackContext: LibraryItem?,
) {
    val api = remember(client) { PersonalCollectionsApi(client) }
    val derived = remember(client) { DerivedPlaylistsApi(client) }
    val scope = rememberCoroutineScope()
    val generation = remember(id) { PlaylistRequestGeneration() }
    var summary by remember(id) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var entries by remember(id) { mutableStateOf<PersonalEntriesPage?>(null) }
    var evaluation by remember(id) { mutableStateOf<DerivedEvaluationPage?>(null) }
    var cursorStack by remember(id) { mutableStateOf(listOf<String?>(null)) }
    var pageIndex by remember(id) { mutableIntStateOf(0) }
    var offset by remember(id) { mutableIntStateOf(0) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    var busy by remember(id) { mutableStateOf(false) }
    var mutating by remember(id) { mutableStateOf(false) }
    ReportPlaylistMutationPending(mutating, onMutationPendingChange)
    var stale by remember(id) { mutableStateOf(true) }
    var blocked by remember(id) { mutableStateOf(false) }
    var message by remember(id) { mutableStateOf<String?>(null) }
    var metadata by remember(id) { mutableStateOf(false) }
    var deleting by remember(id) { mutableStateOf(false) }
    var removing by remember(id) { mutableStateOf<String?>(null) }
    var occurrenceActions by remember(id) { mutableStateOf<PlaylistRowSelection?>(null) }
    val latestInteractionKey by rememberUpdatedState(playback.interactionKey)
    var addingTrack by remember(id) { mutableStateOf<LibraryItem?>(null) }
    var editing by remember(id) { mutableStateOf(false) }
    var copying by remember(id) { mutableStateOf(false) }
    var copyNeedsReview by remember(id) { mutableStateOf(false) }
    var copyReviewed by remember(id) { mutableStateOf(false) }
    var copyReviewMessage by remember(id) { mutableStateOf<String?>(null) }
    var editorDraft by remember(id) { mutableStateOf<PlaylistEditorDraft?>(null) }
    var editorTarget by remember(id) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var snapshot by remember(id) { mutableStateOf<ManualPlaylistSnapshot?>(null) }
    var ordered by remember(id) { mutableStateOf<List<PersonalPlaylistEntry>>(emptyList()) }
    val listState = rememberLazyListState()

    fun refreshAll() { generation.invalidate(); stale = true; snapshot = null; offset = 0; pageIndex = 0; cursorStack = listOf(null); refresh++ }
    fun write(onFailure: ((Exception) -> Unit)? = null, action: suspend () -> Unit) {
        if (busy || blocked) return
        generation.invalidate(); busy = true; mutating = true; stale = true; onMutationPendingChange(true)
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { blocked = true; message = playlistFailure(e, true); onFailure?.invoke(e) }
            finally { busy = false; mutating = false; onMutationPendingChange(false) }
        }
    }
    LaunchedEffect(id, offset, pageIndex, refresh) {
        val ticket = generation.invalidate()
        busy = true; stale = true
        try {
            val saved = api.playlist(id)
            if (generation.accepts(ticket)) summary = saved
            val manual = if (saved.kind == PlaylistKind.MANUAL) api.entries(id, limit = 40, offset = offset) else null
            val result = if (saved.kind == PlaylistKind.DERIVED) derived.evaluate(id, limit = 40, cursor = cursorStack[pageIndex]) else null
            if (result != null && result.revision != saved.revision) throw ApiException(409, "Refresh the changed playlist")
            if (generation.accepts(ticket)) {
                summary = saved; entries = manual; evaluation = result; stale = false; blocked = false; message = null
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation.accepts(ticket)) { message = playlistFailure(e); stale = true } }
        finally { if (generation.accepts(ticket)) busy = false }
    }
    BackHandler {
        if (!busy) when { editing -> editing = false; copying -> copying = false; snapshot != null -> snapshot = null; else -> onBack() }
    }
    val saved = summary
    addingTrack?.let { track -> AddToPlaylistSheet(client, track, onDismiss = {
        addingTrack = null; stale = true; generation.invalidate()
    }) }
    if (editing && saved != null && editorDraft != null) {
        PlaylistEditor(client, editorTarget ?: saved, editorDraft!!, onBack = { editing = false; stale = true; generation.invalidate() }, onSaved = {
            editing = false; editorDraft = null; editorTarget = null; refreshAll()
        }, modifier = modifier, onDiscard = { editing = false; editorDraft = null; editorTarget = null; refreshAll() },
            onMutationPendingChange = onMutationPendingChange)
        return
    }
    if (metadata && saved != null) PlaylistMetadataDialog("Edit playlist", saved.name, saved.description,
        busy = busy, blocked = blocked, message = message, onDismiss = { if (!busy) metadata = false },
        onVerify = { metadata = false; refreshAll() }, onSave = { name, description -> write {
            summary = api.updatePlaylist(id, name, description, saved.revision); metadata = false; refreshAll()
        } })
    if (copying && saved != null) PlaylistMetadataDialog("Make editable copy", "${saved.name} (copy)", saved.description,
        busy = busy, blocked = blocked || copyNeedsReview, message = copyReviewMessage ?: message,
        explanation = "Copies the complete result, including unavailable tracks and duplicates (maximum 1,000), not just this page. Original rules stay unchanged.",
        onDismiss = { if (!busy) copying = false }, onVerify = {
            busy = true
            scope.launch {
                try {
                    val targets = api.playlists(kind = PlaylistKind.MANUAL, limit = 100)
                    copyReviewMessage = "Saved manual playlists: ${targets.items.joinToString { it.name }}. A matching name cannot prove whether the last copy committed. Close, review saved playlists, then explicitly refresh the source before deciding on another copy."
                    copyReviewed = true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { copyReviewMessage = "Saved copies could not load. No copy was resubmitted; verify again explicitly." }
                finally { busy = false }
            }
        }, onSave = { name, description ->
            val result = evaluation
            if (result != null && !stale && !copyNeedsReview) write(onFailure = { error ->
                if (playlistWriteUncertain(error)) { copyNeedsReview = true; copyReviewed = false }
            }) {
                val copy = derived.copy(id, name, description, saved.revision, result.fence)
                copying = false; message = "Created editable playlist: ${copy.name}. Original rules are unchanged."; refreshAll()
            }
        })
    if (deleting && saved != null) AlertDialog(onDismissRequest = { if (!busy) deleting = false }, title = { Text("Delete playlist?") },
        text = { Text("Delete “${saved.name}”? This removes the playlist, not music files. Other recipes may prevent deletion.\n${message.orEmpty()}") },
        confirmButton = { TextButton(enabled = !busy && !blocked, onClick = { write { api.deletePlaylist(id, saved.revision); onBack() } }) { Text("Delete playlist") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleting = false }) { Text("Cancel") } })
    if (removing != null && saved != null) AlertDialog(onDismissRequest = { if (!busy) removing = null }, title = { Text("Remove occurrence?") },
        text = { Text("Only this occurrence is removed. Other copies of the track and music files are unchanged.\n${message.orEmpty()}") },
        confirmButton = { TextButton(enabled = !busy && !blocked, onClick = { val entry = removing!!; write {
            summary = api.removeEntry(id, entry, saved.revision); removing = null; refreshAll()
        } }) { Text("Remove") } }, dismissButton = { TextButton(enabled = !busy, onClick = { removing = null }) { Text("Cancel") } })

    fun play(selectionToken: String? = null, trackId: String? = null, entryId: String? = null,
        action: PlaybackListAction? = null, expectedKey: PlaybackInteractionKey? = null, isCurrent: () -> Boolean = { true }) {
        if (busy || stale || saved == null || !isCurrent()) return
        if (action != null && (onPreparedAction == null || expectedKey == null || latestInteractionKey !== expectedKey)) return
        val result = evaluation
        val request = PlaylistPlaybackRequest(generation); busy = true
        scope.launch {
            try {
                if (saved.kind == PlaylistKind.DERIVED && result != null) {
                    val queue = derived.playback(id, saved.revision, result.fence, selectionToken)
                    request.deliver {
                        if (!isCurrent()) return@deliver
                        message = when (queue.notice) { "CLICKED_OCCURRENCE_ONLY" -> "Only the selected occurrence was prepared because it is beyond the first 1,000 available occurrences."
                            "PLAYBACK_LIMIT" -> "Playback is limited to the first 1,000 available occurrences."; else -> null }
                        if (action == null) onPlayQueue(queue.queue, null, queue.startOccurrenceId)
                        else if (latestInteractionKey === expectedKey && isCurrent()) onPreparedAction?.invoke(queue.queue, null,
                            queue.startOccurrenceId, action, expectedKey, isCurrent)
                    }
                } else if (saved.kind == PlaylistKind.MANUAL) {
                    val queue = api.playback(id)
                    request.deliver {
                        if (!isCurrent()) return@deliver
                        if (entryId != null && queue.items.none { it.entryId == entryId }) message = "This occurrence is outside the prepared playback limit. No queue was replaced."
                        else if (action == null) onPlayQueue(queue.queue, trackId, entryId)
                        else if (latestInteractionKey === expectedKey && isCurrent()) onPreparedAction?.invoke(queue.queue, trackId, entryId, action, expectedKey, isCurrent)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { request.deliver { if (isCurrent()) { message = playlistFailure(e); stale = true } } }
            finally { request.finish { busy = false } }
        }
    }
    occurrenceActions?.let { selection ->
        val playable = !busy && !stale && selection.isCurrent() && selection.track.playable && saved?.capabilities?.play == true &&
            (saved?.kind != PlaylistKind.DERIVED || selection.selectionToken != null)
        fun perform(action: PlaybackListAction? = null) {
            occurrenceActions = null
            play(selectionToken = selection.selectionToken, trackId = selection.track.id,
                entryId = selection.entryId, action = action, expectedKey = selection.interactionKey, isCurrent = selection.isCurrent)
        }
        val preparedEnabled = onPreparedAction != null && selection.interactionKey != null
        PlaylistOccurrenceActions(client, selection.track,
            onDismiss = { occurrenceActions = null },
            onOpen = { occurrenceActions = null; onOpenTrack(selection.track) },
            onAdd = { occurrenceActions = null; addingTrack = selection.track },
            onPersonalChange = { stale = true; generation.invalidate() },
            playbackEnabled = playable, contextualLabel = playback.contextualLabel,
            onPlayNow = { perform() },
            onContextual = if (preparedEnabled) ({ perform(PlaybackListAction.CONTEXTUAL) }) else null,
            onEnqueue = if (preparedEnabled) ({ perform(PlaybackListAction.ADD_TO_QUEUE) }) else null,
            onPlayNext = if (preparedEnabled) ({ perform(PlaybackListAction.PLAY_NEXT) }) else null,
            onOpenItem = { occurrenceActions = null; onOpenTrack(it) })
    }
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row { TextButton(onClick = onBack, enabled = !busy) { Text("Back") }; TextButton(onClick = { refreshAll() }, enabled = !busy) { Text("Refresh") } }
            Text(saved?.name ?: "Playlist", style = MaterialTheme.typography.headlineMedium)
            saved?.let {
                Text(it.description)
                Text(if (it.kind == PlaylistKind.DERIVED) "Rules playlist · live inputs" else "${it.trackCount} tracks · ${it.availableCount} with audio")
                if (it.kind == PlaylistKind.DERIVED) Text(if (stale) "Result not current. Refresh explicitly before playing or copying." else "${evaluation?.total} matches · ${evaluation?.availableTotal} with audio. Complete result evaluated.")
                Row {
                    if (it.capabilities.play) Button(onClick = { play() }, enabled = !busy && !stale) { Text("Play") }
                    if (it.capabilities.editMetadata) TextButton(onClick = { metadata = true }, enabled = !busy && !blocked) { Text("Edit details") }
                    if (it.capabilities.delete) TextButton(onClick = { deleting = true }, enabled = !busy && !blocked) { Text("Delete") }
                }
                if (it.kind == PlaylistKind.DERIVED) {
                    Row {
                        if (it.capabilities.editDefinition) TextButton(enabled = !busy && !blocked, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val rules = derived.definition(id)
                                    if (rules.revision != it.revision) { stale = true; message = "Playlist changed. Refresh before editing rules." }
                                    else {
                                        if (editorDraft == null) { editorDraft = PlaylistEditorDraft(it, rules.definition); editorTarget = it }
                                        else if (editorTarget?.revision != rules.revision) {
                                            editorDraft!!.blocked = true
                                            editorDraft!!.message = "The saved revision changed while this draft was retained. It has not been rebased or resubmitted. Discard the draft to reload current rules, or keep it for review."
                                        }
                                        stale = true; generation.invalidate(); editing = true
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { message = playlistFailure(e) }
                                finally { busy = false }
                            }
                        }) { Text("Edit rules") }
                        if (it.capabilities.copyToManual) TextButton(onClick = { copying = true; copyReviewMessage = null }, enabled = !busy && !stale && !blocked && !copyNeedsReview) { Text("Make editable copy") }
                    }
                    if (copyNeedsReview) {
                        Text("A previous copy may already exist. Refreshing the source cannot verify whether a copy was created.")
                        TextButton(enabled = !busy, onClick = { copying = true }) { Text("Verify saved copies") }
                        if (copyReviewed) TextButton(enabled = !busy && !stale, onClick = {
                            copyNeedsReview = false; copyReviewMessage = null
                            message = "Saved copies reviewed. Any further copy is a new explicit operation, not a retry."
                        }) { Text("I reviewed saved copies — allow a new copy") }
                    }
                    Text("Playback prepares up to 1,000 available occurrences. Copies include all occurrences, with a separate 1,000 limit.")
                } else if (it.capabilities.editEntries && snapshot == null) {
                    TextButton(enabled = !busy && !blocked, onClick = {
                        busy = true; generation.invalidate()
                        scope.launch {
                            try { val complete = api.completeSnapshot(id); snapshot = complete; ordered = complete.entries; summary = complete.summary }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = playlistFailure(e); blocked = true }
                            finally { busy = false }
                        }
                    }) { Text("Reorder all occurrences") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (snapshot != null) {
                Text("Complete bounded order loaded (${ordered.size} occurrences). Drag a handle or use Move up/down; save explicitly.")
                Row {
                    Button(enabled = !busy && !blocked, onClick = { val complete = snapshot!!; write {
                        summary = api.reorder(complete, ordered.map { it.id }); snapshot = null; refreshAll()
                    } }) { Text("Save order") }
                    TextButton(enabled = !busy, onClick = { snapshot = null }) { Text("Cancel reorder") }
                }
            }
        }
        if (snapshot != null) items(ordered, key = { it.id }) { entry ->
            val index = ordered.indexOfFirst { it.id == entry.id }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("≡", Modifier.padding(12.dp).semantics { contentDescription = "Drag to reorder ${entry.track.name}" }
                    .pointerInput(entry.id, busy, blocked) {
                        var distance = 0f
                        detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, drag ->
                            if (!busy && !blocked) {
                                change.consume(); distance += drag
                                val threshold = 48.dp.toPx()
                                if (kotlin.math.abs(distance) >= threshold) {
                                    val from = ordered.indexOfFirst { it.id == entry.id }
                                    ordered = movedOccurrence(ordered, from, from + if (distance > 0) 1 else -1); distance = 0f
                                }
                            }
                        })
                    })
                Column(Modifier.weight(1f)) { Text(entry.track.name); Text(entry.track.subtitle) }
                Column {
                    TextButton(onClick = { ordered = movedOccurrence(ordered, index, index - 1) }, enabled = index > 0 && !busy && !blocked) { Text("Move up") }
                    TextButton(onClick = { ordered = movedOccurrence(ordered, index, index + 1) }, enabled = index < ordered.lastIndex && !busy && !blocked) { Text("Move down") }
                }
            }
        } else if (saved?.kind == PlaylistKind.DERIVED) {
            items(evaluation?.items.orEmpty(), key = { it.occurrenceId }) { entry ->
                PlaylistTrackRow(entry.track, onOpenTrack, { track, isCurrent -> occurrenceActions = PlaylistRowSelection(track, entry.selectionToken, interactionKey = playback.interactionKey, isCurrent = isCurrent) },
                    enabled = !busy && !stale && saved.capabilities.play && entry.selectionToken != null,
                    onPlay = { play(selectionToken = entry.selectionToken) }, actionsEnabled = !busy,
                    playback = playback, occurrenceBinding = listOf(id, saved.revision, evaluation?.fence, entry),
                    currentOccurrence = playlistOccurrenceIsCurrent(id, PlaylistKind.DERIVED, entry.occurrenceId,
                        entry.track.id, playback.trackId, playbackEntryId, playbackContext),
                    onContextual = if (onPreparedAction != null) ({ expectedKey, isCurrent -> play(selectionToken = entry.selectionToken,
                        action = PlaybackListAction.CONTEXTUAL, expectedKey = expectedKey, isCurrent = isCurrent) }) else null)
            }
            item {
                Row {
                    TextButton(enabled = !busy && !stale && pageIndex > 0, onClick = { pageIndex-- }) { Text("Previous page") }
                    TextButton(enabled = !busy && !stale && evaluation?.nextCursor != null, onClick = {
                        cursorStack = cursorStack.take(pageIndex + 1) + evaluation!!.nextCursor; pageIndex++
                    }) { Text("Next page") }
                }
            }
        } else {
            items(entries?.items.orEmpty(), key = { it.id }) { entry ->
                Column {
                    PlaylistTrackRow(entry.track, onOpenTrack, { track, isCurrent -> occurrenceActions = PlaylistRowSelection(track, entryId = entry.id, interactionKey = playback.interactionKey, isCurrent = isCurrent) },
                        enabled = !busy && !stale && saved?.capabilities?.play == true,
                        onPlay = { play(trackId = entry.track.id, entryId = entry.id) }, playback = playback,
                        occurrenceBinding = listOf(id, saved?.revision, entry),
                        currentOccurrence = playlistOccurrenceIsCurrent(id, PlaylistKind.MANUAL, entry.id,
                            entry.track.id, playback.trackId, playbackEntryId, playbackContext),
                        onContextual = if (onPreparedAction != null) ({ expectedKey, isCurrent -> play(trackId = entry.track.id,
                            entryId = entry.id, action = PlaybackListAction.CONTEXTUAL, expectedKey = expectedKey, isCurrent = isCurrent) }) else null)
                    if (saved?.capabilities?.editEntries == true) TextButton(enabled = !busy && !blocked,
                        onClick = { removing = entry.id }) { Text("Remove this occurrence") }
                }
            }
            item { Row {
                TextButton(enabled = !busy && offset > 0, onClick = { offset = (offset - 40).coerceAtLeast(0) }) { Text("Previous page") }
                TextButton(enabled = !busy && offset + (entries?.items?.size ?: 0) < (entries?.total ?: 0), onClick = { offset += 40 }) { Text("Next page") }
            } }
        }
    }
}

/** Track equality is not occurrence equality: duplicates and other contexts must not all announce current. */
internal fun playlistOccurrenceIsCurrent(
    playlistId: String, kind: PlaylistKind, occurrenceId: String, trackId: String,
    playbackTrackId: String?, playbackEntryId: String?, playbackContext: LibraryItem?,
): Boolean = playbackTrackId == trackId && playbackEntryId == occurrenceId && playbackContext != null &&
    playbackContext.id == playlistId && playbackContext.kind == (if (kind == PlaylistKind.DERIVED) "derived" else "playlist")

@Composable
internal fun PlaylistTrackRow(track: LibraryItem, onOpenTrack: (LibraryItem) -> Unit,
    onShowTrackActions: (LibraryItem, () -> Boolean) -> Unit, enabled: Boolean, onPlay: () -> Unit, actionsEnabled: Boolean = true,
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(), occurrenceBinding: Any = track,
    currentOccurrence: Boolean = false,
    onContextual: ((PlaybackInteractionKey, () -> Boolean) -> Unit)? = null) {
    val canPlay = enabled && track.playable
    val canContextual = canPlay && playback.interactionKey != null && onContextual != null
    ContextualSwipeRow(occurrenceBinding, playback.interactionKey, canContextual, playback.contextualLabel,
        onCommit = { key, isCurrent -> onContextual?.invoke(key, isCurrent) }) { swipeModifier, isCurrent ->
        Row(swipeModifier.fillMaxWidth().heightIn(min = 72.dp).background(
            if (currentOccurrence) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { onOpenTrack(track) }
                .semantics {
                    if (currentOccurrence) stateDescription = when {
                        playback.buffering -> "Buffering"
                        playback.isPlaying -> "Playing"
                        else -> "Paused"
                    }
                    customActions = buildList {
                        if (canPlay) add(CustomAccessibilityAction("Play now") { onPlay(); true })
                        if (canContextual && playback.activeIntent) add(CustomAccessibilityAction(playback.contextualLabel) {
                            if (isCurrent()) onContextual?.invoke(playback.interactionKey!!, isCurrent); true
                        })
                        if (actionsEnabled) add(CustomAccessibilityAction("More actions") { onShowTrackActions(track, isCurrent); true })
                    }
                }.padding(vertical = 10.dp, horizontal = 8.dp)) {
                Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(compactMusicSubtitle(track), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onShowTrackActions(track, isCurrent) }, enabled = actionsEnabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, "More actions for ${track.name}")
            }
        }
    }
}

@Composable
private fun PlaylistMetadataDialog(title: String, initialName: String, initialDescription: String,
    busy: Boolean, blocked: Boolean, message: String?, onDismiss: () -> Unit, onVerify: () -> Unit,
    onSave: (String, String) -> Unit, explanation: String? = null) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    val invalid = playlistMetadataError(name, description)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            explanation?.let { Text(it) }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, enabled = !busy, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, enabled = !busy, maxLines = 4)
            invalid?.let { Text(it) }; message?.let { Text(it) }
        }
    }, confirmButton = {
        if (blocked) TextButton(enabled = !busy, onClick = onVerify) { Text("Verify saved playlist") }
        else TextButton(enabled = !busy && invalid == null, onClick = { onSave(name.trim(), description) }) { Text("Save") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
