package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.validSearchQuery
import dev.spofify.mobile.core.search.SearchApi
import dev.spofify.mobile.core.search.SearchErrorCode
import dev.spofify.mobile.core.search.SearchException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SearchState(
    val query: String = "",
    val items: List<MusicItem> = emptyList(),
    val waiting: Boolean = false,
    val paused: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val nextCursor: String? = null,
    val resultCount: Int? = null,
    val expiresAt: Instant? = null,
    val error: SearchErrorCode? = null,
    // A user edit, not a request attempt or a server snapshot/version.
    val queryGeneration: Long = 0,
) {
    val busy: Boolean get() = waiting || loading
    val hasMore: Boolean get() = nextCursor != null && error == null
    val empty: Boolean get() = loaded && !busy && error == null && items.isEmpty()
}

/** One ranked snapshot; cursor continuation never mixes it with a fresh search. */
internal class SearchViewModel(private var api: SearchApi) : ViewModel() {
    private var local = false
    private var owner: LibraryReadOwner? = null

    /** Invalidate the old cursor/jobs, not the destination, query or scroll anchor. */
    fun bind(next: SearchApi, device: Boolean, readOwner: LibraryReadOwner? = null) {
        if (api === next && local == device && owner === readOwner) return
        cancelRequests()
        api = next; local = device; owner = readOwner
        snapshot = null; interruptedAttempt = null
        val old = mutable.value
        mutable.value = SearchState(query = old.query, queryGeneration = old.queryGeneration, paused = old.paused)
        if (!old.paused && old.query.isNotBlank()) load(Request.First)
    }
    private sealed interface Request {
        data object First : Request
        data class Next(val cursor: String) : Request
        data class Rebuild(val minimumItems: Int) : Request
    }

    // Retain the spent recovery allowance across destination pauses.
    private data class Attempt(val request: Request, val recovered: Boolean = false, val wait: Boolean = false)

    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var consumedAnchorGeneration = 0L
    private var consumedFocusIntent = 0
    private var timer: Job? = null
    private var requestJob: Job? = null
    private var activeAttempt: Attempt? = null
    private var interruptedAttempt: Attempt? = null
    private var snapshot: SearchResultSnapshot? = null

    fun consumeFocusIntent(value: Int): Boolean {
        if (value <= consumedFocusIntent) return false
        consumedFocusIntent = value
        return true
    }

    fun consumeInitialAnchor(queryGeneration: Long): Boolean {
        val current = mutable.value
        if (queryGeneration != current.queryGeneration || queryGeneration <= consumedAnchorGeneration ||
            !current.loaded || current.items.isEmpty()) return false
        consumedAnchorGeneration = queryGeneration
        return true
    }

    fun query(value: String) {
        val current = mutable.value
        if (!validSearchQuery(value) || value == current.query) return
        cancelRequests()
        interruptedAttempt = null
        snapshot = null
        val paused = current.paused
        mutable.value = SearchState(query = value, paused = paused, waiting = value.isNotBlank() && !paused && !local,
            queryGeneration = current.queryGeneration + 1)
        if (value.isBlank() || paused) return
        if (local) { load(Request.First); return }
        val token = generation
        timer = viewModelScope.launch {
            delay(300)
            if (token != generation) return@launch
            timer = null
            mutable.value = mutable.value.copy(waiting = false)
            // A collector can pause/clear synchronously when the debounce finishes.
            if (token != generation) return@launch
            load(Request.First)
        }
    }

    fun resume() {
        val current = mutable.value
        if (current.query.isBlank() || current.busy || current.error != null) {
            mutable.value = current.copy(paused = false)
            return
        }
        val interrupted = interruptedAttempt
        interruptedAttempt = null
        if (interrupted != null) load(interrupted, resuming = true)
        else if (!current.loaded) load(Attempt(Request.First), resuming = true)
        else mutable.value = current.copy(paused = false)
    }

    fun pause() {
        interruptedAttempt = activeAttempt ?: if (mutable.value.waiting) Attempt(Request.First) else interruptedAttempt
        cancelRequests()
        mutable.value = mutable.value.copy(waiting = false, loading = false, paused = true)
    }

    fun more() {
        val current = mutable.value
        if (!current.busy && current.error == null) current.nextCursor?.let { load(Request.Next(it)) }
    }

    private fun load(request: Request) = load(Attempt(request))

