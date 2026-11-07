package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.ui.theme.AppTypography
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production CatalogScreenContent + real lazy geometry. Not installed-native acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class CatalogContinuousHostTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var holder: CatalogStateHolder
    private class Reader(val total: Int = 30, val initialSize: Int = 10,
        val initialSizes: Map<String, Int> = emptyMap(),
        val totalsByKind: Map<String, Int> = emptyMap(),
        val initialErrors: Set<String> = emptySet()) : CatalogReader {
        data class Pending(val request: CatalogRequest, val reply: CompletableDeferred<LibraryPage>)
        val requests = mutableListOf<CatalogRequest>()
        val pending = mutableListOf<Pending>()
        var revisionValue = 1L
        var ignoreCancellation = false
        override suspend fun browse(request: CatalogRequest): LibraryPage {
            requests += request
            if (request.offset == 0 && request.kind in initialErrors) throw IOException()
            if (request.offset == 0) return page(request, initialSizes[request.kind] ?: initialSize)
            val reply = CompletableDeferred<LibraryPage>().also { pending += Pending(request, it) }
            return if (ignoreCancellation) withContext(NonCancellable) { reply.await() } else reply.await()
        }
        fun page(request: CatalogRequest, size: Int = request.limit): LibraryPage {
            val resultTotal = totalsByKind[request.kind] ?: total
            return LibraryPage(List(minOf(size, (resultTotal - request.offset).coerceAtLeast(0))) { index ->
                val at = request.offset + index
                val label = when (request.kind) { "artists" -> "Artist"; "albums" -> "Album"; else -> "Song" }
                LibraryItem(request.kind.removeSuffix("s"), "${request.kind}-$at", "$label $at", hasAudio = true)
            }, resultTotal, request.limit, request.offset, revisionValue)
        }
        override suspend fun detail(kind: String, id: String) = LibraryItem(kind, id, id)
        override suspend fun revision() = revisionValue
    }
    private fun mount(reader: Reader, kind: String = "tracks", query: String = "music", height: Int = 400,
        playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(), publishUpdates: Boolean = true,
        onPlay: (LibraryItem, Boolean) -> Unit = { _, _ -> }, onMore: (LibraryItem) -> Unit = {},
        sourceEpoch: () -> Any? = { null }) {
        holder = CatalogStateHolder(reader, scope, kind, query)
        compose.runOnIdle { holder.setVisible(true) }
        if (!publishUpdates) await { holder.state.value.current.sections.values.let { sections ->
            sections.isNotEmpty() && sections.all { it.loaded }
        } }
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                // Retaining one real rendered frame lets tests dispatch against an old row
                // before StateFlow/Compose observes a source transition or conflated ABA.
                val navigation = if (publishUpdates) holder.state.collectAsState().value else remember { holder.state.value }
                CatalogScreenContent(navigation, holder, { holder.open(it) }, onPlay, onMore, {},
                    artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.size(320.dp, height.dp), playback = playback,
                    sourceEpoch = sourceEpoch())
            }
        }
        compose.waitForIdle()
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) { compose.runOnIdle(condition) }
    @After fun close() {
        compose.runOnIdle { if (::holder.isInitialized) holder.close(); scope.cancel() }
    }

    @Test fun visibleFrontierReservesOneBatchPreservesAnchorAndStopsAtEnd() {
        val reader = Reader(total = 20)
        mount(reader)
        compose.runOnIdle { assertEquals(listOf(0), reader.requests.map { it.offset }) }
        compose.onNodeWithTag("catalog-results").performScrollToIndex(7)
        await { reader.pending.size == 1 }
        val anchor = compose.runOnIdle { holder.state.value.current.let { it.scrollIndex to it.scrollOffset } }
        compose.onNodeWithContentDescription("Loading more songs").assertExists()
        compose.runOnIdle {
            repeat(30) { holder.more("tracks") }
            assertEquals(listOf(0, 10), reader.requests.map { it.offset })
            val pending = reader.pending.single()
            pending.reply.complete(reader.page(pending.request))
        }
        await { holder.state.value.current.sections.getValue("tracks").items.size == 20 }
        compose.runOnIdle {
            assertEquals(anchor, holder.state.value.current.let { it.scrollIndex to it.scrollOffset })
            assertTrue(holder.state.value.current.sections.getValue("tracks").ended)
        }
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("End of results"))
        compose.onNodeWithText("Song 19").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(0, 10), reader.requests.map { it.offset }) }
        compose.onNodeWithText("See more songs").assertDoesNotExist()
    }

    @Test fun songsVisibleNeverExpandOffscreenEarlierGroupsAndBackRestoresAnchor() {
        val reader = Reader(total = 40, initialSizes = mapOf("artists" to 1, "albums" to 1))
        var playedId: String? = null
        mount(reader, kind = "all", height = 400, onPlay = { item, _ -> playedId = item.id })
        // The initial viewport contains the entire Artists/Albums frontier AND Songs.
        // Only the last visible row group is allowed to request: neither earlier tail loads.
        compose.onNodeWithText("Artist 0").assertIsDisplayed()
        compose.onNodeWithText("Song 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(3, reader.requests.size) }
        compose.onNodeWithTag("catalog-results").performScrollToIndex(9)
        await { reader.pending.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf("tracks"), reader.pending.map { it.request.kind })
            assertEquals(listOf(0), reader.requests.filter { it.kind == "artists" }.map { it.offset })
            assertEquals(listOf(0), reader.requests.filter { it.kind == "albums" }.map { it.offset })
            val pending = reader.pending.single()
            pending.reply.complete(reader.page(pending.request))
        }
        await { holder.state.value.current.sections.getValue("tracks").items.size == 20 }
        val saved = compose.runOnIdle { holder.state.value.current.let { it.scrollIndex to it.scrollOffset } }
        compose.onNodeWithText("Song 7").performClick()
        compose.runOnIdle {
            // Song bodies now Play now, never push a detail destination. Intentional
            // detail/credit navigation still restores the containing list's anchor.
            assertEquals("tracks-7", playedId)
            assertFalse(holder.state.value.canGoBack)
            holder.open(LibraryItem("track", "tracks-7", "Song 7", hasAudio = true))
            assertTrue(holder.back())
        }
        compose.waitForIdle()
        compose.onNodeWithText("Song 7").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(saved, holder.state.value.current.let { it.scrollIndex to it.scrollOffset })
            assertEquals(4, reader.requests.size)
        }
        for (label in listOf("Artists", "Albums", "Songs", "Previous", "Next"))
            compose.onNodeWithText(label).assertDoesNotExist()
    }

    @Test fun failedAppendStaysLatchedUntilAccessibleRetryOfSameOffset() {
        val reader = Reader(total = 30)
        mount(reader)
        compose.onNodeWithTag("catalog-results").performScrollToIndex(7)
        await { reader.pending.size == 1 }
        compose.runOnIdle { reader.pending.single().reply.completeExceptionally(IOException()) }
        await { holder.state.value.current.sections.getValue("tracks").error != null }
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Retry songs"))
        compose.onNodeWithText("Retry songs").assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { holder.setVisible(false); holder.setVisible(true) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(0, 10), reader.requests.map { it.offset })
            assertEquals(10, holder.state.value.current.sections.getValue("tracks").items.size)
        }
        compose.runOnIdle { reader.ignoreCancellation = true }
        compose.onNodeWithText("Retry songs").performClick()
        await { reader.pending.size == 2 }
        compose.onNodeWithText("Retry songs").assertDoesNotExist()
        compose.onNodeWithContentDescription("Loading more songs").assertExists()
        compose.runOnIdle {
            val retrying = holder.state.value.current.sections.getValue("tracks")
            assertNotNull("Loading hides, but does not erase, the failed batch latch", retrying.error)
            assertEquals(10, retrying.requestedOffset)
            assertTrue(retrying.appendPending)
            holder.open(LibraryItem("track", "nested", "Nested"))
            assertTrue(holder.back())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val obsolete = reader.pending.last()
            obsolete.reply.complete(reader.page(obsolete.request))
            runBlocking { holder.checkRevision() } // An unchanged poll cannot grant Retry either.
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val returned = holder.state.value.current.sections.getValue("tracks")
            assertEquals(listOf(0, 10, 10), reader.requests.map { it.offset })
            assertEquals(10, returned.items.size)
            assertEquals(10, returned.requestedOffset)
            assertTrue(returned.appendPending)
            assertNotNull(returned.error)
            assertFalse(returned.loading)
        }
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Retry songs"))
        compose.onNodeWithText("Retry songs").performClick()
        await { reader.pending.size == 3 }
        compose.runOnIdle {
            assertEquals(listOf(0, 10, 10, 10), reader.requests.map { it.offset })
            val retry = reader.pending.last()
            retry.reply.complete(reader.page(retry.request))
        }
        await { holder.state.value.current.sections.getValue("tracks").items.size == 20 }
        compose.runOnIdle { assertNull(holder.state.value.current.sections.getValue("tracks").error) }
    }

    @Test fun shortVisibleBatchCanFillButAppendAloneCannotDrainCatalog() {
        val reader = Reader(total = 500, initialSize = 1)
        mount(reader, height = 640)
        await { reader.pending.size == 1 }
        compose.runOnIdle {
            val next = reader.pending.last()
            next.reply.complete(reader.page(next.request, size = 1))
        }
        await { reader.pending.size == 2 }
        compose.runOnIdle {
            val next = reader.pending.last()
            next.reply.complete(reader.page(next.request, size = 1))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals(listOf(0, 1, 2), reader.requests.map { it.offset })
            assertEquals(3, holder.state.value.current.sections.getValue("tracks").items.size)
        }
    }

    @Test fun laterEmptyGroupRetryIsAVisibleYieldBoundaryWithoutAutomaticRetry() {
        val reader = Reader(total = 30, totalsByKind = mapOf("albums" to 0, "tracks" to 0),
            initialErrors = setOf("albums"))
        mount(reader, kind = "all", height = 400)
        compose.runOnIdle { holder.demandMore("artists") }
        await { reader.pending.size == 1 }
        compose.runOnIdle {
            val next = reader.pending.single()
            next.reply.complete(reader.page(next.request))
        }
        await { holder.state.value.current.sections.getValue("artists").items.size == 20 }
        compose.runOnIdle { assertTrue(holder.state.value.current.sections.getValue("artists").demandPaused) }
        compose.onNodeWithTag("catalog-results").performScrollToNode(hasText("Retry albums"))
        compose.onNodeWithText("Retry albums").assertIsDisplayed()
        await { !holder.state.value.current.sections.getValue("artists").demandPaused }
        compose.runOnIdle {
            assertEquals("Observing a failed group's footer is not a Retry", 1, reader.requests.count { it.kind == "albums" })
        }
        // Return far enough to see the final Artist row without the later Retry in view.
        compose.onNodeWithTag("catalog-results").performScrollToIndex(15)
        await { reader.pending.size == 2 }
        compose.runOnIdle {
            assertEquals(listOf(0, 10, 20), reader.requests.filter { it.kind == "artists" }.map { it.offset })
            val next = reader.pending.last()
            next.reply.complete(reader.page(next.request))
        }
        await { holder.state.value.current.sections.getValue("artists").ended }
        compose.runOnIdle { assertEquals(1, reader.requests.count { it.kind == "albums" }) }
    }

    @Test fun onlyArtistsWithHealthyEmptyLaterGroupsCanContinueWithoutYieldDeadlock() {
        val reader = Reader(total = 30, totalsByKind = mapOf("albums" to 0, "tracks" to 0))
        mount(reader, kind = "all", height = 400)
        for ((index, expected) in listOf(7 to 1, 17 to 2)) {
            compose.onNodeWithTag("catalog-results").performScrollToIndex(index)
            await { reader.pending.size == expected }
            compose.runOnIdle {
                assertFalse(holder.state.value.current.sections.getValue("artists").demandPaused)
                val next = reader.pending.last()
                next.reply.complete(reader.page(next.request))
            }
            await { holder.state.value.current.sections.getValue("artists").items.size == (expected + 1) * 10 }
        }
        compose.runOnIdle {
            assertTrue(holder.state.value.current.sections.getValue("artists").ended)
            assertEquals(listOf(0, 10, 20), reader.requests.filter { it.kind == "artists" }.map { it.offset })
            assertEquals(1, reader.requests.count { it.kind == "albums" })
            assertEquals(1, reader.requests.count { it.kind == "tracks" })
        }
    }

    @Test fun heldRightSwipeIsRetiredByPublishedRevisionAndHostEpochWithUnchangedItemsAndPlayerKey() {
        val reader = Reader()
        val key = PlaybackInteractionKey()
        val epoch = mutableStateOf(0L)
        val queued = mutableListOf<String>()
        mount(reader, sourceEpoch = { epoch.value }, playback = CatalogPlaybackFeedback(interactionKey = key, onContextual = { item, captured, current ->
            assertSame(key, captured)
            assertTrue(current())
            queued += item.id
        }))
        val row = compose.onNodeWithText("Song 0")
        val original = compose.runOnIdle { holder.state.value.current.sections.getValue("tracks").items }
        row.performTouchInput { down(center.copy(x = width * .3f)); moveTo(center.copy(x = width * .6f)) }
        compose.runOnIdle { reader.revisionValue = 2; runBlocking { holder.checkRevision() } }
        await { holder.state.value.current.sections.getValue("tracks").revision == 2L }
        compose.runOnIdle { assertEquals(original, holder.state.value.current.sections.getValue("tracks").items) }
        row.performTouchInput { moveTo(center.copy(x = width * .8f)); up() }
        compose.runOnIdle { assertTrue("The old gesture cannot adopt the new source version", queued.isEmpty()) }
        row.performTouchInput { swipe(start = center.copy(x = width * .3f), end = center.copy(x = width * .8f)) }
        compose.runOnIdle { assertEquals("A fresh gesture on the new source remains usable", listOf("tracks-0"), queued) }

        val version = compose.runOnIdle { holder.navigationVersion }
        val anchor = compose.runOnIdle { holder.state.value.current.let { it.scrollIndex to it.scrollOffset } }
        row.performTouchInput { down(center.copy(x = width * .3f)); moveTo(center.copy(x = width * .6f)) }
        compose.runOnIdle {
            // Host tab A→B→A can be conflated while the same Catalog holder/items survive.
            // A later progress frame publishes its advanced epoch, not a new catalog revision.
            epoch.value = 1L
            epoch.value = 2L
        }
        compose.waitForIdle()
        row.performTouchInput { moveTo(center.copy(x = width * .8f)); up() }
        compose.runOnIdle {
            assertEquals("The held gesture cannot borrow a new host-epoch callback", listOf("tracks-0"), queued)
            assertEquals(version, holder.navigationVersion)
            assertEquals(anchor, holder.state.value.current.let { it.scrollIndex to it.scrollOffset })
            assertEquals(original, holder.state.value.current.sections.getValue("tracks").items)
        }
        row.performTouchInput { swipe(start = center.copy(x = width * .3f), end = center.copy(x = width * .8f)) }
        compose.runOnIdle { assertEquals(listOf("tracks-0", "tracks-0"), queued) }
    }

    @Test fun heldRightSwipeCannotDispatchAgainstUnpublishedRevision() {
        val reader = Reader()
        val queued = mutableListOf<String>()
        mount(reader, publishUpdates = false, playback = CatalogPlaybackFeedback(interactionKey = PlaybackInteractionKey(),
            onContextual = { item, _, _ -> queued += item.id }))
        val row = compose.onNodeWithText("Song 0")
        row.performTouchInput { down(center.copy(x = width * .3f)); moveTo(center.copy(x = width * .6f)) }
        compose.runOnIdle { reader.revisionValue = 2; runBlocking { holder.checkRevision() } }
        row.performTouchInput { moveTo(center.copy(x = width * .8f)); up() }
        compose.runOnIdle { assertTrue("Event-time source validation precedes dispatch, even with the old render retained", queued.isEmpty()) }
    }

    @Test fun heldRightSwipeAndRetainedActionsRejectUnpublishedQueryABA() {
        val reader = Reader()
        val queued = mutableListOf<String>()
        var plays = 0
        var more = 0
        mount(reader, publishUpdates = false, playback = CatalogPlaybackFeedback(interactionKey = PlaybackInteractionKey(),
            onContextual = { item, _, _ -> queued += item.id }), onPlay = { _, _ -> plays++ }, onMore = { more++ })
        val row = compose.onNodeWithText("Song 0")
        val actions = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        val play = actions.single { it.label == "Play now" }
        val showMore = actions.single { it.label == "More actions" }
        row.performTouchInput { down(center.copy(x = width * .3f)); moveTo(center.copy(x = width * .6f)) }
        compose.runOnIdle {
            holder.commitQuery("other", immediate = true)
            holder.commitQuery("music", immediate = true) // No collector/render can sample B.
            assertEquals("music", holder.state.value.current.query)
            assertTrue(play.action()); assertTrue(showMore.action())
            assertEquals(0, plays); assertEquals(0, more)
        }
        row.performTouchInput { moveTo(center.copy(x = width * .8f)); up() }
        compose.runOnIdle { assertTrue("Returning to equal query/items/player key cannot revive the held gesture", queued.isEmpty()) }
    }

    @Test fun blankSearchHasNoAutomaticMatchAllReads() {
        val reader = Reader()
        mount(reader, kind = "all", query = " ")
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertTrue(reader.requests.isEmpty()) }
        compose.onNodeWithContentDescription("Search your music").assertExists()
    }
}
