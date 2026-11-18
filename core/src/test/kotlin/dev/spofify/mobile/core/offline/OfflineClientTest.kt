package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.origin.ServerOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OfflineClientTest {
    private class Sessions(var value: StoredSession?) : SessionStore {
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }

    private suspend fun fixture(block: suspend (MockWebServer, SessionRepository, AccountIdentity) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val origin = ServerOrigin.parse(server.url("/").toString())
            val summary = SessionSummary("account", "fixture-user", "user", "fixture-csrf")
            val cookie = "spofify_session=fixture-token; Path=/; HttpOnly; Expires=Tue, 01 Jan 2030 00:00:00 GMT"
            val transport = OkHttpAuthApi.defaultClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val sessions = SessionRepository(Sessions(StoredSession(origin.value, cookie, summary)), object : LoginStore {
                override fun read(): RememberedLogin? = null
                override fun write(value: RememberedLogin) = Unit
            }, AuthApiFactory { OkHttpAuthApi(it, transport) }, scope)
            server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"user","csrfToken":"fixture-csrf"}"""))
            try {
                sessions.start()
                val state = withTimeout(3000) { sessions.state.first { it is AuthState.Authenticated } } as AuthState.Authenticated
                server.takeRequest(2, TimeUnit.SECONDS)
                block(server, sessions, state.identity)
            } finally {
                scope.cancel(); scope.coroutineContext.job.join()
                transport.connectionPool.evictAll(); transport.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)
    private suspend fun fails(expected: OfflineFailure, block: suspend () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected sanitized offline failure, got $failure", failure is OfflineException)
        assertEquals(expected, (failure as OfflineException).failure)
        assertEquals("Offline request failed", failure.message)
        assertNull(failure.cause)
    }

    @Test fun completeManifestUsesOnlyDocumentedPaginationAndPreservesDuplicateTrackOccurrences() = runBlocking {
        fixture { server, sessions, identity ->
            server.enqueue(json(OfflineTestData.page(ids = listOf("one", "one"), total = 3, next = "of1_next")))
            server.enqueue(json(OfflineTestData.page(start = 2, ids = listOf("two"), total = 3)))
            val result = OfflineClient(sessions, identity, pageSize = 2).manifest(OfflineTestData.playlist)
            assertEquals(listOf("one", "one", "two"), result.items.map { it.track.id })
            assertEquals(listOf(0L, 1L, 2L), result.items.map { it.position })
            assertEquals(3L, result.recordedAudioTotal)
            repeat(2) { index ->
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("/api/me/offline/v1/playlists/list-one/manifest", request.requestUrl!!.encodedPath)
                assertEquals("2", request.requestUrl!!.queryParameter("limit"))
                assertEquals(if (index == 0) null else "of1_next", request.requestUrl!!.queryParameter("cursor"))
                assertEquals("spofify_session=fixture-token", request.getHeader("Cookie"))
                assertEquals(identity.origin, request.getHeader("Origin"))
                assertEquals("no-store", request.getHeader("Cache-Control"))
            }
        }
    }

    @Test fun albumArtistAndPreparationEscapeIdsOnceAndNeverFollowDownloadUrls() = runBlocking {
        fixture { server, sessions, identity ->
            val id = "a+日本:%"
            val api = OfflineClient(sessions, identity, pageSize = 2)
            for (kind in listOf(OfflineCollectionKind.ALBUM, OfflineCollectionKind.ARTIST)) {
                val ref = OfflineCollectionRef(kind, id)
                server.enqueue(json(OfflineTestData.page(ref)))
                assertEquals(ref, api.manifest(ref).collection.reference)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(listOf("api", "me", "offline", "v1", kind.path, id, "manifest"), request.requestUrl!!.pathSegments)
            }
            server.enqueue(json(OfflineTestData.media(id, "a+%E6%97%A5%E6%9C%AC:%25")))
            assertEquals(id, api.prepareMedia(id).trackId)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(listOf("api", "me", "offline", "v1", "tracks", id, "media"), request.requestUrl!!.pathSegments)
            assertNull(request.requestUrl!!.query)
            assertEquals(4, server.requestCount) // auth, two manifests, descriptor; NO audio download
        }
    }

    @Test fun cacheFillUsesOnlyAuthenticatedVersionedWholeBodyWithExactStrongEtagAndLength() = runBlocking {
        fixture { server, sessions, identity ->
            val id = "a+日本:%"
            server.enqueue(json(OfflineTestData.media(id, "a+%E6%97%A5%E6%9C%AC:%25")))
            val descriptor = OfflineClient(sessions, identity).prepareMedia(id)
            server.takeRequest()
            val tag = "\"${descriptor.mediaVersion}\""
            val body = "x".repeat(123)
            for ((etag, payload) in listOf(tag to body, "W/$tag" to body, tag to "short", "\"other\"" to body)) {
                server.enqueue(MockResponse().setHeader("ETag", etag).setBody(payload))
                val result = runCatching { sessions.versionedAudio(identity, descriptor).use { it.input.readBytes().size } }
                if (etag == tag && payload == body) assertEquals(123, result.getOrThrow()) else assertTrue(result.isFailure)
                val request = server.takeRequest()
                assertEquals(listOf("api", "me", "offline", "v1", "tracks", id, "audio"), request.requestUrl!!.pathSegments)
                assertEquals(descriptor.mediaVersion, request.requestUrl!!.queryParameter("version"))
                assertEquals("spofify_session=fixture-token", request.getHeader("Cookie"))
                assertEquals("identity", request.getHeader("Accept-Encoding")); assertNull(request.getHeader("Range"))
            }
        }
    }

    @Test fun changedFenceCountsOrMetadataDiscardTheEntireStagedSnapshot() = runBlocking {
        for (change in listOf("fence", "count", "revision")) fixture { server, sessions, identity ->
            server.enqueue(json(OfflineTestData.page(ids = listOf("one", "two"), total = 3, next = "of1_next")))
            server.enqueue(json(OfflineTestData.page(start = 2, ids = if (change == "count") emptyList() else listOf("three"),
                total = if (change == "count") 2 else 3,
                fence = if (change == "fence") "replacement" else "fence-a", revision = if (change == "revision") 2 else 1)))
            fails(OfflineFailure.STALE_MANIFEST) { OfflineClient(sessions, identity, pageSize = 2).manifest(OfflineTestData.playlist) }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun repeatedCursorOrOccurrenceCannotLoopOrPublishAPrefix() = runBlocking {
        for (cursorLoop in listOf(true, false)) fixture { server, sessions, identity ->
            server.enqueue(json(OfflineTestData.page(ids = listOf("one", "two"), total = 5, next = "of1_repeat")))
            val second = OfflineTestData.page(start = 2, ids = listOf("three", "four"), total = 5, next = "of1_repeat")
            server.enqueue(json(if (cursorLoop) second else second.replace("occurrence-2", "occurrence-1")))
            fails(OfflineFailure.INVALID_RESPONSE) { OfflineClient(sessions, identity, pageSize = 2).manifest(OfflineTestData.playlist) }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun countAndCumulativeByteCeilingsFailExplicitlyRatherThanReturningTruncation() = runBlocking {
        fixture { server, sessions, identity ->
            server.enqueue(json(OfflineTestData.page(ids = listOf("one", "two"), total = 3, next = "of1_next")))
            fails(OfflineFailure.LIMIT_EXCEEDED) {
                OfflineClient(sessions, identity, OfflineReadLimits(maxOccurrences = 2), 2).manifest(OfflineTestData.playlist)
            }
            assertEquals(2, server.requestCount)
        }
        fixture { server, sessions, identity ->
            val first = OfflineTestData.page(ids = listOf("one", "two"), total = 3, next = "of1_next")
            val second = OfflineTestData.page(start = 2, ids = listOf("three"), total = 3)
            server.enqueue(json(first)); server.enqueue(json(second))
            fails(OfflineFailure.LIMIT_EXCEEDED) {
                OfflineClient(sessions, identity, OfflineReadLimits(maxManifestBytes = (first.toByteArray().size + second.toByteArray().size - 1).toLong()), 2)
                    .manifest(OfflineTestData.playlist)
            }
        }
    }

    @Test fun retirementDuringContinuationCancelsAndCannotReturnAStagedManifest() = runBlocking {
        fixture { server, sessions, identity -> coroutineScope {
            server.enqueue(json(OfflineTestData.page(ids = listOf("one", "two"), total = 3, next = "of1_next")))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val read = async(Dispatchers.Default) { OfflineClient(sessions, identity, pageSize = 2).manifest(OfflineTestData.playlist) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            sessions.useManualLogin()
            val failure = runCatching { withTimeout(3000) { read.await() } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(failure is TimeoutCancellationException)
        } }
    }

    @Test fun semanticStaleAndUnknownConflictsStayDistinctAndLostGet503BodyIsNotInvented() = runBlocking {
        fixture { server, sessions, identity ->
            val api = OfflineClient(sessions, identity, pageSize = 2)
            server.enqueue(json("""{"error":{"code":"STALE_EVALUATION","message":"private diagnostic"}}""", 409))
            fails(OfflineFailure.STALE_MANIFEST) { api.manifest(OfflineTestData.playlist) }
            server.enqueue(json("{}", 409))
            fails(OfflineFailure.CONFLICT_REPREPARE) { api.prepareMedia("one") }
            server.enqueue(json("""{"error":{"code":"MEDIA_TIMEOUT","message":"private diagnostic"}}""", 503))
            fails(OfflineFailure.UNAVAILABLE) { api.prepareMedia("one") } // existing ReadStatus strips this body
            assertEquals(4, server.requestCount) // no status retry
        }
    }

    @Test fun receiptSubmissionSendsExactlySixFieldsAndOnlyTheOutboxCanRepeatAnIdenticalPayload() = runBlocking {
        fixture { server, sessions, identity ->
            val api = OfflineClient(sessions, identity)
            val receipt = OfflineTestData.receipt
            server.enqueue(json("{}", 503))
            fails(OfflineFailure.UNAVAILABLE) { api.submitReceipt(receipt) }
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(2, server.requestCount)
            server.enqueue(json(OfflineTestData.ack(duplicate = true)))
            assertTrue(api.submitReceipt(receipt).duplicate) // explicit identical caller retry
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            val body = first.body.readUtf8()
            assertEquals(body, second.body.readUtf8())
            assertEquals("POST", first.method)
            assertEquals("/api/me/offline/v1/play-receipts", first.path)
            assertEquals("fixture-csrf", first.getHeader("X-CSRF-Token"))
            val payload = Json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("eventId", "trackId", "origin", "occurredAtMs", "elapsedMs", "progressedMs"), payload.keys)
            assertEquals("offline", payload.getValue("origin").jsonPrimitive.content)
            assertFalse(body.contains("fixture-token"))
            assertFalse(body.contains(identity.origin))
        }
    }

    @Test fun receiptConflictsClockErrorsAndInvalidAcksNeverBecomeAcceptanceOrAutomaticRetries() = runBlocking {
        fixture { server, sessions, identity ->
            val api = OfflineClient(sessions, identity)
            for ((status, failure) in listOf(409 to OfflineFailure.RECEIPT_CONFLICT, 422 to OfflineFailure.CLOCK_ERROR,
                404 to OfflineFailure.UNSUPPORTED, 403 to OfflineFailure.FORBIDDEN)) {
                server.enqueue(json("""{"error":"private diagnostic"}""", status))
                fails(failure) { api.submitReceipt(OfflineTestData.receipt) }
            }
            server.enqueue(json(OfflineTestData.ack("wrong-event")))
            fails(OfflineFailure.INVALID_RESPONSE) { api.submitReceipt(OfflineTestData.receipt) }
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun invalidPathInputsAndRetiredIdentityDoNotDispatch() = runBlocking {
        fixture { server, sessions, identity ->
            val api = OfflineClient(sessions, identity)
            fails(OfflineFailure.INVALID_REQUEST) { api.manifest(OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, "not/allowed")) }
            fails(OfflineFailure.INVALID_REQUEST) { api.prepareMedia("..") }
            fails(OfflineFailure.INVALID_REQUEST) { api.prepareMedia("\uD800") }
            val retired = OfflineClient(sessions, identity.copy(incarnation = identity.incarnation + 1))
            assertTrue(runCatching { retired.submitReceipt(OfflineTestData.receipt) }.exceptionOrNull() is CancellationException)
            assertEquals(1, server.requestCount)
        }
    }
}
