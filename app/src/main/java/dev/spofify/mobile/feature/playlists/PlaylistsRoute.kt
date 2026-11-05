package dev.spofify.mobile.feature.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import dev.spofify.mobile.ui.AutomaticListDemand
import dev.spofify.mobile.ui.AppendStatus
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.PlaybackRequests
import dev.spofify.mobile.playback.queueIntentSelection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Entries have no revision: these checks supplement, never replace, the before/after summaries. */
internal fun validatePlaylistEntryBatch(page: PersonalEntriesPage, offset: Int, total: Long) {
    if (page.offset != offset || page.total.toLong() != total || page.limit != 40 ||
        page.items.size > page.limit || offset.toLong() + page.items.size > total ||
        (page.items.isEmpty() && offset.toLong() < total) ||
        page.items.map { it.id }.toSet().size != page.items.size ||
        page.items.withIndex().any { (index, entry) -> entry.position != offset + index }) throw playlistBrowseChanged()
}

private fun samePlaylistSnapshot(before: PersonalPlaylistSummary, after: PersonalPlaylistSummary): Boolean =
    before.id == after.id && before.kind == after.kind && before.revision == after.revision &&
        before.trackCount == after.trackCount && before.availableCount == after.availableCount

private fun playlistBrowseChanged() = ApiException(409, "Playlist results changed. Refresh before continuing.")

internal fun validatePlaylistEvaluationPrefix(items: List<DerivedEvaluationEntry>, total: Long, nextCursor: String?) {
    if (items.withIndex().any { (index, entry) -> entry.position != index.toLong() } ||
        items.map { it.occurrenceId }.toSet().size != items.size || items.size.toLong() > total ||
        (nextCursor == null && items.size.toLong() != total) ||
        (nextCursor != null && (items.isEmpty() || items.size.toLong() >= total))) throw playlistBrowseChanged()
}

/** Compose keys normally use structural equality; equal login DTOs are not the same incarnation. */
private class PlaylistSessionIdentity(private val session: Session?) {
    override fun equals(other: Any?): Boolean = other is PlaylistSessionIdentity && session === other.session
    override fun hashCode(): Int = System.identityHashCode(session)
}

/** Raw exact source selection; preparation belongs to the selected receiver. */
typealias PlaylistQueueIntent = (QueueIntentSelection, PlaybackListAction, PlaybackInteractionKey?, QueueIntentBinding?, () -> Boolean) -> Unit

/** Capture at the Play tap, before launching/fetching; a late queue is never a fresh intent. */
internal class PlaylistPlaybackRequest(private val generation: PlaylistRequestGeneration,
    private val sourceCurrent: () -> Boolean = { true }) {
    private val ticket = generation.invalidate()
    private val playbackEpoch = PlaybackRequests.epoch

    /** Success/error publication and queue delivery are synchronous on the Compose main coroutine. */
    fun deliver(action: () -> Unit) {
        if (generation.accepts(ticket) && playbackEpoch == PlaybackRequests.epoch && sourceCurrent()) action()
    }

    /** Cancellation must still clear busy, but never clear a newer request's busy state. */
    fun finish(action: () -> Unit) {
        if (generation.accepts(ticket)) action()
    }
}

private data class PlaylistRowSelection(val track: LibraryItem, val selectionToken: String? = null,
    val entryId: String? = null, val interactionKey: PlaybackInteractionKey?, val isCurrent: () -> Boolean,
    val queueSelection: QueueIntentSelection? = null, val binding: QueueIntentBinding? = null,
    val unselectedOrigin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null)

private data class PlaylistCollectionSelection(val selection: QueueIntentSelection, val key: PlaybackInteractionKey?,
    val binding: QueueIntentBinding?, val isCurrent: () -> Boolean,
    val unselectedOrigin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null)

/** Session-local browse data only. Jobs, requests, gestures and transient editors stay on the host. */
class PlaylistBrowseMemory {
    private var owner: SpofifyClient? = null
    private var session: Session? = null
    private var seeded = false
    private var initialId: String? = null
    internal val selectedId = mutableStateOf<String?>(null)
    internal val navigationVersion = mutableLongStateOf(0)
    internal var index = PlaylistIndexBrowseMemory()
        private set
    private var detailId: String? = null
    private var currentDetail: PlaylistDetailBrowseMemory? = null

    /** An explicit Home/external navigation intent, not an instruction to reapply on tab return. */
    fun openPlaylist(id: String?) {
        navigationVersion.longValue++
        if (detailId != id) { detailId = null; currentDetail = null }
        selectedId.value = id
    }

