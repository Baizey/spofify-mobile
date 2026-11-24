package dev.spofify.mobile.playback

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.origin.ServerOrigin
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.personal.AccountPersonalReplica.Resource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AccountPersonalSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val origin = ServerOrigin.parse("https://offline-fixture.test")
    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private val summary = PersonalPlaylistSummary("p", "Saved name", "", PersonalPlaylistKind.MANUAL,
        PersonalCountState.EXACT, 0, 0, PersonalPlaylistCapabilities(true, true, false, true, false, true, true), 1, "", "")

    @Test fun staleViewsRenderFromDiskShareOneFetchAndKeepSnapshotOnPartialResponse() = runTest {
        val fixture = Fixture(this, temporary.root, stale = true)
        runCurrent()
        val view = fixture.source.replica.view.value
        assertEquals("Saved name", view.playlists.single().name)
        assertTrue(view.historyKnown)
        repeat(6) { fixture.source.checkFreshness() }
        runCurrent()
        assertEquals(1, fixture.listCalls)
        assertEquals(1, fixture.historyCalls)
        fixture.list.complete(PersonalPage(listOf(summary.copy(name = "Partial response")), 2, 100, 0))
        runCurrent()
        assertEquals("Saved name", fixture.source.replica.view.value.playlists.single().name)
        assertEquals(at.toEpochMilli() - 300_000, fixture.source.replica.snapshots.value.listsAt)
        assertFalse(fixture.source.replica.stale(Resource.History))
        assertTrue(fixture.source.replica.stale(Resource.Lists))
        assertEquals(1, fixture.listCalls)
    }

    @Test fun freshListIsNotFetchedAndRetiredResponseCannotOverwriteDisk() = runTest {
        val fixture = Fixture(this, temporary.root, stale = false)
        runCurrent()
        repeat(6) { fixture.source.checkFreshness() }
        runCurrent()
        assertEquals(0, fixture.listCalls)
        fixture.clock = Clock.offset(fixture.clock, java.time.Duration.ofMinutes(5))
        fixture.source.refresh(Resource.Lists)
        runCurrent()
        assertEquals(1, fixture.listCalls)
        fixture.sessions.signOut()
        fixture.list.complete(PersonalPage(listOf(summary.copy(name = "Late response")), 1, 100, 0))
        runCurrent()
        assertEquals("Saved name", fixture.source.replica.view.value.playlists.single().name)
        assertEquals("Saved name", fixture.saved.read().playlists.single().name)
    }

    @Test fun watchedInvalidationRepairsAfterOlderRequestFailsWithoutAnotherScreenEntry() = runTest {
        val fixture = Fixture(this, temporary.root, stale = false)
        runCurrent()
        val owner = requireNotNull(fixture.sessions.onlineGrant.value)
        fixture.source.applyConfirmed(owner, PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary), emptyList())))
        val watch = fixture.source.watch(summary.id)
        try {
            runCurrent()
            assertEquals(1, fixture.detailCalls)
            val changed = summary.copy(name = "Confirmed", revision = 2, trackCount = 1, availableCount = 1)
            fixture.liveSummary = changed
            fixture.source.applyConfirmed(owner, PersonalUpdate(change = PersonalChange(playlist = changed, rowsInvalid = true)))
            assertEquals("Confirmed", fixture.source.replica.view.value.playlists.single().name)
            fixture.oldDetail.completeExceptionally(PersonalLibraryException(503, PersonalLibraryErrorCode.HTTP_ERROR))
            runCurrent()
            assertEquals(1, fixture.detailCalls)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(3, fixture.detailCalls)
            val repaired = fixture.source.replica.snapshots.value.details.getValue(summary.id)
            assertEquals(listOf("new-occurrence"), repaired.rows.map { it.id })
            assertEquals(2L, repaired.revision)
            assertFalse(fixture.source.replica.stale(Resource.Detail(summary.id)))
        } finally { watch.close() }
    }

    private inner class Fixture(test: TestScope, root: java.io.File, stale: Boolean) {
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        var clock = Clock.fixed(at, ZoneOffset.UTC)
        private val clockProxy = object : Clock() {
            override fun instant() = clock.instant()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = clock.withZone(zone)
        }
        private val identity = AccountIdentity(origin.value, "account", "Listener", 1)
        private val account = SessionSummary(identity.accountId, identity.username, "user", "csrf-fixture")
        private val session = StoredSession(origin.value, "__Host-spofify_session=fixture; Path=/; HttpOnly; Secure", account, null)
        val sessions = SessionRepository(object : SessionStore {
            override fun read() = session
            override fun write(value: StoredSession?) = Unit
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin) = Unit
        }, AuthApiFactory { server ->
            object : AuthApi {
                override val origin = server
                override fun cancel() = Unit
                override fun validateCookie(cookie: String) = Unit
                override suspend fun login(username: String, password: String) = error("Unexpected login")
                override suspend fun session(cookie: String) = AuthReply(account, null, null)
                override suspend fun logout(cookie: String, csrfToken: String) = Unit
            }
        }, test.backgroundScope, dispatcher)
        val saved = PersonalCacheFile(root, identity)
        val list = CompletableDeferred<PersonalPage<PersonalPlaylistSummary>>()
        var listCalls = 0
        var historyCalls = 0
        var detailCalls = 0
        var liveSummary = summary
        val oldDetail = CompletableDeferred<PersonalPlaylistSummary>()
        private val client = object : PersonalLibraryApi by PersonalLibraryClient(sessions, identity) {
            override suspend fun playlists(limit: Int, offset: Int, kind: PersonalPlaylistKind?): PersonalPage<PersonalPlaylistSummary> {
                listCalls++
                return withContext(NonCancellable) { list.await() }
            }
            override suspend fun playlist(id: String): PersonalPlaylistSummary =
                if (++detailCalls == 1) oldDetail.await() else liveSummary
            override suspend fun manualEntries(id: String, limit: Int, offset: Int): PersonalPage<PersonalManualEntry> =
                PersonalPage(listOf(PersonalManualEntry("new-occurrence", 0,
                    PersonalTrack("track", "new-track", "New track", true, PersonalCoverage(1, 1, 1)))), 1, limit, offset)
            override suspend fun history(limit: Int, offset: Int): PersonalPage<PersonalHistoryEntry> {
                historyCalls++
                return PersonalPage(emptyList(), 0, limit, offset)
            }
        }
        val source: AccountPersonalSource
        init {
            val stamp = at.toEpochMilli() - if (stale) 300_000 else 0
            saved.write(PersonalCacheSnapshot(listOf(summary), listsAt = stamp, recentAt = stamp))
            sessions.start()
            test.runCurrent()
            assertEquals(identity, sessions.onlineGrant.value?.identity)
            source = AccountPersonalSource(root, identity, sessions, test.backgroundScope, client,
                AccountPersonalReplica(clockProxy), dispatcher) { test.testScheduler.currentTime }
        }
    }
}
