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
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PersonalCollectionsApi
import dev.spofify.mobile.core.PersonalPlaylistPage
import dev.spofify.mobile.core.PersonalPlaylistSummary
import dev.spofify.mobile.core.PlaylistKind
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Account-local manual targets only. Creating a playlist never implicitly adds the track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(client: SpofifyClient, track: LibraryItem, onDismiss: () -> Unit) {
    // Neither a previous client's drafts nor its in-flight results may enter a new session.
    key(client, track.id) {
        val scope = rememberCoroutineScope()
        val state = remember(client, track.id) {
            AddToPlaylistState(PersonalCollectionsApi(client), track, scope)
        }
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
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            Text(track.name, style = MaterialTheme.typography.titleMedium)
                            Text("Manual playlists only. Each add saves a separate occurrence, including duplicates.")
                        }
                    }
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
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
                            val enabled = !state.busy && state.blocked == null && !state.finished
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
                        if (state.targets != null && !state.createdTarget && state.blocked == null) item {
                            val page = state.targets!!
                            TargetPagination(page, state.busy, "playlists", state::loadTargets)
                        }
                        if (state.targets == null && !state.busy && state.blocked == null) item {
                            TextButton(onClick = { state.loadTargets(state.targetOffset) },
                                modifier = Modifier.padding(horizontal = 16.dp)) { Text("Retry loading playlists") }
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
                    state.verifiedTargets?.let { page ->
                        item { TargetPagination(page, state.busy, "saved playlists", state::verify) }
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

@Composable
private fun TargetPagination(page: PersonalPlaylistPage, busy: Boolean, label: String, load: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(if (page.items.isEmpty()) "No $label on this page (${page.total} total)"
            else "${page.offset + 1}–${page.offset + page.items.size} of ${page.total} $label")
        Row {
            TextButton(onClick = { load((page.offset - 40).coerceAtLeast(0)) }, enabled = !busy && page.offset > 0) {
                Text("Previous $label")
            }
            TextButton(onClick = { load(page.offset + 40) }, enabled = !busy && page.offset + page.items.size < page.total) {
                Text("Next $label")
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
) {
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
    val canAdd: Boolean get() = !busy && !finished && blocked == null && !creating &&
        track.kind == "track" && selected?.let { it.kind == PlaylistKind.MANUAL && it.capabilities.addTracks } == true

    fun loadTargets(offset: Int) {
        if (busy || blocked != null || creating || createdTarget || finished) return
        targetOffset = offset
        targets = null
        selected = null
        read("Editable playlists could not load. Retry explicitly or close.") {
            val page = manualPage(offset)
            targets = page
            selected = page.items.firstOrNull()
            message = if (page.items.isEmpty()) "No manual playlists on this page. Create one to save this track." else ""
        }
    }

    fun select(id: String) {
        if (!busy && blocked == null && !creating && !finished) selected = targets?.items?.find { it.id == id }
    }

    fun beginCreate() {
        if (busy || blocked != null || finished || createdTarget) return
        creating = true
        message = "Create a manual playlist first, then choose Add track to confirm the separate add."
    }

    fun create() {
        if (!creating || busy || blocked != null || finished) return
        val title = name.trim()
        val details = description.trim()
        playlistMetadataError(title, details)?.let { message = it; return }
        mutate(null) {
            val saved = api.createPlaylist(title, details)
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
            check(saved.id == snapshot.id && saved.kind == PlaylistKind.MANUAL)
            finished = true
        }
    }

    fun verify(offset: Int = 0) {
        val failure = blocked ?: return
        if (busy || finished) return
        read("Verification could not load. No change was resubmitted. Check again or close and review.") {
            val target = failure.target
            if (target == null) {
                verifiedTargets = manualPage(offset)
                message = "These are saved manual playlists. A matching name does not prove which request created it. " +
                    "No add was submitted. Close and review before creating another."
            } else {
                val saved = api.playlist(target.id)
                check(saved.id == target.id)
                if (failure.uncertain) {
                    check(saved.kind == PlaylistKind.MANUAL)
                    val count = saved.trackCount ?: error("Missing count")
                    check(count in 0..1000)
                    val entryIds = mutableSetOf<String>()
                    var seen = 0
                    var occurrences = 0
                    do {
                        val page = api.entries(target.id, limit = 100, offset = seen)
                        check(page.offset == seen && page.limit in 1..100 && page.items.size <= page.limit &&
                            page.total.toLong() == count && seen + page.items.size <= count)
                        check(page.items.isNotEmpty() || seen.toLong() == count)
                        for (entry in page.items) {
                            check(entryIds.add(entry.id))
                            if (entry.track.id == track.id) occurrences++
                        }
                        seen += page.items.size
                    } while (seen.toLong() < count)
                    val after = api.playlist(target.id)
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
        check(page.offset == offset && page.limit == 40 && page.total in 0..100 && page.items.size <= 40)
        check(page.items.size == (page.total - offset).coerceIn(0, 40))
        check(page.items.map { it.id }.toSet().size == page.items.size)
        check(page.items.all { it.kind == PlaylistKind.MANUAL && it.capabilities.addTracks })
        return page
    }

    private fun read(failureText: String, action: suspend () -> Unit) {
        reading = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = failureText }
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
