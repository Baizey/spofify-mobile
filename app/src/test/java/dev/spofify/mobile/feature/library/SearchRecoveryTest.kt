package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModelStore
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.search.SearchApi
import dev.spofify.mobile.core.search.SearchErrorCode
import dev.spofify.mobile.core.search.SearchException
import dev.spofify.mobile.core.search.SearchPage
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var nextModel = 0

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(api: Api) = SearchViewModel(api).also { store.put("recovery-${nextModel++}", it) }

    @Test fun expiredDeepPrefixRebuildsThroughItsOldDepthAndPublishesSeventyFiveRowsAtomically() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        loadOldPrefix(api, model)
        val old = model.state.value
        val middle = CompletableDeferred<Unit>()
        val last = CompletableDeferred<Unit>()
        val publications = mutableListOf<SearchState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { publications += it }
        }
        api.first = { page("fresh", 0, expiry = freshExpiry) }
        api.continuation = { cursor ->
            when (cursor) {
                "old:50" -> expired()
                "fresh:25" -> { middle.await(); page("fresh", 25, expiry = freshExpiry) }
                "fresh:50" -> { last.await(); page("fresh", 50, expiry = freshExpiry) }
                else -> error("Unexpected cursor")
            }
        }
        model.more()
        runCurrent()
        assertOldWhileBusy(old, model.state.value)
        assertEquals(listOf("old:25", "old:50", "fresh:25"), api.cursors)
        repeat(3) { model.more() }
        middle.complete(Unit)
        runCurrent()
        assertOldWhileBusy(old, model.state.value) // Fifty fresh rows are still not beyond the old depth.
        assertEquals(listOf("old:25", "old:50", "fresh:25", "fresh:50"), api.cursors)
        last.complete(Unit)
        advanceUntilIdle()
        val rebuilt = model.state.value
        assertEquals(batch("fresh", 0, 75), rebuilt.items)
        assertEquals(100, rebuilt.resultCount)
        assertEquals(freshExpiry, rebuilt.expiresAt)
        assertEquals("fresh:75", rebuilt.nextCursor)
        assertEquals(old.queryGeneration, rebuilt.queryGeneration)
        assertNull(rebuilt.error)
        assertFalse(rebuilt.busy)
        assertEquals(listOf(Triple("query", null, 25), Triple("query", null, 25)), api.firstRequests)
        val freshPublications = publications.filter { state -> state.items.any { it.id.startsWith("fresh-") } }
        assertTrue(freshPublications.isNotEmpty())
        assertTrue(freshPublications.all { it.items == batch("fresh", 0, 75) })
    }

    @Test fun expiryDuringRecoveryStopsAfterOneAttemptAndGenericUserRetryStartsAFreshDeepRebuild() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        loadOldPrefix(api, model)
        val old = model.state.value
        api.first = { page("failed", 0, expiry = freshExpiry) }
        api.continuation = { cursor ->
            when (cursor) {
                "old:50", "failed:25" -> expired()
                else -> error("Unexpected cursor")
            }
        }
        model.more()
        advanceUntilIdle()
        assertEquals(old.items, model.state.value.items)
        assertEquals(old.expiresAt, model.state.value.expiresAt)
        assertEquals(SearchErrorCode.SEARCH_EXPIRED, model.state.value.error)
        assertGenericRetry(model.state.value)
        assertEquals(2, api.firstRequests.size)
        assertEquals(listOf("old:25", "old:50", "failed:25"), api.cursors)
        repeat(3) { model.more(); model.resume() }
        advanceUntilIdle()
        assertEquals(2, api.firstRequests.size)
        assertEquals(3, api.cursors.size)

        api.first = { page("retry", 0, expiry = retryExpiry) }
        api.continuation = { cursor ->
            when (cursor) {
                "retry:25" -> page("retry", 25, expiry = retryExpiry)
                "retry:50" -> page("retry", 50, expiry = retryExpiry)
                else -> error("Retry reused an obsolete cursor")
            }
        }
        model.retry()
        advanceUntilIdle()
        assertEquals(3, api.firstRequests.size)
        assertEquals(listOf("old:25", "old:50", "failed:25", "retry:25", "retry:50"), api.cursors)
        assertEquals(batch("retry", 0, 75), model.state.value.items)
        assertEquals(old.queryGeneration, model.state.value.queryGeneration)
        assertNull(model.state.value.error)
    }

    @Test fun failedRebuildRetainsTheOldPrefixAndRetryRestartsFreshRatherThanRetryingItsOldCursor() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        loadOldPrefix(api, model)
        val old = model.state.value
        api.first = { page("failed", 0, expiry = freshExpiry) }
        api.continuation = { cursor ->
            when (cursor) {
                "old:50" -> expired()
                "failed:25" -> throw SearchException(SearchErrorCode.CONNECTION_FAILED)
                else -> error("Unexpected cursor")
            }
        }
        model.more()
        advanceUntilIdle()
        assertEquals(old.items, model.state.value.items)
        assertEquals(old.resultCount, model.state.value.resultCount)
        assertEquals(old.expiresAt, model.state.value.expiresAt)
        assertEquals(SearchErrorCode.CONNECTION_FAILED, model.state.value.error)
        assertGenericRetry(model.state.value)
        val gate = CompletableDeferred<Unit>()
        api.first = { page("retry", 0, expiry = retryExpiry) }
        api.continuation = { cursor ->
            when (cursor) {
                "retry:25" -> page("retry", 25, expiry = retryExpiry)
                "retry:50" -> { gate.await(); page("retry", 50, expiry = retryExpiry) }
                else -> error("Retry reused an obsolete cursor")
            }
        }
        model.retry()
        runCurrent()
        assertOldWhileBusy(old, model.state.value)
        assertEquals(3, api.firstRequests.size)
        assertEquals(listOf("old:25", "old:50", "failed:25", "retry:25", "retry:50"), api.cursors)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(batch("retry", 0, 75), model.state.value.items)
        assertEquals(retryExpiry, model.state.value.expiresAt)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun pauseAndResumeRememberTheRebuildGoalWithoutReusingAnExpiredOrCancelledCursor() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        loadOldPrefix(api, model)
        val old = model.state.value
        val gate = CompletableDeferred<Unit>()
        api.first = { page("interrupted", 0, expiry = freshExpiry) }
        api.continuation = { cursor ->
            when (cursor) {
                "old:50" -> expired()
                "interrupted:25" -> {
                    withContext(NonCancellable) { gate.await() }
                    page("interrupted", 25, expiry = freshExpiry)
                }
                "resumed:25" -> page("resumed", 25, expiry = retryExpiry)
                "resumed:50" -> page("resumed", 50, expiry = retryExpiry)
                else -> error("Read an obsolete cursor after pause")
            }
        }
        model.more()
        runCurrent()
        assertOldWhileBusy(old, model.state.value)
        model.pause()
        assertTrue(model.state.value.paused)
        assertFalse(model.state.value.busy)
        assertEquals(old.items, model.state.value.items)
        repeat(3) { model.more() }
        runCurrent()
        assertEquals(listOf("old:25", "old:50", "interrupted:25"), api.cursors)
        api.first = { page("resumed", 0, expiry = retryExpiry) }
        model.resume()
        runCurrent()
        assertFalse(model.state.value.paused)
        assertEquals(batch("resumed", 0, 75), model.state.value.items)
        assertEquals(old.queryGeneration, model.state.value.queryGeneration)
        val accepted = model.state.value
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(accepted, model.state.value)
        assertEquals(3, api.firstRequests.size)
        assertEquals(listOf("old:25", "old:50", "interrupted:25", "resumed:25", "resumed:50"), api.cursors)
    }

    @Test fun clearOrNewQueryFencesNoncooperativeRebuildSuccessAndFailureBeforeAnyMoreOldReads() = runTest(dispatcher) {
        for (clear in listOf(false, true)) for (lateFailure in listOf(false, true)) {
            val api = Api()
            val model = model(api)
            loadOldPrefix(api, model)
            val oldGate = CompletableDeferred<Unit>()
            val newGate = CompletableDeferred<Unit>()
            api.first = { query ->
                if (query == "new") {
                    newGate.await()
                    page("new", 0, total = 25, expiry = retryExpiry)
                } else page("cancelled", 0, expiry = freshExpiry)
            }
            api.continuation = { cursor ->
                when (cursor) {
                    "old:50" -> expired()
                    "cancelled:25" -> {
                        withContext(NonCancellable) { oldGate.await() }
                        if (lateFailure) throw SearchException(SearchErrorCode.SEARCH_UNAVAILABLE, 503)
                        page("cancelled", 25, expiry = freshExpiry)
                    }
                    else -> error("Read another old cursor after query retirement")
                }
            }
            model.more()
            runCurrent()
            assertTrue(model.state.value.busy)
            model.query(if (clear) "" else "new")
            advanceTimeBy(300)
            runCurrent()
            val replacement = model.state.value
            assertEquals(if (clear) "" else "new", replacement.query)
            assertTrue(replacement.items.isEmpty())
            assertEquals(!clear, replacement.busy)
            oldGate.complete(Unit)
            runCurrent()
            assertEquals(replacement, model.state.value)
            assertEquals(listOf("old:25", "old:50", "cancelled:25"), api.cursors)
            newGate.complete(Unit)
            advanceUntilIdle()
            if (clear) {
                assertTrue(model.state.value.items.isEmpty())
                assertEquals(2, api.firstRequests.size)
            } else {
                assertEquals(batch("new", 0, 25), model.state.value.items)
                assertEquals(listOf("query", "query", "new"), api.firstRequests.map { it.first })
            }
            assertNull(model.state.value.error)
            assertFalse(model.state.value.busy)
        }
    }

    @Test fun terminalShrunkenOrEmptyFreshSnapshotTruthfullyReplacesTheEntireOldPrefix() = runTest(dispatcher) {
        for (total in listOf(0, 12, 40)) {
            val api = Api()
            val model = model(api)
            loadOldPrefix(api, model)
            val old = model.state.value
            val gate = CompletableDeferred<Unit>()
            api.first = { gate.await(); page("shrunken", 0, total, freshExpiry) }
            api.continuation = { cursor ->
                when (cursor) {
                    "old:50" -> expired()
                    "shrunken:25" -> page("shrunken", 25, total, freshExpiry)
                    else -> error("Unexpected continuation beyond the terminal snapshot")
                }
            }
            model.more()
            runCurrent()
            assertOldWhileBusy(old, model.state.value)
            gate.complete(Unit)
            advanceUntilIdle()
            val result = model.state.value
            assertEquals(batch("shrunken", 0, total), result.items)
            assertEquals(total, result.resultCount)
            assertEquals(freshExpiry, result.expiresAt)
            assertEquals(old.queryGeneration, result.queryGeneration)
            assertEquals(total == 0, result.empty)
            assertNull(result.nextCursor)
            assertNull(result.error)
            assertFalse(result.hasMore)
            assertFalse(result.busy)
            assertEquals(2, api.firstRequests.size)
            assertEquals(if (total > 25) listOf("old:25", "old:50", "shrunken:25")
                else listOf("old:25", "old:50"), api.cursors)
        }
    }

    @Test fun firstGetExpiryIsQuietlyRetriedOnceAndRebuildRetryHasItsOwnBoundedAttempt() = runTest(dispatcher) {
        for (secondExpiry in listOf(false, true)) {
            val api = Api()
            val model = model(api)
            val gate = CompletableDeferred<Unit>()
            api.first = {
                val call = api.firstRequests.size
                if (call == 1 || secondExpiry && call == 3) expired()
                if (call == 2) {
                    gate.await()
                    if (secondExpiry) expired()
                }
                page("accepted", 0, total = 25, expiry = freshExpiry)
            }
            model.query("query")
            advanceTimeBy(300)
            runCurrent()
            assertEquals(2, api.firstRequests.size)
            assertTrue(model.state.value.busy)
            assertTrue(model.state.value.items.isEmpty())
            assertNull(model.state.value.error)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, api.firstRequests.size)
            if (secondExpiry) {
                assertEquals(SearchErrorCode.SEARCH_EXPIRED, model.state.value.error)
                assertGenericRetry(model.state.value)
                repeat(3) { model.more(); model.resume() }
                advanceUntilIdle()
                assertEquals(2, api.firstRequests.size)
                model.retry() // A new operation permits one recovery even for Request.Rebuild.
                advanceUntilIdle()
                assertEquals(4, api.firstRequests.size)
            }
            assertEquals(batch("accepted", 0, 25), model.state.value.items)
            assertTrue(model.state.value.loaded)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.error)
            assertTrue(api.cursors.isEmpty())
        }
    }

    private fun TestScope.loadOldPrefix(api: Api, model: SearchViewModel) {
        api.first = { page("old", 0) }
        api.continuation = { cursor ->
            check(cursor == "old:25")
            page("old", 25)
        }
        model.query("query")
        advanceTimeBy(300)
        runCurrent()
        model.more()
        runCurrent()
        assertEquals(batch("old", 0, 50), model.state.value.items)
        assertEquals("old:50", model.state.value.nextCursor)
        assertEquals(1, api.firstRequests.size)
        assertEquals(listOf("old:25"), api.cursors)
    }

    private class Api : SearchApi {
        val firstRequests = mutableListOf<Triple<String, MusicKind?, Int>>()
        val cursors = mutableListOf<String>()
        var first: suspend (String) -> SearchPage = { error("Unexpected first request") }
        var continuation: suspend (String) -> SearchPage = { error("Unexpected continuation") }
        override suspend fun search(query: String, kind: MusicKind?, limit: Int): SearchPage {
            firstRequests += Triple(query, kind, limit)
            return first(query)
        }
        override suspend fun next(cursor: String): SearchPage {
            cursors += cursor
            return continuation(cursor)
        }
    }

    companion object {
        private val oldExpiry = Instant.parse("2026-01-01T12:05:00Z")
        private val freshExpiry = oldExpiry.plusSeconds(60)
        private val retryExpiry = oldExpiry.plusSeconds(120)

        private fun batch(snapshot: String, start: Int, count: Int) = (start until start + count).map { index ->
            val id = "$snapshot-$index"
            MusicItem(MusicKind.TRACK, id, id, emptyList(), "", "", "", 0, 0, true, 1, 1)
        }

        private fun page(snapshot: String, offset: Int, total: Int = 100, expiry: Instant = oldExpiry): SearchPage {
            val size = minOf(25, total - offset)
            check(size >= 0)
            return SearchPage(batch(snapshot, offset, size), total,
                if (offset + size < total) "$snapshot:${offset + size}" else null, expiry)
        }

        private fun expired(): Nothing = throw SearchException(SearchErrorCode.SEARCH_EXPIRED, 410)

        private fun assertOldWhileBusy(old: SearchState, current: SearchState) {
            assertEquals(old.items, current.items)
            assertEquals(old.resultCount, current.resultCount)
            assertEquals(old.expiresAt, current.expiresAt)
            assertEquals(old.queryGeneration, current.queryGeneration)
            assertTrue(current.busy)
            assertNull(current.error)
        }

        private fun assertGenericRetry(state: SearchState) {
            assertFalse(state.busy)
            assertNotNull(state.error)
            assertFalse(searchErrorMessage(requireNotNull(state.error)).contains("expired", ignoreCase = true))
        }
    }
}
