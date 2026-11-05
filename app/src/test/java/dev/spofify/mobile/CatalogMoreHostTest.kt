package dev.spofify.mobile

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
import java.util.concurrent.atomic.AtomicInteger

/** Actual Source -> host -> More -> VM boundary. Robolectric is not native acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class CatalogMoreHostTest {
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
    private data class Pending(val selection: QueueIntentSelection, val action: PlaybackListAction,
        val key: PlaybackInteractionKey?, val binding: QueueIntentBinding?, val current: () -> Boolean,
        val complete: (PlaybackActionResult) -> Unit)
    private class Controls : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState(interactionKey = PlaybackInteractionKey(),
            queueIntentBinding = QueueIntentBinding("pc", "receiver", 7, 1, "history-A"), canQueueIntent = true))
        val pending = mutableListOf<Pending>()
        override fun queueIntent(selection: QueueIntentSelection, client: SpofifyClient, action: PlaybackListAction,
            expectedKey: PlaybackInteractionKey?, expectedBinding: QueueIntentBinding?, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            pending += Pending(selection, action, expectedKey, expectedBinding, isCurrent, onResult)
        }
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?, shuffle: Boolean,
            startEntryId: String?) = error("More must not use legacy replacement")
        override fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean) = error("No legacy fallback")
        override fun connect() = Unit
        override fun release() = Unit
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() { PlaybackRequests.invalidate() }
    }
    private val web = MockWebServer()
    private val store = ViewModelStore()
    private val owner = Owner()
    private val controls = Controls()
    private val shown = mutableStateOf(true)
    private val playbackReads = AtomicInteger()
    private lateinit var graph: AppGraph
    private lateinit var model: LibraryViewModel
    private val trackJson = """{"kind":"track","id":"song","name":"Synthetic song","albumId":"album","hasAudio":true}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    @Before fun setUp() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path.endsWith("/playback")) playbackReads.incrementAndGet()
                return when (path) {
                    "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"csrf"}""")
                        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                    "/api/auth/logout" -> MockResponse().setResponseCode(204)
                    "/api/library/revision" -> json("""{"revision":1}""")
                    "/api/library/tracks/song" -> json("""{"item":$trackJson,"revision":1}""")
                    "/api/me/recent" -> json("""{"items":[{"track":$trackJson}],"total":1,"offset":0,"limit":40}""")
                    "/api/me/likes/status" -> json("""{"items":[{"trackId":"song","liked":false}]}""")
                    "/api/library" -> if (request.requestUrl!!.queryParameter("kind") == "tracks")
                        json("""{"items":[$trackJson],"total":1,"offset":0,"limit":40,"revision":1}""")
                    else json("""{"items":[],"total":0,"offset":0,"limit":40,"revision":1}""")
                    "/api/library/albums/album" -> json("""{"item":{"kind":"album","id":"album","name":"Synthetic album","coverage":{"inLibrary":2}},"revision":1}""")
                    else -> json("""{"items":[],"total":0,"offset":0,"limit":40,"revision":1}""")
                }
            }
        }
        web.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        graph = AppGraph(app, MemoryStore(), MemoryLoginStore())
        val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        runBlocking { client.login("listener", "synthetic-password") }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model = LibraryViewModel(app, graph, controls); store.put("subject", model)
        }
        await { !model.state.value.restoring && model.state.value.session != null }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    if (shown.value) SpofifyApp(model)
                }
            }
        }
        await { model.homeHolder()?.state?.value?.recent?.value?.isNotEmpty() == true }
    }
    @After fun tearDown() {
        try {
            compose.runOnIdle { shown.value = false }; compose.waitForIdle()
            compose.runOnIdle { store.clear(); if (::graph.isInitialized) graph.clear() }
        } finally { web.shutdown() }
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) { compose.runOnIdle(condition) }
    private fun openMore() {
        compose.onNodeWithContentDescription("More actions for Synthetic song").performClick()
        compose.onNodeWithTag("music-actions").assertExists()
    }
    private fun choose(label: String): Pending {
        val count = controls.pending.size + 1
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithTag("music-actions").assertDoesNotExist()
        await { controls.pending.size == count }
        return compose.runOnIdle { controls.pending.last() }
    }
    @Test fun homeMoreHasFourIndependentIntentsAndNoInlinePlayOrCompositionSubmission() {
        compose.onNodeWithText("Recently played").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Synthetic song").assertDoesNotExist()
        openMore()
        for (label in listOf("Queue Low", "Queue High", "Play next", "Play now"))
            compose.onNodeWithText(label).assertIsEnabled()
        compose.runOnIdle { assertTrue(controls.pending.isEmpty()); assertEquals(0, playbackReads.get()) }
    }
    @Test fun pausedAndPlayingAndBufferingHomeRowsSendExactlyOneRawRowIntent() {
        for (mode in 0..2) {
            compose.runOnIdle { controls.state.value = controls.state.value.copy(isPlaying = mode == 1,
                isBuffering = mode == 2, playWhenReady = mode == 2) }
            for ((label, action) in listOf("Queue Low" to PlaybackListAction.QUEUE_LOW,
                "Queue High" to PlaybackListAction.QUEUE_HIGH, "Play next" to PlaybackListAction.PLAY_NEXT,
                "Play now" to PlaybackListAction.PLAY_NOW)) {
                openMore(); val pending = choose(label)
                compose.runOnIdle {
                    assertEquals(action, pending.action)
                    assertSame(controls.state.value.interactionKey, pending.key)
                    assertEquals(controls.state.value.queueIntentBinding, pending.binding)
                    val row = pending.selection as QueueIntentSelection.Track
                    assertEquals("song", row.track.getValue("id").jsonPrimitive.content)
                    assertNull(row.context)
                    assertTrue(pending.current())
                    pending.complete(PlaybackActionResult(PlaybackActionStatus.NO_OP))
                }
            }
        }
        assertEquals("Receiver, not controller, resolves all contexts", 0, playbackReads.get())
    }
    @Test fun detailMoreRetainsOpeningBindingAndInnerRouteLifetime() {
        compose.runOnIdle { model.open(Page.Detail(LibraryItem("track", "song", "Synthetic song", hasAudio = true))) }
        openMore(); val pending = choose("Queue High")
        val page = model.state.value.page as Page.Detail
        val catalog = checkNotNull(model.catalogHolder(page.item, page.navigationId))
        compose.runOnIdle {
            assertTrue(pending.current())
            catalog.open(LibraryItem("album", "other", "Other")); assertTrue(catalog.back())
            assertFalse("Unsampled route ABA cannot revive More", pending.current())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        }
        await { !model.state.value.preparing }
        compose.runOnIdle { assertNull("Late callback was drained, not merely queued", model.state.value.notice) }
    }
    @Test fun searchAndCollectionDetailsUseActualHostMoreWithoutControllerPlaybackReads() {
        compose.runOnIdle { model.switchTab(Tab.SEARCH); model.catalogHolder()?.commitQuery("Synthetic", immediate = true) }
        compose.waitUntil(5_000) { compose.runOnIdle {
            model.catalogHolder()?.state?.value?.current?.sections?.get("tracks")?.items?.isNotEmpty() == true
        } }
        openMore(); val search = choose("Queue High")
        compose.runOnIdle {
            assertTrue(search.selection is QueueIntentSelection.Track)
            search.complete(PlaybackActionResult(PlaybackActionStatus.NO_OP))
            model.open(Page.Detail(LibraryItem("album", "album", "Synthetic album", coverage = Coverage(inLibrary = 2))))
        }
        compose.onNodeWithContentDescription("More actions for Synthetic album").performClick()
        val collection = choose("Queue Low")
        compose.runOnIdle {
            assertEquals(QueueIntentSelection.Collection(PlaybackContext("album", "album", "Synthetic album")), collection.selection)
            assertEquals(0, playbackReads.get())
            collection.complete(PlaybackActionResult(PlaybackActionStatus.NO_OP))
        }
    }
    @Test fun openMoreDoesNotRetargetReplacementOutput() {
        openMore()
        compose.runOnIdle { controls.state.value = controls.state.value.copy(interactionKey = PlaybackInteractionKey(),
            queueIntentBinding = QueueIntentBinding("new", "newReceiver", 1, null, null)) }
        compose.onNodeWithText("Queue Low").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(controls.pending.isEmpty()) }
    }
    @Test fun acceptedQueueWithUnchangedPausedCurrentNeverClaimsPlaying() {
        openMore(); val pending = choose("Queue Low")
        compose.runOnIdle {
            pending.complete(queueIntentActionResult(QueueIntentResult("queued", 2, 8, "low", "unchanged", null, ""), pending.action))
        }
        compose.onNodeWithText("Queued 2 songs.").assertExists()
        compose.runOnIdle { assertFalse(controls.state.value.isPlaying) }
    }
    @Test fun blockedAndMediaAndSupersededReceiptsKeepPositiveAcceptance() {
        for (playback in listOf("blocked", "media", "superseded")) {
            openMore(); val pending = choose("Play now")
            val receipt = QueueIntentResult(if (playback == "superseded") "queued" else playback, 2, 8,
                "replace", playback, if (playback == "superseded") null else 22, "")
            compose.runOnIdle { pending.complete(queueIntentActionResult(receipt, pending.action)) }
            await { model.state.value.notice?.contains("accepted") == true }
            compose.runOnIdle {
                assertFalse(model.state.value.notice!!.contains("Playing"))
                assertFalse(model.state.value.notice!!.contains("Playback started"))
            }
        }
    }
    @Test fun unsampledInnerRouteABAWhileMoreIsStillOpenCannotDispatchOrRebind() {
        compose.runOnIdle { model.open(Page.Detail(LibraryItem("track", "song", "Synthetic song", hasAudio = true))) }
        openMore()
        val page = model.state.value.page as Page.Detail
        val catalog = checkNotNull(model.catalogHolder(page.item, page.navigationId))
        compose.runOnIdle {
            catalog.open(LibraryItem("album", "other", "Other album"))
            assertTrue(catalog.back()) // No Compose/collector sample between the two events.
        }
        compose.onNodeWithText("Queue High").performScrollTo().performClick()
        compose.onNodeWithTag("music-actions").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("An old open More cannot acquire the returned route incarnation", controls.pending.isEmpty())
            assertNull(model.state.value.notice)
            assertEquals(0, playbackReads.get())
        }
    }
    @Test fun acceptedIntentSurvivesSheetDismissalAndBenignProgressUntilTerminalReceipt() {
        val key = controls.state.value.interactionKey
        val binding = controls.state.value.queueIntentBinding
        openMore(); val pending = choose("Queue High")
        compose.onNodeWithTag("music-actions").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("Dismissing accepted More is presentation, not cancellation", pending.current())
            assertNull("Dispatch is not acceptance", model.state.value.notice)
            controls.state.value = controls.state.value.copy(positionMs = 5_000, isBuffering = true, playWhenReady = true,
                transportInteractionKey = PlaybackInteractionKey())
        }
        compose.runOnIdle {
            assertTrue("Progress/buffering/transport-only key churn cannot retire a held list action", pending.current())
            assertSame(key, pending.key); assertEquals(binding, pending.binding)
            assertNull(model.state.value.notice)
            pending.complete(queueIntentActionResult(QueueIntentResult("queued", 1, 8, "high", "unchanged", null, ""), pending.action))
        }
        compose.onNodeWithText("Queued").assertExists()
        compose.runOnIdle { assertEquals(1, controls.pending.size); assertEquals(0, playbackReads.get()) }
    }
    @Test fun innerRouteNavigationAfterAcceptedDismissalRetiresWorkEvenAfterReturn() {
        compose.runOnIdle { model.open(Page.Detail(LibraryItem("track", "song", "Synthetic song", hasAudio = true))) }
        openMore(); val pending = choose("Queue Low")
        val page = model.state.value.page as Page.Detail
        val catalog = checkNotNull(model.catalogHolder(page.item, page.navigationId))
        compose.runOnIdle { assertTrue(pending.current()); catalog.open(LibraryItem("album", "other", "Other album")) }
        compose.runOnIdle { assertFalse(pending.current()); assertTrue(catalog.back()) }
        compose.runOnIdle {
            assertFalse("Returning to the same nested route cannot revive accepted work", pending.current())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING, 1))
        }
        await { !model.state.value.preparing }
        compose.runOnIdle { assertNull("Late Playing receipt cannot publish after inner navigation", model.state.value.notice) }
    }
    @Test fun outputLossDuringHeldReceiverPreparationCancelsWithoutReplayOrLatePlayingFeedback() {
        openMore(); val pending = choose("Queue High")
        compose.runOnIdle {
            assertTrue(model.state.value.preparing); assertTrue(pending.current())
            // Preparation now belongs to the receiver, so hold its terminal callback rather
            // than inventing a controller playback GET. Output revocation uses the shared fence.
            PlaybackRequests.invalidate()
            controls.state.value = controls.state.value.copy(interactionKey = null, queueIntentBinding = null, canQueueIntent = false)
            assertFalse(pending.current())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING, 1))
        }
        await { !model.state.value.preparing }
        compose.runOnIdle {
            assertEquals("No deferred replay or retargeting", 1, controls.pending.size)
            assertNull(model.state.value.notice)
            assertFalse(controls.state.value.isPlaying)
            assertEquals(0, playbackReads.get())
        }
    }
    @Test fun signOutAfterAcceptedDismissalSuppressesLateSuccessInActualHost() {
        openMore(); val pending = choose("Queue Low")
        compose.runOnIdle { assertTrue(pending.current()); model.logout() }
        await { model.state.value.session == null }
        val signedOutNotice = compose.runOnIdle { model.state.value.notice }
        compose.runOnIdle {
            assertFalse(pending.current())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(signedOutNotice, model.state.value.notice)
            assertNotEquals("Added to queue", model.state.value.notice)
            assertEquals(1, controls.pending.size)
        }
    }
    @Test fun routeRoundTripAndSignOutRetireAcceptedWorkWithoutLateFeedback() {
        openMore(); val pending = choose("Queue High")
        compose.runOnIdle {
            model.switchTab(Tab.SEARCH); model.switchTab(Tab.HOME)
            assertFalse(pending.current())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            assertNull(model.state.value.notice)
        }
        openMore(); val second = choose("Queue Low")
        compose.runOnIdle { model.logout() }
        await { model.state.value.session == null }
        compose.runOnIdle { assertFalse(second.current()); second.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)) }
    }
}
