package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.library.*
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SearchGroup(
    val items: List<MusicItem> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val nextOffset: Int? = null,
    val revision: Long? = null,
    val failed: Boolean = false,
)

internal data class SearchState(
    val query: String = "",
    val waiting: Boolean = false,
    val groups: Map<MusicKind, SearchGroup> = MusicKind.entries.associateWith { SearchGroup() },
) {
    val busy: Boolean get() = waiting || groups.values.any { it.loading }
    val hasMore: Boolean get() = groups.values.any { it.nextOffset != null }
    val empty: Boolean get() = groups.values.all { it.loaded && !it.failed && it.items.isEmpty() }
    val items: List<MusicItem> get() = groups.values.flatMap { it.items }
        .sortedWith(compareByDescending<MusicItem> { searchRank(it, query) }.thenBy { it.key })
}

/** Each kind paints independently, matching the working server client; no mixed-kind blocking read. */
internal class SearchViewModel(private val api: LibraryApi) : ViewModel() {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var consumedFocusIntent = 0

    fun consumeFocusIntent(value: Int): Boolean {
        if (value <= consumedFocusIntent) return false
        consumedFocusIntent = value
        return true
    }
    private var timer: Job? = null
    private val requests = mutableMapOf<MusicKind, Job>()

    fun query(value: String) {
        if (!validSearchQuery(value)) return
        if (value == mutable.value.query) return
        cancelRequests()
        mutable.value = SearchState(query = value, waiting = value.isNotBlank())
        if (value.isBlank()) return
        timer = viewModelScope.launch {
            delay(300)
            mutable.value = mutable.value.copy(waiting = false)
            MusicKind.entries.forEach { load(it, 0) }
        }
    }

    fun resume() {
        if (mutable.value.query.isBlank() || mutable.value.waiting) return
        MusicKind.entries.filter { !mutable.value.groups.getValue(it).loaded }.forEach { load(it, 0) }
    }

    fun pause() {
        cancelRequests()
        mutable.value = mutable.value.copy(waiting = false,
            groups = mutable.value.groups.mapValues { (_, group) -> group.copy(loading = false) })
    }

    fun more() {
        mutable.value.groups.forEach { (kind, group) ->
            if (!group.failed) group.nextOffset?.let { load(kind, it) }
        }
    }

    fun retry(kind: MusicKind) = load(kind, 0)

    private fun load(kind: MusicKind, offset: Int) {
        if (mutable.value.groups.getValue(kind).loading) return
        val token = generation
        val query = mutable.value.query.trim()
        if (query.isBlank()) return
        update(kind) { it.copy(loading = true, failed = false) }
        requests[kind] = viewModelScope.launch {
            try {
                val page = api.search(kind, query, offset)
                if (token != generation) return@launch
                val previous = mutable.value.groups.getValue(kind)
                check(offset == 0 || previous.revision == page.revision)
                val items = if (offset == 0) page.items else previous.items + page.items
                check(items.distinctBy(MusicItem::key).size == items.size)
                check(!page.hasMore || page.items.isNotEmpty())
                update(kind) { SearchGroup(items, loaded = true,
                    nextOffset = if (page.hasMore) page.offset + page.items.size else null, revision = page.revision) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) update(kind) { it.copy(loading = false, loaded = true, failed = true) }
            } finally {
                if (token == generation) update(kind) { it.copy(loading = false) }
            }
        }
    }

    private fun update(kind: MusicKind, change: (SearchGroup) -> SearchGroup) {
        val current = mutable.value
        mutable.value = current.copy(groups = current.groups + (kind to change(current.groups.getValue(kind))))
    }

    private fun cancelRequests() {
        generation++
        timer?.cancel()
        requests.values.forEach { it.cancel() }
        requests.clear()
    }
}

/** Display ranking comes directly from the accepted wireframe, not invented server counts. */
internal fun searchRank(item: MusicItem, query: String): Int {
    val q = query.trim().lowercase(Locale.ROOT)
    fun score(value: String, exact: Int, prefix: Int, contains: Int): Int {
        val name = value.lowercase(Locale.ROOT)
        return when {
            name == q -> exact
            name.startsWith(q) -> prefix
            q in name -> contains
            else -> 0
        }
    }
    return when (item.kind) {
        MusicKind.ARTIST -> score(item.name, 110, 95, 80)
        MusicKind.ALBUM -> score(item.name, 115, 88, 70).takeIf { it > 0 } ?: 30
        MusicKind.TRACK -> score(item.name, 120, 90, 75).takeIf { it > 0 }
            ?: score(item.artists.joinToString(" ") { it.name }, 50, 45, 40).takeIf { it > 0 } ?: 35
    }
}
