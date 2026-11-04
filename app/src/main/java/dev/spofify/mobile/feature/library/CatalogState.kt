package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.CatalogApi
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import dev.spofify.mobile.core.SpofifyClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Test seam for catalog reads; authentication and transport stay in the shared client. */
interface CatalogReader {
    suspend fun browse(request: CatalogRequest): LibraryPage
    suspend fun detail(kind: String, id: String): LibraryItem
    suspend fun revision(): Long
}

class ClientCatalogReader(client: SpofifyClient) : CatalogReader {
    private val api = CatalogApi(client)
    override suspend fun browse(request: CatalogRequest) = with(request) {
        api.browse(kind, query, availability, limit, offset, artistId, albumId, releaseGroup)
    }
    override suspend fun detail(kind: String, id: String) = api.detail(kind, id)
    override suspend fun revision() = api.revision()
}

data class CatalogRequest(
    val kind: String,
    val query: String = "",
    val availability: String = "in-library",
    val limit: Int = 40,
    val offset: Int = 0,
    val artistId: String? = null,
    val albumId: String? = null,
    val releaseGroup: String? = null,
)

val CatalogKinds = listOf("artists", "albums", "tracks")
val ReleaseGroups = listOf("albums" to "Albums", "singles" to "Singles & EPs", "appearances" to "Appears on")
fun catalogLabel(kind: String) = when (kind) { "artists" -> "Artists"; "albums" -> "Albums"; "tracks" -> "Songs"; else -> "All" }

data class CatalogSection(
    val items: List<LibraryItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 40,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val requestedOffset: Int? = null,
    val needsReload: Boolean = false,
    val appendPending: Boolean = false,
    val revalidateCount: Int = 0,
) {
    val hasPrevious get() = offset > 0
    val nextOffset get() = offset + maxOf(items.size, limit)
    val hasNext get() = nextOffset < total && nextOffset <= 1_000_000
}

data class CatalogScreen(
    val key: Long = 0,
    val kind: String = "albums",
    val query: String = "",
    val search: Boolean = false,
    val includeMissing: Boolean = false,
    val item: LibraryItem? = null,
    val detailResolved: Boolean = false,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val detailNeedsReload: Boolean = false,
    val detailUnavailable: Boolean = false,
    val releaseGroup: String = "albums",
    val sections: Map<String, CatalogSection> = emptyMap(),
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val stale: Boolean = false,
) {
    val emptySearch get() = item == null && search && query.isBlank()
    val sectionKinds: List<String> get() = when (item?.kind) {
        "artist" -> listOf("albums")
        "album" -> listOf("tracks")
        "track" -> emptyList()
        else -> if (kind == "all") CatalogKinds else listOf(kind)
    }

    fun request(section: String): CatalogRequest {
        val child = item != null
        return CatalogRequest(kind = section, query = if (child) "" else query,
            availability = if (child || includeMissing) "all" else "in-library",
            limit = if (!child && search) 10 else 40,
            offset = sections[section]?.let { it.requestedOffset ?: it.offset } ?: 0,
            artistId = item?.takeIf { it.kind == "artist" }?.id,
            albumId = item?.takeIf { it.kind == "album" }?.id,
            releaseGroup = if (item?.kind == "artist") releaseGroup else null)
    }
}

data class CatalogNavigationState(val stack: List<CatalogScreen>) {
    val current get() = stack.last()
    val canGoBack get() = stack.size > 1
}

