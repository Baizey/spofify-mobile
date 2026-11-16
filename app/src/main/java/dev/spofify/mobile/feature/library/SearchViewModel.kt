package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.validSearchQuery
import dev.spofify.mobile.core.search.SearchApi
import dev.spofify.mobile.core.search.SearchErrorCode
import dev.spofify.mobile.core.search.SearchException
import dev.spofify.mobile.core.search.SearchPage
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
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val nextCursor: String? = null,
    val resultCount: Int? = null,
    val expiresAt: Instant? = null,
    val error: SearchErrorCode? = null,
    val retryStartsOver: Boolean = false,
    // Local list anchoring only, never a server snapshot/version claim.
    val scrollResetToken: Long = 0,
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
    }

    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var consumedFocusIntent = 0
    private var timer: Job? = null
    private var requestJob: Job? = null
    private var activeRequest: Request? = null
    private var retryRequest: Request? = null
    private var interruptedRequest: Request? = null
    private var seenCursors: Set<String> = emptySet()

    fun consumeFocusIntent(value: Int): Boolean {
        if (value <= consumedFocusIntent) return false
        consumedFocusIntent = value
        return true
    }

    fun query(value: String) {
        if (!validSearchQuery(value) || value == mutable.value.query) return
        cancelRequests()
        retryRequest = null
        interruptedRequest = null
        seenCursors = emptySet()
        mutable.value = SearchState(query = value, waiting = value.isNotBlank())
        if (value.isBlank()) return
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
        if (current.query.isBlank() || current.busy || current.error != null) return
        val interrupted = interruptedRequest
        interruptedRequest = null
        if (interrupted != null) load(interrupted)
        else if (!current.loaded) load(Request.First)
    }

    fun pause() {
        interruptedRequest = activeRequest ?: if (mutable.value.waiting) Request.First else interruptedRequest
        cancelRequests()
        mutable.value = mutable.value.copy(waiting = false, loading = false)
    }

    fun more() {
        val current = mutable.value
        if (!current.busy && current.error == null) current.nextCursor?.let { load(Request.Next(it)) }
    }

    fun retry() {
        if (!mutable.value.busy && mutable.value.error != null) load(retryRequest ?: Request.First)
    }

    private fun load(request: Request) {
        val before = mutable.value
        if (before.busy || before.query.isBlank()) return
        val token = ++generation
        val query = before.query.trim()
        activeRequest = request
        interruptedRequest = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = when (request) {
                    Request.First -> api.search(query)
                    is Request.Next -> api.next(request.cursor)
                }
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                val items = validatePage(request, before, page)
                seenCursors = if (request == Request.First) emptySet() else seenCursors
                page.nextCursor?.let { seenCursors = seenCursors + it }
                retryRequest = null
                activeRequest = null
                requestJob = null
                mutable.value = SearchState(
                    query = mutable.value.query,
                    items = items,
                    loaded = true,
                    nextCursor = page.nextCursor,
                    resultCount = page.resultCount,
                    expiresAt = page.expiresAt,
                    scrollResetToken = if (request == Request.First) token else before.scrollResetToken,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                val code = (failure as? SearchException)?.code ?: SearchErrorCode.INVALID_RESPONSE
                val restart = code == SearchErrorCode.SEARCH_EXPIRED || request is Request.Next &&
                    code in setOf(SearchErrorCode.INVALID_SEARCH_REQUEST, SearchErrorCode.INVALID_RESPONSE)
                retryRequest = if (restart) Request.First else request
                activeRequest = null
                requestJob = null
                mutable.value = mutable.value.copy(
                    loading = false,
                    error = code,
                    nextCursor = if (restart) null else mutable.value.nextCursor,
                    retryStartsOver = restart || request == Request.First && before.loaded,
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
        mutable.value = before.copy(loading = true, error = null)
        job.start()
    }

    private fun validatePage(request: Request, before: SearchState, page: SearchPage): List<MusicItem> {
        val items = when (request) {
            Request.First -> page.items.toList()
            is Request.Next -> {
                check(before.loaded && before.nextCursor == request.cursor)
                check(before.resultCount == page.resultCount && before.expiresAt == page.expiresAt)
                check(page.items.isNotEmpty())
                before.items + page.items
            }
        }
        check(items.distinctBy(MusicItem::key).size == items.size)
        check(items.size <= page.resultCount)
        check((page.nextCursor != null) == (items.size < page.resultCount))
        if (page.nextCursor != null) {
            check(page.items.isNotEmpty())
            if (request is Request.Next) check(page.nextCursor !in seenCursors)
        }
        return items
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
    SearchErrorCode.SEARCH_EXPIRED -> "Search expired. Search again to refresh results."
    SearchErrorCode.SEARCH_INDEX_NOT_READY -> "The search index isn’t ready yet."
    SearchErrorCode.SEARCH_UNAVAILABLE -> "Search is unavailable."
    SearchErrorCode.SEARCH_BUSY -> "Search is busy. Try again shortly."
    SearchErrorCode.INVALID_SEARCH_REQUEST -> "This search couldn’t be completed. Try a new search."
    SearchErrorCode.INVALID_RESPONSE -> "Can’t read search results."
    SearchErrorCode.CONNECTION_FAILED -> "Can’t connect to search."
    SearchErrorCode.HTTP_ERROR -> "Can’t load search results."
}
