package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.search.SearchErrorCode
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SearchPaginationTest {
    @Test fun reverseListLoadsTowardTheHighestIndexNotTheBestResultAtIndexZero() {
        val state = loaded()
        assertFalse(shouldLoadNextSearchPage(state, 0, 26))
        assertFalse(shouldLoadNextSearchPage(state, 19, 26))
        assertTrue(shouldLoadNextSearchPage(state, 20, 26))
        assertTrue(shouldLoadNextSearchPage(state, 24, 26))
        assertTrue(shouldLoadNextSearchPage(state, 25, 26))
    }

    @Test fun prefetchThresholdIncludesTheSingleStatusFooterWithoutAnOffByOne() {
        val state = loaded(count = 10)
        assertFalse(shouldLoadNextSearchPage(state, 4, 11))
        assertTrue(shouldLoadNextSearchPage(state, 5, 11))
        val one = loaded(count = 1)
        assertTrue(shouldLoadNextSearchPage(one, 0, 2))
        assertTrue(shouldLoadNextSearchPage(one, 1, 2))
    }

    @Test fun blankQueriesCannotLoadEvenIfStaleItemsAndACursorRemain() {
        for (query in listOf("", " ", "   \t")) {
            assertFalse(shouldLoadNextSearchPage(loaded().copy(query = query), 25, 26))
        }
    }

    @Test fun initialDebounceAndFirstPageDoNotTriggerContinuation() {
        assertFalse(shouldLoadNextSearchPage(SearchState(query = "query", waiting = true), 0, 1))
        assertFalse(shouldLoadNextSearchPage(SearchState(query = "query", loading = true), 0, 1))
        assertFalse(shouldLoadNextSearchPage(loaded().copy(loaded = false), 25, 26))
    }

    @Test fun genuinelyEmptyResultsAndEmptyPrefixesCannotAutoLoad() {
        val empty = loaded(count = 0).copy(nextCursor = null, resultCount = 0)
        assertTrue(empty.empty)
        assertFalse(shouldLoadNextSearchPage(empty, 0, 1))
        assertFalse(shouldLoadNextSearchPage(empty.copy(nextCursor = "unexpected"), 0, 1))
    }

    @Test fun waitingOrLoadingAPreservedPrefixBlocksConcurrentPagination() {
        val state = loaded()
        assertFalse(shouldLoadNextSearchPage(state.copy(waiting = true), 25, 26))
        assertFalse(shouldLoadNextSearchPage(state.copy(loading = true), 25, 26))
        assertFalse(shouldLoadNextSearchPage(state.copy(waiting = true, loading = true), 25, 26))
        assertTrue(shouldLoadNextSearchPage(state, 25, 26))
    }

    @Test fun everyRealErrorStopsAutomaticReadsUntilExplicitRetryClearsIt() {
        val state = loaded()
        for (error in SearchErrorCode.entries) {
            val failed = state.copy(error = error)
            assertFalse(shouldLoadNextSearchPage(failed, 25, 26))
            assertFalse(shouldLoadNextSearchPage(failed.copy(loading = true), 25, 26))
            assertTrue(shouldLoadNextSearchPage(failed.copy(error = null), 25, 26))
        }
    }

    @Test fun aTerminalPageNeverLoadsAgainEvenWhenItsFooterIsVisible() {
        val terminal = loaded().copy(nextCursor = null, resultCount = 25)
        assertFalse(shouldLoadNextSearchPage(terminal, 25, 26))
    }

    @Test fun staleLayoutCountsIncludingAMissingFooterCannotTriggerAPage() {
        val state = loaded()
        for (count in listOf(-1, 0, 1, 25, 27, 51)) {
            assertFalse(shouldLoadNextSearchPage(state, 20, count))
        }
        assertTrue(shouldLoadNextSearchPage(state, 20, 26))
    }

    @Test fun unmeasuredOrOutOfRangeVisibleIndicesCannotTriggerAPage() {
        val state = loaded()
        for (index in listOf(Int.MIN_VALUE, -1, 26, 100, Int.MAX_VALUE)) {
            assertFalse(shouldLoadNextSearchPage(state, index, 26))
        }
    }

    @Test fun aNewPageMustBeMeasuredAndNearItsOwnWeakEndBeforeLoadingAgain() {
        val first = loaded()
        assertTrue(shouldLoadNextSearchPage(first, 20, 26))
        assertFalse(shouldLoadNextSearchPage(first.copy(loading = true), 20, 26))
        val next = loaded(count = 50, cursor = "cursor-b")
        assertFalse(shouldLoadNextSearchPage(next, 20, 26))
        assertFalse(shouldLoadNextSearchPage(next, 20, 51))
        assertFalse(shouldLoadNextSearchPage(next, 44, 51))
        assertTrue(shouldLoadNextSearchPage(next, 45, 51))
    }

    @Test fun shortPagesCanFillTheViewportButDoNotEagerlyConsumeAllRemainingPages() {
        assertTrue(shouldLoadNextSearchPage(loaded(count = 4), 4, 5))
        assertTrue(shouldLoadNextSearchPage(loaded(count = 8, cursor = "b"), 8, 9))
        assertFalse(shouldLoadNextSearchPage(loaded(count = 16, cursor = "c"), 8, 17))
    }

    @Test fun pausedNearEndCannotLoadEvenAfterItsLoadingFlagIsCleared() {
        val paused = loaded().copy(paused = true, loading = false)
        assertFalse(shouldLoadNextSearchPage(paused, 25, 26))
        assertFalse(shouldLoadNextSearchPage(paused.copy(loading = true), 25, 26))
        assertTrue(shouldLoadNextSearchPage(paused.copy(paused = false), 25, 26))
    }

    @Test fun resumingAnInterruptedCursorRearmsAfterBusyWithoutAConsumedCursorLatch() {
        val interrupted = loaded().copy(loading = true)
        assertFalse(shouldLoadNextSearchPage(interrupted, 25, 26))
        assertTrue(shouldLoadNextSearchPage(interrupted.copy(loading = false), 25, 26))
    }

    @Test fun changedCursorsRemainEligibleEvenWhenTheItemCountAndViewportAreUnchanged() {
        val old = loaded(cursor = "old")
        val fresh = old.copy(nextCursor = "fresh")
        assertTrue(shouldLoadNextSearchPage(old, 20, 26))
        assertTrue(shouldLoadNextSearchPage(fresh, 20, 26))
        assertFalse(shouldLoadNextSearchPage(fresh.copy(loading = true), 20, 26))
        assertTrue(shouldLoadNextSearchPage(fresh.copy(loading = false), 20, 26))
    }

    @Test fun cacheExpiryIsNotAClientSideStopOrUserActionRequirement() {
        val state = loaded().copy(expiresAt = Instant.EPOCH)
        assertTrue(shouldLoadNextSearchPage(state, 20, 26))
    }

    private fun loaded(count: Int = 25, cursor: String = "cursor-a") = SearchState(
        query = "query",
        items = (0 until count).map { index -> MusicItem(
            MusicKind.TRACK, "track-$index", "Track $index", emptyList(), "", "", "", 0, 0, true, 1, 1,
        ) },
        loaded = true,
        nextCursor = cursor,
        resultCount = count + 25,
        expiresAt = Instant.parse("2026-01-01T12:05:00Z"),
    )
}
