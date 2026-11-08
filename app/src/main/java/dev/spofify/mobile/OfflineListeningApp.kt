package dev.spofify.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.QueueIntentIntent
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.feature.library.CatalogPlaybackFeedback
import dev.spofify.mobile.feature.library.MusicRow
import dev.spofify.mobile.feature.playlists.SavedDownloadsOwner
import dev.spofify.mobile.feature.playlists.savedTarget
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.feature.player.NativeMiniPlayer
import dev.spofify.mobile.feature.player.NativeNowPlaying
import dev.spofify.mobile.feature.player.NativeNowPlayingDialog
import dev.spofify.mobile.ui.CompactNavigation
import dev.spofify.mobile.ui.CompactNavigationItem
import dev.spofify.mobile.ui.ListeningChrome
import dev.spofify.mobile.ui.theme.AppFontLicenseDialog
import kotlinx.coroutines.CancellationException
import java.util.Locale

private class OfflineRowSelection(val snapshot: OfflineSavedCollection, val row: LibraryItem,
    val isCurrent: () -> Boolean, val queueKey: dev.spofify.mobile.playback.PlaybackInteractionKey?)

/** A local-library shell has no invented Session and never constructs API/artwork readers. */
@Composable
internal fun OfflineListeningApp(model: LibraryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val observedAccount by model.graph.localEligibilityState.collectAsStateWithLifecycle()
    val account = observedAccount
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error, state.notice) {
        (state.error ?: state.notice)?.let { snackbar.showSnackbar(it) }
    }
    val player by model.playback.state.collectAsStateWithLifecycle()
    val cacheSettings by model.graph.songCache.settings.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val feedback = remember { dev.spofify.mobile.ui.PlaybackFeedbackPresentation() }
    LaunchedEffect(player.feedback?.id) {
        player.feedback?.let {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) feedback.offer(it)
            else model.playback.consumeFeedback(it.id)
        }
    }
    dev.spofify.mobile.ui.PlaybackFeedbackTimer(feedback) { model.playback.consumeFeedback(it) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                feedback.current?.let { model.playback.consumeFeedback(it.id) }
                feedback.discardSuccess()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            feedback.current?.let { model.playback.consumeFeedback(it.id) }
        }
    }
    LaunchedEffect(player.error) { player.error?.let { snackbar.showSnackbar(it) } }
    var settings by rememberSaveable { mutableStateOf(false) }
    var destination by remember(account) { mutableStateOf<SettingsDestination?>(null) }
    var selected by remember(account) { mutableStateOf<OfflineSavedCollection?>(null) }
    var expanded by remember(account) { mutableStateOf(false) }
    var license by remember(account) { mutableStateOf(false) }
    var more by remember(account) { mutableStateOf<OfflineRowSelection?>(null) }
    var collections by remember(account) { mutableStateOf<List<OfflineSavedCollection>>(emptyList()) }
    var loading by remember(account) { mutableStateOf(account != null) }
    var failure by remember(account) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val library by model.graph.offlineLibraryAccess.collectAsStateWithLifecycle()
    val libraryRevision = library?.revision?.collectAsStateWithLifecycle()?.value ?: 0L
    LaunchedEffect(account, library, reload, libraryRevision) {
        if (account == null) return@LaunchedEffect
        loading = true
        try {
            val result = model.graph.offlineCollections(account)
            if (model.graph.localPlaybackIsCurrent(account)) {
                collections = result
                selected = selected?.let { prior -> result.firstOrNull {
                    it.target.kind == prior.target.kind && it.target.id == prior.target.id
                } }
            }
            failure = false
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = true
        } finally { loading = false }
    }
    BackHandler(!expanded && (selected != null || destination != null || license)) {
        when { license -> license = false; destination != null -> destination = null; else -> selected = null }
    }
    val art: @Composable (LibraryItem, Modifier) -> Unit = { _, modifier ->
        Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null) }
    }
    val origin = remember(account, selected, settings, destination) { dev.spofify.mobile.ui.RowActionLifetime() }
    DisposableEffect(origin) { onDispose { origin.invalidate() } }
    fun play(snapshot: OfflineSavedCollection, start: LibraryItem? = null) {
        val eligible = account ?: return
        if (!model.graph.localPlaybackIsCurrent(eligible)) return
        val rows = snapshot.availableRows.map { it.copy(hasAudio = true, libraryPath = null) }
        if (rows.isEmpty()) return
        model.playback.playLocal(rows, eligible, mediaReferences = snapshot.mediaReferences,
            startEntryId = start?.entryId, context = snapshot.target,
            isCurrent = { origin.isCurrent() && model.graph.localPlaybackIsCurrent(eligible) })
    }
    fun enqueue(snapshot: OfflineSavedCollection, row: LibraryItem, key: PlaybackInteractionKey,
        intent: QueueIntentIntent, isCurrent: () -> Boolean) {
        val eligible = account ?: return
        model.playback.enqueueLocal(listOf(row.copy(hasAudio = true, libraryPath = null)), eligible,
            mediaReferences = snapshot.mediaReferences, intent = intent, isCurrent = { origin.isCurrent() && isCurrent() &&
                model.playback.state.value.interactionKey === key && model.graph.localPlaybackIsCurrent(eligible) })
    }
    val openLocal: (LibraryItem) -> Unit = { item ->
        collections.firstOrNull { it.target.kind == item.kind && it.target.id == item.id }?.let {
            focus.clearFocus(); keyboard?.hide()
            selected = it; settings = false; destination = null; expanded = false
        }
    }
    val canOpenLocal: (LibraryItem) -> Boolean = { item ->
        collections.any { it.target.kind == item.kind && it.target.id == item.id }
    }
    ListeningChrome(
        chromeHidden = false, connectionActions = null, snackbarHost = {
            Column { SnackbarHost(snackbar); dev.spofify.mobile.ui.PlaybackFeedbackHost(feedback.current) }
        },
        miniPlayer = { NativeMiniPlayer(player, model.playback, {
            focus.clearFocus(); keyboard?.hide(); expanded = true
        }, art, Modifier.fillMaxWidth()) },
        navigation = {
            CompactNavigation {
                CompactNavigationItem(!settings, { focus.clearFocus(); keyboard?.hide(); settings = false; destination = null },
                    icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }, label = "Saved")
                CompactNavigationItem(settings, { focus.clearFocus(); keyboard?.hide(); settings = true; destination = null },
                    icon = { Icon(Icons.Default.Settings, null) }, label = "Settings")
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
        key(account) { when {
            settings && destination == SettingsDestination.ACCOUNT -> ProfileScreen(account?.username.orEmpty(),
                account?.origin.orEmpty(), state.busy || account == null, {
                    if (account != null && model.graph.localEligibility === account) model.logout()
                }, modifier)
            settings && destination == SettingsDestination.CREDITS -> CreditsScreen({ license = true }, modifier)
            settings -> SettingsScreen(account?.username.orEmpty(), account?.origin.orEmpty(),
                { focus.clearFocus(); keyboard?.hide(); destination = it }, modifier, offlineMode = true, onOfflineModeChanged = model::setOfflineMode,
                cacheSettings = cacheSettings, onSaveCacheSettings = { value ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { model.graph.songCache.updateSettings(value) }
                })
            else -> OfflineSavedLibrary(collections, selected, loading, failure, {
                account?.let(model.graph::retryOfflineCollections)
                reload++
            },
                onOpen = { selected = it }, onPlay = ::play, artwork = art, modifier = modifier,
                retentionControl = { snapshot -> snapshot.target.savedTarget()?.let { target ->
                    OfflineSavedRetentionControl(model.graph.savedDownloads, target) {
                        origin.isCurrent() && account != null && model.graph.localPlaybackIsCurrent(account)
                    }
                } },
                queueKey = player.interactionKey,
                onQueueHigh = { snapshot, row, key, current -> enqueue(snapshot, row, key, QueueIntentIntent.HIGH, current) },
                onMore = { snapshot, row, rowCurrent ->
                    more = OfflineRowSelection(snapshot, row, { origin.isCurrent() && rowCurrent() }, player.interactionKey)
                })
        } }
    }
    more?.let { selection ->
        val valid = selection.isCurrent() && account != null && model.graph.localPlaybackIsCurrent(account)
        dev.spofify.mobile.ui.SongActionSheet({ more = null }) {
            dev.spofify.mobile.ui.SongActionLayout(selection.row, artwork = art) {
                dev.spofify.mobile.ui.SongActionRow(if (selection.snapshot.playableInFull) "Play now" else "Play available",
                    Icons.Default.PlayArrow, enabled = valid) {
                    if (selection.isCurrent()) play(selection.snapshot, selection.row)
                    more = null
                }
                val canQueue = valid && selection.queueKey != null && player.interactionKey === selection.queueKey
                OfflineSavedQueueActions(canQueue) { intent ->
                    selection.queueKey?.let { enqueue(selection.snapshot, selection.row, it, intent, selection.isCurrent) }
                    more = null
                }
            }
        }
    }
    if (license) AppFontLicenseDialog { license = false }
    if (expanded && player.item != null) NativeNowPlayingDialog({ expanded = false }) { presentation ->
        NativeNowPlaying(player, model.playback, false, false, false, {}, {}, {}, openLocal, art,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            presentation = presentation, selectedOutputLabel = "This device",
            onlineActionsAvailable = false, canOpenItem = canOpenLocal,
            queueFeedback = { dev.spofify.mobile.ui.PlaybackFeedbackHost(feedback.current) })
    }
}

