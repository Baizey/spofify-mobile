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
    /** Raw server positions consumed, independent of deduplicated visible identities. */
    val consumed: Int = 0,
    val revision: Long? = null,
    val ended: Boolean = false,
    val refreshRequired: Boolean = false,
    /** Earlier grouped results yield after one append until a later group is visited. */
    val demandPaused: Boolean = false,
) {
    val hasPrevious get() = offset > 0
    val nextOffset get() = offset + consumed
    val hasNext get() = loaded && !ended && nextOffset < total && nextOffset <= 1_000_000
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
    /** First visible artist release, independent of responsive row grouping. */
    val releaseAnchorId: String? = null,
    val stale: Boolean = false,
    /** Row identity incarnation; unlike requests/scroll this changes on query/filter ABA. */
    val resultGeneration: Long = 0,
) {
    val emptySearch get() = item == null && search && query.isBlank()
    val sectionKinds: List<String> get() = when (item?.kind) {
        "artist" -> listOf("albums")
        "album" -> listOf("tracks")
        "track" -> emptyList()
        else -> if (kind == "all") CatalogKinds else listOf(kind)
    }

    fun hasLaterGroups(kind: String): Boolean = item == null && search && this.kind == "all" &&
        sectionKinds.drop(sectionKinds.indexOf(kind) + 1).any { later ->
            sections[later]?.let { !it.loaded || it.items.isNotEmpty() || it.error != null } != false
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
    /** Event-time presentation fence: conflated A→B→A navigation must not revive an intent.
     * Read/progress/scroll publications do not advance it; it is never persisted or authority. */
    internal var navigationVersion = 0L
        private set
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
        navigationVersion++
        cancelReads(markInterrupted = false)
        composing = false
        pendingRevision = null
        catchUpAllowed = false
        mutable.value = CatalogNavigationState(listOf(CatalogScreen(key = ++serial, kind = "all", search = true)))
    }

    fun open(item: LibraryItem) {
        if (item.kind !in listOf("artist", "album", "track")) return
        navigationVersion++
        cancelReads()
        mutable.value = mutable.value.copy(stack = mutable.value.stack + CatalogScreen(key = ++serial, item = item))
        loadVisible()
    }

    fun back(): Boolean {
        if (!state.value.canGoBack) return false
        navigationVersion++
        cancelReads()
        mutable.value = mutable.value.copy(stack = mutable.value.stack.dropLast(1))
        loadVisible()
        return true
    }

    fun setComposing(value: Boolean) {
        composing = value
        if (!value) applyPendingRevision()
    }

    fun rememberScroll(screenKey: Long, index: Int, offset: Int, expectedQuery: String? = null,
        expectedReleaseGroup: String? = null, releaseAnchorId: String? = null) {
        val current = state.value.current
        if (current.key == screenKey && (expectedQuery == null || current.query == expectedQuery) &&
            (expectedReleaseGroup == null || current.releaseGroup == expectedReleaseGroup)) {
            update { it.copy(scrollIndex = index, scrollOffset = offset, releaseAnchorId = releaseAnchorId) }
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
        navigationVersion++
        cancelReads()
        update { it.copy(query = cleaned, search = true, item = null, sections = emptyMap(),
            scrollIndex = 0, scrollOffset = 0, releaseAnchorId = null, detailResolved = false, resultGeneration = ++serial) }
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
        navigationVersion++
        cancelReads()
        update { change(it).copy(sections = emptyMap(), scrollIndex = 0, scrollOffset = 0, releaseAnchorId = null, resultGeneration = ++serial) }
        loadVisible()
    }

    fun selectReleaseGroup(group: String) {
        if (group !in ReleaseGroups.map { it.first } || group == state.value.current.releaseGroup) return
        navigationVersion++
        cancelReads()
        update { it.copy(releaseGroup = group, sections = emptyMap(), scrollIndex = 0, scrollOffset = 0, releaseAnchorId = null, resultGeneration = ++serial) }
        loadVisible()
    }

    /** Geometry demand, distinct from explicit compatibility/test expansion. Never starve a
     * later result group: one earlier append yields until that later group is actually seen. */
    fun demandMore(kind: String) {
        val screen = state.value.current
        val section = screen.sections[kind] ?: return
        if (screen.hasLaterGroups(kind) && section.demandPaused) return
        more(kind)
        if (screen.hasLaterGroups(kind) && state.value.current.sections[kind]?.loading == true)
            update { it.copy(sections = it.sections + (kind to it.sections.getValue(kind).copy(demandPaused = true))) }
    }

    fun visibleSection(screenKey: Long, resultGeneration: Long, kind: String?) {
        val screen = state.value.current
        if (screen.key != screenKey || screen.resultGeneration != resultGeneration || kind == null) return
        val prior = screen.sectionKinds.takeWhile { it != kind }.toSet()
        if (screen.sections.none { (name, section) -> name in prior && section.demandPaused }) return
        update { it.copy(sections = it.sections.mapValues { (name, section) ->
            if (name in prior) section.copy(demandPaused = false) else section
        }) }
    }

    fun more(kind: String) {
        val section = state.value.current.sections[kind] ?: return
        if (!visible || section.loading || section.error != null || section.needsReload || !section.hasNext) return
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

    /** Explicit recovery alone clears a failed batch; Retry retains its exact requested offset. */
    fun retry(kind: String) {
        if (state.value.current.sections[kind]?.refreshRequired == true) {
            navigationVersion++
            update { screen -> screen.copy(sections = screen.sections + (kind to screen.sections.getValue(kind).copy(
                appendPending = false, requestedOffset = null, refreshRequired = false,
                revalidateCount = screen.sections.getValue(kind).items.size))) }
        }
        loadSection(kind)
    }
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
            if (section?.error == null && (section?.loaded != true || section.needsReload || stale) &&
                (!preservePending || section?.loading != true))
                loadSection(kind, quiet = stale || section?.loaded == true)
        }
        update { it.copy(stale = false) }
    }

    private fun loadSection(kind: String, quiet: Boolean = false) {
        if (!visible || kind !in state.value.current.sectionKinds || state.value.current.emptySearch || state.value.current.detailUnavailable ||
            state.value.current.sections[kind]?.loading == true) return
        val token = generation
        val screen = state.value.current
        val request = screen.request(kind)
        val existing = screen.sections[kind]
        val append = existing?.appendPending == true
        val retainedCount = if (!append && existing != null && existing.offset == request.offset)
            maxOf(existing.consumed, existing.items.size, existing.revalidateCount, request.limit) else request.limit
        val jobKey = "section:$kind"
        jobs.remove(jobKey)?.cancel()
        val requestId = ++requestSerial
        requests[jobKey] = requestId
        fun current() = token == generation && requests[jobKey] == requestId && screen.key == state.value.current.key
        // Reserve before launching: repeated layout/demand callbacks cannot replace this flight.
        // A benign append read does not revoke existing source/row actions. Keep a failed
        // batch's latch through explicit Retry: cancellation/Back must not authorize auto-retry.
        // Successful publication below clears the error; loading merely hides its presentation.
        update { it.copy(sections = it.sections + (kind to (it.sections[kind] ?: CatalogSection(limit = request.limit)).copy(
            loading = true, needsReload = !append || existing?.needsReload == true))) }
        jobs[jobKey] = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var batchRequest = request.copy(limit = minOf(retainedCount, 100))
                var result = reader.browse(batchRequest)
                if (!current()) return@launch
                validateCatalogBatch(result, batchRequest)
                var exhausted = result.items.isEmpty()
                // Recover only the retained extent, never a hidden whole-catalog scan. Every
                // component must agree on revision/total before any rebuilt prefix is published.
                while (retainedCount > request.limit && result.items.size < retainedCount &&
                    request.offset + result.items.size < result.total && !exhausted) {
                    if (!current()) return@launch
                    val nextOffset = request.offset + result.items.size
                    if (nextOffset > 1_000_000) throw CatalogProgressException()
                    batchRequest = request.copy(offset = nextOffset, limit = minOf(retainedCount - result.items.size, 100))
                    val next = reader.browse(batchRequest)
                    if (!current()) return@launch
                    validateCatalogBatch(next, batchRequest)
                    if (result.revision != next.revision || result.total != next.total) throw CatalogSnapshotException()
                    val seen = result.items.mapTo(hashSetOf()) { it.kind to it.id }
                    if (next.items.isNotEmpty() && next.items.none { (it.kind to it.id) !in seen })
                        throw CatalogProgressException()
                    exhausted = next.items.isEmpty()
                    result = result.copy(items = result.items + next.items)
                }
                if (!current() || state.value.current.detailUnavailable) return@launch
                val baseline = knownRevision
                if (baseline == null) knownRevision = result.revision
                val outdated = baseline != null && result.revision < baseline
                if (baseline != null && outdated) pendingRevision = maxOf(pendingRevision ?: baseline, latestObservedRevision ?: baseline)
                if (baseline != null && result.revision > baseline) {
                    pendingRevision = maxOf(pendingRevision ?: result.revision, result.revision)
                    if (!append) {
                        navigationVersion++
                        // A newer sibling response is not proof that the retained groups are
                        // current. Keep them usable but fenced until the revision poll rebuilds.
                        update { current -> current.copy(sections = current.sections.mapValues { (_, section) ->
                            if (section.loaded && section.revision != result.revision) section.copy(needsReload = true) else section
                        }) }
                    }
                }
                if (append && (existing?.revision != result.revision || existing?.total != result.total || outdated)) {
                    if (baseline == null || result.revision > baseline)
                        pendingRevision = maxOf(pendingRevision ?: result.revision, result.revision)
                    throw CatalogSnapshotException()
                }
                val prefix = if (append) existing?.items.orEmpty() else emptyList()
                val identities = prefix.associateBy { it.kind to it.id }.toMutableMap()
                val added = result.items.filter { item ->
                    val previous = identities.put(item.kind to item.id, item)
                    if (previous != null && previous != item) throw CatalogProgressException()
                    previous == null
                }
                if (append && result.items.isNotEmpty() && added.isEmpty()) throw CatalogProgressException()
                val consumed = (if (append) existing?.consumed ?: 0 else 0) + result.items.size
                val origin = if (append) existing?.offset ?: request.offset else request.offset
                if (!exhausted && origin + consumed < result.total && origin + consumed > 1_000_000)
                    throw CatalogProgressException()
                update { it.copy(sections = it.sections + (kind to CatalogSection(
                    items = prefix + added, total = result.total, offset = origin,
                    limit = request.limit, loaded = true, needsReload = outdated,
                    consumed = consumed, revision = result.revision,
                    ended = exhausted || origin + consumed >= result.total,
                    demandPaused = state.value.current.sections[kind]?.demandPaused == true))) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current()) update { current ->
                    val old = current.sections[kind] ?: return@update current
                    val invalidSnapshot = error is CatalogSnapshotException || error is CatalogProgressException
                    if (invalidSnapshot) navigationVersion++
                    current.copy(sections = current.sections + (kind to old.copy(loading = false,
                        needsReload = old.needsReload || invalidSnapshot,
                        refreshRequired = old.refreshRequired || invalidSnapshot,
                        error = if (quiet && old.loaded && !append && !invalidSnapshot) null else catalogError(error))))
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
                    val previous = state.value.current
                    if (result.kind != item.kind || result.id != item.id) {
                        navigationVersion++
                        throw ApiException(200, "The server returned different music. Try again.")
                    }
                    // Resolving a seed can enrich credits/art without changing source identity.
                    // Once resolved, a changed detail is a new source; availability changes are
                    // significant even on the first read. Neither can renew a held old action.
                    if ((previous.detailResolved && previous.item != result) || previous.item?.playable != result.playable)
                        navigationVersion++
                    val recovering = previous.detailUnavailable
                    update { it.copy(item = result, detailResolved = true, detailLoading = false,
                        detailNeedsReload = false, detailUnavailable = false,
                        sections = if (recovering) emptyMap() else it.sections) }
                    if (recovering) state.value.current.sectionKinds.forEach { loadSection(it) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current()) update {
                    if (error is ApiException && error.status == 404) {
                        navigationVersion++
                        it.copy(detailLoading = false, detailResolved = true,
                            detailNeedsReload = false, detailUnavailable = true, sections = emptyMap(),
                            detailError = "This music is no longer saved. Go back to search.")
                    } else it.copy(detailLoading = false,
                        detailError = if (quiet && it.detailResolved && !(error is ApiException && error.status == 200))
                            null else catalogError(error))
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
        // The first cheap fence also re-reads a detail whose seed has no revision. That
        // same-baseline read is not a source change and must not retire an already open More.
        // A real revision advance, or a retained older prefix overtaken by an earlier poll,
        // still invalidates source authority before rebuilding anything.
        val sourceChanged = knownRevision?.let { revision > it } == true || state.value.stack.any { screen ->
            screen.sections.values.any { it.loaded && it.revision != revision }
        }
        knownRevision = maxOf(knownRevision ?: revision, revision)
        if (sourceChanged) navigationVersion++
        mutable.value = mutable.value.copy(stack = mutable.value.stack.map { screen ->
            screen.copy(stale = true, sections = screen.sections.mapValues { (_, section) ->
                // A same-revision first detail fence is not permission to retry a failed child
                // batch. Keep its exact frontier and latch, including across Back/visibility.
                // Only a changed source may discard that frontier and rebuild a fresh prefix.
                if (!sourceChanged && section.error != null) section else section.copy(
                    appendPending = false, requestedOffset = null, error = null, refreshRequired = false, demandPaused = false,
                    revalidateCount = maxOf(section.revalidateCount,
                        if (section.appendPending) section.nextOffset + section.limit - section.offset else 0))
            })
        })
        loadVisible()
    }

    fun close() { navigationVersion++; visible = false; cancelReads() }
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
private class CatalogSnapshotException : IOException("Music changed. Refresh these results.")
private class CatalogProgressException : IOException("Could not continue these results. Refresh to try again.")

private fun validateCatalogBatch(page: LibraryPage, request: CatalogRequest) {
    if (page.offset != request.offset || page.items.size > request.limit || page.total < 0 ||
        (page.items.isNotEmpty() && page.offset.toLong() + page.items.size > page.total.toLong()) ||
        page.items.any { it.id.isBlank() }) throw CatalogProgressException()
}

internal fun catalogError(error: Exception): String = when (error) {
    is CatalogSnapshotException, is CatalogProgressException -> error.message ?: "Refresh these results."
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
    itemYear(item),
    item.coverage.tracks.takeIf { item.kind != "track" && it > 0 }?.let { "$it ${if (it == 1) "song" else "songs"}" },
    itemDuration(item.durationMs),
    if (!item.playable) "Unavailable" else null,
).joinToString(" · ")
