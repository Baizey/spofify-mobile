package dev.spofify.mobile

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Copy assertions extracted from SearchNavigationHostTest, without its activation contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class RootCopyHostTest {
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
    private class FakePlayback : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        override fun connect() = Unit
        override fun release() = Unit
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?, shuffle: Boolean, startEntryId: String?) = Unit
        override fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean) = Unit
        override fun setContext(context: LibraryItem?) = Unit
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() = Unit
    }
    private val web = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val shown = mutableStateOf(true)
    private lateinit var graph: AppGraph
    private lateinit var model: LibraryViewModel
    private var mounted = false
    private lateinit var systemBack: OnBackPressedDispatcher
    @Volatile private var respond: (RecordedRequest) -> MockResponse? = { null }
    private val releases = mutableListOf<CountDownLatch>()
    private val playlist = """{"id":"mix","name":"Evening mix","trackCount":0,"availableCount":0,"revision":1}"""
    private val song = """{"kind":"track","id":"song","name":"Evening song","hasAudio":false}"""

    private fun page(items: String = "", total: Int = if (items.isEmpty()) 0 else 1, offset: Int = 0, limit: Int = 40) =
        json("""{"items":[$items],"total":$total,"offset":$offset,"limit":$limit,"revision":1}""")
    private fun failure() = json("""{"error":"synthetic failure"}""").setResponseCode(500)
    private fun hold(): CountDownLatch = CountDownLatch(1).also { releases += it }
    private fun mount() {
        mounted = true
        compose.setContent {
            val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            SideEffect { systemBack = dispatcher }
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                if (shown.value) Box(Modifier.requiredSize(320.dp, 640.dp)) { SpofifyApp(model) }
            }
        }
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        // Pump Android/Compose work before checking non-UI state or server counters; those
        // predicates alone do not drain queued main-thread response publications in Robolectric.
        compose.waitForIdle()
        condition()
    }
    private fun waitText(text: String) = await {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun tab(text: String) = compose.onNodeWithText(text).performClick()
    private fun scrollTo(text: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text))
    }
    private fun awaitNoProgress() = await {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes().isEmpty()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setUp() {
        println("FEATURE02_FORK_TMP=" + System.getProperty("java.io.tmpdir"))
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return respond(request) ?: when (request.requestUrl?.encodedPath) {
                    "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"csrf"}""")
                        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                    "/api/library/revision" -> json("""{"revision":1}""")
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
            model = LibraryViewModel(app, graph, FakePlayback())
            store.put("subject", model)
        }
        await { !model.state.value.restoring && model.state.value.session != null }
    }

    @After fun tearDown() {
        try {
            println("ROOT_REQUESTS=" + requests.map { it.path })
            if (::model.isInitialized) println("ROOT_HOME=" + model.homeHolder()?.state?.value)
            releases.forEach { it.countDown() }
            if (mounted) {
                compose.runOnIdle { shown.value = false }
                compose.waitForIdle()
            }
            compose.runOnIdle { store.clear(); if (::graph.isInitialized) graph.clear() }
        } finally { web.shutdown() }
    }

    @Test fun emptyHomeSearchAndPlaylistsRootsKeepConciseCopy() {
        mount()
        await {
            model.homeHolder()?.state?.value?.let {
                it.recent.value != null && !it.recent.loading && it.playlists.value != null && !it.playlists.loading
            } == true && compose.onAllNodesWithText("Recently played").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Liked songs").assertIsDisplayed()
        compose.onNodeWithText("Create a playlist to collect your music.").assertDoesNotExist()
        compose.onNodeWithText("recent listening", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Recently played").assertDoesNotExist()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("search-query").assertIsDisplayed()
        compose.onNodeWithText("Find an artist, album or song", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("search-query").performImeAction()
        assertTrue("Empty Search must never issue a global match-all read", requests.none { request ->
            val url = request.requestUrl ?: return@none false
            url.encodedPath == "/api/library" && url.queryParameter("q").isNullOrBlank() &&
                url.queryParameter("artistId") == null && url.queryParameter("albumId") == null
        })
        compose.onNodeWithText("Playlists").performClick()
        await { requests.any { it.requestUrl?.encodedPath == "/api/me/playlists" && it.requestUrl?.queryParameter("limit") == "40" } }
        awaitNoProgress()
        compose.onNodeWithText("Playlists could not load.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Your playlists").assertDoesNotExist()
        compose.onNodeWithText("Make a playlist", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Create playlist").assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertIsDisplayed()
        compose.onNodeWithText("Next playlists").assertDoesNotExist()
        compose.onNodeWithText("Previous").assertDoesNotExist()
        compose.onNodeWithText("Create playlist").performClick()
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithText("Manual playlists keep an editable ordered list. Duplicate tracks remain separate occurrences.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun emptyLikesRemainConciseAndRecoverable() {
        mount()
        compose.onNodeWithText("Liked songs").performClick()
        waitText("No liked songs yet.")
        compose.onNodeWithText("No liked songs yet.").assertIsDisplayed()
        compose.onNodeWithText("Songs you like will find a home here.", substring = true).assertDoesNotExist()
        compose.runOnIdle { systemBack.onBackPressed() }
        compose.onNodeWithText("Liked songs").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("No liked songs yet.").assertDoesNotExist()
    }

    @Test fun homeInitialLoadingAndFailureNeverBecomeAnEmptyRecentShelf() {
        val gate = hold()
        respond = { request ->
            if (request.requestUrl?.encodedPath == "/api/me/recent") {
                gate.await(15, TimeUnit.SECONDS); failure()
            } else null
        }
        mount()
        waitText("Recently played")
        compose.onNodeWithText("Recently played").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes().also { assertTrue("Loading must remain visible", it.isNotEmpty()) }
        gate.countDown()
        waitText("Retry recently played")
        compose.onNodeWithText("Retry recently played").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Liked songs").assertIsDisplayed()
        respond = { request -> if (request.requestUrl?.encodedPath == "/api/me/recent") page("""{"track":$song}""") else null }
        compose.onNodeWithText("Retry recently played").performClick()
        waitText("Evening song")
        compose.onNodeWithText("Evening song").assertIsDisplayed()
        compose.onNodeWithText("Retry recently played").assertDoesNotExist()
    }

    @Test fun homePartialFailureKeepsMusicAndPlaylistNavigationAndRetry() {
        val releaseHealthyRecent = hold()
        respond = { request -> when (request.requestUrl?.encodedPath) {
            "/api/me/recent" -> {
                releaseHealthyRecent.await(15, TimeUnit.SECONDS)
                page("""{"track":$song}""")
            }
            "/api/me/playlists" -> failure()
            else -> null
        } }
        mount()
        waitText("Retry playlists")
        // Force the ordering that exposed the merged-result failure: playlist failure
        // is visible while the independent healthy recent response is still held.
        compose.onNodeWithText("Evening song").assertDoesNotExist()
        compose.runOnIdle { assertTrue(model.homeHolder()!!.state.value.recent.loading) }
        releaseHealthyRecent.countDown()
        // A failed playlist response is not a completion barrier for healthy shelves.
        waitText("Evening song")
        compose.onNodeWithText("Evening song").assertIsDisplayed()
        compose.onNodeWithText("All playlists").performScrollTo().assertIsEnabled()
        respond = { request -> when (request.requestUrl?.encodedPath) {
            "/api/me/recent" -> page("""{"track":$song}""")
            "/api/me/playlists" -> page(playlist)
            "/api/me/playlists/mix" -> json(playlist)
            else -> null
        } }
        compose.onNodeWithText("Retry playlists").performScrollTo().performClick()
        waitText("Evening mix")
        compose.onNodeWithText("Evening mix").performScrollTo().performClick()
        waitText("0 tracks · 0 with audio")
        compose.onNodeWithText("Evening mix").assertIsDisplayed()
    }

    @Test fun playlistsLoadingErrorRefreshAndRealPagingKeepPrimaryActions() {
        fun indexOffsets() = requests.filter {
            it.requestUrl?.encodedPath == "/api/me/playlists" && it.requestUrl?.queryParameter("limit") == "40"
        }.map { it.requestUrl!!.queryParameter("offset")!!.toInt() }
        val gate = hold()
        val appendGate = hold()
        respond = { request -> if (request.requestUrl?.encodedPath == "/api/me/playlists") {
            gate.await(15, TimeUnit.SECONDS); failure()
        } else null }
        mount(); tab("Playlists")
        await { indexOffsets() == listOf(0) }
        compose.onNodeWithText("Create playlist").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes().also { assertTrue("Initial playlist loading must remain visible", it.isNotEmpty()) }
        compose.onNodeWithText("Next playlists").assertDoesNotExist()
        compose.onNodeWithText("Previous").assertDoesNotExist()
        gate.countDown()
        waitText("Playlists could not load.")
        compose.onNodeWithText("Create playlist").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Refresh").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled()
        respond = { request -> if (request.requestUrl?.encodedPath == "/api/me/playlists") {
            val offset = request.requestUrl!!.queryParameter("offset")?.toInt() ?: 0
            val limit = request.requestUrl!!.queryParameter("limit")!!.toInt()
            if (offset == 0) page((0 until limit).joinToString(",") { index ->
                if (index == 0) playlist else playlist.replace("\"mix\"", "\"mix-$index\"").replace("Evening mix", "Mix $index")
            }, total = 41, limit = limit)
            else {
                appendGate.await(15, TimeUnit.SECONDS)
                page(playlist.replace("\"mix\"", "\"last\"").replace("Evening mix", "Last mix"), total = 41, offset = 40)
            }
        } else null }
        compose.onNodeWithText("Retry").performClick()
        waitText("Evening mix")
        awaitNoProgress()
        assertEquals(listOf(0, 0), indexOffsets())
        compose.onNodeWithText("Playlists could not load.").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Create playlist").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Refresh").assertIsDisplayed().assertIsEnabled().performClick()
        await { indexOffsets() == listOf(0, 0, 0) }
        awaitNoProgress()
        compose.onNodeWithText("Evening mix").assertIsDisplayed()
        compose.onNodeWithText("Create playlist").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Refresh").assertIsDisplayed().assertIsEnabled()

        val list = compose.onNode(hasScrollToIndexAction())
        // Real touch movement brings the loaded 40-row frontier into this finite root viewport.
        // No holder callback, fetch-more action or semantic scroll-to an unloaded row substitutes.
        for (attempt in 0 until 24) {
            if (indexOffsets().contains(40)) break
            list.performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        await { indexOffsets() == listOf(0, 0, 0, 40) }
        list.performTouchInput { swipeUp() }
        await { compose.onAllNodesWithContentDescription("Loading more playlists").fetchSemanticsNodes().isNotEmpty() }
        repeat(3) { list.performTouchInput { swipeUp() }; compose.waitForIdle() }
        assertEquals("Held append remains single-flight at the exact raw frontier", listOf(0, 0, 0, 40), indexOffsets())
        appendGate.countDown()
        awaitNoProgress()
        scrollTo("Last mix")
        compose.onNodeWithText("Last mix").assertIsDisplayed()
        compose.onNodeWithText("All playlists loaded.").performScrollTo().assertIsDisplayed()
        repeat(3) { list.performTouchInput { swipeUp() }; compose.waitForIdle() }
        assertEquals("The terminal one-row batch stops automatic demand", listOf(0, 0, 0, 40), indexOffsets())
        scrollTo("Evening mix")
        compose.onNodeWithText("Evening mix").assertIsDisplayed() // Appending never replaces the prefix.
        compose.onNodeWithText("Create playlist").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Refresh").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Next playlists").assertDoesNotExist()
        compose.onNodeWithText("Previous").assertDoesNotExist()
        scrollTo("Last mix")
        compose.onNodeWithText("Last mix").assertIsDisplayed() // Prefix and terminal row coexist in one list.
        assertEquals(listOf(0, 0, 0, 40), indexOffsets())
    }

    @Test fun playlistIndexProfileReturnRetainsPrefixAndFailedRawOffsetUntilRetry() {
        val appendAttempts = java.util.concurrent.atomic.AtomicInteger()
        val retryGate = hold()
        fun offsets() = requests.filter {
            it.requestUrl?.encodedPath == "/api/me/playlists" && it.requestUrl?.queryParameter("limit") == "40"
        }.map { it.requestUrl!!.queryParameter("offset")!!.toInt() }
        respond = { request -> if (request.requestUrl?.encodedPath == "/api/me/playlists") {
            val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
            val limit = request.requestUrl!!.queryParameter("limit")!!.toInt()
            if (offset == 0) page((0 until limit).joinToString { index ->
                if (index == 0) playlist else playlist.replace("\"mix\"", "\"mix-$index\"").replace("Evening mix", "Mix $index")
            }, total = 41, limit = limit)
            else if (appendAttempts.incrementAndGet() == 1) failure()
            else {
                if (appendAttempts.get() == 2) retryGate.await(15, TimeUnit.SECONDS)
                page(playlist.replace("\"mix\"", "\"last\"").replace("Evening mix", "Last mix"), total = 41, offset = 40)
            }
        } else null }
        mount(); tab("Playlists")
        waitText("Evening mix")
        awaitNoProgress()
        val list = compose.onNode(hasScrollToIndexAction())
        for (attempt in 0 until 24) {
            if (offsets().contains(40)) break
            list.performTouchInput { swipeUp() }; compose.waitForIdle()
        }
        list.performTouchInput { swipeUp() }
        waitText("Retry")
        assertEquals(listOf(0, 40), offsets())
        compose.onNodeWithText("Retry").performScrollTo()
        val beforeProfile = compose.onNodeWithText("Mix 39").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        tab("Profile")
        await { model.state.value.page == Page.Profile }
        tab("Playlists")
        waitText("Retry")
        val afterProfile = compose.onNodeWithText("Mix 39").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Index row top survives Profile return", beforeProfile.top, afterProfile.top, 1f)
        assertEquals("Index row bottom survives Profile return", beforeProfile.bottom, afterProfile.bottom, 1f)
        assertEquals("Actual tab return cannot reset the prefix or retry the failed batch", listOf(0, 40), offsets())
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        await { offsets() == listOf(0, 40, 40) }
        val beforeCanceledRetryReturn = compose.onNodeWithText("Mix 39").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        tab("Profile") // Cancel the explicitly retried read before its response exists.
        await { model.state.value.page == Page.Profile }
        retryGate.countDown()
        tab("Playlists")
        waitText("Retry")
        assertEquals("Canceled Retry must not clear the retained failure latch", listOf(0, 40, 40), offsets())
        val afterCanceledRetryReturn = compose.onNodeWithText("Mix 39").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Index row top survives canceled-Retry return", beforeCanceledRetryReturn.top, afterCanceledRetryReturn.top, 1f)
        assertEquals("Index row bottom survives canceled-Retry return", beforeCanceledRetryReturn.bottom, afterCanceledRetryReturn.bottom, 1f)
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        await { offsets() == listOf(0, 40, 40, 40) }
        awaitNoProgress()
        scrollTo("Last mix")
        compose.onNodeWithText("Last mix").assertIsDisplayed()
        scrollTo("Evening mix")
        compose.onNodeWithText("Evening mix").assertIsDisplayed()
        assertEquals(listOf(0, 40, 40, 40), offsets())
    }

    @Test fun derivedPlaylistTrackBackRetainsSelectedPrefixAndExactFailedCursor() {
        val summary = """{"id":"rules","name":"Rules mix","kind":"derived","countState":"unevaluated","trackCount":null,"availableCount":null,"revision":5,"capabilities":{"play":true,"editMetadata":false,"editEntries":false,"editDefinition":false,"delete":false,"copyToManual":false,"addTracks":false}}"""
        val failedCursorAttempts = java.util.concurrent.atomic.AtomicInteger()
        fun cursors() = requests.filter { it.requestUrl?.encodedPath == "/api/me/playlists/rules/evaluation" }
            .map { it.requestUrl!!.queryParameter("cursor") ?: "start" }
        fun track(index: Int) = """{"kind":"track","id":"song-$index","name":"Song $index","hasAudio":true}"""
        respond = { request -> when (request.requestUrl?.encodedPath) {
            "/api/me/playlists" -> page(summary, limit = request.requestUrl!!.queryParameter("limit")!!.toInt())
            "/api/me/playlists/rules" -> json(summary)
            "/api/me/playlists/rules/evaluation" -> {
                val offset = request.requestUrl!!.queryParameter("cursor")?.removePrefix("cursor-")?.toInt() ?: 0
                if (offset == 80 && failedCursorAttempts.incrementAndGet() == 1) failure()
                else {
                    val end = minOf(offset + 40, 85)
                    val items = (offset until end).joinToString { index ->
                        """{"occurrenceId":"path-$index","selectionToken":"signed-$index","position":$index,"track":${track(index)}}"""
                    }
                    val next = if (end < 85) "\"cursor-$end\"" else "null"
                    json("""{"items":[$items],"total":85,"availableTotal":85,"limit":40,"nextCursor":$next,"revision":5,"fence":"same-fence","complete":true}""")
                }
            }
            "/api/library/tracks/song-78" -> json("""{"item":${track(78)},"revision":1}""")
            else -> null
        } }
        mount(); tab("Playlists")
        waitText("Rules mix")
        compose.onNodeWithText("Rules mix").performClick()
        waitText("Song 0")
        val list = compose.onNode(hasScrollToIndexAction())
        for (attempt in 0 until 40) {
            if (cursors().size == 3) break
            list.performTouchInput { swipeUp() }; compose.waitForIdle()
        }
        list.performTouchInput { swipeUp() }
        waitText("Retry")
        assertEquals(listOf("start", "cursor-40", "cursor-80"), cursors())
        compose.onNodeWithText("Song 78").performScrollTo().assertIsDisplayed()
        val beforeTrack = compose.onNodeWithText("Song 78").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Song 78").performClick()
        await { (model.state.value.page as? Page.Detail)?.item?.id == "song-78" }
        await { requests.any { it.requestUrl?.encodedPath == "/api/library/tracks/song-78" } }
        compose.runOnIdle { systemBack.onBackPressed() }
        waitText("Retry")
        val afterTrack = compose.onNodeWithText("Song 78").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Playlist row top survives track Back", beforeTrack.top, afterTrack.top, 1f)
        assertEquals("Playlist row bottom survives track Back", beforeTrack.bottom, afterTrack.bottom, 1f)
        assertEquals("Track Back must not restart evaluation or retry its latched cursor",
            listOf("start", "cursor-40", "cursor-80"), cursors())
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        await { cursors().size == 4 }
        awaitNoProgress()
        scrollTo("Song 84")
        compose.onNodeWithText("Song 84").assertIsDisplayed()
        scrollTo("Song 0")
        compose.onNodeWithText("Song 0").assertIsDisplayed()
        assertEquals(listOf("start", "cursor-40", "cursor-80", "cursor-80"), cursors())
        assertTrue("Browsing and track Back cannot prepare music", requests.none { it.requestUrl?.encodedPath?.endsWith("/playback") == true })
    }

    @Test fun searchLoadingPartialErrorRetryAndNoResultsAreDistinct() {
        val gate = hold()
        respond = { request -> if (request.requestUrl?.encodedPath == "/api/library") {
            gate.await(15, TimeUnit.SECONDS)
            when (request.requestUrl?.queryParameter("kind")) {
                "tracks" -> page(song)
                "albums" -> failure()
                else -> page()
            }
        } else null }
        mount(); tab("Search")
        compose.onNodeWithTag("search-query").performTextReplacement("evening")
        compose.onNodeWithTag("search-query").performImeAction()
        await { requests.any { it.requestUrl?.encodedPath == "/api/library" } }
        compose.onNodeWithContentDescription("Searching saved music").assertIsDisplayed()
        compose.onNodeWithText("No music matches this search.").assertDoesNotExist()
        gate.countDown()
        waitText("Retry albums")
        waitText("Evening song")
        compose.onNodeWithText("Evening song").assertIsDisplayed()
        compose.onNodeWithText("No music matches this search.").assertDoesNotExist()
        respond = { request -> if (request.requestUrl?.encodedPath == "/api/library") page() else null }
        compose.onNodeWithText("Retry albums").performClick()
        await { compose.onAllNodesWithText("Retry albums").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Evening song").assertIsDisplayed()
        compose.onNodeWithTag("search-query").performTextReplacement("absent")
        compose.onNodeWithTag("search-query").performImeAction()
        waitText("No music matches this search.")
        compose.onNodeWithText("No music matches this search.").assertIsDisplayed()
        compose.onNodeWithText("Find an artist, album or song", substring = true).assertDoesNotExist()
    }

    @Test fun failedLikesNeverShowEmptySuccessAndRetryStillWorks() {
        respond = { request -> if (request.requestUrl?.encodedPath == "/api/me/likes") failure() else null }
        mount(); compose.onNodeWithText("Liked songs").performClick()
        waitText("Retry liked songs")
        compose.onNodeWithText("No liked songs yet.").assertDoesNotExist()
        respond = { request -> if (request.requestUrl?.encodedPath == "/api/me/likes") page("""{"track":$song}""") else null }
        compose.onNodeWithText("Retry liked songs").performClick()
        waitText("Evening song")
        compose.onNodeWithText("Evening song").assertIsDisplayed()
        compose.onNodeWithText("No liked songs yet.").assertDoesNotExist()
    }
}
