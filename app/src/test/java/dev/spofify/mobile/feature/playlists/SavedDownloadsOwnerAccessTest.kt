package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.AuthenticationChange
import dev.spofify.mobile.core.AuthenticationChangeKind
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.core.saved.SavedLibraryStore
import dev.spofify.mobile.core.saved.SavedDownloader
import dev.spofify.mobile.core.saved.SavedDownloadTransport
import dev.spofify.mobile.core.saved.SavedMediaDescriptor
import dev.spofify.mobile.core.saved.SavedTransferResponse
import dev.spofify.mobile.core.saved.SavedLocalEligibility
import dev.spofify.mobile.core.saved.SavedOccurrence
import dev.spofify.mobile.core.saved.SavedScope
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SavedDownloadsOwnerAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = SavedScope("https://saved.example", "account")
    private val target = SavedTarget(SavedTargetKind.ARTIST, "artist")
    private fun client(origin: String = scope.origin, timeMillis: () -> Long = System::currentTimeMillis) =
        SpofifyClient(ServerAddress.parse(origin), object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        }, timeMillis)

    private fun seed(root: File, savedScope: SavedScope = scope) {
        SavedLibraryStore(root, SavedLocalEligibility { it == savedScope }).use { store ->
            store.bind(savedScope)
            val stage = store.beginManifest(savedScope, target, "Saved artist", "generation", 1)
            store.appendManifest(stage, 0,
                listOf(SavedOccurrence("entry", "missing", "Missing song", hasAudio = false)), terminal = true)
            store.finishManifest(stage)
        }
    }

    private suspend fun seedVerified(root: File): SavedMediaDescriptor = withContext(Dispatchers.IO) {
        val bytes = "abcdef".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val media = SavedMediaDescriptor("track", "sha256:$sha", sha, bytes.size.toLong(), "\"sha256:$sha\"")
        SavedLibraryStore(root, SavedLocalEligibility { it == scope }).use { store ->
            store.bind(scope)
            val stage = store.beginManifest(scope, target, "Saved artist", "generation", 1)
            store.appendManifest(stage, 0, listOf(SavedOccurrence("entry", "track", "Song")), terminal = true)
            store.finishManifest(stage)
            SavedDownloader(store, object : SavedDownloadTransport {
                override suspend fun prepare(scope: SavedScope, trackId: String) = media
                override suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long,
                    consume: (SavedTransferResponse) -> Unit) {
                    assertEquals(0L, offset)
                    SavedTransferResponse(200, ByteArrayInputStream(bytes), bytes.size.toLong(), etag = media.strongETag).use(consume)
                }
            }).download(scope, target)
            assertTrue(store.snapshot(scope, target)!!.occurrences.single().available)
        }
        media
    }

    @Test fun staleFrameCannotCaptureSuccessorOrRecaptureSameScopeABA() = runTest {
        val root = temporary.newFolder("frame-capture")
        val nextScope = scope.copy(accountId = "next-account")
        seed(root)
        seed(root, nextScope)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val source = client()
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope || it == nextScope })
            assertTrue(owner.bindLocal(source, scope))
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            val revisionA = owner.state.value.bindingRevision
            val guardA = owner.captureAction(revisionA)
            assertTrue(guardA())
            assertTrue(owner.bindLocal(source, nextScope))
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            assertFalse(guardA())
            assertTrue("A fresh unscoped capture would authorize B", owner.captureAction()())
            assertFalse("An old collected frame cannot capture B", owner.captureAction(revisionA)())
            val revisionB = owner.state.value.bindingRevision
            val guardB = owner.captureAction(revisionB)
            assertTrue(guardB())
            assertTrue(owner.bindLocal(source, nextScope))
            owner.access { true }
            assertFalse("Same-scope binding ABA still changes the frame incarnation", guardB())
            assertFalse(owner.captureAction(revisionB)())
            assertTrue(owner.captureAction(owner.state.value.bindingRevision)())
        } finally { owner.close() }
    }

    @Test fun staleFrameUnsaveCannotRemoveSuccessorEvenAfterOldGuardAlreadyPassed() = runTest {
        val root = temporary.newFolder("frame-unsave")
        val nextScope = scope.copy(accountId = "next-account")
        seed(root)
        seed(root, nextScope)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val source = client()
        var closed = false
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope || it == nextScope })
            assertTrue(owner.bindLocal(source, scope))
            owner.access { true }
            val revisionA = owner.state.value.bindingRevision
            assertTrue(owner.captureAction(revisionA)()) // The UI check already passed before the switch.
            assertTrue(owner.bindLocal(source, nextScope))
            val successor = requireNotNull(owner.access { true }).collections().single()
            assertEquals(target, successor.target)
            owner.unsave(target, expectedBindingRevision = revisionA)
            owner.ownedBytes()
            assertEquals(listOf(target), requireNotNull(owner.access { true }).collections().map { it.target })
            val revisionB = owner.state.value.bindingRevision
            owner.unsave(target, expectedBindingRevision = revisionB)
            owner.ownedBytes()
            assertTrue(requireNotNull(owner.access { true }).collections().isEmpty())
            owner.close()
            closed = true
            SavedLibraryStore(root, SavedLocalEligibility { it == nextScope }).use { store ->
                store.bind(nextScope)
                assertTrue("Only the fresh B intent durably removes B", store.snapshots(nextScope).isEmpty())
            }
        } finally { if (!closed) owner.close() }
    }

    @Test fun staleFrameCannotSaveOrCancelValidatedSuccessorPlans() = runTest {
        val web = MockWebServer()
        web.start()
        val origin = "http://127.0.0.1:${web.port}"
        val scopeA = SavedScope(origin, "account-a")
        val scopeB = SavedScope(origin, "account-b")
        val root = temporary.newFolder("frame-network-intents")
        seed(root, scopeA)
        seed(root, scopeB)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val source = client(origin)
        var demand: SavedPlaybackDemand? = null
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scopeA || it == scopeB })
            assertTrue(owner.bindLocal(source, scopeA))
            owner.access { true }
            val revisionA = owner.state.value.bindingRevision
            assertTrue(owner.captureAction(revisionA)())
            source.onAuthenticationChanged = { owner.authenticationChanged(source, it) }
            web.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "spofify_session=synthetic-b; Path=/; HttpOnly; Max-Age=3600")
                .setBody("""{"accountId":"account-b","username":"listener-b","role":"user","csrfToken":"synthetic-b"}"""))
            source.login("listener-b", "synthetic-password")
            assertEquals("/api/auth/login", web.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            assertEquals(scopeB, owner.currentScope())
            demand = owner.playbackDemand(source).also { it.setActive(true) }
            val revisionB = owner.state.value.bindingRevision
            owner.save(target, "Accepted B", expectedBindingRevision = revisionB)
            owner.ownedBytes()
            assertEquals(setOf(target), owner.state.value.working)
            val staleOnlyTarget = SavedTarget(SavedTargetKind.ALBUM, "old-a-only")
            owner.save(staleOnlyTarget, "Wrong A intent", expectedBindingRevision = revisionA)
            assertEquals("An old Save cannot add a plan under B", setOf(target), owner.state.value.working)
            owner.cancel(target, expectedBindingRevision = revisionA)
            assertEquals("An old Cancel cannot discard B's accepted plan", setOf(target), owner.state.value.working)
            owner.cancel(target, expectedBindingRevision = revisionB)
            assertTrue(owner.state.value.working.isEmpty())
            owner.save(staleOnlyTarget, "Fresh B intent", expectedBindingRevision = revisionB)
            owner.ownedBytes()
            assertEquals(setOf(staleOnlyTarget), owner.state.value.working)
            assertEquals("Playback priority keeps all accepted plans network-deferred", 1, web.requestCount)
        } finally {
            source.onAuthenticationChanged = null
            owner.close()
            demand?.close()
            source.httpClient.dispatcher.cancelAll()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
            web.shutdown()
        }
    }

    @Test fun staleFramePlayCannotReauthorizeSameGenerationAfterBindingABA() = runTest {
        val root = temporary.newFolder("frame-play")
        seedVerified(root)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val source = client()
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(owner.bindLocal(source, scope))
            val snapshotA = requireNotNull(owner.access { true }).collections().single()
            val revisionA = owner.state.value.bindingRevision
            assertTrue(owner.captureAction(revisionA)())
            assertTrue(owner.bindLocal(source, scope))
            val snapshotB = requireNotNull(owner.access { true }).collections().single()
            val revisionB = owner.state.value.bindingRevision
            assertEquals(snapshotA.generation, snapshotB.generation)
            assertEquals(snapshotA.occurrences, snapshotB.occurrences)
            var calls = 0
            var acceptedGuard: (() -> Boolean)? = null
            owner.setPlaybackHandler { admittedScope, _, _, isCurrent ->
                assertEquals(scope, admittedScope)
                assertTrue(isCurrent())
                calls++
                acceptedGuard = isCurrent
            }
            owner.playAvailable(snapshotA, expectedBindingRevision = revisionA)
            assertEquals("Matching generation/media must not recapture a successor", 0, calls)
            owner.playAvailable(snapshotB, expectedBindingRevision = revisionB)
            assertEquals(1, calls)
            assertTrue(requireNotNull(acceptedGuard)())
            assertTrue(owner.bindLocal(source, scope))
            owner.access { true }
            assertFalse("The consumer retains the captured frame guard through later waits", requireNotNull(acceptedGuard)())
        } finally { owner.close() }
    }

    @Test fun staleLocalAdmissionCannotRetireOrStompNewValidatedAccountAndAcceptedDownload() = runTest {
        val web = MockWebServer()
        web.start()
        val origin = "http://127.0.0.1:${web.port}"
        val scopeA = SavedScope(origin, "account-a")
        val scopeB = SavedScope(origin, "account-b")
        val root = temporary.newFolder("late-local-commit")
        seed(root, scopeA)
        val metadata = File(root, "metadata")
        val pointersA = metadata.listFiles()!!.filter { it.name.endsWith(".current") }.map { it.name }.toSet()
        seed(root, scopeB)
        val pointerB = metadata.listFiles()!!.single { it.name.endsWith(".current") && it.name !in pointersA }
        val namespaceB = File(metadata, pointerB.readText())
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val allowedA = AtomicBoolean(true)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler), SavedDownloadsOwner.TestHooks {
            if (armed.compareAndSet(true, false)) {
                admitted.countDown() // The legitimate policy already returned true; never block inside it.
                check(release.await(5, TimeUnit.SECONDS))
            }
        }) { root }
        val sourceA = client(origin)
        val sourceB = client(origin)
        var closed = false
        try {
            owner.configure(sourceA)
            owner.setLocalEligibility(SavedLocalEligibility { it == scopeA && allowedA.get() })
            assertTrue(owner.bindLocal(sourceA, scopeA))
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            armed.set(true)
            val stale = async(Dispatchers.Default) { owner.bindLocal(sourceA, scopeA) }
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            allowedA.set(false)
            owner.retire()
            owner.prepareConfigure(origin)
            owner.configure(sourceB)
            owner.setLocalEligibility(SavedLocalEligibility { it == scopeB })
            sourceB.onAuthenticationChanged = { owner.authenticationChanged(sourceB, it) }
            web.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "spofify_session=synthetic-b; Path=/; HttpOnly; Max-Age=3600")
                .setBody("""{"accountId":"account-b","username":"listener-b","role":"user","csrfToken":"synthetic-b"}"""))
            sourceB.login("listener-b", "synthetic-password")
            assertEquals("/api/auth/login", web.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            owner.save(target, "Accepted B download")
            runCurrent()
            assertEquals("/api/me/offline/v1/artists/artist/manifest?limit=100", web.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals(setOf(target), owner.state.value.working)
            assertEquals(target, owner.state.value.activeTarget)
            assertFalse(File(namespaceB, "retired").exists())
            val revisionB = owner.state.value.bindingRevision
            release.countDown()
            assertFalse(withContext(Dispatchers.Default) { withTimeout(5_000) { stale.await() } })
            owner.ownedBytes() // Drain any wrongly enqueued retirement before asserting durable B ownership.
            assertEquals(scopeB, owner.currentScope())
            assertTrue(owner.state.value.networkEnabled)
            assertEquals(revisionB, owner.state.value.bindingRevision)
            assertEquals(setOf(target), owner.state.value.working)
            assertEquals(target, owner.state.value.activeTarget)
            assertEquals(listOf(target), requireNotNull(owner.access { true }).collections().map { it.target })
            assertFalse("A stale attach must never enqueue retirement of B", File(namespaceB, "retired").exists())
            assertEquals(2, web.requestCount)
            owner.cancel(target)
            closed = true
            owner.close()
            SavedLibraryStore(root, SavedLocalEligibility { it == scopeB }).use { store ->
                store.bind(scopeB)
                assertEquals(listOf(target), store.snapshots(scopeB).map { it.target })
            }
        } finally {
            release.countDown()
            sourceB.onAuthenticationChanged = null
            if (!closed) owner.close()
            listOf(sourceA, sourceB).forEach {
                it.httpClient.dispatcher.cancelAll()
                it.httpClient.connectionPool.evictAll()
                it.httpClient.dispatcher.executorService.shutdown()
            }
            web.shutdown()
        }
    }

    @Test fun sameSourcePolicyAndBindingAfterLocalExpiryStillRejectEarlierAdmissionEpoch() = runTest {
        val root = temporary.newFolder("local-admission-aba")
        seed(root)
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler), SavedDownloadsOwner.TestHooks {
            if (armed.compareAndSet(true, false)) {
                admitted.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }) { root }
        val source = client()
        try {
            owner.configure(source)
            val policy = SavedLocalEligibility { it == scope }
            owner.setLocalEligibility(policy)
            assertTrue(owner.bindLocal(source, scope))
            val original = requireNotNull(owner.access { true })
            assertEquals(1, original.collections().size)
            armed.set(true)
            val stale = async(Dispatchers.Default) { owner.bindLocal(source, scope) }
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            owner.authenticationChanged(source, AuthenticationChange(1, AuthenticationChangeKind.EXPIRED, null))
            val current = requireNotNull(owner.access { true })
            val revision = owner.state.value.bindingRevision
            assertEquals(1, current.collections().size) // Ordinary expiry keeps the exact pure-local binding.
            release.countDown()
            assertFalse(withContext(Dispatchers.Default) { withTimeout(5_000) { stale.await() } })
            owner.ownedBytes()
            assertEquals(revision, owner.state.value.bindingRevision)
            assertEquals(scope, owner.currentScope())
            assertEquals(1, original.collections().size)
            assertEquals(1, current.collections().size)
        } finally {
            release.countDown()
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun freshEligibleAAfterAccountAtoBtoADoesNotReauthorizeOldAdmission() = runTest {
        val root = temporary.newFolder("local-account-aba")
        val scopeB = scope.copy(accountId = "other-account")
        seed(root)
        seed(root, scopeB)
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler), SavedDownloadsOwner.TestHooks {
            if (armed.compareAndSet(true, false)) { admitted.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }) { root }
        val sourceA = client()
        val sourceB = client()
        val selected = java.util.concurrent.atomic.AtomicReference(scope)
        val policy = SavedLocalEligibility { it == selected.get() }
        try {
            owner.configure(sourceA)
            owner.setLocalEligibility(policy)
            assertTrue(owner.bindLocal(sourceA, scope))
            requireNotNull(owner.access { true })
            armed.set(true)
            val stale = async(Dispatchers.Default) { owner.bindLocal(sourceA, scope) }
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            selected.set(scopeB)
            owner.prepareConfigure(scope.origin)
            owner.configure(sourceB)
            owner.setLocalEligibility(policy)
            assertTrue(owner.bindLocal(sourceB, scopeB))
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            selected.set(scope)
            owner.prepareConfigure(scope.origin)
            owner.configure(sourceA) // Same source and policy objects as the original admission.
            owner.setLocalEligibility(policy)
            assertTrue(owner.bindLocal(sourceA, scope))
            requireNotNull(owner.access { true })
            val freshA = owner.captureAction()
            val revision = owner.state.value.bindingRevision
            assertTrue(freshA())
            release.countDown()
            assertFalse(withContext(Dispatchers.Default) { withTimeout(5_000) { stale.await() } })
            owner.ownedBytes()
            assertEquals(scope, owner.currentScope())
            assertEquals(revision, owner.state.value.bindingRevision)
            assertTrue("Fresh A eligibility is not permission to revive the old A admission", freshA())
        } finally {
            release.countDown()
            owner.close()
            listOf(sourceA, sourceB).forEach {
                it.httpClient.connectionPool.evictAll()
                it.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    private suspend fun TestScope.coldKnownScopeRetirement(originSwitch: Boolean) {
        val root = temporary.newFolder(if (originSwitch) "known-origin" else "known-logout")
        val scopeB = SavedScope(if (originSwitch) "https://other.example" else scope.origin, "other-account")
        seed(root)
        val metadata = File(root, "metadata")
        val pointerA = metadata.listFiles()!!.single { it.name.endsWith(".current") }
        val namespaceA = File(metadata, pointerA.readText())
        seed(root, scopeB)
        val pointerB = metadata.listFiles()!!.single { it.name.endsWith(".current") && it.name != pointerA.name }
        val namespaceB = File(metadata, pointerB.readText())
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val sourceB = client(scopeB.origin)
        var closed = false
        try {
            if (originSwitch) owner.prepareConfigure(scopeB.origin, knownOldScope = scope)
            else owner.retire(knownOldScope = scope)
            assertNull(owner.currentScope())
            assertTrue(owner.reader.snapshots(scope).isEmpty())
            assertFalse(owner.state.value.networkEnabled)
            owner.configure(sourceB)
            owner.setLocalEligibility(SavedLocalEligibility { it == scopeB })
            assertTrue(owner.bindLocal(sourceB, scopeB))
            assertEquals(listOf(target), requireNotNull(owner.access { true }).collections().map { it.target })
            assertTrue("Known A must be durably retired before any owner close", File(namespaceA, "retired").isFile)
            assertFalse("Ordered old-scope retirement must not retire successor B", File(namespaceB, "retired").exists())
            closed = true
            owner.close()
            SavedLibraryStore(root, SavedLocalEligibility { it == scopeB }).use { store ->
                store.bind(scopeB)
                assertEquals(listOf(target), store.snapshots(scopeB).map { it.target })
            }
        } finally {
            if (!closed) owner.close()
            sourceB.httpClient.connectionPool.evictAll()
            sourceB.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun trustedColdKnownScopeLogoutPurgesBeforeAnyBindAndPreservesSuccessor() = runTest {
        coldKnownScopeRetirement(originSwitch = false)
    }

    @Test fun trustedColdKnownScopeOriginSwitchPurgesBeforeAnyBindAndPreservesSuccessor() = runTest {
        coldKnownScopeRetirement(originSwitch = true)
    }

    @Test fun sameOriginKnownScopeIsMetadataOnlyAndRetiresOnGenuineDifferentAccountValidation() = runTest {
        val web = MockWebServer()
        web.start()
        val origin = "http://127.0.0.1:${web.port}"
        val scopeA = SavedScope(origin, "old-account")
        val scopeB = SavedScope(origin, "new-account")
        val root = temporary.newFolder("known-same-origin")
        seed(root, scopeA)
        val metadata = File(root, "metadata")
        val pointerA = metadata.listFiles()!!.single { it.name.endsWith(".current") }
        val namespaceA = File(metadata, pointerA.readText())
        seed(root, scopeB)
        val pointerB = metadata.listFiles()!!.single { it.name.endsWith(".current") && it.name != pointerA.name }
        val namespaceB = File(metadata, pointerB.readText())
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val sourceB = client(origin)
        try {
            owner.prepareConfigure(origin, knownOldScope = scopeA)
            owner.configure(sourceB)
            owner.setLocalEligibility(SavedLocalEligibility { it == scopeA })
            assertNull(owner.currentScope())
            assertTrue(owner.reader.snapshots(scopeA).isEmpty())
            owner.save(target, "Metadata cannot authorize a download")
            assertTrue(owner.state.value.working.isEmpty())
            assertEquals(0, web.requestCount)
            sourceB.onAuthenticationChanged = { owner.authenticationChanged(sourceB, it) }
            web.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "spofify_session=synthetic-b; Path=/; HttpOnly; Max-Age=3600")
                .setBody("""{"accountId":"new-account","username":"listener-b","role":"user","csrfToken":"synthetic-b"}"""))
            sourceB.login("listener-b", "synthetic-password")
            assertEquals("/api/auth/login", web.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals(scopeB, owner.currentScope())
            assertEquals(listOf(target), requireNotNull(owner.access { true }).collections().map { it.target })
            assertTrue(File(namespaceA, "retired").isFile)
            assertFalse(File(namespaceB, "retired").exists())
            assertEquals(1, web.requestCount)
        } finally {
            sourceB.onAuthenticationChanged = null
            owner.close()
            sourceB.httpClient.connectionPool.evictAll()
            sourceB.httpClient.dispatcher.executorService.shutdown()
            web.shutdown()
        }
    }

    private inner class AuthFixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        beforeCommit: (AuthenticationChange) -> Unit) {
        val web = MockWebServer().apply { start() }
        val origin = "http://127.0.0.1:${web.port}"
        val scopeA = SavedScope(origin, "account-a")
        val scopeB = SavedScope(origin, "account-b")
        val scopeC = SavedScope(origin, "account-c")
        val root = temporary.newFolder()
        private fun seedNamespace(savedScope: SavedScope): File {
            val metadata = File(root, "metadata")
            val before = metadata.listFiles().orEmpty().map { it.name }.toSet()
            seed(root, savedScope)
            val pointer = metadata.listFiles()!!.single { it.name.endsWith(".current") && it.name !in before }
            return File(metadata, pointer.readText())
        }
        val namespaceA = seedNamespace(scopeA)
        val namespaceB = seedNamespace(scopeB)
        val namespaceC = seedNamespace(scopeC)
        val owner = SavedDownloadsOwner(dispatcher, SavedDownloadsOwner.TestHooks(beforeAuthCommit = beforeCommit)) { root }
        val source = client(origin)
        init {
            owner.prepareConfigure(origin, knownOldScope = scopeA)
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scopeB })
            source.onAuthenticationChanged = { owner.authenticationChanged(source, it) }
        }
        suspend fun login(account: String) {
            web.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "spofify_session=synthetic-$account; Path=/; HttpOnly; Max-Age=3600")
                .setBody("""{"accountId":"$account","username":"listener-$account","role":"user","csrfToken":"synthetic"}"""))
            source.login("listener-$account", "synthetic-password")
        }
        suspend fun close() {
            source.onAuthenticationChanged = null
            owner.close()
            source.httpClient.dispatcher.cancelAll()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
            web.shutdown()
        }
    }

    @Test fun publicRetireIsTerminalForExecutingAuthUntilExplicitConfigureAndNewGenuineAuth() = runTest {
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val fixture = AuthFixture(StandardTestDispatcher(testScheduler)) { change ->
            if (change.kind == AuthenticationChangeKind.VALIDATED && armed.compareAndSet(true, false)) {
                admitted.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val pending = async(Dispatchers.Default) { runCatching { fixture.login("account-b") } }
        try {
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            fixture.owner.retire()
            assertNull(fixture.owner.currentScope())
            assertFalse(fixture.owner.bindLocal(fixture.source, fixture.scopeB)) // Even while the old policy and SDK cookie still accept B.
            assertNull(fixture.owner.access { true })
            fixture.owner.configure(fixture.source) // Reusing the client must not revive its executing old callback.
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5_000) { pending.await() } }
            fixture.owner.ownedBytes()
            assertNull(fixture.owner.currentScope())
            assertNull(fixture.owner.access { true })
            assertTrue(fixture.owner.state.value.collections.isEmpty())
            assertFalse(fixture.owner.state.value.networkEnabled)
            assertFalse(File(fixture.namespaceB, "retired").exists())
            fixture.login("account-b") // Only a new SDK auth event in the explicitly configured ownership may attach.
            assertEquals(fixture.scopeB, fixture.owner.currentScope())
            assertEquals(1, requireNotNull(fixture.owner.access { true }).collections().size)
            assertTrue(fixture.owner.state.value.networkEnabled)
            assertEquals(2, fixture.web.requestCount)
        } finally { release.countDown(); pending.cancelAndJoin(); fixture.close() }
    }

    @Test fun privateInvalidationRetiresButAllowsNewerGenuineAuthWithoutConfigure() = runTest {
        val fixture = AuthFixture(StandardTestDispatcher(testScheduler)) { }
        try {
            fixture.login("account-b")
            assertEquals(1, requireNotNull(fixture.owner.access { true }).collections().size)
            fixture.web.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Unauthorized"}"""))
            assertTrue(runCatching { fixture.source.library() }.isFailure) // Genuine current-credential INVALIDATED.
            assertNull(fixture.source.validatedSessionIdentity())
            fixture.owner.ownedBytes()
            assertNull(fixture.owner.currentScope())
            assertTrue(File(fixture.namespaceB, "retired").isFile)
            fixture.login("account-b")
            assertEquals(fixture.scopeB, fixture.owner.currentScope())
            assertTrue(requireNotNull(fixture.owner.access { true }).collections().isEmpty())
            assertTrue(fixture.owner.state.value.networkEnabled)
            assertEquals(3, fixture.web.requestCount)
        } finally { fixture.close() }
    }

    @Test fun genuineValidatedEventRetriesModeAndPolicyChurnAndRetiresOldAccount() = runTest {
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val fixture = AuthFixture(StandardTestDispatcher(testScheduler)) { change ->
            if (change.kind == AuthenticationChangeKind.VALIDATED && armed.compareAndSet(true, false)) {
                admitted.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val login = async(Dispatchers.Default) { fixture.login("account-b") }
        try {
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            fixture.owner.setNetworkAllowed(false)
            fixture.owner.setLocalEligibility(SavedLocalEligibility { it == fixture.scopeB })
            fixture.owner.setNetworkAllowed(true)
            runCurrent()
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5_000) { login.await() } }
            assertEquals(fixture.scopeB, fixture.owner.currentScope())
            assertEquals(listOf(target), requireNotNull(fixture.owner.access { true }).collections().map { it.target })
            assertTrue(fixture.owner.state.value.networkEnabled)
            assertTrue(File(fixture.namespaceA, "retired").isFile)
            assertFalse(File(fixture.namespaceB, "retired").exists())
            assertEquals("Validation retry is metadata only, not a second HTTP login/probe", 1, fixture.web.requestCount)
        } finally { release.countDown(); login.cancelAndJoin(); fixture.close() }
    }

    @Test fun delayedValidatedEventCannotClobberNewerGenuineAuthOrAcceptedDownload() = runTest {
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val fixture = AuthFixture(StandardTestDispatcher(testScheduler)) { change ->
            if (change.kind == AuthenticationChangeKind.VALIDATED && change.identity?.accountId == "account-b" && armed.compareAndSet(true, false)) {
                admitted.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val oldLogin = async(Dispatchers.Default) { runCatching { fixture.login("account-b") } }
        try {
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            fixture.owner.setLocalEligibility(SavedLocalEligibility { it == fixture.scopeC })
            fixture.login("account-c") // Real SDK RESET/VALIDATED revisions supersede the parked B event.
            val demand = fixture.owner.playbackDemand(fixture.source)
            fixture.owner.save(target, "Accepted C")
            assertEquals(1, requireNotNull(fixture.owner.access { true }).collections().size)
            val revisionC = fixture.owner.state.value.bindingRevision
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5_000) { oldLogin.await() } }
            fixture.owner.ownedBytes()
            assertEquals("account-c", fixture.source.validatedSessionIdentity()?.accountId)
            assertEquals(fixture.scopeC, fixture.owner.currentScope())
            assertEquals(revisionC, fixture.owner.state.value.bindingRevision)
            assertEquals(setOf(target), fixture.owner.state.value.working)
            assertTrue(fixture.owner.state.value.networkEnabled)
            assertFalse(File(fixture.namespaceC, "retired").exists())
            assertEquals(2, fixture.web.requestCount)
            fixture.owner.cancel(target)
            demand.close()
        } finally { release.countDown(); oldLogin.cancelAndJoin(); fixture.close() }
    }

    @Test fun delayedValidatedEventAcknowledgesNewerEligibleLocalBindingWithoutReplacingIt() = runTest {
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val fixture = AuthFixture(StandardTestDispatcher(testScheduler)) { change ->
            if (change.kind == AuthenticationChangeKind.VALIDATED && armed.compareAndSet(true, false)) {
                admitted.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val login = async(Dispatchers.Default) { fixture.login("account-b") }
        try {
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            assertEquals("account-b", fixture.source.validatedSessionIdentity()?.accountId)
            fixture.owner.setNetworkAllowed(false)
            assertTrue(fixture.owner.bindLocal(fixture.source, fixture.scopeB))
            val local = requireNotNull(fixture.owner.access { true })
            val current = fixture.owner.captureAction()
            val revision = fixture.owner.state.value.bindingRevision
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5_000) { login.await() } }
            fixture.owner.ownedBytes()
            assertEquals(fixture.scopeB, fixture.owner.currentScope())
            assertEquals(revision, fixture.owner.state.value.bindingRevision)
            assertTrue(current())
            assertEquals(1, local.collections().size)
            assertFalse(fixture.owner.state.value.networkEnabled)
            assertFalse(File(fixture.namespaceB, "retired").exists())
            assertEquals(1, fixture.web.requestCount)
        } finally { release.countDown(); login.cancelAndJoin(); fixture.close() }
    }

    private suspend fun TestScope.nonSecurityEventSurvivesChurn(kind: AuthenticationChangeKind) {
        val root = temporary.newFolder("auth-churn-${kind.name}")
        seedVerified(root)
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler), SavedDownloadsOwner.TestHooks(beforeAuthCommit = { change ->
            if (change.kind == kind && armed.compareAndSet(true, false)) {
                admitted.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        })) { root }
        val source = client()
        var lease: dev.spofify.mobile.core.saved.SavedMediaLease? = null
        try {
            owner.configure(source)
            val policy = SavedLocalEligibility { it == scope }
            owner.setLocalEligibility(policy)
            assertTrue(owner.bindLocal(source, scope))
            requireNotNull(owner.access { true })
            val accepted = requireNotNull(withContext(Dispatchers.IO) { owner.reader.open(scope, target, "entry") })
            lease = accepted
            assertEquals('a'.code, withContext(Dispatchers.IO) { accepted.input.read() })
            val event = async(Dispatchers.Default) { owner.authenticationChanged(source, AuthenticationChange(1, kind, null)) }
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            owner.setNetworkAllowed(false)
            owner.setLocalEligibility(policy)
            val revisionAfterChurn = owner.state.value.bindingRevision
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5_000) { event.await() } }
            owner.ownedBytes()
            assertTrue("Current SDK event must not be dropped after benign churn", owner.state.value.bindingRevision > revisionAfterChurn)
            assertEquals(scope, owner.currentScope())
            assertEquals('b'.code, withContext(Dispatchers.IO) { accepted.input.read() })
            assertFalse(owner.state.value.networkEnabled)
        } finally {
            release.countDown()
            lease?.let { withContext(Dispatchers.IO) { it.close() } }
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun dormantEventRetriesBenignChurnWithoutRevokingEligibleLocalLease() = runTest {
        nonSecurityEventSurvivesChurn(AuthenticationChangeKind.DORMANT)
    }

    @Test fun expiredEventRetriesBenignChurnWithoutRevokingEligibleLocalLease() = runTest {
        nonSecurityEventSurvivesChurn(AuthenticationChangeKind.EXPIRED)
    }

    @Test fun nonSecurityResetRetriesBenignChurnWithoutRevokingEligibleLocalLease() = runTest {
        nonSecurityEventSurvivesChurn(AuthenticationChangeKind.RESET)
    }

    @Test fun nonSecurityResetStillFencesLiveBindingWithoutRetiringRetainedBytes() = runTest {
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val fixture = AuthFixture(StandardTestDispatcher(testScheduler)) { change ->
            if (change.kind == AuthenticationChangeKind.RESET && armed.compareAndSet(true, false)) {
                admitted.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        var pending: kotlinx.coroutines.Deferred<*>? = null
        try {
            fixture.login("account-b")
            val live = requireNotNull(fixture.owner.access { true })
            assertEquals(1, live.collections().size)
            armed.set(true)
            fixture.web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            pending = async(Dispatchers.Default) { runCatching { fixture.source.login("listener-account-b", "synthetic-password") } }
            assertTrue(withContext(Dispatchers.Default) { admitted.await(5, TimeUnit.SECONDS) })
            fixture.owner.setNetworkAllowed(false)
            fixture.owner.setLocalEligibility(SavedLocalEligibility { it == fixture.scopeB })
            release.countDown()
            runCurrent()
            assertEquals("/api/auth/login", fixture.web.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/api/auth/login", fixture.web.takeRequest(5, TimeUnit.SECONDS)?.path)
            fixture.owner.ownedBytes()
            assertNull(fixture.owner.currentScope())
            assertTrue(live.collections().isEmpty())
            assertFalse(File(fixture.namespaceB, "retired").exists())
        } finally { release.countDown(); pending?.cancelAndJoin(); fixture.close() }
    }

    @Test fun immediateAccessWaitsForColdBindAndReturnsPersistedSnapshotNotFalseEmpty() = runBlocking {
        val root = temporary.newFolder("saved")
        seed(root)
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = SavedDownloadsOwner {
            creating.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            root
        }
        val source = client()
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(owner.bindLocal(source, scope))
            assertTrue(creating.await(5, TimeUnit.SECONDS))
            val access = async(Dispatchers.Default) { owner.access { true } }
            assertNull("Access must await actual IO bind", withTimeoutOrNull(100) { access.await() })
            release.countDown()
            val ready = requireNotNull(withTimeout(5_000) { access.await() })
            val collections = ready.collections()
            assertEquals(listOf(target), collections.map { it.target })
            assertEquals(listOf("entry"), collections.single().occurrences.map { it.occurrence.occurrenceId })
            assertFalse(collections.single().occurrences.single().available)
        } finally {
            release.countDown()
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun eligibilityRetiredWhileWaitingNeverReturnsAnAccessHandle() = runBlocking {
        val root = temporary.newFolder("saved")
        seed(root)
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val owner = SavedDownloadsOwner {
            creating.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            root
        }
        val source = client()
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(owner.bindLocal(source, scope))
            assertTrue(creating.await(5, TimeUnit.SECONDS))
            val access = async(Dispatchers.Default) { owner.access { current.get() } }
            assertNull(withTimeoutOrNull(100) { access.await() })
            current.set(false)
            release.countDown()
            assertNull(withTimeout(5_000) { access.await() })
            assertNull(owner.access { false })
        } finally {
            release.countDown()
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun localLeasedMediaSurvivesOrdinaryExpiryButAuthoritativeInvalidationFencesIt() = runTest {
        val root = temporary.newFolder("local-expiry-media")
        val media = seedVerified(root)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val source = client()
        var lease: dev.spofify.mobile.core.saved.SavedMediaLease? = null
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(owner.bindLocal(source, scope))
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            val accepted = requireNotNull(withContext(Dispatchers.IO) { owner.reader.open(scope, target, "entry") })
            lease = accepted
            assertEquals(media, accepted.descriptor)
            assertEquals('a'.code, withContext(Dispatchers.IO) { accepted.input.read() })
            owner.authenticationChanged(source, AuthenticationChange(1, AuthenticationChangeKind.EXPIRED, null))
            runCurrent()
            assertEquals('b'.code, withContext(Dispatchers.IO) { accepted.input.read() })
            owner.authenticationChanged(source, AuthenticationChange(2, AuthenticationChangeKind.DORMANT, null))
            runCurrent()
            assertEquals('c'.code, withContext(Dispatchers.IO) { accepted.input.read() })
            owner.authenticationChanged(source, AuthenticationChange(3, AuthenticationChangeKind.RESET, null))
            runCurrent()
            assertEquals('d'.code, withContext(Dispatchers.IO) { accepted.input.read() })
            owner.authenticationChanged(source, AuthenticationChange(4, AuthenticationChangeKind.INVALIDATED, null))
            try {
                withContext(Dispatchers.IO) { accepted.input.read() }
                fail("Authoritative invalidation must fence accepted local bytes")
            } catch (_: IOException) { }
            assertNull(owner.access { true })
            assertNull(withContext(Dispatchers.IO) { owner.reader.open(scope, target, "entry") })
        } finally {
            lease?.let { accepted -> withContext(Dispatchers.IO) { accepted.close() } }
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun TestScope.onlineReturn(expired: Boolean) {
        val web = MockWebServer()
        web.start()
        val savedScope = SavedScope("http://127.0.0.1:${web.port}", "account")
        val root = temporary.newFolder(if (expired) "expired-online" else "valid-online")
        seed(root, savedScope)
        val clock = AtomicLong(System.currentTimeMillis())
        val localOnly = AtomicBoolean(true)
        val source = client(savedScope.origin, clock::get)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == savedScope && localOnly.get() })
            source.onAuthenticationChanged = { owner.authenticationChanged(source, it) }
            web.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                .setBody("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}"""))
            source.login("listener", "synthetic-password")
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            owner.setNetworkAllowed(false)
            assertTrue(owner.bindLocal(source, savedScope))
            val local = requireNotNull(owner.access { true })
            assertEquals(1, local.collections().size)
            assertFalse(owner.state.value.networkEnabled)
            if (expired) clock.addAndGet(2 * 60 * 60 * 1000L)
            localOnly.set(false) // The offline-only policy cannot become network authentication.
            owner.setNetworkAllowed(true)
            runCurrent()
            assertEquals(!expired, owner.state.value.networkEnabled)
            assertTrue(owner.state.value.working.isEmpty())
            assertEquals("Mode return performs no HTTP probe, manifest or transfer", 1, web.requestCount)
            if (expired) {
                assertNull(source.validatedSessionIdentity())
                assertFalse(owner.state.value.networkEnabled)
            } else {
                assertTrue("Actual local-to-live handoff fences prior reader incarnation", local.collections().isEmpty())
                assertEquals(1, requireNotNull(owner.access { true }).collections().size)
                owner.save(target, "Saved artist")
                assertTrue("Only a new explicit Save admits work", target in owner.state.value.working)
                owner.setNetworkAllowed(false)
                runCurrent()
                assertEquals(1, web.requestCount)
            }
        } finally {
            source.onAuthenticationChanged = null
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
            web.shutdown()
        }
    }

    @Test fun validSessionOnlineReturnEnablesOnlyNewExplicitDownloadsWithoutHttpOrRestart() = runTest {
        onlineReturn(expired = false)
    }

    @Test fun expiredSessionOnlineReturnCannotEnableDownloadsOrProbeHttp() = runTest {
        onlineReturn(expired = true)
    }

    @Test fun snapshotPublicationHeldAcrossAuthClearCannotResurrectOldRowsOnCasRetry() = runBlocking {
        val root = temporary.newFolder("publish")
        seed(root)
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = AtomicBoolean(false)
        val leaked = AtomicBoolean(false)
        val owner = SavedDownloadsOwner(Dispatchers.IO, beforeSnapshotPublish = {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }) { root }
        val source = client()
        val observer = launch(Dispatchers.Unconfined) {
            owner.state.collect { if (retired.get() && it.collections.isNotEmpty()) leaked.set(true) }
        }
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(owner.bindLocal(source, scope))
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            armed.set(true)
            owner.setNetworkAllowed(false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            retired.set(true)
            owner.retire()
            assertTrue(owner.state.value.collections.isEmpty())
            release.countDown()
            owner.ownedBytes() // Actor barrier after the held publisher and retirement.
            assertFalse("No old-account rows may be emitted after auth clear", leaked.get())
            assertTrue(owner.state.value.collections.isEmpty())
        } finally {
            release.countDown()
            observer.cancelAndJoin()
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    private enum class RetainedRebind { EXPIRY, SAME_ORIGIN, RESET }

    private suspend fun TestScope.pendingUnsaveSurvives(transition: RetainedRebind) {
        val root = temporary.newFolder("remove-${transition.name}")
        seed(root)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SavedDownloadsOwner(dispatcher) { root }
        val sources = mutableListOf(client())
        var active = sources.single()
        var ownerClosed = false
        var restarted: SavedDownloadsOwner? = null
        try {
            owner.configure(active)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(owner.bindLocal(active, scope))
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            owner.unsave(target)
            assertTrue(owner.state.value.collections.isEmpty())
            when (transition) {
                RetainedRebind.EXPIRY -> owner.authenticationChanged(active, AuthenticationChange(1, AuthenticationChangeKind.EXPIRED, null))
                RetainedRebind.RESET -> owner.authenticationChanged(active, AuthenticationChange(1, AuthenticationChangeKind.RESET, null))
                RetainedRebind.SAME_ORIGIN -> {
                    owner.prepareConfigure(scope.origin)
                    active = client().also { sources += it }
                    owner.configure(active)
                }
            }
            assertTrue(owner.bindLocal(active, scope))
            runCurrent()
            assertTrue(requireNotNull(owner.access { true }).collections().isEmpty())
            val metadata = File(root, "metadata")
            val pointer = metadata.listFiles()!!.single { it.name.endsWith(".current") }
            assertTrue("Unsave must persist before owner close", File(metadata, pointer.readText()).listFiles()!!.any { it.name.endsWith(".removed") })
            ownerClosed = true
            owner.close()
            val next = SavedDownloadsOwner(dispatcher) { root }
            restarted = next
            next.configure(active)
            next.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(next.bindLocal(active, scope))
            assertTrue(requireNotNull(next.access { true }).collections().isEmpty())
        } finally {
            if (!ownerClosed) owner.close()
            restarted?.close()
            sources.forEach {
                it.httpClient.connectionPool.evictAll()
                it.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun queuedUnsavePersistsAcrossExpiryAndSameScopeReattach() = runTest {
        pendingUnsaveSurvives(RetainedRebind.EXPIRY)
    }

    @Test fun queuedUnsavePersistsAcrossSameOriginClientReconfigure() = runTest {
        pendingUnsaveSurvives(RetainedRebind.SAME_ORIGIN)
    }

    @Test fun queuedUnsavePersistsAcrossResetAndSameScopeReattach() = runTest {
        pendingUnsaveSurvives(RetainedRebind.RESET)
    }

    @Test fun queuedUnsaveNeverBorrowsDifferentAccountWithSameTargetId() = runTest {
        val root = temporary.newFolder("account-removal")
        val otherScope = scope.copy(accountId = "other-account")
        seed(root)
        seed(root, otherScope)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val source = client()
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it.origin == scope.origin })
            assertTrue(owner.bindLocal(source, scope))
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            owner.unsave(target)
            assertTrue(owner.bindLocal(source, otherScope))
            runCurrent()
            assertEquals(otherScope, owner.currentScope())
            assertEquals(listOf(target), requireNotNull(owner.access { true }).collections().map { it.target })
        } finally {
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun newerExplicitSaveSupersedesQueuedUnsaveWithoutDeletingRetainedMembership() = runTest {
        val web = MockWebServer()
        web.start()
        val savedScope = SavedScope("http://127.0.0.1:${web.port}", "account")
        val root = temporary.newFolder("resave")
        seed(root, savedScope)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        val source = client(savedScope.origin)
        try {
            owner.configure(source)
            source.onAuthenticationChanged = { owner.authenticationChanged(source, it) }
            web.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                .setBody("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}"""))
            source.login("listener", "synthetic-password")
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            owner.unsave(target)
            owner.save(target, "Saved artist")
            owner.setNetworkAllowed(false) // Cancel media work, not the newer explicit retention choice.
            runCurrent()
            assertEquals(listOf(target), requireNotNull(owner.access { true }).collections().map { it.target })
            assertEquals("Only synthetic login; no media/manifest request", 1, web.requestCount)
        } finally {
            source.onAuthenticationChanged = null
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
            web.shutdown()
        }
    }

    private enum class ColdRetirement { LOGOUT, ORIGIN_SWITCH, ACCOUNT_SWITCH }

    private suspend fun TestScope.coldRetirement(transition: ColdRetirement, createUnboundStoreFirst: Boolean = false) {
        val root = temporary.newFolder("saved")
        seed(root)
        val metadata = File(root, "metadata")
        val pointer = metadata.listFiles()!!.single { it.name.endsWith(".current") }
        val oldNamespace = File(metadata, pointer.readText())
        assertFalse(File(oldNamespace, "retired").exists())
        var constructions = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SavedDownloadsOwner(dispatcher) { constructions++; root }
        val source = client()
        var ownerClosed = false
        var restarted: SavedDownloadsOwner? = null
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it.origin == scope.origin })
            if (createUnboundStoreFirst) owner.ownedBytes() // Non-null store, but fence has no bound namespace.
            assertTrue(owner.bindLocal(source, scope))
            // No actor work is allowed between publishing the local binding and the retirement.
            when (transition) {
                ColdRetirement.LOGOUT -> owner.retire()
                ColdRetirement.ORIGIN_SWITCH -> owner.prepareConfigure("https://other.example")
                ColdRetirement.ACCOUNT_SWITCH -> assertTrue(owner.bindLocal(source, scope.copy(accountId = "other-account")))
            }
            runCurrent()
            assertEquals("Cold retirement must initialize its IO store", 1, constructions)
            assertTrue("Retirement must be durable BEFORE close/shutdown", File(oldNamespace, "retired").isFile)
            if (transition == ColdRetirement.ACCOUNT_SWITCH) {
                assertEquals("other-account", owner.currentScope()?.accountId)
                assertTrue(requireNotNull(owner.access { true }).collections().isEmpty())
            }
            ownerClosed = true
            owner.close() // Only after the pre-close durable-marker assertion: cannot mask the bug.
            val next = SavedDownloadsOwner(dispatcher) { root }
            restarted = next
            next.configure(source)
            next.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(next.bindLocal(source, scope))
            assertTrue(requireNotNull(next.access { true }).collections().isEmpty())
        } finally {
            if (!ownerClosed) owner.close()
            restarted?.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun coldLogoutBeforeActorBindPurgesPersistedNamespaceBeforeShutdown() = runTest {
        coldRetirement(ColdRetirement.LOGOUT)
    }

    @Test fun coldOriginSwitchBeforeActorBindPurgesPersistedNamespaceBeforeShutdown() = runTest {
        coldRetirement(ColdRetirement.ORIGIN_SWITCH)
    }

    @Test fun emptyFenceDoesNotSuppressKnownPersistedScopeRetirement() = runTest {
        coldRetirement(ColdRetirement.LOGOUT, createUnboundStoreFirst = true)
    }

    @Test fun coldAccountSwitchRetiresPreviousScopeBeforeSuccessorBind() = runTest {
        coldRetirement(ColdRetirement.ACCOUNT_SWITCH)
    }

    @Test fun resetAndExpiryPreserveEligibleLocalAccessWhileReattachFencesPriorIncarnation() = runBlocking {
        val root = temporary.newFolder("saved")
        seed(root)
        val owner = SavedDownloadsOwner { root }
        val source = client()
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(owner.bindLocal(source, scope))
            val original = requireNotNull(owner.access { true })
            assertEquals(1, original.collections().size)
            owner.authenticationChanged(source, AuthenticationChange(1, AuthenticationChangeKind.RESET, null))
            assertEquals(1, original.collections().size)
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            assertTrue(owner.bindLocal(source, scope))
            val reattached = requireNotNull(owner.access { true })
            assertEquals(1, reattached.collections().size)
            assertTrue(original.collections().isEmpty())
            owner.authenticationChanged(source, AuthenticationChange(2, AuthenticationChangeKind.EXPIRED, null))
            assertEquals(1, reattached.collections().size)
            val dormant = requireNotNull(owner.access { true })
            assertEquals(1, dormant.collections().size)
            dormant.retire()
            assertTrue(dormant.collections().isEmpty())
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
        } finally {
            owner.close()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }
}
