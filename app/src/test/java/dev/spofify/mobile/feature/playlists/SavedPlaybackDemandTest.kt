package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.core.saved.SavedDownloadState
import dev.spofify.mobile.core.saved.SavedDownloadTransport
import dev.spofify.mobile.core.saved.SavedDownloader
import dev.spofify.mobile.core.saved.SavedLibraryStore
import dev.spofify.mobile.core.saved.SavedMediaDescriptor
import dev.spofify.mobile.core.saved.SavedOccurrence
import dev.spofify.mobile.core.saved.SavedTransferResponse
import dev.spofify.mobile.core.saved.SavedLocalEligibility
import dev.spofify.mobile.core.saved.SavedScope
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SavedPlaybackDemandTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = SavedTarget(SavedTargetKind.ARTIST, "artist")
    private class MemoryStore : SessionStore {
        override fun read(): StoredSession? = null
        override fun write(value: StoredSession?) = Unit
    }

    private inner class Fixture(dispatcher: CoroutineDispatcher, hooks: SavedDownloadsOwner.TestHooks? = null,
        beforeWorkerExit: (suspend () -> Unit)? = null) {
        val web = MockWebServer().apply { start() }
        val scope = SavedScope("http://127.0.0.1:${web.port}", "account")
        val client = SpofifyClient(ServerAddress.parse(scope.origin), MemoryStore())
        val root = temporary.newFolder()
        val owner = hooks?.let { SavedDownloadsOwner(dispatcher, it) { root } }
            ?: beforeWorkerExit?.let { SavedDownloadsOwner(dispatcher, beforeWorkerExit = it) { root } }
            ?: SavedDownloadsOwner(dispatcher) { root }
        val bytes = "abcdef".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val media = SavedMediaDescriptor("track", "sha256:$sha", sha, bytes.size.toLong(), "\"sha256:$sha\"")
        suspend fun seedMedia(complete: Boolean) = withContext(Dispatchers.IO) {
            SavedLibraryStore(root, SavedLocalEligibility { it == scope }).use { store ->
                store.bind(scope)
                val stage = store.beginManifest(scope, target, "Retained", "old-generation", 1)
                store.appendManifest(stage, 0, listOf(SavedOccurrence("entry", "track", "Song")), terminal = true)
                store.finishManifest(stage)
                SavedDownloader(store, object : SavedDownloadTransport {
                    override suspend fun prepare(scope: SavedScope, trackId: String) = media
                    override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long,
                        consume: (SavedTransferResponse) -> Unit) {
                        val body = if (complete) bytes else bytes.copyOf(3)
                        SavedTransferResponse(200, ByteArrayInputStream(body), bytes.size.toLong(), etag = media.strongETag).use(consume)
                    }
                }, maxAttemptsPerTrack = 1).download(scope, target)
                val snapshot = store.snapshot(scope, target)!!
                assertEquals(complete, snapshot.occurrences.single().available)
                assertEquals(if (complete) 6L else 3L, snapshot.transferredBytes)
            }
        }
        suspend fun login() {
            owner.configure(client)
            client.onAuthenticationChanged = { owner.authenticationChanged(client, it) }
            web.enqueue(json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                .addHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600"))
            client.login("listener", "synthetic-password")
            assertEquals("/api/auth/login", web.takeRequest(5, TimeUnit.SECONDS)?.path)
            requireNotNull(owner.access { true })
        }
        fun completedManifest(title: String = "Downloaded") = json("""{"version":1,"collection":{"kind":"artist","id":"artist","name":"$title"},"fence":"generation","items":[],"total":0,"recordedAudioTotal":0,"limit":100,"nextCursor":null}""")
        fun mediaManifest() = json("""{"version":1,"collection":{"kind":"artist","id":"artist","name":"Downloaded"},"fence":"new-generation","items":[{"occurrenceId":"entry","position":0,"track":{"kind":"track","id":"track","name":"Song","hasAudio":true}}],"total":1,"recordedAudioTotal":1,"limit":100,"nextCursor":null}""")
        fun descriptor() = json("""{"version":1,"trackId":"track","mediaVersion":"sha256:$sha","sha256":"$sha","byteLength":6,"format":"mp3","contentType":"audio/mpeg","downloadUrl":"/api/me/offline/v1/tracks/track/audio?version=sha256%3A$sha"}""")
        suspend fun retained() = requireNotNull(owner.access { true }).collections()
        suspend fun completed() = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                owner.state.first { state -> state.working.isEmpty() && state.collections.any { it.target == target && it.state == SavedDownloadState.COMPLETE } }
            }
        }
        fun assertOnlyManifestRequest() {
            assertEquals("GET", web.takeRequest(5, TimeUnit.SECONDS)?.let { request ->
                assertEquals("/api/me/offline/v1/artists/artist/manifest?limit=100", request.path)
                request.method
            })
            assertEquals("No output selection, playback or history requests", 2, web.requestCount)
        }
        suspend fun close() {
            client.onAuthenticationChanged = null
            owner.close()
            client.httpClient.dispatcher.cancelAll()
            client.httpClient.connectionPool.evictAll()
            client.httpClient.dispatcher.executorService.shutdown()
            web.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun manifestFailureIsDurableBeforeWorkerForgetsItsPlan() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.login()
            fixture.web.enqueue(json("""{"error":"Collection not found"}""").setResponseCode(404))
            fixture.owner.save(target, "Unavailable collection")
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { fixture.owner.state.first { it.working.isEmpty() && target in it.failures } }
            }
            assertEquals(SavedDownloadState.FAILED, fixture.retained().single().state)
            fixture.assertOnlyManifestRequest()
        } finally { fixture.close() }
        withContext(Dispatchers.IO) {
            SavedLibraryStore(fixture.root, SavedLocalEligibility { it == fixture.scope }).use { restarted ->
                restarted.bind(fixture.scope)
                assertEquals(SavedDownloadState.FAILED, restarted.snapshots(fixture.scope).single().state)
            }
        }
    }

    @Test fun unsaveAcceptedAfterPersistenceGuardCannotBeConsumedThenRecreated() = runTest {
        lateinit var fixture: Fixture
        val armed = AtomicBoolean(false)
        fixture = Fixture(StandardTestDispatcher(testScheduler), hooks = SavedDownloadsOwner.TestHooks(beforeIntentPersist = {
            if (armed.compareAndSet(true, false)) fixture.owner.unsave(target)
        }))
        try {
            fixture.seedMedia(complete = true)
            fixture.login()
            armed.set(true)
            fixture.owner.save(target, "Update accepted before Unsave")
            fixture.owner.ownedBytes()
            assertTrue(fixture.retained().isEmpty())
            assertTrue(fixture.owner.state.value.working.isEmpty())
            assertEquals("The removed intent must not start manifest/network work", 1, fixture.web.requestCount)
        } finally { fixture.close() }
        withContext(Dispatchers.IO) {
            SavedLibraryStore(fixture.root, SavedLocalEligibility { it == fixture.scope }).use { restarted ->
                restarted.bind(fixture.scope)
                assertTrue("Unsave must remain durable after restart", restarted.snapshots(fixture.scope).isEmpty())
            }
        }
    }

    @Test fun unsaveCompletedAfterWorkerGuardCannotBeRecreatedByLateManifestPublication() = runTest {
        lateinit var fixture: Fixture
        val removed = CompletableDeferred<Unit>()
        val armed = AtomicBoolean(true)
        fixture = Fixture(StandardTestDispatcher(testScheduler), hooks = SavedDownloadsOwner.TestHooks(beforeManifestPublish = {
            if (armed.compareAndSet(true, false)) withContext(NonCancellable) {
                fixture.owner.unsave(target)
                fixture.owner.ownedBytes() // Removal actor has actually completed before stale publication resumes.
                removed.complete(Unit)
            }
        }))
        try {
            fixture.login()
            fixture.web.enqueue(fixture.completedManifest())
            fixture.owner.save(target, "Explicit save")
            withContext(Dispatchers.Default) { withTimeout(5_000) { removed.await() } }
            fixture.owner.ownedBytes()
            assertTrue(fixture.retained().isEmpty())
            fixture.assertOnlyManifestRequest()
        } finally { fixture.close() } // Joins the cancelled worker and any queued publication before durable inspection.
        withContext(Dispatchers.IO) {
            SavedLibraryStore(fixture.root, SavedLocalEligibility { it == fixture.scope }).use { restarted ->
                restarted.bind(fixture.scope)
                assertTrue("Late synchronous work must not resurrect removed membership", restarted.snapshots(fixture.scope).isEmpty())
            }
        }
    }

    @Test fun activeDemandRetainsExplicitSaveWithoutHttpAndFalseResumesSameIntent() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(true)
            fixture.owner.save(target, "Selected")
            assertEquals(listOf(target), fixture.retained().map { it.target })
            assertTrue(fixture.owner.state.value.networkEnabled)
            assertEquals(setOf(target), fixture.owner.state.value.working)
            assertNull(fixture.owner.state.value.activeTarget)
            assertEquals(1, fixture.web.requestCount)
            fixture.web.enqueue(fixture.completedManifest())
            demand.setActive(false)
            fixture.completed()
            fixture.assertOnlyManifestRequest()
            demand.close()
        } finally { fixture.close() }
    }

    @Test fun supersededHandleFalseAndCloseCannotReleaseSuccessorDemand() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.login()
            val old = fixture.owner.playbackDemand(fixture.client)
            old.setActive(true)
            val successor = fixture.owner.playbackDemand(fixture.client)
            successor.setActive(true)
            old.setActive(false)
            old.close()
            fixture.owner.save(target, "Selected")
            assertEquals(1, fixture.retained().size)
            assertEquals(1, fixture.web.requestCount)
            fixture.web.enqueue(fixture.completedManifest())
            successor.setActive(false)
            fixture.completed()
            fixture.assertOnlyManifestRequest()
            successor.close()
        } finally { fixture.close() }
    }

    @Test fun foreignClientDemandHandleCannotReplaceCurrentServiceRecord() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val other = SpofifyClient(ServerAddress.parse(fixture.scope.origin), MemoryStore())
        try {
            fixture.login()
            val current = fixture.owner.playbackDemand(fixture.client)
            current.setActive(true)
            val foreign = fixture.owner.playbackDemand(other)
            foreign.setActive(false)
            foreign.close()
            fixture.owner.save(target, "Selected")
            assertEquals(1, fixture.retained().size)
            assertEquals(1, fixture.web.requestCount)
            fixture.owner.cancel(target)
            current.close()
            runCurrent()
            assertEquals(1, fixture.web.requestCount)
        } finally {
            fixture.close()
            other.httpClient.connectionPool.evictAll()
            other.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun priorityPulseCancelsInFlightManifestAndResumesOnlyAfterOldJobJoins() = runTest {
        val hold = AtomicBoolean(true)
        val exiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(StandardTestDispatcher(testScheduler), beforeWorkerExit = {
            if (hold.compareAndSet(true, false)) { exiting.complete(Unit); release.await() }
        })
        try {
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(false) // Initial idle aggregate permits the first explicit transfer.
            fixture.web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            fixture.owner.save(target, "Selected")
            runCurrent()
            assertEquals("/api/me/offline/v1/artists/artist/manifest?limit=100", fixture.web.takeRequest(5, TimeUnit.SECONDS)?.path)
            demand.setActive(true)
            withContext(Dispatchers.Default) { withTimeout(5_000) { exiting.await() } }
            assertEquals(1, withContext(Dispatchers.Default) { withTimeout(5_000) { fixture.retained().size } })
            fixture.web.enqueue(fixture.completedManifest())
            demand.setActive(false) // Fast aggregate pulse must not lose the accepted ticket.
            runCurrent()
            assertEquals("The old job is still held, so no successor HTTP is permitted", 2, fixture.web.requestCount)
            assertNull(fixture.web.takeRequest(100, TimeUnit.MILLISECONDS))
            release.complete(Unit)
            fixture.completed()
            assertEquals("/api/me/offline/v1/artists/artist/manifest?limit=100", fixture.web.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals(3, fixture.web.requestCount) // Login, cancelled manifest, same-intent resumed manifest.
            demand.close()
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun priorityDeferralPreservesVerifiedOldGenerationAndItsAcceptedLeaseAcrossUpdate() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        var lease: dev.spofify.mobile.core.saved.SavedMediaLease? = null
        try {
            fixture.seedMedia(complete = true)
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(true)
            fixture.owner.save(target, "Update")
            val retained = fixture.retained().single()
            assertEquals("old-generation", retained.generation)
            assertTrue(retained.occurrences.single().available)
            val accepted = requireNotNull(withContext(Dispatchers.IO) { fixture.owner.reader.open(fixture.scope, target, "entry") })
            lease = accepted
            assertEquals('a'.code, withContext(Dispatchers.IO) { accepted.input.read() })
            assertEquals(1, fixture.web.requestCount)
            fixture.web.enqueue(fixture.completedManifest())
            demand.setActive(false)
            fixture.completed()
            fixture.assertOnlyManifestRequest()
            assertEquals("bcdef", withContext(Dispatchers.IO) { String(accepted.input.readBytes()) })
            demand.close()
        } finally {
            lease?.let { withContext(Dispatchers.IO) { it.close() } }
            fixture.close()
        }
    }

    @Test fun deferredIntentKeepsExactPartialBytesAndResumesWithRangeAfterDemandFalse() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.seedMedia(complete = false)
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(true)
            fixture.owner.save(target, "Resume")
            val partial = fixture.retained().single()
            assertEquals(3L, partial.transferredBytes)
            assertFalse(partial.occurrences.single().available)
            assertEquals(1, fixture.web.requestCount)
            fixture.web.enqueue(fixture.mediaManifest())
            fixture.web.enqueue(fixture.descriptor())
            fixture.web.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/mpeg")
                .setHeader("ETag", fixture.media.strongETag).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
            demand.setActive(false)
            fixture.completed()
            assertEquals("/api/me/offline/v1/artists/artist/manifest?limit=100", fixture.web.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/api/me/offline/v1/tracks/track/media", fixture.web.takeRequest(5, TimeUnit.SECONDS)?.path)
            val transfer = requireNotNull(fixture.web.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("bytes=3-", transfer.getHeader("Range"))
            assertEquals(fixture.media.strongETag, transfer.getHeader("If-Range"))
            assertEquals(4, fixture.web.requestCount)
            val complete = fixture.retained().single()
            assertTrue(complete.occurrences.single().available)
            assertEquals(fixture.media, complete.occurrences.single().media)
            assertEquals(3L, complete.transferredBytes)
            demand.close()
        } finally { fixture.close() }
    }

    @Test fun modePauseDefeatsDeferredResumeAndOnlineReturnNeedsNewExplicitSave() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(true)
            fixture.owner.save(target, "Selected")
            assertEquals(1, fixture.retained().size)
            fixture.owner.setNetworkAllowed(false)
            demand.setActive(false)
            fixture.owner.setNetworkAllowed(true)
            assertEquals(1, fixture.retained().size)
            assertTrue(fixture.owner.state.value.working.isEmpty())
            assertEquals(1, fixture.web.requestCount)
            fixture.web.enqueue(fixture.completedManifest())
            fixture.owner.save(target, "New explicit save")
            fixture.completed()
            fixture.assertOnlyManifestRequest()
            demand.close()
        } finally { fixture.close() }
    }

    @Test fun userCancelDefeatsPriorityResumeButPreservesRetainedMembership() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(true)
            fixture.owner.save(target, "Selected")
            assertEquals(1, fixture.retained().size)
            fixture.owner.cancel(target)
            demand.setActive(false)
            assertEquals(1, fixture.retained().size)
            assertTrue(fixture.owner.state.value.working.isEmpty())
            assertEquals(1, fixture.web.requestCount)
            demand.close()
        } finally { fixture.close() }
    }

    @Test fun authoritativeAuthLossDefeatsDeferredResume() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(true)
            fixture.owner.save(target, "Selected")
            assertEquals(1, fixture.retained().size)
            fixture.client.clearSession()
            demand.setActive(false)
            fixture.owner.ownedBytes()
            assertNull(fixture.owner.access { true })
            assertTrue(fixture.owner.state.value.working.isEmpty())
            assertEquals(1, fixture.web.requestCount)
            demand.close()
        } finally { fixture.close() }
    }

    @Test fun unsaveDefeatsDeferredResumeAndNewestExplicitSaveSupersedesOldRemoval() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(true)
            fixture.owner.save(target, "First intent")
            assertEquals(1, fixture.retained().size)
            fixture.owner.unsave(target)
            demand.setActive(false)
            assertTrue(fixture.retained().isEmpty())
            assertEquals(1, fixture.web.requestCount)
            demand.setActive(true)
            fixture.owner.save(target, "Second intent")
            fixture.owner.unsave(target)
            fixture.owner.save(target, "Latest intent")
            assertEquals(1, fixture.retained().size)
            assertEquals(1, fixture.web.requestCount)
            fixture.web.enqueue(fixture.completedManifest("Latest downloaded"))
            demand.setActive(false)
            fixture.completed()
            assertEquals("Latest downloaded", fixture.retained().single().title)
            fixture.assertOnlyManifestRequest()
            demand.close()
        } finally { fixture.close() }
    }

    @Test fun realScopeChangeDefeatsDeferredResume() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.login()
            val demand = fixture.owner.playbackDemand(fixture.client)
            demand.setActive(true)
            fixture.owner.save(target, "Selected")
            assertEquals(1, fixture.retained().size)
            fixture.owner.setLocalEligibility(SavedLocalEligibility { it.origin == fixture.scope.origin })
            assertTrue(fixture.owner.bindLocal(fixture.client, fixture.scope.copy(accountId = "other-account")))
            demand.setActive(false)
            assertTrue(fixture.retained().isEmpty())
            assertTrue(fixture.owner.state.value.working.isEmpty())
            assertEquals(1, fixture.web.requestCount)
            demand.close()
        } finally { fixture.close() }
    }
}
