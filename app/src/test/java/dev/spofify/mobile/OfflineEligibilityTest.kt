package dev.spofify.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.ApiTransport
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import dev.spofify.mobile.playback.PlaybackRequests
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineEligibilityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class Sessions : SessionStore {
        var value: StoredSession? = null
        var beforeWrite: ((StoredSession?) -> Unit)? = null
        override fun read() = value
        override fun write(value: StoredSession?) { beforeWrite?.invoke(value); this.value = value }
    }
    private class Eligibility : OfflineEligibilityStore {
        var value: OfflineEligibility? = null
        var beforeWrite: ((OfflineEligibility?) -> Unit)? = null
        override fun read() = value?.let { OfflineEligibility(it.origin, it.accountId, it.username, it.localAccountEpoch, it.accountEpochPresent) }
        override fun write(value: OfflineEligibility?) { beforeWrite?.invoke(value); this.value = value }
    }
    private class StopBarrierContext(base: Context) : android.content.ContextWrapper(base) {
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var failStop = false
        override fun stopService(service: android.content.Intent): Boolean {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                if (failStop) throw java.io.IOException("Synthetic stop failure")
            }
            return super.stopService(service)
        }
    }
    private fun response(accountId: String = "account") = MockResponse().setHeader("Content-Type", "application/json")
        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
        .setBody("""{"accountId":"$accountId","username":"listener","role":"user","csrfToken":"synthetic-csrf"}""")

    @Before fun resetSettings() {
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        OfflineAccountJournal.forgetProcessForTest(context)
    }

    @Test fun genuinePriorAuthenticationRestoresOnlyLocalEligibilityWithoutAColdLaunchRequest() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val eligibility = Eligibility()
        val graph = AppGraph(context, sessions, offlineEligibilityStore = eligibility)
        var restored: AppGraph? = null
        var predecessorClosed = false
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            assertNull(graph.localEligibility)
            web.enqueue(response())
            client.login("listener", "synthetic-password")
            val admitted = graph.localEligibility!!
            assertEquals("account", admitted.accountId)
            assertEquals(0L, admitted.localAccountEpoch)
            graph.setOfflineMode(true)
            assertNull(graph.playbackCoordinator)
            assertTrue(graph.localPlaybackIsCurrent(admitted))
            // Model actual process death: stop owner submissions and release its exclusive
            // root without logout/purge before constructing a cold owner of the SAME bytes.
            graph.savedDownloads.prepareConfigure(client.server.origin)
            predecessorClosed = true // close is terminal, not an idempotent logout operation.
            graph.savedDownloads.close()
            val cold = AppGraph(context, sessions, offlineEligibilityStore = eligibility)
            restored = cold
            assertTrue(cold.offlineMode.value)
            assertNull(cold.restore())
            assertNull(cold.client!!.currentSession())
            assertNull(cold.client!!.validatedSessionIdentity())
            assertNull(cold.client!!.observedAuthenticatedScope())
            assertTrue(cold.localEligibilityIsCurrent(requireNotNull(cold.localEligibility)))
            assertTrue(cold.offlineLibrary is SavedOfflineLibraryAdapter)
            assertEquals(dev.spofify.mobile.core.saved.SavedScope(admitted.origin, admitted.accountId),
                cold.savedDownloads.currentScope())
            assertTrue(cold.offlineCollections(requireNotNull(cold.localEligibility)).isEmpty())
            assertNull(cold.playbackCoordinator)
            assertEquals("listener", cold.localEligibility!!.username)
            assertNotSame(admitted, cold.localEligibility)
            assertEquals(admitted.localAccountEpoch, cold.localEligibility!!.localAccountEpoch)
            assertEquals(1, web.requestCount)
            assertFalse(dev.spofify.mobile.playback.PlaybackService.exportLocalSnapshot().playing)
        } finally {
            restored?.let { it.clear(); it.savedDownloads.close() }
            graph.clear()
            if (!predecessorClosed) graph.savedDownloads.close()
            web.shutdown()
        }
    }

    @Test fun persistedSuccessorBeforeParkedValidatedDeliveryDeniesAOnImmediateColdOfflineLaunch() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val retained = Eligibility()
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
        var cold: AppGraph? = null
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            val parked = mutableListOf<dev.spofify.mobile.core.AuthenticationChange>()
            val callback = requireNotNull(client.onAuthenticationChanged)
            client.onAuthenticationChanged = {
                if (it.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED) parked += it else callback(it)
            }
            sessions.beforeWrite = { value ->
                if (value?.session?.accountId == "other-account") {
                    assertTrue("Denial must be durable BEFORE the B session delegate write",
                        preferences.getBoolean("offline_revoked", false))
                    assertNull(graph.localEligibility)
                    assertEquals(old.localAccountEpoch + 1L,
                        org.json.JSONObject(preferences.getString("offline_account_history", null)!!).getLong("epoch"))
                }
            }
            web.enqueue(response("other-account")); client.login("listener", "synthetic-password")
            assertEquals(1, parked.size)
            assertEquals("other-account", sessions.value?.session?.accountId)
            assertSame(old, retained.value) // No Graph VALIDATED retirement/save has run.
            assertSame(old, graph.retirementScope)
            // Simulate process death in the exact core-install -> Graph-callback gap. Do
            // NOT use setOfflineMode on the old graph: that would hide the durable gap.
            // An older save can clear the legacy flag, but cannot undo the account epoch.
            preferences.edit().putBoolean("offline_revoked", false).putBoolean("offline_mode", true).commit()
            OfflineAccountJournal.forgetProcessForTest(context)
            cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
            assertNull(cold.localEligibility)
            assertEquals(old.accountId, cold.retirementScope?.accountId)
            assertFalse(cold.localPlaybackIsCurrent(old))
            assertNull(cold.client!!.observedAuthenticatedScope())
            assertNull(cold.client!!.currentSession())
            assertNull(cold.client!!.validatedSessionIdentity())
            assertNull(cold.restore())
            assertEquals(2, web.requestCount)
        } finally { sessions.beforeWrite = null; cold?.clear(); graph.clear(); web.shutdown() }
    }

    @Test fun genuineSuccessorValidationPersistsItsReservedEpochAndRestoresOnlyLocalAuthority() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val retained = Eligibility()
        val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
        var cold: AppGraph? = null
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            web.enqueue(response("other-account")); client.login("listener", "synthetic-password")
            val fresh = requireNotNull(graph.localEligibility)
            assertEquals(old.localAccountEpoch + 1L, fresh.localAccountEpoch)
            assertEquals("other-account", fresh.accountId)
            assertFalse(graph.localEligibilityIsCurrent(old))
            assertTrue(graph.localEligibilityIsCurrent(fresh))
            graph.setOfflineMode(true)
            cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
            val local = requireNotNull(cold.localEligibility)
            assertNotSame(fresh, local)
            assertEquals(fresh.localAccountEpoch, local.localAccountEpoch)
            assertTrue(cold.localPlaybackIsCurrent(local))
            assertNull(cold.client!!.currentSession())
            assertNull(cold.client!!.validatedSessionIdentity())
            assertNull(cold.client!!.observedAuthenticatedScope())
            assertNull(cold.restore())
            assertEquals(2, web.requestCount)
        } finally { cold?.clear(); graph.clear(); web.shutdown() }
    }

    @Test fun staleAStorageGrantClearingTheFlagWhileBHasCoreLockCannotResurrectAAfterDeath() = runBlocking {
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val staleFlagCleared = CountDownLatch(1)
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
                if (name != "connection") return super.getSharedPreferences(name, mode)
                return object : android.content.SharedPreferences by preferences {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = preferences.edit()
                        var clearsRevocation = false
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
                                if (key == "offline_revoked" && !value) clearsRevocation = true
                                editor.putBoolean(key, value); return this
                            }
                            override fun commit(): Boolean {
                                val committed = editor.commit()
                                if (committed && clearsRevocation) staleFlagCleared.countDown()
                                return committed
                            }
                        }
                    }
                }
            }
        }
        val sessions = Sessions()
        val retained = Eligibility()
        val aSaving = CountDownLatch(1)
        val releaseASave = CountDownLatch(1)
        val bWriting = CountDownLatch(1)
        val releaseBWrite = CountDownLatch(1)
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(wrapped, sessions, offlineEligibilityStore = retained)
        var cold: AppGraph? = null
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            val callback = requireNotNull(client.onAuthenticationChanged)
            client.onAuthenticationChanged = {
                // A genuinely enters Graph's storage serialization. B's callback and
                // intervening RESET are parked, so neither can mask the core-write gap.
                if (it.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED && it.identity?.accountId == "account")
                    callback(it)
            }
            retained.beforeWrite = { value ->
                if (value?.accountId == "account") {
                    aSaving.countDown(); check(releaseASave.await(5, TimeUnit.SECONDS))
                }
            }
            sessions.beforeWrite = { value ->
                if (value?.session?.accountId == "other-account") {
                    bWriting.countDown(); check(releaseBWrite.await(5, TimeUnit.SECONDS))
                }
            }
            web.enqueue(response())
            val a = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(aSaving.await(3, TimeUnit.SECONDS))
            web.enqueue(response("other-account"))
            val b = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue("B's hook must not wait for A's localStorageLock", bWriting.await(3, TimeUnit.SECONDS))
            assertEquals(1L, org.json.JSONObject(preferences.getString("offline_account_history", null)!!).getLong("epoch"))
            assertTrue(preferences.getBoolean("offline_revoked", false))
            releaseASave.countDown()
            assertTrue(staleFlagCleared.await(3, TimeUnit.SECONDS))
            val stale = requireNotNull(retained.value)
            assertEquals(0L, stale.localAccountEpoch)
            assertFalse("The real older grant has undone the bool-only fence", preferences.getBoolean("offline_revoked", true))
            assertEquals("A is still waiting for the core lock held by B", 1L, releaseBWrite.count)
            // Construct the new graph BEFORE either callback can reconcile/purge A.
            preferences.edit().putBoolean("offline_mode", true).commit()
            OfflineAccountJournal.forgetProcessForTest(context)
            cold = AppGraph(wrapped, sessions, offlineEligibilityStore = retained)
            assertNull(cold.localEligibility)
            assertEquals(0L, requireNotNull(cold.retirementScope).localAccountEpoch)
            assertFalse(cold.localPlaybackIsCurrent(stale))
            assertNull(cold.client!!.currentSession())
            assertNull(cold.client!!.observedAuthenticatedScope())
            assertNull(cold.restore())
            assertEquals(2, web.requestCount)
            releaseBWrite.countDown()
            withTimeout(3_000) { a.await(); b.await() }
            assertNull(graph.localEligibility)
        } finally {
            releaseASave.countDown(); releaseBWrite.countDown()
            sessions.beforeWrite = null; retained.beforeWrite = null
            cold?.clear(); graph.clear(); web.shutdown()
        }
    }

    @Test fun persistedAccountAbaBeforeAnyValidatedDeliveryCannotClearTheDurableDenial() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val retained = Eligibility()
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
        var cold: AppGraph? = null
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            val callback = requireNotNull(client.onAuthenticationChanged)
            val parked = mutableListOf<dev.spofify.mobile.core.AuthenticationChange>()
            client.onAuthenticationChanged = {
                if (it.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED) parked += it else callback(it)
            }
            web.enqueue(response("other-account")); client.login("listener", "synthetic-password")
            web.enqueue(response()); client.login("listener", "synthetic-password")
            assertEquals(2, parked.size)
            assertEquals(old.accountId, sessions.value?.session?.accountId)
            assertTrue(preferences.getBoolean("offline_revoked", false))
            assertNull(graph.localEligibility)
            assertEquals(old.localAccountEpoch + 2L,
                org.json.JSONObject(preferences.getString("offline_account_history", null)!!).getLong("epoch"))
            preferences.edit().putBoolean("offline_revoked", false).putBoolean("offline_mode", true).commit()
            OfflineAccountJournal.forgetProcessForTest(context)
            cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
            assertNull(cold.localEligibility)
            assertEquals(old.accountId, cold.retirementScope?.accountId)
            assertNull(cold.client!!.observedAuthenticatedScope())
            assertNull(cold.client!!.currentSession())
            assertNull(cold.restore())
            assertFalse(cold.localPlaybackIsCurrent(old))
            assertEquals(3, web.requestCount)
        } finally { cold?.clear(); graph.clear(); web.shutdown() }
    }

    @Test fun legacyConstructorSeedsEpochZeroButExplicitEpochZeroCannotRecoverALostJournal() {
        val origin = "http://127.0.0.1:43117"
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("offline_mode", true).commit()
        val retained = Eligibility().apply { value = OfflineEligibility(origin, "account", "listener") }
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = retained)
        try {
            val legacy = requireNotNull(graph.localEligibility)
            assertEquals(0L, legacy.localAccountEpoch)
            assertTrue("Preserve the genuine record in protected format during migration", legacy.accountEpochPresent)
            assertTrue(graph.localPlaybackIsCurrent(legacy))
            assertTrue(preferences.getBoolean("offline_account_history_initialized", false))
            assertEquals(0L, org.json.JSONObject(preferences.getString("offline_account_history", null)!!).getLong("epoch"))
            assertTrue(OfflineEligibility(origin, "account", "listener", 0L).accountEpochPresent)
        } finally { graph.clear() }
    }

    @Test fun missingOrCorruptJournalAfterProtectedZeroGrantFailsClosedRatherThanMigratingAgain() = runBlocking {
        for (damage in 0..2) {
            resetSettings()
            val web = MockWebServer().apply { start() }
            val sessions = Sessions()
            val retained = Eligibility()
            val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
            val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
            var cold: AppGraph? = null
            try {
                val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
                web.enqueue(response()); client.login("listener", "synthetic-password")
                val old = requireNotNull(graph.localEligibility)
                assertEquals(0L, old.localAccountEpoch)
                assertTrue(old.accountEpochPresent)
                graph.setOfflineMode(true)
                val editor = preferences.edit().putBoolean("offline_revoked", false)
                if (damage == 2) editor.putString("offline_account_history", "broken journal")
                else editor.remove("offline_account_history")
                if (damage == 1) editor.remove("offline_account_history_initialized")
                editor.commit()
                OfflineAccountJournal.forgetProcessForTest(context)
                cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
                assertNull(cold.localEligibility)
                assertTrue(cold.offlineStorageFailed.value)
                assertEquals(old.localAccountEpoch, requireNotNull(cold.retirementScope).localAccountEpoch)
                assertNotNull("Keep genuine evidence when journal cleanup cannot be acknowledged", retained.value)
                assertFalse(cold.localPlaybackIsCurrent(old))
                assertNull(cold.client!!.currentSession())
                assertNull(cold.restore())
                assertEquals(1, web.requestCount)
            } finally { runCatching { cold?.clear() }; graph.clear(); web.shutdown() }
        }
    }

    @Test fun unavailableEligibilityCannotInitializeMissingHistoryOrOverwriteProtectedEvidence() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val retained = Eligibility()
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
        var unavailable: AppGraph? = null
        var cold: AppGraph? = null
        try {
            val origin = ServerAddress.parse("http://127.0.0.1:${web.port}")
            val initial = graph.configure(origin)
            web.enqueue(response()); initial.login("listener", "synthetic-password")
            val old = requireNotNull(retained.value)
            assertTrue(old.accountEpochPresent)
            assertEquals(0L, old.localAccountEpoch)
            graph.setOfflineMode(true)
            preferences.edit().remove("offline_account_history")
                .remove("offline_account_history_initialized").putBoolean("offline_revoked", false)
                .putBoolean("offline_mode", false).commit()
            OfflineAccountJournal.forgetProcessForTest(context)
            var failFirstRead = true
            val flaky = object : OfflineEligibilityStore {
                override fun read(): OfflineEligibility? {
                    if (failFirstRead) { failFirstRead = false; throw java.io.IOException("Synthetic unavailable eligibility") }
                    return retained.read()
                }
                override fun write(value: OfflineEligibility?) = retained.write(value)
            }
            unavailable = AppGraph(context, sessions, offlineEligibilityStore = flaky)
            assertNull(unavailable.localEligibility)
            assertTrue(unavailable.offlineStorageFailed.value)
            assertNotNull("Unknown protected evidence must not be erased", retained.value)
            val client = requireNotNull(unavailable.client)
            val notify = client.onAuthenticationChanged
            var validationDelivered = false
            client.onAuthenticationChanged = { change ->
                // Hold the genuine notification rather than grant fresh Graph eligibility.
                if (change.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED) validationDelivered = true
                else notify?.invoke(change)
            }
            web.enqueue(response())
            assertTrue("Unconfirmed absence must not initialize history before persisting A", runCatching {
                client.login("listener", "synthetic-password")
            }.isFailure)
            assertFalse(validationDelivered)
            assertNull(sessions.value)
            assertNull(preferences.getString("offline_account_history", null))
            assertNotNull(retained.value)
            preferences.edit().putBoolean("offline_mode", true).commit()
            OfflineAccountJournal.forgetProcessForTest(context)
            cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
            assertNull(cold.localEligibility)
            assertTrue(cold.offlineStorageFailed.value)
            assertFalse(cold.localPlaybackIsCurrent(old))
            assertNotNull("Protected zero still proves that migration occurred", retained.value)
            assertEquals(2, web.requestCount)
        } finally { runCatching { cold?.clear() }; runCatching { unavailable?.clear() }; graph.clear(); web.shutdown() }
    }

    @Test fun historicalUnfencedExpiredBMetadataOnlyDeniesAAndNeverGrantsB() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val retained = Eligibility()
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
        var cold: AppGraph? = null
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            client.onAuthenticationChanged = null
            web.enqueue(response("other-account")); client.login("listener", "synthetic-password")
            // Reconstruct a pre-migration disk state: genuine v1 A, B metadata, no journal.
            retained.value = OfflineEligibility(old.origin, old.accountId, old.username)
            sessions.value = requireNotNull(sessions.value).copy(cookie =
                "spofify_session=synthetic; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; HttpOnly")
            preferences.edit().remove("offline_account_history").remove("offline_account_history_initialized")
                .putBoolean("offline_revoked", false).putBoolean("offline_mode", true).commit()
            OfflineAccountJournal.forgetProcessForTest(context)
            cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
            assertNull(cold.localEligibility)
            assertEquals(old.accountId, cold.retirementScope?.accountId)
            assertTrue(preferences.getBoolean("offline_revoked", false))
            assertNull(cold.client!!.currentSession())
            assertNull(cold.client!!.validatedSessionIdentity())
            assertNull(cold.client!!.observedAuthenticatedScope())
            assertNull(cold.restore())
            assertEquals(2, web.requestCount)
        } finally { cold?.clear(); graph.clear(); web.shutdown() }
    }

    @Test fun failedEpochCommitWithChangedPreferenceRamBlocksBAndRetriesFromLastCommittedA() = runBlocking {
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        var rejectDenial = false
        val attemptedEpochs = CopyOnWriteArrayList<Long>()
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
                if (name != "connection") return super.getSharedPreferences(name, mode)
                return object : android.content.SharedPreferences by preferences {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = preferences.edit()
                        var denial = false
                        var historyWrite = false
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                                if (key == "offline_account_history") historyWrite = true
                                editor.putString(key, value); return this
                            }
                            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
                                if (key == "offline_revoked" && value) denial = true
                                editor.putBoolean(key, value); return this
                            }
                            override fun commit(): Boolean {
                                // Model Android's dangerous failure shape: the preference
                                // map changed even though commit reports failure.
                                val applied = editor.commit()
                                if (historyWrite) attemptedEpochs += org.json.JSONObject(
                                    preferences.getString("offline_account_history", null)!!).getLong("epoch")
                                return if (rejectDenial && denial) false else applied
                            }
                        }
                    }
                }
            }
        }
        val sessions = Sessions()
        val retained = Eligibility()
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(wrapped, sessions, offlineEligibilityStore = retained)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            var bWrites = 0
            sessions.beforeWrite = { if (it?.session?.accountId == "other-account") bWrites++ }
            rejectDenial = true
            web.enqueue(response("other-account"))
            assertTrue(runCatching { client.login("listener", "synthetic-password") }.isFailure)
            assertEquals(0, bWrites)
            assertNotEquals("other-account", sessions.value?.session?.accountId)
            assertNull(graph.localEligibility)
            assertSame(old, graph.retirementScope)
            assertFalse(graph.localEligibilityIsCurrent(old))
            assertTrue(graph.offlineStorageFailed.value)
            assertNull(client.validatedSessionIdentity())
            val failed = OfflineAccountJournal.obtain(wrapped, preferences).snapshot
            assertTrue(failed.failed)
            assertEquals(old.localAccountEpoch, requireNotNull(failed.history).epoch)
            assertEquals(old.localAccountEpoch + 1L,
                org.json.JSONObject(preferences.getString("offline_account_history", null)!!).getLong("epoch"))
            rejectDenial = false
            web.enqueue(response("other-account")); client.login("listener", "synthetic-password")
            assertEquals(1, bWrites)
            assertEquals(old.localAccountEpoch + 1L, requireNotNull(graph.localEligibility).localAccountEpoch)
            assertEquals(listOf(old.localAccountEpoch + 1L, old.localAccountEpoch, old.localAccountEpoch + 1L),
                attemptedEpochs.takeLast(3))
            assertEquals(3, web.requestCount)
        } finally { rejectDenial = false; sessions.beforeWrite = null; graph.clear(); web.shutdown() }
    }

    @Test fun failedOldOwnerRetirementRetainsEncryptedScopeEvidenceAndTheDenialMarker() = runBlocking {
        val web = MockWebServer().apply { start() }
        val retained = Eligibility()
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = retained)
        var failRetirement = true
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            graph.offlineLibrary = object : OfflineLibraryAccess {
                override val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
                override suspend fun collections(origin: String, accountId: String): List<OfflineSavedCollection> = emptyList()
                override fun retire() { if (failRetirement) throw java.io.IOException("Synthetic owner retirement failure") }
            }
            // No Graph VALIDATED delivery: only the pre-persistence wrapper can deny A.
            val callback = requireNotNull(client.onAuthenticationChanged)
            client.onAuthenticationChanged = { if (it.kind != dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED) callback(it) }
            web.enqueue(response("other-account")); client.login("listener", "synthetic-password")
            graph.setOfflineMode(true)
            assertNull(graph.localEligibility)
            assertSame(old, graph.retirementScope)
            assertSame("Failed cleanup must not discard encrypted old-scope evidence", old, retained.value)
            assertTrue(context.getSharedPreferences("connection", Context.MODE_PRIVATE).getBoolean("offline_revoked", false))
            assertTrue(graph.offlineStorageFailed.value)
            assertEquals(2, web.requestCount)
        } finally { failRetirement = false; graph.clear(); web.shutdown() }
    }

    @Test fun staleSessionLeaseCannotTombstoneTheSuccessorsGenuineEligibility() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val graph = AppGraph(context, sessions, offlineEligibilityStore = Eligibility())
        try {
            val oldClient = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); oldClient.login("listener", "synthetic-password")
            val successor = graph.configure(oldClient.server)
            web.enqueue(response()); successor.login("listener", "synthetic-password")
            val current = requireNotNull(graph.localEligibility)
            val persisted = sessions.value
            // A genuine install on a retired client reaches its stale lease but must
            // never reach the app wrapper below SessionStoreOwner's lease check.
            web.enqueue(response("other-account")); oldClient.login("listener", "synthetic-password")
            assertSame(persisted, sessions.value)
            assertSame(current, graph.localEligibility)
            assertFalse(context.getSharedPreferences("connection", Context.MODE_PRIVATE).getBoolean("offline_revoked", false))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(graph.localEligibilityIsCurrent(current))
            assertEquals(3, web.requestCount)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun explicitLogoutAndOriginSecurityFencesCannotBeUndoneByAnOlderGrantClearingTheFlag() = runBlocking {
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        for (logout in listOf(true, false)) {
            preferences.edit().putBoolean("offline_mode", false).commit()
            val sessions = Sessions()
            val retained = Eligibility()
            val web = MockWebServer().apply { start() }
            val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
            var cold: AppGraph? = null
            try {
                val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
                web.enqueue(response()); client.login("listener", "synthetic-password")
                val old = requireNotNull(graph.localEligibility)
                if (logout) {
                    graph.fenceForLogout(client)
                    graph.clear(client) // Later logout cleanup must not advance the same fence twice.
                } else graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port + 1}"))
                assertEquals(old.localAccountEpoch + 1L,
                    org.json.JSONObject(preferences.getString("offline_account_history", null)!!).getLong("epoch"))
                // Model an older genuine storage grant completing after the security fence.
                retained.value = old
                preferences.edit().putBoolean("offline_revoked", false).putBoolean("offline_mode", true).commit()
                cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
                assertNull(cold.localEligibility)
                assertEquals(old.localAccountEpoch, requireNotNull(cold.retirementScope).localAccountEpoch)
                assertFalse(cold.localPlaybackIsCurrent(old))
                assertNull(cold.client!!.currentSession())
                assertNull(cold.client!!.validatedSessionIdentity())
                assertNull(cold.restore())
                assertEquals(1, web.requestCount)
            } finally { cold?.clear(); graph.clear(); web.shutdown() }
        }
    }

    @Test fun separateAccepted401AfterUnpublishedReauthenticationReservesANewColdDenialEpoch() = runBlocking {
        val web = MockWebServer().apply { start() }
        val retained = Eligibility()
        val sessions = Sessions()
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
        val grantEntered = CountDownLatch(1)
        val releaseGrant = CountDownLatch(1)
        val secondDenied = CountDownLatch(1)
        val releaseSecondDenial = CountDownLatch(1)
        var reauthentication: kotlinx.coroutines.Job? = null
        var secondRejection: kotlinx.coroutines.Job? = null
        var cold: AppGraph? = null
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            web.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { ApiTransport(client).get(listOf("api", "library")) }.isFailure)
            assertEquals(1L, org.json.JSONObject(preferences.getString("offline_account_history", null)!!).getLong("epoch"))
            retained.beforeWrite = { value ->
                if (value?.localAccountEpoch == 1L) {
                    grantEntered.countDown()
                    check(releaseGrant.await(5, TimeUnit.SECONDS))
                }
            }
            web.enqueue(response())
            reauthentication = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(grantEntered.await(5, TimeUnit.SECONDS))
            val notifyDenial = client.onUnauthorized
            client.onUnauthorized = {
                notifyDenial?.invoke()
                secondDenied.countDown()
                // Keep core locked after the real denial while the stale grant writes.
                check(releaseSecondDenial.await(5, TimeUnit.SECONDS))
            }
            web.enqueue(MockResponse().setResponseCode(401))
            secondRejection = async(Dispatchers.IO) { runCatching { ApiTransport(client).get(listOf("api", "library")) } }
            assertTrue(secondDenied.await(5, TimeUnit.SECONDS))
            assertEquals("A distinct accepted401 cannot reuse the prior terminal event's epoch", 2L,
                org.json.JSONObject(preferences.getString("offline_account_history", null)!!).getLong("epoch"))
            releaseGrant.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (preferences.getBoolean("offline_revoked", true) && System.nanoTime() < deadline) Thread.yield()
            assertFalse("The old writer reached its flag clear before crash reconstruction", preferences.getBoolean("offline_revoked", true))
            val stale = requireNotNull(retained.value)
            assertEquals(1L, stale.localAccountEpoch)
            preferences.edit().putBoolean("offline_mode", true).commit()
            OfflineAccountJournal.forgetProcessForTest(context)
            cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
            assertNull(cold.localEligibility)
            assertFalse(cold.localPlaybackIsCurrent(stale))
            assertEquals(4, web.requestCount)
        } finally {
            releaseGrant.countDown(); releaseSecondDenial.countDown()
            reauthentication?.join(); secondRejection?.join()
            retained.beforeWrite = null
            runCatching { cold?.clear() }; graph.clear(); web.shutdown()
        }
    }

    @Test fun sameAccountCookieExpiryAndNullResetPreserveTheGenuineColdGrant() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val retained = Eligibility()
        val now = AtomicLong(System.currentTimeMillis())
        val graph = AppGraph(context, sessions, authenticationTimeMillis = now::get, offlineEligibilityStore = retained)
        var cold: AppGraph? = null
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            web.enqueue(response()); client.login("listener", "synthetic-password")
            assertSame(old, graph.localEligibility)
            assertEquals(old.localAccountEpoch,
                org.json.JSONObject(context.getSharedPreferences("connection", Context.MODE_PRIVATE)
                    .getString("offline_account_history", null)!!).getLong("epoch"))
            now.addAndGet(TimeUnit.HOURS.toMillis(2))
            assertNull(client.currentSession())
            client.clearSession() // Genuine core null RESET write, not account rejection.
            assertNull(sessions.value)
            assertSame(old, graph.localEligibility)
            assertEquals(old.localAccountEpoch,
                org.json.JSONObject(context.getSharedPreferences("connection", Context.MODE_PRIVATE)
                    .getString("offline_account_history", null)!!).getLong("epoch"))
            assertTrue(graph.setOfflineMode(true))
            cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
            val local = requireNotNull(cold.localEligibility)
            assertTrue(cold.localPlaybackIsCurrent(local))
            assertNull(cold.client!!.currentSession())
            assertNull(cold.client!!.validatedSessionIdentity())
            assertFalse(context.getSharedPreferences("connection", Context.MODE_PRIVATE).getBoolean("offline_revoked", false))
            assertEquals(2, web.requestCount)
        } finally { cold?.clear(); graph.clear(); web.shutdown() }
    }

    @Test fun ordinaryCookieExpiryDoesNotRevokeLocalEligibilityOrInventLiveAuthority() = runBlocking {
        val web = MockWebServer().apply { start() }
        val now = AtomicLong(System.currentTimeMillis())
        val retained = Eligibility()
        val graph = AppGraph(context, Sessions(), authenticationTimeMillis = now::get, offlineEligibilityStore = retained)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val local = graph.localEligibility!!
            graph.setOfflineMode(true)
            now.set(System.currentTimeMillis() + java.util.concurrent.TimeUnit.HOURS.toMillis(2))
            assertNull(client.currentSession())
            assertNull(client.validatedSessionIdentity())
            assertSame(local, graph.localEligibility)
            assertTrue(graph.localEligibilityIsCurrent(local))
            assertTrue(graph.localPlaybackIsCurrent(local))
            assertFalse(graph.sessionExpired.value)
            assertNull(graph.restore())
            assertEquals(1, web.requestCount)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun queuedOrdinaryExpiryCannotTurnANewerOfflineModeIntoSignedOutState() = runBlocking {
        val web = MockWebServer().apply { start() }
        val now = AtomicLong(System.currentTimeMillis())
        val graph = AppGraph(context, Sessions(), authenticationTimeMillis = now::get, offlineEligibilityStore = Eligibility())
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val local = graph.localEligibility!!
            now.set(System.currentTimeMillis() + java.util.concurrent.TimeUnit.HOURS.toMillis(2))
            assertNull(client.currentSession()) // Posts the ordinary online-expiry teardown.
            assertTrue("Retained-record policy needs no live Session or offline mode", graph.localEligibilityIsCurrent(local))
            graph.setOfflineMode(true)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(graph.sessionExpired.value)
            assertTrue(graph.localPlaybackIsCurrent(local))
            assertEquals(1, web.requestCount)
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun observedUnauthorizedRetiresTheRecordAndHeldReadersAcrossColdLaunch() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val retained = Eligibility()
        val graph = AppGraph(context, sessions, offlineEligibilityStore = retained)
        var cold: AppGraph? = null
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = graph.localEligibility!!
            web.enqueue(MockResponse().setResponseCode(401))
            try { ApiTransport(client).get(listOf("api", "library")); fail("Expected rejection") } catch (_: dev.spofify.mobile.core.ApiException) { }
            assertNull(graph.localEligibility)
            assertNull(retained.value)
            assertEquals("Under-auth 401 and deferred INVALIDATED must reserve just one denial epoch",
                old.localAccountEpoch + 1L,
                org.json.JSONObject(context.getSharedPreferences("connection", Context.MODE_PRIVATE)
                    .getString("offline_account_history", null)!!).getLong("epoch"))
            graph.setOfflineMode(true)
            assertFalse(graph.localPlaybackIsCurrent(old))
            retained.value = old // A stale genuine save must not undo an accepted-401 epoch.
            context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().putBoolean("offline_revoked", false).commit()
            cold = AppGraph(context, sessions, offlineEligibilityStore = retained)
            assertNull(cold.localEligibility)
            assertEquals(old.localAccountEpoch, requireNotNull(cold.retirementScope).localAccountEpoch)
            assertEquals(2, web.requestCount)
        } finally { cold?.clear(); graph.clear(); web.shutdown() }
    }

    @Test fun lateSavedMetadataCannotPublishAcrossOfflineModeAba() = runBlocking {
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = Eligibility())
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            graph.setOfflineMode(true)
            val local = graph.localEligibility!!
            graph.offlineLibrary = object : OfflineLibraryAccess {
                override val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
                override suspend fun collections(origin: String, accountId: String): List<OfflineSavedCollection> {
                    assertEquals(local.origin, origin); assertEquals(local.accountId, accountId)
                    entered.complete(Unit); release.await()
                    return listOf(OfflineSavedCollection(dev.spofify.mobile.core.LibraryItem("album", "album", "Saved album"),
                        emptyList(), emptyMap(), true))
                }
                override fun retire() = Unit
            }
            val readFence = graph.captureLocalRead(local)
            assertTrue(readFence())
            val result = async(Dispatchers.Default) { graph.offlineCollections(local) }
            entered.await()
            graph.setOfflineMode(false); graph.setOfflineMode(true)
            assertFalse("Exact read readiness cannot borrow a newer identical-looking offline mode", readFence())
            assertTrue(graph.captureLocalRead(local)())
            release.complete(Unit)
            assertTrue(result.await().isEmpty())
            assertEquals(1, web.requestCount)
        } finally { release.complete(Unit); graph.clear(); web.shutdown() }
    }

    @Test fun failedDurableRevocationStillFencesMemoryCompletesTeardownAndReportsFailure() = runBlocking {
        var storageUnavailable = false
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
                if (name != "connection") return super.getSharedPreferences(name, mode)
                return object : android.content.SharedPreferences by preferences {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
                                editor.putBoolean(key, value); return this
                            }
                            override fun commit() = if (storageUnavailable) false else editor.commit()
                        }
                    }
                }
            }
        }
        var retained: OfflineEligibility? = null
        val store = object : OfflineEligibilityStore {
            override fun read() = retained
            override fun write(value: OfflineEligibility?) { if (storageUnavailable) throw java.io.IOException("Synthetic write failure"); retained = value }
        }
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(wrapped, Sessions(), offlineEligibilityStore = store)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val local = graph.localEligibility!!
            storageUnavailable = true
            graph.fenceForLogout(client)
            assertFalse(graph.localPlaybackIsCurrent(local))
            assertNull(graph.localEligibility)
            assertTrue(graph.offlineStorageFailed.value)
            assertNotNull(retained) // Not claimed deleted when all durable mechanisms failed.
            try { graph.clear(client); fail("Expected explicit durable cleanup failure") } catch (_: java.io.IOException) { }
            assertNull(graph.client)
            assertNull(graph.playbackCoordinator)
            assertTrue(graph.offlineStorageFailed.value)
        } finally { storageUnavailable = false; graph.clear(); web.shutdown() }
    }

    @Test fun sessionStoreFailureStillCancelsRequestsClearsCapturedRuntimeAndDetachesEveryGraphOwner() = runBlocking {
        var saved: StoredSession? = null
        var failClear = false
        val sessions = object : SessionStore {
            override fun read() = saved
            override fun write(value: StoredSession?) {
                if (failClear && value == null) throw java.io.IOException("private synthetic storage detail")
                saved = value
            }
        }
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, sessions, offlineEligibilityStore = Eligibility())
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            assertNotNull(web.takeRequest(2, TimeUnit.SECONDS))
            web.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val active = async(Dispatchers.IO) { runCatching { ApiTransport(client).get(listOf("api", "library")) } }
            assertNotNull("A real pending call must exist before teardown", web.takeRequest(2, TimeUnit.SECONDS))
            failClear = true
            try { graph.clear(client); fail("Expected sanitized storage failure") }
            catch (failure: java.io.IOException) { assertEquals("Could not clear local sign-in data.", failure.message) }
            assertNull(graph.client)
            assertNull(graph.playbackCoordinator)
            assertNull(graph.imageLoader)
            assertNull(graph.localEligibilityState.value)
            assertNull(client.onAuthenticationChanged)
            assertNull(client.onUnauthorized)
            assertNull(client.currentSession())
            assertNull(client.validatedSessionIdentity())
            assertTrue("Captured client calls must actually be cancelled", withTimeout(3_000) { active.await() }.isFailure)
            assertNotNull("Failed persistence is not claimed deleted", saved)
        } finally { failClear = false; graph.clear(); web.shutdown() }
    }

    @Test fun observed401FencesEligibilityFlowAndPostsDrainageBeforeBlockedAdapterRetirement() = runBlocking {
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = Eligibility())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = CopyOnWriteArrayList<OfflineEligibility?>()
        val watching = launch(Dispatchers.Unconfined) { graph.localEligibilityState.collect { observed += it } }
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val eligible = requireNotNull(graph.localEligibility)
            val epoch = PlaybackRequests.epoch
            graph.offlineLibrary = object : OfflineLibraryAccess {
                override val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
                override suspend fun collections(origin: String, accountId: String): List<OfflineSavedCollection> = emptyList()
                override fun retire() {
                    assertNull(graph.localEligibilityState.value)
                    assertNull(graph.offlineLibrary)
                    assertNotEquals(epoch, PlaybackRequests.epoch)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            web.enqueue(MockResponse().setResponseCode(401))
            val rejected = async(Dispatchers.IO) { runCatching { ApiTransport(client).get(listOf("api", "library")) } }
            assertTrue("Purge must really be blocked", entered.await(3, TimeUnit.SECONDS))
            assertFalse(graph.localPlaybackIsCurrent(eligible))
            assertNull(graph.localEligibility)
            assertTrue(observed.any { it === eligible })
            assertNull(observed.last())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("401 drainage runs while purge is STILL blocked", graph.sessionExpired.value)
            assertEquals(1L, release.count)
            release.countDown()
            assertTrue(withTimeout(3_000) { rejected.await() }.exceptionOrNull() is dev.spofify.mobile.core.ApiException)
            assertEquals(2, web.requestCount)
        } finally { release.countDown(); watching.cancel(); graph.clear(); web.shutdown() }
    }

    @Test fun lateValidatedSaveCannotRepublishEligibilityAfterImmediateUnauthorizedFence() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unauthorized = CountDownLatch(1)
        var stored: OfflineEligibility? = null
        val store = object : OfflineEligibilityStore {
            override fun read() = stored
            override fun write(value: OfflineEligibility?) {
                if (value != null) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                stored = value
            }
        }
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = store)
        val observed = CopyOnWriteArrayList<OfflineEligibility?>()
        val watching = launch(Dispatchers.Unconfined) { graph.localEligibilityState.collect { observed += it } }
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            val realFence = requireNotNull(client.onUnauthorized)
            client.onUnauthorized = { realFence(); unauthorized.countDown() }
            web.enqueue(response())
            val validating = async(Dispatchers.IO) { client.login("listener", "synthetic-password") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNotNull(web.takeRequest(2, TimeUnit.SECONDS))
            val epoch = PlaybackRequests.epoch
            web.enqueue(MockResponse().setResponseCode(401))
            val rejected = async(Dispatchers.IO) { runCatching { ApiTransport(client).get(listOf("api", "library")) } }
            assertTrue("Real 401 must fence without waiting for the VALIDATED storage/cache lock", unauthorized.await(3, TimeUnit.SECONDS))
            assertNotEquals(epoch, PlaybackRequests.epoch)
            assertNull(graph.localEligibilityState.value)
            assertEquals(1L, release.count)
            release.countDown()
            withTimeout(3_000) { validating.await(); rejected.await() }
            assertNull(client.currentSession())
            assertNull(graph.localEligibility)
            assertNull(stored)
            assertTrue("The stale saved record must never reappear in observable state", observed.all { it == null })
            assertTrue(context.getSharedPreferences("connection", Context.MODE_PRIVATE).getBoolean("offline_revoked", false))
            assertEquals(2, web.requestCount)
        } finally { release.countDown(); watching.cancel(); graph.clear(); web.shutdown() }
    }

    @Test fun offlineEntryFencesOldAccountWhenSuccessorCoreInstallPrecedesParkedGraphValidatedDelivery() = runBlocking {
        val web = MockWebServer().apply { start() }
        val retained = Eligibility()
        val now = AtomicLong(System.currentTimeMillis())
        val graph = AppGraph(context, Sessions(), authenticationTimeMillis = now::get, offlineEligibilityStore = retained)
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = CopyOnWriteArrayList<OfflineEligibility?>()
        val watching = launch(Dispatchers.Unconfined) { graph.localEligibilityState.collect { observed += it } }
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            val realCallback = requireNotNull(client.onAuthenticationChanged)
            client.onAuthenticationChanged = { change ->
                if (change.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED && change.identity?.accountId == "other-account") {
                    committed.countDown() // Core installation is finished; Graph has not received this event.
                    check(release.await(5, TimeUnit.SECONDS))
                }
                realCallback(change)
            }
            web.enqueue(response("other-account"))
            val successor = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(committed.await(3, TimeUnit.SECONDS))
            assertEquals("other-account", client.validatedSessionIdentity()?.accountId)
            val changedScope = requireNotNull(client.observedAuthenticatedScope())
            assertEquals(2L, changedScope.scopeRevision)
            now.set(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2))
            assertNull("Force genuine B cookie expiry BEFORE entering offline mode", client.currentSession())
            assertNull(client.validatedSessionIdentity())
            assertSame(changedScope, client.observedAuthenticatedScope())
            assertNull("The durable pre-install fence already retired A before B's callback", graph.localEligibility)
            assertSame(old, graph.retirementScope)
            assertFalse(graph.localEligibilityIsCurrent(old))
            assertTrue(graph.setOfflineMode(true))
            assertTrue(graph.offlineMode.value)
            assertNull(graph.localEligibilityState.value)
            assertFalse(graph.localPlaybackIsCurrent(old))
            assertNull(retained.value)
            release.countDown()
            withTimeout(3_000) { successor.await() }
            assertNull(client.currentSession())
            assertSame(changedScope, client.observedAuthenticatedScope())
            assertNull(graph.localEligibility)
            assertNull(AppGraph::class.java.getDeclaredField("audioCache").apply { isAccessible = true }.get(graph))
            assertTrue("No late B local grant may appear after the offline edge", observed.none { it?.accountId == "other-account" })
            assertNull(retained.value)
            assertEquals(2, web.requestCount)
        } finally { release.countDown(); watching.cancel(); graph.clear(); web.shutdown() }
    }

    @Test fun expiredAccountAbaCannotReuseTheOriginalSameAccountRecordWhileValidatedDeliveryIsParked() = runBlocking {
        val web = MockWebServer().apply { start() }
        val now = AtomicLong(System.currentTimeMillis())
        val retained = Eligibility()
        val graph = AppGraph(context, Sessions(), authenticationTimeMillis = now::get, offlineEligibilityStore = retained)
        val bInstalled = CountDownLatch(1)
        val aInstalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            val firstScope = requireNotNull(client.observedAuthenticatedScope())
            assertEquals(1L, firstScope.scopeRevision)
            val realCallback = requireNotNull(client.onAuthenticationChanged)
            client.onAuthenticationChanged = { change ->
                if (change.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED) {
                    if (change.identity?.accountId == "other-account") bInstalled.countDown() else aInstalled.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                realCallback(change)
            }
            web.enqueue(response("other-account"))
            val b = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(bInstalled.await(3, TimeUnit.SECONDS))
            assertEquals(2L, client.observedAuthenticatedScope()?.scopeRevision)
            web.enqueue(response())
            val a = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(aInstalled.await(3, TimeUnit.SECONDS))
            val latest = requireNotNull(client.observedAuthenticatedScope())
            assertEquals(old.accountId, latest.accountId)
            assertEquals(3L, latest.scopeRevision)
            assertNull(graph.localEligibility)
            assertSame(old, graph.retirementScope)
            assertFalse("Account equality cannot refresh the old record's A1 baseline to A3", graph.localEligibilityIsCurrent(old))
            now.set(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2))
            assertNull(client.currentSession())
            assertSame(latest, client.observedAuthenticatedScope())
            assertTrue(graph.setOfflineMode(true))
            assertNull(graph.localEligibilityState.value)
            assertFalse(graph.localPlaybackIsCurrent(old))
            assertNull(retained.value)
            release.countDown()
            withTimeout(3_000) { b.await(); a.await() }
            assertNull(graph.localEligibility)
            assertSame(latest, client.observedAuthenticatedScope())
            assertEquals(3, web.requestCount)
        } finally { release.countDown(); graph.clear(); web.shutdown() }
    }

    @Test fun genuineValidatedReturnToSameAccountRetiresTheOldAbaRecordBeforeCreatingAFreshOne() = runBlocking {
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = Eligibility())
        val bInstalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = AtomicLong()
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            graph.offlineLibrary = object : OfflineLibraryAccess {
                override val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
                override suspend fun collections(origin: String, accountId: String): List<OfflineSavedCollection> = emptyList()
                override fun retire() { retired.incrementAndGet() }
            }
            val realCallback = requireNotNull(client.onAuthenticationChanged)
            client.onAuthenticationChanged = { change ->
                if (change.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED && change.identity?.accountId == "other-account") {
                    bInstalled.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                realCallback(change)
            }
            web.enqueue(response("other-account"))
            val b = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(bInstalled.await(3, TimeUnit.SECONDS))
            assertEquals(0L, retired.get())
            web.enqueue(response())
            withTimeout(3_000) { kotlinx.coroutines.withContext(Dispatchers.IO) { client.login("listener", "synthetic-password") } }
            val fresh = requireNotNull(graph.localEligibility)
            assertNotSame("A genuine A3 validation must not bless the original A1 record", old, fresh)
            assertEquals(old.accountId, fresh.accountId)
            assertEquals(old.localAccountEpoch + 2L, fresh.localAccountEpoch)
            assertEquals(3L, client.observedAuthenticatedScope()?.scopeRevision)
            assertEquals(1L, retired.get())
            assertFalse(graph.localEligibilityIsCurrent(old))
            assertTrue("A >1 witness only matches a newly genuine VALIDATED record, never a cold old record", graph.localEligibilityIsCurrent(fresh))
            graph.setOfflineMode(true)
            assertTrue(graph.localPlaybackIsCurrent(fresh))
            release.countDown()
            withTimeout(3_000) { b.await() }
            assertSame("Late B delivery cannot retire the newer A3 record", fresh, graph.localEligibility)
            assertTrue(graph.localPlaybackIsCurrent(fresh))
            assertEquals(1L, retired.get())
            assertEquals(3, web.requestCount)
        } finally { release.countDown(); graph.clear(); web.shutdown() }
    }

    @Test fun coldRecordCannotBorrowAMatchingScopeAfterAnUnobservedNewClientAccountSwitch() = runBlocking {
        val web = MockWebServer().apply { start() }
        val sessions = Sessions()
        val retained = Eligibility()
        val original = AppGraph(context, sessions, offlineEligibilityStore = retained)
        var cold: AppGraph? = null
        val firstAInstalled = CountDownLatch(1)
        val aCommits = AtomicLong()
        val bInstalled = CountDownLatch(1)
        val aInstalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val firstClient = original.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); firstClient.login("listener", "synthetic-password")
            val graph = AppGraph(context, sessions, offlineEligibilityStore = retained).also { cold = it }
            val record = requireNotNull(graph.localEligibility)
            val client = requireNotNull(graph.client)
            assertNotSame(firstClient, client)
            assertNull(client.observedAuthenticatedScope())
            assertTrue(graph.localEligibilityIsCurrent(record))
            val realCallback = requireNotNull(client.onAuthenticationChanged)
            client.onAuthenticationChanged = { change ->
                if (change.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED) {
                    if (change.identity?.accountId == "other-account") bInstalled.countDown()
                    else if (aCommits.incrementAndGet() == 1L) firstAInstalled.countDown() else aInstalled.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                realCallback(change)
            }
            web.enqueue(response())
            val firstA = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(firstAInstalled.await(3, TimeUnit.SECONDS))
            assertEquals(1L, client.observedAuthenticatedScope()?.scopeRevision)
            assertSame(record, graph.localEligibility)
            assertTrue("A first matching witness may only preserve an ALREADY genuine cold record", graph.localEligibilityIsCurrent(record))
            web.enqueue(response("other-account"))
            val b = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(bInstalled.await(3, TimeUnit.SECONDS))
            assertEquals(2L, client.observedAuthenticatedScope()?.scopeRevision)
            assertFalse(graph.localEligibilityIsCurrent(record))
            web.enqueue(response())
            val a = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(aInstalled.await(3, TimeUnit.SECONDS))
            val latest = requireNotNull(client.observedAuthenticatedScope())
            assertEquals(record.accountId, latest.accountId)
            assertEquals(3L, latest.scopeRevision)
            assertNull(graph.localEligibility)
            assertSame(record, graph.retirementScope)
            assertFalse("A cold baseline permits only null/first matching scope, never an unobserved ABA", graph.localEligibilityIsCurrent(record))
            graph.setOfflineMode(true)
            assertNull(graph.localEligibility)
            release.countDown()
            withTimeout(3_000) { firstA.await(); b.await(); a.await() }
            assertNull(graph.localEligibility)
            assertNull(retained.value)
            assertEquals(4, web.requestCount)
        } finally { release.countDown(); cold?.clear(); original.clear(); web.shutdown() }
    }

    @Test fun replacingTheClientCannotReclassifyAKnownSwitchedRecordAsAColdRecord() = runBlocking {
        val web = MockWebServer().apply { start() }
        val retained = Eligibility()
        val now = AtomicLong(System.currentTimeMillis())
        val stopping = StopBarrierContext(context)
        val graph = AppGraph(stopping, Sessions(), authenticationTimeMillis = now::get, offlineEligibilityStore = retained)
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            val realCallback = requireNotNull(client.onAuthenticationChanged)
            client.onAuthenticationChanged = { change ->
                if (change.kind == dev.spofify.mobile.core.AuthenticationChangeKind.VALIDATED && change.identity?.accountId == "other-account") {
                    committed.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                realCallback(change)
            }
            web.enqueue(response("other-account"))
            val b = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(committed.await(3, TimeUnit.SECONDS))
            assertFalse(graph.localEligibilityIsCurrent(old))
            val changedScope = requireNotNull(client.observedAuthenticatedScope())
            now.set(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2))
            assertNull(client.currentSession())
            stopping.armed.set(true)
            val configuring = async(Dispatchers.IO) { graph.configure(client.server) }
            assertTrue(stopping.entered.await(3, TimeUnit.SECONDS))
            assertSame(changedScope, client.observedAuthenticatedScope())
            assertNull("Final scope denial precedes fallible platform teardown", graph.localEligibility)
            stopping.release.countDown()
            val next = withTimeout(3_000) { configuring.await() }
            assertNotSame(client, next)
            assertNull(next.observedAuthenticatedScope())
            assertNull("Known denial must be retired before the old client witness is discarded", graph.localEligibility)
            assertNull(retained.value)
            graph.setOfflineMode(true)
            assertFalse(graph.localPlaybackIsCurrent(old))
            release.countDown()
            withTimeout(3_000) { b.await() }
            assertNull(graph.localEligibility)
            assertEquals(2, web.requestCount)
        } finally { stopping.release.countDown(); release.countDown(); graph.clear(); web.shutdown() }
    }

    @Test fun pendingAccountResponseReleasedAfterEarlyCoreRetirementCannotInstallDuringBlockedTeardown() = runBlocking {
        val web = MockWebServer().apply { start() }
        val stopping = StopBarrierContext(context)
        val retained = Eligibility()
        val graph = AppGraph(stopping, Sessions(), offlineEligibilityStore = retained)
        val pending = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            val oldScope = requireNotNull(client.observedAuthenticatedScope())
            web.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    pending.countDown(); check(releaseResponse.await(5, TimeUnit.SECONDS))
                    return response("other-account")
                }
            }
            val b = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(pending.await(3, TimeUnit.SECONDS))
            stopping.armed.set(true)
            val configuring = async(Dispatchers.IO) { graph.configure(client.server) }
            assertTrue(stopping.entered.await(3, TimeUnit.SECONDS))
            releaseResponse.countDown()
            assertTrue("The already-pending B response must be stale", withTimeout(3_000) { b.await() }.isFailure)
            assertNull(client.currentSession())
            assertSame(oldScope, client.observedAuthenticatedScope())
            assertSame("Matching A may legitimately survive recreation", old, graph.localEligibility)
            stopping.release.countDown()
            val next = withTimeout(3_000) { configuring.await() }
            assertNull(next.observedAuthenticatedScope())
            graph.setOfflineMode(true)
            assertTrue(graph.localPlaybackIsCurrent(old))
            assertSame(old, retained.value)
            assertEquals(2, web.requestCount)
        } finally { releaseResponse.countDown(); stopping.release.countDown(); graph.clear(); web.shutdown() }
    }

    @Test fun acceptedShared401StillFencesWhileRetirementWaitsForItsUnderCoreLockNotification() = runBlocking {
        val web = MockWebServer().apply { start() }
        val stopping = StopBarrierContext(context)
        val retained = Eligibility()
        val graph = AppGraph(stopping, Sessions(), offlineEligibilityStore = retained)
        val accepted = CountDownLatch(1)
        val releaseNotification = CountDownLatch(1)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            val realFence = requireNotNull(client.onUnauthorized)
            client.onUnauthorized = {
                accepted.countDown() // Genuine core-accepted 401, still holding its generation lock.
                check(releaseNotification.await(5, TimeUnit.SECONDS))
                realFence()
            }
            web.enqueue(MockResponse().setResponseCode(401))
            val rejected = async(Dispatchers.IO) { runCatching { ApiTransport(client).get(listOf("api", "library")) } }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            val epoch = PlaybackRequests.epoch
            stopping.armed.set(true)
            val configuring = async(Dispatchers.IO) { graph.configure(client.server) }
            withTimeout(3_000) { while (PlaybackRequests.epoch == epoch) kotlinx.coroutines.delay(5) }
            assertEquals("Core retirement must precede stopService", 1L, stopping.entered.count)
            releaseNotification.countDown()
            assertTrue(stopping.entered.await(3, TimeUnit.SECONDS))
            assertNull("Cache ownership must not disable the retiring client's 401 denial", graph.localEligibility)
            assertFalse(graph.localEligibilityIsCurrent(old))
            stopping.release.countDown()
            val next = withTimeout(3_000) { configuring.await() }
            withTimeout(3_000) { rejected.await() }
            assertNull(next.observedAuthenticatedScope())
            assertNull(retained.value)
            graph.setOfflineMode(true)
            assertFalse(graph.localPlaybackIsCurrent(old))
            assertEquals(2, web.requestCount)
        } finally { releaseNotification.countDown(); stopping.release.countDown(); graph.clear(); web.shutdown() }
    }

    @Test fun staleShared401AfterCoreRetirementCannotRevokeReplacementEligibility() = runBlocking {
        val web = MockWebServer().apply { start() }
        val stopping = StopBarrierContext(context)
        val graph = AppGraph(stopping, Sessions(), offlineEligibilityStore = Eligibility())
        val pending = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            web.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    if (request.path == "/api/library") {
                        pending.countDown(); check(releaseResponse.await(5, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(401)
                    }
                    return response("other-account")
                }
            }
            val rejected = async(Dispatchers.IO) { runCatching { ApiTransport(client).get(listOf("api", "library")) } }
            assertTrue(pending.await(3, TimeUnit.SECONDS))
            stopping.armed.set(true)
            val configuring = async(Dispatchers.IO) { graph.configure(client.server) }
            assertTrue(stopping.entered.await(3, TimeUnit.SECONDS))
            releaseResponse.countDown() // Before cancellation, but AFTER clearSession retired its generation.
            withTimeout(3_000) { rejected.await() }
            assertSame("A stale old-generation 401 is not an accepted rejection", old, graph.localEligibility)
            stopping.release.countDown()
            val next = withTimeout(3_000) { configuring.await() }
            next.login("listener", "synthetic-password")
            val fresh = requireNotNull(graph.localEligibility)
            assertEquals("other-account", fresh.accountId)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(fresh, graph.localEligibility)
            assertTrue(graph.localEligibilityIsCurrent(fresh))
            assertFalse(graph.sessionExpired.value)
            assertEquals(3, web.requestCount)
        } finally { releaseResponse.countDown(); stopping.release.countDown(); graph.clear(); web.shutdown() }
    }

    @Test fun throwingStopServiceStillFinishesKnownScopeRetirementAndOwnerBookkeeping() = runBlocking {
        val web = MockWebServer().apply { start() }
        val stopping = StopBarrierContext(context)
        val retained = Eligibility()
        val graph = AppGraph(stopping, Sessions(), offlineEligibilityStore = retained)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            stopping.failStop = true
            stopping.armed.set(true)
            stopping.release.countDown()
            try { graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port + 1}")); fail("Expected teardown failure") }
            catch (_: java.io.IOException) { }
            assertNull(client.currentSession())
            assertNull(client.onUnauthorized)
            assertNull(client.onAuthenticationChanged)
            assertNull(graph.localEligibility)
            assertNull(retained.value)
            assertNull(graph.playbackCoordinator)
            assertNull(graph.imageLoader)
            assertFalse(graph.localEligibilityIsCurrent(old))
            assertFalse(graph.offlineStorageFailed.value)
            assertEquals(1, web.requestCount)
        } finally { stopping.release.countDown(); graph.clear(); web.shutdown() }
    }

    @Test fun validatedSaveParkedInStorageCannotPublishRecordOrCacheAcrossOfflineEntry() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var stored: OfflineEligibility? = null
        val store = object : OfflineEligibilityStore {
            override fun read() = stored
            override fun write(value: OfflineEligibility?) {
                if (value?.accountId == "other-account") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                stored = value
            }
        }
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = store)
        val observed = CopyOnWriteArrayList<OfflineEligibility?>()
        val watching = launch(Dispatchers.Unconfined) { graph.localEligibilityState.collect { observed += it } }
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = requireNotNull(graph.localEligibility)
            web.enqueue(response("other-account"))
            val successor = async(Dispatchers.IO) { runCatching { client.login("listener", "synthetic-password") } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals("other-account", client.validatedSessionIdentity()?.accountId)
            val releaseAfterEdge = async(Dispatchers.IO) {
                try {
                    withTimeout(3_000) { while (!graph.offlineMode.value) kotlinx.coroutines.delay(5) }
                    assertNull(graph.localEligibilityState.value)
                    assertFalse(graph.localPlaybackIsCurrent(old))
                } finally { release.countDown() }
            }
            assertTrue(graph.setOfflineMode(true))
            withTimeout(3_000) { successor.await(); releaseAfterEdge.await() }
            assertNull(graph.localEligibility)
            assertNull(stored)
            assertNull(graph.audioCache(client, requireNotNull(client.currentSession())))
            assertTrue("Completing a real disk write is not permission to publish into the newer mode", observed.none { it?.accountId == "other-account" })
            assertEquals(2, web.requestCount)
        } finally { release.countDown(); watching.cancel(); graph.clear(); web.shutdown() }
    }

    @Test fun currentEligibilityWithoutAnInstalledOwnerIsAnErrorNotAFakeEmptyLibrary() = runBlocking {
        val web = MockWebServer().apply { start() }
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = Eligibility())
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            graph.setOfflineMode(true)
            val eligible = requireNotNull(graph.localEligibility)
            val installed = requireNotNull(graph.offlineLibrary)
            assertTrue(installed is SavedOfflineLibraryAdapter)
            assertTrue("Healthy installed empty storage is genuinely empty", graph.offlineCollections(eligible).isEmpty())
            installed.retire()
            graph.offlineLibrary = null // Explicitly arrange the unavailable-owner branch.
            try { graph.offlineCollections(eligible); fail("Missing installed owner is unavailable, not empty") }
            catch (failure: java.io.IOException) { assertEquals("Saved library unavailable.", failure.message) }
            graph.fenceForLogout()
            assertTrue(graph.offlineCollections(eligible).isEmpty())
        } finally { graph.clear(); web.shutdown() }
    }

    @Test fun localLogoutAndOriginSwitchFenceWithoutSendingOfflineCleanupTraffic() = runBlocking {
        val web = MockWebServer().apply { start() }
        val retained = Eligibility()
        val graph = AppGraph(context, Sessions(), offlineEligibilityStore = retained)
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            web.enqueue(response()); client.login("listener", "synthetic-password")
            val old = graph.localEligibility!!
            graph.setOfflineMode(true)
            graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port + 1}"))
            assertNull(graph.localEligibility)
            assertFalse(graph.localPlaybackIsCurrent(old))
            graph.clear()
            assertNull(retained.value)
            assertEquals(1, web.requestCount)
            assertTrue(graph.offlineMode.value)
        } finally { graph.clear(); web.shutdown() }
    }
}
