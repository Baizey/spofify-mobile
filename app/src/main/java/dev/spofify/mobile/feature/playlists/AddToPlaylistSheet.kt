package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import dev.spofify.mobile.ui.AutomaticListDemand
import dev.spofify.mobile.ui.AppendStatus
import kotlinx.coroutines.isActive
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.ApiJson
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PersonalCollectionsApi
import dev.spofify.mobile.core.PersonalPlaylistPage
import dev.spofify.mobile.core.PersonalPlaylistSummary
import dev.spofify.mobile.core.PlaylistKind
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Account-local manual targets only. Creating a playlist never implicitly adds the track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(client: SpofifyClient, track: LibraryItem, onDismiss: () -> Unit) {
    // Neither a previous client's drafts nor its in-flight results may enter a new session.
    key(client, track.id) {
        val scope = rememberCoroutineScope()
        val state = remember(client, track.id) {
            AddToPlaylistState(PersonalCollectionsApi(client), track, scope,
                personalRevision = { client.personalRevision.value.playlists }, currentSession = { client.currentSession() })
        }
        // Read during composition so an otherwise idle revision emission schedules the fence.
        val playlistRevision = client.personalRevision.collectAsState().value.playlists
        SideEffect { state.checkFreshness(playlistRevision) }
        val listState = rememberLazyListState()
        DisposableEffect(state) { onDispose { state.dispose() } }
        AutomaticListDemand(listState, state.targetIdentity,
            state.targets?.items.orEmpty().takeLast(3).map { it.id }, state.canLoadMoreTargets,
            state::moreTargets)
        AutomaticListDemand(listState, state.verificationIdentity,
            state.verifiedTargets?.items.orEmpty().takeLast(3).map { "verified-${it.id}" },
            state.canLoadMoreVerification, state::moreVerification)
        val sheet = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !state.mutating },
        )
        LaunchedEffect(state) { state.loadTargets(0) }
        LaunchedEffect(state.finished) { if (state.finished) onDismiss() }
        ModalBottomSheet(
            onDismissRequest = { if (!state.mutating) onDismiss() },
            sheetState = sheet,
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = !state.mutating,
                shouldDismissOnClickOutside = !state.mutating,
            ),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f)) {
                Text("Add to playlist", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            Text(track.name, style = MaterialTheme.typography.titleMedium)
                            Text("Manual playlists only. Each add saves a separate occurrence, including duplicates.")
                        }
                    }
                    if (state.mutating || (state.reading && state.targets == null && state.verifiedTargets == null)) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
                    if (state.message.isNotEmpty()) item {
                        Text(state.message, modifier = Modifier.padding(horizontal = 24.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (state.creating) item {
                        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.name, onValueChange = { state.name = it },
                                label = { Text("Name") }, singleLine = true,
                                enabled = !state.busy && state.blocked == null, modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.description, onValueChange = { state.description = it },
                                label = { Text("Description (optional)") }, maxLines = 4,
                                enabled = !state.busy && state.blocked == null, modifier = Modifier.fillMaxWidth(),
                            )
                            if (state.blocked == null) Button(onClick = state::create, enabled = !state.busy) {
                                Text("Create manual playlist")
                            }
                        }
                    }
                    if (!state.creating) {
                        items(state.targets?.items.orEmpty(), key = { it.id }) { target ->
                            val enabled = !state.busy && !state.targetsStale && state.blocked == null && !state.finished
                            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { state.select(target.id) }
                                .padding(horizontal = 16.dp, vertical = 4.dp)) {
                                RadioButton(selected = state.selected?.id == target.id,
                                    onClick = { state.select(target.id) }, enabled = enabled)
                                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                    Text(target.name, style = MaterialTheme.typography.titleMedium)
                                    Text(addTargetCountText(target))
                                }
                            }
                        }
                        if (!state.createdTarget && state.blocked == null) item {
                            AppendStatus(state.reading && state.targets != null, state.targetError, state::retryTargets, retryLabel = "Retry loading playlists")
                            if (state.targetsStale) TextButton(onClick = state::refreshTargets,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh playlists") }
                            if (state.targets?.items?.isEmpty() == true) Text("No manual playlists. Create one to save this track.")
                            else if (state.targetsEnded) Text("End of playlists")
                        }
                    }
                    if (state.blocked != null) item {
                        TextButton(onClick = { state.verify() }, enabled = !state.busy,
                            modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(if (state.blocked?.target == null) "Verify saved playlists" else "Verify playlist")
                        }
                    }
                    items(state.verifiedTargets?.items.orEmpty(), key = { "verified-${it.id}" }) { saved ->
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            Text(saved.name, style = MaterialTheme.typography.titleMedium)
                            Text(addTargetCountText(saved))
                        }
                    }
                    if (state.verifiedTargets != null || state.verificationError != null) item {
                        AppendStatus(state.reading && state.verifiedTargets != null, state.verificationError, state::retryVerification, retryLabel = "Retry verification")
                        if (state.verificationEnded) Text("End of saved playlists")
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { if (!state.mutating) onDismiss() }, enabled = !state.mutating) { Text("Close") }
                        if (state.blocked == null && !state.finished && !state.creating && !state.createdTarget) {
                            TextButton(onClick = state::beginCreate, enabled = !state.busy) { Text("Create playlist") }
                        }
                    }
                    if (state.blocked == null && !state.finished && !state.creating) {
                        Button(onClick = state::add, enabled = state.canAdd, modifier = Modifier.fillMaxWidth()) { Text("Add track") }
                    }
                }
            }
        }
    }
}

