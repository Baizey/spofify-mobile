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
                compose.onNodeWithText("Add track").assertIsEnabled()
                val before = client.personalRevision.value.playlists

                // No click, scroll, draft edit or explicit freshness helper may make this pass.
                // The production host must observe this emission while otherwise idle.
                compose.runOnIdle { client.notifyPersonalChange(PersonalCollection.PLAYLISTS) }
                assertEquals(before + 1, client.personalRevision.value.playlists)
                waitFor("Playlists changed. Refresh before choosing a target.")
                compose.onNodeWithText("Add track").assertIsNotEnabled()
                compose.onNode(hasText("Playlist p0") and hasClickAction()).assertIsNotEnabled()
                compose.onNodeWithText("Refresh playlists").assertExists()
                assertEquals("Invalidation neither writes nor automatically resets the chooser", 2, web.requestCount)

                web.enqueue(json("""{"items":[${summary("p1", 8)}],"total":1,"limit":40,"offset":0}"""))
                compose.onNodeWithText("Refresh playlists").performClick()
                waitFor("Playlist p1")
                compose.onNodeWithText("Add track").assertIsEnabled()
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
                    compose.onNodeWithText("Create playlist").performClick()
                    compose.onNodeWithText("Name").performTextInput("Retained draft")
                    compose.onNodeWithText("Create manual playlist").performScrollTo().performClick()
                    waitFor("Verify saved playlists")
                    compose.onNodeWithText("Verify saved playlists").performScrollTo().performClick()
                    waitFor("Playlist p0", scroll = true)
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Playlist p39"))
                    waitFor("Playlist p40", scroll = true)
                    // Blocked writes remove the create action altogether, but retain the disabled draft.
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Retained draft"))
                    compose.onNodeWithText("Retained draft").assertIsNotEnabled()
                    compose.onNodeWithText("Create manual playlist").assertDoesNotExist()
                    compose.onNodeWithText("Add track").assertDoesNotExist()
                    assertEquals(1, requests.count { it.first == "POST" })
                    assertEquals(listOf("0", "0", "40"), requests.filter { it.first == "GET" }.map { it.second })
                } else {
                    compose.onNode(hasText("Playlist p2") and hasClickAction()).performClick()
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Playlist p39"))
                    waitFor("Retry loading playlists")
                    compose.waitForIdle()
                    assertEquals(listOf("0", "40"), requests.filter { it.first == "GET" }.map { it.second })
                    compose.onNodeWithText("Retry loading playlists").performScrollTo().performClick()
                    waitFor("Playlist p40", scroll = true)
                    assertEquals(listOf("0", "40", "40"), requests.filter { it.first == "GET" }.map { it.second })
                    compose.onNodeWithText("Add track").performClick()
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
            state.retryTargets(); state.retryTargets(); until { !state.busy }
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
            state.retryVerification(); state.retryVerification(); until { !state.busy }
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
            state.name = "  New mix  "
            state.description = "  Description  "
            state.create()
            state.create()
            until { !state.busy }
            assertEquals(1, web.requestCount)
            assertFalse(state.finished)
            assertTrue(state.canAdd)
            assertEquals("  New mix  ", state.name)
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
            assertTrue(state.message.contains("does not prove"))
            assertNotNull(state.blocked)
            state.create(); state.add()
            assertEquals(2, web.requestCount)
            web.takeRequest(1, TimeUnit.SECONDS) // The original create.
            assertEquals("manual", web.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("kind"))
        }
    }

    @Test fun lostAddCanCountOccurrencesButCannotInferWhetherLastAddSucceeded() = runTest {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(response(page(listOf(summary()))))
            web.enqueue(response("{}", 500))
            web.enqueue(response(summary(count = 2)))
            web.enqueue(response("""{"items":[{"id":"e1","position":0,"track":{"kind":"track","id":"t","name":"Song"}},{"id":"e2","position":1,"track":{"kind":"track","id":"t","name":"Song"}}],"total":2,"limit":100,"offset":0}"""))
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
            assertTrue(state.message.contains("2 occurrences"))
            assertTrue(state.message.contains("cannot prove whether the last add succeeded"))
            assertFalse(state.canAdd)
            state.add()
            assertEquals(5, web.requestCount)
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
            assertTrue(state.message.contains("Close and reopen"))
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
