package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.personal.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PlaylistsState(
    val items: List<PersonalPlaylistSummary> = emptyList(),
    val loaded: Boolean = false,
    val canCreate: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val writing: Boolean = false,
    val needsReview: Boolean = false,
    val message: String? = null,
    val created: PersonalPlaylistSummary? = null,
    val added: PersonalPlaylistSummary? = null,
    val createSent: Long = 0,
    val addSent: Long = 0,
)

internal class PlaylistsViewModel(private val api: PersonalLibraryApi, private val manualOnly: Boolean = false) : ViewModel() {
    private val mutable = MutableStateFlow(PlaylistsState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var reading: Job? = null

    private val live = api as? dev.spofify.mobile.playback.LivePersonal
    init {
        if (live == null) refresh() else viewModelScope.launch {
            refresh()
            live.replica.view.collect { snapshot ->
                val rows = snapshot.playlists.filter { !manualOnly || it.kind == PersonalPlaylistKind.MANUAL }
                mutable.value = mutable.value.copy(items = naturalPlaylistOrder(rows), loaded = snapshot.ready,
                    canCreate = snapshot.connected, message = if (snapshot.ready && !snapshot.listsKnown) "Playlists not saved on this device." else null)
            }
        }
    }

    fun refresh() {
        if (live != null) {
            live.refresh(AccountPersonalReplica.Resource.Lists)
            return
        }
        reading?.cancel()
        val token = ++generation
        mutable.value = mutable.value.copy(loading = api !is dev.spofify.mobile.playback.DevicePersonalLibrary, error = null)
        reading = viewModelScope.launch {
            try {
                val result = mutableListOf<PersonalPlaylistSummary>()
                val seen = mutableSetOf<String>()
                var offset = 0
                var total: Long? = null
                do {
                    val page = api.playlists(100, offset, if (manualOnly) PersonalPlaylistKind.MANUAL else null)
                    check(page.offset == offset && page.limit == 100 && page.items.size <= 100)
                    check(page.total >= 0 && (total == null || total == page.total))
                    total = page.total
                    check(page.items.isNotEmpty() || offset.toLong() >= page.total)
                    check(offset.toLong() + page.items.size <= page.total)
                    page.items.forEach {
                        check(seen.add(it.id))
                        check(!manualOnly || it.kind == PersonalPlaylistKind.MANUAL)
                        result.add(it)
                    }
                    offset += page.items.size
                } while (offset.toLong() < requireNotNull(total))
                if (token != generation) return@launch
                // Never publish or sort a paged prefix as the complete global library.
                mutable.value = mutable.value.copy(items = naturalPlaylistOrder(result), loaded = true, loading = false, error = null, needsReview = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) mutable.value = mutable.value.copy(loading = false, error = personalError(failure))
            }
        }
    }

    fun create(name: String, description: String) {
        live?.let {
            if (it.command(PersonalIntent("createPlaylist", name = name, description = description))) {
                mutable.value = mutable.value.copy(createSent = mutable.value.createSent + 1)
            }
            return
        }
        if (mutable.value.writing || mutable.value.needsReview) return
        mutable.value = mutable.value.copy(writing = true, message = null, created = null)
        viewModelScope.launch {
            try {
                val created = api.createManualPlaylist(name, description)
                mutable.value = mutable.value.copy(writing = false, created = created, message = null)
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.value = mutable.value.copy(writing = false, needsReview = true, message = personalError(failure, mutation = true))
            }
        }
    }

    fun add(trackId: String, summary: PersonalPlaylistSummary) {
        live?.let {
            if (summary.capabilities.addTracks && it.command(PersonalIntent("addEntry", playlistId = summary.id, trackId = trackId))) {
                mutable.value = mutable.value.copy(addSent = mutable.value.addSent + 1)
            }
            return
        }
        if (mutable.value.writing || mutable.value.needsReview || summary.kind != PersonalPlaylistKind.MANUAL || !summary.capabilities.addTracks) return
        mutable.value = mutable.value.copy(writing = true, message = null, added = null)
        viewModelScope.launch {
            try {
                val saved = api.addTrack(summary.id, trackId, summary.revision)
                mutable.value = mutable.value.copy(writing = false, added = saved, message = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.value = mutable.value.copy(writing = false, needsReview = true, message = personalError(failure, mutation = true))
                if ((failure as? PersonalLibraryException)?.status == 409) refresh()
            }
        }
    }

    fun consumeCreated() { mutable.value = mutable.value.copy(created = null) }
    fun consumeAdded() { mutable.value = mutable.value.copy(added = null) }
}
