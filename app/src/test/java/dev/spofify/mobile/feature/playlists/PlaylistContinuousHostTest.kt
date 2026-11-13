package dev.spofify.mobile.feature.playlists

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.library.CatalogPlaybackFeedback
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production route + authenticated APIs + actual finite LazyColumn viewport, not helper-only tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistContinuousHostTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    private enum class Source { INDEX, MANUAL, DERIVED }

    private class Fixture(val source: Source, val count: Int = 85) : AutoCloseable {
        val web = MockWebServer()
        val reads = Collections.synchronizedList(mutableListOf<Int>())
        val summaryReads = AtomicInteger()
        val preparations = AtomicInteger()
        val regenerationRequests = AtomicInteger()
        val failNextAppend = AtomicBoolean()
        val holdAppend = AtomicBoolean()
        val enteredAppend = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val changedFence = AtomicBoolean()
        val changeSummaryAfterAppend = AtomicBoolean()
        val overlapIndex = AtomicBoolean()
        val prematureEnd = AtomicBoolean()
        val client: SpofifyClient
        init {
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val path = url.encodedPath
                    if (path.endsWith("/playback")) preparations.incrementAndGet()
                    if (path.endsWith("/evaluation") || path.endsWith("/preview")) regenerationRequests.incrementAndGet()
                    return when (path) {
                        "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                        "/api/me/playlists" -> {
                            if (source == Source.INDEX) batch(url.queryParameter("offset")!!.toInt())
                            else json("""{"items":[${summary()}],"total":1,"offset":0,"limit":40}""")
                        }
                        "/api/me/playlists/fixture" -> {
                            summaryReads.incrementAndGet()
                            val revision = if (changeSummaryAfterAppend.get() && reads.contains(40)) 6 else 5
                            json(summary(revision = revision, tracks = if (source == Source.INDEX) 1 else count))
                        }
                        "/api/me/playlists/fixture/entries" -> if (source == Source.INDEX)
                            json("""{"items":[{"id":"entry-0","position":0,"track":{"kind":"track","id":"same","name":"Song 0","hasAudio":true}}],"total":1,"offset":0,"limit":40}""")
                        else if (source == Source.DERIVED) batch(url.queryParameter("cursor")?.removePrefix("cursor-")?.toInt() ?: 0)
                        else batch(url.queryParameter("offset")!!.toInt())
                        "/api/me/likes/status" -> json("""{"items":[{"trackId":"same","liked":false}]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            web.start(InetAddress.getByName("127.0.0.1"), 0)
            client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            runBlocking { client.login("listener", "synthetic") }
        }
        private fun summary(id: String = "fixture", name: String = "Fixture playlist", revision: Int = 5, tracks: Int = count): String {
            val derived = source == Source.DERIVED
            return """{"id":"$id","name":"$name","kind":"${if (derived) "derived" else "manual"}","countState":"${if (derived) "unevaluated" else "exact"}","trackCount":${if (derived) "null" else tracks},"availableCount":${if (derived) "null" else tracks},"revision":$revision,"capabilities":{"play":true,"editMetadata":false,"editEntries":false,"editDefinition":false,"delete":false,"copyToManual":false,"addTracks":false}}"""
        }
        private fun batch(offset: Int): MockResponse {
            reads += offset
            if (offset > 0) {
                enteredAppend.countDown()
                if (holdAppend.get()) check(releaseAppend.await(10, TimeUnit.SECONDS)) { "Test did not release its append" }
                if (failNextAppend.compareAndSet(true, false)) return MockResponse().setResponseCode(503)
            }
            val end = minOf(offset + 40, count)
            val items = (offset until end).joinToString { index ->
                val track = """{"kind":"track","id":"same","name":"Song $index","hasAudio":true}"""
                when (source) {
                    Source.INDEX -> {
                        val identity = if (overlapIndex.get() && offset == 40 && index == 40) 39 else index
                        summary("playlist-$identity", "Playlist $identity")
                    }
                    Source.MANUAL -> """{"id":"entry-$index","position":$index,"track":$track}"""
                    Source.DERIVED -> """{"occurrenceId":"path-$index","selectionToken":"signed-$index","position":$index,"track":$track}"""
                }
            }
            return if (source == Source.DERIVED) {
                val cursor = if (end < count && !prematureEnd.get()) "\"cursor-$end\"" else "null"
                val fence = if (offset > 0 && changedFence.get()) "changed-fence" else "fence"
                json("""{"items":[$items],"total":$count,"availableTotal":$count,"limit":40,"nextCursor":$cursor,"revision":5,"fence":"$fence","complete":true}""")
            } else json("""{"items":[$items],"total":$count,"offset":$offset,"limit":40}""")
        }
        fun requests(): List<Int> = synchronized(reads) { reads.toList() }
        override fun close() {
            releaseAppend.countDown()
            client.httpClient.dispatcher.cancelAll()
            client.httpClient.connectionPool.evictAll()
            client.httpClient.dispatcher.executorService.shutdown()
            web.close()
        }
        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) { compose.waitForIdle(); condition() }
    private fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun scroll(key: String) = compose.onNode(hasScrollToIndexAction()).performScrollToKey(key)
    private fun appendSettled() = compose.onAllNodesWithContentDescription("Loading more playlists")
        .fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithContentDescription("Loading more songs")
        .fetchSemanticsNodes().isEmpty()

    private fun host(fixture: Fixture, run: (MutableHost) -> Unit) {
        val shown = mutableStateOf(true)
        val key = PlaybackInteractionKey()
        val feedback = mutableStateOf(CatalogPlaybackFeedback(interactionKey = key,
            queueIntentBinding = dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding("controller", "receiver", 1, 1, 1)))
        val accepted = mutableListOf<Pair<LibraryPlaybackSelection, PlaybackListAction>>()
        val sourceEpoch = mutableStateOf(0)
        val initialId = mutableStateOf(if (fixture.source == Source.INDEX) null else "fixture")
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            val currentDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            SideEffect { dispatcher = currentDispatcher }
            MaterialTheme {
                if (shown.value) PlaylistsRoute(fixture.client, {}, {},
                    initialPlaylistId = initialId.value, playback = feedback.value, sourceEpoch = sourceEpoch.value,
                    onQueueIntent = { selection, action, captured, _, current ->
                        assertSame(key, captured); assertTrue(current()); accepted += selection to action
                    })
            }
        }
        try { run(MutableHost(accepted,
            heartbeat = { feedback.value = feedback.value.copy(buffering = !feedback.value.buffering) },
            navigate = { initialId.value = it }, back = { checkNotNull(dispatcher).onBackPressed() },
            advanceSourceEpoch = { sourceEpoch.value++ })) }
        finally { compose.runOnIdle { shown.value = false }; compose.waitForIdle() }
        assertEquals(0, fixture.preparations.get())
        assertEquals(0, fixture.regenerationRequests.get())
    }
    private class MutableHost(val accepted: MutableList<Pair<LibraryPlaybackSelection, PlaybackListAction>>,
        val heartbeat: () -> Unit, val navigate: (String?) -> Unit, val back: () -> Unit,
        val advanceSourceEpoch: () -> Unit)

    @Test fun indexAppendsAtVisibleFrontierConsumesRawOffsetsDeduplicatesAndStopsAtEnd() {
        Fixture(Source.INDEX).use { fixture ->
            fixture.overlapIndex.set(true)
            host(fixture) { host ->
                await { visible("Playlist 0") }
                compose.onNodeWithText("Refresh").assertDoesNotExist()
                assertEquals(listOf(0), fixture.requests()) // An offscreen frontier cannot load.
                scroll("playlist-38")
                await { fixture.requests().size == 2 && appendSettled() }
                scroll("playlist-78")
                await { fixture.requests().size == 3 && appendSettled() }
                scroll("playlist-84")
                await { visible("Playlist 84") && appendSettled() }
                compose.onNodeWithText("All playlists loaded.").assertDoesNotExist()
                compose.runOnIdle { repeat(8) { host.heartbeat() } }
                compose.waitForIdle()
                assertEquals(listOf(0, 40, 80), fixture.requests())
                compose.onAllNodesWithText("Previous").assertCountEquals(0)
                compose.onAllNodesWithText("Next playlists").assertCountEquals(0)
                scroll("playlist-39") // Duplicate page identity is one stable row, not a key crash.
                compose.onAllNodesWithText("Playlist 39").assertCountEquals(1)
            }
        }
    }

    @Test fun indexBackPreservesAppendedRowsAnchorAndLatchedFailedFrontier() {
        Fixture(Source.INDEX).use { fixture ->
            fixture.failNextAppend.set(true)
            host(fixture) { host ->
                await { visible("Playlist 0") }
                scroll("playlist-38")
                await { visible("Retry") }
                val before = compose.onNodeWithText("Playlist 38").getUnclippedBoundsInRoot()
                compose.runOnIdle { host.navigate("fixture") }
                await { visible("Song 0") }
                compose.runOnIdle { host.back() }
                await { visible("Playlist 38") && visible("Retry") }
                assertEquals(before, compose.onNodeWithText("Playlist 38").getUnclippedBoundsInRoot())
                assertEquals(listOf(0, 40), fixture.requests())
                compose.onNodeWithText("Retry").performScrollTo().performClick()
                await { fixture.requests().size == 3 && appendSettled() && !visible("Retry") }
                scroll("playlist-42")
                compose.onNodeWithText("Playlist 42").assertExists()
            }
        }
    }

    @Test fun lateDerivedSelectionAfterTwentyFiveAutomaticBatchesRetainsExactSignedAuthority() {
        Fixture(Source.DERIVED, count = 1002).use { fixture ->
            host(fixture) { host ->
                await { visible("Song 0") }
                for (offset in 0..960 step 40) {
                    scroll("path-${offset + 38}")
                    await { fixture.requests().size == offset / 40 + 2 && appendSettled() }
                }
                scroll("path-1001")
                await { visible("Song 1001") && appendSettled() }
                compose.onNodeWithText("All songs loaded.").assertDoesNotExist()
                compose.onNodeWithContentDescription("More actions for Song 1001").performClick()
                await { visible("Like song") }
                compose.onNodeWithText("Play now").performScrollTo().performClick()
                compose.waitForIdle()
                val selection = host.accepted.single().first as LibraryPlaybackSelection.Track
                assertEquals("path-1001", selection.track.occurrenceId)
                assertEquals("signed-1001", selection.track.selectionToken)
                assertEquals("fence", selection.context!!.fence)
                assertEquals((0..1000 step 40).toList(), fixture.requests())
            }
        }
    }

    @Test fun indexFailureIsLatchedAndRetryUsesTheSameOffset() = retry(Source.INDEX)
    @Test fun manualFailureRetainsDuplicateOccurrencesAndRetriesExactOffset() = retry(Source.MANUAL)
    @Test fun derivedFailureRetainsPrefixAndRetriesExactSignedCursor() = retry(Source.DERIVED)
    private fun retry(source: Source) {
        Fixture(source).use { fixture ->
            fixture.failNextAppend.set(true)
            host(fixture) { host ->
                await { visible(if (source == Source.INDEX) "Playlist 0" else "Song 0") }
                val prefix = when (source) { Source.INDEX -> "playlist"; Source.MANUAL -> "entry"; Source.DERIVED -> "path" }
                scroll("$prefix-38")
                await { fixture.requests().size == 2 }
                await { visible("Retry") }
                compose.runOnIdle { repeat(12) { host.heartbeat() } }
                compose.waitForIdle()
                assertEquals(listOf(0, 40), fixture.requests())
                compose.onNodeWithText("Retry").performScrollTo().performClick()
                await { fixture.requests().size == 3 && !visible("Retry") && appendSettled() }
                scroll("$prefix-78")
                await { fixture.requests().size == 4 && appendSettled() }
                scroll("$prefix-84")
                await { visible(if (source == Source.INDEX) "Playlist 84" else "Song 84") && appendSettled() }
                compose.onNodeWithText(if (source == Source.INDEX) "All playlists loaded." else "All songs loaded.").assertDoesNotExist()
                assertEquals(listOf(0, 40, 40, 80), fixture.requests())
                scroll("$prefix-0")
                compose.onNodeWithText(if (source == Source.INDEX) "Playlist 0" else "Song 0").assertExists()
                if (source == Source.MANUAL) assertEquals("Each successful entry read is summary-bracketed; failed HTTP has only its before-read", 7, fixture.summaryReads.get())
            }
        }
    }

    @Test fun openCollectionMoreCannotBorrowNewSourceEpochAfterBenignRecomposition() {
        Fixture(Source.DERIVED, count = 1).use { fixture ->
            host(fixture) { host ->
                await { visible("Song 0") }
                compose.onNodeWithContentDescription("More actions for Fixture playlist").performClick()
                compose.onNodeWithText("Queue").assertIsEnabled()
                compose.runOnIdle { host.advanceSourceEpoch(); host.heartbeat() }
                compose.onNodeWithText("Queue").performScrollTo().performClick()
                compose.waitForIdle()
                assertTrue("An open collection selection cannot borrow the latest VM source epoch", host.accepted.isEmpty())
                compose.onNodeWithContentDescription("More actions for Fixture playlist").performClick()
                compose.onNodeWithText("Queue").performScrollTo().performClick()
                compose.waitForIdle()
                val selection = host.accepted.single().first as LibraryPlaybackSelection.Collection
                assertEquals("fixture", selection.context.id)
                assertEquals("fence", selection.context.fence)
                assertEquals(listOf(0), fixture.requests())
            }
        }
    }

    @Test fun heldDerivedGestureCannotBorrowRefreshedSameRevisionAndFence() {
        Fixture(Source.DERIVED, count = 1).use { fixture ->
            host(fixture) { host ->
                await { visible("Song 0") }
                val row = compose.onNodeWithText("Song 0")
                val before = row.getUnclippedBoundsInRoot()
                row.performTouchInput { down(Offset(80f, 24f)); moveTo(Offset(180f, 24f)) }
                assertTrue("The physical-right gesture is held beyond its commit threshold",
                    row.getUnclippedBoundsInRoot().left > before.left)
                // A known input change triggers a read-only resync while the pointer is held.
                // The synthetic server deliberately returns identical revision/fence/occurrence values.
                compose.runOnIdle { fixture.client.notifyPersonalChange(PersonalCollection.LIKES) }
                await {
                    fixture.requests() == listOf(0, 0) &&
                        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
                            .fetchSemanticsNodes().isEmpty()
                }
                row.performTouchInput { up() }
                compose.waitForIdle()
                assertTrue("A pre-refresh gesture cannot acquire the refreshed source authority", host.accepted.isEmpty())
                row.performTouchInput { swipe(Offset(80f, 24f), Offset(180f, 24f)) }
                compose.waitForIdle()
                assertEquals("A new deliberate gesture on the refreshed occurrence still works", 1, host.accepted.size)
                val selection = host.accepted.single().first as LibraryPlaybackSelection.Track
                assertEquals(PlaybackListAction.QUEUE_HIGH, host.accepted.single().second)
                assertEquals("path-0", selection.track.occurrenceId)
                assertEquals("signed-0", selection.track.selectionToken)
                assertEquals("fence", selection.context!!.fence)
                assertEquals(listOf(0, 0), fixture.requests())
            }
        }
    }

    @Test fun manualOpenMoreAndAnchorSurviveUnchangedAppendAndKeepExactEntry() = heldMore(Source.MANUAL)
    @Test fun derivedOpenMoreAndAnchorSurviveUnchangedAppendAndKeepSignedOccurrence() = heldMore(Source.DERIVED)
    private fun heldMore(source: Source) {
        Fixture(source).use { fixture ->
            fixture.holdAppend.set(true)
            host(fixture) { host ->
                await { visible("Song 0") }
                scroll(if (source == Source.MANUAL) "entry-38" else "path-38")
                await { fixture.enteredAppend.count == 0L }
                val before = compose.onNodeWithText("Song 38").getUnclippedBoundsInRoot()
                compose.onNodeWithContentDescription("More actions for Song 38").performClick()
                await { visible("Like song") }
                fixture.releaseAppend.countDown()
                // Manual validation includes the summary AFTER the held entries response.
                await { fixture.requests().size == 2 && appendSettled() && (source == Source.DERIVED || fixture.summaryReads.get() >= 4) }
                compose.waitForIdle()
                compose.onNodeWithText("Queue").assertIsEnabled().performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(before, compose.onNodeWithText("Song 38").getUnclippedBoundsInRoot())
                val selection = host.accepted.single().first as LibraryPlaybackSelection.Track
                assertEquals(PlaybackListAction.QUEUE_HIGH, host.accepted.single().second)
                assertEquals("same", selection.track.id)
                if (source == Source.MANUAL) assertEquals("entry-38", selection.track.entryId)
                else {
                    assertEquals("path-38", selection.track.occurrenceId)
                    assertEquals("signed-38", selection.track.selectionToken)
                    assertEquals("fence", selection.context!!.fence)
                }
                scroll(if (source == Source.MANUAL) "entry-42" else "path-42")
                compose.onNodeWithText("Song 42").assertExists() // Same track ID, distinct retained occurrence.
                assertEquals(listOf(0, 40), fixture.requests())
            }
        }
    }

    @Test fun prematureDerivedTerminalCannotPublishPartialRowsAsAllLoaded() {
        Fixture(Source.DERIVED).use { fixture ->
            host(fixture) {
                await { visible("Song 0") }
                fixture.prematureEnd.set(true)
                scroll("path-38")
                await { fixture.requests().size == 2 && appendSettled() }
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                await { visible("Playlist changed. Reload to continue.") }
                compose.onNodeWithText("Song 0").assertExists()
                compose.onAllNodesWithText("All songs loaded.").assertCountEquals(0)
                compose.onAllNodesWithText("Retry").assertCountEquals(0)
                assertEquals(listOf(0, 40), fixture.requests())
            }
        }
    }

    @Test fun changedDerivedFenceRetainsOldRowsReadOnlyUntilExplicitRefresh() = changed(Source.DERIVED)
    @Test fun manualSummaryRevisionChangedAfterReadCannotPublishMixedSnapshot() = changed(Source.MANUAL)
    private fun changed(source: Source) {
        Fixture(source).use { fixture ->
            fixture.changedFence.set(true)
            fixture.changeSummaryAfterAppend.set(true)
            host(fixture) { host ->
                await { visible("Song 0") }
                scroll(if (source == Source.MANUAL) "entry-38" else "path-38")
                await { fixture.requests().size == 2 && appendSettled() }
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                await { visible("Playlist changed. Reload to continue.") }
                compose.onNodeWithText("Retry loading playlist").assertExists()
                scroll(if (source == Source.MANUAL) "entry-0" else "path-0")
                compose.onNodeWithText("Song 0").assertExists()
                compose.onNodeWithContentDescription("More actions for Song 0").performClick()
                await { visible("Like song") }
                compose.onNodeWithText("Queue").assertIsNotEnabled()
                compose.runOnIdle { repeat(8) { host.heartbeat() } }
                assertTrue(host.accepted.isEmpty())
                assertEquals(listOf(0, 40), fixture.requests())
            }
        }
    }
}
