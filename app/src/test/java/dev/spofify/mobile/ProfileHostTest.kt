package dev.spofify.mobile

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
    private fun openCredits() { profileAction("Credits").performClick(); compose.onNodeWithTag("credits-screen").assertIsDisplayed() }
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
                    "/api/library" -> {
                        val items = if (request.requestUrl?.queryParameter("kind") == "tracks") (0 until 30).joinToString(",") {
                            """{"kind":"track","id":"result-$it","name":"Search result $it","hasAudio":false}"""
                        } else ""
                        json("""{"items":[$items],"total":${if (items.isEmpty()) 0 else 30},"offset":0,"limit":40,"revision":1}""")
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

    @Test fun profileIsAnOrdinaryIdentityRootWithoutPopupSavedControlsOrSyntheticBack() {
        val before = playback.state.value
        mount(); tab("profile")
        compose.onNodeWithTag("profile-screen").assertIsDisplayed()
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText(username).assertIsDisplayed()
        profileAction("Server").assertIsDisplayed()
        compose.onNodeWithText(client.server.origin).assertIsDisplayed()
        listOf("Playback devices", "Credits", "Sign out").forEach { profileAction(it).assertHasClickAction() }
        listOf("Your connection", "Font license", "View full font license", "Use saved login", "Forget saved login",
            "Remember sign-in on this device", "saved-user", "saved-secret", "private-csrf", "private-cookie", "profile-account")
            .forEach { compose.onNodeWithText(it, substring = true).assertDoesNotExist() }
        compose.onNodeWithContentDescription("Account and server").assertDoesNotExist()
        compose.onNodeWithTag("app-actions").assertDoesNotExist()
        compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
        compose.onNodeWithTag("mini-player-identity").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(Page.Profile, model.state.value.page)
            assertFalse(model.state.value.canGoBack)
            assertFalse("Profile root must leave Back to the host fallback", backInput.hasEnabledHandlers)
        }
        back()
        assertEquals(1, rootBacks)
        assertEquals(Page.Profile, model.state.value.page)
        assertPlaybackUnchanged(before)
    }

    @Test fun offlineCreditsFullNoticeDismissesBeforeNestedBackAndRetainsItsTabStack() {
        val before = playback.state.value
        mount(); tab("profile")
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
        tab("playlists"); tab("profile")
        compose.onNodeWithTag("credits-screen").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Credits must enable nested Back: ${model.state.value}", model.state.value.canGoBack)
            assertTrue("Credits must register a host Back handler; lifecycle=${actualLifecycle.currentState}", backInput.hasEnabledHandlers)
        }
        back()
        compose.runOnIdle {
            assertEquals("Nested Back must pop Credits; rootBacks=$rootBacks, clientSession=${client.currentSession() != null}",
                Page.Profile, model.state.value.page)
        }
        compose.onNodeWithTag("profile-screen").assertIsDisplayed()
        assertEquals(0, rootBacks)
        back()
        assertEquals(1, rootBacks)
        assertPlaybackUnchanged(before)
        assertTrue("The local notice must not fetch a license", requests.none { it.path?.contains("license") == true })
    }

    @Test fun profileReturnPreservesSearchQueryAndRealScrollButExplicitActivationResets() {
        val before = playback.state.value
        mount(); tab("search")
        compose.onNodeWithTag("search-query").performTextReplacement("preserved query")
        compose.onNodeWithTag("search-query").performImeAction()
        await { model.catalogHolder()!!.state.value.current.sections.values.let { it.size == 3 && it.all { section -> section.loaded } } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        await { model.catalogHolder()!!.state.value.current.scrollIndex > 0 }
        val saved = model.catalogHolder()!!.state.value.current
        tab("profile"); openCredits()
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
        tab("profile") // Its nested Credits route is retained independently.
        compose.onNodeWithTag("credits-screen").assertIsDisplayed()
        tab("search")
        compose.onNodeWithTag("search-query").assertIsFocused()
        assertEquals("", compose.onNodeWithTag("search-query").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        assertEquals("", model.catalogHolder()!!.state.value.current.query)
        assertEquals(0, model.catalogHolder()!!.state.value.current.scrollIndex)
        assertPlaybackUnchanged(before)
    }

    @Test fun profileAndCreditsCancelPendingSearchFocusBeforeFieldAttachment() {
        mount()
        compose.runOnIdle {
            model.activateSearch()
            assertNotNull(model.state.value.searchActivationId)
            model.switchTab(Tab.PROFILE)
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
        mount(); tab("profile")
        val bounds = listOf("home", "search", "playlists", "profile").map {
            compose.onNodeWithTag("nav-$it").assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
        listOf("Home", "Search", "Playlists", "Profile").forEach { label ->
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
        listOf(username, client.server.origin, "Playback devices", "Credits", "Sign out").forEach {
            profileAction(it).assertIsDisplayed()
        }
        openCredits()
        compose.onNodeWithText("View full font license").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        openLicense()
        compose.onNodeWithText("Done").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        dismissDialogWithBack()
        back()
        profileAction("Sign out").assertIsDisplayed()
        assertTrue(playback.commands.isEmpty())
    }

    @Test fun logoutWhileNoticeIsOpenCannotLeakPrivateProfileRouteIntoReplacementSession() {
        mount(); tab("profile"); openCredits(); openLicense()
        compose.runOnIdle { model.logout() }
        await { model.state.value.session == null && !model.state.value.busy }
        compose.onNodeWithTag("profile-screen").assertDoesNotExist()
        compose.onNodeWithTag("credits-screen").assertDoesNotExist()
        compose.onNodeWithText("JetBrains Mono font license").assertDoesNotExist()
        compose.onNodeWithText(username).assertDoesNotExist()
        account = "replacement-account"
        username = "Replacement listener"
        compose.runOnIdle { model.signIn("http://127.0.0.1:${web.port}", "listener", "synthetic-password") }
        await { model.state.value.session?.accountId == account && !model.state.value.busy }
        assertEquals(Page.Home, model.state.value.page)
        tab("profile")
        compose.onNodeWithTag("profile-screen").assertIsDisplayed()
        compose.onNodeWithText("Replacement listener").assertIsDisplayed()
        compose.onNodeWithTag("credits-screen").assertDoesNotExist()
        compose.onNodeWithText("JetBrains Mono font license").assertDoesNotExist()
        back()
        assertEquals(1, rootBacks)
    }
}
