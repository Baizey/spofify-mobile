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
        val selection: QueueIntentSelection, val action: PlaybackListAction,
        val key: PlaybackInteractionKey?, val binding: QueueIntentBinding?,
        val current: () -> Boolean, val complete: (PlaybackActionResult) -> Unit,
    )
    private class Controls : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState(interactionKey = PlaybackInteractionKey(),
            queueIntentBinding = QueueIntentBinding("phone", "receiver", 7, 1, "synthetic-history"), canQueueIntent = true))
        val pending = mutableListOf<Pending>()
        override fun queueIntent(selection: QueueIntentSelection, client: SpofifyClient, action: PlaybackListAction,
            expectedKey: PlaybackInteractionKey?, expectedBinding: QueueIntentBinding?, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            pending += Pending(selection, action, expectedKey, expectedBinding, isCurrent, onResult)
        }
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?, shuffle: Boolean,
            startEntryId: String?) = error("Browsing must not replace playback")
        override fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean) = error("No legacy enqueue fallback")
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
                    else -> if (path.startsWith("/api/library/tracks/song-")) {
                        val id = path.substringAfterLast("song-").toInt()
                        json("""{"item":${track(id)},"revision":1}""")
                    } else json("""{"items":[],"total":0,"offset":0,"limit":40,"revision":1}""")
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

    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) { compose.runOnIdle(condition) }
    private fun list() = compose.onNodeWithTag("liked-songs-list")
    private fun row(title: String) = compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
    private fun touchUntil(condition: () -> Boolean) {
        // Slow physical swipes, not calling VM.more or scrolling directly to a synthetic footer.
        repeat(35) {
            if (compose.runOnIdle(condition)) return
            list().performTouchInput {
                swipe(start = center.copy(y = height * .8f), end = center.copy(y = height * .35f), durationMillis = 600)
            }
            compose.waitForIdle()
        }
        assertTrue("Touch scrolling did not reach the loaded frontier", compose.runOnIdle(condition))
    }
    private fun anchor(): Anchor {
        val viewport = list().fetchSemanticsNode().boundsInRoot
        val first = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes().filter { it.boundsInRoot.top >= viewport.top && it.boundsInRoot.bottom <= viewport.bottom }
            .minByOrNull { it.boundsInRoot.top } ?: error("No complete real music row in viewport")
        return Anchor(first.config[SemanticsProperties.Text].first().text, first.boundsInRoot)
    }
    private fun assertAnchor(expected: Anchor) {
        val actual = row(expected.title).fetchSemanticsNode().boundsInRoot
        assertEquals("Retained row's exact scroll position", expected.bounds.top, actual.top, 1f)
        assertEquals(expected.bounds.bottom, actual.bottom, 1f)
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
        compose.runOnIdle { assertTrue(controls.pending.isEmpty()) }
        assertTrue("Scrolling/retry must not prepare, acquire or play", requests.none { request ->
            request.requestUrl!!.encodedPath.endsWith("/playback") ||
                request.requestUrl!!.encodedPath.startsWith("/api/me/player") || request.method != "GET" &&
                request.requestUrl!!.encodedPath != "/api/auth/login"
        })
    }
    private fun assertRows(ids: Iterable<Int>) = compose.runOnIdle {
        assertEquals(ids.map { "song-$it" }, model.state.value.items.map { it.id })
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
        row(retained.title).performClick()
        await { model.state.value.page is Page.Detail }
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

    @Test fun failedAppendStaysLatchedAcrossVisibleDemandAndDetailBackUntilExactBatchRetry() {
        val append = gate()
        likesReply = { offset, attempt -> when {
            offset == 0 -> page(0, 80, 0 until 40)
            offset == 40 && attempt == 1 -> append.hold(MockResponse().setResponseCode(503))
            offset == 40 -> page(40, 80, 40 until 80)
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
        row(retained.title).performClick()
        await { model.state.value.page is Page.Detail }
        systemBack()
        assertAnchor(retained)
        assertRows(0 until 40)
        progressFrames()
        compose.runOnIdle { assertNotNull(model.state.value.error); assertEquals(40, model.state.value.offset) }
        assertEquals("Returning to the failed visible frontier must not auto-retry", listOf(0, 40), offsets.toList())
        list().performScrollToNode(hasText("Retry liked songs"))
        val retryAnchor = anchor()
        compose.onNodeWithText("Retry liked songs").assertHeightIsAtLeast(48.dp).performClick()
        await { model.state.value.offset == 80 && !model.state.value.loading }
        assertAnchor(retryAnchor)
        assertRows(0 until 80)
        compose.runOnIdle { assertNull(model.state.value.error); assertTrue(model.state.value.likesEnded) }
        progressFrames()
        assertEquals("One deliberate Retry of the exact failed offset", listOf(0, 40, 40), offsets.toList())
        assertBrowseOnly()
    }

    @Test fun shortFirstBatchFillsBoundedlyRatherThanDrainingAStationaryViewport() {
        likesReply = { offset, _ -> page(offset, 10_000, listOf(offset)) }
        mountLikes()
        await { model.state.value.offset >= 3 && !model.state.value.loading }
        progressFrames(24)
        assertRows(0..2)
        assertEquals("Initial read plus two bounded viewport fills, not whole-library fetch", listOf(0, 1, 2), offsets.toList())
        compose.runOnIdle { assertFalse(model.state.value.likesEnded) }
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
            val selection = pending.selection as QueueIntentSelection.Track
            assertEquals(retained.title, selection.track.getValue("name").jsonPrimitive.content)
            assertEquals(PlaybackContext("likes", "likes", "Liked songs"), selection.context)
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
            val selection = pending.selection as QueueIntentSelection.Track
            assertEquals(retained.title, selection.track.getValue("name").jsonPrimitive.content)
            assertEquals(PlaybackContext("likes", "likes", "Liked songs"), selection.context)
            assertNull("Submission is not acceptance", model.state.value.notice)
        }
        touchUntil { model.state.value.offset == 120 && !model.state.value.loading }
        progressFrames()
        compose.runOnIdle {
            assertTrue("Accepted/dismissed action remains current through another append", pending.current())
            // No Compose/collector sample between the two route changes.
            model.switchTab(Tab.PROFILE); model.switchTab(Tab.HOME)
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
