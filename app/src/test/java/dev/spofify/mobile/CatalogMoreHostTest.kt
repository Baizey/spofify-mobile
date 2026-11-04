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
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackQueue
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.playback.*
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the production host/sheet wiring, not just MusicActionsContent callbacks.
 * Delayed controls model acknowledged dispatch; native rendering/service policy remain separate gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class CatalogMoreHostTest {
    @get:Rule val compose = createComposeRule()

    private class MemoryStore : SessionStore {
        private var session: StoredSession? = null
        override fun read() = session
        override fun write(value: StoredSession?) { session = value }
    }
    private class MemoryLoginStore : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private data class PendingAction(
        val queue: PlaybackQueue,
        val action: PlaybackListAction,
        val key: PlaybackInteractionKey?,
        val startTrackId: String?,
        val isCurrent: () -> Boolean,
        val complete: (PlaybackActionResult) -> Unit,
    )
    private class DelayedPlayback : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState(interactionKey = PlaybackInteractionKey()))
        val pending = mutableListOf<PendingAction>()
        var replacements = 0
        override fun performPreparedAction(queue: PlaybackQueue, client: SpofifyClient,
            action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, startTrackId: String?,
            startEntryId: String?, context: LibraryItem?, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            pending += PendingAction(queue, action, expectedKey, startTrackId, isCurrent, onResult)
        }
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?,
            shuffle: Boolean, startEntryId: String?) { replacements++ }
        override fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean) {
            error("More must use the acknowledged list-action boundary")
        }
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
    private val playback = DelayedPlayback()
    private val shown = mutableStateOf(true)
    private val fetchStarted = CountDownLatch(1)
    private val releaseFetch = CountDownLatch(1)
    @Volatile private var holdFetch = false
    private lateinit var graph: AppGraph
    private lateinit var model: LibraryViewModel
    private var mounted = false
    private val trackJson = """{"kind":"track","id":"song","name":"Synthetic song","albumId":"album","hasAudio":true}"""

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setUp() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"csrf"}""")
                    .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                "/api/auth/logout" -> MockResponse().setResponseCode(204)
                "/api/library/revision" -> json("""{"revision":1}""")
                "/api/library/tracks/song" -> json("""{"item":$trackJson,"revision":1}""")
                "/api/me/recent" -> json("""{"items":[{"track":$trackJson}],"total":1,"offset":0,"limit":40}""")
                "/api/me/likes/status" -> json("""{"items":[{"trackId":"song","liked":false}]}""")
                "/api/library/albums/album/playback" -> {
                    if (holdFetch) {
                        fetchStarted.countDown()
                        check(releaseFetch.await(5, TimeUnit.SECONDS)) { "Synthetic playback gate timed out" }
                    }
                    json("""{"items":[$trackJson],"total":1}""")
                }
                else -> json("""{"items":[],"total":0,"offset":0,"limit":40,"revision":1}""")
            }
        }
        web.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        graph = AppGraph(app, MemoryStore(), MemoryLoginStore())
        val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        runBlocking { client.login("listener", "synthetic-password") }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model = LibraryViewModel(app, graph, playback)
            store.put("subject", model)
        }
        awaitHost { !model.state.value.restoring && model.state.value.session != null }
        mounted = true
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    if (shown.value) SpofifyApp(model)
                }
            }
        }
        awaitHost { model.homeHolder()?.state?.value?.recent?.value?.isNotEmpty() == true }
    }

    @After fun tearDown() {
        releaseFetch.countDown()
        try {
            if (mounted) {
                compose.runOnIdle { shown.value = false }
                compose.waitForIdle()
            }
            compose.runOnIdle { store.clear(); if (::graph.isInitialized) graph.clear() }
        } finally {
            web.shutdown()
        }
    }

    private fun openMore() {
        compose.onNodeWithContentDescription("More actions for Synthetic song").performClick()
        compose.onNodeWithTag("music-actions").assertExists()
    }
    private fun choose(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithTag("music-actions").assertDoesNotExist()
    }
    private fun awaitHost(condition: () -> Boolean) {
        // Network completion resumes viewModelScope on Android's main queue, not the
        // Compose frame clock. Drain that queue on every poll, and inspect lazy holders
        // and the playback fake on their owning thread rather than racing UI callbacks.
        compose.waitUntil(5_000) { compose.runOnIdle(condition) }
    }
    private fun awaitAction(count: Int = 1): PendingAction {
        awaitHost { playback.pending.size == count }
        return compose.runOnIdle { playback.pending.last() }
    }

    @Test fun actualRecentlyPlayedRowsKeepMoreWithoutInlinePlayOrAutoplay() {
        compose.onNodeWithText("Recently played").assertIsDisplayed()
        compose.onNodeWithText("Synthetic song").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for Synthetic song").assertIsEnabled()
        compose.onNodeWithContentDescription("Play Synthetic song").assertDoesNotExist()
        openMore()
        compose.onNodeWithText("Play now").assertIsEnabled()
        compose.runOnIdle {
            assertTrue("Opening Recently played More must not submit playback", playback.pending.isEmpty())
            assertEquals(0, playback.replacements)
        }
    }

    @Test fun idleContextualIntentSurvivesSheetDismissalAndProgressUntilAcknowledged() {
        val captured = playback.state.value.interactionKey
        openMore()
        compose.onNodeWithText("Play now (swipe action)").assertIsEnabled()
        compose.onNodeWithText("Add to queue (swipe action)").assertDoesNotExist()
        choose("Play now (swipe action)")
        val pending = awaitAction()
        compose.runOnIdle {
            assertEquals(PlaybackListAction.CONTEXTUAL, pending.action)
            assertSame(captured, pending.key)
            assertEquals("song", pending.startTrackId)
            assertEquals("song", pending.queue.items.single().id)
            assertTrue("Dismissing the accepting sheet must not retire the intent", pending.isCurrent())
            assertNull("No optimistic Playing/Added feedback", model.state.value.notice)
            assertEquals(0, playback.replacements)
            playback.state.value = playback.state.value.copy(positionMs = 5_000)
        }
        compose.runOnIdle {
            assertTrue("Benign progress must not retire an accepted intent", pending.isCurrent())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.PLAYING))
        }
        compose.onNodeWithText("Playing").assertExists()
    }

    @Test fun activeAndBufferingMoreKeepContextualExplicitAddPlayNextAndPlayNowDistinct() {
        for (buffering in listOf(false, true)) {
            compose.runOnIdle {
                playback.state.value = playback.state.value.copy(isPlaying = !buffering,
                    isBuffering = buffering, playWhenReady = true)
            }
            for ((label, action) in listOf("Add to queue" to PlaybackListAction.ADD_TO_QUEUE,
                "Play next" to PlaybackListAction.PLAY_NEXT,
                "Add to queue (swipe action)" to PlaybackListAction.CONTEXTUAL)) {
                openMore()
                compose.onAllNodesWithText("Add to queue").assertCountEquals(1)
                compose.onAllNodesWithText("Play now").assertCountEquals(1)
                compose.onNodeWithText("Play now (swipe action)").assertDoesNotExist()
                val count = playback.pending.size + 1
                choose(label)
                val pending = awaitAction(count)
                compose.runOnIdle {
                    assertEquals(action, pending.action)
                    assertTrue(pending.isCurrent())
                    if (action == PlaybackListAction.CONTEXTUAL) assertSame(playback.state.value.interactionKey, pending.key)
                    else assertNull("Explicit Add/Play next must not become contextual starts", pending.key)
                    pending.complete(PlaybackActionResult(PlaybackActionStatus.NO_OP))
                }
            }
            openMore()
            val plays = playback.replacements
            choose("Play now")
            awaitHost { playback.replacements == plays + 1 }
        }
    }

    @Test fun routeAwayAndBackRetiresAcceptedIntentEvenThoughItsSheetAlreadyDismissed() {
        openMore()
        choose("Play now (swipe action)")
        val pending = awaitAction()
        compose.runOnIdle { model.switchTab(Tab.SEARCH) }
        compose.runOnIdle { model.switchTab(Tab.HOME) }
        compose.runOnIdle {
            assertFalse("A→B→A must not revive the old route lifetime", pending.isCurrent())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            assertNull(model.state.value.notice)
        }
    }

    @Test fun innerCatalogNavigationAwayAndBackAlsoRetiresAcceptedIntent() {
        compose.runOnIdle { model.open(Page.Detail(LibraryItem("track", "song", "Synthetic song", albumId = "album", hasAudio = true))) }
        openMore()
        choose("Play now (swipe action)")
        val pending = awaitAction()
        val page = model.state.value.page as Page.Detail
        val catalog = checkNotNull(model.catalogHolder(page.item, page.navigationId))
        compose.runOnIdle { catalog.open(LibraryItem("album", "other", "Other album")) }
        compose.runOnIdle { assertFalse("Inner navigation does not change LibraryState.page", pending.isCurrent()) }
        compose.runOnIdle { assertTrue(catalog.back()) }
        compose.runOnIdle {
            assertFalse("Returning to the same inner screen must not revive its retired lifetime", pending.isCurrent())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.STALE))
            assertNull(model.state.value.notice)
        }
    }

    @Test fun unsampledInnerNavigationRoundTripCannotReviveAcceptedIntent() {
        compose.runOnIdle { model.open(Page.Detail(LibraryItem("track", "song", "Synthetic song", albumId = "album", hasAudio = true))) }
        openMore()
        choose("Play now (swipe action)")
        val pending = awaitAction()
        val page = model.state.value.page as Page.Detail
        val catalog = checkNotNull(model.catalogHolder(page.item, page.navigationId))
        compose.runOnIdle {
            val screen = catalog.state.value.current
            catalog.rememberScroll(screen.key, 1, 12)
            assertTrue("Harmless same-route publication must preserve the intent", pending.isCurrent())
            // No recomposition/collector sample between navigation and return.
            catalog.open(LibraryItem("album", "other", "Other album"))
            assertTrue(catalog.back())
            assertFalse("Event-time A→B→A must retire the original intent", pending.isCurrent())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            assertNull("An old completion cannot publish after an unsampled round-trip", model.state.value.notice)
        }
    }

    @Test fun unsampledInnerNavigationRoundTripWhileMoreIsOpenCannotDispatch() {
        compose.runOnIdle { model.open(Page.Detail(LibraryItem("track", "song", "Synthetic song", albumId = "album", hasAudio = true))) }
        openMore()
        val page = model.state.value.page as Page.Detail
        val catalog = checkNotNull(model.catalogHolder(page.item, page.navigationId))
        compose.runOnIdle {
            catalog.open(LibraryItem("album", "other", "Other album"))
            assertTrue(catalog.back())
        }
        choose("Play now (swipe action)")
        compose.runOnIdle {
            assertTrue("More cannot retarget a new event-time route incarnation", playback.pending.isEmpty())
            assertEquals(0, playback.replacements)
        }
    }

    @Test fun outputBindingChangeWhileMoreIsOpenCannotRetargetItsCapturedSelection() {
        openMore()
        compose.runOnIdle {
            playback.state.value = playback.state.value.copy(interactionKey = PlaybackInteractionKey())
        }
        choose("Play now (swipe action)")
        compose.runOnIdle {
            assertTrue("The old captured key must be rejected, not replaced by the current key", playback.pending.isEmpty())
            assertEquals(0, playback.replacements)
        }
    }

    @Test fun unavailableBindingDoesNotOfferAnExecutableContextualAction() {
        compose.runOnIdle { playback.state.value = playback.state.value.copy(interactionKey = null) }
        openMore()
        compose.onNodeWithText("Play now (swipe action)").assertDoesNotExist()
        compose.onNodeWithText("Add to queue (swipe action)").assertDoesNotExist()
        compose.onNodeWithText("Play now").assertIsEnabled()
        compose.onNodeWithText("Play next").assertIsEnabled()
        compose.onNodeWithText("Add to queue").assertIsEnabled()
    }

    @Test fun outputLossDuringPreparationCancelsRatherThanStartingAfterSheetDismissal() {
        holdFetch = true
        openMore()
        choose("Play now (swipe action)")
        // Keep pumping main while waiting for preparation to reach the server gate.
        // Blocking the test thread on the latch can strand a queued main continuation.
        awaitHost { fetchStarted.count == 0L }
        compose.runOnIdle {
            assertTrue("Playback preparation must still be in flight at the synthetic gate", model.state.value.preparing)
            PlaybackRequests.invalidate()
            playback.state.value = playback.state.value.copy(interactionKey = null)
        }
        releaseFetch.countDown()
        awaitHost { !model.state.value.preparing }
        compose.runOnIdle {
            assertTrue(playback.pending.isEmpty())
            assertEquals(0, playback.replacements)
            assertNull(model.state.value.notice)
        }
    }

    @Test fun signOutRetiresAcceptedIntentAndCannotPublishItsLateSuccess() {
        openMore()
        choose("Play now (swipe action)")
        val pending = awaitAction()
        compose.runOnIdle { model.logout() }
        awaitHost { model.state.value.session == null }
        compose.runOnIdle {
            assertFalse(pending.isCurrent())
            pending.complete(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))
            assertNotEquals("Added to queue", model.state.value.notice)
        }
    }
}
