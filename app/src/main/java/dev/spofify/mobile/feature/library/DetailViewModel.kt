package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.library.LibraryApi
import dev.spofify.mobile.core.library.LibraryException
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.library.MusicPage
import dev.spofify.mobile.core.offline.LocalMusicCatalog
import dev.spofify.mobile.core.playback.PlaybackContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    private val owner: LibraryReadOwner? = null,
) : ViewModel() {
    private val local = api is LocalMusicCatalog
    private val mutable = MutableStateFlow(DetailState())
    val state = mutable.asStateFlow()
    private var request: Job? = null

    init { load(0) }

    fun toggleRankMode() {
        publish {
            if (viewModelScope.isActive) mutable.value = mutable.value.copy(rankMode = mutable.value.rankMode.next)
        }
    }

    fun refresh() = load(0)
    fun more() { if (!mutable.value.failed) mutable.value.nextOffset?.let(::load) }

    private fun load(offset: Int) {
        if (request?.isCompleted == false) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val read = suspend {
                    val previous = mutable.value
                    publishRead { it.copy(loading = !local, failed = false) }
                    readSnapshot(offset, previous)
                }
                val result = if (owner == null) read() else owner.read(read)
                publishRead { result.copy(rankMode = it.rankMode) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishRead { it.copy(loading = false, failed = !local) }
            }
        }
        request = job
        job.start()
    }

    private suspend fun readSnapshot(offset: Int, previous: DetailState): DetailState {
        val reads = LoadReads(retryAvailable = !local)
        val item = if (offset == 0) reads.read { api.detail(kind, id) } else requireNotNull(previous.item)
        // An initial identity can be shown while the complete album is being fetched.
        // A refresh keeps the old identity and rows together until it succeeds.
        if (previous.item == null) publishRead { it.copy(item = item) }
        val pages = when (kind) {
            MusicKind.ARTIST -> listOf(reads.read { api.artistAlbums(id, offset) })
            MusicKind.ALBUM -> completeAlbum(reads)
            MusicKind.TRACK -> emptyList()
        }
        val page = pages.firstOrNull()
        check(offset == 0 || page?.revision == previous.revision)
        val rows = if (offset == 0) pages.flatMap(MusicPage::items)
            else previous.rows + pages.flatMap(MusicPage::items)
        check(rows.distinctBy(MusicItem::key).size == rows.size)
        if (kind == MusicKind.ARTIST) {
            check(page != null && page.offset == offset && page.items.all { it.kind == MusicKind.ALBUM })
            check(!page.hasMore || page.items.isNotEmpty())
        }
        return DetailState(
            item = item,
            rows = rows,
            nextOffset = page?.takeIf { kind == MusicKind.ARTIST && it.hasMore }?.nextOffset(),
            revision = page?.revision,
            loaded = true,
        )
    }

    private fun publish(block: () -> Unit): Boolean = owner?.publish(block) ?: run { block(); true }

    private suspend fun publishRead(update: (DetailState) -> DetailState) {
        val context = currentCoroutineContext()
        val accepted = publish {
            context.ensureActive()
            mutable.value = update(mutable.value)
        }
        if (!accepted) throw CancellationException("Library source retired")
    }

    // Share one recovery across identity and every page. Repeat only the failed read, so
    // a continuation must still match the revision already staged for this load.
    private class LoadReads(private var retryAvailable: Boolean) {
        suspend fun <T> read(block: suspend () -> T): T {
            val context = currentCoroutineContext()
            context.ensureActive()
            val result = try {
                block()
            } catch (failure: Exception) {
                val transient = when (failure) {
                    is LibraryException -> failure.transient
                    is AuthFailure -> failure.transient
                    else -> false
                }
                if (!retryAvailable || !transient) throw failure
                retryAvailable = false
                delay(1_000)
                context.ensureActive()
                block()
            }
            context.ensureActive()
            return result
        }
    }

    private suspend fun completeAlbum(reads: LoadReads): List<MusicPage> {
        val pages = mutableListOf<MusicPage>()
        val keys = hashSetOf<String>()
        var offset = 0
        do {
            val page = reads.read { api.albumTracks(id, offset) }
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
