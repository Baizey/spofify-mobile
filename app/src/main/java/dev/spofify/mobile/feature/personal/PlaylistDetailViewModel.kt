package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.PlaybackContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PlaylistDetailState(
    val summary: PersonalPlaylistSummary? = null,
    val rows: List<PlaylistSong> = emptyList(),
    val context: PlaybackContext? = null,
    val loaded: Boolean = false,
    val completeCollection: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val filter: String = "",
    val writing: Boolean = false,
    val message: String? = null,
    val needsRefresh: Boolean = false,
    val deleted: Boolean = false,
) {
    val visibleRows: List<PlaylistSong> get() {
        val query = filter.trim()
        if (query.isEmpty()) return rows
        return rows.filter { row -> row.track.name.contains(query, ignoreCase = true) ||
            row.track.artists.any { it.name.contains(query, ignoreCase = true) } }
    }
    val noMatches: Boolean get() = loaded && completeCollection && filter.isNotBlank() && visibleRows.isEmpty()
    val canEdit: Boolean get() = summary != null && loaded && !loading && !writing && !needsRefresh && !deleted
}

internal class PlaylistDetailViewModel(
    private val api: PersonalLibraryApi,
    private val id: String,
    private val onMutationAcknowledged: () -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(PlaylistDetailState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var reading: Job? = null

    init { refresh() }

    fun filter(value: String) { mutable.value = mutable.value.copy(filter = value) }

    fun refresh() {
        if (mutable.value.deleted) return
        reading?.cancel()
        val token = ++generation
        mutable.value = mutable.value.copy(loading = api !is dev.spofify.mobile.playback.DevicePersonalLibrary, error = null, context = null)
        reading = viewModelScope.launch {
            try {
                val summary = api.playlist(id)
                if (token != generation) return@launch
                if (!mutable.value.loaded) mutable.value = mutable.value.copy(summary = summary)
                val loaded = when (summary.kind) {
                    PersonalPlaylistKind.MANUAL -> readManual(summary)
                    PersonalPlaylistKind.DERIVED -> readDerived()
                }
                if (token != generation) return@launch
                mutable.value = mutable.value.copy(summary = loaded.summary, rows = loaded.rows, context = loaded.context,
                    loaded = true, completeCollection = loaded.complete, loading = false, error = null, needsRefresh = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) mutable.value = if (api is dev.spofify.mobile.playback.DevicePersonalLibrary)
                    PlaylistDetailState(loaded = true, completeCollection = true, message = "Not saved on this device.")
                else mutable.value.copy(loading = false, error = personalError(failure), needsRefresh = true)
            }
        }
    }

    private data class Loaded(
        val summary: PersonalPlaylistSummary,
        val rows: List<PlaylistSong>,
        val context: PlaybackContext?,
        val complete: Boolean,
    )

    private suspend fun readManual(summary: PersonalPlaylistSummary): Loaded {
        val rows = mutableListOf<PlaylistSong>()
        val seen = mutableSetOf<String>()
        var offset = 0
        val total = requireNotNull(summary.trackCount)
        do {
            val page = api.manualEntries(id, 100, offset)
            check(page.total == total && page.offset == offset && page.limit == 100 && page.items.size <= 100)
            check(page.items.isNotEmpty() || offset.toLong() >= total)
            check(offset.toLong() + page.items.size <= total)
            page.items.forEach {
                check(seen.add(it.id))
                check(rows.lastOrNull()?.let { previous -> previous.position < it.position } != false)
                rows.add(PlaylistSong(it.id, it.position, it.track, entryId = it.id))
            }
            offset += page.items.size
        } while (offset.toLong() < total)
        val confirmed = api.playlist(id)
        check(confirmed.kind == PersonalPlaylistKind.MANUAL && confirmed.revision == summary.revision && confirmed.trackCount == total)
        return Loaded(confirmed, rows, PlaybackContext("playlist", id, confirmed.name), complete = true)
    }

    private suspend fun readDerived(): Loaded {
        val rows = mutableListOf<PlaylistSong>()
        val seen = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var first: PersonalDerivedPage? = null
        do {
            val page = api.derivedEntries(id, 100, cursor)
            check(page.limit == 100 && page.items.size <= 100)
            val basis = first
            if (basis == null) first = page else {
                check(page.complete && basis.complete && page.revision == basis.revision && page.fence == basis.fence &&
                    page.total == basis.total && page.availableTotal == basis.availableTotal)
            }
            page.items.forEach {
                check(seen.add(it.occurrenceId))
                check(rows.lastOrNull()?.let { previous -> previous.position < it.position } != false)
                rows.add(PlaylistSong(it.occurrenceId, it.position, it.track, occurrenceId = it.occurrenceId, selectionToken = it.selectionToken))
            }
            cursor = page.nextCursor
            check(cursor == null || page.complete && page.items.isNotEmpty() && cursors.add(cursor))
        } while (cursor != null)
        val page = requireNotNull(first)
        val confirmed = api.playlist(id)
        check(confirmed.kind == PersonalPlaylistKind.DERIVED && confirmed.revision == page.revision)
        if (!page.complete) return Loaded(confirmed, rows, null, complete = false)
        check(rows.size.toLong() == page.total)
        val context = if (page.revision != null && page.fence.isNotBlank() &&
            (api is dev.spofify.mobile.playback.DevicePersonalLibrary || confirmed.evaluationState == PersonalEvaluationState.READY))
            PlaybackContext("derived", id, confirmed.name, page.revision, page.fence) else null
        return Loaded(confirmed, rows, context, complete = true)
    }

    fun rename(name: String, description: String, expectedRevision: Long) = change(expectedRevision) {
        api.renamePlaylist(id, name, description, expectedRevision)
    }

    fun remove(row: PlaylistSong, expectedRevision: Long) {
        val entryId = row.entryId ?: return
        if (mutable.value.summary?.kind != PersonalPlaylistKind.MANUAL || mutable.value.summary?.capabilities?.editEntries != true) return
        change(expectedRevision) { api.removeEntry(id, entryId, expectedRevision) }
    }

    fun delete(expectedRevision: Long) {
        if (!beginChange(expectedRevision)) return
        viewModelScope.launch {
            try {
                api.deletePlaylist(id, expectedRevision)
                mutable.value = mutable.value.copy(writing = false, deleted = true)
                onMutationAcknowledged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) { failedChange(failure) }
        }
    }

    private fun change(expectedRevision: Long, action: suspend () -> PersonalPlaylistSummary) {
        if (!beginChange(expectedRevision)) return
        viewModelScope.launch {
            try {
                action()
                mutable.value = mutable.value.copy(writing = false, message = null)
                onMutationAcknowledged()
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) { failedChange(failure) }
        }
    }

    private fun beginChange(expectedRevision: Long): Boolean {
        val current = mutable.value
        if (!current.canEdit) return false
        if (current.summary?.revision != expectedRevision) {
            mutable.value = current.copy(message = "This playlist changed. Review its latest details before saving.")
            return false
        }
        generation++
        reading?.cancel()
        mutable.value = current.copy(writing = true, message = null, context = null)
        return true
    }

    private fun failedChange(failure: Exception) {
        mutable.value = mutable.value.copy(writing = false, needsRefresh = true, message = personalError(failure, mutation = true))
        if ((failure as? PersonalLibraryException)?.status == 409) refresh()
    }
}
