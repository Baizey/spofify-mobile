package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
        data class Read(val request: CatalogRequest, val result: CompletableDeferred<LibraryPage> = CompletableDeferred(), var cancelled: Boolean = false)
        val reads = mutableListOf<Read>()
        val details = mutableListOf<Pair<String, String>>()
        var revisionValue = 1L
        var ignoreCancellation = false
        var detailError: Exception? = null
        var suspendDetails = false
        val pendingDetails = mutableListOf<CompletableDeferred<LibraryItem>>()
        override suspend fun browse(request: CatalogRequest): LibraryPage {
            val read = Read(request).also { reads += it }
            return try {
                if (ignoreCancellation) withContext(NonCancellable) { read.result.await() } else read.result.await()
            } catch (error: CancellationException) {
                read.cancelled = true
                throw error
            }
        }
        override suspend fun detail(kind: String, id: String): LibraryItem {
            details += kind to id
            detailError?.let { throw it }
            if (suspendDetails) return CompletableDeferred<LibraryItem>().also { pendingDetails += it }.await()
            return LibraryItem(kind, id, "Resolved $id")
        }
        override suspend fun revision() = revisionValue
        fun completePending(revision: Long = 1) {
            reads.filterNot { it.result.isCompleted }.forEach { read -> read.result.complete(LibraryPage(
                List(minOf(read.request.limit, (25 - read.request.offset).coerceAtLeast(0))) { index ->
                    LibraryItem(read.request.kind.removeSuffix("s"), "${read.request.kind}-${read.request.offset + index}", "Result")
                }, 25, read.request.limit, read.request.offset, revision)) }
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

    private fun Reader.Read.completeRows(total: Int = 45, revision: Long = 1) {
        result.complete(LibraryPage(List(request.limit) { index ->
            LibraryItem(request.kind.removeSuffix("s"), "${request.kind}-${request.offset + index}", "Result")
        }, total, request.limit, request.offset, revision))
    }

    @Test fun unchangedSubmitPreservesSelectedSectionExpandedPageScrollAndRevisionBaseline() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "music")
        holder.includeMissing(true)
        holder.setVisible(true); runCurrent()
        reader.reads.last().completeRows(revision = 7); runCurrent()
        holder.page("tracks", 1); runCurrent()
        reader.reads.last().completeRows(revision = 7); runCurrent()
        holder.more("tracks"); runCurrent()
        reader.reads.last().completeRows(revision = 7); runCurrent()
        holder.rememberScroll(0, 12, 37, expectedQuery = "music")
        val accepted = holder.state.value.current
        assertEquals(10, accepted.sections.getValue("tracks").offset)
        assertEquals(20, accepted.sections.getValue("tracks").items.size)
        val count = reader.reads.size

        holder.commitQuery("music", immediate = true)
        holder.commitQuery("mu\nsic", immediate = true)
        holder.commitQuery("music")
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertSame(accepted, holder.state.value.current)
        assertEquals(count, reader.reads.size)
        assertTrue(reader.reads.none { it.cancelled })
        assertEquals("tracks", holder.state.value.current.kind)
        assertTrue(holder.state.value.current.includeMissing)
        assertEquals(12, holder.state.value.current.scrollIndex)
        assertEquals(37, holder.state.value.current.scrollOffset)

        reader.revisionValue = 7
        holder.checkRevision(); runCurrent()
        assertEquals(count, reader.reads.size)
        reader.revisionValue = 8
        holder.checkRevision(); runCurrent()
        assertEquals(count + 1, reader.reads.size)
        assertEquals(10, reader.reads.last().request.offset)
        assertEquals(20, reader.reads.last().request.limit)
        assertEquals(accepted.sections.getValue("tracks").items, holder.state.value.current.sections.getValue("tracks").items)
        reader.reads.last().completeRows(revision = 8); runCurrent()
        assertEquals(12, holder.state.value.current.scrollIndex)
        assertEquals(37, holder.state.value.current.scrollOffset)
        holder.close()
    }

    @Test fun unchangedSubmitDoesNotCancelOrReplacePendingGroupedBodies() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent()
        holder.rememberScroll(0, 3, 19)
        val pending = holder.state.value.current

        holder.commitQuery("music", immediate = true)
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertSame(pending, holder.state.value.current)
        assertEquals(3, reader.reads.size)
        assertTrue(reader.reads.none { it.cancelled })
        reader.reads.forEach { it.completeRows() }; runCurrent()
        assertTrue(holder.state.value.current.sections.values.all { it.loaded && !it.loading && it.items.size == 10 })
        assertEquals(3, holder.state.value.current.scrollIndex)
        assertEquals(19, holder.state.value.current.scrollOffset)
        assertEquals(3, reader.reads.size)
        holder.close()
    }

    @Test fun unchangedSubmitPreservesPendingAppendAndItsLoadedPrefix() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "music")
        holder.setVisible(true); runCurrent()
        reader.reads.last().completeRows(); runCurrent()
        holder.more("tracks"); runCurrent()
        holder.rememberScroll(0, 8, 27)
        val pending = holder.state.value.current
        val section = pending.sections.getValue("tracks")
        assertTrue(section.appendPending)
        assertEquals(10, section.requestedOffset)

        holder.commitQuery("music", immediate = true)
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertSame(pending, holder.state.value.current)
        assertEquals(2, reader.reads.size)
        assertTrue(reader.reads.none { it.cancelled })
        reader.reads.last().completeRows(); runCurrent()
        val appended = holder.state.value.current.sections.getValue("tracks")
        assertEquals((0 until 20).map { "tracks-$it" }, appended.items.map { it.id })
        assertEquals(0, appended.offset)
        assertFalse(appended.appendPending)
        assertNull(appended.requestedOffset)
        assertEquals(8, holder.state.value.current.scrollIndex)
        assertEquals(27, holder.state.value.current.scrollOffset)
        assertEquals(2, reader.reads.size)
        holder.close()
    }

    @Test fun unchangedImmediateSubmitFlushesActiveDebounceExactlyOnceWithoutResettingScroll() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all")
        holder.setVisible(true)
        holder.commitQuery("music"); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertTrue(reader.reads.isEmpty())
        holder.rememberScroll(0, 2, 17, expectedQuery = "music")

        holder.commitQuery("music", immediate = true); runCurrent()
        assertEquals(3, reader.reads.size)
        assertTrue(reader.reads.all { it.request.query == "music" && it.request.limit == 10 })
        assertEquals(2, holder.state.value.current.scrollIndex)
        assertEquals(17, holder.state.value.current.scrollOffset)
        advanceTimeBy(300); runCurrent()
        assertEquals(3, reader.reads.size)
        assertTrue(reader.reads.none { it.cancelled })
        reader.completePending(); runCurrent()
        holder.commitQuery("music", immediate = true); runCurrent()
        assertEquals(3, reader.reads.size)
        assertTrue(holder.state.value.current.sections.values.all { it.loaded })
        holder.close()
    }

    @Test fun flushingDebouncePreservesAReadAlreadyStartedByExplicitRetry() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all")
        holder.setVisible(true)
        holder.commitQuery("music"); runCurrent()
        holder.retry("tracks"); runCurrent()
        assertEquals(1, reader.reads.size)
        val pending = reader.reads.single()
        val pendingSection = holder.state.value.current.sections.getValue("tracks")

        holder.commitQuery("music", immediate = true); runCurrent()
        assertSame(pendingSection, holder.state.value.current.sections.getValue("tracks"))
        assertEquals(CatalogKinds.toSet(), reader.reads.map { it.request.kind }.toSet())
        assertEquals(3, reader.reads.size)
        assertFalse(pending.cancelled)
        advanceTimeBy(300); runCurrent()
        assertEquals(3, reader.reads.size)
        assertTrue(reader.reads.none { it.cancelled })
        reader.reads.forEach { it.completeRows() }; runCurrent()
        assertTrue(holder.state.value.current.sections.values.all { it.loaded && it.items.size == 10 })
        holder.close()
    }

    @Test fun unchangedImmediateSubmitCanFlushBeforeDebounceCoroutineStarts() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks")
        holder.setVisible(true)
        holder.commitQuery("music")
        holder.commitQuery("music", immediate = true); runCurrent()
        assertEquals(1, reader.reads.size)
        assertEquals("music", reader.reads.single().request.query)
        advanceTimeBy(300); runCurrent()
        assertEquals(1, reader.reads.size)
        assertFalse(reader.reads.single().cancelled)
        reader.completePending(); runCurrent(); holder.close()
    }

    @Test fun changedImmediateQueryStillResetsRowsPagingAndScrollWithoutWaitingForDebounce() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "old")
        holder.setVisible(true); runCurrent()
        reader.reads.last().completeRows(); runCurrent()
        holder.page("tracks", 1); runCurrent()
        reader.reads.last().completeRows(); runCurrent()
        holder.rememberScroll(0, 9, 31)

        holder.commitQuery("new", immediate = true)
        assertEquals("new", holder.state.value.current.query)
        assertTrue(holder.state.value.current.sections.isEmpty())
        assertEquals(0, holder.state.value.current.scrollIndex)
        assertEquals(0, holder.state.value.current.scrollOffset)
        runCurrent()
        assertEquals(3, reader.reads.size)
        assertEquals("new", reader.reads.last().request.query)
        assertEquals(0, reader.reads.last().request.offset)
        reader.completePending(); runCurrent(); holder.close()
    }

    @Test fun unchangedSubmitDoesNotTurnExplicitRetryIntoANoOp() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "music")
        holder.setVisible(true); runCurrent()
        reader.reads.single().result.completeExceptionally(IOException()); runCurrent()
        val failed = holder.state.value.current
        holder.commitQuery("music", immediate = true); runCurrent()
        assertSame(failed, holder.state.value.current)
        assertEquals(1, reader.reads.size)
        holder.retry("tracks"); runCurrent()
        assertEquals(2, reader.reads.size)
        reader.reads.last().completeRows(); runCurrent()
        assertTrue(holder.state.value.current.sections.getValue("tracks").loaded)
        assertNull(holder.state.value.current.sections.getValue("tracks").error)
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

    @Test fun firstSameRevisionDetailFencePreservesActionsButRealRevisionAdvanceRetiresThem() = runTest {
        for (kind in listOf("track", "album")) {
            val seed = LibraryItem(kind, "detail", "Detail")
            val reader = Reader().apply { suspendDetails = true }
            val holder = CatalogStateHolder(reader, backgroundScope, initialItem = seed)
            holder.setVisible(true); runCurrent()
            // Albums can establish a baseline through their child read before detail resolves;
            // tracks have no child section. Both initial cheap-fence paths must stay benign.
            reader.completePending(1); runCurrent()
            val openingVersion = holder.navigationVersion
            holder.checkRevision(); runCurrent()
            reader.pendingDetails[0].complete(seed); runCurrent()
            assertEquals("The initial fence must still validate the detail", 2, reader.pendingDetails.size)
            assertEquals("Same-baseline revalidation is not a source change", openingVersion, holder.navigationVersion)
            reader.pendingDetails[1].complete(seed)
            reader.completePending(1); runCurrent()
            assertEquals(openingVersion, holder.navigationVersion)

            reader.revisionValue = 2
            holder.checkRevision(); runCurrent()
            assertTrue("A genuine source revision must still retire a held action", holder.navigationVersion > openingVersion)
            reader.pendingDetails[2].complete(seed)
            reader.completePending(2); runCurrent()
            holder.close()
        }
    }

    @Test fun changedResolvedDetailAndWithdrawalRetireActionsEvenAtSamePolledRevision() = runTest {
        for (change in listOf("metadata", "availability", "identity", "missing")) {
            val seed = LibraryItem("track", "detail", "Detail", hasAudio = true)
            val reader = Reader().apply { suspendDetails = true }
            val holder = CatalogStateHolder(reader, backgroundScope, initialItem = seed)
            holder.setVisible(true); runCurrent()
            reader.pendingDetails.single().complete(seed); runCurrent()
            val openingVersion = holder.navigationVersion
            if (change == "missing") reader.detailError = ApiException(404, "Missing")
            // First validation requests a detail read without changing the known revision.
            holder.checkRevision(); runCurrent()
            if (change != "missing") {
                assertEquals(openingVersion, holder.navigationVersion)
                val changed = when (change) {
                    "metadata" -> seed.copy(name = "Changed detail")
                    "availability" -> seed.copy(hasAudio = false)
                    else -> seed.copy(id = "different-source")
                }
                reader.pendingDetails.last().complete(changed); runCurrent()
            }
            assertTrue("$change must revoke the previously held detail action", holder.navigationVersion > openingVersion)
            val current = holder.state.value.current
            when (change) {
                "metadata" -> assertEquals("Changed detail", current.item?.name)
                "availability" -> assertFalse(checkNotNull(current.item).playable)
                "identity" -> {
                    assertEquals("detail", current.item?.id)
                    assertNotNull(current.detailError)
                }
                else -> assertTrue(current.detailUnavailable)
            }
            holder.close()
        }
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

    @Test fun automaticDemandReservesSynchronouslyAndAppendDoesNotRevokeSourceActions() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "music")
        holder.setVisible(true); runCurrent(); reader.reads.single().completeRows(); runCurrent()
        val before = holder.state.value.current
        val version = holder.navigationVersion
        val keys = before.sections.getValue("tracks").items.map { catalogRowKey(before, "tracks", it) }
        repeat(50) { holder.more("tracks") }
        assertTrue(holder.state.value.current.sections.getValue("tracks").loading)
        assertFalse(holder.state.value.current.sections.getValue("tracks").needsReload)
        assertEquals(version, holder.navigationVersion)
        runCurrent()
        assertEquals(listOf(0, 10), reader.reads.map { it.request.offset })
        reader.reads.last().completeRows(); runCurrent()
        val after = holder.state.value.current
        assertEquals(version, holder.navigationVersion)
        assertEquals(keys, after.sections.getValue("tracks").items.take(10).map { catalogRowKey(after, "tracks", it) })
        holder.close()
    }

    @Test fun earlierGroupedExpansionYieldsUntilLaterRowsAreActuallyVisited() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "all", "music")
        holder.setVisible(true); runCurrent(); reader.completePending(); runCurrent()
        holder.demandMore("artists"); runCurrent(); reader.completePending(); runCurrent()
        assertTrue(holder.state.value.current.sections.getValue("artists").demandPaused)
        repeat(20) { holder.demandMore("artists") }; runCurrent()
        assertEquals(listOf(0, 10), reader.reads.filter { it.request.kind == "artists" }.map { it.request.offset })
        val screen = holder.state.value.current
        holder.visibleSection(screen.key, screen.resultGeneration, "albums")
        assertFalse(holder.state.value.current.sections.getValue("artists").demandPaused)
        holder.demandMore("artists"); runCurrent()
        assertEquals(listOf(0, 10, 20), reader.reads.filter { it.request.kind == "artists" }.map { it.request.offset })
        reader.completePending(); runCurrent(); holder.close()
    }

    @Test fun appendFailureIsLatchedAcrossDemandVisibilityAndPollAndRetriesExactBatchOnce() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "music")
        holder.setVisible(true); runCurrent(); reader.reads.single().completeRows(); runCurrent()
        holder.more("tracks"); runCurrent()
        reader.reads.last().result.completeExceptionally(IOException()); runCurrent()
        val failed = holder.state.value.current.sections.getValue("tracks")
        repeat(20) { holder.more("tracks") }
        holder.setVisible(false); holder.setVisible(true)
        holder.checkRevision(); runCurrent()
        assertEquals(2, reader.reads.size)
        assertEquals(10, failed.requestedOffset)
        assertNotNull(failed.error)
        holder.retry("tracks"); holder.retry("tracks"); runCurrent()
        assertEquals(listOf(0, 10, 10), reader.reads.map { it.request.offset })
        assertEquals(failed.items, holder.state.value.current.sections.getValue("tracks").items)
        reader.reads.last().completeRows(); runCurrent()
        assertEquals(20, holder.state.value.current.sections.getValue("tracks").items.size)
        holder.close()
    }

    @Test fun rawConsumedOffsetsDeduplicateIdentitiesAndEmptyBatchStopsAtTrueFrontier() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "music")
        holder.setVisible(true); runCurrent()
        val one = LibraryItem("track", "one", "One")
        val two = LibraryItem("track", "two", "Two")
        reader.reads.single().result.complete(LibraryPage(listOf(one, one, two), 20, 10, 0, 1)); runCurrent()
        var section = holder.state.value.current.sections.getValue("tracks")
        assertEquals(listOf(one, two), section.items)
        assertEquals(3, section.nextOffset)
        holder.more("tracks"); runCurrent()
        assertEquals(3, reader.reads.last().request.offset)
        reader.reads.last().result.complete(LibraryPage(emptyList(), 20, 10, 3, 1)); runCurrent()
        section = holder.state.value.current.sections.getValue("tracks")
        assertEquals(listOf(one, two), section.items)
        assertTrue(section.ended); assertFalse(section.hasNext)
        repeat(5) { holder.more("tracks") }; runCurrent()
        assertEquals(2, reader.reads.size)
        holder.close()
    }

    @Test fun repeatedIdentityAndWrongPositionStopWithoutDiscardingOrClaimingEnd() = runTest {
        for (wrongOffset in listOf(false, true)) {
            val reader = Reader()
            val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "music")
            holder.setVisible(true); runCurrent(); reader.reads.single().completeRows(); runCurrent()
            val prefix = holder.state.value.current.sections.getValue("tracks").items
            holder.more("tracks"); runCurrent()
            reader.reads.last().result.complete(LibraryPage(if (wrongOffset) listOf(LibraryItem("track", "new", "New")) else prefix,
                45, 10, if (wrongOffset) 0 else 10, 1)); runCurrent()
            val section = holder.state.value.current.sections.getValue("tracks")
            assertEquals(prefix, section.items)
            assertNotNull(section.error); assertTrue(section.refreshRequired); assertFalse(section.ended)
            repeat(5) { holder.more("tracks") }; runCurrent()
            assertEquals(2, reader.reads.size)
            holder.close()
        }
    }

    @Test fun revisionChangingAppendRetainsPrefixAndExplicitRefreshRebuildsIt() = runTest {
        val reader = Reader()
        val holder = CatalogStateHolder(reader, backgroundScope, "tracks", "music")
        holder.setVisible(true); runCurrent(); reader.reads.single().completeRows(); runCurrent()
        val prefix = holder.state.value.current.sections.getValue("tracks").items
        val version = holder.navigationVersion
        holder.more("tracks"); runCurrent(); reader.reads.last().completeRows(revision = 2); runCurrent()
        assertEquals(prefix, holder.state.value.current.sections.getValue("tracks").items)
        assertTrue(holder.state.value.current.sections.getValue("tracks").refreshRequired)
        assertTrue(holder.navigationVersion > version)
        holder.retry("tracks"); runCurrent()
        assertEquals(0, reader.reads.last().request.offset)
        reader.reads.last().completeRows(revision = 2); runCurrent()
        assertEquals(2L, holder.state.value.current.sections.getValue("tracks").revision)
        assertNull(holder.state.value.current.sections.getValue("tracks").error)
        holder.close()
    }

    @Test fun queryFilterAndReleaseGroupABARejectCancellationIgnoringAppendAndChangeRowKeys() = runTest {
        for (change in 0..2) {
            val reader = Reader().apply { ignoreCancellation = true }
            val holder = if (change == 2) CatalogStateHolder(reader, backgroundScope,
                initialItem = LibraryItem("artist", "artist", "Artist"))
                else CatalogStateHolder(reader, backgroundScope, "tracks", "music")
            val kind = if (change == 2) "albums" else "tracks"
            holder.setVisible(true); runCurrent(); reader.reads.single().completeRows(total = 100); runCurrent()
            val oldScreen = holder.state.value.current
            val oldKey = catalogRowKey(oldScreen, kind, oldScreen.sections.getValue(kind).items.first())
            holder.more(kind); runCurrent()
            val obsolete = reader.reads.last()
            when (change) {
                0 -> { holder.commitQuery("other", true); holder.commitQuery("music", true) }
                1 -> { holder.includeMissing(true); holder.includeMissing(false) }
                else -> { holder.selectReleaseGroup("singles"); holder.selectReleaseGroup("albums") }
            }
            runCurrent()
            val newest = reader.reads.last()
            newest.completeRows(total = 100); runCurrent()
            obsolete.completeRows(total = 100); runCurrent()
            val current = holder.state.value.current
            assertEquals(0, current.sections.getValue(kind).offset)
            assertEquals(if (change == 2) 40 else 10, current.sections.getValue(kind).items.size)
            assertNotEquals(oldKey, catalogRowKey(current, kind, current.sections.getValue(kind).items.first()))
            reader.completePending(); runCurrent(); holder.close()
        }
    }

    @Test fun metadataIsConciseAndDoesNotPretendMissingDurationIsZero() {
        assertNull(itemDuration(0))
        assertEquals("1:01", itemDuration(61_000))
        assertEquals("2022 · 1:01 · Unavailable", itemMetadata(LibraryItem("track", "t", "Song", durationMs = 61_000, releaseDate = "2022-06", hasAudio = false)))
        assertNull(itemYear(LibraryItem("album", "a", "Album", releaseDate = "bad")))
    }
}
