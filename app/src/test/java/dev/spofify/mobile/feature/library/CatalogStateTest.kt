package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogStateTest {
    private class Reader : CatalogReader {
        data class Read(val request: CatalogRequest, val result: CompletableDeferred<LibraryPage> = CompletableDeferred())
        val reads = mutableListOf<Read>()
        val details = mutableListOf<Pair<String, String>>()
        var revisionValue = 1L
        var ignoreCancellation = false
        var detailError: Exception? = null
        var suspendDetails = false
        val pendingDetails = mutableListOf<CompletableDeferred<LibraryItem>>()
        override suspend fun browse(request: CatalogRequest): LibraryPage {
            val read = Read(request).also { reads += it }
            return if (ignoreCancellation) withContext(NonCancellable) { read.result.await() } else read.result.await()
        }
        override suspend fun detail(kind: String, id: String): LibraryItem {
            details += kind to id
            detailError?.let { throw it }
            if (suspendDetails) return CompletableDeferred<LibraryItem>().also { pendingDetails += it }.await()
            return LibraryItem(kind, id, "Resolved $id")
        }
        override suspend fun revision() = revisionValue
        fun completePending(revision: Long = 1) {
            reads.filterNot { it.result.isCompleted }.forEach { it.result.complete(LibraryPage(
                listOf(LibraryItem(it.request.kind.removeSuffix("s"), "${it.request.kind}-${it.request.offset}", "Result")),
                25, it.request.limit, it.request.offset, revision)) }
        }
    }

    @Test fun emptySearchNeverFetchesAnyCatalogEvenAfterFilterChanges() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, initialKind = "all")
        holder.setVisible(true); runCurrent()
        holder.selectKind("artists"); holder.includeMissing(true); runCurrent()
        assertTrue(holder.state.value.current.emptySearch)
        assertTrue(reader.reads.isEmpty())
        holder.commitQuery("   ", immediate = true); runCurrent()
        assertTrue(reader.reads.isEmpty())
        holder.close()
    }

    @Test fun groupedSearchHasIndependentTenRowTotalsFailuresAndPaging() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent()
        assertEquals(CatalogKinds, reader.reads.map { it.request.kind })
        assertTrue(reader.reads.all { it.request.limit == 10 && it.request.availability == "in-library" })
        reader.reads[0].result.completeExceptionally(IOException())
        reader.reads[1].result.complete(LibraryPage(listOf(LibraryItem("album", "a", "Album")), 22))
        reader.reads[2].result.complete(LibraryPage(emptyList(), 0)); runCurrent()
        assertNotNull(holder.state.value.current.sections.getValue("artists").error)
        assertEquals(22, holder.state.value.current.sections.getValue("albums").total)
        holder.page("albums", 1); runCurrent()
        assertEquals("albums", reader.reads.last().request.kind)
        assertEquals(10, reader.reads.last().request.offset)
        assertEquals(0, holder.state.value.current.sections.getValue("artists").offset)
        reader.completePending(); runCurrent()
        holder.retry("artists"); runCurrent()
        assertEquals(5, reader.reads.size)
        assertEquals("artists", reader.reads.last().request.kind)
        reader.completePending(); runCurrent(); holder.close()
    }

    @Test fun lateCancelledQueryCannotOverwriteNewQueryOrReturnFromDetails() = runTest {
        val reader = Reader().apply { ignoreCancellation = true }
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "old")
        holder.setVisible(true); runCurrent()
        holder.commitQuery("new"); runCurrent(); advanceTimeBy(250); runCurrent()
        val latest = reader.reads.drop(3)
        latest.forEach { it.result.complete(LibraryPage(listOf(LibraryItem("track", "new", "New")), 1)) }; runCurrent()
        reader.reads.take(3).forEach { it.result.complete(LibraryPage(listOf(LibraryItem("track", "old", "Old")), 1)) }; runCurrent()
        assertEquals("new", holder.state.value.current.query)
        assertTrue(holder.state.value.current.sections.values.all { it.items.first().id == "new" })
        holder.commitQuery("pending"); runCurrent()
        holder.open(LibraryItem("track", "detail", "Detail")); runCurrent()
        advanceTimeBy(300); runCurrent()
        assertEquals("detail", holder.state.value.current.item?.id)
        assertEquals(6, reader.reads.size)
        holder.close()
    }

    @Test fun detailChildrenIgnoreSearchAndBackRestoresGroupsFiltersPagesAndScroll() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "needle")
        holder.setVisible(true); runCurrent(); reader.completePending(); runCurrent()
        holder.includeMissing(true); runCurrent(); reader.completePending(); runCurrent()
        holder.page("albums", 1); runCurrent(); reader.completePending(); runCurrent()
        val key = holder.state.value.current.key
        holder.rememberScroll(key, 9, 23)
        holder.open(LibraryItem("artist", "artist-id", "Artist")); runCurrent()
        val child = reader.reads.last().request
        assertEquals("", child.query); assertEquals("all", child.availability)
        assertEquals(40, child.limit); assertEquals("artist-id", child.artistId); assertEquals("albums", child.releaseGroup)
        reader.completePending(); runCurrent()
        holder.selectReleaseGroup("singles"); runCurrent()
        assertEquals("singles", reader.reads.last().request.releaseGroup)
        reader.completePending(); runCurrent()
        val count = reader.reads.size
        assertTrue(holder.back()); runCurrent()
        val restored = holder.state.value.current
        assertEquals("needle", restored.query); assertTrue(restored.includeMissing)
        assertEquals(10, restored.sections.getValue("albums").offset)
        assertEquals(9, restored.scrollIndex); assertEquals(23, restored.scrollOffset)
        assertEquals(count, reader.reads.size)
        holder.close()
    }

    @Test fun revisionDiscoveredDuringReadsWaitsForIdleAndPreservesRows() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent(); reader.completePending(1); runCurrent()
        holder.rememberScroll(0, 2, 17)
        reader.revisionValue = 2
        holder.checkRevision(); runCurrent()
        assertEquals(6, reader.reads.size)
        assertTrue(holder.state.value.current.sections.values.all { it.items.isNotEmpty() && it.loading })
        reader.revisionValue = 3
        holder.checkRevision(); runCurrent()
        assertEquals(6, reader.reads.size)
        reader.completePending(2); runCurrent()
        assertEquals(9, reader.reads.size)
        reader.completePending(3); runCurrent()
        assertEquals(2, holder.state.value.current.scrollIndex)
        assertEquals(17, holder.state.value.current.scrollOffset)
        holder.setVisible(false)
        reader.revisionValue = 4
        holder.checkRevision(); runCurrent()
        assertEquals(9, reader.reads.size)
        holder.close()
    }

    @Test fun interruptedPageKeepsSuccessfulOffsetAndRetriesRequestedPageOnReturn() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent(); reader.completePending(); runCurrent()
        holder.page("tracks", 1); runCurrent()
        assertEquals(0, holder.state.value.current.sections.getValue("tracks").offset)
        assertEquals(10, holder.state.value.current.sections.getValue("tracks").requestedOffset)
        holder.setVisible(false); runCurrent()
        holder.setVisible(true); runCurrent()
        assertEquals(10, reader.reads.last().request.offset)
        reader.completePending(); runCurrent()
        assertEquals(10, holder.state.value.current.sections.getValue("tracks").offset)
        assertFalse(holder.state.value.current.sections.getValue("tracks").needsReload)
        holder.close()
    }

    @Test fun failedQuietRevisionReadRetriesAtSameRevisionAndDoesNotDropUsableRows() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent(); reader.completePending(); runCurrent()
        reader.revisionValue = 2
        holder.checkRevision(); runCurrent()
        reader.reads.drop(3).forEach { it.result.completeExceptionally(IOException()) }; runCurrent()
        assertTrue(holder.state.value.current.sections.values.all { it.items.isNotEmpty() && it.needsReload && it.error == null })
        holder.checkRevision(); runCurrent()
        assertEquals(9, reader.reads.size)
        reader.completePending(2); runCurrent()
        assertTrue(holder.state.value.current.sections.values.none { it.needsReload })
        holder.close()
    }

    @Test fun olderInitialResponseCannotBeBlessedByFasterRevisionPoll() = runTest {
        val reader = Reader().apply { revisionValue = 2 }
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent()
        holder.checkRevision(); runCurrent()
        reader.completePending(1); runCurrent()
        assertEquals(6, reader.reads.size)
        reader.completePending(2); runCurrent()
        holder.checkRevision(); runCurrent()
        assertEquals(6, reader.reads.size)
        holder.close()
    }

    @Test fun deletedDetailWithdrawsActionsAndChildRowsEvenDuringQuietRefresh() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, initialItem = LibraryItem("album", "album", "Album"))
        holder.setVisible(true); runCurrent(); reader.completePending(); runCurrent()
        reader.detailError = ApiException(404, "Missing")
        reader.revisionValue = 2
        holder.checkRevision(); runCurrent()
        reader.completePending(2); runCurrent()
        assertTrue(holder.state.value.current.detailUnavailable)
        assertTrue(holder.state.value.current.sections.isEmpty())
        holder.close()
    }

    @Test fun committedCompositionDefersRevisionRevalidationUntilIdle() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent(); reader.completePending(); runCurrent()
        holder.setComposing(true); reader.revisionValue = 2
        holder.checkRevision(); runCurrent()
        assertEquals(3, reader.reads.size)
        holder.setComposing(false); runCurrent()
        assertEquals(6, reader.reads.size)
        reader.completePending(2); runCurrent(); holder.close()
    }

    @Test fun persistentlyOlderSuccessfulPagesWaitForNextPollInsteadOfLooping() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent(); reader.completePending(1); runCurrent()
        reader.revisionValue = 2
        holder.checkRevision(); runCurrent(); reader.completePending(1); runCurrent()
        assertEquals(6, reader.reads.size)
        assertTrue(holder.state.value.current.sections.values.all { it.needsReload })
        runCurrent(); assertEquals(6, reader.reads.size)
        holder.checkRevision(); runCurrent()
        assertEquals(9, reader.reads.size)
        reader.completePending(2); runCurrent(); holder.close()
    }

    @Test fun firstTrackDetailIsValidatedOnceAfterAnOvertakingRevisionPoll() = runTest {
        val reader = Reader().apply { suspendDetails = true; revisionValue = 2 }
        val holder = CatalogStateHolder(reader, backgroundScope, initialItem = LibraryItem("track", "track", "Seed"))
        holder.setVisible(true); runCurrent()
        holder.checkRevision(); runCurrent()
        reader.pendingDetails[0].complete(LibraryItem("track", "track", "Old")); runCurrent()
        assertEquals(2, reader.pendingDetails.size)
        reader.pendingDetails[1].complete(LibraryItem("track", "track", "New")); runCurrent()
        assertEquals("New", holder.state.value.current.item?.name)
        holder.checkRevision(); runCurrent()
        assertEquals(2, reader.pendingDetails.size)
        holder.close()
    }

    @Test fun catalogRevisionRevalidatesPendingExpansionPrefixBeforeCombiningRows() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent()
        reader.reads.forEach { read -> read.result.complete(LibraryPage(List(10) {
            LibraryItem(read.request.kind.removeSuffix("s"), "old-$it", "Old")
        }, 25, revision = 1)) }; runCurrent()
        holder.more("tracks"); runCurrent()
        reader.reads.last().result.completeExceptionally(IOException()); runCurrent()
        reader.revisionValue = 2
        holder.checkRevision(); runCurrent()
        val refresh = reader.reads.last().request
        assertEquals("tracks", refresh.kind); assertEquals(0, refresh.offset); assertEquals(20, refresh.limit)
        reader.reads.filterNot { it.result.isCompleted }.forEach { read -> read.result.complete(LibraryPage(
            List(read.request.limit) { LibraryItem(read.request.kind.removeSuffix("s"), "fresh-$it", "Fresh") }, 25, revision = 2)) }
        runCurrent()
        val section = holder.state.value.current.sections.getValue("tracks")
        assertEquals(20, section.items.size)
        assertTrue(section.items.all { it.id.startsWith("fresh-") })
        assertFalse(section.needsReload); assertFalse(section.appendPending)
        holder.close()
    }

    @Test fun firstParentDetailFenceIsIndependentOfAnEarlierChildListingBaseline() = runTest {
        val reader = Reader().apply { suspendDetails = true; revisionValue = 2 }
        val holder = CatalogStateHolder(reader, backgroundScope, initialItem = LibraryItem("album", "album", "Seed"))
        holder.setVisible(true); runCurrent()
        reader.completePending(2); runCurrent()
        holder.checkRevision(); runCurrent()
        reader.pendingDetails[0].complete(LibraryItem("album", "album", "Old")); runCurrent()
        assertEquals(2, reader.pendingDetails.size)
        reader.pendingDetails[1].complete(LibraryItem("album", "album", "New"))
        reader.completePending(2); runCurrent()
        assertEquals("New", holder.state.value.current.item?.name)
        holder.checkRevision(); runCurrent()
        assertEquals(2, reader.pendingDetails.size)
        holder.close()
    }

    @Test fun metadataIsConciseAndDoesNotPretendMissingDurationIsZero() {
        assertNull(itemDuration(0))
        assertEquals("1:01", itemDuration(61_000))
        assertEquals("2022 · 1:01 · Unavailable", itemMetadata(LibraryItem("track", "t", "Song", durationMs = 61_000, releaseDate = "2022-06", hasAudio = false)))
        assertNull(itemYear(LibraryItem("album", "a", "Album", releaseDate = "bad")))
    }
}
