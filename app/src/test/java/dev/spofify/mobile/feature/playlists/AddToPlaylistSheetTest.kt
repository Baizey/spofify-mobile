package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.LibraryItem
import java.io.IOException
import dev.spofify.mobile.core.PersonalCollectionsApi
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            state.loadTargets(40)
            until { !state.busy }
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
            state.loadTargets(0)
            until { !state.busy }
            assertNull(state.targets)
            assertFalse(state.canAdd)
            assertEquals(2, web.requestCount)
        }
    }
}
