package dev.spofify.mobile

import dev.spofify.mobile.core.LibraryPlaybackSelection
import dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding
import dev.spofify.mobile.playback.PlaybackActionResult
import dev.spofify.mobile.playback.PlaybackActionStatus
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.OnBackCompletedFallback
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.core.app.ApplicationProvider
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** Real app-host semantics/navigation coverage; not rendered native/IME acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class ProfileHostTest {
    @get:Rule val compose = createComposeRule()

    // Same synthetic graph/session setup as RootCopyHostTest, without its unrelated root cases.
    private class MemoryStore : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class MemoryLoginStore : LoginStore {
        private var value: RememberedLogin? = RememberedLogin("https://saved.example.com", "saved-user", "saved-secret")
        override fun read() = value
        override fun write(value: RememberedLogin?) { this.value = value }
    }
    private class FakePlayback : PlaybackControls {
        private val song = LibraryItem("track", "playing", "Playing song", hasAudio = true)
        override val state = MutableStateFlow(PlaybackState(item = song, queue = listOf(song), queueIndex = 0,
            currentEntryId = "playing-occurrence", positionMs = 12_345, durationMs = 60_000,
            isPlaying = true, playWhenReady = true))
        val commands = mutableListOf<String>()
        override fun connect() = Unit
        override fun release() = Unit
        override fun queueIntent(selection: LibraryPlaybackSelection, client: SpofifyClient,
            action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, expectedBinding: LibraryPlaybackActionBinding?,
            shuffle: Boolean?, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            commands += if (action == PlaybackListAction.PLAY_NOW) "play" else "enqueue"; onResult(PlaybackActionResult(PlaybackActionStatus.UNSUPPORTED))
        }
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
        override fun show() { shows++ }
        override fun hide() = Unit
    }

    private class BackInput : NavigationEventInput() {
        var hasEnabledHandlers = false
            private set
        override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
            this.hasEnabledHandlers = hasEnabledHandlers
        }
        fun completeBack() { dispatchOnBackCompleted() }
    }

    private val web = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val playback = FakePlayback()
    private val keyboard = Keyboard()
    private val shown = mutableStateOf(true)
    private val fontScale = mutableStateOf(1f)
    private var rootBacks = 0
    private lateinit var navigationBack: NavigationEventDispatcher
    private val backInput = BackInput()
    private val navigationOwner = object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher get() = navigationBack
    }
    private var mounted = false
    private lateinit var actualLifecycle: androidx.lifecycle.Lifecycle
    private lateinit var graph: AppGraph
    private lateinit var model: LibraryViewModel
    private lateinit var client: SpofifyClient
    @Volatile private var offline = false
    @Volatile private var account = "profile-account"
    @Volatile private var username = "Listener 音楽 · a deliberately long synthetic username for wrapping"

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        compose.waitForIdle()
        condition()
    }
    private fun mount() {
        mounted = true
        compose.setContent {
            actualLifecycle = LocalLifecycleOwner.current.lifecycle
            val density = LocalDensity.current
            // Activity 1.13 BackHandler prefers NavigationEvent ownership over the legacy
            // OnBackPressed local. Provide and drive the same explicit modern dispatcher.
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navigationOwner,
                LocalSoftwareKeyboardController provides keyboard,
                LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    if (shown.value) Box(Modifier.requiredSize(320.dp, 640.dp)) { SpofifyApp(model) }
                }
            }
        }
    }
    private fun tab(name: String) = compose.onNodeWithTag("nav-$name").performClick()
    private fun back() { compose.runOnIdle { backInput.completeBack() }; compose.waitForIdle() }
    private fun profileAction(label: String) = compose.onNodeWithTag("profile-screen")
        .performScrollToNode(hasText(label)).let { compose.onNodeWithText(label) }
    private fun settingsAction(label: String) = compose.onNodeWithTag("settings-screen")
        .performScrollToNode(hasText(label)).let { compose.onNodeWithText(label) }
    private fun openAccount() { settingsAction("Account and server").performClick(); compose.onNodeWithTag("profile-screen").assertIsDisplayed() }
    private fun openCredits() { settingsAction("Credits").performClick(); compose.onNodeWithTag("credits-screen").assertIsDisplayed() }
    private fun openLicense() = compose.onNodeWithText("View full font license").performScrollTo().performClick()
    private fun dismissDialogWithBack() {
        val dialog = compose.runOnIdle {
            org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog
        }
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun assertPlaybackUnchanged(before: PlaybackState) {
        compose.runOnIdle {
            assertEquals(before, playback.state.value)
            assertTrue("Presentation must not issue transport/preparation commands", playback.commands.isEmpty())
        }
    }

    @Before fun setUp() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (offline) return MockResponse().setResponseCode(503)
                return when (request.requestUrl?.encodedPath) {
                    "/api/auth/login" -> json("""{"accountId":"$account","username":"$username","role":"user","csrfToken":"private-csrf"}""")
                        .setHeader("Set-Cookie", "spofify_session=private-cookie; Path=/; HttpOnly; Max-Age=3600")
                    "/api/auth/logout" -> MockResponse().setResponseCode(204)
                    "/api/library/revision" -> json("""{"revision":1}""")
                    "/api/me/player" -> json("""{"devices":[],"activeDeviceId":"","epoch":1,"leaseMs":0,"state":null,"stateSeq":0,"stateAgeMs":0,"transfer":null,"directives":[],"commands":[]}""")
                    "/api/library" -> {
                        val url = checkNotNull(request.requestUrl)
                        val limit = url.queryParameter("limit")?.toInt() ?: 40
                        val offset = url.queryParameter("offset")?.toInt() ?: 0
                        val total = if (url.queryParameter("kind") == "tracks") 30 else 0
                        val items = (offset until minOf(total, offset + limit)).joinToString(",") {
                            """{"kind":"track","id":"result-$it","name":"Search result $it","hasAudio":false}"""
                        }
                        json("""{"items":[$items],"total":$total,"offset":$offset,"limit":$limit,"revision":1}""")
                    }
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
            navigationBack = NavigationEventDispatcher(object : OnBackCompletedFallback {
                override fun onBackCompletedFallback() { rootBacks++ }
            })
            navigationBack.addInput(backInput)
            model = LibraryViewModel(app, graph, playback)
            store.put("subject", model)
        }
        await { !model.state.value.restoring && model.state.value.session != null }
    }

    @After fun tearDown() {
        try {
            if (mounted) {
                compose.runOnIdle { shown.value = false }
                compose.waitForIdle()
            }
            compose.runOnIdle {
                store.clear()
                if (::graph.isInitialized) graph.clear()
                if (::navigationBack.isInitialized) {
                    navigationBack.removeInput(backInput)
                    navigationBack.dispose()
                }
            }
        } finally { web.shutdown() }
    }

    @Test fun settingsIsAnOrdinaryRootAndAccountIsNestedWithoutPopupSavedControlsOrSyntheticBack() {
        val before = playback.state.value
        mount(); tab("settings")
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        compose.onNodeWithTag("settings-query").assertIsDisplayed()
        settingsAction("Account and server").assertHasClickAction()
        settingsAction("Credits").assertHasClickAction()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
        compose.onNodeWithTag("profile-screen").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(Page.Settings, model.state.value.page)
            assertFalse(model.state.value.canGoBack)
            assertFalse("Settings root must leave Back to the host fallback", backInput.hasEnabledHandlers)
        }
        back()
        assertEquals(1, rootBacks)
        assertEquals(Page.Settings, model.state.value.page)
        openAccount()
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText(username).assertIsDisplayed()
        profileAction("Server").assertIsDisplayed()
        compose.onNodeWithText(client.server.origin).assertIsDisplayed()
        profileAction("Sign out").assertHasClickAction()
        compose.onNodeWithText("Credits").assertDoesNotExist()
        compose.onNodeWithText("Playback devices").assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback devices").assertIsDisplayed()
        listOf("Your connection", "Font license", "View full font license", "Use saved login", "Forget saved login",
            "Remember sign-in on this device", "saved-user", "saved-secret", "private-csrf", "private-cookie", "profile-account")
            .forEach { compose.onNodeWithText(it, substring = true).assertDoesNotExist() }
        compose.onNodeWithContentDescription("Account and server").assertDoesNotExist()
        compose.onNodeWithTag("app-actions").assertDoesNotExist()
        compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
        compose.onNodeWithTag("mini-player-identity").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(Page.Profile, model.state.value.page)
            assertTrue(model.state.value.canGoBack)
            assertTrue("Account must register nested Back", backInput.hasEnabledHandlers)
        }
        back()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        assertEquals(1, rootBacks)
        assertEquals(Page.Settings, model.state.value.page)
        back()
        assertEquals(2, rootBacks)
        assertEquals(Page.Settings, model.state.value.page)
        assertPlaybackUnchanged(before)
    }

    @Test fun settingsSearchIsLocalAndRetainedAcrossRoutesButNotReplacementSessions() {
        mount(); tab("settings")
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        await { !model.state.value.loading && client.httpClient.dispatcher.runningCallsCount() == 0 &&
            client.httpClient.dispatcher.queuedCallsCount() == 0 }
        val requestPaths = requests.map { "${it.method} ${it.path}" }
        val requestCount = web.requestCount
        val beforeNavigation = model.state.value
        val beforePlayback = playback.state.value
        fun assertRows(accountVisible: Boolean, creditsVisible: Boolean) {
            compose.onAllNodesWithText("Account and server").assertCountEquals(if (accountVisible) 1 else 0)
            compose.onAllNodesWithText("Credits").assertCountEquals(if (creditsVisible) 1 else 0)
            compose.onNodeWithText("Sign out").assertDoesNotExist()
            compose.onNodeWithTag("profile-screen").assertDoesNotExist()
            compose.onNodeWithTag("catalog-results").assertDoesNotExist()
        }
        fun assertLocalSearchOnly() {
            compose.waitForIdle()
            assertEquals("Settings search must not issue catalog/library/player or other requests", requestPaths,
                requests.map { "${it.method} ${it.path}" })
            assertEquals("No hidden local-search requests", requestCount, web.requestCount)
            compose.runOnIdle { assertEquals("Filtering must not navigate", beforeNavigation, model.state.value) }
            assertPlaybackUnchanged(beforePlayback)
        }
        assertRows(accountVisible = true, creditsVisible = true)
        listOf("font" to false, "account" to true, "server" to true).forEach { (query, accountVisible) ->
            compose.onNodeWithTag("settings-query").performTextReplacement(query)
            assertRows(accountVisible, creditsVisible = !accountVisible)
            assertLocalSearchOnly()
            compose.onNodeWithContentDescription("Clear settings search").performClick()
            assertRows(accountVisible = true, creditsVisible = true)
            assertLocalSearchOnly()
        }
        compose.onNodeWithTag("settings-query").performTextReplacement("server")
        assertLocalSearchOnly()
        openAccount()
        assertEquals(Page.Profile, model.state.value.page)
        back()
        compose.onNodeWithTag("settings-query").assertTextContains("server")
        assertRows(accountVisible = true, creditsVisible = false)
        assertLocalSearchOnly()
        tab("playlists"); tab("settings")
        compose.onNodeWithTag("settings-query").assertTextContains("server")
        assertRows(accountVisible = true, creditsVisible = false)
        assertEquals(Page.Settings, model.state.value.page)
        assertPlaybackUnchanged(beforePlayback)

        // Authentication deliberately replaces the client and its private saveable-state owner.
        account = "replacement-search-account"
        username = "Replacement search listener"
        compose.runOnIdle { model.signIn("http://127.0.0.1:${web.port}", "listener", "synthetic-password") }
        await { model.state.value.session?.accountId == account && !model.state.value.busy }
        tab("settings")
        assertEquals("", compose.onNodeWithTag("settings-query").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        assertRows(accountVisible = true, creditsVisible = true)
        compose.onNodeWithText(username).assertExists()
    }

    @Test fun offlineCreditsFullNoticeDismissesBeforeNestedBackAndRetainsItsTabStack() {
        val before = playback.state.value
        mount(); tab("settings")
        offline = true // Attribution must not need a working server or an external viewer.
        openCredits()
        compose.onNodeWithText("JetBrains Mono").assertIsDisplayed()
        compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
        openLicense()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fullNotice = app.assets.open("licenses/jetbrains-mono/OFL.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        compose.onNodeWithText(fullNotice).assertExists()
        compose.onNodeWithText("OTHER DEALINGS IN THE FONT SOFTWARE.", substring = true).assertExists()
        dismissDialogWithBack()
        compose.onNodeWithText("JetBrains Mono font license").assertDoesNotExist()
        compose.onNodeWithTag("credits-screen").assertIsDisplayed()
        assertEquals(Page.Credits, model.state.value.page)
        assertEquals(0, rootBacks)
        openLicense()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("JetBrains Mono font license").assertDoesNotExist()
        tab("playlists"); tab("settings")
        compose.onNodeWithTag("credits-screen").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Credits must enable nested Back: ${model.state.value}", model.state.value.canGoBack)
            assertTrue("Credits must register a host Back handler; lifecycle=${actualLifecycle.currentState}", backInput.hasEnabledHandlers)
        }
        back()
        compose.runOnIdle {
            assertEquals("Nested Back must pop Credits; rootBacks=$rootBacks, clientSession=${client.currentSession() != null}",
                Page.Settings, model.state.value.page)
        }
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        assertEquals(0, rootBacks)
        back()
        assertEquals(1, rootBacks)
        assertPlaybackUnchanged(before)
        assertTrue("The local notice must not fetch a license", requests.none { it.path?.contains("license") == true })
    }

    @Test fun settingsReturnPreservesSearchQueryAndRealScrollButExplicitActivationResets() {
        val before = playback.state.value
        mount(); tab("search")
        compose.onNodeWithTag("search-query").performTextReplacement("preserved query")
        compose.onNodeWithTag("search-query").performImeAction()
        await { model.catalogHolder()!!.state.value.current.sections.values.let { it.size == 3 && it.all { section -> section.loaded } } }
        val results = compose.onNodeWithTag("catalog-results")
        fun trackReads() = requests.filter { it.requestUrl?.let { url ->
            url.encodedPath == "/api/library" && url.queryParameter("kind") == "tracks"
        } == true }
        compose.runOnIdle {
            val tracks = model.catalogHolder()!!.state.value.current.sections.getValue("tracks")
            assertEquals(30, tracks.total)
            assertEquals(10, tracks.items.size)
            assertEquals(listOf("0"), trackReads().map { it.requestUrl!!.queryParameter("offset") })
        }
        // Search now receives real ten-row pages. Reach each actual visible frontier before
        // exercising the original index-15 navigation/scroll preservation regression.
        for (loadedCount in listOf(20, 30)) {
            results.performScrollToIndex(loadedCount - 11)
            await { model.catalogHolder()!!.state.value.current.sections.getValue("tracks").let {
                it.items.size == loadedCount && !it.loading
            } }
        }
        results.performScrollToNode(hasText("End of results"))
        compose.onNodeWithText("Search result 29").assertIsDisplayed()
        compose.runOnIdle {
            val tracks = model.catalogHolder()!!.state.value.current.sections.getValue("tracks")
            assertTrue(tracks.ended)
            assertFalse(tracks.hasNext)
            assertEquals((0 until 30).map { "result-$it" }, tracks.items.map { it.id })
            assertEquals(listOf("0", "10", "20"), trackReads().map { it.requestUrl!!.queryParameter("offset") })
            assertTrue(trackReads().all { it.requestUrl!!.queryParameter("limit") == "10" &&
                it.requestUrl!!.queryParameter("q") == "preserved query" })
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        await { model.catalogHolder()!!.state.value.current.scrollIndex > 0 }
        val saved = model.catalogHolder()!!.state.value.current
        tab("settings"); openCredits()
        compose.runOnIdle { model.switchTab(Tab.SEARCH) }
        compose.onNodeWithTag("search-query").assertTextContains("preserved query").assertIsNotFocused()
        await { model.catalogHolder()!!.state.value.current.scrollIndex == saved.scrollIndex }
        compose.runOnIdle {
            val restored = model.catalogHolder()!!.state.value.current
            assertEquals(saved.key, restored.key)
            assertEquals(saved.sections, restored.sections)
            assertEquals(saved.scrollOffset, restored.scrollOffset)
            assertNull(model.state.value.searchActivationId)
        }
        tab("settings") // Its nested Credits route is retained independently.
        compose.onNodeWithTag("credits-screen").assertIsDisplayed()
        tab("search")
        compose.onNodeWithTag("search-query").assertIsFocused()
        assertEquals("", compose.onNodeWithTag("search-query").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        assertEquals("", model.catalogHolder()!!.state.value.current.query)
        assertEquals(0, model.catalogHolder()!!.state.value.current.scrollIndex)
        compose.runOnIdle {
            assertEquals("End, Settings return and empty explicit reset must not fetch another batch",
                listOf("0", "10", "20"), trackReads().map { it.requestUrl!!.queryParameter("offset") })
        }
        assertPlaybackUnchanged(before)
    }

    @Test fun settingsAndCreditsCancelPendingSearchFocusBeforeFieldAttachment() {
        mount()
        compose.runOnIdle {
            model.activateSearch()
            assertNotNull(model.state.value.searchActivationId)
            model.switchTab(Tab.SETTINGS)
            model.open(Page.Credits)
        }
        compose.onNodeWithTag("credits-screen").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(model.state.value.searchActivationId)
            assertEquals(0, keyboard.shows)
            model.switchTab(Tab.SEARCH)
        }
        compose.onNodeWithTag("search-query").assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, keyboard.shows); assertTrue(playback.commands.isEmpty()) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fourNavigationTargetsAndLongProfileValuesRemainReachableAt320Font2() {
        fontScale.value = 2f
        mount(); tab("settings")
        val bounds = listOf("home", "search", "playlists", "settings").map {
            compose.onNodeWithTag("nav-$it").assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
        listOf("Home", "Search", "Playlists", "Settings").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("primary-navigation")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
            assertEquals("Exactly one label layout for $label", 1, layouts.size)
            val layout = layouts.single()
            // Compose may retain a full-constraint paragraph (144px) while shrinking the
            // Text box to its actual line (e.g. Home: 122px in a 123px box). That makes
            // hasVisualOverflow true for unused paragraph area, not clipped glyphs.
            // Assert every real line and the Text box instead of accepting arbitrary overflow.
            assertTrue("Every navigation glyph line must fit at font2: $label", (0 until layout.lineCount).all {
                layout.getLineLeft(it) >= -1f && layout.getLineRight(it) <= layout.size.width + 1f &&
                    layout.getLineTop(it) >= -1f && layout.getLineBottom(it) <= layout.size.height + 1f
            })
            val textBounds = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("primary-navigation")), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val target = compose.onNodeWithTag("nav-${label.lowercase()}").getUnclippedBoundsInRoot()
            assertTrue("The full label must stay inside its target: $label",
                textBounds.left >= target.left && textBounds.right <= target.right &&
                    textBounds.top >= target.top && textBounds.bottom <= target.bottom)
            assertTrue("Navigation labels must not ellipsize: $label", (0 until layout.lineCount).none(layout::isLineEllipsized))
        }
        openAccount()
        listOf(username, client.server.origin, "Sign out").forEach {
            profileAction(it).assertIsDisplayed()
        }
        back()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        openCredits()
        compose.onNodeWithText("View full font license").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        openLicense()
        compose.onNodeWithText("Done").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        dismissDialogWithBack()
        back()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        openAccount()
        profileAction("Sign out").assertIsDisplayed()
        assertTrue(playback.commands.isEmpty())
    }

    @Test fun emptyDockPickerIsPresentationOnlyAndBackReturnsToSameRoot() {
        compose.runOnIdle { playback.state.value = PlaybackState() }
        val before = playback.state.value
        mount()
        compose.onNodeWithText("No song selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
        compose.onNodeWithTag("mini-player-identity").assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback devices").performClick()
        compose.onNodeWithText("Playback devices").assertIsDisplayed()
        compose.onNodeWithText("View only", substring = true).assertExists()
        dismissDialogWithBack()
        compose.onNodeWithText("Playback devices").assertDoesNotExist()
        compose.onNodeWithText("No song selected").assertIsDisplayed()
        assertEquals(Page.Home, model.state.value.page)
        assertPlaybackUnchanged(before)
        assertTrue("Opening cannot register or select an output", requests.none {
            it.method == "POST" && it.requestUrl?.encodedPath == "/api/me/player"
        })
    }

    @Test fun miniAndExpandedPickerBackPreserveActualPlayerWindowAndPreparedState() {
        val before = playback.state.value
        mount()
        compose.onNodeWithContentDescription("Playback devices").performClick()
        compose.onNodeWithText("Playback devices").assertIsDisplayed()
        dismissDialogWithBack()
        compose.onNodeWithTag("mini-player-identity").performClick()
        compose.onNodeWithTag("now-playing").assertIsDisplayed()
        val playerDialog = compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog() }
        compose.onNode(hasContentDescription("Playback devices") and hasAnyAncestor(hasTestTag("now-playing"))).performClick()
        compose.onNodeWithText("Playback devices").assertIsDisplayed()
        val pickerDialog = compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog() }
        assertNotSame("Picker must have its own modal window", playerDialog, pickerDialog)
        assertTrue("Opening must keep the underlying player window mounted", playerDialog.isShowing)
        dismissDialogWithBack()
        compose.onNodeWithText("Playback devices").assertDoesNotExist()
        compose.onNodeWithTag("now-playing").assertIsDisplayed()
        assertTrue(playerDialog.isShowing)
        compose.runOnIdle { (playerDialog as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("now-playing").assertDoesNotExist()
        compose.onNodeWithTag("mini-player-identity").assertIsDisplayed()
        assertPlaybackUnchanged(before)
        assertTrue(requests.none { it.method == "POST" && it.requestUrl?.encodedPath == "/api/me/player" })
    }

    @Test fun logoutWhileNoticeIsOpenCannotLeakPrivateProfileRouteIntoReplacementSession() {
        mount(); tab("settings"); openCredits(); openLicense()
        compose.runOnIdle { model.logout() }
        await { model.state.value.session == null && !model.state.value.busy }
        compose.onNodeWithTag("settings-screen").assertDoesNotExist()
        compose.onNodeWithTag("profile-screen").assertDoesNotExist()
        compose.onNodeWithTag("credits-screen").assertDoesNotExist()
        compose.onNodeWithText("JetBrains Mono font license").assertDoesNotExist()
        compose.onNodeWithText(username).assertDoesNotExist()
        account = "replacement-account"
        username = "Replacement listener"
        compose.runOnIdle { model.signIn("http://127.0.0.1:${web.port}", "listener", "synthetic-password") }
        await { model.state.value.session?.accountId == account && !model.state.value.busy }
        assertEquals(Page.Home, model.state.value.page)
        tab("settings")
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        compose.onNodeWithTag("profile-screen").assertDoesNotExist()
        assertEquals(Page.Settings, model.state.value.page)
        assertFalse(model.state.value.canGoBack)
        openAccount()
        compose.onNodeWithText("Replacement listener").assertIsDisplayed()
        compose.onNodeWithTag("credits-screen").assertDoesNotExist()
        compose.onNodeWithText("JetBrains Mono font license").assertDoesNotExist()
        back()
        assertEquals(Page.Settings, model.state.value.page)
        back()
        assertEquals(1, rootBacks)
    }
}
