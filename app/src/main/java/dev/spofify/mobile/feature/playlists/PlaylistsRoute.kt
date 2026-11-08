package dev.spofify.mobile.feature.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.spofify.mobile.feature.library.CatalogPlaybackFeedback
import dev.spofify.mobile.feature.library.MusicListLayout
import dev.spofify.mobile.feature.library.MusicRow
import dev.spofify.mobile.feature.library.MusicRowActionGuard
import dev.spofify.mobile.feature.library.sourceCurrent as sourceLifetime
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import dev.spofify.mobile.ui.AutomaticListDemand
import dev.spofify.mobile.ui.AppendStatus
import dev.spofify.mobile.ui.SongActionSheet
import dev.spofify.mobile.playback.defaultQueueAction
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.PlaybackRequests
import dev.spofify.mobile.playback.queueIntentSelection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Entries have no revision: these checks supplement, never replace, the before/after summaries. */
internal fun validatePlaylistEntryBatch(page: PersonalEntriesPage, offset: Int, total: Long, afterPosition: Int = -1) {
    if (page.offset != offset || page.total.toLong() != total || page.limit != 40 ||
        page.items.size > page.limit || offset.toLong() + page.items.size > total ||
        (page.items.isEmpty() && offset.toLong() < total) ||
        page.items.map { it.id }.toSet().size != page.items.size ||
        page.items.any { it.position < 0 } ||
        page.items.firstOrNull()?.let { it.position <= afterPosition } == true ||
        page.items.zipWithNext().any { (left, right) -> left.position >= right.position }) throw playlistBrowseChanged()
}

private fun samePlaylistSnapshot(before: PersonalPlaylistSummary, after: PersonalPlaylistSummary): Boolean =
    before.id == after.id && before.kind == after.kind && before.revision == after.revision &&
        before.trackCount == after.trackCount && before.availableCount == after.availableCount

private fun playlistBrowseChanged() = ApiException(409, "Playlist results changed. Reload before continuing.")

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

private data class PlaylistCollectionSelection(val selection: QueueIntentSelection?, val key: PlaybackInteractionKey?,
    val binding: QueueIntentBinding?, val isCurrent: () -> Boolean,
    val unselectedOrigin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null)

/** Session-local browse data only. Jobs, requests, gestures and transient editors stay on the host. */
class PlaylistBrowseMemory(private val projectionDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default) {
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
            detailId = id; currentDetail = PlaylistDetailBrowseMemory(change, projectionDispatcher)
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
    val baselineLikes = mutableLongStateOf(0)
    val baselineSession = mutableStateOf<Session?>(null, referentialEqualityPolicy())
}

internal data class PlaylistScrollAnchor(val key: Any?, val index: Int, val offset: Int)

/** One successful server read keeps its original retention frame; it is not local authority. */
internal data class SavedPlaylistControlFrame(val summary: PersonalPlaylistSummary,
    val source: PlaylistSourceStamp, val bindingRevision: Long)

