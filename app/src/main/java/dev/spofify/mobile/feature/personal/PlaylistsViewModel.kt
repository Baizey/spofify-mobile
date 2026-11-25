package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.playback.LivePersonal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PlaylistsState(
    val items: List<PersonalPlaylistSummary> = emptyList(),
    val loaded: Boolean = false,
    val canCreate: Boolean = false,
    val message: String? = null,
    val addSent: Long = 0,
)

internal class PlaylistsViewModel(private val source: LivePersonal, private val manualOnly: Boolean = false) : ViewModel() {
    private val mutable = MutableStateFlow(PlaylistsState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            source.replica.view.collect { snapshot ->
                val rows = snapshot.playlists.filter { !manualOnly || it.kind == PersonalPlaylistKind.MANUAL }
                mutable.value = mutable.value.copy(items = naturalPlaylistOrder(rows), loaded = snapshot.ready,
                    canCreate = snapshot.connected, message = when {
                        !snapshot.ready -> null
                        !snapshot.listsKnown -> "Playlists not saved on this device."
                        !snapshot.listsComplete && manualOnly && rows.isEmpty() -> "No manual playlists in the available list."
                        else -> null
                    })
            }
        }
    }

    fun refresh() = source.refreshAccount()

    fun create(name: String, description: String) {
        source.command(PersonalIntent("createPlaylist", name = name, description = description))
    }

    fun add(trackId: String, summary: PersonalPlaylistSummary) {
        if (summary.capabilities.addTracks && source.command(PersonalIntent("addEntry", playlistId = summary.id, trackId = trackId))) {
            mutable.value = mutable.value.copy(addSent = mutable.value.addSent + 1)
        }
    }
}