private class OfflineUnsaveRequest(val revision: Long, val isCurrent: () -> Boolean)

/** Local removal uses the same saved owner and its rendered-binding fence; never a server call. */
@Composable
internal fun OfflineSavedRetentionControl(owner: SavedDownloadsOwner, target: SavedTarget,
    isCurrent: () -> Boolean) {
    val library by owner.state.collectAsStateWithLifecycle()
    var pending by remember(owner, target) { mutableStateOf<OfflineUnsaveRequest?>(null) }
    val revision = library.bindingRevision
    val current = owner.captureAction(revision)
    if (library.collections.any { it.target == target }) key(revision) {
        TextButton(onClick = {
            if (current() && isCurrent()) pending = OfflineUnsaveRequest(revision) { current() && isCurrent() }
        }, enabled = current() && isCurrent(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Unsave") }
    }
    pending?.let { request ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Unsave?") },
            confirmButton = { TextButton(onClick = {
                pending = null
                if (request.isCurrent()) owner.unsave(target, request.revision)
            }, enabled = request.isCurrent()) { Text("Unsave") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}

@Composable
internal fun OfflineSavedQueueActions(enabled: Boolean, onEnqueue: (QueueIntentIntent) -> Unit) {
    dev.spofify.mobile.ui.SongActionRow("Queue Low", Icons.Default.PlaylistAdd, enabled) { onEnqueue(QueueIntentIntent.LOW) }
    dev.spofify.mobile.ui.SongActionRow("Queue High", Icons.Default.PlaylistAdd, enabled) { onEnqueue(QueueIntentIntent.HIGH) }
    dev.spofify.mobile.ui.SongActionRow("Play next", Icons.Default.SkipNext, enabled) { onEnqueue(QueueIntentIntent.NEXT) }
}

@Composable
internal fun OfflineSavedLibrary(
    collections: List<OfflineSavedCollection>, selected: OfflineSavedCollection?, loading: Boolean, failure: Boolean,
    onRetry: () -> Unit, onOpen: (OfflineSavedCollection) -> Unit,
    onPlay: (OfflineSavedCollection, LibraryItem?) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit, modifier: Modifier,
    onMore: (OfflineSavedCollection, LibraryItem, () -> Boolean) -> Unit,
    queueKey: PlaybackInteractionKey? = null,
    onQueueHigh: ((OfflineSavedCollection, LibraryItem, PlaybackInteractionKey, () -> Boolean) -> Unit)? = null,
    retentionControl: @Composable (OfflineSavedCollection) -> Unit = {},
) {
    var query by rememberSaveable(selected?.target?.kind, selected?.target?.id) { mutableStateOf("") }
    val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
    fun matches(item: LibraryItem): Boolean {
        val text = "${item.name} ${item.subtitle}".lowercase(Locale.ROOT)
        return words.all(text::contains)
    }
    LazyColumn(modifier.testTag("offline-library"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text(selected?.target?.name ?: "Saved", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(16.dp)) }
        item { OutlinedTextField(query, { query = it }, label = { Text("Filter") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("offline-filter")) }
        if (selected != null) item { retentionControl(selected) }
        if (selected != null && selected.availableRows.isNotEmpty()) item {
            TextButton({ onPlay(selected, null) }, Modifier.heightIn(min = 48.dp)) {
                Text(if (selected.playableInFull) "Play" else "Play available")
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (failure) item { TextButton(onRetry) { Text("Retry") } }
        else if (selected == null) {
            if (collections.isEmpty()) item { Text("No saved music", Modifier.padding(16.dp)) }
            items(collections.filter { matches(it.target) }, key = { "${it.target.kind}:${it.target.id}" }) { snapshot ->
                MusicRow(snapshot.target, { onOpen(snapshot) }, { _, _ -> }, {}, {}, artwork,
                    actionsEnabled = false, playbackEnabled = false)
            }
        } else items(selected.rows.filter(::matches), key = { it.entryId!! }) { row ->
            val available = row.entryId in selected.availableEntryIds
            MusicRow(row.copy(hasAudio = available, libraryPath = null), {},
                { _, _ -> onPlay(selected, row) }, {}, {}, artwork,
                rowIdentity = row.entryId!!, actionsEnabled = available,
                playback = CatalogPlaybackFeedback(interactionKey = queueKey,
                    onContextual = onQueueHigh?.let { action -> { item, key, current -> action(selected, item, key, current) } }),
                onShowScopedActions = { item, current -> onMore(selected, item, current) },
                playbackEnabled = selected.playableInFull && available,
                bodyActionsEnabled = selected.playableInFull && available)
        }
    }
}
