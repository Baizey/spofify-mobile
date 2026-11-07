package dev.spofify.mobile.feature.playlists

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.LibraryItem
import java.io.IOException
import dev.spofify.mobile.core.PersonalCollection
import dev.spofify.mobile.core.PersonalCollectionsApi
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production modal/finite lazy viewport, not direct state.more() as a substitute for scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class AddToPlaylistSheetHostTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    @Test fun visibleFrontierRetriesSameOffsetAndAppendRetainsChosenTarget() = host(uncertainCreate = false)
    @Test fun verificationFrontierIsReadOnlyAfterLostCreateAndCannotRestoreWriteAuthority() = host(uncertainCreate = true)

    @Test fun idlePlaylistRevisionEmissionDisablesRetainedTargetAndOffersRefresh() {
        MockWebServer().use { web ->
            web.start()
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            val shown = mutableStateOf(true)
            try {
                web.enqueue(json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                    .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600"))
                runBlocking { client.login("listener", "synthetic") }
                web.enqueue(json("""{"items":[${summary("p0", 7)}],"total":1,"limit":40,"offset":0}"""))
                compose.setContent { MaterialTheme {
                    if (shown.value) AddToPlaylistSheet(client, LibraryItem("track", "t", "Song"), { shown.value = false })
                } }
                waitFor("Playlist p0")
                compose.waitForIdle()
                compose.onNodeWithText("Add").assertIsEnabled()
                val before = client.personalRevision.value.playlists

                // No click, scroll, draft edit or explicit freshness helper may make this pass.
                // The production host must observe this emission while otherwise idle.
                compose.runOnIdle { client.notifyPersonalChange(PersonalCollection.PLAYLISTS) }
                assertEquals(before + 1, client.personalRevision.value.playlists)
                waitFor("Playlists changed.")
                compose.onNodeWithText("Add").assertIsNotEnabled()
                compose.onNode(hasText("Playlist p0") and hasClickAction()).assertIsNotEnabled()
                compose.onNodeWithText("Refresh playlists").assertExists()
                assertEquals("Invalidation neither writes nor automatically resets the chooser", 2, web.requestCount)

                web.enqueue(json("""{"items":[${summary("p1", 8)}],"total":1,"limit":40,"offset":0}"""))
                compose.onNodeWithText("Refresh playlists").performClick()
                waitFor("Playlist p1")
                compose.onNodeWithText("Add").assertIsNotEnabled()
                compose.onNode(hasText("Playlist p1") and hasClickAction()).performClick()
                compose.onNodeWithText("Add").assertIsEnabled()
                assertEquals(3, web.requestCount)
                assertEquals("POST", web.takeRequest(1, TimeUnit.SECONDS)!!.method) // Synthetic login only.
                repeat(2) { assertEquals("GET", web.takeRequest(1, TimeUnit.SECONDS)!!.method) }
            } finally {
                compose.runOnIdle { shown.value = false }; compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun host(uncertainCreate: Boolean) {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val failAppend = AtomicBoolean(!uncertainCreate)
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    requests += request.method!! to url.queryParameter("offset")
                    if (request.method == "POST") return if (uncertainCreate) json("{}", 503)
                        else json(summary("p2", 8))
                    val offset = url.queryParameter("offset")!!.toInt()
                    if (offset == 40 && failAppend.compareAndSet(true, false)) return json("{}", 503)
                    val items = (offset until minOf(offset + 40, 41)).joinToString(",") { summary("p$it", 7) }
                    return json("""{"items":[$items],"total":41,"limit":40,"offset":$offset}""")
                }
            }
            web.start()
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            val shown = mutableStateOf(true)
            try {
                compose.setContent { MaterialTheme {
                    if (shown.value) AddToPlaylistSheet(client, LibraryItem("track", "t", "Song"), { shown.value = false })
                } }
                waitFor("Playlist p2")
                assertEquals(listOf("0"), requests.filter { it.first == "GET" }.map { it.second })
                if (uncertainCreate) {
                    compose.onNodeWithText("New playlist").performClick()
                    compose.onNodeWithText("Name").performTextInput("Retained draft")
                    compose.onNodeWithText("Create").assertIsDisplayed().performClick()
                    waitFor("Review")
                    compose.onNodeWithText("Review").assertIsDisplayed().performClick()
                    waitFor("Playlist p0", scroll = true)
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Playlist p39"))
                    waitFor("Playlist p40", scroll = true)
                    // Blocked writes remove the create action altogether, but retain the disabled draft.
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Retained draft"))
                    compose.onNodeWithText("Retained draft").assert(hasNoClickAction())
                    compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                    compose.onNodeWithText("Create").assertDoesNotExist()
                    compose.onNodeWithText("Add").assertDoesNotExist()
                    assertEquals(1, requests.count { it.first == "POST" })
                    assertEquals(listOf("0", "0", "40"), requests.filter { it.first == "GET" }.map { it.second })
                } else {
                    compose.onNode(hasText("Playlist p2") and hasClickAction()).performClick()
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Playlist p39"))
                    waitFor("Retry")
                    compose.waitForIdle()
                    assertEquals(listOf("0", "40"), requests.filter { it.first == "GET" }.map { it.second })
                    compose.onNodeWithText("Retry").performScrollTo().performClick()
                    waitFor("Playlist p40", scroll = true)
                    assertEquals(listOf("0", "40", "40"), requests.filter { it.first == "GET" }.map { it.second })
                    compose.onNodeWithText("Add").performClick()
                    compose.waitUntil(5_000) { requests.any { it.first == "POST" } }
                    val add = (0 until web.requestCount).map { web.takeRequest(1, TimeUnit.SECONDS)!! }.last()
                    assertEquals("/api/me/playlists/p2/entries", add.path)
                    assertTrue(add.body.readUtf8().contains("\"expectedRevision\":7"))
                }
            } finally {
                compose.runOnIdle { shown.value = false }; compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun waitFor(text: String, scroll: Boolean = false) {
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            try {
                if (scroll) compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            } catch (_: AssertionError) { false }
        }
    }
    private fun summary(id: String, revision: Int) =
        """{"id":"$id","name":"Playlist $id","kind":"manual","trackCount":0,"availableCount":0,"revision":$revision,"capabilities":{"addTracks":true}}"""
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddToPlaylistSheetTest {
    private val track = LibraryItem(kind = "track", id = "t", name = "Song")
    private class EmptyStore : SessionStore {
        override fun read(): StoredSession? = null
        override fun write(value: StoredSession?) = Unit
    }
    private fun TestScope.state(web: MockWebServer) = AddToPlaylistState(
        PersonalCollectionsApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())), track, this,
    )
    private fun response(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun summary(id: String = "p", revision: Int = 7, count: Int = 0) =
        """{"id":"$id","name":"Playlist $id","description":"","kind":"manual","trackCount":$count,"availableCount":$count,"revision":$revision,"capabilities":{"addTracks":true}}"""
    private fun page(items: List<String>, total: Int = items.size, offset: Int = 0) =
        """{"items":[${items.joinToString(",")}],"total":$total,"limit":40,"offset":$offset}"""
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue("Timed out waiting for sheet operation", condition())
    }

    @Test fun refreshPreservesIntendedSelectionButNeverAuthorizesAnOldDto() = runTest {
        MockWebServer().use { web ->
            web.start()
            var revision = 0L
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            val state = AddToPlaylistState(PersonalCollectionsApi(client), track, this, personalRevision = { revision })
            for (rev in listOf(7, 9)) for (offset in listOf(0, 40)) {
                web.enqueue(response(page((offset until offset + 40).map { summary("p$it", rev) }, 81, offset)))
            }
            state.loadTargets(0); until { !state.busy }
            state.moreTargets(); until { !state.busy }; state.select("p43")
            state.name = "Retained draft"; state.query = "p43"
            revision++; state.checkFreshness(revision)
            state.refreshTargets()
            assertEquals(80, state.targets!!.items.size) // Pending refresh keeps the viewport, not old authority.
            assertNull(state.selected); assertFalse(state.canAdd)
            until { !state.busy }
            assertNull(state.selected); assertFalse(state.canAdd)
            assertEquals(40, state.targets!!.items.size)
            state.moreTargets(); until { !state.busy }
            assertEquals("p43", state.selected!!.id)
            assertEquals(9L, state.selected!!.revision)
            assertTrue(state.canAdd)
            assertEquals("Retained draft", state.name); assertEquals("p43", state.query)
            assertEquals(4, web.requestCount)
        }
    }

    @Test fun malformedOrOverlappingPagesCannotEstablishCompleteSearchCoverage() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page((0 until 40).map { summary("p$it") }, 41)))
            web.enqueue(response(page(listOf(summary("p39")), 41, 40)))
            val state = state(web)
            state.loadTargets(0); until { !state.busy }; state.select("p3")
            state.query = "Missing"
            state.moreTargets(); until { !state.busy }
            assertNotNull(state.targetError)
            assertFalse(state.targetsEnded)
            assertTrue(state.presentation.searchIncomplete)
            assertEquals("p3", state.selected!!.id)
            assertEquals(40, state.targets!!.items.size)
            state.moreTargets(); assertEquals(2, web.requestCount)
        }
    }

    @Test fun retainedSummaryBytesAreBoundedWithoutTurningAPrefixIntoCompleteResults() = runTest {
        MockWebServer().use { web ->
            web.start()
            for (offset in 0..80 step 40) {
                val items = (offset until offset + 40).map { summary("p$it").replace("Playlist p$it", "x".repeat(200 * 1024)) }
                web.enqueue(response(page(items, 120, offset)))
            }
            val state = state(web)
            state.loadTargets(0); until { !state.busy }
            state.moreTargets(); until { !state.busy }
            assertEquals(80, state.targets!!.items.size)
            state.moreTargets(); until { !state.busy }
            assertEquals(80, state.targets!!.items.size)
            assertNotNull(state.targetError)
            assertFalse(state.targetsEnded)
            assertEquals(80, state.targetOffset)
            assertEquals(3, web.requestCount)
        }
    }

    @Test fun searchContinuesPast100TargetsWithoutClaimingCompletePrefixCoverage() = runTest {
        MockWebServer().use { web ->
            web.start()
            for (offset in 0..120 step 40) {
                web.enqueue(response(page((offset until minOf(offset + 40, 121)).map { summary("p$it") }, 121, offset)))
            }
            val state = state(web)
            state.loadTargets(0); until { !state.busy }; state.select("p3")
            state.query = "  P120  "
            assertTrue(state.presentation.targets.isEmpty())
            assertTrue(state.presentation.searchIncomplete)
            assertEquals("Playlist p3", state.presentation.hiddenSelection)
            assertEquals(1, web.requestCount) // Local matching does not invent a server search API.
            repeat(3) { state.moreTargets(); until { !state.busy } }
            assertEquals(listOf("p120"), state.presentation.targets.map { it.id })
            assertFalse(state.presentation.searchIncomplete)
            assertEquals("p3", state.selected!!.id)
            assertEquals(121, state.targets!!.items.size)
            assertEquals(listOf("0", "40", "80", "120"), (0 until 4).map {
                val request = web.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(setOf("kind", "limit", "offset"), request.requestUrl!!.queryParameterNames)
                request.requestUrl!!.queryParameter("offset")
            })
            state.query = "Absent"
            assertTrue(state.presentation.targets.isEmpty())
            assertFalse(state.presentation.searchIncomplete)
            state.query = ""
            assertEquals(121, state.presentation.targets.size)
            assertEquals(4, web.requestCount)
        }
    }

    @Test fun cancelledCreationRetainsDraftAndTargetAndNeverWrites() = runTest {
        MockWebServer().use { web ->
            web.start(); web.enqueue(response(page(listOf(summary()))))
            val state = state(web)
            state.loadTargets(0); until { !state.busy }
            state.beginCreate(); state.name = "Draft"; state.description = "Retained"
            state.cancelCreate()
            assertFalse(state.creating)
            assertEquals("p", state.selected!!.id)
            assertTrue(state.canAdd)
            state.beginCreate()
            assertEquals("Draft", state.name)
            assertEquals("Retained", state.description)
            assertEquals("", state.message)
            assertEquals(1, web.requestCount)
        }
    }

    @Test fun disposalOrAccountReplacementBeforeDispatchPreventsReservedCreateAndRead() = runTest {
        for (dispose in listOf(true, false)) MockWebServer().use { web ->
            web.start()
            var session: Any = Any()
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            val state = AddToPlaylistState(PersonalCollectionsApi(client), track, this, currentSession = { session })
            state.beginCreate(); state.name = "Draft"; state.create()
            assertTrue(state.mutating)
            if (dispose) state.dispose() else session = Any()
            runCurrent()
            assertFalse(state.busy)
            assertFalse(state.canAdd)
            assertNull(state.selected)
            assertEquals(0, web.requestCount)
            val readState = AddToPlaylistState(PersonalCollectionsApi(client), track, this, currentSession = { session })
            readState.loadTargets(0)
            if (dispose) readState.dispose() else session = Any()
            runCurrent()
            assertFalse(readState.busy)
            assertNull(readState.targets)
            assertEquals(0, web.requestCount)
        }
    }

    @Test fun lateCreateAfterDisposalOrAccountReplacementCannotPublishOrAdd() = runTest {
        for (dispose in listOf(true, false)) {
            val release = CountDownLatch(1)
            MockWebServer().use { web ->
                web.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        release.await(5, TimeUnit.SECONDS)
                        return response(summary("late"), 201)
                    }
                }
                web.start()
                try {
                    var session: Any = Any()
                    val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
                    val state = AddToPlaylistState(PersonalCollectionsApi(client), track, this, currentSession = { session })
                    state.beginCreate(); state.name = "Draft"; state.create()
                    until { web.requestCount == 1 }
                    if (dispose) state.dispose() else session = Any()
                    release.countDown(); until { !state.busy }
                    assertNull(state.targets); assertNull(state.selected); assertFalse(state.finished)
                    assertFalse(state.canAdd); assertFalse(state.createdTarget)
                    state.add(); state.create(); state.verify()
                    assertEquals(1, web.requestCount)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun createdTargetDoesNotBypassLaterPersonalRevisionFence() = runTest {
        MockWebServer().use { web ->
            web.start(); web.enqueue(response(summary("new"), 201))
            var revision = 0L
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            val state = AddToPlaylistState(PersonalCollectionsApi(client), track, this, personalRevision = { revision })
            state.beginCreate(); state.name = "New"; state.create(); until { !state.busy }
            assertTrue(state.canAdd)
            revision++; state.checkFreshness(revision)
            assertTrue(state.targetsStale); assertFalse(state.canAdd)
            state.add(); assertEquals(1, web.requestCount)
            web.enqueue(response(page(listOf(summary("new", revision = 9)))))
            state.refreshTargets(); until { !state.busy }
            assertFalse(state.createdTarget); assertTrue(state.canAdd)
            assertEquals(9L, state.selected!!.revision)
        }
    }

    @Test fun personalRevisionChangeAfterAddReservationPreventsDispatch() = runTest {
        MockWebServer().use { web ->
            web.start(); web.enqueue(response(page(listOf(summary()))))
            var revision = 0L
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            val state = AddToPlaylistState(PersonalCollectionsApi(client), track, this, personalRevision = { revision })
            state.loadTargets(0); until { !state.busy }
            state.add(); revision++; runCurrent()
            assertEquals(1, web.requestCount)
            assertNotNull(state.blocked); assertFalse(state.blocked!!.uncertain)
            assertEquals("Playlist changed.", state.message)
            assertFalse(state.canAdd)
        }
    }

    @Test fun readFailuresRetainActualAccountPermissionAndUnavailableStates() = runTest {
        for ((status, message) in listOf(401 to "Session expired. Sign in again.", 403 to "Permission denied.",
            404 to "Playlist unavailable.", 429 to "Too many requests. Try later.")) {
            MockWebServer().use { web ->
                web.start(); web.enqueue(response("{\"private\":\"backend detail\"}", status))
                val state = state(web)
                state.loadTargets(0); until { !state.busy }
                assertEquals(message, state.targetError)
                assertNull(state.targets); assertFalse(state.canAdd)
            }
        }
    }

    @Test fun recoveryDecisionsBlockEveryUncertainOutcomeAndConflict() {
        for (status in listOf(200, 201, 204, 302, 408, 500, 502, 503)) {
            assertEquals("HTTP $status", AddWriteRecovery.UNCERTAIN, addWriteRecovery(ApiException(status, "Ignored")))
        }
        assertEquals(AddWriteRecovery.UNCERTAIN, addWriteRecovery(IOException("Private transport detail")))
        assertEquals(AddWriteRecovery.UNCERTAIN, addWriteRecovery(IllegalStateException("Invalid saved DTO")))
        assertEquals(AddWriteRecovery.CONFLICT, addWriteRecovery(ApiException(409, "Ignored", "REVISION_CONFLICT")))
        assertEquals(AddWriteRecovery.CONFLICT, addWriteRecovery(ApiException(409, "Ignored", "PLAYLIST_KIND_MISMATCH")))
        for (status in listOf(400, 401, 403, 404, 413, 422, 429)) {
            assertEquals("HTTP $status", AddWriteRecovery.REJECTED, addWriteRecovery(ApiException(status, "Ignored")))
        }
    }

    @Test fun targetsAreServerFilteredAndPagedAndAddUsesTheSelectedRevision() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page((0 until 40).map { summary("p$it") }, total = 41)))
            web.enqueue(response(page(listOf(summary("last", revision = 12)), total = 41, offset = 40)))
            web.enqueue(response(summary("last", revision = 13, count = 1)))
            val state = state(web)
            state.loadTargets(0)
            until { !state.busy }
            val first = web.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("manual", first.requestUrl!!.queryParameter("kind"))
            assertEquals("40", first.requestUrl!!.queryParameter("limit"))
            assertEquals("0", first.requestUrl!!.queryParameter("offset"))
            state.select("p2")
            state.moreTargets(); state.moreTargets()
            until { !state.busy }
            assertEquals("p2", state.selected!!.id)
            assertEquals(41, state.targets!!.items.size)
            state.select("last")
            assertEquals("last", state.selected!!.id)
            assertEquals("40", web.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("offset"))
            state.add()
            state.add() // Synchronous reservation prevents a second tap from racing.
            until { !state.busy }
            val add = web.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/me/playlists/last/entries", add.path)
            assertEquals("POST", add.method)
            assertTrue(add.body.readUtf8().contains("\"expectedRevision\":12"))
            assertTrue(state.finished)
            assertEquals(3, web.requestCount)
        }
    }

    @Test fun failedAppendLatchesExactRawOffsetAndKeepsSelectionUntilExplicitRetry() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page((0 until 40).map { summary("p$it") }, total = 81)))
            web.enqueue(response("{}", 503))
            web.enqueue(response(page((40 until 80).map { summary("p$it") }, total = 81, offset = 40)))
            web.enqueue(response(page(listOf(summary("p80")), total = 81, offset = 80)))
            val state = state(web)
            state.loadTargets(0); until { !state.busy }; state.select("p3")
            state.moreTargets(); state.moreTargets(); until { !state.busy }
            assertNotNull(state.targetError)
            repeat(5) { state.moreTargets(); state.loadTargets(40) }
            assertEquals(2, web.requestCount)
            assertEquals("p3", state.selected!!.id)
            assertEquals(40, state.targets!!.items.size)
            state.retryTargets()
            assertTrue(state.reading)
            assertNotNull(state.presentation.targetError)
            state.retryTargets(); until { !state.busy }
            assertEquals(80, state.targets!!.items.size)
            state.moreTargets(); until { !state.busy }
            assertTrue(state.targetsEnded)
            repeat(3) { state.moreTargets() }
            assertEquals(listOf("0", "40", "40", "80"), (0 until 4).map {
                web.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("offset")
            })
        }
    }

    @Test fun detectedPersonalChangeBlocksRetainedSelectionInsteadOfInventingSnapshotAuthority() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page((0 until 40).map { summary("p$it") }, total = 41)))
            var revision = 0L
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            val state = AddToPlaylistState(PersonalCollectionsApi(client), track, this, personalRevision = { revision })
            state.loadTargets(0); until { !state.busy }
            revision++
            assertFalse(state.canAdd)
            state.moreTargets(); until { !state.busy }
            assertTrue(state.targetsStale)
            assertEquals(40, state.targets!!.items.size)
            state.add()
            assertEquals(1, web.requestCount)
        }
    }

    @Test fun verificationAppendsReadOnlyAndRetryCannotReplayUncertainCreate() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response("{}", 503))
            web.enqueue(response(page((0 until 40).map { summary("p$it") }, total = 41)))
            web.enqueue(response("{}", 503))
            web.enqueue(response(page(listOf(summary("p40")), total = 41, offset = 40)))
            val state = state(web)
            state.beginCreate(); state.name = "Retained draft"; state.create(); until { !state.busy }
            val blocked = state.blocked
            state.verify(); until { !state.busy }
            state.moreVerification(); state.moreVerification(); until { !state.busy }
            repeat(4) { state.moreVerification(); state.add(); state.create() }
            assertEquals(3, web.requestCount)
            assertEquals(40, state.verifiedTargets!!.items.size)
            state.retryVerification()
            assertTrue(state.reading)
            assertNotNull(state.presentation.reviewError)
            state.retryVerification(); until { !state.busy }
            assertEquals(41, state.verifiedTargets!!.items.size)
            assertTrue(state.verificationEnded)
            assertEquals(blocked, state.blocked)
            assertFalse(state.canAdd)
            assertEquals("Retained draft", state.name)
            assertEquals(listOf("POST", "GET", "GET", "GET"), (0 until 4).map { web.takeRequest(1, TimeUnit.SECONDS)!!.method })
        }
    }

    @Test fun createRequiresASeparateExplicitAddAndTrimsButRetainsDraft() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(summary("new", revision = 1), 201))
            web.enqueue(response(summary("new", revision = 2, count = 1)))
            val state = state(web)
            state.beginCreate()
            state.query = "An earlier search"
            state.name = "  New mix  "
            state.description = "  Description  "
            state.create()
            state.create()
            until { !state.busy }
            assertEquals(1, web.requestCount)
            assertFalse(state.finished)
            assertTrue(state.canAdd)
            assertEquals("  New mix  ", state.name)
            assertEquals(listOf("new"), state.presentation.targets.map { it.id })
            assertFalse(state.presentation.searchIncomplete)
            assertFalse(state.presentation.searchable)
            val create = web.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/me/playlists", create.path)
            val body = create.body.readUtf8()
            assertTrue(body.contains("\"name\":\"New mix\""))
            assertTrue(body.contains("\"description\":\"Description\""))
            assertFalse(body.contains("\"kind\""))
            state.add()
            until { !state.busy }
            assertTrue(state.finished)
            assertEquals("/api/me/playlists/new/entries", web.takeRequest(1, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test fun uncertainCreatePreservesDraftAndVerificationNeverUnblocksOrAdds() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response("""{"private":"server detail"}""", 503))
            web.enqueue(response(page(listOf(summary("maybe-created")))))
            val state = state(web)
            state.beginCreate()
            state.name = "My draft"
            state.description = "Keep me"
            state.create()
            until { !state.busy }
            assertNotNull(state.blocked)
            assertTrue(state.creating)
            assertEquals("My draft", state.name)
            assertEquals("Keep me", state.description)
            assertFalse(state.message.contains("server detail"))
            state.create(); state.add(); state.loadTargets(0)
            assertEquals(1, web.requestCount)
            state.verify()
            until { !state.busy }
            assertEquals(1, state.verifiedTargets!!.items.size)
            assertEquals("Create unconfirmed.", state.message)
            assertNotNull(state.blocked)
            state.create(); state.add()
            assertEquals(2, web.requestCount)
            web.takeRequest(1, TimeUnit.SECONDS) // The original create.
            assertEquals("manual", web.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("kind"))
        }
    }

    @Test fun lostAddCanCountSparseOccurrencesButCannotInferWhetherLastAddSucceeded() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page(listOf(summary()))))
            web.enqueue(response("{}", 500))
            web.enqueue(response(summary(count = 2)))
            web.enqueue(response("""{"items":[{"id":"e1","position":0,"track":{"kind":"track","id":"t","name":"Song"}},{"id":"e2","position":4,"track":{"kind":"track","id":"t","name":"Song"}}],"total":2,"limit":100,"offset":0}"""))
            web.enqueue(response(summary(count = 2)))
            val state = state(web)
            state.loadTargets(0)
            until { !state.busy }
            state.add()
            until { !state.busy }
            assertTrue(state.blocked!!.uncertain)
            state.add()
            state.verify()
            until { !state.busy }
            assertEquals(2, state.reviewCount)
            assertEquals("Add unconfirmed.", state.message)
            assertFalse(state.canAdd)
            state.add()
            assertEquals(5, web.requestCount)
        }
    }

    @Test fun lostAddBeyond1000VerifiesCompleteOrderWithoutRestoringWriteAuthority() = runTest {
        MockWebServer().use { web ->
            web.start()
            val count = 1201
            web.enqueue(response(page(listOf(summary(count = count - 1)))))
            web.enqueue(response("{}", 503))
            web.enqueue(response(summary(revision = 8, count = count)))
            for (offset in 0 until count step 100) {
                val entries = (offset until minOf(offset + 100, count)).joinToString(",") {
                    """{"id":"e$it","position":$it,"track":{"kind":"track","id":"t","name":"Song"}}"""
                }
                web.enqueue(response("""{"items":[$entries],"total":$count,"limit":100,"offset":$offset}"""))
            }
            web.enqueue(response(summary(revision = 8, count = count)))
            val state = state(web)
            state.loadTargets(0); until { !state.busy }
            state.add(); until { !state.busy }
            state.verify(); until { !state.busy }
            assertEquals(1201, state.reviewCount)
            assertEquals("Add unconfirmed.", state.message)
            assertNotNull(state.blocked)
            assertFalse(state.canAdd)
            repeat(3) { state.add(); state.create() }
            val requests = (0 until web.requestCount).map { web.takeRequest(1, TimeUnit.SECONDS)!! }
            assertEquals(1, requests.count { it.method == "POST" })
            assertEquals((0 until count step 100).map { it.toString() }, requests.mapNotNull { it.requestUrl!!.queryParameter("offset").takeIf { _ -> it.path!!.contains("/entries?") } })
            assertEquals(17, requests.size)
        }
    }

    @Test fun verificationRejectsAggregateEntryBytesWithoutReportingAPrefixOrReplaying() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page(listOf(summary(count = 50)))))
            web.enqueue(response("{}", 503))
            web.enqueue(response(summary(revision = 8, count = 50)))
            for (offset in 0 until 40 step 10) {
                val entries = (offset until offset + 10).joinToString(",") {
                    """{"id":"e$it","position":$it,"track":{"kind":"track","id":"t","name":"${"x".repeat(512 * 1024)}"}}"""
                }
                web.enqueue(response("""{"items":[$entries],"total":50,"limit":100,"offset":$offset}"""))
            }
            val state = state(web)
            state.loadTargets(0); until { !state.busy }
            state.add(); until { !state.busy }
            state.verify(); until { !state.busy }
            assertNotNull(state.blocked)
            assertFalse(state.canAdd)
            assertNull(state.reviewCount)
            assertEquals("Add unconfirmed.", state.message)
            state.add(); state.create()
            val requests = (0 until web.requestCount).map { web.takeRequest(1, TimeUnit.SECONDS)!! }
            assertEquals(1, requests.count { it.method == "POST" })
            assertEquals(7, requests.size)
        }
    }

    @Test fun conflictRequiresReadAndReviewWithoutRetryingWithFreshRevision() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page(listOf(summary()))))
            web.enqueue(response("""{"error":{"code":"REVISION_CONFLICT","message":"private diagnostic"}}""", 409))
            web.enqueue(response(summary(revision = 10)))
            val state = state(web)
            state.loadTargets(0)
            until { !state.busy }
            state.add()
            until { !state.busy }
            assertFalse(state.blocked!!.uncertain)
            assertFalse(state.message.contains("private diagnostic"))
            state.verify()
            until { !state.busy }
            assertEquals("Playlist changed.", state.message)
            assertEquals(10L, state.reviewedTarget!!.revision)
            assertFalse(state.canAdd)
            state.add()
            assertEquals(3, web.requestCount)
        }
    }

    @Test fun pendingMutationIsReservedUntilResponseAndDefiniteRejectionAllowsExplicitRetry() = runTest {
        val release = CountDownLatch(1)
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    release.await(5, TimeUnit.SECONDS)
                    return response("{}", 422)
                }
            }
            web.start()
            try {
                val state = state(web)
                state.beginCreate()
                state.name = "Mix"
                state.create()
                assertTrue(state.mutating)
                state.create(); state.add(); state.verify()
                runCurrent()
                until { web.requestCount == 1 }
                assertTrue(state.mutating)
                release.countDown()
                until { !state.busy }
                assertNull(state.blocked)
                assertEquals("Mix", state.name)
                state.create()
                until { !state.busy }
                assertEquals(2, web.requestCount)
            } finally { release.countDown() }
        }
    }

    @Test fun malformedSuccessfulWriteResponseIsUncertainAndNeverAutomaticallyReplayed() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response("{}", 201))
            val state = state(web)
            state.beginCreate()
            state.name = "Mix"
            state.create()
            until { !state.busy }
            assertTrue(state.blocked!!.uncertain)
            state.create()
            assertEquals(1, web.requestCount)
        }
    }

    @Test fun malformedSuccessfulAddResponseBlocksExplicitReplayToo() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page(listOf(summary()))))
            web.enqueue(response("{}", 200))
            val state = state(web)
            state.loadTargets(0)
            until { !state.busy }
            state.add()
            until { !state.busy }
            assertTrue(state.blocked!!.uncertain)
            assertFalse(state.finished)
            assertFalse(state.canAdd)
            state.add(); state.beginCreate(); state.create()
            assertEquals(2, web.requestCount)
        }
    }

    @Test fun disconnectedCreateResponseIsNeverRetriedAndRetainsDraft() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val state = state(web)
            state.beginCreate()
            state.name = "Do not duplicate"
            state.create()
            until { !state.busy }
            assertTrue(state.blocked!!.uncertain)
            assertEquals("Do not duplicate", state.name)
            state.create(); state.add()
            assertEquals(1, web.requestCount)
        }
    }

    @Test fun metadataLimitsCountUnicodeCodePointsAndInvalidDraftsNeverWrite() = runTest {
        MockWebServer().use { web ->
            web.start()
            val state = state(web)
            state.beginCreate()
            state.name = "😀".repeat(101)
            state.create()
            assertEquals(0, web.requestCount)
            state.name = "😀".repeat(100)
            state.description = "x".repeat(501)
            state.create()
            assertEquals(0, web.requestCount)
            state.description = "Valid"
            web.enqueue(response(summary(), 201))
            state.create()
            until { !state.busy }
            assertEquals(1, web.requestCount)
            assertTrue(state.canAdd)
        }
    }

    @Test fun invalidOrNonAddableTargetPagesFailClosedRatherThanClientFiltering() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page(listOf(summary().replace("\"addTracks\":true", "\"addTracks\":false")))))
            web.enqueue(response(page(listOf("""{"id":"rules","name":"Rules","kind":"derived","countState":"unevaluated","trackCount":null,"availableCount":null,"revision":1,"capabilities":{"addTracks":false}}"""))))
            val state = state(web)
            state.loadTargets(0)
            until { !state.busy }
            assertNull(state.targets)
            assertFalse(state.canAdd)
            state.refreshTargets()
            until { !state.busy }
            assertNull(state.targets)
            assertFalse(state.canAdd)
            assertEquals(2, web.requestCount)
        }
    }
}