private fun addTargetCountText(summary: PersonalPlaylistSummary): String =
    summary.trackCount?.let { "$it tracks" } ?: "Track count unavailable"

internal data class AddBlockedWrite(val target: PersonalPlaylistSummary?, val uncertain: Boolean)

internal enum class AddWriteRecovery { REJECTED, CONFLICT, UNCERTAIN }

/** A malformed successful response is uncertain too: receiving HTTP 2xx does not confirm the saved DTO. */
internal fun addWriteRecovery(error: Exception): AddWriteRecovery {
    val status = (error as? ApiException)?.status
    return when {
        status == 409 -> AddWriteRecovery.CONFLICT
        status == null || status !in 400..499 || status == 408 -> AddWriteRecovery.UNCERTAIN
        else -> AddWriteRecovery.REJECTED
    }
}

/** Single-flight state lives only as long as this client's sheet; verification never restores write authority. */
internal class AddToPlaylistState(
    private val api: PersonalCollectionsApi,
    private val track: LibraryItem,
    private val scope: CoroutineScope,
    private val personalRevision: () -> Long = { 0L },
    private val currentSession: () -> Any? = { null },
) {
    private val session = currentSession()
    private var closed = false
    fun dispose() { closed = true }
    private fun current() {
        if (closed || !scope.isActive) throw CancellationException()
        check(session === currentSession())
    }
    var targetIdentity by mutableStateOf<Any>(Any()); private set
    var verificationIdentity by mutableStateOf<Any>(Any()); private set
    fun checkFreshness(revision: Long) {
        if (session !== currentSession() || (!createdTarget && blocked == null && targetRevision != null && targetRevision != revision)) {
            targetsStale = true
            message = "Playlists changed. Refresh before choosing a target."
        }
    }
    var targetError by mutableStateOf<String?>(null); private set
    var verificationError by mutableStateOf<String?>(null); private set
    var targetsStale by mutableStateOf(false); private set
    private var targetRevision: Long? = null
    private var verificationRevision: Long? = null
    private var verificationOffset = 0
    private var failedVerificationOffset = 0
    private var failedTargetOffset = 0
    var targetsEnded by mutableStateOf(false); private set
    var verificationEnded by mutableStateOf(false); private set
    val canLoadMoreTargets get() = !closed && !busy && !creating && !createdTarget && !finished &&
        blocked == null && targets != null && !targetsEnded && !targetsStale && targetError == null
    val canLoadMoreVerification get() = !closed && !busy && blocked?.target == null && blocked != null &&
        verifiedTargets != null && !verificationEnded && verificationError == null
    fun moreTargets() { if (canLoadMoreTargets) loadTargets(targetOffset) }
    fun retryTargets() {
        if (targetError != null && !targetsStale && !busy) {
            targetError = null; loadTargets(failedTargetOffset)
        }
    }
    fun refreshTargets() {
        if (busy || blocked != null || closed) return
        targetIdentity = Any()
        targets = null; selected = null; targetRevision = null; targetError = null; targetsStale = false
        targetsEnded = false; targetOffset = 0; loadTargets(0)
    }
    fun moreVerification() { if (canLoadMoreVerification) verify(verificationOffset) }
    fun retryVerification() {
        if (verificationError != null && !busy) { verificationError = null; verify(failedVerificationOffset) }
    }
    var targets by mutableStateOf<PersonalPlaylistPage?>(null)
        private set
    var selected by mutableStateOf<PersonalPlaylistSummary?>(null)
        private set
    var targetOffset by mutableStateOf(0)
        private set
    var creating by mutableStateOf(false)
        private set
    var createdTarget by mutableStateOf(false)
        private set
    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var mutating by mutableStateOf(false)
        private set
    var reading by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var blocked by mutableStateOf<AddBlockedWrite?>(null)
        private set
    var verifiedTargets by mutableStateOf<PersonalPlaylistPage?>(null)
        private set
    var message by mutableStateOf("")
        private set
    val busy: Boolean get() = mutating || reading
    val canAdd: Boolean get() = !closed && session === currentSession() && !busy && !finished && blocked == null && !creating && !targetsStale &&
        (createdTarget || targetRevision == personalRevision()) && track.kind == "track" && selected?.let { it.kind == PlaylistKind.MANUAL && it.capabilities.addTracks } == true

    fun loadTargets(offset: Int) {
        if (closed || session !== currentSession() || busy || blocked != null || creating || createdTarget || finished || targetsStale || targetError != null) return
        if (offset != targetOffset || targetsEnded) return
        val revision = targetRevision ?: personalRevision().also { targetRevision = it }
        failedTargetOffset = offset
        read("Editable playlists could not load. Retry explicitly or close.", onFailure = { error ->
            targetsStale = error is IllegalStateException
            targetError = if (targetsStale) null else "Playlists could not load. Retained targets have not changed."
            if (targetsStale) message = "Playlists changed. Refresh before choosing a target."
        }) {
            check(revision == personalRevision())
            val page = manualPage(offset)
            check(revision == personalRevision())
            val merged = appendPage(targets, page)
            targets = merged
            targetOffset = offset + page.items.size // Raw consumed offset, never the deduplicated row count.
            targetsEnded = targetOffset == page.total
            if (selected == null) selected = merged.items.firstOrNull()
            message = ""
        }
    }

    fun select(id: String) {
        if (!closed && !busy && !targetsStale && targetRevision == personalRevision() && blocked == null && !creating && !finished) selected = targets?.items?.find { it.id == id }
    }

    fun beginCreate() {
        if (closed || session !== currentSession() || busy || blocked != null || finished || createdTarget) return
        creating = true
        message = "Create a manual playlist first, then choose Add track to confirm the separate add."
    }

    fun create() {
        if (closed || session !== currentSession() || !creating || busy || blocked != null || finished) return
        val title = name.trim()
        val details = description.trim()
        playlistMetadataError(title, details)?.let { message = it; return }
        mutate(null) {
            val saved = api.createPlaylist(title, details)
            current()
            check(saved.kind == PlaylistKind.MANUAL && saved.capabilities.addTracks)
            targets = PersonalPlaylistPage(listOf(saved), 1)
            selected = saved
            creating = false
            createdTarget = true
            message = "Playlist created. Choose Add track to add this track."
        }
    }

    fun add() {
        if (!canAdd) return
        val snapshot = selected ?: return
        mutate(snapshot) {
            val saved = api.addEntry(snapshot.id, track.id, snapshot.revision)
            current()
            check(saved.id == snapshot.id && saved.kind == PlaylistKind.MANUAL)
            finished = true
        }
    }

    fun verify(offset: Int = 0) {
        val failure = blocked ?: return
        if (closed || session !== currentSession() || busy || finished) return
        // A deliberate verification starts a fresh read-only chain; continuation/retry keeps its exact offset.
        if (offset == 0) {
            verificationIdentity = Any()
            verifiedTargets = null; verificationOffset = 0; verificationEnded = false
            verificationRevision = personalRevision(); verificationError = null
        } else if (offset != verificationOffset || verificationEnded || verificationError != null) return
        failedVerificationOffset = offset
        read("Verification could not load. No change was resubmitted. Check again or close and review.",
            onFailure = { verificationError = "Verification could not load. No change was resubmitted." }) {
            val target = failure.target
            if (target == null) {
                check(verificationRevision == personalRevision())
                val page = manualPage(offset)
                check(verificationRevision == personalRevision())
                verifiedTargets = appendPage(verifiedTargets, page)
                verificationOffset = offset + page.items.size
                verificationEnded = verificationOffset == page.total
                message = "These are saved manual playlists. A matching name does not prove which request created it. " +
                    "No add was submitted. Close and review before creating another."
            } else {
                val saved = api.playlist(target.id)
                current()
                check(saved.id == target.id)
                if (failure.uncertain) {
                    check(saved.kind == PlaylistKind.MANUAL)
                    val count = saved.trackCount ?: error("Missing count")
                    check(count in 0..Int.MAX_VALUE.toLong())
                    val entryIds = mutableSetOf<String>()
                    var retainedBytes = 0L
                    var previousPosition = -1
                    var seen = 0
                    var occurrences = 0
                    do {
                        val page = api.entries(target.id, limit = 100, offset = seen)
                        current()
                        check(page.offset == seen && page.limit in 1..100 && page.items.size <= page.limit &&
                            page.total.toLong() == count && seen.toLong() + page.items.size <= count)
                        check(page.items.isNotEmpty() || seen.toLong() == count)
                        for (entry in page.items) {
                            check(entry.position > previousPosition)
                            previousPosition = entry.position
                            // Bound actual entry JSON bytes across this read, not a nominal per-song allowance.
                            retainedBytes += ApiJson.encodeToString(entry).toByteArray(Charsets.UTF_8).size.toLong()
                            check(retainedBytes <= 16L * 1024 * 1024)
                            check(entryIds.add(entry.id))
                            if (entry.track.id == track.id) occurrences++
                        }
                        seen += page.items.size
                    } while (seen.toLong() < count)
                    val after = api.playlist(target.id)
                    current()
                    check(after.id == saved.id && after.kind == PlaylistKind.MANUAL &&
                        after.revision == saved.revision && after.trackCount == count)
                    message = "Saved playlist has $occurrences occurrence${if (occurrences == 1) "" else "s"} of this track. " +
                        "Duplicates are allowed: this cannot prove whether the last add succeeded. Close and review before adding another."
                } else {
                    message = "Saved playlist: ${saved.name} · ${addTargetCountText(saved)}. " +
                        "Nothing was resubmitted. Close and reopen to review its latest revision before making another change."
                }
            }
        }
    }

    private suspend fun manualPage(offset: Int): PersonalPlaylistPage {
        val page = api.playlists(kind = PlaylistKind.MANUAL, limit = 40, offset = offset)
        current()
        check(page.offset == offset && page.limit == 40 && page.total in 0..100 && page.items.size <= 40)
        check(page.items.size == (page.total - offset).coerceIn(0, 40))
        check(page.items.map { it.id }.toSet().size == page.items.size)
        check(page.items.all { it.kind == PlaylistKind.MANUAL && it.capabilities.addTracks })
        return page
    }

    private fun appendPage(prefix: PersonalPlaylistPage?, page: PersonalPlaylistPage): PersonalPlaylistPage {
        if (prefix != null) check(prefix.total == page.total)
        val old = prefix?.items.orEmpty().associateBy { it.id }
        check(page.items.all { old[it.id]?.let { previous -> previous == it } ?: true })
        val fresh = page.items.filter { it.id !in old }
        check(fresh.isNotEmpty() || page.total == 0)
        val all = prefix?.items.orEmpty() + fresh
        // An overlapping offset page cannot truthfully establish a complete chooser.
        check(page.offset + page.items.size < page.total || all.size == page.total)
        return page.copy(items = all, offset = 0)
    }

    private fun read(failureText: String, onFailure: (Exception) -> Unit = {}, action: suspend () -> Unit) {
        reading = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (!closed) { message = failureText; onFailure(error) } }
            finally { reading = false }
        }
    }

    private fun mutate(target: PersonalPlaylistSummary?, action: suspend () -> Unit) {
        // Reserve synchronously, not after dispatch: a second tap cannot submit twice.
        mutating = true
        message = if (target == null) "Creating playlist…" else "Adding track…"
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val status = (error as? ApiException)?.status
                val recovery = addWriteRecovery(error)
                if (recovery != AddWriteRecovery.REJECTED) {
                    blocked = AddBlockedWrite(target, recovery == AddWriteRecovery.UNCERTAIN)
                    message = when {
                        status == 409 -> "This playlist changed elsewhere. Your draft is retained. " +
                            "Verify, then close and review before making another change. Nothing was resubmitted."
                        target == null -> "The create response was lost or could not be confirmed. A playlist may already exist. " +
                            "Your draft is retained. Verify saved playlists before creating another; no track has been added."
                        else -> "The add response was lost or could not be confirmed. This track may already have been added. " +
                            "Verify before adding again: playlists allow duplicate occurrences."
                    }
                } else {
                    message = when (status) {
                        401 -> "Your session has expired. Close and sign in again."
                        403 -> "This change was refused. Check your account permissions."
                        404 -> "This playlist or track is no longer available. Close and review."
                        429 -> "Too many requests. Wait before trying again explicitly."
                        else -> if (target == null) "The playlist could not be created. Check its name and description."
                            else "The track could not be added. Close and review the playlist."
                    }
                }
            } finally { mutating = false }
        }
    }
}
