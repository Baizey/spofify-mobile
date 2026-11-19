package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.library.LibraryApi
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.library.MusicPage
import dev.spofify.mobile.core.playback.PlaybackContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class DetailRankMode(val label: String) {
    CATALOGUE("Catalogue"), PERSONAL("Your plays");

    val next: DetailRankMode get() = if (this == CATALOGUE) PERSONAL else CATALOGUE
}

internal data class DetailState(
    val item: MusicItem? = null,
    val rows: List<MusicItem> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val nextOffset: Int? = null,
    val revision: Long? = null,
    val loaded: Boolean = false,
    val rankMode: DetailRankMode = DetailRankMode.CATALOGUE,
)

// There is no catalogue popularity or artist ranking endpoint in the server API.
// Do not turn album pages or a prefix of personal history into pretend top tracks.
internal const val DETAIL_RANKINGS_UNAVAILABLE = "Rankings unavailable"

internal fun releaseYear(releaseDate: String): String =
    releaseDate.takeIf { it.length >= 4 && it.take(4).all(Char::isDigit) }?.take(4).orEmpty()

internal fun MusicItem.collectionContext(revision: Long? = null): PlaybackContext? = when (kind) {
    MusicKind.ALBUM -> PlaybackContext("album", id, name, revision)
    MusicKind.ARTIST -> PlaybackContext("artist", id, name, revision)
    MusicKind.TRACK -> null
}

internal fun MusicItem.canPlay(): Boolean = when (kind) {
    MusicKind.TRACK -> hasAudio
    MusicKind.ALBUM, MusicKind.ARTIST -> availableTracks > 0
}

internal class DetailViewModel(
    private val api: LibraryApi,
    private val kind: MusicKind,
    private val id: String,
) : ViewModel() {
    private val mutable = MutableStateFlow(DetailState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var retryOffset = 0

    init { load(0) }

    fun toggleRankMode() { mutable.value = mutable.value.copy(rankMode = mutable.value.rankMode.next) }
    fun refresh() = load(0)
    fun retry() = load(retryOffset)
    fun more() { if (!mutable.value.failed) mutable.value.nextOffset?.let(::load) }

    private fun load(offset: Int) {
        if (request?.isActive == true) return
        mutable.value = mutable.value.copy(loading = api !is dev.spofify.mobile.core.offline.LocalMusicCatalog, failed = false)
        retryOffset = offset
        request = viewModelScope.launch {
            try {
                val previous = mutable.value
                val item = if (offset == 0) api.detail(kind, id) else requireNotNull(previous.item)
                // An initial identity can be shown while the complete album is being fetched.
                // A refresh keeps the old identity and rows together until it succeeds.
                if (previous.item == null) mutable.value = mutable.value.copy(item = item)
                val pages = when (kind) {
                    MusicKind.ARTIST -> listOf(api.artistAlbums(id, offset))
                    MusicKind.ALBUM -> completeAlbum()
                    MusicKind.TRACK -> emptyList()
                }
                val page = pages.firstOrNull()
                if (offset != 0 && page?.revision != previous.revision) {
                    retryOffset = 0 // Restart a changed catalogue; never join two revisions.
                    error("Library changed")
                }
                val rows = if (offset == 0) pages.flatMap(MusicPage::items)
                    else previous.rows + pages.flatMap(MusicPage::items)
                check(rows.distinctBy(MusicItem::key).size == rows.size)
                if (kind == MusicKind.ARTIST) {
                    check(page != null && page.offset == offset && page.items.all { it.kind == MusicKind.ALBUM })
                    check(!page.hasMore || page.items.isNotEmpty())
                }
                mutable.value = DetailState(
                    item = item,
                    rows = rows,
                    nextOffset = page?.takeIf { kind == MusicKind.ARTIST && it.hasMore }?.nextOffset(),
                    revision = page?.revision,
                    loaded = true,
                    rankMode = mutable.value.rankMode,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loading = false, failed = api !is dev.spofify.mobile.core.offline.LocalMusicCatalog)
            } finally {
                mutable.value = mutable.value.copy(loading = false)
            }
        }
    }

    private suspend fun completeAlbum(): List<MusicPage> {
        val pages = mutableListOf<MusicPage>()
        val keys = hashSetOf<String>()
        var offset = 0
        do {
            val page = api.albumTracks(id, offset)
            check(page.offset == offset && page.items.all { it.kind == MusicKind.TRACK })
            check(page.items.all { keys.add(it.key) })
            pages.firstOrNull()?.let { first ->
                check(page.revision == first.revision && page.total == first.total)
            }
            check(!page.hasMore || page.items.isNotEmpty())
            pages.add(page)
            offset = page.nextOffset()
        } while (page.hasMore)
        return pages
    }

    private fun MusicPage.nextOffset(): Int {
        val next = offset.toLong() + items.size
        check(next <= Int.MAX_VALUE)
        return next.toInt()
    }
}
