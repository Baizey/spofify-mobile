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
internal class SearchViewModel(private val api: SearchApi) : ViewModel() {
    private sealed interface Request {
        data object First : Request
        data class Next(val cursor: String) : Request
        data class Rebuild(val minimumItems: Int) : Request
    }

    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var consumedAnchorGeneration = 0L
    private var consumedFocusIntent = 0
    private var timer: Job? = null
    private var requestJob: Job? = null
    private var activeRequest: Request? = null
    private var retryRequest: Request? = null
    private var interruptedRequest: Request? = null
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
        retryRequest = null
        interruptedRequest = null
        snapshot = null
        val paused = current.paused
        mutable.value = SearchState(query = value, paused = paused, waiting = value.isNotBlank() && !paused,
            queryGeneration = current.queryGeneration + 1)
        if (value.isBlank() || paused) return
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
        val interrupted = interruptedRequest
        interruptedRequest = null
        if (interrupted != null) load(interrupted, resuming = true)
        else if (!current.loaded) load(Request.First, resuming = true)
        else mutable.value = current.copy(paused = false)
    }

    fun pause() {
        interruptedRequest = activeRequest ?: if (mutable.value.waiting) Request.First else interruptedRequest
        cancelRequests()
        mutable.value = mutable.value.copy(waiting = false, loading = false, paused = true)
    }

    fun more() {
        val current = mutable.value
        if (!current.busy && current.error == null) current.nextCursor?.let { load(Request.Next(it)) }
    }

    fun retry() {
        if (!mutable.value.busy && mutable.value.error != null) load(retryRequest ?: Request.First)
    }

    private fun load(request: Request, resuming: Boolean = false) {
        val before = mutable.value
        if (before.busy || before.query.isBlank() || before.paused && !resuming) return
        val token = ++generation
        val query = before.query.trim()
        val base = snapshot
        activeRequest = request
        interruptedRequest = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = readSnapshot(request, base, query)
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                snapshot = result
                retryRequest = null
                activeRequest = null
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                val code = (failure as? SearchException)?.code ?: SearchErrorCode.INVALID_RESPONSE
                val failedRequest = activeRequest ?: request
                retryRequest = if (failedRequest is Request.Next && code in
                    setOf(SearchErrorCode.INVALID_SEARCH_REQUEST, SearchErrorCode.INVALID_RESPONSE))
                    Request.Rebuild((base?.items?.size ?: 0) + 1)
                else failedRequest
                activeRequest = null
                requestJob = null
                mutable.value = mutable.value.copy(
                    loading = false,
                    error = code,
                )
            } finally {
                if (token == generation) {
                    activeRequest = null
                    requestJob = null
                    mutable.value = mutable.value.copy(loading = false)
                }
            }
        }
        requestJob = job
        mutable.value = before.copy(loading = true, paused = false, error = null)
        job.start()
    }

    private suspend fun readSnapshot(request: Request, base: SearchResultSnapshot?, query: String): SearchResultSnapshot {
        return try {
            when (request) {
                Request.First -> readFirst(query)
                is Request.Next -> readNext(requireNotNull(base), request.cursor)
                is Request.Rebuild -> rebuild(query, request.minimumItems)
            }
        } catch (failure: SearchException) {
            if (failure.code != SearchErrorCode.SEARCH_EXPIRED) throw failure
            currentCoroutineContext().ensureActive()
            val recovery = if (request is Request.Rebuild) request
                else Request.Rebuild((base?.items?.size ?: 0) + 1)
            activeRequest = recovery
            // One quiet recovery attempt per operation, not an unbounded retry loop.
            rebuild(query, recovery.minimumItems)
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
        activeRequest = null
    }
}

internal fun searchErrorMessage(code: SearchErrorCode): String = when (code) {
    SearchErrorCode.SEARCH_EXPIRED -> "Can’t load search results. Try again."
    SearchErrorCode.SEARCH_INDEX_NOT_READY -> "The search index isn’t ready yet."
    SearchErrorCode.SEARCH_UNAVAILABLE -> "Search is unavailable."
    SearchErrorCode.SEARCH_BUSY -> "Search is busy. Try again shortly."
    SearchErrorCode.INVALID_SEARCH_REQUEST -> "This search couldn’t be completed. Try a new search."
    SearchErrorCode.INVALID_RESPONSE -> "Can’t read search results."
    SearchErrorCode.CONNECTION_FAILED -> "Can’t connect to search."
    SearchErrorCode.HTTP_ERROR -> "Can’t load search results."
}
