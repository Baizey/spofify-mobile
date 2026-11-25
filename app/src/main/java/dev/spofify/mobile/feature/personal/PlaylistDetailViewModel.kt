package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.PlaybackContext
import dev.spofify.mobile.playback.LivePersonal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PlaylistDetailState(
    val summary: PersonalPlaylistSummary? = null,
    val rows: List<PlaylistSong> = emptyList(),
    val context: PlaybackContext? = null,
    val loaded: Boolean = false,
    val canSaveOnline: Boolean = false,
    val showEmpty: Boolean = false,
    val filter: String = "",
    val message: String? = null,
    val deleted: Boolean = false,
) {
    val visibleRows: List<PlaylistSong> get() {
        val query = filter.trim()
        if (query.isEmpty()) return rows
        return rows.filter { row -> row.track.name.contains(query, ignoreCase = true) ||
            row.track.artists.any { it.name.contains(query, ignoreCase = true) } }
    }
    val noMatches: Boolean get() = loaded && filter.isNotBlank() && visibleRows.isEmpty()
    val canEdit: Boolean get() = summary != null && loaded && !deleted
}

internal class PlaylistDetailViewModel(private val source: LivePersonal, private val id: String) : ViewModel() {
    private val mutable = MutableStateFlow(PlaylistDetailState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            source.replica.view.collect { snapshot ->
                val summary = snapshot.playlists.firstOrNull { it.id == id }
                if (summary == null) {
                    if (snapshot.ready) mutable.value = PlaylistDetailState(filter = mutable.value.filter,
                        deleted = id in snapshot.deletedPlaylists, message = "Not saved on this device.")
                    return@collect
                }
                val detail = snapshot.details[id]
                val derived = summary.kind == PersonalPlaylistKind.DERIVED
                val rows = detail?.rows.orEmpty().map { row ->
                    PlaylistSong(row.id, row.position, row.track, entryId = row.id.takeUnless { derived },
                        occurrenceId = row.id.takeIf { derived })
                }
                mutable.value = mutable.value.copy(summary = summary, rows = rows, loaded = detail != null, deleted = false,
                    canSaveOnline = id in snapshot.sharedPlayablePlaylistIds,
                    showEmpty = detail?.rows?.isEmpty() == true && (!derived || detail.hasContinuity),
                    message = if (snapshot.ready && detail == null) "Not saved on this device." else null,
                    context = detail?.let { playlistContext(summary) })
            }
        }
    }

    fun watch() = source.watch(id)

    fun filter(value: String) { mutable.value = mutable.value.copy(filter = value) }

    fun rename(name: String, description: String) {
        source.command(PersonalIntent("renamePlaylist", playlistId = id, name = name, description = description))
    }

    fun remove(row: PlaylistSong) {
        val entryId = row.entryId ?: return
        if (mutable.value.summary?.capabilities?.editEntries != true) return
        source.command(PersonalIntent("removeEntry", playlistId = id, entryId = entryId))
    }

    fun delete() { source.command(PersonalIntent("deletePlaylist", playlistId = id)) }
}
