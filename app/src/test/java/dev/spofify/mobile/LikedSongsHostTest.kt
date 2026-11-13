package dev.spofify.mobile

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.*
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Likes -> shared visible demand -> VM -> HTTP and row-action boundaries.
 * Touch/semantics development regressions only; not installed-native rendering acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class LikedSongsHostTest {
    @get:Rule val compose = createComposeRule()

    private class MemoryStore : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class MemoryLoginStore : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Gate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold(response: MockResponse): MockResponse {
            started.countDown()
            return if (release.await(15, TimeUnit.SECONDS)) response else MockResponse().setResponseCode(503)
        }
    }
    private data class Pending(
        val selection: LibraryPlaybackSelection, val action: PlaybackListAction,
        val key: PlaybackInteractionKey?, val binding: dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding?,
        val current: () -> Boolean, val complete: (PlaybackActionResult) -> Unit,
    )
    private class Controls : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState(interactionKey = PlaybackInteractionKey(),
            queueIntentBinding = dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding("phone", "receiver", 7, 1, 1), canQueueIntent = true))
        val pending = mutableListOf<Pending>()
        override fun queueIntent(selection: LibraryPlaybackSelection, client: SpofifyClient, action: PlaybackListAction,
            expectedKey: PlaybackInteractionKey?, expectedBinding: dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding?, shuffle: Boolean?, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            pending += Pending(selection, action, expectedKey, expectedBinding, isCurrent, onResult)
        }

        override fun connect() = Unit
        override fun release() = Unit
        override fun toggle() = error("Browsing must not toggle playback")
        override fun next() = Unit
        override fun previous() = Unit
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() { PlaybackRequests.invalidate() }
    }
    private data class Anchor(val title: String, val bounds: Rect)

    private val web = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val offsets = CopyOnWriteArrayList<Int>()
    private val gates = mutableListOf<Gate>()
    private val store = ViewModelStore()
    private val owner = Owner()
    private val controls = Controls()
    private val shown = mutableStateOf(true)
    private lateinit var graph: AppGraph
    private lateinit var client: SpofifyClient
    private lateinit var model: LibraryViewModel
    private var backDispatcher: OnBackPressedDispatcher? = null
    private var mounted = false
    // Assigned before any Likes read; attempt is counted per exact backend offset.
    private var likesReply: (Int, Int) -> MockResponse = { offset, _ -> page(offset, 80, offset until minOf(offset + 40, 80)) }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun title(id: Int) = "Song ${id.toString().padStart(3, '0')}"
    private fun track(id: Int) = """{"kind":"track","id":"song-$id","name":"${title(id)}","hasAudio":true}"""
    private fun page(offset: Int, total: Int, ids: Iterable<Int>) = json("""{"items":[${ids.joinToString(",") { """{"track":${track(it)}}""" }}],"total":$total,"offset":$offset,"limit":40}""")
    private fun gate() = Gate().also { gates += it }

    @Before fun setUp() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                return when (val path = url.encodedPath) {
                    "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"csrf"}""")
                        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                    "/api/me/likes" -> {
                        val offset = url.queryParameter("offset")?.toInt() ?: 0
                        offsets += offset
                        likesReply(offset, offsets.count { it == offset })
                    }
                    "/api/me/likes/status" -> json("""{"items":[${(0..120).joinToString(",") { """{"trackId":"song-$it","liked":true}""" }}]}""")
                    "/api/library/revision" -> json("""{"revision":1}""")
                    else -> when {
                        // Artwork uses the same authenticated client/dispatcher as list reads.
                        // Missing synthetic art must complete, not crash a server worker and
                        // occupy every per-origin OkHttp slot until its read timeout.
                        path.endsWith("/artwork") -> MockResponse().setResponseCode(404)
                        path.matches(Regex("/api/library/tracks/song-[0-9]+")) -> {
                            val id = path.substringAfterLast("song-").toInt()
                            json("""{"item":${track(id)},"revision":1}""")
                        }
                        else -> json("""{"items":[],"total":0,"offset":0,"limit":40,"revision":1}""")
                    }
                }
            }
        }
        web.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        graph = AppGraph(app, MemoryStore(), MemoryLoginStore())
        client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        runBlocking { client.login("listener", "synthetic-password") }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model = LibraryViewModel(app, graph, controls)
            store.put("subject", model)
        }
        await { !model.state.value.restoring && model.state.value.session != null }
        // Establish the real personal-change owner without mounting/fetching Home's shelves.
        compose.runOnIdle { model.homeHolder() }
    }

    private fun mountLikes() {
        compose.runOnIdle { model.open(Page.Likes) }
        await { !model.state.value.loading && model.state.value.items.isNotEmpty() }
        mounted = true
        compose.setContent {
            val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            SideEffect { backDispatcher = dispatcher }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    if (shown.value) SpofifyApp(model)
                }
            }
        }
        compose.onNodeWithTag("liked-songs-list").assertIsDisplayed()
    }

    @After fun tearDown() {
        // Unblock our own HTTP dispatchers even after a failed assertion, before server shutdown.
        gates.forEach { it.release.countDown() }
        try {
            if (mounted) {
                compose.runOnIdle { shown.value = false }
                compose.waitForIdle()
            }
            compose.runOnIdle { store.clear(); if (::graph.isInitialized) graph.clear() }
        } finally { web.shutdown() }
    }

    private fun await(condition: () -> Boolean) {
        try {
            compose.waitUntil(5_000) { compose.runOnIdle(condition) }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Timed out awaiting real Likes host: ${diagnostics()}", failure)
        }
    }
    private fun list() = compose.onNodeWithTag("liked-songs-list")
    private fun row(title: String) = compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
    private fun openDetailsForBackCoverage(title: String) {
        // These cases exercise retained browse state across navigation/Back, not the More
        // menu. Enter details explicitly now that a song-body tap means Play now.
        compose.runOnIdle { model.open(Page.Detail(model.state.value.items.single { it.name == title })) }
        await { model.state.value.page is Page.Detail }
    }
    private fun rowBounds(title: String): Rect {
        val bounds = row(title).getUnclippedBoundsInRoot()
        return with(compose.density) {
            Rect(bounds.left.toPx(), bounds.top.toPx(), bounds.right.toPx(), bounds.bottom.toPx())
        }
    }
    private fun visibleRows(): List<Anchor> {
        val viewport = list().fetchSemanticsNode().boundsInRoot
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].first().text }
            .map { Anchor(it, rowBounds(it)) }
            .filter { it.bounds.top < viewport.bottom && it.bounds.bottom > viewport.top }
    }
    private fun diagnostics(): String {
        // Synthetic paths/public state only; never cookies, credentials or request bodies.
        val state = if (::model.isInitialized) compose.runOnIdle {
            model.state.value.let { "page=${it.page}, loaded=${it.items.size}, offset=${it.offset}/${it.total}, " +
                "loading=${it.loading}, error=${it.error}, stale=${it.likesStale}, end=${it.likesEnded}, generation=${it.likesGeneration}" }
        } else "model not initialized"
        val viewport = if (mounted) runCatching {
            val scroll = list().fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            "scroll=${scroll?.value?.invoke()}/${scroll?.maxValue?.invoke()}, visible=${visibleRows()}"
        }.getOrElse { "Likes viewport unavailable: ${it.message}" } else "host not mounted"
        return "$state; $viewport; lifecycle=${owner.lifecycle.currentState}; offsets=${offsets.toList()}; " +
            "http running=${if (::client.isInitialized) client.httpClient.dispatcher.runningCallsCount() else 0}; " +
            "http queued=${if (::client.isInitialized) client.httpClient.dispatcher.queuedCallsCount() else 0}; " +
            "recent requests=${requests.takeLast(12).map { "${it.method} ${it.requestUrl?.encodedPath}" }}"
    }
    private fun touchUntil(condition: () -> Boolean) {
        val start = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        // Slow physical swipes, not calling VM.more or scrolling directly to a synthetic footer.
        repeat(50) {
            if (compose.runOnIdle(condition)) return
            list().performTouchInput {
                swipe(start = center.copy(y = height * .8f), end = center.copy(y = height * .35f), durationMillis = 600)
            }
            compose.waitForIdle()
            if (compose.runOnIdle(condition)) return
            val frontier = compose.runOnIdle { model.state.value.items.takeLast(3).map { it.name } }
            if (visibleRows().any { it.title in frontier }) {
                // Compose idleness does not wait for real OkHttp IO. Once touch has exposed
                // the actual row frontier, hold that viewport and await its bounded read.
                try { await(condition) } catch (failure: AssertionError) {
                    throw AssertionError("Touch exposed the real frontier but demand did not complete; startScroll=$start; ${diagnostics()}", failure)
                }
                return
            }
        }
        fail("Touch scrolling did not reach the loaded frontier; startScroll=$start; ${diagnostics()}")
    }
    private fun anchor(): Anchor {
        val viewport = list().fetchSemanticsNode().boundsInRoot
        // boundsInRoot is clipped: a partly hidden row can falsely appear to start at 0.
        // Select using full layout bounds so only a genuinely complete row is the witness.
        return visibleRows().filter { it.bounds.top >= viewport.top && it.bounds.bottom <= viewport.bottom }
            .minByOrNull { it.bounds.top } ?: error("No complete real music row in viewport; ${diagnostics()}")
    }
    private fun assertAnchor(expected: Anchor, phase: String = "append/Back") {
        val actual = rowBounds(expected.title)
        assertEquals("$phase retained ${expected.title}'s exact scroll position; expected=$expected; ${diagnostics()}",
            expected.bounds.top, actual.top, 1f)
        assertEquals("$phase retained row's bottom; expected=$expected; ${diagnostics()}", expected.bounds.bottom, actual.bottom, 1f)
    }
    private fun systemBack() {
        compose.runOnIdle { checkNotNull(backDispatcher).onBackPressed() }
        compose.waitForIdle()
    }
    private fun progressFrames(count: Int = 12) {
        repeat(count) { frame ->
            compose.runOnIdle {
                controls.state.value = controls.state.value.copy(positionMs = frame * 1_000L,
                    isBuffering = frame % 2 == 0, transportInteractionKey = PlaybackInteractionKey())
            }
            compose.waitForIdle()
        }
    }
    private fun assertBrowseOnly() {
        val pending = compose.runOnIdle { controls.pending.size }
        assertEquals("Browsing dispatched music; ${diagnostics()}", 0, pending)
        val forbidden = requests.filter { request ->
            val path = request.requestUrl!!.encodedPath
            val readOnlyStatus = path == "/api/me/likes/status" && request.method == "POST"
            path.endsWith("/playback") || path.startsWith("/api/me/player") ||
                request.method != "GET" && !(path == "/api/auth/login" && request.method == "POST") && !readOnlyStatus
        }
        assertTrue("Scrolling/retry must not prepare, acquire, play or mutate; " +
            "unexpected=${forbidden.map { "${it.method} ${it.requestUrl?.encodedPath}" }}; ${diagnostics()}", forbidden.isEmpty())
    }
    private fun assertRows(ids: Iterable<Int>) {
        val actual = compose.runOnIdle { model.state.value.items.map { it.id } }
        assertEquals("Retained row order/identity; ${diagnostics()}", ids.map { "song-$it" }, actual)
    }

    @Test fun songBodyUsesRenderedPlayNowBindingAndLikesContextWithoutNavigationOrPopup() {
        mountLikes()
        val retained = anchor()
        val rendered = compose.runOnIdle { controls.state.value }
        row(retained.title).performClick()
        await { controls.pending.size == 1 }
        compose.runOnIdle {
            val pending = controls.pending.single()
            assertEquals(PlaybackListAction.PLAY_NOW, pending.action)
            assertSame(rendered.interactionKey, pending.key)
            assertEquals(rendered.queueIntentBinding, pending.binding)
            val selected = pending.selection as LibraryPlaybackSelection.Track
            assertEquals("song-${retained.title.substringAfter("Song ").toInt()}", selected.track.id)
            assertEquals(LibraryPlaybackContext("likes", "liked-songs", "Liked songs"), selected.context)
            assertTrue(pending.current())
            assertEquals(Page.Likes, model.state.value.page)
            pending.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING, 1))
        }
        await { !model.state.value.preparing }
        compose.runOnIdle {
            assertEquals(Page.Likes, model.state.value.page)
            assertNull(model.state.value.feedback)
            assertNull(model.state.value.notice)
        }
    }

    @Test fun actualTouchFrontierIsSingleFlightAndOffscreenAndTrueEndDoNotFetch() {
        val append = gate()
        likesReply = { offset, _ -> if (offset == 40) append.hold(page(40, 80, 40 until 80)) else page(offset, 80, 0 until 40) }
        mountLikes()
        progressFrames()
        assertEquals("Offscreen frontier must not preload", listOf(0), offsets.toList())
        assertRows(0 until 40)
        touchUntil { append.started.count == 0L }
        val retained = anchor()
        progressFrames(20)
        compose.runOnIdle { assertTrue(model.state.value.loading) }
        assertEquals("One reserved request despite visible demand/progress storm", listOf(0, 40), offsets.toList())
        assertRows(0 until 40)
        append.release.countDown()
        await { model.state.value.offset == 80 && !model.state.value.loading }
        assertRows(0 until 80)
        assertAnchor(retained)
        list().performScrollToNode(hasText(title(79)))
        row(title(79)).assertIsDisplayed()
        progressFrames(20)
        compose.runOnIdle { assertTrue(model.state.value.likesEnded) }
        assertEquals(listOf(0, 40), offsets.toList())
        compose.onNodeWithText("Show more", substring = true).assertDoesNotExist()
        assertBrowseOnly()
    }

    @Test fun overlappingSongIdsDeduplicateButConsumeRawPositionsAndKeepBackScrollAnchor() {
        val append = gate()
        likesReply = { offset, _ -> when (offset) {
            0 -> page(0, 120, 0 until 40)
            40 -> append.hold(page(40, 120, listOf(38, 39) + (40 until 78)))
            80 -> page(80, 120, 78 until 118)
            else -> MockResponse().setResponseCode(400)
        } }
        mountLikes()
        touchUntil { append.started.count == 0L }
        val retained = anchor()
        append.release.countDown()
        await { model.state.value.offset == 80 && !model.state.value.loading }
        assertRows(0 until 78)
        assertAnchor(retained)
        openDetailsForBackCoverage(retained.title)
        systemBack()
        list().assertIsDisplayed()
        assertAnchor(retained)
        assertRows(0 until 78)
        assertEquals("Back restores cached prefix, not offset zero", listOf(0, 40), offsets.toList())
        touchUntil { model.state.value.likesEnded }
        assertRows(0 until 118)
        compose.runOnIdle { assertEquals(120, model.state.value.offset) }
        assertEquals("Never use the deduplicated display count 78 as a backend offset", listOf(0, 40, 80), offsets.toList())
        assertBrowseOnly()
    }

    @Test fun changedMetadataOverlapRetainsPrefixRequiresRefreshAndRetiresHeldQueueAction() {
        val append = gate()
        val changed = track(38).replace(title(38), "Changed server title")
        likesReply = { offset, _ -> when (offset) {
            0 -> page(0, 80, 0 until 40)
            // Same total and valid raw positions; only a repeated song's payload changed.
            40 -> append.hold(json("""{"items":[{"track":$changed},${(39 until 78).joinToString(",") { """{"track":${track(it)}}""" }}],"total":80,"offset":40,"limit":40}"""))
            else -> MockResponse().setResponseCode(400)
        } }
        mountLikes()
        val prefix = compose.runOnIdle { model.state.value.items.toList() }
        val source = compose.runOnIdle { model.state.value.likesGeneration }
        touchUntil { append.started.count == 0L }
        val retained = anchor()
        compose.onNodeWithContentDescription("More actions for ${retained.title}").performClick()
        compose.onNodeWithText("Queue Low").performScrollTo().performClick()
        compose.onNodeWithTag("music-actions").assertDoesNotExist()
        await { controls.pending.size == 1 }
        val pending = compose.runOnIdle { controls.pending.single() }
        compose.runOnIdle {
            assertTrue("Unchanged prefix action is valid while the read is held", pending.current())
            val selected = pending.selection as LibraryPlaybackSelection.Track
            assertEquals("song-${retained.title.substringAfter("Song ").toInt()}", selected.track.id)
            assertEquals(PlaybackListAction.QUEUE_LOW, pending.action)
        }
        // Hold a second, real physical-right gesture across the detected source change.
        // Its pre-stale movement must actually engage the row, not merely inject an up.
        row(retained.title).performTouchInput {
            down(center.copy(x = width * .3f)); moveTo(center.copy(x = width * .6f))
        }
        assertTrue("Real row translated right before source invalidation; ${diagnostics()}",
            rowBounds(retained.title).left > retained.bounds.left)
        compose.runOnIdle { assertEquals("A held pointer has not committed", 1, controls.pending.size) }
        append.release.countDown()
        await { model.state.value.likesStale && !model.state.value.loading }
        row(retained.title).performTouchInput { moveTo(center.copy(x = width * .8f)); up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Releasing an old-source gesture must not dispatch", 1, controls.pending.size) }
        assertAnchor(retained, "Detected changed overlap")
        assertRows(0 until 40)
        compose.runOnIdle {
            val state = model.state.value
            assertEquals("Never publish the changed overlap or its new suffix", prefix, state.items)
            assertEquals(40, state.offset)
            assertNotNull(state.error)
            assertFalse("Changed data is not a successful end", state.likesEnded)
            assertTrue("Detected source change advances action authority", state.likesGeneration > source)
            assertFalse("Held prefix action cannot borrow the changed source", pending.current())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING, 1))
        }
        await { !model.state.value.preparing || controls.pending.size > 1 }
        progressFrames()
        compose.runOnIdle {
            assertEquals("The stale held gesture must not be admitted behind the held queue action", 1, controls.pending.size)
            assertFalse(model.state.value.preparing)
            assertNull("Late receipt from the old source cannot claim Playing", model.state.value.notice)
        }
        assertEquals("No retry, reset or skipped offset after detected change; ${diagnostics()}", listOf(0, 40), offsets.toList())

        // Expose the stale footer deliberately: it must explain failure, not offer batch Retry.
        val error = compose.runOnIdle { checkNotNull(model.state.value.error) }
        list().performScrollToNode(hasText(error))
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText("Retry liked songs").assertDoesNotExist()
        progressFrames()
        list().performScrollToNode(hasText("Refresh liked songs"))
        compose.onNodeWithText("Refresh liked songs").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertIsEnabled()
        assertEquals("Visible stale frontier/recomposition cannot restart reads", listOf(0, 40), offsets.toList())
        compose.onNodeWithText("Refresh liked songs").performClick()
        await { !model.state.value.loading && !model.state.value.likesStale && model.state.value.error == null }
        assertRows(0 until 40)
        assertEquals("Only explicit Refresh establishes a new coherent prefix", listOf(0, 40, 0), offsets.toList())
        compose.runOnIdle {
            assertFalse("Refreshing cannot resurrect the retired queue action", pending.current())
            assertEquals(1, controls.pending.size)
            assertFalse(controls.state.value.isPlaying)
        }
        assertTrue("Receiver preparation never runs on the scrolling controller", requests.none {
            it.requestUrl!!.encodedPath.endsWith("/playback") || it.requestUrl!!.encodedPath.startsWith("/api/me/player")
        })
    }

    @Test fun failedAppendStaysLatchedAcrossVisibleDemandAndDetailBackUntilExactBatchRetry() {
        val append = gate()
        val retry = gate()
        likesReply = { offset, attempt -> when {
            offset == 0 -> page(0, 80, 0 until 40)
            offset == 40 && attempt == 1 -> append.hold(MockResponse().setResponseCode(503))
            offset == 40 -> retry.hold(page(40, 80, 40 until 80))
            else -> MockResponse().setResponseCode(400)
        } }
        mountLikes()
        touchUntil { append.started.count == 0L }
        val retained = anchor()
        append.release.countDown()
        await { model.state.value.error != null && !model.state.value.loading }
        assertRows(0 until 40)
        assertAnchor(retained)
        progressFrames()
        assertEquals(listOf(0, 40), offsets.toList())
        openDetailsForBackCoverage(retained.title)
        systemBack()
        assertAnchor(retained)
        assertRows(0 until 40)
        progressFrames()
        compose.runOnIdle { assertNotNull(model.state.value.error); assertEquals(40, model.state.value.offset) }
        assertEquals("Returning to the failed visible frontier must not auto-retry", listOf(0, 40), offsets.toList())
        list().performScrollToNode(hasText("Retry liked songs"))
        compose.onNodeWithText("Retry liked songs").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        // Capture AFTER deliberate footer scrolling, before Retry changes any geometry.
        val retryAnchor = anchor()
        compose.onNodeWithText("Retry liked songs").performClick()
        await { retry.started.count == 0L && model.state.value.loading }
        // Isolate loading-footer shrink/clamping from the later successful append. Both
        // phases must preserve the same pre-click full-row witness, not a resampled anchor.
        assertAnchor(retryAnchor, "Retry loading before HTTP response")
        progressFrames(4)
        assertAnchor(retryAnchor, "Held Retry loading before HTTP response")
        assertRows(0 until 40)
        assertEquals(listOf(0, 40, 40), offsets.toList())
        retry.release.countDown()
        await { model.state.value.offset == 80 && !model.state.value.loading }
        assertAnchor(retryAnchor, "Retry append after HTTP response")
        assertRows(0 until 80)
        compose.runOnIdle { assertNull(model.state.value.error); assertTrue(model.state.value.likesEnded) }
        progressFrames()
        assertEquals("One deliberate Retry of the exact failed offset", listOf(0, 40, 40), offsets.toList())
        assertBrowseOnly()
    }

    @Test fun failedOffsetZeroRefreshRestoresItsLatchButExplicitRetryReadsAgain() {
        val retry = gate()
        val retryReturned = CountDownLatch(1)
        val recovery = gate()
        likesReply = { offset, attempt -> when {
            offset != 0 -> MockResponse().setResponseCode(400)
            attempt == 1 -> page(0, 40, 0 until 40)
            attempt == 2 -> MockResponse().setResponseCode(503)
            // The server handler deliberately returns a distinguishable late success even
            // after route exit cancels the client read. It must not replace the old cache.
            attempt == 3 -> retry.hold(page(0, 40, 40 until 80)).also { retryReturned.countDown() }
            attempt == 4 -> recovery.hold(page(0, 40, 0 until 40))
            else -> MockResponse().setResponseCode(400)
        } }
        mountLikes()
        val prefix = compose.runOnIdle { model.state.value.items.toList() }
        val retained = anchor()
        // Exercise a deliberate source refresh with an existing successful cache, not an
        // initial empty failure. Likes currently exposes refresh only for stale recovery.
        compose.runOnIdle { model.refresh() }
        await { model.state.value.error != null && !model.state.value.loading }
        assertRows(0 until 40)
        assertAnchor(retained, "Offset-zero refresh failure")
        val failure = compose.runOnIdle { checkNotNull(model.state.value.error) }
        compose.runOnIdle {
            assertEquals(prefix, model.state.value.items)
            assertEquals(0, model.state.value.offset)
            assertFalse(model.state.value.likesStale)
        }
        progressFrames()
        assertEquals("Refresh failed once at zero; ${diagnostics()}", listOf(0, 0), offsets.toList())
        openDetailsForBackCoverage(retained.title)
        systemBack()
        assertAnchor(retained, "Back restores offset-zero failure")
        assertRows(0 until 40)
        progressFrames()
        compose.runOnIdle { assertNotNull(model.state.value.error); assertEquals(0, model.state.value.offset) }
        assertEquals("Back must restore the failure without a reset or auto-retry", listOf(0, 0), offsets.toList())
        list().performScrollToNode(hasText("Retry liked songs"))
        compose.onNodeWithText("Retry liked songs").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val retryAnchor = anchor()
        compose.onNodeWithText("Retry liked songs").performClick()
        await { retry.started.count == 0L && model.state.value.loading }
        assertEquals("Explicit offset-zero Retry must bypass the retained cache", listOf(0, 0, 0), offsets.toList())
        assertRows(0 until 40)
        assertAnchor(retryAnchor, "Offset-zero Retry held in HTTP")
        openDetailsForBackCoverage(retryAnchor.title)
        retry.release.countDown()
        await { retryReturned.count == 0L }
        // Let the real canceled call drain while the detail route remains current. Do not
        // replace it with a fake callback or treat cancellation as server cooperation.
        await { client.httpClient.dispatcher.runningCallsCount() == 0 && client.httpClient.dispatcher.queuedCallsCount() == 0 }
        compose.runOnIdle { assertTrue(model.state.value.page is Page.Detail) }
        systemBack()
        assertAnchor(retryAnchor, "Back after canceled offset-zero Retry")
        assertRows(0 until 40)
        compose.runOnIdle {
            assertEquals("Late retry success cannot replace the prior cache", prefix, model.state.value.items)
            assertEquals("Canceling Retry must preserve the exact original failure latch", failure, model.state.value.error)
            assertEquals(0, model.state.value.offset)
            assertFalse(model.state.value.loading)
        }
        progressFrames(24)
        assertEquals("Restoration after a canceled Retry must not auto-read zero; ${diagnostics()}",
            listOf(0, 0, 0), offsets.toList())
        list().performScrollToNode(hasText("Retry liked songs"))
        compose.onNodeWithText("Retry liked songs").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val recoveryAnchor = anchor()
        compose.onNodeWithText("Retry liked songs").performClick()
        await { recovery.started.count == 0L && model.state.value.loading }
        assertEquals("A fresh explicit Retry is exactly one additional real zero read", listOf(0, 0, 0, 0), offsets.toList())
        assertRows(0 until 40)
        assertAnchor(recoveryAnchor, "Second offset-zero Retry held in HTTP")
        recovery.release.countDown()
        await { model.state.value.error == null && !model.state.value.loading && model.state.value.offset == 40 }
        assertRows(0 until 40)
        progressFrames()
        compose.runOnIdle { assertTrue(model.state.value.likesEnded) }
        assertEquals("No replay after deliberate recovery", listOf(0, 0, 0, 0), offsets.toList())
        assertBrowseOnly()
    }

    @Test fun shortFirstBatchFillsBoundedlyRatherThanDrainingAStationaryViewport() {
        likesReply = { offset, _ -> page(offset, 7, listOf(offset)) }
        mountLikes()
        await { model.state.value.offset >= 3 && !model.state.value.loading }
        progressFrames(24)
        assertRows(0..2)
        assertEquals("Initial read plus two bounded viewport fills, not whole-library fetch", listOf(0, 1, 2), offsets.toList())
        compose.runOnIdle { assertFalse(model.state.value.likesEnded) }
        val beforeDrag = anchor()
        val viewport = list().fetchSemanticsNode().boundsInRoot
        assertTrue("All three short-batch rows really fit before overscroll; ${diagnostics()}",
            rowBounds(title(2)).bottom <= viewport.bottom)
        // Fit-content overscroll cannot move the anchor, but a real new vertical drag
        // must re-arm bounded demand. Heartbeats alone above deliberately did not do so.
        list().performTouchInput {
            swipe(start = center.copy(y = height * .8f), end = center.copy(y = height * .35f), durationMillis = 600)
        }
        await { model.state.value.offset >= 5 && !model.state.value.loading }
        progressFrames(24)
        assertRows(0..4)
        assertAnchor(beforeDrag, "Fit-content user drag re-arms without jumping")
        assertEquals("One new user drag permits only two further stationary fills; ${diagnostics()}",
            listOf(0, 1, 2, 3, 4), offsets.toList())
        compose.runOnIdle { assertFalse(model.state.value.likesEnded) }
        touchUntil { model.state.value.likesEnded && !model.state.value.loading }
        assertRows(0..6)
        progressFrames(24)
        list().performTouchInput {
            swipe(start = center.copy(y = height * .8f), end = center.copy(y = height * .35f), durationMillis = 600)
        }
        progressFrames()
        assertEquals("True end stops even fresh user drags; ${diagnostics()}", (0..6).toList(), offsets.toList())
        assertBrowseOnly()
    }

    @Test fun heldPhysicalRightRealRowGestureSurvivesUnchangedAppend() {
        val append = gate()
        likesReply = { offset, _ -> if (offset == 40) append.hold(page(40, 80, 40 until 80)) else page(0, 80, 0 until 40) }
        mountLikes()
        touchUntil { append.started.count == 0L }
        val retained = anchor()
        val binding = controls.state.value.queueIntentBinding
        val key = controls.state.value.interactionKey
        row(retained.title).performTouchInput {
            down(center.copy(x = width * .3f)); moveTo(center.copy(x = width * .6f))
        }
        append.release.countDown()
        await { model.state.value.offset == 80 && !model.state.value.loading }
        assertAnchor(retained)
        progressFrames()
        row(retained.title).performTouchInput { moveTo(center.copy(x = width * .8f)); up() }
        await { controls.pending.size == 1 }
        compose.runOnIdle {
            val pending = controls.pending.single()
            assertEquals(PlaybackListAction.QUEUE_HIGH, pending.action)
            assertSame(key, pending.key); assertEquals(binding, pending.binding)
            val selection = pending.selection as LibraryPlaybackSelection.Track
            assertEquals("song-${retained.title.substringAfter("Song ").toInt()}", selection.track.id)
            assertEquals(LibraryPlaybackContext("likes", "liked-songs", "Liked songs"), selection.context)
            assertTrue(pending.current())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.NO_OP))
        }
        assertEquals(listOf(0, 40), offsets.toList())
    }

    @Test fun openRealMoreAndAcceptedQueuedActionSurviveAppendsButUnsampledRouteABAInvalidates() {
        val append = gate()
        likesReply = { offset, _ -> when (offset) {
            0 -> page(0, 120, 0 until 40)
            40 -> append.hold(page(40, 120, 40 until 80))
            80 -> page(80, 120, 80 until 120)
            else -> MockResponse().setResponseCode(400)
        } }
        mountLikes()
        touchUntil { append.started.count == 0L }
        val retained = anchor()
        val key = controls.state.value.interactionKey
        val binding = controls.state.value.queueIntentBinding
        compose.onNodeWithContentDescription("More actions for ${retained.title}").performClick()
        compose.onNodeWithTag("music-actions").assertExists()
        append.release.countDown()
        await { model.state.value.offset == 80 && !model.state.value.loading }
        progressFrames()
        compose.onNodeWithText("Queue High").performScrollTo().performClick()
        compose.onNodeWithTag("music-actions").assertDoesNotExist()
        await { controls.pending.size == 1 }
        val pending = compose.runOnIdle { controls.pending.single() }
        compose.runOnIdle {
            assertTrue("Append cannot expire an already open unchanged row More", pending.current())
            assertSame(key, pending.key); assertEquals(binding, pending.binding)
            val selection = pending.selection as LibraryPlaybackSelection.Track
            assertEquals("song-${retained.title.substringAfter("Song ").toInt()}", selection.track.id)
            assertEquals(LibraryPlaybackContext("likes", "liked-songs", "Liked songs"), selection.context)
            assertNull("Submission is not acceptance", model.state.value.notice)
        }
        touchUntil { model.state.value.offset == 120 && !model.state.value.loading }
        progressFrames()
        compose.runOnIdle {
            assertTrue("Accepted/dismissed action remains current through another append", pending.current())
            // No Compose/collector sample between the two route changes.
            model.switchTab(Tab.SETTINGS); model.switchTab(Tab.HOME)
            assertEquals(Page.Likes, model.state.value.page)
            assertFalse("Returning to Likes must not revive the original action", pending.current())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING, 1))
        }
        await { !model.state.value.preparing }
        compose.runOnIdle { assertNull("Late receipt cannot publish after route ABA", model.state.value.notice) }
        assertRows(0 until 120)
        assertEquals(listOf(0, 40, 80), offsets.toList())
        compose.runOnIdle { assertEquals(1, controls.pending.size); assertFalse(controls.state.value.isPlaying) }
        assertTrue(requests.none { it.requestUrl!!.encodedPath.endsWith("/playback") })
    }
}
