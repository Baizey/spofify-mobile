package dev.spofify.mobile

import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackState
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
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Actual host navigation regression coverage, not evidence of a rendered native software IME. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class SearchNavigationHostTest {
    @get:Rule(order = 0) val touchMode = object : TestWatcher() {
        override fun starting(description: Description) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        }
        override fun finished(description: Description) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    private class MemoryStore : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class MemoryLoginStore : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }
    private class FakePlayback : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        val commands = mutableListOf<String>()
        override fun connect() = Unit
        override fun release() = Unit
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?, shuffle: Boolean, startEntryId: String?) { commands += "play" }
        override fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean) { commands += "enqueue" }
        override fun setContext(context: LibraryItem?) { commands += "context" }
        override fun toggle() { commands += "toggle" }
        override fun next() { commands += "next" }
        override fun previous() { commands += "previous" }
        override fun select(index: Int) { commands += "select" }
        override fun seekTo(positionMs: Long) { commands += "seek" }
        override fun toggleShuffle() { commands += "shuffle" }
        override fun cycleRepeat() { commands += "repeat" }
        override fun stop() { commands += "stop" }
    }
    private class Keyboard : SoftwareKeyboardController {
        var shows = 0
        var hides = 0
        override fun show() { shows++ }
        override fun hide() { hides++ }
    }
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val web = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val playback = FakePlayback()
    private val keyboard = Keyboard()
    private val owner = Owner()
    private val shown = mutableStateOf(true)
    private val revision = mutableStateOf(0)
    private lateinit var graph: AppGraph
    private lateinit var client: SpofifyClient
    private lateinit var model: LibraryViewModel
    private var backDispatcher: OnBackPressedDispatcher? = null
    private var hostView: View? = null
    @Volatile private var loginAccount = "account"
    private var mounted = false
    private val song = LibraryItem("track", "song", "Synthetic song", hasAudio = true)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setUp() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.requestUrl?.encodedPath) {
                    "/api/auth/login" -> json("""{"accountId":"$loginAccount","username":"listener","role":"user","csrfToken":"csrf"}""")
                        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                    "/api/library/revision" -> json("""{"revision":1}""")
                    "/api/library/tracks/song" -> json("""{"item":{"kind":"track","id":"song","name":"Synthetic song","hasAudio":true},"revision":1}""")
                    else -> json("""{"items":[],"total":0,"offset":0,"limit":40,"revision":1}""")
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
            model = LibraryViewModel(app, graph, playback)
            store.put("subject", model)
        }
        compose.waitUntil(5_000) { !model.state.value.restoring && model.state.value.session != null }
    }

    private fun mount() {
        mounted = true
        compose.setContent {
            val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val view = LocalView.current
            SideEffect { backDispatcher = dispatcher; hostView = view }
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalSoftwareKeyboardController provides keyboard) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    key(revision.value) { if (shown.value) SpofifyApp(model) }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun tearDown() {
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

    private fun tap(tab: String) { compose.onNodeWithTag("nav-$tab").performClick() }
    private fun field() = compose.onNodeWithTag("search-query")
    private fun assertText(text: String) {
        assertEquals(AnnotatedString(text), field().fetchSemanticsNode().config[SemanticsProperties.EditableText])
    }
    private fun systemBack() {
        compose.runOnIdle { checkNotNull(backDispatcher).onBackPressed() }
        compose.waitForIdle()
    }
    private fun assertFreshSearch(deliveries: Int) {
        field().assertIsFocused()
        assertText("")
        compose.onNodeWithTag("primary-navigation").assertDoesNotExist()
        compose.onNodeWithTag("app-actions").assertDoesNotExist()
        compose.onNodeWithText("Find an artist, album or song", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(Tab.SEARCH, model.state.value.tab)
            assertEquals(Page.Search(), model.state.value.page)
            assertFalse(model.state.value.canGoBack)
            assertNull("Field delivery must consume the transient intent", model.state.value.searchActivationId)
            val navigation = checkNotNull(model.catalogHolder()).state.value
            assertFalse(navigation.canGoBack)
            assertEquals(1, navigation.stack.size)
            assertTrue(navigation.current.emptySearch)
            assertTrue(navigation.current.sections.isEmpty())
            assertEquals(0, navigation.current.scrollIndex)
            assertEquals(0, navigation.current.scrollOffset)
            assertEquals(deliveries, keyboard.shows)
            assertTrue("Navigation must not issue playback commands", playback.commands.isEmpty())
        }
        assertNoBlankCatalogReads()
    }
    private fun assertNoBlankCatalogReads() {
        assertTrue("Empty Search must never issue a global match-all read", requests.none { request ->
            val url = request.requestUrl ?: return@none false
            url.encodedPath == "/api/library" && url.queryParameter("q").isNullOrBlank() &&
                url.queryParameter("artistId") == null && url.queryParameter("albumId") == null
        })
    }

    @Test fun actualHomeAndPlaylistsSearchTapsStartFocusedEmptySearch() {
        mount()
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            model.homeHolder()?.state?.value?.let { it.recent.value != null && it.playlists.value != null } == true
        }
        compose.onNodeWithText("Liked songs").assertIsDisplayed()
        tap("search")
        assertFreshSearch(1)
        systemBack() // IME-hidden editing ends first, not host navigation.
        field().assertIsNotFocused()
        compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
        compose.onNodeWithTag("app-actions").assertIsDisplayed()
        tap("playlists")
        compose.onNodeWithText("Create playlist").assertIsDisplayed()
        tap("search")
        assertFreshSearch(2)
    }

    @Test fun actualReselectResetsAcceptedQueryScrollAndAlreadyEmptyRootWithFreshIdentity() {
        mount()
        tap("search")
        assertFreshSearch(1)
        field().performTextReplacement("accepted query")
        field().performImeAction()
        compose.runOnIdle {
            val holder = checkNotNull(model.catalogHolder())
            assertEquals("accepted query", holder.state.value.current.query)
            holder.rememberScroll(holder.state.value.current.key, 3, 17)
        }
        val oldKey = model.catalogHolder()!!.state.value.current.key
        tap("search")
        assertFreshSearch(2)
        assertTrue(model.catalogHolder()!!.state.value.current.key > oldKey)
        field().performImeAction()
        val emptyKey = model.catalogHolder()!!.state.value.current.key
        tap("search") // Reselect when accepted query is already empty is still an intent.
        assertFreshSearch(3)
        assertTrue(model.catalogHolder()!!.state.value.current.key > emptyKey)
    }

    @Test fun actualTapDiscardsSavedHostDetailAndSearchInnerDetailStacks() {
        mount()
        tap("search")
        field().performTextReplacement("kept until explicit tap")
        field().performImeAction()
        compose.runOnIdle { model.open(Page.Detail(song)) }
        compose.waitForIdle()
        assertTrue(model.state.value.canGoBack)
        tap("home")
        tap("search") // Saved Search host stack previously ended in Page.Detail.
        assertFreshSearch(2)
        field().performImeAction()
        compose.runOnIdle { model.catalogHolder()!!.open(song) }
        compose.onNodeWithText("Synthetic song").assertIsDisplayed()
        assertTrue(model.catalogHolder()!!.state.value.canGoBack)
        tap("search") // Already-selected Search, but its route-owned stack has a detail.
        assertFreshSearch(3)
        compose.onNodeWithText("Synthetic song").assertDoesNotExist()
        field().performImeAction()
        compose.runOnIdle { model.open(Page.Detail(song)) }
        compose.waitForIdle()
        tap("search") // Also reset a currently visible host-owned detail, not only a saved one.
        assertFreshSearch(4)
    }

    @Test fun ordinaryProgrammaticTabsAndBothSystemBackStacksPreserveAcceptedQueryWithoutActivation() {
        mount()
        tap("search")
        field().performTextReplacement("preserved query")
        field().performImeAction()
        compose.runOnIdle { model.catalogHolder()!!.open(song) }
        compose.onNodeWithText("Synthetic song").assertIsDisplayed()
        systemBack()
        assertText("preserved query")
        compose.runOnIdle { model.open(Page.Detail(song)) }
        compose.onNodeWithText("Synthetic song").assertIsDisplayed()
        systemBack()
        assertText("preserved query")
        for (tab in listOf(Tab.HOME, Tab.PLAYLISTS)) {
            compose.runOnIdle { model.switchTab(tab) }
            compose.waitForIdle()
            compose.runOnIdle { model.switchTab(Tab.SEARCH) }
            assertText("preserved query")
            field().assertIsNotFocused()
            compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
        }
        compose.runOnIdle {
            assertEquals("preserved query", model.catalogHolder()!!.state.value.current.query)
            assertFalse(model.state.value.canGoBack)
            assertNull(model.state.value.searchActivationId)
            assertEquals(1, keyboard.shows)
            assertTrue(playback.commands.isEmpty())
        }
        assertNoBlankCatalogReads()
    }

    @Test fun consumedActivationDoesNotReplayOnQueryPublicationLifecycleResumeOrHostRecreation() {
        mount()
        tap("search")
        assertFreshSearch(1)
        field().performTextReplacement("do not replay")
        field().performImeAction()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.runOnIdle { revision.value++ } // Recreate composition, not a claim of native rotation.
        assertText("do not replay")
        field().assertIsNotFocused()
        compose.runOnIdle {
            assertNull(model.state.value.searchActivationId)
            assertEquals(1, keyboard.shows)
            assertTrue(playback.commands.isEmpty())
        }
        assertNoBlankCatalogReads()
    }

    @Test fun pendingActivationIsDiscardedIfHostPausesBeforeInitialFieldDelivery() {
        mount()
        // Publish the same intent as the click and pause in one UI turn, before the field's
        // attachment-frame effect can deliver it. A backgrounded host must not defer its IME.
        compose.runOnIdle {
            model.activateSearch()
            assertNotNull(model.state.value.searchActivationId)
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        field().assertIsNotFocused()
        assertNull(model.state.value.searchActivationId)
        assertEquals(0, keyboard.shows)
        tap("search") // A synthetic click while inactive is not a new active user intent.
        assertNull(model.state.value.searchActivationId)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        field().assertIsNotFocused()
        assertEquals(0, keyboard.shows)
        tap("search") // A genuinely fresh resumed tap still delivers once.
        assertFreshSearch(1)
        field().performTextReplacement("accepted after resume")
        field().performImeAction()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertText("accepted after resume")
        field().assertIsNotFocused()
        compose.runOnIdle { assertNull(model.state.value.searchActivationId); assertEquals(1, keyboard.shows) }
    }

    @Test fun pendingActivationCancelsOnRouteExitOrHostDisposal() {
        mount()
        var pending = 0L
        compose.runOnIdle {
            model.activateSearch()
            pending = checkNotNull(model.state.value.searchActivationId)
            model.switchTab(Tab.HOME)
        }
        compose.waitForIdle()
        assertNull(model.state.value.searchActivationId)
        assertEquals(0, keyboard.shows)
        compose.runOnIdle {
            model.activateSearch()
            assertTrue(checkNotNull(model.state.value.searchActivationId) > pending)
            shown.value = false
        }
        compose.waitForIdle()
        assertNull(model.state.value.searchActivationId)
        compose.runOnIdle { shown.value = true }
        field().assertIsNotFocused()
        assertEquals(0, keyboard.shows)
        tap("search")
        assertFreshSearch(1)
    }

    private val expandPlayer = SemanticsMatcher("Expand Now Playing") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Expand Now Playing"
    }

    private fun collapsePlayer() {
        val dialog = compose.runOnIdle {
            org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog
        }
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("now-playing").assertDoesNotExist()
    }

    @Test fun playerExpansionWhileEditingPreservesSearchAndPlaybackWithoutRefocusOnCollapse() {
        val playing = PlaybackState(item = song, isPlaying = true, playWhenReady = true,
            positionMs = 12_345, durationMs = 60_000, queue = listOf(song), queueIndex = 0,
            currentEntryId = "current-occurrence")
        compose.runOnIdle { playback.state.value = playing }
        mount()
        tap("search")
        field().performTextReplacement("preserve on expansion")
        field().performImeAction() // Finish the debounce before observing settled results.
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                model.catalogHolder()!!.state.value.current.sections.values.let { it.size == 3 && it.all { section -> section.loaded } }
            }
        }
        field().performClick() // Expand while genuinely editing, not after submit cleared focus.
        field().assertIsFocused()
        val before = model.catalogHolder()!!.state.value
        val hides = keyboard.hides
        compose.onNode(expandPlayer).performClick()
        compose.onNodeWithTag("now-playing").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(keyboard.hides > hides)
            assertNull(model.state.value.searchActivationId)
            assertEquals(before, model.catalogHolder()!!.state.value)
            assertEquals(playing, playback.state.value)
            assertTrue(playback.commands.isEmpty())
        }
        collapsePlayer()
        field().assertIsNotFocused()
        assertText("preserve on expansion")
        compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, keyboard.shows)
            assertEquals(before, model.catalogHolder()!!.state.value)
            assertEquals(playing, playback.state.value)
            assertTrue(playback.commands.isEmpty())
        }
        tap("search")
        assertFreshSearch(2)
    }

    @Test fun playerExpansionCancelsPendingActivationBeforeFieldAttachment() {
        compose.runOnIdle { playback.state.value = PlaybackState(item = song, queue = listOf(song)) }
        mount()
        // Retain the actual mini-player click action so intent and expansion occur in the
        // same UI turn, before the activation's field-attachment frame can run.
        val expand = compose.onNode(expandPlayer).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            model.activateSearch()
            assertNotNull(model.state.value.searchActivationId)
            assertTrue(expand())
            assertNull(model.state.value.searchActivationId)
        }
        compose.onNodeWithTag("now-playing").assertIsDisplayed()
        collapsePlayer()
        field().assertIsNotFocused()
        assertText("")
        compose.runOnIdle { assertEquals(0, keyboard.shows); assertTrue(playback.commands.isEmpty()) }
        tap("search")
        assertFreshSearch(1)
    }

    @Test fun mountedPendingActivationCannotDeliverAcrossLogoutAndDifferentAccountSignIn() {
        mount()
        val retiredClient = client
        var retiredActivation = 0L
        compose.runOnIdle {
            model.activateSearch()
            retiredActivation = checkNotNull(model.state.value.searchActivationId)
            model.logout() // Same UI turn: no field attachment/delivery before auth reset.
        }
        compose.waitUntil(5_000) {
            compose.runOnIdle { !model.state.value.busy && model.state.value.session == null }
        }
        compose.onNodeWithTag("search-query").assertDoesNotExist()
        assertNull(model.state.value.searchActivationId)
        assertEquals(0, keyboard.shows)
        loginAccount = "new-account"
        compose.runOnIdle { model.signIn("http://127.0.0.1:${web.port}", "listener", "synthetic-password") }
        compose.waitUntil(5_000) {
            compose.runOnIdle { model.state.value.session?.accountId == "new-account" && !model.state.value.busy }
        }
        client = checkNotNull(graph.client)
        assertNotSame(retiredClient, client)
        compose.runOnIdle { model.switchTab(Tab.SEARCH) }
        field().assertIsNotFocused()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        field().assertIsNotFocused()
        assertEquals(0, keyboard.shows)
        compose.runOnIdle {
            // Queue a fresh current-account intent, then exercise stale callback fences before
            // the field can deliver it. Old identity and old account must both fail closed.
            model.activateSearch()
            val current = checkNotNull(model.state.value.searchActivationId)
            assertTrue(current > retiredActivation)
            model.consumeSearchActivation(retiredClient, "account", current)
            model.cancelSearchActivation(retiredClient, "account")
            model.consumeSearchActivation(client, "account", current)
            model.consumeSearchActivation(client, "new-account", retiredActivation)
            assertEquals(current, model.state.value.searchActivationId)
        }
        field().assertIsFocused()
        assertText("")
        compose.runOnIdle { assertNull(model.state.value.searchActivationId); assertEquals(1, keyboard.shows) }
        field().performImeAction()
        val commandsAfterAuth = playback.commands.toList() // Auth intentionally stops old playback.
        tap("search")
        field().assertIsFocused()
        compose.runOnIdle { assertEquals(2, keyboard.shows); assertEquals(commandsAfterAuth, playback.commands) }
        assertNoBlankCatalogReads()
    }

    @Test fun actualReselectDiscardsUncommittedCompositionWithoutReadingItsDraft() {
        mount()
        tap("search")
        compose.runOnIdle {
            val connection = checkNotNull(checkNotNull(hostView).onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingText("discarded composing draft", 1))
        }
        compose.waitForIdle()
        assertEquals("discarded composing draft", field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("", model.catalogHolder()!!.state.value.current.query)
        systemBack() // No software IME in Robolectric: end editing to restore actual nav targets.
        field().assertIsNotFocused()
        assertEquals("discarded composing draft", field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val beforeKey = model.catalogHolder()!!.state.value.current.key
        tap("search")
        assertFreshSearch(2)
        assertTrue(model.catalogHolder()!!.state.value.current.key > beforeKey)
        compose.runOnIdle { assertTrue(requests.none { it.requestUrl?.queryParameter("q") == "discarded composing draft" }) }
    }

    @Test fun modelConsumptionAndCancellationFenceClientAccountAndObsoleteIdentity() {
        // No field mounted: inspect the actual model's pending one-shot without consuming it.
        compose.runOnIdle {
            model.activateSearch()
            val first = checkNotNull(model.state.value.searchActivationId)
            model.activateSearch()
            val second = checkNotNull(model.state.value.searchActivationId)
            assertTrue(second > first)
            model.consumeSearchActivation(client, "account", first)
            model.consumeSearchActivation(client, "other-account", second)
            model.consumeSearchActivation(client, null, second)
            model.cancelSearchActivation(client, "other-account")
            model.cancelSearchActivation(client, null)
            assertEquals(second, model.state.value.searchActivationId)
            model.consumeSearchActivation(client, "account", second)
            assertNull(model.state.value.searchActivationId)
            model.consumeSearchActivation(client, "account", second)
            model.activateSearch()
        }
        val retired = client
        client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        runBlocking { client.login("listener", "synthetic-password") }
        compose.runOnIdle {
            model.activateSearch()
            val current = checkNotNull(model.state.value.searchActivationId)
            model.consumeSearchActivation(retired, "account", current)
            model.cancelSearchActivation(retired, "account")
            assertEquals(current, model.state.value.searchActivationId)
            model.cancelSearchActivation(client, "account")
            assertNull(model.state.value.searchActivationId)
            assertTrue(playback.commands.isEmpty())
        }
        assertNoBlankCatalogReads()
    }
}
