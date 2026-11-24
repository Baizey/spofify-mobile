package dev.spofify.mobile.core.auth

import dev.spofify.mobile.core.origin.ServerOrigin
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {
    private val origin = ServerOrigin.parse("http://fixture.test:6030")
    private fun summary(id: String = "fixture-id") = SessionSummary(id, "fixture-user", "user", "fixture-csrf")
    private fun cookie() = "spofify_session=${UUID.randomUUID()}; Path=/; HttpOnly"
    private fun stored(id: String = "fixture-id", cookie: String = cookie()) =
        StoredSession(origin.value, cookie, summary(id), "rolling-v1")
    private fun remembered() = RememberedLogin(origin.value, "fixture-user", UUID.randomUUID().toString())

    private class Sessions(var record: StoredSession? = null) : SessionStore {
        var readFails = false
        var writeFails = false
        val writes = mutableListOf<StoredSession?>()
        override fun read(): StoredSession? {
            if (readFails) throw IOException()
            return record
        }
        override fun write(value: StoredSession?) {
            if (writeFails) throw IOException()
            record = value
            writes.add(value)
        }
    }
    private class Logins(var record: RememberedLogin? = null) : LoginStore {
        var fails = false
        override fun read() = record
        override fun write(value: RememberedLogin) {
            if (fails) throw IOException()
            record = value
        }
    }
    private inner class Api : AuthApi {
        override val origin = this@SessionRepositoryTest.origin
        var loginReply = AuthReply(summary(), cookie(), "rolling-v1")
        var sessionReply = AuthReply(summary(), null, null)
        var sessionError: Exception? = null
        var loginError: Exception? = null
        var logoutError: Exception? = null
        var loginGate: CompletableDeferred<Unit>? = null
        var sessionGate: CompletableDeferred<Unit>? = null
        var beforeSessionReturn: (() -> Unit)? = null
        var logoutGate: CompletableDeferred<Unit>? = null
        var loginCalls = 0
        var sessionCalls = 0
        var logoutCalls = 0
        var logoutCookie: String? = null
        override suspend fun login(username: String, password: String): AuthReply {
            loginCalls++
            loginGate?.let { withContext(NonCancellable) { it.await() } }
            loginError?.let { throw it }
            return loginReply
        }
        override suspend fun session(cookie: String): AuthReply {
            sessionCalls++
            sessionGate?.let { withContext(NonCancellable) { it.await() } }
            beforeSessionReturn?.invoke()
            sessionError?.let { throw it }
            return sessionReply
        }
        override suspend fun logout(cookie: String, csrfToken: String) {
            logoutCalls++
            logoutCookie = cookie
            logoutGate?.await()
            logoutError?.let { throw it }
        }
        override fun validateCookie(cookie: String) { require(cookie.isNotEmpty()) }
        override fun cancel() = Unit // Deliberately uncooperative to test publication fences.
    }

    private class Policy(var offline: Boolean = false) : ManualOfflineStore {
        var unreadable = false
        var unwritable = false
        override fun read(): Boolean { if (unreadable) throw IOException(); return offline }
        override fun write(enabled: Boolean) { if (unwritable) throw IOException(); offline = enabled }
    }
    private fun TestScope.repository(
        sessions: Sessions, logins: Logins, vararg api: Api, policy: Policy? = null,
        installationNetworkAllowed: (Boolean) -> Unit = {},
    ): SessionRepository {
        val queue = ArrayDeque(api.toList())
        return SessionRepository(sessions, logins, AuthApiFactory { queue.removeFirst() },
            this, StandardTestDispatcher(testScheduler), policy, installationNetworkAllowed)
    }

    @Test fun installationNetworkFencesEverySwitchBeforeThePlaybackHandoff() = runTest {
        val policy = Policy()
        val modes = mutableListOf<Pair<Boolean, Boolean>>()
        val repo = repository(Sessions(stored()), Logins(), Api(), Api(), policy = policy,
            installationNetworkAllowed = { allowed -> modes += allowed to policy.offline })
        repo.start()
        assertTrue(modes.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(true to false), modes)

        repo.setManualOffline(true) {
            assertTrue(policy.offline)
            assertFalse(repo.manualOffline.value)
            // The installation fence has already run, before the durable write.
            assertEquals(listOf(true to false, false to false), modes)
        }
        repo.setManualOffline(false)
        assertEquals(listOf(true to false, false to false, true to false), modes)
    }

    @Test fun installationNetworkStartsClosedAndDoesNotUseTheDefaultOnColdReadFailure() = runTest {
        val modes = mutableListOf<Boolean>()
        val policy = Policy().apply { unreadable = true }
        val repo = repository(Sessions(null), Logins(), policy = policy, installationNetworkAllowed = modes::add)
        repo.start(); advanceUntilIdle()
        assertFalse(repo.offlinePolicyLoaded.value)
        assertTrue(modes.isEmpty())
    }

    @Test fun installationNetworkRemainsFencedIfTheOfflineWriteFails() = runTest {
        val modes = mutableListOf<Boolean>()
        val policy = Policy()
        val repo = repository(Sessions(null), Logins(), policy = policy, installationNetworkAllowed = modes::add)
        repo.start(); advanceUntilIdle()
        policy.unwritable = true
        assertTrue(runCatching { repo.setManualOffline(true) }.exceptionOrNull() is IOException)
        assertFalse(repo.manualOffline.value)
        assertEquals(listOf(true, false), modes)
    }

    private fun transientUnavailable() = AuthFailure(AuthFailure.Kind.UNAVAILABLE, transient = true)

    @Test fun modeEntryJoinsUncooperativeAuthAndDiscardsLate401OrRenewal() = runTest {
        for (unauthorized in listOf(true, false)) {
            val original = stored(cookie = "spofify_session=old; Path=/; HttpOnly")
            val sessions = Sessions(original)
            val old = Api().apply {
                sessionGate = CompletableDeferred()
                if (unauthorized) sessionError = AuthFailure(AuthFailure.Kind.UNAUTHORIZED)
                else sessionReply = AuthReply(summary(), "stale-cookie", "rolling-v1")
            }
            val repo = repository(sessions, Logins(), old, policy = Policy())
            repo.start(); runCurrent()
            val entering = launch { repo.setManualOffline(true) }
            runCurrent(); assertTrue(repo.manualOffline.value); assertFalse(entering.isCompleted)
            old.sessionGate!!.complete(Unit); advanceUntilIdle(); assertTrue(entering.isCompleted)
            assertTrue(repo.state.value is AuthState.Authenticated); assertNull(repo.onlineGrant.value)
            assertSame(original, sessions.record); assertTrue(sessions.writes.isEmpty())
        }
    }

    @Test fun policyFailuresDoNotFallbackToNetworkOrAdvertiseUndurableSwitch() = runTest {
        val policy = Policy(true).apply { unreadable = true }
        val repo = repository(Sessions(stored(cookie = "spofify_session=x; Path=/; HttpOnly")), Logins(), policy = policy)
        repo.start(); advanceUntilIdle(); repo.signIn(origin.value, "user", "no"); repo.refresh()
        assertEquals(AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE), repo.state.value)
        assertFalse(repo.offlinePolicyLoaded.value)
        repo.useManualLogin()
        assertEquals(AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE), repo.state.value)
        policy.unreadable = false
        repo.setAppForeground(false); repo.setAppForeground(true); repo.start(); advanceUntilIdle()
        assertEquals(AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE), repo.state.value)
        val local = repository(Sessions(stored(cookie = "spofify_session=x; Path=/; HttpOnly")), Logins(), policy = policy)
        local.start(); advanceUntilIdle()
        assertTrue(local.offlinePolicyLoaded.value)
        val id = (local.state.value as AuthState.Authenticated).identity
        policy.unwritable = true
        assertTrue(runCatching { local.setManualOffline(false) }.exceptionOrNull() is IOException)
        assertTrue(local.manualOffline.value); assertTrue(local.isCurrent(id)); assertNull(local.onlineGrant.value)
    }

    @Test fun returnValidationRetainsLocalIdentityWithoutGrantButCurrent401PurgesIt() = runTest {
        val sessions = Sessions(stored(cookie = "spofify_session=x; Path=/; HttpOnly"))
        val api = Api().apply { sessionError = transientUnavailable() }
        val policy = Policy(true)
        val repo = repository(sessions, Logins(), api, policy = policy)
        repo.start(); advanceUntilIdle(); val id = (repo.state.value as AuthState.Authenticated).identity
        repo.setManualOffline(false); runCurrent()
        assertTrue(repo.isCurrent(id)); assertNull(repo.onlineGrant.value)
        assertNull((repo.state.value as AuthState.Authenticated).notice)
        api.sessionGate = CompletableDeferred()
        api.sessionError = AuthFailure(AuthFailure.Kind.UNAUTHORIZED)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(repo.isCurrent(id)); assertNull(repo.onlineGrant.value)
        api.sessionGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(2, api.sessionCalls)
        assertFalse(repo.isCurrent(id)); assertNull(sessions.record)
        assertEquals(AuthState.SignedOut(AuthNotice.SESSION_EXPIRED), repo.state.value)
        repo.setManualOffline(true); advanceUntilIdle(); assertFalse(repo.isCurrent(id))
        val restarted = repository(sessions, Logins(), policy = policy)
        restarted.start(); advanceUntilIdle(); assertEquals(AuthState.SignedOut(), restarted.state.value)
    }

    @Test fun handoffTimeoutCannotPreventRetirementAndModeEntryCancelsPendingLogout() = runTest {
        val policy = Policy()
        val sessions = Sessions(stored(cookie = "spofify_session=x; Path=/; HttpOnly"))
        val logout = Api().apply { logoutGate = CompletableDeferred() }
        val repo = repository(sessions, Logins(), Api(), Api(), logout, policy = policy)
        repo.start(); advanceUntilIdle(); val id = (repo.state.value as AuthState.Authenticated).identity
        repo.setManualOffline(true) { awaitCancellation() }
        assertTrue(repo.isCurrent(id)); assertNull(repo.onlineGrant.value)
        repo.setManualOffline(false); advanceUntilIdle(); repo.signOut(); runCurrent()
        assertNotNull(logout.logoutCookie); assertNull(sessions.record)
        repo.setManualOffline(true); advanceUntilIdle(); assertEquals(AuthState.SignedOut(), repo.state.value)
    }

    @Test fun coldRestorePublishesLocalIdentityWhileHttpBlockedAndGrantsOnlyOnValidation() = runTest {
        val old = stored()
        val sessions = Sessions(old)
        val first = Api().apply { sessionGate = CompletableDeferred() }
        val second = Api().apply {
            sessionReply = AuthReply(SessionSummary("fixture-id", "new-name", "admin", "new-csrf"), cookie(), "rolling-v1")
        }
        val repo = repository(sessions, Logins(), first, second)
        repo.start()
        assertEquals(AuthState.Starting, repo.state.value)
        runCurrent()
        val identity = (repo.state.value as AuthState.Authenticated).identity
        assertEquals(1, first.sessionCalls)
        assertEquals(OnlineValidation.VALIDATING, repo.onlineValidation.value)
        assertNull(repo.onlineGrant.value)
        assertTrue(repo.isCurrent(identity))
        assertTrue(runCatching { repo.get(identity, listOf("api", "library")) }.exceptionOrNull() is CancellationException)
        assertTrue(sessions.writes.isEmpty())
        first.sessionGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(OnlineValidation.VALIDATED, repo.onlineValidation.value)
        assertEquals(identity, repo.onlineGrant.value?.identity)
        assertEquals(old.cookie, sessions.record!!.cookie)
        repo.refresh()
        assertTrue(repo.isCurrent(identity))
        assertNull(repo.onlineGrant.value)
        advanceUntilIdle()
        assertEquals(identity, (repo.state.value as AuthState.Authenticated).identity)
        assertEquals(second.sessionReply.cookie, sessions.record!!.cookie)
        assertEquals("new-csrf", sessions.record!!.session.csrfToken)
        assertTrue(repo.isAdministrator(identity))
    }

    @Test fun restoredIdentityUsesCanonicalOriginNotLegacySpelling() = runTest {
        val legacy = StoredSession("HTTP://FIXTURE.test:6030/", cookie(), summary())
        val sessions = Sessions(legacy)
        val repo = repository(sessions, Logins(), Api())
        repo.start()
        advanceUntilIdle()
        assertEquals(origin.value, (repo.state.value as AuthState.Authenticated).identity.origin)
        assertEquals(origin.value, sessions.record!!.server)
    }

    @Test fun restore401RevokesOnlySessionAndRetainsRememberedTriple() = runTest {
        val login = remembered()
        val logins = Logins(login)
        val sessions = Sessions(stored())
        val repo = repository(sessions, logins, Api().apply { sessionError = AuthFailure(AuthFailure.Kind.UNAUTHORIZED) })
        repo.start()
        advanceUntilIdle()
        assertEquals(AuthState.SignedOut(AuthNotice.SESSION_EXPIRED), repo.state.value)
        assertNull(sessions.record)
        assertSame(login, logins.record)
    }

    @Test fun coldRestoreCanScheduleRecoveryBeforeFirstForegroundWithoutDuplicateBootstrap() = runTest {
        val old = stored()
        val sessions = Sessions(old)
        val api = Api().apply { sessionError = transientUnavailable() }
        val repo = repository(sessions, Logins(remembered()), api)
        repo.start(); runCurrent()
        assertEquals(1, api.sessionCalls)
        assertTrue(repo.state.value is AuthState.Authenticated)
        assertEquals(OnlineValidation.VALIDATING, repo.onlineValidation.value)
        assertNull(repo.onlineGrant.value)
        assertSame(old, sessions.record)
        assertTrue(sessions.writes.isEmpty())
        advanceTimeBy(500)
        repo.setAppForeground(true); repo.start(); repo.refresh(); runCurrent()
        assertEquals(1, api.sessionCalls)
        api.sessionError = null
        advanceTimeBy(500); runCurrent()
        assertTrue(repo.state.value is AuthState.Authenticated)
        assertNotNull(repo.onlineGrant.value)
        assertEquals(2, api.sessionCalls)
        assertEquals(0, api.loginCalls)
    }

    @Test fun returnValidationIsBoundedAndTerminalFailureCannotStartAnotherLoop() = runTest {
        val original = stored(cookie = "spofify_session=x; Path=/; HttpOnly")
        val sessions = Sessions(original)
        val api = Api().apply { sessionError = transientUnavailable() }
        val repo = repository(sessions, Logins(), api, policy = Policy(true))
        repo.start(); advanceUntilIdle()
        val id = (repo.state.value as AuthState.Authenticated).identity
        repo.setManualOffline(false); runCurrent()
        assertEquals(1, api.sessionCalls)
        for ((index, wait) in listOf(1_000L, 2_000L, 4_000L).withIndex()) {
            advanceTimeBy(wait - 1); runCurrent()
            assertEquals(index + 1, api.sessionCalls)
            assertEquals(AuthState.Authenticated(id), repo.state.value)
            assertNull(repo.onlineGrant.value)
            advanceTimeBy(1); runCurrent()
            assertEquals(index + 2, api.sessionCalls)
        }
        assertEquals(AuthState.Authenticated(id, AuthNotice.CONNECTION_FAILED), repo.state.value)
        repo.setAppForeground(false); repo.setAppForeground(true); repo.refresh()
        advanceUntilIdle()
        assertEquals(7_000L, currentTime)
        assertEquals(4, api.sessionCalls)
        assertSame(original, sessions.record)
        assertTrue(sessions.writes.isEmpty())
        assertNull(repo.onlineGrant.value)
    }

    @Test fun goOnlineStartsFreshValidationAndOnlyCurrentGrantCanPersistFallback() = runTest {
        val original = stored()
        val sessions = Sessions(original)
        val policy = Policy()
        val first = Api().apply { sessionReply = AuthReply(summary("different-account"), cookie(), "rolling-v1") }
        val second = Api().apply { sessionGate = CompletableDeferred() }
        val third = Api()
        val repo = repository(sessions, Logins(), first, second, third, policy = policy)
        repo.start(); advanceUntilIdle()
        val id = (repo.state.value as AuthState.Authenticated).identity
        assertEquals(original.session.accountId, id.accountId)
        assertLocalConnectionFailure(repo)
        assertSame(original, sessions.record)
        assertTrue(sessions.writes.isEmpty())
        repo.setManualOffline(false); runCurrent()
        assertEquals(OnlineValidation.VALIDATING, repo.onlineValidation.value)
        assertTrue(repo.isCurrent(id))
        repo.setManualOffline(false); runCurrent()
        assertEquals(1, second.sessionCalls)
        second.sessionGate!!.complete(Unit); advanceUntilIdle()
        val retired = requireNotNull(repo.onlineGrant.value)
        repo.refresh(); advanceUntilIdle()
        val current = requireNotNull(repo.onlineGrant.value)
        assertFalse(repo.fallbackToManualOffline(retired)); assertFalse(policy.offline)
        assertTrue(repo.fallbackToManualOffline(current)); assertTrue(policy.offline)
        assertEquals(OnlineValidation.IDLE, repo.onlineValidation.value)
        assertNull(repo.onlineGrant.value); assertTrue(repo.isCurrent(id))
        assertFalse(repo.fallbackToManualOffline(current))
        assertEquals(0, third.logoutCalls)
    }

    @Test fun staleFallbackAfterBlockedPolicyWriteDoesNotRetireSuccessor() = runBlocking {
        val policyWriteStarted = CompletableDeferred<Unit>()
        val releasePolicyWrite = CountDownLatch(1)
        val policy = object : ManualOfflineStore {
            val stored = AtomicBoolean(false)
            override fun read() = stored.get()
            override fun write(enabled: Boolean) {
                if (enabled) {
                    policyWriteStarted.complete(Unit)
                    check(releasePolicyWrite.await(5, TimeUnit.SECONDS)) { "Blocked write was not released" }
                }
                stored.set(enabled)
            }
        }
        val first = Api()
        val successor = Api().apply {
            loginReply = AuthReply(summary("successor"), cookie(), "rolling-v1")
            sessionReply = AuthReply(summary("successor"), null, null)
        }
        val logout = Api()
        val logoutCreated = CompletableDeferred<Unit>()
        val clients = ConcurrentLinkedQueue(listOf(first, logout, successor))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SessionRepository(Sessions(stored()), Logins(), AuthApiFactory {
            clients.remove().also { if (it === logout) logoutCreated.complete(Unit) }
        }, owner, manualOfflineStore = policy)
        try {
            withTimeout(5_000) {
                repo.start()
                val firstGrant = repo.onlineGrant.first { it != null }!!
                val staleFallback = owner.async { repo.fallbackToManualOffline(firstGrant) }
                policyWriteStarted.await()
                repo.signOut()
                logoutCreated.await()
                repo.signIn(origin.value, "successor", "fixture-password")
                val next = repo.onlineGrant.first { it?.identity?.accountId == "successor" }!!
                releasePolicyWrite.countDown()
                assertFalse(staleFallback.await())
                assertSame(next, repo.onlineGrant.value)
                assertTrue(repo.isCurrent(next.identity))
                assertFalse(repo.manualOffline.value)
                assertFalse(policy.stored.get())
            }
        } finally {
            releasePolicyWrite.countDown()
            owner.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun structurallyInvalidStoredCookieCannotGrantLocalOrNetworkAuthority() = runTest {
        val original = stored(cookie = "spofify_session=x; Domain=fixture.test; Path=/; HttpOnly")
        val sessions = Sessions(original)
        val repo = repository(sessions, Logins()) // No transport factory call is allowed.
        repo.start(); advanceUntilIdle()
        assertEquals(AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE), repo.state.value)
        assertNull(repo.onlineGrant.value)
        assertSame(original, sessions.record)
        assertTrue(sessions.writes.isEmpty())
    }

    @Test fun unclassifiedAndInvalidSessionFailuresNeverBackOff() = runTest {
        for (error in listOf(AuthFailure(AuthFailure.Kind.UNAVAILABLE), AuthFailure(AuthFailure.Kind.INVALID_RESPONSE), IOException())) {
            val original = stored()
            val sessions = Sessions(original)
            val api = Api().apply { sessionError = error }
            val repo = repository(sessions, Logins(), api)
            repo.start(); advanceUntilIdle()
            assertEquals(0L, currentTime)
            assertEquals(1, api.sessionCalls)
            assertLocalConnectionFailure(repo)
            assertSame(original, sessions.record)
            assertTrue(sessions.writes.isEmpty())
        }
    }

    private fun assertLocalConnectionFailure(repo: SessionRepository) {
        assertEquals(AuthNotice.CONNECTION_FAILED, (repo.state.value as AuthState.Authenticated).notice)
        assertEquals(OnlineValidation.UNAVAILABLE, repo.onlineValidation.value)
        assertNull(repo.onlineGrant.value)
    }

    @Test fun explicitBackgroundPreventsSchedulingRecovery() = runTest {
        val api = Api().apply { sessionError = transientUnavailable() }
        val repo = repository(Sessions(stored()), Logins(), api)
        repo.setAppForeground(false)
        repo.start(); advanceUntilIdle()
        assertEquals(0L, currentTime)
        assertEquals(1, api.sessionCalls)
        assertLocalConnectionFailure(repo)
    }

    @Test fun backgroundingCancelsBackoffEvenIfActivityImmediatelyReturns() = runTest {
        val api = Api().apply { sessionError = transientUnavailable() }
        val repo = repository(Sessions(stored()), Logins(), api)
        repo.setAppForeground(true)
        repo.start(); runCurrent()
        advanceTimeBy(500)
        repo.setAppForeground(false); repo.setAppForeground(true); repo.refresh()
        advanceUntilIdle()
        assertEquals(500L, currentTime)
        assertEquals(1, api.sessionCalls)
        assertLocalConnectionFailure(repo)
    }

    @Test fun backgroundedRetryCannotPromoteLateSuccess() = runTest {
        val original = stored()
        val sessions = Sessions(original)
        val api = Api().apply { sessionError = transientUnavailable() }
        val repo = repository(sessions, Logins(), api)
        repo.start(); runCurrent()
        api.sessionError = null
        api.sessionGate = CompletableDeferred()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, api.sessionCalls)
        repo.setAppForeground(false)
        api.sessionGate!!.complete(Unit); advanceUntilIdle()
        assertLocalConnectionFailure(repo)
        assertSame(original, sessions.record)
        assertTrue(sessions.writes.isEmpty())
    }

    @Test fun completedRetryCannotPublishSuccessOrRevocationAfterForegroundEpochChanges() = runTest {
        for (revoked in listOf(false, true)) {
            val original = stored()
            val sessions = Sessions(original)
            val api = Api().apply { sessionError = transientUnavailable() }
            val repo = repository(sessions, Logins(), api)
            repo.setAppForeground(true)
            repo.start(); runCurrent()
            api.sessionError = if (revoked) AuthFailure(AuthFailure.Kind.UNAUTHORIZED) else null
            api.beforeSessionReturn = {
                // Already queued when the retry child completes, so this runs before await resumes.
                launch { repo.setAppForeground(false); repo.setAppForeground(true) }
            }
            advanceTimeBy(1_000); runCurrent()
            assertEquals(2, api.sessionCalls)
            assertLocalConnectionFailure(repo)
            assertSame(original, sessions.record)
            assertTrue(sessions.writes.isEmpty())
        }
    }

    @Test fun cancellingRepositoryScopeRetiresPendingBackoffWithoutPublishingFailure() = runTest {
        val original = stored()
        val sessions = Sessions(original)
        val api = Api().apply { sessionError = transientUnavailable() }
        val owner = SupervisorJob()
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = SessionRepository(sessions, Logins(), AuthApiFactory { api }, CoroutineScope(owner + dispatcher), dispatcher)
            repo.setAppForeground(true)
            repo.start(); runCurrent()
            owner.cancelAndJoin(); advanceUntilIdle()
            assertEquals(0L, currentTime)
            assertEquals(1, api.sessionCalls)
            assertTrue(repo.state.value is AuthState.Authenticated)
            assertNull(repo.onlineGrant.value)
            assertSame(original, sessions.record)
            assertTrue(sessions.writes.isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun modeEntryCancelsPendingBackoffAndAllowsLocalRestore() = runTest {
        val original = stored(cookie = "spofify_session=x; Path=/; HttpOnly")
        val sessions = Sessions(original)
        val api = Api().apply { sessionError = transientUnavailable() }
        val repo = repository(sessions, Logins(), api, policy = Policy())
        repo.start(); runCurrent()
        repo.setManualOffline(true); advanceUntilIdle()
        assertEquals(0L, currentTime)
        assertEquals(1, api.sessionCalls)
        assertTrue(repo.state.value is AuthState.Authenticated)
        assertTrue(repo.manualOffline.value)
        assertNull(repo.onlineGrant.value)
        assertSame(original, sessions.record)
        assertTrue(sessions.writes.isEmpty())
    }

    @Test fun signOutRetiresBackoffAndLogoutWritesAreNotReplayed() = runTest {
        val original = stored()
        val sessions = Sessions(original)
        val api = Api().apply { sessionError = transientUnavailable() }
        val logout = Api().apply { logoutError = transientUnavailable() }
        val repo = repository(sessions, Logins(), api, logout)
        repo.start(); runCurrent()
        repo.signOut(); advanceUntilIdle()
        assertEquals(0L, currentTime)
        assertEquals(1, api.sessionCalls)
        assertEquals(1, logout.logoutCalls)
        assertEquals(original.cookie, logout.logoutCookie)
        assertNull(sessions.record)
        assertEquals(AuthState.SignedOut(AuthNotice.REMOTE_LOGOUT_FAILED), repo.state.value)
    }

    @Test fun loginAndItsValidationAreNeverAutomaticallyReplayed() = runTest {
        for (loginFails in listOf(true, false)) {
            val api = Api().apply {
                if (loginFails) loginError = transientUnavailable() else sessionError = transientUnavailable()
            }
            val repo = repository(Sessions(), Logins(), api)
            repo.signIn(origin.value, "user", "fixture-password"); advanceUntilIdle()
            assertEquals(0L, currentTime)
            assertEquals(1, api.loginCalls)
            assertEquals(if (loginFails) 0 else 1, api.sessionCalls)
            assertEquals(AuthState.SignedOut(AuthNotice.CONNECTION_FAILED), repo.state.value)
        }
    }

    @Test fun corruptStorageFailsClosedWithoutDeletionAndAllowsManualLogin() = runTest {
        val old = stored()
        val sessions = Sessions(old).apply { readFails = true }
        val repo = repository(sessions, Logins(), policy = Policy())
        repo.start()
        advanceUntilIdle()
        assertTrue(repo.offlinePolicyLoaded.value)
        assertEquals(AuthState.Unavailable(AuthNotice.STORAGE_UNAVAILABLE), repo.state.value)
        repo.useManualLogin()
        assertEquals(AuthState.SignedOut(), repo.state.value)
        assertSame(old, sessions.record)
        assertTrue(sessions.writes.isEmpty())
    }

    @Test fun completeLoginSavesCanonicalAuthenticatedUsernameAndUnmodifiedSecret() = runTest {
        val sessions = Sessions()
        val logins = Logins()
        val api = Api()
        val repo = repository(sessions, logins, api)
        val secret = UUID.randomUUID().toString() + " \t"
        repo.signIn("http://FIXTURE.test:6030/", "fixture-user", secret)
        advanceUntilIdle()
        assertTrue(repo.state.value is AuthState.Authenticated)
        assertEquals(origin.value, logins.record!!.server)
        assertEquals(api.sessionReply.session.username, logins.record!!.username)
        assertEquals(secret, logins.record!!.password)
        assertEquals(api.loginReply.cookie, sessions.record!!.cookie)
    }

    @Test fun partialOrMismatchedLoginNeverOverwritesPriorRecords() = runTest {
        for (mismatch in listOf(false, true)) {
            val old = stored()
            val login = remembered()
            val sessions = Sessions(old)
            val logins = Logins(login)
            val api = Api().apply {
                if (mismatch) sessionReply = AuthReply(summary("other-id"), null, null)
                else sessionError = AuthFailure(AuthFailure.Kind.UNAVAILABLE)
            }
            val repo = repository(sessions, logins, api)
            repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
            advanceUntilIdle()
            assertTrue(repo.state.value is AuthState.SignedOut)
            assertSame(old, sessions.record)
            assertSame(login, logins.record)
        }
    }

    @Test fun saveFailureRetainsAuthenticatedSessionAndPriorDurableCredentials() = runTest {
        val old = remembered()
        val sessions = Sessions().apply { writeFails = true }
        val logins = Logins(old).apply { fails = true }
        val repo = repository(sessions, logins, Api())
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        advanceUntilIdle()
        val state = repo.state.value as AuthState.Authenticated
        assertEquals(AuthNotice.SAVE_FAILED, state.notice)
        assertTrue(repo.isCurrent(state.identity))
        assertSame(old, logins.record)
    }

    @Test fun rememberedSaveFailureRemainsVisibleAcrossForegroundRevalidation() = runTest {
        val logins = Logins().apply { fails = true }
        val repo = repository(Sessions(), logins, Api(), Api(), Api())
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        advanceUntilIdle()
        assertEquals(AuthNotice.SAVE_FAILED, (repo.state.value as AuthState.Authenticated).notice)
        repo.refresh()
        advanceUntilIdle()
        assertEquals(AuthNotice.SAVE_FAILED, (repo.state.value as AuthState.Authenticated).notice)
        logins.fails = false
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        advanceUntilIdle()
        assertNull((repo.state.value as AuthState.Authenticated).notice)
    }

    @Test fun staleRestoreCookieCannotReplaceSuccessor() = runTest {
        val sessions = Sessions(stored())
        val old = Api().apply {
            sessionGate = CompletableDeferred()
            sessionReply = AuthReply(summary(), "stale-renewal", "rolling-v1")
        }
        val successor = Api()
        val repo = repository(sessions, Logins(), old, successor)
        repo.start()
        runCurrent()
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        runCurrent()
        val accepted = sessions.record
        old.sessionGate!!.complete(Unit)
        advanceUntilIdle()
        assertSame(accepted, sessions.record)
        assertEquals(successor.loginReply.cookie, sessions.record!!.cookie)
        assertTrue(repo.state.value is AuthState.Authenticated)
    }

    @Test fun staleSession401CannotRevokeSuccessor() = runTest {
        val sessions = Sessions(stored())
        val old = Api().apply {
            sessionGate = CompletableDeferred()
            sessionError = AuthFailure(AuthFailure.Kind.UNAUTHORIZED)
        }
        val repo = repository(sessions, Logins(), old, Api())
        repo.start()
        runCurrent()
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        runCurrent()
        val accepted = sessions.record
        val identity = (repo.state.value as AuthState.Authenticated).identity
        old.sessionGate!!.complete(Unit)
        advanceUntilIdle()
        assertSame(accepted, sessions.record)
        assertTrue(repo.isCurrent(identity))
    }

    @Test fun staleLogin401CannotSignOutSuccessorOrOverwriteRememberedLogin() = runTest {
        val old = Api().apply {
            loginGate = CompletableDeferred()
            loginError = AuthFailure(AuthFailure.Kind.UNAUTHORIZED)
        }
        val logins = Logins()
        val sessions = Sessions()
        val successor = Api()
        val repo = repository(sessions, logins, old, successor)
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        runCurrent()
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        runCurrent()
        val acceptedLogin = logins.record
        val acceptedSession = sessions.record
        old.loginGate!!.complete(Unit)
        advanceUntilIdle()
        assertSame(acceptedLogin, logins.record)
        assertSame(acceptedSession, sessions.record)
        assertTrue(repo.state.value is AuthState.Authenticated)
    }

    @Test fun signoutFencesImmediatelyAndLateRemoteFailureCannotChangeSuccessor() = runTest {
        val sessions = Sessions()
        val logins = Logins()
        val loginApi = Api()
        val logoutApi = Api().apply {
            logoutGate = CompletableDeferred()
            logoutError = IOException()
        }
        val successor = Api()
        val repo = repository(sessions, logins, loginApi, logoutApi, successor)
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        advanceUntilIdle()
        val identity = (repo.state.value as AuthState.Authenticated).identity
        val cookie = sessions.record!!.cookie
        val remembered = logins.record
        repo.signOut()
        assertFalse(repo.isCurrent(identity))
        assertTrue(repo.state.value is AuthState.SignedOut)
        runCurrent()
        assertNull(sessions.record)
        assertSame(remembered, logins.record)
        assertEquals(cookie, logoutApi.logoutCookie)
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        runCurrent()
        val accepted = sessions.record
        logoutApi.logoutGate!!.complete(Unit)
        advanceUntilIdle()
        assertSame(accepted, sessions.record)
        assertTrue(repo.state.value is AuthState.Authenticated)
    }

    @Test fun blockedPersistenceNeverBlocksForegroundFenceOrSuccessorAndWritesStayOrdered() = runBlocking {
        val ioExecutor = Executors.newFixedThreadPool(2)
        val dispatcher = ioExecutor.asCoroutineDispatcher()
        val main = Executors.newSingleThreadExecutor()
        val appJob = SupervisorJob()
        val appScope = CoroutineScope(appJob + dispatcher)
        val enteredStore = CountDownLatch(1)
        val releaseStore = CountDownLatch(1)
        val refreshCreated = CountDownLatch(1)
        val refreshGate = CompletableDeferred<Unit>()
        val firstWrite = AtomicBoolean(true)
        val stored = AtomicReference<StoredSession?>()
        val profile = AtomicReference<RememberedLogin?>()
        val writes = CopyOnWriteArrayList<StoredSession?>()
        val sessions = object : SessionStore {
            override fun read() = stored.get()
            override fun write(value: StoredSession?) {
                if (firstWrite.compareAndSet(true, false)) {
                    enteredStore.countDown()
                    check(releaseStore.await(10, TimeUnit.SECONDS)) { "Store was not released" }
                }
                stored.set(value)
                writes.add(value)
            }
        }
        val logins = object : LoginStore {
            override fun read() = profile.get()
            override fun write(value: RememberedLogin) { profile.set(value) }
        }
        val first = Api()
        val refresh = Api().apply { sessionGate = refreshGate }
        val successor = Api()
        val clients = ConcurrentLinkedQueue(listOf(first, refresh, successor, Api()))
        val created = AtomicInteger()
        val repo = SessionRepository(sessions, logins, AuthApiFactory {
            val client = clients.remove()
            if (created.incrementAndGet() == 2) refreshCreated.countDown()
            client
        }, appScope, dispatcher)
        try {
            repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
            assertTrue(enteredStore.await(2, TimeUnit.SECONDS))
            val beforeForeground = (repo.state.value as? AuthState.Authenticated)?.identity
            // Real ON_START follows this synchronous frontend path. A blocked store
            // must not delay even the state check, nor make retirement wait for I/O.
            main.submit { repo.refresh() }.get(500, TimeUnit.MILLISECONDS)
            val previous = requireNotNull(beforeForeground)
            assertTrue(refreshCreated.await(2, TimeUnit.SECONDS))
            val nextSecret = UUID.randomUUID().toString()
            main.submit {
                repo.signOut()
                assertFalse(repo.isCurrent(previous))
                assertTrue(repo.state.value is AuthState.SignedOut)
                repo.signIn(origin.value, "fixture-user", nextSecret)
            }.get(500, TimeUnit.MILLISECONDS)
            val next = withTimeout(2_000) {
                repo.state.first { it is AuthState.Authenticated && it.identity != previous }
            } as AuthState.Authenticated
            assertTrue(repo.isCurrent(next.identity))
            assertTrue("Blocked predecessor unexpectedly finished", writes.isEmpty())
            releaseStore.countDown()
            // Read waits for the accepted successor intent, not merely its network result.
            assertEquals(nextSecret, withTimeout(2_000) { repo.rememberedLogin() }!!.password)
            refreshGate.complete(Unit)
            withTimeout(2_000) { appJob.children.toList().joinAll() }
            assertEquals(3, writes.size)
            assertEquals(first.loginReply.cookie, writes[0]!!.cookie)
            assertNull(writes[1])
            assertEquals(successor.loginReply.cookie, writes[2]!!.cookie)
            assertEquals(successor.loginReply.cookie, stored.get()!!.cookie)
            assertTrue(repo.isCurrent(next.identity))
        } finally {
            releaseStore.countDown()
            refreshGate.complete(Unit)
            main.shutdownNow()
            appJob.cancel()
            try { withTimeout(2_000) { appJob.join() } } finally {
                dispatcher.close()
                val mainStopped = main.awaitTermination(2, TimeUnit.SECONDS)
                val ioStopped = ioExecutor.awaitTermination(2, TimeUnit.SECONDS)
                assertTrue(mainStopped)
                assertTrue(ioStopped)
            }
        }
    }

    @Test fun failedClearIsNotHiddenByAYoungerFailedLogin() = runTest {
        val sessions = Sessions()
        val failedLogin = Api().apply { loginError = AuthFailure(AuthFailure.Kind.UNAUTHORIZED) }
        val repo = repository(sessions, Logins(), Api(), failedLogin, Api())
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        advanceUntilIdle()
        val previous = sessions.record
        sessions.writeFails = true
        repo.signOut()
        repo.signIn(origin.value, "fixture-user", UUID.randomUUID().toString())
        advanceUntilIdle()
        assertSame(previous, sessions.record)
        assertEquals(AuthState.SignedOut(AuthNotice.LOCAL_LOGOUT_FAILED), repo.state.value)
    }

    @Test fun localSignoutFailureIsTruthfulAndDoesNotDeleteOtherRecords() = runTest {
        val sessions = Sessions(stored())
        val login = remembered()
        val logins = Logins(login)
        val repo = repository(sessions, logins, Api(), Api())
        repo.start()
        advanceUntilIdle()
        val old = sessions.record
        sessions.writeFails = true
        repo.signOut()
        advanceUntilIdle()
        assertEquals(AuthState.SignedOut(AuthNotice.LOCAL_LOGOUT_FAILED), repo.state.value)
        assertSame(old, sessions.record)
        assertSame(login, logins.record)
    }
}