    private fun load(attempt: Attempt, resuming: Boolean = false) {
        val before = mutable.value
        if (requestJob?.isActive == true || before.busy || before.query.isBlank() || before.paused && !resuming) return
        val token = ++generation
        val query = before.query.trim()
        val base = snapshot
        val readOwner = owner
        val device = local
        fun publish(block: () -> Unit) {
            if (token == generation) {
                if (readOwner == null) block() else readOwner.publish(block)
            }
        }
        activeAttempt = attempt
        interruptedAttempt = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = if (readOwner == null) readSnapshot(attempt, base, query, device)
                else readOwner.read { readSnapshot(attempt, base, query, device) }
                currentCoroutineContext().ensureActive()
                publish {
                    snapshot = result
                    activeAttempt = null
                    requestJob = null
                    mutable.value = SearchState(
                        query = mutable.value.query,
                        items = result.items,
                        loaded = true,
                        nextCursor = result.nextCursor,
                        resultCount = result.resultCount,
                        expiresAt = result.expiresAt,
                        queryGeneration = before.queryGeneration,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                val code = (failure as? SearchException)?.code ?: SearchErrorCode.INVALID_RESPONSE
                publish {
                    activeAttempt = null
                    requestJob = null
                    mutable.value = mutable.value.copy(loading = false, error = code)
                }
            } finally {
                publish {
                    activeAttempt = null
                    requestJob = null
                    mutable.value = mutable.value.copy(loading = false)
                }
            }
        }
        requestJob = job
        publish { mutable.value = before.copy(loading = !device, paused = false, error = null) }
        job.start()
    }

    private suspend fun readSnapshot(attempt: Attempt, base: SearchResultSnapshot?, query: String, device: Boolean): SearchResultSnapshot {
        suspend fun read(action: Attempt): SearchResultSnapshot {
            if (action.wait) delay(1_000)
            currentCoroutineContext().ensureActive()
            return when (val request = action.request) {
                Request.First -> readFirst(query)
                is Request.Next -> readNext(requireNotNull(base), request.cursor)
                is Request.Rebuild -> rebuild(query, request.minimumItems)
            }
        }
        return try {
            read(attempt)
        } catch (failure: SearchException) {
            currentCoroutineContext().ensureActive()
            if (attempt.recovered || failure.retryAfter) throw failure
            val recovery = when {
                // Rebuild from page one; never reissue an explicitly expired cursor.
                failure.code == SearchErrorCode.SEARCH_EXPIRED ->
                    Attempt(Request.Rebuild((base?.items?.size ?: 0) + 1), recovered = true)
                !device && failure.transient -> attempt.copy(recovered = true, wait = true)
                else -> throw failure
            }
            activeAttempt = recovery
            read(recovery)
        }
    }

    private suspend fun rebuild(query: String, minimumItems: Int): SearchResultSnapshot {
        var staged = readFirst(query)
        while (staged.items.size < minimumItems) {
            val cursor = staged.nextCursor ?: break
            staged = readNext(staged, cursor)
        }
        // Publish only a complete fresh prefix through the old depth plus a new page.
        return staged
    }

    private suspend fun readFirst(query: String): SearchResultSnapshot {
        val page = api.search(query)
        currentCoroutineContext().ensureActive()
        return SearchResultSnapshot.first(page)
    }

    private suspend fun readNext(base: SearchResultSnapshot, cursor: String): SearchResultSnapshot {
        val page = api.next(cursor)
        currentCoroutineContext().ensureActive()
        return base.append(cursor, page)
    }

    private fun cancelRequests() {
        generation++
        timer?.cancel()
        timer = null
        requestJob?.cancel()
        requestJob = null
        activeAttempt = null
    }
}

internal fun searchErrorMessage(code: SearchErrorCode): String = when (code) {
    SearchErrorCode.SEARCH_EXPIRED -> "Can’t load search results."
    SearchErrorCode.SEARCH_INDEX_NOT_READY -> "The search index isn’t ready yet."
    SearchErrorCode.SEARCH_UNAVAILABLE -> "Search is unavailable."
    SearchErrorCode.SEARCH_BUSY -> "Search is busy."
    SearchErrorCode.INVALID_SEARCH_REQUEST -> "This search couldn’t be completed."
    SearchErrorCode.INVALID_RESPONSE -> "Can’t read search results."
    SearchErrorCode.CONNECTION_FAILED -> "Can’t connect to search."
    SearchErrorCode.HTTP_ERROR -> "Can’t load search results."
}
