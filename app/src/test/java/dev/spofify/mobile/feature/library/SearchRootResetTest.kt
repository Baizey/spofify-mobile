package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** State-only tests: no Android, playback, host navigation, focus or software-keyboard claims. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRootResetTest {
    // Deliberately bypass cancellable suspension: completion really reaches the cancelled caller,
    // so these tests exercise the holder's fences rather than a transport's cancellation checks.
    private class Held<T>(val job: Job) {
        private var continuation: Continuation<T>? = null
        suspend fun await(): T = suspendCoroutine { continuation = it }
        fun complete(value: T) {
            val pending = checkNotNull(continuation)
            continuation = null
            pending.resume(value)
        }
    }

    private class Reader : CatalogReader {
        data class Browse(val request: CatalogRequest, val held: Held<LibraryPage>?)
        data class Detail(val kind: String, val id: String, val held: Held<LibraryItem>?)
        val browses = mutableListOf<Browse>()
        val details = mutableListOf<Detail>()
        val revisions = mutableListOf<Held<Long>?>()
        var holdBrowse = false
        var holdDetail = false
        var holdRevision = false
        var revisionValue = 1L
        var pageRevision = 1L

        override suspend fun browse(request: CatalogRequest): LibraryPage {
            val held = if (holdBrowse) Held<LibraryPage>(checkNotNull(currentCoroutineContext()[Job])) else null
            browses += Browse(request, held)
            return held?.await() ?: page(request, pageRevision)
        }

        override suspend fun detail(kind: String, id: String): LibraryItem {
            val held = if (holdDetail) Held<LibraryItem>(checkNotNull(currentCoroutineContext()[Job])) else null
            details += Detail(kind, id, held)
            return held?.await() ?: LibraryItem(kind, id, "Resolved $id")
        }

        override suspend fun revision(): Long {
            val held = if (holdRevision) Held<Long>(checkNotNull(currentCoroutineContext()[Job])) else null
            revisions += held
            return held?.await() ?: revisionValue
        }

        fun complete(reads: List<Browse>, revision: Long = pageRevision) {
            reads.forEach { checkNotNull(it.held).complete(page(it.request, revision)) }
        }

        private fun page(request: CatalogRequest, revision: Long) = LibraryPage(
            List(request.limit) { index ->
                LibraryItem(request.kind.removeSuffix("s"), "${request.query}-${request.kind}-${request.offset + index}", "Result")
            }, 60, request.limit, request.offset, revision,
        )
    }

    private fun assertFreshRoot(holder: CatalogStateHolder): CatalogScreen {
        val navigation = holder.state.value
        assertEquals(1, navigation.stack.size)
        assertFalse(navigation.canGoBack)
        val root = navigation.current
        // Exact defaults cover query, item/detail state, filters, paging/results, scroll and stale.
        assertEquals(CatalogScreen(key = root.key, kind = "all", search = true), root)
        assertTrue(root.emptySearch)
        return root
    }

    @Test fun populatedRootResetsResultsPagingFiltersAndScrollWithoutReading() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "accepted")
        holder.setVisible(true); runCurrent()
        holder.includeMissing(true); runCurrent()
        holder.page("tracks", 1); runCurrent()
        holder.more("tracks"); runCurrent()
        val oldKey = holder.state.value.current.key
        holder.rememberScroll(oldKey, 14, 37, expectedQuery = "accepted")
        assertEquals(10, holder.state.value.current.sections.getValue("tracks").offset)
        assertEquals(20, holder.state.value.current.sections.getValue("tracks").items.size)
        val reads = reader.browses.size

        val result: Unit = holder.activateSearchRoot()
        assertEquals(Unit, result)
        val root = assertFreshRoot(holder)
        assertNotEquals(oldKey, root.key)
        holder.rememberScroll(oldKey, 99, 99)
        assertEquals(root, holder.state.value.current)
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(reads, reader.browses.size)
        assertTrue(reader.details.isEmpty())
        assertTrue(reader.revisions.isEmpty())
        holder.close()
    }

    @Test fun innerDetailsCollapseToFreshRootAndCannotBeReachedByBack() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "accepted")
        holder.setVisible(true); runCurrent()
        val oldRoot = holder.state.value.current
        holder.open(LibraryItem("artist", "artist", "Artist")); runCurrent()
        holder.selectReleaseGroup("singles"); runCurrent()
        holder.open(LibraryItem("album", "album", "Album")); runCurrent()
        val oldDetail = holder.state.value.current
        holder.rememberScroll(oldDetail.key, 8, 12)
        assertEquals(3, holder.state.value.stack.size)
        val reads = reader.browses.size
        val details = reader.details.size

        holder.activateSearchRoot()
        val root = assertFreshRoot(holder)
        assertTrue(root.key > oldDetail.key)
        holder.rememberScroll(oldRoot.key, 80, 90, expectedQuery = "accepted")
        holder.rememberScroll(oldDetail.key, 80, 90)
        assertFalse(holder.back())
        runCurrent()
        assertEquals(root, holder.state.value.current)
        assertEquals(reads, reader.browses.size)
        assertEquals(details, reader.details.size)
        holder.close()
    }

    @Test fun alreadyEmptyAndComposingReselectsAlwaysHaveFreshKeysAndFenceOldScroll() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all")
        holder.setVisible(true); runCurrent()
        val keys = mutableSetOf(holder.state.value.current.key)
        repeat(3) {
            val previous = holder.state.value.current
            holder.setComposing(true)
            holder.rememberScroll(previous.key, 5, 23, expectedQuery = "")
            holder.activateSearchRoot()
            val root = assertFreshRoot(holder)
            assertTrue(keys.add(root.key))
            holder.rememberScroll(previous.key, 88, 99, expectedQuery = "")
            assertEquals(root, holder.state.value.current)
            holder.setComposing(false)
        }
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertTrue(reader.browses.isEmpty())
        assertTrue(reader.details.isEmpty())
        assertTrue(reader.revisions.isEmpty())
        holder.close()
    }

    @Test fun resetCancelsDebounceAndBlankRetriesVisibilityAndSubmitNeverRead() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all")
        holder.setVisible(true)
        holder.commitQuery("debounced"); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertTrue(reader.browses.isEmpty())

        holder.activateSearchRoot()
        val root = assertFreshRoot(holder)
        holder.commitQuery("", immediate = true)
        CatalogKinds.forEach { holder.retry(it) }
        holder.retryDetail()
        holder.setVisible(false)
        holder.setVisible(true)
        advanceTimeBy(500); runCurrent()
        assertEquals(root, holder.state.value.current)
        assertTrue(reader.browses.isEmpty())
        assertTrue(reader.details.isEmpty())
        holder.close()
    }

    @Test fun resetBeforeDebounceStartsAndWhileHiddenDoesNotFetchOnReturn() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks")
        holder.commitQuery("not started")
        holder.activateSearchRoot() // Cancel before the debounce coroutine gets its first turn.
        val firstRoot = assertFreshRoot(holder)
        holder.open(LibraryItem("track", "detail", "Detail")) // Hidden inner stack: no reads.
        holder.setComposing(true)
        holder.activateSearchRoot()
        val root = assertFreshRoot(holder)
        assertTrue(root.key > firstRoot.key)
        holder.setVisible(true)
        advanceTimeBy(500); runCurrent()
        assertEquals(root, holder.state.value.current)
        assertTrue(reader.browses.isEmpty())
        assertTrue(reader.details.isEmpty())
        holder.close()
    }

    @Test fun cancelledGroupedResponsesCannotRepopulateEmptyRootOrClearNewRequests() = runTest {
        val reader = Reader().apply { holdBrowse = true }
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "old")
        holder.setVisible(true); runCurrent()
        val oldReads = reader.browses.toList()
        assertEquals(3, oldReads.size)

        holder.activateSearchRoot()
        val root = assertFreshRoot(holder)
        assertTrue(oldReads.all { checkNotNull(it.held).job.isCancelled })
        // One cancellation-insensitive response arrives while the root is still blank.
        reader.complete(oldReads.take(1), revision = 99); runCurrent()
        assertEquals(root, holder.state.value.current)
        assertEquals(3, reader.browses.size)

        holder.commitQuery("new", immediate = true); runCurrent()
        val newReads = reader.browses.drop(3)
        assertEquals(3, newReads.size)
        val pendingNew = holder.state.value.current
        reader.complete(oldReads.drop(1), revision = 99); runCurrent()
        assertEquals(pendingNew, holder.state.value.current)
        assertTrue(newReads.none { checkNotNull(it.held).job.isCancelled })
        reader.complete(newReads); runCurrent()
        assertEquals(root.key, holder.state.value.current.key)
        assertEquals("new", holder.state.value.current.query)
        assertEquals(CatalogKinds.toSet(), holder.state.value.current.sections.keys)
        assertTrue(holder.state.value.current.sections.values.all { section ->
            section.loaded && !section.loading && section.items.all { it.id.startsWith("new-") }
        })
        holder.checkRevision(); runCurrent()
        assertEquals(6, reader.browses.size)
        assertTrue(reader.browses.none { it.request.query.isBlank() })
        holder.close()
    }

    @Test fun cancelledDetailAndChildResponsesCannotRestoreDiscardedInnerStack() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "accepted")
        holder.setVisible(true); runCurrent()
        reader.holdBrowse = true
        reader.holdDetail = true
        holder.open(LibraryItem("album", "old-album", "Seed")); runCurrent()
        val childRead = reader.browses.last()
        val detailRead = reader.details.single()
        assertEquals("old-album", childRead.request.albumId)

        holder.activateSearchRoot()
        val root = assertFreshRoot(holder)
        assertTrue(checkNotNull(childRead.held).job.isCancelled)
        assertTrue(checkNotNull(detailRead.held).job.isCancelled)
        checkNotNull(detailRead.held).complete(LibraryItem("album", "old-album", "Late detail"))
        reader.complete(listOf(childRead), revision = 99)
        runCurrent()
        assertEquals(root, holder.state.value.current)
        assertFalse(holder.back())
        assertEquals(4, reader.browses.size)
        assertEquals(1, reader.details.size)
        holder.close()
    }

    @Test fun cancelledPendingExpansionCannotRevivePagingOrResults() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "accepted")
        holder.setVisible(true); runCurrent()
        reader.holdBrowse = true
        holder.more("tracks"); runCurrent()
        val expansion = reader.browses.last()
        assertTrue(holder.state.value.current.sections.getValue("tracks").appendPending)
        assertEquals(10, expansion.request.offset)

        holder.activateSearchRoot()
        val root = assertFreshRoot(holder)
        assertTrue(checkNotNull(expansion.held).job.isCancelled)
        reader.complete(listOf(expansion), revision = 99); runCurrent()
        assertEquals(root, holder.state.value.current)
        assertEquals(2, reader.browses.size)
        holder.close()
    }

    @Test fun resetDropsPendingRevisionAndCompositionWithoutDeferringTheNextRealRefresh() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "old")
        holder.setVisible(true); runCurrent()
        holder.setComposing(true)
        reader.revisionValue = 2
        holder.checkRevision(); runCurrent()
        assertEquals(3, reader.browses.size)

        holder.activateSearchRoot()
        assertFreshRoot(holder)
        holder.commitQuery("new", immediate = true); runCurrent()
        // The old deferred revision must not kick off another round after new results settle.
        assertEquals(6, reader.browses.size)
        assertTrue(holder.state.value.current.sections.values.all { it.loaded && !it.loading })
        reader.pageRevision = 2
        holder.checkRevision(); runCurrent()
        // No explicit setComposing(false): activation itself must have cleared composition.
        assertEquals(9, reader.browses.size)
        assertTrue(holder.state.value.current.sections.values.all { !it.needsReload && !it.loading })
        assertTrue(reader.browses.none { it.request.query.isBlank() })
        holder.close()
    }

    @Test fun revisionPollBegunBeforeResetCannotInvalidateFreshRootOrPoisonNewResults() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "old")
        holder.setVisible(true); runCurrent()
        reader.holdRevision = true
        val poll = backgroundScope.launch { holder.checkRevision() }
        runCurrent()
        val held = checkNotNull(reader.revisions.single())

        holder.activateSearchRoot()
        val root = assertFreshRoot(holder)
        held.complete(99); runCurrent()
        assertTrue(poll.isCompleted)
        assertEquals(root, holder.state.value.current)
        assertEquals(3, reader.browses.size)
        reader.holdRevision = false
        holder.commitQuery("new", immediate = true); runCurrent()
        holder.checkRevision(); runCurrent()
        assertEquals(6, reader.browses.size)
        assertTrue(holder.state.value.current.sections.values.all { !it.needsReload })
        holder.close()
    }

    @Test fun ordinaryBackVisibilityAndUnchangedCommitStillPreserveSearch() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "accepted")
        holder.setVisible(true); runCurrent()
        holder.includeMissing(true); runCurrent()
        holder.page("tracks", 1); runCurrent()
        holder.rememberScroll(holder.state.value.current.key, 12, 31)
        val accepted = holder.state.value.current
        val reads = reader.browses.size
        holder.open(LibraryItem("track", "detail", "Track")); runCurrent()
        assertTrue(holder.back()); runCurrent()
        assertEquals(accepted, holder.state.value.current)
        holder.setVisible(false)
        holder.setVisible(true)
        holder.commitQuery("accepted", immediate = true)
        advanceTimeBy(500); runCurrent()
        assertEquals(accepted, holder.state.value.current)
        assertEquals(reads, reader.browses.size)
        assertEquals(1, reader.details.size)
        assertFalse(holder.back())
        holder.close()
    }
}