    internal fun bind(client: SpofifyClient) {
        val current = client.currentSession()
        if (owner != null && (owner !== client || session !== current)) {
            selectedId.value = null; index = PlaylistIndexBrowseMemory()
            detailId = null; currentDetail = null; seeded = false; initialId = null
        }
        owner = client; session = current
    }

    internal fun seed(id: String?) {
        if (!seeded) {
            seeded = true; initialId = id
            if (id != null) openPlaylist(id)
        } else if (initialId != id) {
            initialId = id; openPlaylist(id)
        }
    }

    internal fun detail(id: String, change: Long): PlaylistDetailBrowseMemory {
        if (detailId != id) {
            detailId = id; currentDetail = PlaylistDetailBrowseMemory(change)
        }
        return checkNotNull(currentDetail)
    }
}

internal class PlaylistIndexBrowseMemory {
    val refresh = mutableIntStateOf(0)
    val offset = mutableIntStateOf(0)
    val page = mutableStateOf<PersonalPlaylistPage?>(null)
    val appendError = mutableStateOf<String?>(null)
    val failedReset = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)
    val baselineChange = mutableLongStateOf(0)
    val baselineSession = mutableStateOf<Session?>(null, referentialEqualityPolicy())
}

internal class PlaylistDetailBrowseMemory(change: Long) {
    val initialized = mutableStateOf(false)
    val summary = mutableStateOf<PersonalPlaylistSummary?>(null)
    val entries = mutableStateOf<PersonalEntriesPage?>(null)
    val evaluation = mutableStateOf<DerivedEvaluationPage?>(null)
    val manualEntries = mutableStateOf<List<PersonalPlaylistEntry>>(emptyList())
    val derivedEntries = mutableStateOf<List<DerivedEvaluationEntry>>(emptyList())
    val offset = mutableIntStateOf(0)
    val appendError = mutableStateOf<String?>(null)
    val browseChange = mutableLongStateOf(change)
    val usedCursors = mutableStateOf<Set<String>>(emptySet())
    val refresh = mutableIntStateOf(0)
    val stale = mutableStateOf(true)
    val blocked = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)
    val copyNeedsReview = mutableStateOf(false)
}

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
    onQueueIntent: PlaylistQueueIntent? = null,
    onUnselectedHigh: ((QueueIntentSelection, dev.spofify.mobile.playback.PlaybackQueueOrigin, () -> Boolean) -> Unit)? = null,
    browseMemory: PlaylistBrowseMemory? = null,
    sourceEpoch: Any? = null,
) {
    key(client, PlaylistSessionIdentity(client.currentSession())) {
        val memory = remember(browseMemory) { browseMemory ?: PlaylistBrowseMemory() }
        memory.bind(client)
        memory.seed(initialPlaylistId)
        val navigation = memory.navigationVersion.longValue
        var selectedId by memory.selectedId
        var creating by remember { mutableStateOf(false) }
        var newDraft by remember { mutableStateOf(PlaylistEditorDraft()) }
        val index = memory.index
        var refresh by index.refresh
        var offset by index.offset
        var page by index.page
        var loading by remember { mutableStateOf(false) }
        var appending by remember { mutableStateOf(false) }
        var appendError by index.appendError
        var failedIndexReset by index.failedReset
        var message by index.message
        var baselineChange by index.baselineChange
        var baselineSession by index.baselineSession
        val changes by client.personalRevision.collectAsState()
        val api = remember(client) { PersonalCollectionsApi(client) }
        val scope = rememberCoroutineScope()
        val requests = remember { PlaylistRequestGeneration() }
        var indexJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        DisposableEffect(requests) { onDispose { requests.invalidate(); indexJob?.cancel() } }
        val listState = rememberLazyListState()
        fun loadIndex(reset: Boolean = false, retry: Boolean = false) {
            if (loading || appending) return
            val requestedOffset = if (reset) 0 else offset
            val previous = if (reset) null else page
            val session = client.currentSession()
            val change = client.personalRevision.value.playlists
            val ticket = requests.invalidate()
            if (reset) { loading = true; message = null }
            else appending = true
            if (!retry) appendError = null
            indexJob = scope.launch {
                try {
                    if (requestedOffset > 1_000_000) throw playlistBrowseChanged()
                    val next = api.playlists(limit = 40, offset = requestedOffset)
                    if (!requests.accepts(ticket) || client.currentSession() !== session ||
                        memory.navigationVersion.longValue != navigation || selectedId != null || creating) return@launch
                    if (change != client.personalRevision.value.playlists ||
                        (previous != null && (baselineChange != change || baselineSession !== session)) || next.offset != requestedOffset ||
                        next.limit != 40 || next.total < 0 || next.items.size > 40 ||
                        requestedOffset.toLong() + next.items.size > next.total ||
                        (next.items.isEmpty() && requestedOffset < next.total) ||
                        (previous != null && next.total != previous.total) ||
                        next.items.map { it.id }.toSet().size != next.items.size) throw playlistBrowseChanged()
                    val old = previous?.items.orEmpty().associateBy { it.id }
                    if (next.items.any { old[it.id]?.let { retained -> retained != it } == true }) throw playlistBrowseChanged()
                    val added = next.items.filter { it.id !in old }
                    if (next.items.isNotEmpty() && added.isEmpty()) throw playlistBrowseChanged()
                    page = next.copy(items = previous?.items.orEmpty() + added)
                    offset = requestedOffset + next.items.size // Raw consumed positions, not deduplicated display size.
                    baselineChange = change; baselineSession = session
                    message = null; appendError = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (requests.accepts(ticket) && client.currentSession() === session &&
                        memory.navigationVersion.longValue == navigation && selectedId == null && !creating) {
                        if (e is ApiException && (e.status in 200..299 || e.status == 409)) {
                            message = "Playlists changed. Refresh to continue."; appendError = null
                        } else { appendError = "Playlists could not load."; failedIndexReset = reset }
                    }
                } finally {
                    if (requests.accepts(ticket)) { loading = false; appending = false }
                }
            }
        }
        LaunchedEffect(client, refresh, selectedId, creating, navigation) {
            if (selectedId == null && !creating && page == null && appendError == null && message == null) loadIndex(reset = true)
        }
        // Hidden destinations never drive demand; reads already in flight are also fenced on leaving.
        DisposableEffect(selectedId, creating, navigation) {
            onDispose { requests.invalidate(); indexJob?.cancel(); loading = false; appending = false }
        }
        val indexChanged = page != null && changes.playlists != baselineChange
        if (selectedId == null && !creating) AutomaticListDemand(listState, refresh,
            page?.items.orEmpty().takeLast(3).map { it.id },
            !loading && !appending && appendError == null && message == null && !indexChanged && offset < (page?.total ?: 0)) { loadIndex() }
        when {
            creating -> PlaylistEditor(client, null, newDraft, onBack = { creating = false }, onSaved = {
                creating = false; newDraft = PlaylistEditorDraft(); selectedId = it.id; refresh++
            }, modifier = modifier, onDiscard = { newDraft = PlaylistEditorDraft(); creating = false; refresh++ },
                onMutationPendingChange = onMutationPendingChange)
            selectedId != null -> key(navigation, selectedId) {
                val detailId = selectedId!!
                PlaylistDetail(client, detailId, onOpenTrack, onPlayQueue, onShowTrackActions,
                    onBack = { selectedId = null }, modifier = modifier, onMutationPendingChange = onMutationPendingChange,
                    playback = playback, onPreparedAction = onPreparedAction,
                    playbackEntryId = playbackEntryId, playbackContext = playbackContext, onQueueIntent = onQueueIntent,
                    onUnselectedHigh = onUnselectedHigh, browse = memory.detail(detailId, changes.playlists),
                    navigationCurrent = { memory.navigationVersion.longValue == navigation && selectedId == detailId },
                    sourceEpoch = sourceEpoch)
            }
            else -> LazyColumn(modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row {
                        Button(onClick = { creating = true }) { Text("Create playlist") }
                        TextButton(onClick = { refresh++; loadIndex(reset = true) }, enabled = !loading && !appending) { Text("Refresh") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (indexChanged) Text("Playlists changed. Refresh to continue.")
                }
                items(page?.items.orEmpty(), key = { it.id }) { playlist ->
                    PlaylistCard(playlist, onClick = { memory.openPlaylist(playlist.id) })
                }
                item {
                    AppendStatus(appending, if (loading || appending) null else appendError,
                        onRetry = { loadIndex(reset = failedIndexReset, retry = true) }, loadingLabel = "Loading more playlists")
                    if (!loading && page?.total == 0) Text("No playlists yet.")
                    else if (!loading && message == null && page != null && offset >= page!!.total) Text("All playlists loaded.")
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
    playbackEntryId: String?, playbackContext: LibraryItem?, onQueueIntent: PlaylistQueueIntent?,
    onUnselectedHigh: ((QueueIntentSelection, dev.spofify.mobile.playback.PlaybackQueueOrigin, () -> Boolean) -> Unit)?,
    browse: PlaylistDetailBrowseMemory,
    navigationCurrent: () -> Boolean,
    sourceEpoch: Any?,
) {
    val api = remember(client) { PersonalCollectionsApi(client) }
    val derived = remember(client) { DerivedPlaylistsApi(client) }
    val scope = rememberCoroutineScope()
    val generation = remember(id) { PlaylistRequestGeneration() }
    val playbackRequests = remember(id) { PlaylistRequestGeneration() }
    var summary by browse.summary
    var entries by browse.entries
    var evaluation by browse.evaluation
    var manualEntries by browse.manualEntries
    var derivedEntries by browse.derivedEntries
    var offset by browse.offset
    var appending by remember(id) { mutableStateOf(false) }
    var appendError by browse.appendError
    var browseChange by browse.browseChange
    val personalChanges by client.personalRevision.collectAsState()
    var usedCursors by browse.usedCursors
    val browseRequests = remember(id) { PlaylistRequestGeneration() }
    var appendJob by remember(id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(browseRequests) {
        onDispose { browseRequests.invalidate(); playbackRequests.invalidate(); generation.invalidate(); appendJob?.cancel() }
    }
    var refresh by browse.refresh
    val sourceSession = remember(id, refresh) { client.currentSession() }
    var busy by remember(id) { mutableStateOf(false) }
    var mutating by remember(id) { mutableStateOf(false) }
    ReportPlaylistMutationPending(mutating, onMutationPendingChange)
    var stale by browse.stale
    var blocked by browse.blocked
    var message by browse.message
    var metadata by remember(id) { mutableStateOf(false) }
    var deleting by remember(id) { mutableStateOf(false) }
    var removing by remember(id) { mutableStateOf<String?>(null) }
    var occurrenceActions by remember(id) { mutableStateOf<PlaylistRowSelection?>(null) }
    var collectionActions by remember(id) { mutableStateOf<PlaylistCollectionSelection?>(null) }
    val actionLifetime = remember(id, client) { dev.spofify.mobile.ui.RowActionLifetime() }
    DisposableEffect(actionLifetime) { onDispose { actionLifetime.invalidate() } }
    val latestInteractionKey by rememberUpdatedState(playback.interactionKey)
    val latestSourceEpoch = rememberUpdatedState(sourceEpoch)
    var addingTrack by remember(id) { mutableStateOf<LibraryItem?>(null) }
    var editing by remember(id) { mutableStateOf(false) }
    var copying by remember(id) { mutableStateOf(false) }
    var copyNeedsReview by browse.copyNeedsReview
    var copyReviewed by remember(id) { mutableStateOf(false) }
    var copyReviewMessage by remember(id) { mutableStateOf<String?>(null) }
    var editorDraft by remember(id) { mutableStateOf<PlaylistEditorDraft?>(null) }
    var editorTarget by remember(id) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var snapshot by remember(id) { mutableStateOf<ManualPlaylistSnapshot?>(null) }
    var ordered by remember(id) { mutableStateOf<List<PersonalPlaylistEntry>>(emptyList()) }
    val listState = rememberLazyListState()

    fun refreshAll() {
        generation.invalidate(); browseRequests.invalidate(); appendJob?.cancel(); appending = false
        stale = true; snapshot = null; offset = 0; appendError = null; usedCursors = emptySet()
        browse.initialized.value = false; refresh++
    }
    fun write(onFailure: ((Exception) -> Unit)? = null, action: suspend () -> Unit) {
        if (busy || blocked || !navigationCurrent()) return
        generation.invalidate(); browseRequests.invalidate(); appendJob?.cancel(); appending = false
        busy = true; mutating = true; stale = true; onMutationPendingChange(true)
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { blocked = true; message = playlistFailure(e, true); onFailure?.invoke(e) }
            finally { busy = false; mutating = false; onMutationPendingChange(false) }
        }
    }
    LaunchedEffect(id, refresh) {
        if (browse.initialized.value) return@LaunchedEffect
        val ticket = generation.invalidate()
        val request = browseRequests.invalidate()
        val session = client.currentSession()
        val change = client.personalRevision.value.playlists
        fun current() = navigationCurrent() && generation.accepts(ticket) && browseRequests.accepts(request) && client.currentSession() === session
        busy = true; stale = true; appendError = null
        try {
            val saved = api.playlist(id)
            if (current()) summary = saved
            val manual = if (saved.kind == PlaylistKind.MANUAL) api.entries(id, limit = 40, offset = 0) else null
            if (manual != null) {
                validatePlaylistEntryBatch(manual, 0, saved.trackCount!!)
                if (!samePlaylistSnapshot(saved, api.playlist(id))) throw playlistBrowseChanged()
            }
            val result = if (saved.kind == PlaylistKind.DERIVED) derived.evaluate(id, limit = 40) else null
            if (result != null) {
                if (result.revision != saved.revision || result.limit != 40) throw playlistBrowseChanged()
                validatePlaylistEvaluationPrefix(result.items, result.total, result.nextCursor)
            }
            if (change != client.personalRevision.value.playlists) throw playlistBrowseChanged()
            if (current()) {
                summary = saved; entries = manual; evaluation = result
                manualEntries = manual?.items.orEmpty(); derivedEntries = result?.items.orEmpty()
                offset = manual?.items?.size ?: 0; browseChange = change
                stale = false; blocked = false; message = null; browse.initialized.value = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (current()) { message = playlistFailure(e); stale = true; browse.initialized.value = true } }
        finally { if (generation.accepts(ticket)) busy = false }
    }
    fun append() {
        val baseline = summary ?: return
        if (busy || appending || stale || snapshot != null || editing || !navigationCurrent() || client.currentSession() !== sourceSession) return
        val requestedOffset = offset
        val requestedCursor = evaluation?.nextCursor
        if (baseline.kind == PlaylistKind.MANUAL && requestedOffset >= (entries?.total ?: 0) ||
            baseline.kind == PlaylistKind.DERIVED && requestedCursor == null) return
        val session = client.currentSession()
        val change = browseChange
        val request = browseRequests.invalidate() // Separate from held row/More/play authority.
        appending = true // Synchronous single-flight reservation; a canceled Retry keeps its latch.
        appendJob = scope.launch {
            fun current() = navigationCurrent() && browseRequests.accepts(request) && client.currentSession() === session && !stale && snapshot == null
            try {
                if (change != client.personalRevision.value.playlists) throw playlistBrowseChanged()
                if (baseline.kind == PlaylistKind.MANUAL) {
                    if (requestedOffset > 1_000_000 || !samePlaylistSnapshot(baseline, api.playlist(id))) throw playlistBrowseChanged()
                    val next = api.entries(id, limit = 40, offset = requestedOffset)
                    validatePlaylistEntryBatch(next, requestedOffset, baseline.trackCount!!)
                    val after = api.playlist(id)
                    if (!samePlaylistSnapshot(baseline, after) ||
                        next.items.any { entry -> manualEntries.any { it.id == entry.id } }) throw playlistBrowseChanged()
                    if (current() && change == client.personalRevision.value.playlists) {
                        manualEntries = manualEntries + next.items; entries = next
                        offset = requestedOffset + next.items.size; appendError = null
                    } else if (current()) throw playlistBrowseChanged()
                } else {
                    val retained = evaluation ?: throw playlistBrowseChanged()
                    val next = derived.evaluate(id, limit = 40, cursor = requestedCursor)
                    if (next.revision != baseline.revision || next.fence != retained.fence ||
                        next.total != retained.total || next.availableTotal != retained.availableTotal || next.limit != 40 ||
                        next.nextCursor == requestedCursor || next.nextCursor in usedCursors ||
                        next.items.zipWithNext().any { (a, b) -> a.position >= b.position }) throw playlistBrowseChanged()
                    val old = derivedEntries.associateBy { it.occurrenceId }
                    if (next.items.any { old[it.occurrenceId]?.let { previous -> previous != it } == true }) throw playlistBrowseChanged()
                    val added = next.items.filter { it.occurrenceId !in old }
                    val previousPosition = derivedEntries.lastOrNull()?.position
                    if ((next.items.isNotEmpty() && added.isEmpty()) ||
                        (next.items.isEmpty() && next.nextCursor != null) ||
                        (previousPosition != null && added.firstOrNull()?.position?.let { it != previousPosition + 1 } == true) ||
                        added.zipWithNext().any { (a, b) -> b.position != a.position + 1 }) throw playlistBrowseChanged()
                    val combined = derivedEntries + added
                    validatePlaylistEvaluationPrefix(combined, next.total, next.nextCursor)
                    if (current() && change == client.personalRevision.value.playlists) {
                        derivedEntries = combined; evaluation = next; appendError = null
                        usedCursors = usedCursors + requestedCursor!!
                    } else if (current()) throw playlistBrowseChanged()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current()) {
                    if (e is ApiException && (e.status == 409 || e.status == 410 || e.status in 200..299)) {
                        stale = true; generation.invalidate(); message = playlistFailure(e); appendError = null
                    } else appendError = "More songs could not load."
                }
            } finally { if (browseRequests.accepts(request)) appending = false }
        }
    }
    LaunchedEffect(personalChanges.playlists) {
        if (!stale && personalChanges.playlists != browseChange) {
            generation.invalidate(); browseRequests.invalidate(); appendJob?.cancel(); appending = false
            stale = true; message = "Playlists changed. Refresh to continue."
        }
    }
    AutomaticListDemand(listState, refresh,
        if (summary?.kind == PlaylistKind.DERIVED) derivedEntries.takeLast(3).map { it.occurrenceId }
        else manualEntries.takeLast(3).map { it.id },
        !busy && !appending && !stale && appendError == null && snapshot == null && !editing && !copying &&
            (if (summary?.kind == PlaylistKind.DERIVED) evaluation?.nextCursor != null else offset < (entries?.total ?: 0))) { append() }
    BackHandler {
        if (!busy) when { editing -> editing = false; copying -> copying = false; snapshot != null -> refreshAll(); else -> onBack() }
    }
    val saved = summary
    fun captureCurrent(rowCurrent: () -> Boolean = { true }): () -> Boolean {
        val ticket = generation.ticket()
        val session = client.currentSession()
        val change = client.personalRevision.value.playlists
        val revision = summary?.revision
        val fence = evaluation?.fence
        val capturedEpoch = sourceEpoch
        return { latestSourceEpoch.value == capturedEpoch && navigationCurrent() && rowCurrent() && actionLifetime.isCurrent() && generation.accepts(ticket) &&
            client.currentSession() === session && session === sourceSession &&
            client.personalRevision.value.playlists == change && change == browseChange &&
            summary?.revision == revision && evaluation?.fence == fence }
    }
    fun exactSelection(track: LibraryItem? = null, occurrenceId: String? = null,
        token: String? = null, entryId: String? = null): QueueIntentSelection? {
        val current = saved ?: return null
        val context = PlaybackContext(if (current.kind == PlaylistKind.DERIVED) "derived" else "playlist",
            id, current.name, current.revision, if (current.kind == PlaylistKind.DERIVED) evaluation?.fence ?: return null else "")
        return if (track == null) QueueIntentSelection.Collection(context)
            else queueIntentSelection(track.copy(entryId = entryId), context, occurrenceId, token)
    }
    fun submit(selection: QueueIntentSelection?, action: PlaybackListAction,
        key: PlaybackInteractionKey? = playback.interactionKey, binding: QueueIntentBinding? = playback.queueIntentBinding,
        current: () -> Boolean = actionLifetime::isCurrent,
        origin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null) {
        if (selection == null || busy || stale || !navigationCurrent() || !current() || !actionLifetime.isCurrent() ||
            client.currentSession() !== sourceSession || client.personalRevision.value.playlists != browseChange) return
        if (key != null && latestInteractionKey !== key) return
        val epoch = PlaybackRequests.epoch
        val revision = saved?.revision
        val session = client.currentSession()
        val change = browseChange
        val sourceTicket = generation.ticket()
        val live = {
            navigationCurrent() && current() && actionLifetime.isCurrent() && generation.accepts(sourceTicket) &&
                epoch == PlaybackRequests.epoch && !stale && summary?.revision == revision &&
                client.currentSession() === session && client.personalRevision.value.playlists == change
        }
        if (action == PlaybackListAction.QUEUE_HIGH && origin != null) onUnselectedHigh?.invoke(selection, origin, live)
        else onQueueIntent?.invoke(selection, action, key, binding, live)
    }
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
        if (busy || stale || saved == null || !navigationCurrent() || !isCurrent() || client.currentSession() !== sourceSession ||
            client.personalRevision.value.playlists != browseChange) return
        if (onQueueIntent != null) {
            val row = if (selectionToken != null) derivedEntries.firstOrNull { it.selectionToken == selectionToken } else null
            val manual = if (entryId != null) manualEntries.firstOrNull { it.id == entryId } else null
            submit(exactSelection(row?.track ?: manual?.track, row?.occurrenceId, row?.selectionToken, manual?.id),
                action ?: PlaybackListAction.PLAY_NOW, expectedKey ?: playback.interactionKey, current = isCurrent)
            return
        }
        if (action != null && (onPreparedAction == null || expectedKey == null || latestInteractionKey !== expectedKey)) return
        val result = evaluation
        val sourceTicket = generation.ticket()
        val request = PlaylistPlaybackRequest(playbackRequests) {
            navigationCurrent() && generation.accepts(sourceTicket) && actionLifetime.isCurrent() && client.currentSession() === sourceSession
        }; busy = true
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
            if (onQueueIntent != null) submit(selection.queueSelection, action ?: PlaybackListAction.PLAY_NOW,
                selection.interactionKey, selection.binding, selection.isCurrent, selection.unselectedOrigin)
            else play(selectionToken = selection.selectionToken, trackId = selection.track.id,
                entryId = selection.entryId, action = action, expectedKey = selection.interactionKey, isCurrent = selection.isCurrent)
        }
        val preparedEnabled = onQueueIntent != null || onPreparedAction != null && selection.interactionKey != null
        PlaylistOccurrenceActions(client, selection.track,
            onDismiss = { occurrenceActions = null },
            onOpen = { occurrenceActions = null; onOpenTrack(selection.track) },
            onAdd = { occurrenceActions = null; addingTrack = selection.track },
            onPersonalChange = { stale = true; generation.invalidate() },
            playbackEnabled = playable, contextualLabel = playback.contextualLabel,
            onPlayNow = { perform() },
            onContextual = if (preparedEnabled) ({ perform(PlaybackListAction.QUEUE_HIGH) }) else null,
            onEnqueue = if (preparedEnabled) ({ perform(PlaybackListAction.QUEUE_LOW) }) else null,
            onPlayNext = if (preparedEnabled) ({ perform(PlaybackListAction.PLAY_NEXT) }) else null,
            onOpenItem = { occurrenceActions = null; onOpenTrack(it) })
    }
    collectionActions?.let { captured ->
        AlertDialog(onDismissRequest = { collectionActions = null }, title = { Text(saved?.name ?: "Playlist") }, text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                listOf("Queue Low" to PlaybackListAction.QUEUE_LOW, "Queue High" to PlaybackListAction.QUEUE_HIGH,
                    "Play next" to PlaybackListAction.PLAY_NEXT, "Play now" to PlaybackListAction.PLAY_NOW).forEach { (label, action) ->
                    TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && !stale, onClick = {
                        collectionActions = null; submit(captured.selection, action, captured.key, captured.binding, captured.isCurrent, captured.unselectedOrigin)
                    }) { Text(label) }
                }
            }
        }, confirmButton = {})
    }
    // Capture source authority while rendering, never upgrade a held gesture at its later commit.
    // This ticket stays unchanged for append, but distinguishes even same-revision/fence resets.
    val renderedSourceTicket = generation.ticket()
    val renderedCurrent = captureCurrent()
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row { TextButton(onClick = { refreshAll() }, enabled = !busy) { Text("Refresh") }
                if (onQueueIntent != null) IconButton(onClick = { exactSelection()?.let { collectionActions = PlaylistCollectionSelection(it, playback.interactionKey, playback.queueIntentBinding, captureCurrent(), playback.unselectedQueueOrigin) } }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, "More actions for ${saved?.name ?: "playlist"}")
                }
            }
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
                        busy = true; stale = true; generation.invalidate(); browseRequests.invalidate(); appendJob?.cancel(); appending = false
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
                    TextButton(enabled = !busy, onClick = { refreshAll() }) { Text("Cancel reorder") }
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
            items(derivedEntries, key = { it.occurrenceId }) { entry ->
                PlaylistTrackRow(entry.track, onOpenTrack, { track, rowCurrent ->
                    val current = { renderedCurrent() && rowCurrent() }
                    if (current()) occurrenceActions = PlaylistRowSelection(track, entry.selectionToken, interactionKey = playback.interactionKey, isCurrent = current,
                        queueSelection = exactSelection(track, entry.occurrenceId, entry.selectionToken), binding = playback.queueIntentBinding,
                        unselectedOrigin = playback.unselectedQueueOrigin) },
                    enabled = !busy && !stale && saved.capabilities.play && entry.selectionToken != null,
                    onPlay = { play(selectionToken = entry.selectionToken, isCurrent = renderedCurrent) }, actionsEnabled = !busy,
                    playback = playback, occurrenceBinding = listOf(id, sourceEpoch, renderedSourceTicket, saved.revision, evaluation?.fence, entry),
                    currentOccurrence = playlistOccurrenceIsCurrent(id, PlaylistKind.DERIVED, entry.occurrenceId,
                        entry.track.id, playback.trackId, playbackEntryId, playbackContext),
                    onContextual = if (onQueueIntent != null || onPreparedAction != null) ({ expectedKey, rowCurrent -> play(selectionToken = entry.selectionToken,
                        action = PlaybackListAction.QUEUE_HIGH, expectedKey = expectedKey, isCurrent = { renderedCurrent() && rowCurrent() }) }) else null,
                    onUnselectedHigh = if (onUnselectedHigh != null) ({ origin, rowCurrent ->
                        submit(exactSelection(entry.track, entry.occurrenceId, entry.selectionToken), PlaybackListAction.QUEUE_HIGH,
                            null, null, { renderedCurrent() && rowCurrent() }, origin)
                    }) else null)
            }
            item {
                AppendStatus(appending, if (appending) null else appendError, onRetry = { append() }, loadingLabel = "Loading more songs")
                if (!busy && !stale && derivedEntries.isEmpty()) Text("No songs match these rules.")
                else if (!busy && !stale && evaluation?.nextCursor == null) Text("All songs loaded.")
            }
        } else {
            items(manualEntries, key = { it.id }) { entry ->
                Column {
                    PlaylistTrackRow(entry.track, onOpenTrack, { track, rowCurrent ->
                        val current = { renderedCurrent() && rowCurrent() }
                        if (current()) occurrenceActions = PlaylistRowSelection(track, entryId = entry.id, interactionKey = playback.interactionKey, isCurrent = current,
                        queueSelection = exactSelection(track, entryId = entry.id), binding = playback.queueIntentBinding,
                        unselectedOrigin = playback.unselectedQueueOrigin) },
                        enabled = !busy && !stale && saved?.capabilities?.play == true,
                        onPlay = { play(trackId = entry.track.id, entryId = entry.id, isCurrent = renderedCurrent) }, playback = playback,
                        occurrenceBinding = listOf(id, sourceEpoch, renderedSourceTicket, saved?.revision, entry),
                        currentOccurrence = playlistOccurrenceIsCurrent(id, PlaylistKind.MANUAL, entry.id,
                            entry.track.id, playback.trackId, playbackEntryId, playbackContext),
                        onContextual = if (onQueueIntent != null || onPreparedAction != null) ({ expectedKey, rowCurrent -> play(trackId = entry.track.id,
                            entryId = entry.id, action = PlaybackListAction.QUEUE_HIGH, expectedKey = expectedKey, isCurrent = { renderedCurrent() && rowCurrent() }) }) else null,
                        onUnselectedHigh = if (onUnselectedHigh != null) ({ origin, rowCurrent ->
                            submit(exactSelection(entry.track, entryId = entry.id), PlaybackListAction.QUEUE_HIGH,
                                null, null, { renderedCurrent() && rowCurrent() }, origin)
                        }) else null)
                    if (saved?.capabilities?.editEntries == true) TextButton(enabled = !busy && !blocked,
                        onClick = { removing = entry.id }) { Text("Remove this occurrence") }
                }
            }
            item {
                AppendStatus(appending, if (appending) null else appendError, onRetry = { append() }, loadingLabel = "Loading more songs")
                if (!busy && !stale && manualEntries.isEmpty()) Text("No songs yet.")
                else if (!busy && !stale && offset >= (entries?.total ?: 0)) Text("All songs loaded.")
            }
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
    onContextual: ((PlaybackInteractionKey, () -> Boolean) -> Unit)? = null,
    onUnselectedHigh: ((dev.spofify.mobile.playback.PlaybackQueueOrigin, () -> Boolean) -> Unit)? = null) {
    val canPlay = enabled && track.playable
    val capture = playback.interactionKey ?: playback.unselectedQueueOrigin
    val canContextual = canPlay && (playback.interactionKey != null && onContextual != null ||
        playback.unselectedQueueOrigin != null && onUnselectedHigh != null)
    fun contextual(captured: Any, current: () -> Boolean) {
        when (captured) {
            is PlaybackInteractionKey -> onContextual?.invoke(captured, current)
            is dev.spofify.mobile.playback.PlaybackQueueOrigin -> onUnselectedHigh?.invoke(captured, current)
        }
    }
    ContextualSwipeRow(occurrenceBinding, capture, canContextual, playback.contextualLabel,
        onCommit = { captured, current -> contextual(captured, current) }) { swipeModifier, isCurrent ->
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
                        if (canContextual) add(CustomAccessibilityAction(playback.contextualLabel) {
                            if (isCurrent()) capture?.let { contextual(it, isCurrent) }; true
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
