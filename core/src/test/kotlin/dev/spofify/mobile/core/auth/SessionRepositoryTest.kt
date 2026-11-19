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
    private fun stored(id: String = "fixture-id", cookie: String = UUID.randomUUID().toString()) =
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
        var loginReply = AuthReply(summary(), UUID.randomUUID().toString(), "rolling-v1")
        var sessionReply = AuthReply(summary(), null, null)
        var sessionError: Exception? = null
        var loginError: Exception? = null
        var logoutError: Exception? = null
        var loginGate: CompletableDeferred<Unit>? = null
        var sessionGate: CompletableDeferred<Unit>? = null
        var logoutGate: CompletableDeferred<Unit>? = null
        var sessionCalls = 0
        var logoutCookie: String? = null
        override suspend fun login(username: String, password: String): AuthReply {
            loginGate?.let { withContext(NonCancellable) { it.await() } }
            loginError?.let { throw it }
            return loginReply
        }
        override suspend fun session(cookie: String): AuthReply {
            sessionCalls++
            sessionGate?.let { withContext(NonCancellable) { it.await() } }
            sessionError?.let { throw it }
            return sessionReply
        }
        override suspend fun logout(cookie: String, csrfToken: String) {
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
    private fun TestScope.repository(sessions: Sessions, logins: Logins, vararg api: Api, policy: Policy? = null): SessionRepository {
        val queue = ArrayDeque(api.toList())
        return SessionRepository(sessions, logins, AuthApiFactory { queue.removeFirst() },
            this, StandardTestDispatcher(testScheduler), policy)
    }

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
        policy.unreadable = false; repo.retry(); advanceUntilIdle()
        val id = (repo.state.value as AuthState.Authenticated).identity
        policy.unwritable = true
        assertTrue(runCatching { repo.setManualOffline(false) }.exceptionOrNull() is IOException)
        assertTrue(repo.manualOffline.value); assertTrue(repo.isCurrent(id)); assertNull(repo.onlineGrant.value)
    }

    @Test fun returnValidationRetainsLocalIdentityWithoutGrantButCurrent401PurgesIt() = runTest {
        val sessions = Sessions(stored(cookie = "spofify_session=x; Path=/; HttpOnly"))
        val unavailable = Api().apply { sessionError = AuthFailure(AuthFailure.Kind.UNAVAILABLE) }
        val revoked = Api().apply { sessionGate = CompletableDeferred(); sessionError = AuthFailure(AuthFailure.Kind.UNAUTHORIZED) }
        val policy = Policy(true)
        val repo = repository(sessions, Logins(), unavailable, revoked, policy = policy)
        repo.start(); advanceUntilIdle(); val id = (repo.state.value as AuthState.Authenticated).identity
        repo.setManualOffline(false); advanceUntilIdle()
        assertTrue(repo.isCurrent(id)); assertNull(repo.onlineGrant.value)
        assertEquals(AuthNotice.CONNECTION_FAILED, (repo.state.value as AuthState.Authenticated).notice)
        repo.retry(); runCurrent(); assertTrue(repo.isCurrent(id)); assertNull(repo.onlineGrant.value)
        revoked.sessionGate!!.complete(Unit); advanceUntilIdle()
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

    @Test fun restore200ValidatesBeforeGrantingAndKeepsLogicalIdentityOnRenewal() = runTest {
        val old = stored()
        val sessions = Sessions(old)
        val first = Api()
        val second = Api().apply { sessionReply = AuthReply(summary(), UUID.randomUUID().toString(), "rolling-v1") }
        val repo = repository(sessions, Logins(), first, second)
        repo.start()
        assertEquals(AuthState.Starting, repo.state.value)
        advanceUntilIdle()
        val identity = (repo.state.value as AuthState.Authenticated).identity
        assertEquals(old.cookie, sessions.record!!.cookie)
        repo.refresh()
        assertFalse(repo.isCurrent(identity))
        advanceUntilIdle()
        assertEquals(identity, (repo.state.value as AuthState.Authenticated).identity)
        assertEquals(second.sessionReply.cookie, sessions.record!!.cookie)
    }

    @Test fun restoredIdentityUsesCanonicalOriginNotLegacySpelling() = runTest {
        val legacy = StoredSession("HTTP://FIXTURE.test:6030/", UUID.randomUUID().toString(), summary())
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

    @Test fun transientRestoreRetainsBytesAndRetriesWithoutPasswordReplay() = runTest {
        val old = stored()
        val sessions = Sessions(old)
        val failed = Api().apply { sessionError = AuthFailure(AuthFailure.Kind.UNAVAILABLE) }
        val recovered = Api()
        val repo = repository(sessions, Logins(), failed, recovered)
        repo.start()
        advanceUntilIdle()
        assertTrue(repo.state.value is AuthState.Unavailable)
        assertSame(old, sessions.record)
        assertTrue(sessions.writes.isEmpty())
        repo.retry()
        advanceUntilIdle()
        assertTrue(repo.state.value is AuthState.Authenticated)
        assertEquals(1, recovered.sessionCalls)
    }

    @Test fun corruptStorageFailsClosedWithoutDeletionAndAllowsManualLogin() = runTest {
        val old = stored()
        val sessions = Sessions(old).apply { readFails = true }
        val repo = repository(sessions, Logins())
        repo.start()
        advanceUntilIdle()
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