/** In-memory, account-scoped navigation. Back restores rows, page offsets and scroll, never audio. */
class CatalogStateHolder(
    private val reader: CatalogReader,
    private val scope: CoroutineScope,
    initialKind: String = "albums",
    initialQuery: String = "",
    initialItem: LibraryItem? = null,
) {
    private val mutable = MutableStateFlow(CatalogNavigationState(listOf(CatalogScreen(
        kind = initialKind.takeIf { it in CatalogKinds || it == "all" } ?: "albums",
        query = cleanCatalogQuery(initialQuery), search = true, item = initialItem))))
    val state = mutable.asStateFlow()
    private var serial = 0L
    private var generation = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val requests = mutableMapOf<String, Long>()
    private var requestSerial = 0L
    private var queryJob: Job? = null
    private var visible = false
    private var knownRevision: Long? = null
    private var latestObservedRevision: Long? = null
    private var composing = false
    private var pendingRevision: Long? = null
    private var revisionBusy = false
    private var catchUpAllowed = false
    private var revisionValidated = false

    private fun update(transform: (CatalogScreen) -> CatalogScreen) {
        val value = mutable.value
        mutable.value = value.copy(stack = value.stack.dropLast(1) + transform(value.current))
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) loadVisible() else cancelReads()
    }

    private fun cancelReads(markInterrupted: Boolean = true) {
        generation++
        queryJob?.cancel(); queryJob = null
        jobs.values.forEach { it.cancel() }; jobs.clear(); requests.clear()
        if (!markInterrupted) return
        update { screen -> screen.copy(detailLoading = false,
            detailNeedsReload = screen.detailNeedsReload || screen.detailLoading,
            sections = screen.sections.mapValues { (_, section) -> section.copy(loading = false,
                needsReload = section.needsReload || section.loading) }) }
    }

    /**
     * Explicit Search-tab intent, unlike normal Back/visibility/query preservation.
     * Publishes one fresh empty root, fences obsolete reads and scroll callbacks, and
     * drops composition/deferred refresh work without fetching or touching playback.
     * Focus and local IME draft reset belong to the caller's activation event.
     */
    fun activateSearchRoot(): Unit {
        cancelReads(markInterrupted = false)
        composing = false
        pendingRevision = null
        catchUpAllowed = false
        mutable.value = CatalogNavigationState(listOf(CatalogScreen(key = ++serial, kind = "all", search = true)))
    }

    fun open(item: LibraryItem) {
        if (item.kind !in listOf("artist", "album", "track")) return
        cancelReads()
        mutable.value = mutable.value.copy(stack = mutable.value.stack + CatalogScreen(key = ++serial, item = item))
        loadVisible()
    }

    fun back(): Boolean {
        if (!state.value.canGoBack) return false
        cancelReads()
        mutable.value = mutable.value.copy(stack = mutable.value.stack.dropLast(1))
        loadVisible()
        return true
    }

    fun setComposing(value: Boolean) {
        composing = value
        if (!value) applyPendingRevision()
    }

    fun rememberScroll(screenKey: Long, index: Int, offset: Int, expectedQuery: String? = null, expectedReleaseGroup: String? = null) {
        val current = state.value.current
        if (current.key == screenKey && (expectedQuery == null || current.query == expectedQuery) &&
            (expectedReleaseGroup == null || current.releaseGroup == expectedReleaseGroup)) {
            update { it.copy(scrollIndex = index, scrollOffset = offset) }
        }
    }

    /** Only committed IME text reaches this method. Submit flushes debounce, not accepted results. */
    fun commitQuery(query: String, immediate: Boolean = false) {
        val cleaned = cleanCatalogQuery(query)
        val current = state.value.current
        if (cleaned == current.query) {
            val pending = queryJob
            if (immediate && pending?.isActive == true) {
                val token = generation
                pending.cancel()
                if (queryJob === pending) {
                    queryJob = null
                    if (token == generation && visible && state.value.current.key == current.key && state.value.current.query == cleaned) {
                        loadVisible(preservePending = true)
                        applyPendingRevision()
                    }
                }
            }
            return
        }
        cancelReads()
        update { it.copy(query = cleaned, search = true, item = null, sections = emptyMap(),
            scrollIndex = 0, scrollOffset = 0, detailResolved = false) }
        if (cleaned.isBlank()) return
        val token = generation
        queryJob = scope.launch {
            if (!immediate) delay(250)
            if (token == generation && visible) {
                queryJob = null
                loadVisible()
                applyPendingRevision()
            }
        }
    }

    fun selectKind(kind: String) {
        if (kind !in CatalogKinds && kind != "all") return
        if (state.value.current.kind == kind) return
        changeResults { it.copy(kind = kind, search = it.search || kind == "all") }
    }

    fun includeMissing(include: Boolean) {
        if (state.value.current.includeMissing == include) return
        changeResults { it.copy(includeMissing = include) }
    }

    private fun changeResults(change: (CatalogScreen) -> CatalogScreen) {
        cancelReads()
        update { change(it).copy(sections = emptyMap(), scrollIndex = 0, scrollOffset = 0) }
        loadVisible()
    }

    fun selectReleaseGroup(group: String) {
        if (group !in ReleaseGroups.map { it.first } || group == state.value.current.releaseGroup) return
        cancelReads()
        update { it.copy(releaseGroup = group, sections = emptyMap(), scrollIndex = 0, scrollOffset = 0) }
        loadVisible()
    }

    fun more(kind: String) {
        val section = state.value.current.sections[kind] ?: return
        if (section.loading || !section.hasNext) return
        update { it.copy(sections = it.sections + (kind to section.copy(
            requestedOffset = section.nextOffset, appendPending = true))) }
        loadSection(kind)
    }

    fun page(kind: String, direction: Int) {
        val section = state.value.current.sections[kind] ?: return
        if (section.loading || (direction < 0 && !section.hasPrevious) || (direction > 0 && !section.hasNext)) return
        update { it.copy(sections = it.sections + (kind to section.copy(
            requestedOffset = (section.offset + direction.coerceIn(-1, 1) * section.limit).coerceIn(0, 1_000_000), appendPending = false))) }
        loadSection(kind)
    }

    fun retry(kind: String) = loadSection(kind)
    fun retryDetail() = loadDetail()

    private fun loadVisible(preservePending: Boolean = false) {
        if (!visible) return
        val screen = state.value.current
        if (screen.emptySearch) return
        val stale = screen.stale
        if (screen.item != null && (!screen.detailResolved || screen.detailNeedsReload || stale) &&
            (!preservePending || !screen.detailLoading)) loadDetail(quiet = stale || screen.detailResolved)
        if (!screen.detailUnavailable) screen.sectionKinds.forEach { kind ->
            val section = screen.sections[kind]
            if ((section?.loaded != true || section.needsReload || stale) && (!preservePending || section?.loading != true))
                loadSection(kind, quiet = stale || section?.loaded == true)
        }
        update { it.copy(stale = false) }
    }

    private fun loadSection(kind: String, quiet: Boolean = false) {
        if (!visible || kind !in state.value.current.sectionKinds || state.value.current.emptySearch || state.value.current.detailUnavailable) return
        val token = generation
        val screen = state.value.current
        val request = screen.request(kind)
        val existing = screen.sections[kind]
        val append = existing?.appendPending == true
        val retainedCount = if (!append && existing != null && existing.offset == request.offset)
            maxOf(existing.items.size, existing.revalidateCount, request.limit) else request.limit
        val jobKey = "section:$kind"
        jobs.remove(jobKey)?.cancel()
        val requestId = ++requestSerial
        requests[jobKey] = requestId
        fun current() = token == generation && requests[jobKey] == requestId && screen.key == state.value.current.key
        update { it.copy(sections = it.sections + (kind to (it.sections[kind] ?: CatalogSection(limit = request.limit)).copy(loading = true, error = null, needsReload = true))) }
        jobs[jobKey] = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var result = reader.browse(request.copy(limit = minOf(retainedCount, 100)))
                // Revalidate only the prefix the listener explicitly expanded, in bounded reads.
                // Never fetch the whole catalog just to create a recommendation/ranking illusion.
                while (retainedCount > request.limit && result.items.size < retainedCount && request.offset + result.items.size < result.total && result.items.isNotEmpty()) {
                    if (!current()) return@launch
                    val next = reader.browse(request.copy(offset = request.offset + result.items.size,
                        limit = minOf(retainedCount - result.items.size, 100)))
                    if (next.items.isEmpty()) break
                    result = result.copy(items = result.items + next.items, total = next.total,
                        revision = minOf(result.revision, next.revision))
                }
                if (!current() || state.value.current.detailUnavailable) return@launch
                val baseline = knownRevision
                if (baseline == null) knownRevision = result.revision
                val outdated = baseline != null && result.revision < baseline
                if (baseline != null && outdated) pendingRevision = maxOf(pendingRevision ?: baseline, latestObservedRevision ?: baseline)
                update { it.copy(sections = it.sections + (kind to CatalogSection(
                    (if (append) existing?.items.orEmpty() else emptyList()) + result.items, result.total,
                    if (append) existing?.offset ?: request.offset else request.offset,
                    request.limit, loaded = true, needsReload = outdated))) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current()) update { current ->
                    val old = current.sections[kind] ?: return@update current
                    current.copy(sections = current.sections + (kind to old.copy(loading = false,
                        error = if (quiet && old.loaded) null else catalogError(error))))
                }
            } finally {
                if (current()) { jobs.remove(jobKey); requests.remove(jobKey); applyPendingRevision() }
            }
        }
        jobs[jobKey]?.start()
    }

    private fun loadDetail(quiet: Boolean = false) {
        if (!visible) return
        val item = state.value.current.item ?: return
        val token = generation
        jobs.remove("detail")?.cancel()
        val requestId = ++requestSerial
        requests["detail"] = requestId
        fun current() = token == generation && requests["detail"] == requestId
        update { it.copy(detailLoading = true, detailError = null, detailNeedsReload = true) }
        jobs["detail"] = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = reader.detail(item.kind, item.id)
                if (current()) {
                    val recovering = state.value.current.detailUnavailable
                    update { it.copy(item = result, detailResolved = true, detailLoading = false,
                        detailNeedsReload = false, detailUnavailable = false,
                        sections = if (recovering) emptyMap() else it.sections) }
                    if (recovering) state.value.current.sectionKinds.forEach { loadSection(it) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current()) update {
                    if (error is ApiException && error.status == 404) it.copy(detailLoading = false, detailResolved = true,
                        detailNeedsReload = false, detailUnavailable = true, sections = emptyMap(),
                        detailError = "This music is no longer saved. Go back to search.")
                    else it.copy(detailLoading = false,
                        detailError = if (quiet && it.detailResolved) null else catalogError(error))
                }
            } finally {
                if (current()) { jobs.remove("detail"); requests.remove("detail"); applyPendingRevision() }
            }
        }
        jobs["detail"]?.start()
    }

    /** Call on a fixed lifecycle-visible 15-second cadence, not after user interactions. */
    suspend fun checkRevision() {
        if (!visible || revisionBusy) return
        revisionBusy = true
        // Normal inner navigation preserves revision knowledge; an explicit fresh root does not
        // accept a poll begun for the discarded stack, even if its transport ignores cancellation.
        val rootKey = state.value.stack.first().key
        try {
            val revision = reader.revision()
            if (!visible || state.value.stack.first().key != rootKey) return
            catchUpAllowed = true
            latestObservedRevision = maxOf(latestObservedRevision ?: revision, revision)
            val known = knownRevision
            val firstValidation = !revisionValidated
            revisionValidated = true
            if (known == null) knownRevision = revision
            // A child listing may already have established the baseline while its older parent
            // detail is still pending. The first cheap revision fence is independent of that.
            if ((firstValidation && state.value.current.item != null) || (known != null && revision > known)) {
                pendingRevision = maxOf(pendingRevision ?: revision, revision, known ?: revision)
            }
            applyPendingRevision()
            if (jobs.isEmpty() && !composing && queryJob?.isActive != true) loadVisible()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Background checks never replace a usable screen with a connection error.
        } finally { revisionBusy = false }
    }

    private fun applyPendingRevision() {
        if (!visible || composing || jobs.isNotEmpty() || queryJob?.isActive == true) return
        val revision = pendingRevision ?: return
        if (!catchUpAllowed) return
        catchUpAllowed = false
        pendingRevision = null
        knownRevision = maxOf(knownRevision ?: revision, revision)
        mutable.value = mutable.value.copy(stack = mutable.value.stack.map { screen ->
            screen.copy(stale = true, sections = screen.sections.mapValues { (_, section) ->
                // A new revision invalidates the prefix too, not just a failed/pending expansion.
                // Preserve the listener's requested extent, but rebuild it from a fresh snapshot.
                section.copy(appendPending = false, requestedOffset = null,
                    revalidateCount = maxOf(section.revalidateCount,
                        if (section.appendPending) section.nextOffset + section.limit - section.offset else 0))
            })
        })
        loadVisible()
    }

    fun close() { visible = false; cancelReads() }
}

fun cleanCatalogQuery(value: String): String = buildString {
    var index = 0
    var count = 0
    while (index < value.length && count < 200) {
        val character = value[index++]
        if (character.isISOControl() || character.isLowSurrogate()) continue
        if (character.isHighSurrogate()) {
            if (index >= value.length || !value[index].isLowSurrogate()) continue
            append(character); append(value[index++])
        } else append(character)
        count++
    }
}
internal fun catalogError(error: Exception): String = when (error) {
    is ApiException -> error.message
    is IOException -> "Cannot reach your server. Try again."
    else -> "Could not load this music. Try again."
}

fun itemDuration(durationMs: Long): String? = if (durationMs <= 0) null else {
    val seconds = durationMs / 1000
    "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
fun itemYear(item: LibraryItem): String? = item.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
fun itemMetadata(item: LibraryItem): String = listOfNotNull(
    itemYear(item), itemDuration(item.durationMs),
    if (!item.playable) "Unavailable" else null,
).joinToString(" · ")
