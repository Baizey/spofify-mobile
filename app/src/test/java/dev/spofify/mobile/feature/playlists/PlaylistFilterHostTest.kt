package dev.spofify.mobile.feature.playlists

import android.graphics.Bitmap
import android.view.View
import android.widget.Magnifier
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.library.CatalogPlaybackFeedback
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.net.InetAddress
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi", shadows = [PlaylistFilterHostTest.NoSurfaceMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistFilterHostTest {
    // Robolectric has no magnifier Surface; retain actual focus/insets/Back and stub only its popup.
    @Implements(Magnifier::class)
    class NoSurfaceMagnifier {
        @Implementation protected fun show(sourceX: Float, sourceY: Float) = Unit
        @Implementation protected fun show(sourceX: Float, sourceY: Float, windowX: Float, windowY: Float) = Unit
        @Implementation protected fun dismiss() = Unit
    }
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    private class Fixture(val derived: Boolean = false) : AutoCloseable {
        val web = MockWebServer()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val client: SpofifyClient
        init {
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    paths += request.path!!
                    val body = when (path) {
                        "/api/auth/logout" -> "{}"
                        "/api/auth/login" -> """{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}"""
                        "/api/me/playlists/fixture" -> """{"id":"fixture","name":"Filter fixture","kind":"${if (derived) "derived" else "manual"}","countState":"${if (derived) "unevaluated" else "exact"}","trackCount":${if (derived) "null" else "85"},"availableCount":${if (derived) "null" else "85"},"revision":5,"capabilities":{"play":true,"editMetadata":false,"editEntries":false,"editDefinition":false,"delete":false,"copyToManual":false,"addTracks":false}}"""
                        "/api/me/playlists/fixture/entries" -> {
                            val items = (0 until 40).joinToString { index ->
                                val name = if (index == 20 || index == 39) "Needle duplicate" else "Song $index"
                                val track = """{"kind":"track","id":"same","name":"$name","artists":[{"id":"a","name":"Mira Vale"}],"albumName":"After the Tide","hasAudio":true}"""
                                if (derived) """{"occurrenceId":"path-$index","selectionToken":"signed-$index","position":$index,"track":$track}"""
                                else """{"id":"entry-$index","position":$index,"track":$track}"""
                            }
                            if (derived) """{"items":[$items],"total":85,"availableTotal":85,"limit":40,"nextCursor":"cursor-40","revision":5,"fence":"fence","complete":true}"""
                            else """{"items":[$items],"total":85,"offset":0,"limit":40}"""
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body).apply {
                        if (path == "/api/auth/login") setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
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
        fun requests(): List<String> = synchronized(paths) { paths.toList() }
        override fun close() {
            client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
            client.httpClient.dispatcher.executorService.shutdown(); web.close()
        }
    }

    private data class Host(val shown: MutableState<Boolean>, val memory: PlaylistBrowseMemory,
        val accepted: MutableList<Pair<QueueIntentSelection, PlaybackListAction>>, val back: () -> Unit,
        val height: MutableState<Dp>)
    private fun host(fixture: Fixture, projectionScheduler: TestCoroutineScheduler? = null, run: (Host) -> Unit) {
        val shown = mutableStateOf(true)
        val height = mutableStateOf(850.dp)
        val accepted = mutableListOf<Pair<QueueIntentSelection, PlaybackListAction>>()
        val memory = PlaylistBrowseMemory(projectionScheduler?.let { StandardTestDispatcher(it) } ?: kotlinx.coroutines.Dispatchers.Default)
        var dispatcher: OnBackPressedDispatcher? = null
        val key = PlaybackInteractionKey()
        compose.setContent {
            val current = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            SideEffect { dispatcher = current }
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.fillMaxWidth().height(height.value).testTag("filter-host")) {
                    if (shown.value) PlaylistsRoute(fixture.client,
                        { fail("Filtering/song bodies must not navigate") }, { _, _, _ -> fail("No preparation during typing") }, {},
                        initialPlaylistId = "fixture", browseMemory = memory,
                        artwork = { _, modifier -> Box(modifier) },
                        playback = CatalogPlaybackFeedback(interactionKey = key),
                        onQueueIntent = { selection, action, captured, _, valid ->
                            assertSame(key, captured); assertTrue(valid()); accepted += selection to action
                        })
                }
            }
        }
        try {
            compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithText("Song 0").fetchSemanticsNodes().isNotEmpty() }
            run(Host(shown, memory, accepted, { checkNotNull(dispatcher).onBackPressed() }, height))
        } finally { compose.runOnIdle { shown.value = false }; compose.waitForIdle() }
    }
    private fun query(value: String, projectionScheduler: TestCoroutineScheduler? = null) {
        compose.onNodeWithTag("playlist-filter").performTextReplacement(value)
        compose.waitForIdle()
        projectionScheduler?.runCurrent()
        compose.waitUntil(5_000) { compose.waitForIdle(); value.isBlank() ||
            compose.onAllNodesWithTag("playlist-filter-result").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun editsNoMatchWhitespaceAndClearEmitZeroRequestsOrPlaybackAcrossPagedPrefix() {
        Fixture().use { fixture -> host(fixture) { host ->
            val before = fixture.requests()
            compose.onNodeWithTag("playlist-filter-result").assertDoesNotExist()
            query("  nEEdle  ")
            compose.onAllNodesWithText("Needle duplicate").assertCountEquals(2)
            compose.onNodeWithText("Filtered to 2 songs").assertExists()
            query("no such song")
            compose.onNodeWithText("Filtered to 0 songs").assertExists()
            capture("paged-no-match")
            query(".*")
            query("MIRA VALE")
            query("after the tide")
            query(" \t ")
            compose.onNodeWithTag("playlist-filter-result").assertDoesNotExist()
            compose.onNodeWithTag("playlist-filter").performClick()
            compose.onNodeWithContentDescription("Clear playlist filter").performClick()
            compose.onNodeWithTag("playlist-filter").assertIsFocused()
            compose.onNodeWithText("Song 0").assertExists()
            compose.waitForIdle()
            assertEquals(before, fixture.requests())
            assertTrue(host.accepted.isEmpty())
            capture("paged-filter-clear")
        } }
    }

    @Test fun viewportShrinkMayRelocateClippedInputInsteadOfSuppressingItsVisibilityRequest() {
        Fixture().use { fixture -> host(fixture) { host ->
            compose.onNodeWithTag("playlist-filter").performClick().assertIsFocused()
            val before = fixture.requests()
            compose.runOnIdle { host.height.value = 170.dp }
            compose.waitForIdle()
            val field = compose.onNodeWithTag("playlist-filter").getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("filter-host").getUnclippedBoundsInRoot()
            assertTrue("Clipped input must be scrolled into the resized viewport: field=$field viewport=$viewport", field.top >= viewport.top && field.bottom <= viewport.bottom)
            compose.onNodeWithTag("playlist-filter").assertIsFocused()
            capture("filter-short-viewport")
            assertEquals(before, fixture.requests())
            assertTrue(host.accepted.isEmpty())
        } }
    }

    @Test fun lastResidentMatchAndPinnedInputRemainReachableWithoutLoadingMore() {
        Fixture().use { fixture -> host(fixture) { host ->
            query("mira")
            val requests = fixture.requests()
            compose.onNode(hasScrollToIndexAction()).performScrollToKey("entry-39")
            compose.onNodeWithText("Needle duplicate").assertIsDisplayed()
            compose.onNodeWithTag("playlist-filter").assertIsDisplayed()
            capture("last-resident-match")
            assertEquals(requests, fixture.requests())
            assertTrue(host.accepted.isEmpty())
        } }
    }

    @Test fun filteredManualDuplicatesKeepOriginalEntryAndCompleteCollectionContext() = exact(false)
    @Test fun filteredDerivedDuplicatesKeepOriginalOccurrenceTokenFenceAndCompleteContext() = exact(true)
    private fun exact(derived: Boolean) {
        Fixture(derived).use { fixture -> host(fixture) { host ->
            query("needle")
            capture(if (derived) "derived-matches" else "manual-matches")
            compose.onAllNodesWithText("Needle duplicate")[1].performClick()
            val selected = host.accepted.single().first as QueueIntentSelection.Track
            assertEquals("fixture", selected.context!!.id)
            assertEquals(5L, selected.context!!.revision)
            assertEquals(if (derived) "derived" else "playlist", selected.context!!.kind)
            if (derived) {
                assertEquals("path-39", selected.track.getValue("occurrenceId").jsonPrimitive.content)
                assertEquals("signed-39", selected.track.getValue("selectionToken").jsonPrimitive.content)
                assertEquals("fence", selected.context!!.fence)
            } else assertEquals("entry-39", selected.track.getValue("entryId").jsonPrimitive.content)
            assertEquals(PlaybackListAction.PLAY_NOW, host.accepted.single().second)
            compose.onNodeWithText("Play", substring = false).performScrollTo().performClick()
            val header = host.accepted.last().first as QueueIntentSelection.Collection
            assertEquals(selected.context, header.context)
            assertEquals(1, fixture.requests().count { it.contains("/entries") })
        } }
    }

    @Test fun editingBackDoesNotPopAndTabRestoreKeepsQueryRowsScrollWithoutRefocusOrRequests() {
        Fixture().use { fixture -> host(fixture) { host ->
            query("needle")
            compose.onNodeWithTag("playlist-filter").performClick()
            compose.runOnIdle { host.back() }
            compose.onAllNodesWithText("Needle duplicate").assertCountEquals(2)
            compose.onNodeWithTag("playlist-filter").assertIsNotFocused()
            val before = fixture.requests()
            compose.runOnIdle { host.shown.value = false }; compose.waitForIdle()
            compose.runOnIdle { host.shown.value = true }; compose.waitForIdle()
            compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithText("Needle duplicate").fetchSemanticsNodes().size == 2 }
            compose.onNodeWithTag("playlist-filter").assertTextEquals("needle").assertIsNotFocused()
            compose.onAllNodesWithText("Needle duplicate").assertCountEquals(2)
            assertEquals(before, fixture.requests())
            assertTrue(host.accepted.isEmpty())
        } }
    }

    @Test fun deepFilteredAnchorSurvivesTabRestoreAndSameQuerySourceUpdate() {
        Fixture().use { fixture -> host(fixture) { host ->
            query("mira")
            compose.onNode(hasScrollToIndexAction()).performScrollToKey("entry-20")
            val before = compose.onAllNodesWithText("Needle duplicate")[0].getUnclippedBoundsInRoot()
            val requests = fixture.requests()
            val detail = host.memory.detail("fixture", 0)
            val retained = detail.manualProjection.result
            compose.runOnIdle { host.shown.value = false }; compose.waitForIdle()
            compose.runOnIdle { host.shown.value = true }; compose.waitForIdle()
            assertSame(retained, detail.manualProjection.result)
            assertEquals(before, compose.onAllNodesWithText("Needle duplicate")[0].getUnclippedBoundsInRoot())
            compose.runOnIdle { detail.manualEntries.value = detail.manualEntries.value.drop(1) }
            compose.waitUntil(5_000) { compose.waitForIdle(); detail.manualProjection.result?.source === detail.manualEntries.value }
            assertEquals(before, compose.onAllNodesWithText("Needle duplicate")[0].getUnclippedBoundsInRoot())
            assertEquals(requests, fixture.requests())
        } }
    }

    @Test fun delayedProjectionKeepsPendingOccurrenceAcrossSourceSupersessionAndDisposal() {
        val background = TestCoroutineScheduler()
        Fixture().use { fixture -> host(fixture, background) { host ->
            query("mira", background)
            compose.onNode(hasScrollToIndexAction()).performScrollToKey("entry-20")
            val before = compose.onAllNodesWithText("Needle duplicate")[0].getUnclippedBoundsInRoot()
            val detail = host.memory.detail("fixture", 0)
            compose.runOnIdle { detail.manualEntries.value = detail.manualEntries.value.drop(1) }
            compose.waitForIdle()
            val anchor = checkNotNull(detail.pendingFilteredAnchor)
            assertEquals("entry-20", anchor.second.key)
            compose.onNodeWithTag("playlist-filter-result").assertDoesNotExist()
            compose.runOnIdle { detail.manualEntries.value = detail.manualEntries.value.drop(1) }
            compose.waitForIdle()
            assertSame(anchor, detail.pendingFilteredAnchor)
            compose.runOnIdle { host.shown.value = false }; compose.waitForIdle()
            compose.runOnIdle { host.shown.value = true }; compose.waitForIdle()
            assertSame(anchor, detail.pendingFilteredAnchor)
            background.runCurrent(); compose.waitForIdle()
            assertNull(detail.pendingFilteredAnchor)
            assertSame(detail.manualEntries.value, detail.manualProjection.result!!.source)
            assertEquals(before, compose.onAllNodesWithText("Needle duplicate")[0].getUnclippedBoundsInRoot())
            assertTrue(host.accepted.isEmpty())
            assertEquals(1, fixture.requests().count { it.contains("/entries") })
        } }
    }

    @Test fun retiredQueryCannotPublishAfterQueryAba() {
        val background = TestCoroutineScheduler()
        Fixture().use { fixture -> host(fixture, background) { host ->
            query("needle", background)
            compose.onNodeWithTag("playlist-filter").performTextReplacement("missing")
            compose.waitForIdle()
            compose.onNodeWithTag("playlist-filter").performTextReplacement("needle")
            compose.waitForIdle()
            background.runCurrent(); compose.waitForIdle()
            compose.onAllNodesWithText("Needle duplicate").assertCountEquals(2)
            compose.onNodeWithText("Filtered to 0 songs").assertDoesNotExist()
            assertEquals("needle", host.memory.detail("fixture", 0).manualProjection.result!!.query)
            assertTrue(host.accepted.isEmpty())
        } }
    }

    @Test fun observedImeDismissalEndsEditingWithoutConsumingAnotherBack() {
        val value = mutableStateOf("needle")
        var view: View? = null
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            MaterialTheme { PlaylistFilterField(value.value) { value.value = it } }
        }
        fun ime(visible: Boolean) {
            ViewCompat.dispatchApplyWindowInsets(checkNotNull(view), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), if (visible) Insets.of(0, 0, 0, 300) else Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), visible).build())
        }
        val field = compose.onNodeWithTag("playlist-filter")
        field.performClick().assertIsFocused()
        compose.runOnIdle { ime(true) }
        field.assertIsFocused()
        compose.runOnIdle { ime(false) }
        field.assertIsNotFocused().assertTextEquals("needle")
        field.performClick().assertIsFocused()
    }

    @Test fun clearingRestoresUnfilteredOccurrenceAnchorWithoutPaging() {
        Fixture().use { fixture -> host(fixture) { host ->
            compose.onNode(hasScrollToIndexAction()).performScrollToKey("entry-20")
            val before = compose.onAllNodesWithText("Needle duplicate")[0].getUnclippedBoundsInRoot()
            val requests = fixture.requests()
            query("needle")
            compose.onNodeWithContentDescription("Clear playlist filter").performClick()
            compose.waitForIdle()
            assertEquals(before, compose.onAllNodesWithText("Needle duplicate")[0].getUnclippedBoundsInRoot())
            assertEquals(requests, fixture.requests())
            assertTrue(host.accepted.isEmpty())
        } }
    }

    @Test fun heldFilteredRowCannotBorrowSourceAfterSessionInvalidation() {
        Fixture().use { fixture -> host(fixture) { host ->
            query("needle")
            val held = compose.onAllNodesWithText("Needle duplicate")[1].fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { runBlocking { fixture.client.logout() }; held() }
            assertTrue(host.accepted.isEmpty())
        } }
    }

    @Test fun compactFieldAndClearStay48dpAt320dpFont2AndLongUnicodeDoesNotClipTargets() {
        val value = mutableStateOf("Café 音楽 🌙 ".repeat(30))
        var density = 1f
        compose.setContent {
            val current = LocalDensity.current
            density = current.density
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface(Modifier.width(320.dp).testTag("filter-host")) { PlaylistFilterField(value.value) { value.value = it } }
                }
            }
        }
        val field = compose.onNodeWithTag("playlist-filter").fetchSemanticsNode().boundsInRoot
        val clear = compose.onNodeWithContentDescription("Clear playlist filter").fetchSemanticsNode().boundsInRoot
        assertTrue(field.height >= 48 * density)
        assertEquals(48 * density, clear.width, .5f)
        assertEquals(48 * density, clear.height, .5f)
        assertTrue(clear.top >= field.top && clear.bottom <= field.bottom)
        capture("filter-320-font2")
    }

    private fun capture(name: String) {
        val root = System.getenv("SPOFIFY_FILTER_RENDER_DIR") ?: return
        File(root, "$name.png").outputStream().use { output ->
            check(compose.onNodeWithTag("filter-host").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
