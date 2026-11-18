package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModelStore
import dev.spofify.mobile.core.library.Artist
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
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private class Api : SearchApi {
        val firstRequests = mutableListOf<Triple<String, MusicKind?, Int>>()
        val cursors = mutableListOf<String>()
        var first: suspend (String) -> SearchPage = { query -> page(listOf(item(MusicKind.TRACK, query))) }
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

    private fun model(api: Api) = SearchViewModel(api).also { store.put("search", it) }

    @Test fun blankAndInvalidInputMakeNoRequestsAndTypingIsDebounced() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        model.query("   ")
        for (invalid in listOf("bad\nquery", "a".repeat(201), "\uD800")) model.query(invalid)
        advanceUntilIdle()
        assertTrue(api.firstRequests.isEmpty())
        model.query("lo")
        advanceTimeBy(150)
        model.query("  love  ")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(Triple("love", null, 25)), api.firstRequests)
        assertEquals("  love  ", model.state.value.query)
        assertFalse(model.state.value.busy)
    }

    @Test fun mixedServerRankingIsRetainedWithoutTypeBoostsOrNameResorting() = runTest(dispatcher) {
        val ranked = listOf(
            item(MusicKind.ARTIST, "fuzzy-artist", "A romanized match"),
            item(MusicKind.TRACK, "track", "love"),
            item(MusicKind.ALBUM, "album", "love"),
        )
        val api = Api().apply { first = { page(ranked) } }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        assertEquals(ranked, model.state.value.items)
        assertEquals(1, api.firstRequests.size)
        assertNull(api.firstRequests.single().second)
        assertEquals(3, model.state.value.resultCount)
        assertTrue(model.consumeInitialAnchor(model.state.value.queryGeneration))
        assertFalse(model.state.value.hasMore)
    }

    @Test fun continuationUsesOnlyTheCursorAndAppendsWithoutReordering() = runTest(dispatcher) {
        val first = batch(0, 25)
        val last = batch(25, 2)
        val api = Api().apply {
            this.first = { page(first, 27, "cursor-a") }
            continuation = { page(last, 27) }
        }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        val generation = model.state.value.queryGeneration
        assertTrue(model.consumeInitialAnchor(generation))
        model.more()
        advanceUntilIdle()
        assertEquals(1, api.firstRequests.size)
        assertEquals(listOf("cursor-a"), api.cursors)
        assertEquals(first + last, model.state.value.items)
        assertEquals(generation, model.state.value.queryGeneration)
        assertFalse(model.consumeInitialAnchor(generation))
        assertEquals(27, model.state.value.resultCount)
        assertFalse(model.state.value.hasMore)
        model.more()
        advanceUntilIdle()
        assertEquals(1, api.cursors.size)
    }

    @Test fun repeatedMoreDoesNotStartConcurrentContinuationReads() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            first = { page(batch(0, 25), 27, "cursor-a") }
            continuation = { gate.await(); page(batch(25, 2), 27) }
        }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        repeat(3) { model.more() }
        runCurrent()
        assertEquals(listOf("cursor-a"), api.cursors)
        assertTrue(model.state.value.busy)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(27, model.state.value.items.size)
    }

    @Test fun continuationFailureKeepsThePrefixAndRetryUsesTheSameCursor() = runTest(dispatcher) {
        val first = batch(0, 25)
        val api = Api().apply {
            this.first = { page(first, 27, "cursor-a") }
            continuation = { throw SearchException(SearchErrorCode.SEARCH_BUSY, 503) }
        }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        model.more()
        advanceUntilIdle()
        assertEquals(first, model.state.value.items)
        assertEquals(SearchErrorCode.SEARCH_BUSY, model.state.value.error)
        assertFalse(model.state.value.empty)
        model.more()
        advanceUntilIdle()
        assertEquals(1, api.cursors.size)
        api.continuation = { page(batch(25, 2), 27) }
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf("cursor-a", "cursor-a"), api.cursors)
        assertEquals(27, model.state.value.items.size)
        assertNull(model.state.value.error)
    }

    @Test fun expiredContinuationQuietlyStagesTheWholeFreshPrefixAndKeepsTheAnchor() = runTest(dispatcher) {
        val prefix = batch(0, 25)
        val firstGate = CompletableDeferred<Unit>()
        val suffixGate = CompletableDeferred<Unit>()
        val freshPrefix = prefix.map { it.copy(name = "Fresh ${it.name}") }
        val suffix = batch(25, 2)
        val freshExpiry = expiry.plusSeconds(60)
        val api = Api().apply {
            first = {
                if (firstRequests.size == 1) page(prefix, 27, "old-cursor")
                else {
                    firstGate.await()
                    page(freshPrefix, 27, "fresh-cursor", freshExpiry)
                }
            }
            continuation = { cursor ->
                when (cursor) {
                    "old-cursor" -> throw SearchException(SearchErrorCode.SEARCH_EXPIRED, 410)
                    "fresh-cursor" -> {
                        suffixGate.await()
                        page(suffix, 27, expiresAt = freshExpiry)
                    }
                    else -> error("Unexpected cursor")
                }
            }
        }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        val generation = model.state.value.queryGeneration
        assertTrue(model.consumeInitialAnchor(generation))
        model.more()
        runCurrent()
        assertEquals(prefix, model.state.value.items)
        assertTrue(model.state.value.busy)
        assertNull(model.state.value.error)
        firstGate.complete(Unit)
        runCurrent()
        assertEquals(prefix, model.state.value.items)
        assertEquals(expiry, model.state.value.expiresAt)
        suffixGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, api.firstRequests.size)
        assertEquals(listOf("old-cursor", "fresh-cursor"), api.cursors)
        assertEquals(freshPrefix + suffix, model.state.value.items)
        assertEquals(freshExpiry, model.state.value.expiresAt)
        assertEquals(generation, model.state.value.queryGeneration)
        assertFalse(model.consumeInitialAnchor(generation))
        assertNull(model.state.value.error)
    }

    @Test fun onlyANewUserQueryResetsScrollingNotQuietCacheRecovery() = runTest(dispatcher) {
        val api = Api().apply {
            first = { query ->
                if (query == "other") page(listOf(item(MusicKind.ALBUM, "other")))
                else page(batch(0, 25), 27, if (firstRequests.size == 1) "old" else "fresh")
            }
            continuation = { cursor ->
                if (cursor == "old") throw SearchException(SearchErrorCode.SEARCH_EXPIRED, 410)
                page(batch(25, 2), 27)
            }
        }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        val generation = model.state.value.queryGeneration
        assertTrue(model.consumeInitialAnchor(generation))
        model.more()
        advanceUntilIdle()
        assertEquals(expiry, model.state.value.expiresAt)
        assertEquals(27, model.state.value.items.size)
        assertFalse(model.consumeInitialAnchor(model.state.value.queryGeneration))
        model.query("other")
        advanceUntilIdle()
        assertFalse(model.consumeInitialAnchor(generation))
        assertTrue(model.consumeInitialAnchor(model.state.value.queryGeneration))
        assertEquals(listOf("other"), model.state.value.items.map { it.id })
    }

    @Test fun anchorWaitsForRowsThroughLoadingFailureAndRetryAndIsNotReissuedOnReentry() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            first = { gate.await(); throw SearchException(SearchErrorCode.SEARCH_BUSY) }
        }
        val model = model(api)
        model.query("love")
        val generation = model.state.value.queryGeneration
        assertTrue(model.state.value.waiting)
        assertFalse(model.consumeInitialAnchor(generation))
        advanceTimeBy(300)
        runCurrent()
        assertTrue(model.state.value.loading)
        assertFalse(model.consumeInitialAnchor(generation))
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.consumeInitialAnchor(generation))
        api.first = { page(batch(0, 25)) }
        model.retry()
        advanceUntilIdle()
        assertTrue(model.consumeInitialAnchor(generation))
        assertFalse(model.consumeInitialAnchor(generation))
        model.pause()
        model.resume()
        advanceUntilIdle()
        assertFalse(model.consumeInitialAnchor(generation))
        assertEquals(batch(0, 25), model.state.value.items)
        assertEquals(2, api.firstRequests.size)
    }

    @Test fun fastClearAndRetypeOfTheSameQueryGetsItsOwnAnchorEvenWithIdenticalRows() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        val original = model.state.value
        assertTrue(model.consumeInitialAnchor(original.queryGeneration))
        model.query("love") // An unchanged input is not a new query.
        assertEquals(original, model.state.value)
        model.query("")
        assertFalse(model.consumeInitialAnchor(model.state.value.queryGeneration))
        model.query("love")
        advanceUntilIdle() // The UI need not observe the intermediate empty/loading states.
        val retyped = model.state.value
        assertEquals(original.items, retyped.items)
        assertFalse(model.consumeInitialAnchor(original.queryGeneration))
        assertTrue(model.consumeInitialAnchor(retyped.queryGeneration))
        assertFalse(model.consumeInitialAnchor(retyped.queryGeneration))
        assertEquals(listOf("love", "love"), api.firstRequests.map { it.first })
    }

    @Test fun inconsistentContinuationNeverPublishesAPartialOrMixedSnapshot() = runTest(dispatcher) {
        val prefix = batch(0, 25)
        val malformedPages = listOf(
            page(batch(25, 2), 28),
            page(batch(25, 2), 27, expiresAt = expiry.plusSeconds(1)),
            page(listOf(prefix.first(), item(MusicKind.TRACK, "new")), 27),
            page(emptyList(), 27),
            page(batch(25, 1), 27, "cursor-a"),
            page(batch(25, 1), 27),
            page(batch(25, 3), 27),
        )
        for (malformed in malformedPages) {
            val api = Api().apply {
                first = { page(prefix, 27, "cursor-a") }
                continuation = { malformed }
            }
            val model = model(api)
            model.query("love")
            advanceUntilIdle()
            model.more()
            advanceUntilIdle()
            assertEquals(prefix, model.state.value.items)
            assertEquals(SearchErrorCode.INVALID_RESPONSE, model.state.value.error)
            model.more()
            advanceUntilIdle()
            assertEquals(1, api.firstRequests.size)
            assertEquals(1, api.cursors.size)
        }
    }

    @Test fun cursorCyclesAreRejectedEvenWhenTheNextItemsAreUnique() = runTest(dispatcher) {
        val api = Api().apply {
            first = { page(batch(0, 25), 75, "cursor-a") }
            continuation = { cursor ->
                if (cursor == "cursor-a") page(batch(25, 25), 75, "cursor-b")
                else page(batch(50, 1), 75, "cursor-a")
            }
        }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        model.more()
        advanceUntilIdle()
        model.more()
        advanceUntilIdle()
        assertEquals(batch(0, 50), model.state.value.items)
        assertEquals(SearchErrorCode.INVALID_RESPONSE, model.state.value.error)
    }

    @Test fun unavailableSearchIsNotPresentedAsNoMatchesOrAutomaticallyRetried() = runTest(dispatcher) {
        for (code in SearchErrorCode.entries) {
            val api = Api().apply { first = { throw SearchException(code) } }
            val model = model(api)
            model.query("love")
            advanceUntilIdle()
            assertEquals(code, model.state.value.error)
            assertFalse(model.state.value.empty)
            assertFalse(model.state.value.busy)
            assertTrue(model.state.value.items.isEmpty())
            model.resume()
            advanceUntilIdle()
            assertEquals(if (code == SearchErrorCode.SEARCH_EXPIRED) 2 else 1, api.firstRequests.size)
            assertFalse(searchErrorMessage(code).contains("expired", ignoreCase = true))
            api.first = { page(emptyList()) }
            model.retry()
            advanceUntilIdle()
            assertNull(model.state.value.error)
            assertTrue(model.state.value.empty)
            assertFalse(model.consumeInitialAnchor(model.state.value.queryGeneration))
        }
    }

    @Test fun clearDiscardsEvenANoncooperativePendingResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            first = { query ->
                withContext(NonCancellable) { gate.await() }
                page(listOf(item(MusicKind.TRACK, query)))
            }
        }
        val model = model(api)
        model.query("old")
        advanceTimeBy(300)
        runCurrent()
        model.query("")
        gate.complete(Unit)
        runCurrent()
        assertEquals("", model.state.value.query)
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.nextCursor)
        assertFalse(model.state.value.busy)
        assertEquals(1, api.firstRequests.size)
    }

    @Test fun staleSuccessCannotReplaceANewerQuery() = runTest(dispatcher) {
        val oldGate = CompletableDeferred<Unit>()
        val api = Api().apply {
            first = { query ->
                if (query == "old") withContext(NonCancellable) { oldGate.await() }
                page(listOf(item(MusicKind.TRACK, query)))
            }
        }
        val model = model(api)
        model.query("old")
        advanceTimeBy(300)
        runCurrent()
        val staleGeneration = model.state.value.queryGeneration
        model.query("new")
        advanceTimeBy(300)
        runCurrent()
        oldGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.consumeInitialAnchor(staleGeneration))
        assertTrue(model.consumeInitialAnchor(model.state.value.queryGeneration))
        assertEquals(listOf("new"), model.state.value.items.map { it.id })
        assertEquals("new", model.state.value.query)
        assertNull(model.state.value.error)
    }

    @Test fun staleFailureCannotPublishOrStopANewerRequestsLoadingState() = runTest(dispatcher) {
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        val api = Api().apply {
            first = { query ->
                if (query == "old") {
                    withContext(NonCancellable) { oldGate.await() }
                    throw SearchException(SearchErrorCode.SEARCH_BUSY, 503)
                }
                newGate.await()
                page(listOf(item(MusicKind.TRACK, query)))
            }
        }
        val model = model(api)
        model.query("old")
        advanceTimeBy(300)
        runCurrent()
        model.query("new")
        advanceTimeBy(300)
        runCurrent()
        oldGate.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.busy)
        assertNull(model.state.value.error)
        newGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("new"), model.state.value.items.map { it.id })
    }

    @Test fun pausedContinuationResumesItsCursorAndRepeatedPauseIsSafe() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            first = { page(batch(0, 25), 27, "cursor-a") }
            continuation = { gate.await(); page(batch(25, 2), 27) }
        }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        model.more()
        runCurrent()
        model.pause()
        model.pause()
        runCurrent()
        assertEquals(batch(0, 25), model.state.value.items)
        api.continuation = { page(batch(25, 2), 27) }
        model.resume()
        advanceUntilIdle()
        assertEquals(listOf("cursor-a", "cursor-a"), api.cursors)
        assertEquals(1, api.firstRequests.size)
        assertEquals(27, model.state.value.items.size)
        model.pause()
        model.resume()
        advanceUntilIdle()
        assertEquals(1, api.firstRequests.size)
        assertEquals(2, api.cursors.size)
    }

    @Test fun pausedDebounceResumesOneFirstPageInsteadOfLosingTheQuery() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        model.query("love")
        model.pause()
        advanceUntilIdle()
        assertTrue(api.firstRequests.isEmpty())
        model.resume()
        advanceUntilIdle()
        assertEquals(1, api.firstRequests.size)
        assertEquals("love", model.state.value.items.single().id)
    }

    @Test fun firstPageCleanupCannotClearAReentrantlyStartedContinuation() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            first = { page(batch(0, 25), 27, "cursor-a") }
            continuation = { gate.await(); page(batch(25, 2), 27) }
        }
        val model = model(api)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { current ->
                if (!current.busy && current.items.size == 25 && current.nextCursor != null) model.more()
            }
        }
        model.query("love")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("cursor-a"), api.cursors)
        assertTrue(model.state.value.busy)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(27, model.state.value.items.size)
        assertFalse(model.state.value.busy)
    }

    @Test fun pauseFromDebounceCompletionPreventsRequestAdmission() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        var sawWaiting = false
        var paused = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { current ->
                if (current.waiting) sawWaiting = true
                if (sawWaiting && !current.waiting && !current.loading && !paused) {
                    paused = true
                    model.pause()
                }
            }
        }
        model.query("love")
        advanceUntilIdle()
        assertTrue(paused)
        assertTrue(api.firstRequests.isEmpty())
        model.resume()
        advanceUntilIdle()
        assertEquals(1, api.firstRequests.size)
    }

    @Test fun pauseFromLoadingPublicationCancelsTheInstalledJobBeforeAnyRead() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        var paused = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { current ->
                if (current.loading && !paused) {
                    paused = true
                    model.pause()
                }
            }
        }
        model.query("love")
        advanceUntilIdle()
        assertTrue(paused)
        assertTrue(api.firstRequests.isEmpty())
        assertFalse(model.state.value.busy)
        model.resume()
        advanceUntilIdle()
        assertEquals(1, api.firstRequests.size)
        assertTrue(model.state.value.loaded)
    }

    @Test fun pauseFromAcceptedPageDoesNotResumeAnAlreadyCompletedCursor() = runTest(dispatcher) {
        val api = Api().apply { first = { page(batch(0, 25), 27, "cursor-a") } }
        val model = model(api)
        var paused = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { current ->
                if (current.loaded && !current.busy && !paused) {
                    paused = true
                    model.pause()
                }
            }
        }
        model.query("love")
        advanceUntilIdle()
        model.resume()
        advanceUntilIdle()
        assertEquals(1, api.firstRequests.size)
        assertTrue(api.cursors.isEmpty())
        assertEquals("cursor-a", model.state.value.nextCursor)
    }

    @Test fun pausedViewRejectsPagingAndDefersEditedQueriesUntilResume() = runTest(dispatcher) {
        val api = Api().apply { first = { query -> page(batch(0, 25), 27, "$query-cursor") } }
        val model = model(api)
        model.query("old")
        advanceUntilIdle()
        model.pause()
        model.more()
        model.query("new")
        advanceUntilIdle()
        assertTrue(model.state.value.paused)
        assertFalse(model.state.value.busy)
        assertEquals(1, api.firstRequests.size)
        assertTrue(api.cursors.isEmpty())
        model.resume()
        advanceUntilIdle()
        assertFalse(model.state.value.paused)
        assertEquals(listOf("old", "new"), api.firstRequests.map { it.first })
        assertEquals("new-cursor", model.state.value.nextCursor)
    }

    @Test fun pausePublicationCannotAdmitAReentrantPagingRequest() = runTest(dispatcher) {
        val api = Api().apply { first = { page(batch(0, 25), 27, "cursor-a") } }
        val model = model(api)
        model.query("love")
        advanceUntilIdle()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { current ->
                if (current.paused && current.hasMore) model.more()
            }
        }
        model.pause()
        advanceUntilIdle()
        assertTrue(model.state.value.paused)
        assertTrue(api.cursors.isEmpty())
        model.resume()
        advanceUntilIdle()
        assertFalse(model.state.value.paused)
        assertEquals(1, api.firstRequests.size)
    }

    @Test fun manualArtistNamesAndFocusIntentsSurviveTheNewSearchContract() = runTest(dispatcher) {
        val track = item(MusicKind.TRACK, "manual").copy(artists = listOf(Artist("", "Manual artist")))
        val api = Api().apply { first = { page(listOf(track)) } }
        val model = model(api)
        assertFalse(model.consumeFocusIntent(0))
        assertTrue(model.consumeFocusIntent(1))
        assertFalse(model.consumeFocusIntent(1))
        model.query("manual")
        advanceUntilIdle()
        assertEquals(track, model.state.value.items.single())
        assertTrue(model.consumeFocusIntent(2))
        assertEquals("The search index isn’t ready yet.", searchErrorMessage(SearchErrorCode.SEARCH_INDEX_NOT_READY))
    }

    companion object {
        private val expiry = Instant.parse("2026-01-01T12:05:00Z")

        private fun item(kind: MusicKind, id: String, name: String = id) = MusicItem(
            kind, id, name, emptyList(), "", "", "", 0, 0, kind == MusicKind.TRACK, 1, 1,
        )

        private fun batch(start: Int, count: Int) = (start until start + count).map {
            item(MusicKind.TRACK, "track-$it")
        }

        private fun page(
            items: List<MusicItem>,
            resultCount: Int = items.size,
            nextCursor: String? = null,
            expiresAt: Instant = expiry,
        ) = SearchPage(items, resultCount, nextCursor, expiresAt)
    }
}