internal class PlaylistDetailBrowseMemory(change: Long,
    projectionDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default) {
    val initialized = mutableStateOf(false)
    val summary = mutableStateOf<PersonalPlaylistSummary?>(null)
    val entries = mutableStateOf<PersonalEntriesPage?>(null)
    val evaluation = mutableStateOf<DerivedEvaluationPage?>(null)
    val readSource = mutableStateOf<PlaylistSourceStamp?>(null)
    val savedControlFrame = mutableStateOf<SavedPlaylistControlFrame?>(null)

    internal fun publishRead(response: PersonalPlaylistSummary, source: PlaylistSourceStamp, bindingRevision: Long?) {
        Snapshot.withMutableSnapshot {
            summary.value = response
            readSource.value = source
            // Structural state equality can retain an older equal object. Pair with the value
            // actually published, not the fresh response reference that state did not install.
            savedControlFrame.value = bindingRevision?.let {
                SavedPlaylistControlFrame(checkNotNull(summary.value), source, it)
            }
        }
    }

    val manualEntries = mutableStateOf<List<PersonalPlaylistEntry>>(emptyList())
    val derivedEntries = mutableStateOf<List<DerivedEvaluationEntry>>(emptyList())
    val offset = mutableIntStateOf(0)
    val appendError = mutableStateOf<String?>(null)
    val fetchFailed = mutableStateOf(false)
    val observationChecks = mutableIntStateOf(0)
    val browseChange = mutableLongStateOf(change)
    val usedCursors = mutableStateOf<Set<String>>(emptySet())
    val refresh = mutableIntStateOf(0)
    val stale = mutableStateOf(true)
    val blocked = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)
    val copyNeedsReview = mutableStateOf(false)
    val filterQuery = mutableStateOf("")
    // Separate anchors: clearing returns to the unfiltered source occurrence where possible.
    val songsScroll = LazyListState()
    val filteredScroll = LazyListState()
    val manualProjection = ResidentPlaylistProjection<PersonalPlaylistEntry>(projectionDispatcher)
    val derivedProjection = ResidentPlaylistProjection<DerivedEvaluationEntry>(projectionDispatcher)
    // Pending layouts must not overwrite this anchor, even across another source update/tab disposal.
    var pendingFilteredAnchor: Pair<String, PlaylistScrollAnchor>? = null
    val filterDemandPaused = mutableStateOf(false)
    var unfilteredAnchor: PlaylistScrollAnchor? = null
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
    artwork: @Composable (LibraryItem, Modifier) -> Unit = { _, artModifier -> Icon(Icons.Default.MusicNote, null, artModifier) },
    browseMemory: PlaylistBrowseMemory? = null,
    sourceEpoch: Any? = null,
    savedDownloads: SavedDownloadsOwner? = null,
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
        val savedLibrary = savedDownloads?.state?.collectAsState()?.value
        val savedPlaylistIds = savedLibrary?.collections.orEmpty()
            .filter { it.target.matchesPlaylist(it.target.id) }.mapTo(hashSetOf()) { it.target.id }
        var refresh by index.refresh
        var offset by index.offset
        var page by index.page
        var loading by remember { mutableStateOf(false) }
        var appending by remember { mutableStateOf(false) }
        var appendError by index.appendError
        var failedIndexReset by index.failedReset
        var message by index.message
        var baselineChange by index.baselineChange
        var baselineLikes by index.baselineLikes
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
            val source = PlaylistSourceStamp(client)
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
                    if (!source.isCurrent() || !requests.accepts(ticket) || client.currentSession() !== session ||
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
                    baselineChange = change; baselineLikes = client.personalRevision.value.likes; baselineSession = session
                    message = null; appendError = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (requests.accepts(ticket) && client.currentSession() === session &&
                        memory.navigationVersion.longValue == navigation && selectedId == null && !creating) {
                        if (e is ApiException && (e.status in 200..299 || e.status == 409)) {
                            message = null; appendError = "Playlists changed. Reload the list to continue."; failedIndexReset = true
                        } else { appendError = "Playlists could not load."; failedIndexReset = reset }
                    }
                } finally {
                    if (requests.accepts(ticket)) { loading = false; appending = false }
                }
            }
        }
        LaunchedEffect(client, refresh, selectedId, creating, navigation, changes.playlists, changes.likes) {
            if (selectedId == null && !creating &&
                ((page == null && appendError == null && message == null) ||
                    (page != null && (baselineChange != changes.playlists || baselineLikes != changes.likes)))) {
                requests.invalidate(); indexJob?.cancel(); loading = false; appending = false
                loadIndex(reset = true)
            }
        }
        // Hidden destinations never drive demand; reads already in flight are also fenced on leaving.
        DisposableEffect(selectedId, creating, navigation) {
            onDispose { requests.invalidate(); indexJob?.cancel(); loading = false; appending = false }
        }
        val indexChanged = page != null && (changes.playlists != baselineChange || changes.likes != baselineLikes)
        val renderedServerPlaylists = page?.items.orEmpty().filterNot { it.id in savedPlaylistIds }
        if (selectedId == null && !creating) AutomaticListDemand(listState, refresh,
            savedIndexFrontier(renderedServerPlaylists.map { it.id }, savedLibrary?.collections?.lastOrNull()?.target),
            frontierProgress = Triple(offset, baselineChange, baselineLikes),
            enabled = !loading && !appending && appendError == null && message == null && !indexChanged && offset < (page?.total ?: 0)) { loadIndex() }
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
                    sourceEpoch = sourceEpoch, artwork = artwork, savedDownloads = savedDownloads)
            }
            else -> LazyColumn(modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row {
                        Button(onClick = { creating = true }) { Text("Create playlist") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (indexChanged && loading) Text("Playlists changed. Updating list…")
                }
                // Saved identities have stable keys/positions as further server pages arrive.
                items(savedLibrary?.collections.orEmpty(), key = { "saved:${it.target.kind}:${it.target.id}" }) { saved ->
                    if (savedDownloads != null) SavedCollectionIndexRow(saved, savedDownloads,
                        bindingRevision = checkNotNull(savedLibrary).bindingRevision,
                        onOpenServerPlaylist = if (saved.target.matchesPlaylist(saved.target.id))
                            ({ memory.openPlaylist(saved.target.id) }) else null)
                }
                items(renderedServerPlaylists, key = { it.id }) { playlist ->
                    PlaylistCard(playlist, onClick = { memory.openPlaylist(playlist.id) })
                }
                item {
                    AppendStatus(appending, if (loading || appending) null else appendError,
                        onRetry = { loadIndex(reset = failedIndexReset, retry = true) }, loadingLabel = "Loading more playlists")
                    if (!loading && page?.total == 0 && savedLibrary?.collections.isNullOrEmpty()) Text("No playlists yet.")
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
                Text(playlistCountLabel(playlist))
                if (playlist.description.isNotBlank()) Text(playlist.description, maxLines = 3)
            }
        }
    }
}

internal fun playlistFailure(error: Exception, mutation: Boolean = false): String = when {
    error is ApiException && error.code == "SOURCE_REFERENCED" -> "Remove playlist references before deleting."
    error is ApiException && error.code == "PLAYLIST_KIND_MISMATCH" -> "Action unavailable. Reload playlist."
    error is ApiException && error.code == "PLAYLIST_STORAGE_EXCEEDED" -> "Playlist is too large to save."
    error is ApiException && error.code == "COPY_LIMIT_EXCEEDED" -> "Playlist is too large to copy."
    error is ApiException && error.code == "RECIPE_CYCLE" -> "Circular playlist sources. Choose another source."
    error is ApiException && error.code == "RECIPE_LIMIT_EXCEEDED" -> "Too many rules. Simplify them."
    error is ApiException && error.code == "OCCURRENCE_UNAVAILABLE" -> "Song unavailable."
    error is ApiException && error.status == 409 -> "Playlist changed. Check saved playlist."
    error is ApiException && error.status == 429 -> "Playlist is updating. Try again in a moment."
    error is ApiException && error.status == 413 -> "Playlist change is too large."
    error is ApiException && error.status == 422 -> "Could not save playlist. Check its name and rules."
    error is ApiException && error.status == 404 -> "Playlist unavailable."
    mutation && playlistWriteUncertain(error) -> "Save unconfirmed. Check saved playlists."
    else -> "Could not finish. Try again."
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDetail(
    client: SpofifyClient, id: String, onOpenTrack: (LibraryItem) -> Unit,
    onPlayQueue: (PlaybackQueue, String?, String?) -> Unit, onShowTrackActions: (LibraryItem) -> Unit,
    onBack: () -> Unit, modifier: Modifier, onMutationPendingChange: (Boolean) -> Unit,
    playback: CatalogPlaybackFeedback,
    onPreparedAction: ((PlaybackQueue, String?, String?, PlaybackListAction, PlaybackInteractionKey?, () -> Boolean) -> Unit)?,
    playbackEntryId: String?, playbackContext: LibraryItem?, onQueueIntent: PlaylistQueueIntent?,
    onUnselectedHigh: ((QueueIntentSelection, dev.spofify.mobile.playback.PlaybackQueueOrigin, () -> Boolean) -> Unit)?,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    browse: PlaylistDetailBrowseMemory,
    navigationCurrent: () -> Boolean,
    sourceEpoch: Any?,
    savedDownloads: SavedDownloadsOwner?,
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
    var readSource by browse.readSource
    var reconcileOnNextRead by remember(id) { mutableStateOf(false) }
    var fetchFailed by browse.fetchFailed
    var metadata by remember(id) { mutableStateOf(false) }
    var deleting by remember(id) { mutableStateOf(false) }
    var removing by remember(id) { mutableStateOf<PlaylistRowSelection?>(null) }
    var occurrenceActions by remember(id) { mutableStateOf<PlaylistRowSelection?>(null) }
    var collectionActions by remember(id) { mutableStateOf<PlaylistCollectionSelection?>(null) }
    val actionLifetime = remember(id, client) { dev.spofify.mobile.ui.RowActionLifetime() }
    DisposableEffect(actionLifetime) { onDispose { actionLifetime.invalidate() } }
    // Only uncommitted captures fence output ABA. Submitted work retains its source guard
    // and the playback owner handles its exact binding/additive successor receipt.
    val outputCapture = playback.interactionKey ?: playback.unselectedQueueOrigin
    val latestOutputCapture by rememberUpdatedState(outputCapture)
    val outputLifetime = remember(id, client, outputCapture) { dev.spofify.mobile.ui.RowActionLifetime() }
    DisposableEffect(outputLifetime) { onDispose { outputLifetime.invalidate() } }
    val latestInteractionKey by rememberUpdatedState(playback.interactionKey)
    val latestSourceEpoch = rememberUpdatedState(sourceEpoch)
    var addingTrack by remember(id) { mutableStateOf<LibraryItem?>(null) }
    var editing by remember(id) { mutableStateOf(false) }
    var copying by remember(id) { mutableStateOf(false) }
    var copySource by remember(id) { mutableStateOf<PlaylistCopySource?>(null) }
    var copyNeedsReview by browse.copyNeedsReview
    var copyReviewed by remember(id) { mutableStateOf(false) }
    var copyReviewMessage by remember(id) { mutableStateOf<String?>(null) }
    var editorDraft by remember(id) { mutableStateOf<PlaylistEditorDraft?>(null) }
    var editorTarget by remember(id) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var snapshot by remember(id) { mutableStateOf<ManualPlaylistSnapshot?>(null) }
    var ordered by remember(id) { mutableStateOf<List<PersonalPlaylistEntry>>(emptyList()) }
    var filterQuery by browse.filterQuery
    var filterEditing by remember { mutableStateOf(false) }
    val filtering = filterQuery.trim().isNotEmpty()
    // Capture the old occurrence before a same-query source update temporarily measures a
    // pending projection. Cached exact-source results avoid that empty measurement on tab return.
    val changedFilterSource = if (summary?.kind == PlaylistKind.DERIVED) browse.derivedProjection.result?.let {
        it.query == filterQuery && it.source !== derivedEntries
    } == true else browse.manualProjection.result?.let { it.query == filterQuery && it.source !== manualEntries } == true
    if (filtering && changedFilterSource && browse.pendingFilteredAnchor == null) {
        val state = browse.filteredScroll
        browse.pendingFilteredAnchor = filterQuery to PlaylistScrollAnchor(state.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == state.firstVisibleItemIndex }?.key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    }
    val manualProjection = residentPlaylistProjection(browse.manualProjection, manualEntries, filterQuery) { it.track }
    val derivedProjection = residentPlaylistProjection(browse.derivedProjection, derivedEntries, filterQuery) { it.track }
    val filteredManual = manualProjection.orEmpty()
    val filteredDerived = derivedProjection.orEmpty()
    val filterPending = if (summary?.kind == PlaylistKind.DERIVED) derivedProjection == null else manualProjection == null
    val listState = if (filtering && snapshot == null) browse.filteredScroll else browse.songsScroll
    LaunchedEffect(manualProjection, derivedProjection, filterQuery) {
        val anchor = browse.pendingFilteredAnchor?.takeIf { it.first == filterQuery }?.second
        if (filtering && !filterPending && anchor != null) {
            val index = if (summary?.kind == PlaylistKind.DERIVED) filteredDerived.indexOfFirst { it.occurrenceId == anchor.key }
                else filteredManual.indexOfFirst { it.id == anchor.key }
            val size = if (summary?.kind == PlaylistKind.DERIVED) filteredDerived.size else filteredManual.size
            browse.filteredScroll.scrollToItem(if (index >= 0) index + 2 else minOf(anchor.index, size + 1), anchor.offset)
            browse.pendingFilteredAnchor = null
        }
    }
    var restoringFilterAnchor by remember { mutableStateOf(false) }
    val latestRestoringFilterAnchor by rememberUpdatedState(restoringFilterAnchor)
    // A query/clear-induced short viewport must never admit a page. Ordinary subsequent
    // scrolling of the unfiltered list restores demand, without eager full materialization.
    LaunchedEffect(listState, filtering) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !filtering && !latestRestoringFilterAnchor) browse.filterDemandPaused.value = false
        }
    }
    // Keep the browsing anchor before focus-driven bring-into-view can move a pinned input
    // to its natural list position. Query callbacks run after that focus request.
    LaunchedEffect(filtering, filterEditing) {
        if (!filtering && !filterEditing) snapshotFlow {
            val state = browse.songsScroll
            PlaylistScrollAnchor(state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }?.key,
                state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }.collect { browse.unfilteredAnchor = it }
    }
    fun changeFilter(query: String) {
        if (query == filterQuery) return
        browse.filterDemandPaused.value = true
        browse.pendingFilteredAnchor = null
        val nextFiltering = query.trim().isNotEmpty()
        if (!filtering && nextFiltering && browse.unfilteredAnchor == null) {
            val state = browse.songsScroll
            browse.unfilteredAnchor = PlaylistScrollAnchor(state.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == state.firstVisibleItemIndex }?.key,
                state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
        filterQuery = query
        if (nextFiltering) scope.launch { browse.filteredScroll.scrollToItem(0) }
        else if (filtering) browse.unfilteredAnchor?.let { anchor ->
            restoringFilterAnchor = true
            scope.launch {
                try {
                    // Let the focused sticky input transfer to the unfiltered viewport first;
                    // its bring-into-view must not overwrite the retained song anchor.
                    withFrameNanos { }
                    val row = if (summary?.kind == PlaylistKind.DERIVED) derivedEntries.indexOfFirst { it.occurrenceId == anchor.key }
                        else manualEntries.indexOfFirst { it.id == anchor.key }
                    browse.songsScroll.scrollToItem(if (row >= 0) row + 2 else anchor.index, anchor.offset)
                    withFrameNanos { }
                } finally { restoringFilterAnchor = false }
            }
        }
    }

    fun refreshAll(verifyBlocked: Boolean = false, resetObservationBudget: Boolean = true) {
        if (resetObservationBudget) browse.observationChecks.intValue = 0
        generation.invalidate(); browseRequests.invalidate(); appendJob?.cancel(); appending = false
        reconcileOnNextRead = verifyBlocked && blocked
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
    LaunchedEffect(id, refresh, personalChanges.playlists, personalChanges.likes) {
        if (browse.initialized.value && readSource?.isCurrent() == true) return@LaunchedEffect
        val reconcilesBlockedWrite = reconcileOnNextRead
        reconcileOnNextRead = false
        val source = PlaylistSourceStamp(client)
        val savedBindingRevision = savedDownloads?.state?.value?.bindingRevision
        appendJob?.cancel(); appending = false; usedCursors = emptySet()
        val ticket = generation.invalidate()
        val request = browseRequests.invalidate()
        val session = client.currentSession()
        val change = client.personalRevision.value.playlists
        if (change != browseChange) browse.observationChecks.intValue = 0
        fun current() = source.isCurrent() && navigationCurrent() && generation.accepts(ticket) && browseRequests.accepts(request) && client.currentSession() === session
        busy = true; stale = true; appendError = null; fetchFailed = false
        try {
            val saved = api.playlist(id)
            if (current()) summary = saved
            val manual = if (saved.kind == PlaylistKind.MANUAL) api.entries(id, limit = 40, offset = 0) else null
            if (manual != null) {
                validatePlaylistEntryBatch(manual, 0, saved.trackCount!!)
                if (!samePlaylistSnapshot(saved, api.playlist(id))) throw playlistBrowseChanged()
            }
            val result = if (saved.kind == PlaylistKind.DERIVED) derived.entries(id, limit = 40) else null
            if (result != null) {
                if (result.revision != saved.revision || result.limit != 40) throw playlistBrowseChanged()
                if (result.current) validatePlaylistEvaluationPrefix(result.items, result.total, result.nextCursor)
            }
            if (change != client.personalRevision.value.playlists) throw playlistBrowseChanged()
            if (current()) {
                browse.publishRead(saved, source, savedBindingRevision)
                entries = manual; evaluation = result
                manualEntries = manual?.items.orEmpty()
                derivedEntries = if (result == null) emptyList() else if (result.current || result.items.isNotEmpty()) result.items
                    else derivedEntries.map { it.copy(selectionToken = null) }
                offset = manual?.items?.size ?: 0; browseChange = change
                stale = result?.current == false
                if (reconcilesBlockedWrite && !stale) blocked = false
                if (!blocked) message = null
                browse.initialized.value = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (current()) { message = "Playlist could not load."; fetchFailed = true; stale = true; browse.initialized.value = true } }
        finally { if (generation.accepts(ticket)) busy = false }
    }
    // Observe only a displayed pending result, with a finite per-snapshot budget even if
    // the server returns inconsistent status and entries. Never evaluate rules or write.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(id, refresh, evaluation, readSource, lifecycle) {
        val pending = evaluation ?: return@LaunchedEffect
        val source = readSource ?: return@LaunchedEffect
        if (pending.current || pending.evaluationState !in setOf(PlaylistEvaluationState.PENDING, PlaylistEvaluationState.STALE)) return@LaunchedEffect
        val ticket = generation.ticket()
        val session = client.currentSession()
        val revision = pending.revision
        fun current() = navigationCurrent() && source.isCurrent() && readSource === source &&
            generation.accepts(ticket) && client.currentSession() === session && !busy && !mutating &&
            !editing && !copying && !blocked && !fetchFailed
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (browse.observationChecks.intValue < 6) {
                delay(2_000)
                if (!current()) return@repeatOnLifecycle
                browse.observationChecks.intValue++
                try {
                    val latest = api.playlist(id)
                    if (!current()) return@repeatOnLifecycle
                    if (latest.revision != revision || latest.evaluationState != pending.evaluationState) {
                        refreshAll(resetObservationBudget = false)
                        return@repeatOnLifecycle
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (current()) { message = "Playlist could not load."; fetchFailed = true }
                    return@repeatOnLifecycle
                }
            }
        }
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
            fun current() = readSource?.isCurrent() == true && navigationCurrent() && browseRequests.accepts(request) && client.currentSession() === session && !stale && snapshot == null
            try {
                if (change != client.personalRevision.value.playlists) throw playlistBrowseChanged()
                if (baseline.kind == PlaylistKind.MANUAL) {
                    if (!samePlaylistSnapshot(baseline, api.playlist(id))) throw playlistBrowseChanged()
                    val next = api.entries(id, limit = 40, offset = requestedOffset)
                    validatePlaylistEntryBatch(next, requestedOffset, baseline.trackCount!!, manualEntries.lastOrNull()?.position ?: -1)
                    val after = api.playlist(id)
                    if (!samePlaylistSnapshot(baseline, after) ||
                        next.items.any { entry -> manualEntries.any { it.id == entry.id } }) throw playlistBrowseChanged()
                    if (current() && change == client.personalRevision.value.playlists) {
                        manualEntries = manualEntries + next.items; entries = next
                        offset = requestedOffset + next.items.size; appendError = null
                    } else if (current()) throw playlistBrowseChanged()
                } else {
                    val retained = evaluation ?: throw playlistBrowseChanged()
                    val next = derived.entries(id, limit = 40, cursor = requestedCursor)
                    if (!next.current || next.revision != baseline.revision || next.fence != retained.fence ||
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
                        stale = true; generation.invalidate(); message = "Playlist changed. Reload to continue."; fetchFailed = true; appendError = null
                    } else appendError = "More songs could not load."
                }
            } finally { if (browseRequests.accepts(request)) appending = false }
        }
    }
    AutomaticListDemand(listState, refresh,
        if (summary?.kind == PlaylistKind.DERIVED) derivedEntries.takeLast(3).map { it.occurrenceId }
        else manualEntries.takeLast(3).map { it.id },
        !filtering && !browse.filterDemandPaused.value && !busy && !appending && !stale && appendError == null && snapshot == null && !editing && !copying &&
            (if (summary?.kind == PlaylistKind.DERIVED) evaluation?.nextCursor != null else offset < (entries?.total ?: 0))) { append() }
    BackHandler {
        if (!busy) when { editing -> editing = false; copying -> copying = false; snapshot != null -> refreshAll(); else -> onBack() }
    }
    val saved = summary
    val savedControlFrame = browse.savedControlFrame.value?.takeIf { it.summary === saved && it.source === readSource }
    fun captureCurrent(rowCurrent: () -> Boolean = { true }): () -> Boolean {
        val ticket = generation.ticket()
        val source = readSource
        val rowSource = sourceLifetime(rowCurrent)
        val session = client.currentSession()
        val change = client.personalRevision.value.playlists
        val revision = summary?.revision
        val fence = evaluation?.fence
        val capturedEpoch = sourceEpoch
        return MusicRowActionGuard(
            { source?.isCurrent() == true && latestSourceEpoch.value == capturedEpoch && navigationCurrent() && rowSource() && actionLifetime.isCurrent() && generation.accepts(ticket) &&
                client.currentSession() === session && session === sourceSession &&
                client.personalRevision.value.playlists == change && change == browseChange &&
                summary?.revision == revision && evaluation?.fence == fence },
            { rowCurrent() && outputLifetime.isCurrent() && latestOutputCapture === outputCapture })
    }
    fun exactSelection(track: LibraryItem? = null, occurrenceId: String? = null,
        token: String? = null, entryId: String? = null): QueueIntentSelection? {
        val current = saved ?: return null
        if (stale || readSource?.isCurrent() != true || track?.playable == false ||
            current.kind == PlaylistKind.DERIVED && evaluation?.current != true) return null
        val context = PlaybackContext(if (current.kind == PlaylistKind.DERIVED) "derived" else "playlist",
            id, current.name, current.revision, if (current.kind == PlaylistKind.DERIVED) evaluation?.fence ?: return null else "")
        return if (track == null) QueueIntentSelection.Collection(context)
            else queueIntentSelection(track.copy(entryId = entryId), context, occurrenceId, token)
    }
    fun submit(selection: QueueIntentSelection?, action: PlaybackListAction,
        key: PlaybackInteractionKey? = playback.interactionKey, binding: QueueIntentBinding? = playback.queueIntentBinding,
        current: () -> Boolean = actionLifetime::isCurrent,
        origin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null) {
        if (selection == null || busy || stale || readSource?.isCurrent() != true || !navigationCurrent() || !current() || !actionLifetime.isCurrent() ||
            client.currentSession() !== sourceSession || client.personalRevision.value.playlists != browseChange) return
        if (key != null && latestInteractionKey !== key) return
        val epoch = PlaybackRequests.epoch
        val revision = saved?.revision
        val session = client.currentSession()
        val change = browseChange
        val sourceTicket = generation.ticket()
        val admittedSource = sourceLifetime(current)
        val live = {
            readSource?.isCurrent() == true && navigationCurrent() && admittedSource() && actionLifetime.isCurrent() && generation.accepts(sourceTicket) &&
                epoch == PlaybackRequests.epoch && !stale && summary?.revision == revision &&
                client.currentSession() === session && client.personalRevision.value.playlists == change
        }
        if (action == PlaybackListAction.QUEUE_HIGH && origin != null) onUnselectedHigh?.invoke(selection, origin, live)
        else onQueueIntent?.invoke(selection, action, key, binding, live)
    }
    addingTrack?.let { track -> AddToPlaylistSheet(client, track, onDismiss = {
        addingTrack = null
    }) }
    if (editing && saved != null && editorDraft != null) {
        PlaylistEditor(client, editorTarget ?: saved, editorDraft!!, onBack = { editing = false }, onSaved = {
            editing = false; editorDraft = null; editorTarget = null; refreshAll()
        }, modifier = modifier, onDiscard = { editing = false; editorDraft = null; editorTarget = null; refreshAll() },
            onMutationPendingChange = onMutationPendingChange)
        return
    }
    if (metadata && saved != null) PlaylistMetadataDialog("Edit playlist", saved.name, saved.description,
        busy = busy, blocked = blocked, message = message, onDismiss = { if (!busy) metadata = false },
        onVerify = { metadata = false; refreshAll(verifyBlocked = true) }, onSave = { name, description -> write {
            summary = api.updatePlaylist(id, name, description, saved.revision); metadata = false; refreshAll()
        } })
    if (copying && saved != null) PlaylistMetadataDialog("Copy playlist", "${saved.name} (copy)", saved.description,
        busy = busy, blocked = blocked || copyNeedsReview || copySource?.isCurrent?.invoke() != true,
        message = copyReviewMessage ?: if (copySource?.isCurrent?.invoke() != true) "Playlist changed. Reopen copy." else message,
        saveLabel = "Create",
        onDismiss = { if (!busy) copying = false }, onVerify = {
            busy = true
            scope.launch {
                try {
                    api.playlists(kind = PlaylistKind.MANUAL, limit = 100)
                    copyReviewMessage = if (copyNeedsReview) "Copy unconfirmed. Review saved playlists."
                        else "Playlist changed. Reopen copy."
                    copyReviewed = true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { copyReviewMessage = "Could not check saved copies. Try again." }
                finally { busy = false }
            }
        }, onSave = { name, description ->
            val captured = copySource
            if (captured != null && captured.isCurrent() && !stale && !copyNeedsReview) write(onFailure = { error ->
                if (playlistWriteUncertain(error)) { copyNeedsReview = true; copyReviewed = false }
            }) {
                derived.copy(id, name, description, captured.revision, captured.fence)
                copying = false; message = null; refreshAll()
            }
        })
    if (deleting && saved != null) AlertDialog(onDismissRequest = { if (!busy) deleting = false }, title = { Text("Delete playlist?") },
        text = { Column { Text("Delete “${saved.name}”? Music files are kept."); message?.let { Text(it) } } },
        confirmButton = { TextButton(enabled = !busy && !blocked, onClick = { write { api.deletePlaylist(id, saved.revision); onBack() } }) { Text("Delete playlist") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleting = false }) { Text("Cancel") } })
    if (removing != null && saved != null) AlertDialog(onDismissRequest = { if (!busy) removing = null }, title = { Text("Remove song from playlist?") },
        text = { Column { Text(removing!!.track.name); message?.let { Text(it) } } },
        confirmButton = { TextButton(enabled = !busy && !blocked && !stale && removing!!.isCurrent(), onClick = {
            val selection = removing ?: return@TextButton
            if (!stale && selection.isCurrent()) write {
                summary = api.removeEntry(id, selection.entryId!!, saved.revision); removing = null; refreshAll()
            }
        }) { Text("Remove") } }, dismissButton = { TextButton(enabled = !busy, onClick = { removing = null }) { Text("Cancel") } })

    fun play(selectionToken: String? = null, trackId: String? = null, entryId: String? = null,
        action: PlaybackListAction? = null, expectedKey: PlaybackInteractionKey? = null, isCurrent: () -> Boolean = { true }) {
        if (busy || stale || saved == null || readSource?.isCurrent() != true || !navigationCurrent() || !isCurrent() || client.currentSession() !== sourceSession ||
            client.personalRevision.value.playlists != browseChange) return
        if (onQueueIntent != null) {
            val row = if (selectionToken != null) derivedEntries.firstOrNull { it.selectionToken == selectionToken } else null
            val manual = if (entryId != null) manualEntries.firstOrNull { it.id == entryId } else null
            if (selectionToken != null && row == null || entryId != null && manual == null) return
            submit(exactSelection(row?.track ?: manual?.track, row?.occurrenceId, row?.selectionToken, manual?.id),
                action ?: PlaybackListAction.PLAY_NOW, expectedKey ?: playback.interactionKey, current = isCurrent)
            return
        }
        if (action != null && (onPreparedAction == null || expectedKey == null || latestInteractionKey !== expectedKey)) return
        val result = evaluation
        val sourceTicket = generation.ticket()
        val sourceCurrent = sourceLifetime(captureCurrent(isCurrent))
        val request = PlaylistPlaybackRequest(playbackRequests) {
            sourceCurrent() && navigationCurrent() && generation.accepts(sourceTicket) && actionLifetime.isCurrent() && client.currentSession() === sourceSession
        }; busy = true
        scope.launch {
            try {
                if (saved.kind == PlaylistKind.DERIVED && result != null) {
                    val queue = derived.playback(id, saved.revision, result.fence, selectionToken)
                    request.deliver {
                        if (!sourceCurrent()) return@deliver
                        message = null
                        if (action == null) onPlayQueue(queue.queue, null, queue.startOccurrenceId)
                        else if (latestInteractionKey === expectedKey && sourceCurrent()) onPreparedAction?.invoke(queue.queue, null,
                            queue.startOccurrenceId, action, expectedKey, sourceCurrent)
                    }
                } else if (saved.kind == PlaylistKind.MANUAL) {
                    val queue = api.playback(id)
                    request.deliver {
                        if (!sourceCurrent()) return@deliver
                        if (entryId != null && queue.items.none { it.entryId == entryId }) message = "Could not play this song. Reload playlist."
                        else if (action == null) onPlayQueue(queue.queue, trackId, entryId)
                        else if (latestInteractionKey === expectedKey && sourceCurrent()) onPreparedAction?.invoke(queue.queue, trackId, entryId, action, expectedKey, sourceCurrent)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { request.deliver { if (sourceCurrent()) { message = playlistFailure(e); stale = true } } }
            finally { request.finish { if (generation.accepts(sourceTicket)) busy = false } }
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
            // Successful Like publishes personalRevision; the single bounded read above consumes it.
            onPersonalChange = {},
            playbackEnabled = playable,
            onPlayNow = { perform() },
            onQueue = if (preparedEnabled) ({ perform(selection.track.defaultQueueAction()) }) else null,
            onPlayNext = if (preparedEnabled) ({ perform(PlaybackListAction.PLAY_NEXT) }) else null,
            onOpenItem = { occurrenceActions = null; onOpenTrack(it) },
            onRemove = if (saved?.capabilities?.editEntries == true && selection.entryId != null) ({
                if (!busy && !blocked && !stale && selection.isCurrent()) {
                    occurrenceActions = null; removing = selection
                }
            }) else null,
            removeEnabled = !busy && !blocked && !stale && selection.isCurrent(), isCurrent = selection.isCurrent,
            artwork = artwork)
    }
    collectionActions?.let { captured ->
        val managementEnabled = !busy && !blocked && captured.isCurrent()
        val playbackEnabled = !busy && !stale && saved?.capabilities?.play == true &&
            captured.selection != null && captured.isCurrent() &&
            (captured.key == null || latestInteractionKey === captured.key)
        val canPlay = onQueueIntent != null && saved?.capabilities?.play == true
        val collectionItem = LibraryItem("playlist", id, saved?.name ?: "Playlist")
        val playlist = saved
        fun perform(action: PlaybackListAction) {
            collectionActions = null
            submit(captured.selection, action, captured.key, captured.binding, captured.isCurrent,
                captured.unselectedOrigin.takeIf { action == PlaybackListAction.PLAY_NOW })
        }
        SongActionSheet(onDismiss = { collectionActions = null }) {
            PlaylistCollectionActionsContent(
                item = collectionItem,
                onPlayNow = if (canPlay) ({ perform(PlaybackListAction.PLAY_NOW) }) else null,
                onPlayNext = if (canPlay) ({ perform(PlaybackListAction.PLAY_NEXT) }) else null,
                onQueue = if (canPlay) ({ perform(collectionItem.defaultQueueAction()) }) else null,
                playbackEnabled = playbackEnabled, managementEnabled = managementEnabled,
                copyEnabled = managementEnabled && !stale && !copyNeedsReview,
                verifyEnabled = !busy, allowNewCopyEnabled = !busy && !stale,
                // Saved collections have no library artwork endpoint. Keep the local menu
                // placeholder rather than handing a playlist kind to the catalog renderer.
                onEditDetails = if (playlist?.capabilities?.editMetadata == true)
                    ({ collectionActions = null; metadata = true }) else null,
                onEditRules = if (playlist != null && playlist.kind == PlaylistKind.DERIVED && playlist.capabilities.editDefinition) ({
                    val source = PlaylistSourceStamp(client)
                    val ticket = generation.ticket()
                    collectionActions = null; busy = true
                    scope.launch {
                        try {
                            val rules = derived.definition(id)
                            if (!source.isCurrent() || !generation.accepts(ticket) || !actionLifetime.isCurrent()) return@launch
                            if (rules.revision != playlist.revision) { stale = true; message = "Playlist changed. Reload before editing rules."; fetchFailed = true }
                            else {
                                if (editorDraft == null) { editorDraft = PlaylistEditorDraft(playlist, rules.definition); editorTarget = playlist }
                                else if (editorTarget?.revision != rules.revision) {
                                    editorDraft!!.blocked = true
                                    editorDraft!!.message = "Playlist changed. Review or discard this draft."
                                }
                                editing = true
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (source.isCurrent() && generation.accepts(ticket)) message = playlistFailure(e) }
                        finally { if (generation.accepts(ticket)) busy = false }
                    }
                }) else null,
                onMakeEditableCopy = if (playlist != null && playlist.kind == PlaylistKind.DERIVED && playlist.capabilities.copyToManual) ({
                    val result = evaluation
                    if (result?.current == true) {
                        copySource = PlaylistCopySource(playlist.revision, result.fence, captureCurrent())
                        collectionActions = null; copying = true; copyReviewMessage = null
                    }
                }) else null,
                onVerifySavedCopies = if (playlist?.kind == PlaylistKind.DERIVED && copyNeedsReview)
                    ({ collectionActions = null; copying = true }) else null,
                onAllowNewCopy = if (playlist?.kind == PlaylistKind.DERIVED && copyNeedsReview && copyReviewed) ({
                    collectionActions = null; copyNeedsReview = false; copyReviewMessage = null
                    message = null
                }) else null,
                onReorder = if (playlist != null && playlist.kind != PlaylistKind.DERIVED && playlist.capabilities.editEntries && snapshot == null) ({
                    collectionActions = null; busy = true; generation.invalidate()
                    browseRequests.invalidate(); appendJob?.cancel(); appending = false
                    scope.launch {
                        try { val complete = api.completeSnapshot(id); snapshot = complete; ordered = complete.entries; summary = complete.summary }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = playlistFailure(e); blocked = true }
                        finally { busy = false }
                    }
                }) else null,
                onDelete = if (playlist?.capabilities?.delete == true)
                    ({ collectionActions = null; deleting = true }) else null)
        }
    }
    val renderedSourceTicket = generation.ticket()
    val renderedCurrent = captureCurrent()
    val defaultBringIntoView = LocalBringIntoViewSpec.current
    val latestFilterEditing by rememberUpdatedState(filterEditing)
    var filterInputBounds by remember { mutableStateOf<Rect?>(null) }
    var filterViewportBounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(filterEditing, filterInputBounds, filterViewportBounds, listState) {
        val input = filterInputBounds ?: return@LaunchedEffect
        val viewport = filterViewportBounds ?: return@LaunchedEffect
        if (filterEditing && input.height <= viewport.height) {
            val distance = when {
                input.bottom > viewport.bottom -> input.bottom - viewport.bottom
                input.top < viewport.top -> input.top - viewport.top
                else -> 0f
            }
            if (distance != 0f) {
                restoringFilterAnchor = true
                try { listState.scrollBy(distance) }
                finally { restoringFilterAnchor = false }
            }
        }
    }
    val latestFilterInputBounds by rememberUpdatedState(filterInputBounds)
    val latestFilterViewportBounds by rememberUpdatedState(filterViewportBounds)
    val filterBringIntoView = remember(defaultBringIntoView) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // The focused input is pinned and already visible. Relocating its natural
                // (offscreen) item position would erase the source/filtered song anchors.
                val input = latestFilterInputBounds
                val viewport = latestFilterViewportBounds
                val inputFullyVisible = input != null && viewport != null && input.top >= viewport.top &&
                    input.bottom <= viewport.bottom && input.left >= viewport.left && input.right <= viewport.right
                return if (latestFilterEditing && inputFullyVisible) 0f
                    else defaultBringIntoView.calculateScrollDistance(offset, size, containerSize)
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides filterBringIntoView) {
    LazyColumn(modifier.fillMaxSize().onGloballyPositioned {
        filterViewportBounds = Rect(it.positionInWindow(), it.size.toSize())
    }, state = listState,
        contentPadding = PaddingValues(horizontal = MusicListLayout.horizontalPadding, vertical = 16.dp),
        verticalArrangement = MusicListLayout.arrangement) {
        item {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val count = if (saved?.kind == PlaylistKind.DERIVED) evaluation?.total?.takeIf { !stale } else saved?.trackCount
                PlaylistDetailIdentity(saved?.name ?: "Playlist", saved?.description, count,
                    if (saved?.kind == PlaylistKind.DERIVED) derivedEntries.firstOrNull()?.track else manualEntries.firstOrNull()?.track,
                    artwork)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (saved?.capabilities?.play == true) Button(onClick = { play() }, enabled = !busy && !stale) { Text("Play") }
                    IconButton(onClick = {
                        collectionActions = PlaylistCollectionSelection(exactSelection(), playback.interactionKey,
                            playback.queueIntentBinding, captureCurrent(), playback.unselectedQueueOrigin)
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, "More actions for ${saved?.name ?: "playlist"}")
                    }
                }
                if (savedDownloads != null && saved != null && savedControlFrame != null) {
                    val sourceCurrent = sourceLifetime(captureCurrent())
                    val downloadCurrent = {
                        browse.summary.value === savedControlFrame.summary && browse.readSource.value === savedControlFrame.source &&
                            savedControlFrame.source.isCurrent() && sourceCurrent()
                    }
                    SavedCollectionControl(savedDownloads, dev.spofify.mobile.core.saved.SavedTarget(
                        if (saved.kind == PlaylistKind.DERIVED) dev.spofify.mobile.core.saved.SavedTargetKind.DERIVED_PLAYLIST
                        else dev.spofify.mobile.core.saved.SavedTargetKind.MANUAL_PLAYLIST, id), saved.name,
                        actionsEnabled = downloadCurrent(), isCurrent = downloadCurrent,
                        expectedBindingRevision = savedControlFrame.bindingRevision)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!busy && fetchFailed) TextButton(onClick = { refreshAll() }) { Text("Retry loading playlist") }
                if (!busy && blocked) TextButton(onClick = { refreshAll(verifyBlocked = true) }) { Text("Verify saved playlist") }
                if (stale && saved?.kind == PlaylistKind.DERIVED && message == null) Text(playlistCountLabel(saved, evaluation))
                if (copyNeedsReview) Text("Copy unconfirmed.")
                if (!busy && !stale && (if (saved?.kind == PlaylistKind.DERIVED) evaluation?.total == 0L else saved?.trackCount == 0L)) Text("No songs in this playlist.")
            }
            if (snapshot != null) {
                Row {
                    Button(enabled = !busy && !blocked, onClick = { val complete = snapshot!!; write {
                        summary = api.reorder(complete, ordered.map { it.id }); snapshot = null; refreshAll()
                    } }) { Text("Save order") }
                    TextButton(enabled = !busy, onClick = { refreshAll() }) { Text("Cancel reorder") }
                }
            }
        }
        if (snapshot == null) stickyHeader(key = "playlist-filter") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                PlaylistFilterField(filterQuery, modifier = Modifier.onGloballyPositioned {
                    filterInputBounds = Rect(it.positionInWindow(), it.size.toSize())
                }, onEditingChanged = { filterEditing = it }, onQueryChange = ::changeFilter)
                val matching = if (saved?.kind == PlaylistKind.DERIVED) filteredDerived.size else filteredManual.size
                if (!filterPending) playlistFilterLabel(matching, filterQuery)?.let { label ->
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp).testTag("playlist-filter-result"))
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
            items(filteredDerived, key = { it.occurrenceId }) { entry ->
                PlaylistTrackRow(entry.track, onOpenTrack, { track, rowCurrent ->
                    val current = captureCurrent(rowCurrent)
                    if (current()) occurrenceActions = PlaylistRowSelection(track, entry.selectionToken, interactionKey = playback.interactionKey, isCurrent = current,
                        queueSelection = exactSelection(track, entry.occurrenceId, entry.selectionToken), binding = playback.queueIntentBinding,
                        unselectedOrigin = playback.unselectedQueueOrigin) },
                    enabled = !busy && !stale && saved.capabilities.play && entry.selectionToken != null,
                    onPlay = { current -> play(selectionToken = entry.selectionToken, isCurrent = captureCurrent(current)) }, actionsEnabled = !busy,
                    artwork = artwork, playback = playback, occurrenceBinding = listOf(id, sourceEpoch, renderedSourceTicket, saved.revision, evaluation?.fence, entry),
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
                if (!filtering) AppendStatus(appending, if (appending) null else appendError, onRetry = { append() }, loadingLabel = "Loading more songs")
                if (!filtering && !busy && !stale && derivedEntries.isEmpty()) Text("No songs match these rules.")
            }
        } else {
            items(filteredManual, key = { it.id }) { entry ->
                    PlaylistTrackRow(entry.track, onOpenTrack, { track, isCurrent -> occurrenceActions = PlaylistRowSelection(track, entryId = entry.id, interactionKey = playback.interactionKey, isCurrent = captureCurrent(isCurrent),
                        queueSelection = exactSelection(track, entryId = entry.id), binding = playback.queueIntentBinding,
                        unselectedOrigin = playback.unselectedQueueOrigin) },
                        enabled = !busy && !stale && saved?.capabilities?.play == true,
                        onPlay = { current -> play(trackId = entry.track.id, entryId = entry.id, isCurrent = captureCurrent(current)) },
                        actionsEnabled = !busy, artwork = artwork, playback = playback,
                        occurrenceBinding = listOf(id, sourceEpoch, renderedSourceTicket, saved?.revision, entry),
                        currentOccurrence = playlistOccurrenceIsCurrent(id, PlaylistKind.MANUAL, entry.id,
                            entry.track.id, playback.trackId, playbackEntryId, playbackContext),
                        onContextual = if (onQueueIntent != null || onPreparedAction != null) ({ expectedKey, rowCurrent -> play(trackId = entry.track.id,
                            entryId = entry.id, action = PlaybackListAction.QUEUE_HIGH, expectedKey = expectedKey, isCurrent = { renderedCurrent() && rowCurrent() }) }) else null,
                        onUnselectedHigh = if (onUnselectedHigh != null) ({ origin, rowCurrent ->
                            submit(exactSelection(entry.track, entryId = entry.id), PlaybackListAction.QUEUE_HIGH,
                                null, null, { renderedCurrent() && rowCurrent() }, origin)
                        }) else null)
            }
            item {
                if (!filtering) AppendStatus(appending, if (appending) null else appendError, onRetry = { append() }, loadingLabel = "Loading more songs")
                if (!filtering && !busy && !stale && manualEntries.isEmpty()) Text("No songs yet.")
            }
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

/** Only adapts exact playlist occurrence actions; all layout and gestures belong to MusicRow. */
@Composable
internal fun PlaylistTrackRow(track: LibraryItem, onOpenTrack: (LibraryItem) -> Unit,
    onShowTrackActions: (LibraryItem, () -> Boolean) -> Unit, enabled: Boolean, onPlay: (() -> Boolean) -> Unit, actionsEnabled: Boolean = true,
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(), occurrenceBinding: Any = track,
    currentOccurrence: Boolean = false,
    onContextual: ((PlaybackInteractionKey, () -> Boolean) -> Unit)? = null,
    onUnselectedHigh: ((dev.spofify.mobile.playback.PlaybackQueueOrigin, () -> Boolean) -> Unit)? = null,
    artwork: @Composable (LibraryItem, Modifier) -> Unit = { _, artModifier -> Icon(Icons.Default.MusicNote, null, artModifier) },
) {
    MusicRow(track, onOpenTrack, { _, _ -> }, {}, {}, artwork,
        playback = playback.copy(
            onContextual = onContextual?.let { action -> { _, key, current -> action(key, current) } },
            onUnselectedHigh = onUnselectedHigh?.let { action -> { _, origin, current -> action(origin, current) } }),
        rowIdentity = occurrenceBinding, currentOccurrence = currentOccurrence,
        playbackEnabled = enabled, actionsEnabled = actionsEnabled,
        onShowScopedActions = onShowTrackActions, onPlayScoped = { _, current -> onPlay(current) })
}

@Composable
internal fun PlaylistMetadataDialog(title: String, initialName: String, initialDescription: String,
    busy: Boolean, blocked: Boolean, message: String?, onDismiss: () -> Unit, onVerify: () -> Unit,
    onSave: (String, String) -> Unit, saveLabel: String = "Save") {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    val invalid = playlistMetadataError(name, description)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, enabled = !busy, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, enabled = !busy, maxLines = 4)
            invalid?.let { Text(it) }; message?.let { Text(it) }
        }
    }, confirmButton = {
        if (blocked) TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = onVerify) { Text("Check") }
        else TextButton(enabled = !busy && invalid == null, modifier = Modifier.heightIn(min = 48.dp),
            onClick = { onSave(name.trim(), description) }) { Text(saveLabel) }
    }, dismissButton = { TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = onDismiss) { Text("Cancel") } })
}
