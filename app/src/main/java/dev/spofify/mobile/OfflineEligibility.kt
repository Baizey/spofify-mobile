package dev.spofify.mobile

import android.content.Context
import dev.spofify.mobile.core.ServerAddress
import android.content.SharedPreferences
import org.json.JSONObject

/** Local retained-library access only. This is never a Session, server credential or lease.
 * Instances are incarnation fences: equal account strings do not revive a retired reader. */
class OfflineEligibility internal constructor(val origin: String, val accountId: String, val username: String,
    val localAccountEpoch: Long, internal val accountEpochPresent: Boolean = true) {
    // Existing three-argument fixtures/records are LEGACY, unlike a protected explicit 0.
    internal constructor(origin: String, accountId: String, username: String) : this(origin, accountId, username, 0L, false)
}

/** Denial-only journal shared across graph replacement. Its lock never calls core,
 * media, graph publication or the encrypted/retirement store. Checks use only snapshot. */
internal class OfflineAccountJournal private constructor(private val preferences: SharedPreferences) {
    data class History(val origin: String, val accountId: String, val epoch: Long)
    data class Snapshot(val history: History?, val failed: Boolean = false, val corrupt: Boolean = false)
    @Volatile var snapshot: Snapshot = load()
        private set

    private fun load(): Snapshot = try {
        val raw = preferences.getString("offline_account_history", null)
        if (raw == null) {
            if (preferences.getBoolean("offline_account_history_initialized", false)) Snapshot(null, true, true)
            else Snapshot(null)
        } else {
            val json = JSONObject(raw)
            val history = History(json.getString("origin"), json.getString("accountId"), json.getLong("epoch"))
            require(history.epoch >= 0L && history.accountId.isNotBlank() &&
                ServerAddress.parse(history.origin).origin == history.origin)
            Snapshot(history)
        }
    } catch (_: Exception) { Snapshot(null, true, true) }

    fun seedLegacy(record: OfflineEligibility?): History? = synchronized(mutex) {
        check(!snapshot.corrupt) { "Local account history unavailable" }
        if (snapshot.history == null && record != null) {
            // A v2/epoch-bearing ciphertext proves migration already happened. Losing
            // the journal must never silently reclassify that record as legacy epoch 0.
            if (record.accountEpochPresent || record.localAccountEpoch != 0L) {
                snapshot = Snapshot(null, true, true)
                error("Migrated local account history is missing")
            }
            commit(History(record.origin, record.accountId, 0L), revoked = false)
        } else if (snapshot.failed && snapshot.history != null) {
            commit(checkNotNull(snapshot.history), revoked = true)
        }
        snapshot.history
    }

    fun commit(next: History, revoked: Boolean) = synchronized(mutex) {
        val before = snapshot
        check(!before.corrupt) { "Local account history unavailable" }
        require(next.epoch >= 0L && next.accountId.isNotBlank() && ServerAddress.parse(next.origin).origin == next.origin)
        before.history?.let { prior ->
            check(next.epoch >= prior.epoch && (next.epoch != prior.epoch ||
                next.origin == prior.origin && next.accountId == prior.accountId)) { "Local account history moved backwards" }
        }
        try {
            // commit(false) may have changed SharedPreferences' RAM map. Roll that map
            // back to the LAST COMMITTED tuple and explicitly recommit before any retry.
            if (before.failed) before.history?.let { write(it, revoked = true) }
            write(next, revoked)
            snapshot = Snapshot(next)
        } catch (error: Exception) {
            snapshot = before.copy(failed = true)
            throw error
        }
    }

    private fun write(history: History, revoked: Boolean) {
        val raw = JSONObject().put("origin", history.origin).put("accountId", history.accountId)
            .put("epoch", history.epoch).toString()
        val editor = preferences.edit()
        editor.putString("offline_account_history", raw)
        editor.putBoolean("offline_account_history_initialized", true)
        if (revoked) editor.putBoolean("offline_revoked", true)
        check(editor.commit()) { "Could not persist local account history" }
    }

    companion object {
        private val mutex = Any()
        private val journals = java.util.WeakHashMap<Context, OfflineAccountJournal>()
        fun obtain(context: Context, preferences: SharedPreferences): OfflineAccountJournal = synchronized(mutex) {
            journals.getOrPut(context.applicationContext) { OfflineAccountJournal(preferences) }
        }

        /** Simulates process death in source tests; production never clears epoch/history. */
        internal fun forgetProcessForTest(context: Context) = synchronized(mutex) { journals.remove(context.applicationContext); Unit }
    }
}

interface OfflineEligibilityStore {
    fun read(): OfflineEligibility?
    fun write(value: OfflineEligibility?)
    fun revoke() = write(null)
}

internal class EncryptedOfflineEligibilityStore(context: Context) : OfflineEligibilityStore {
    private val values = EncryptedValueStore(
        context.getSharedPreferences("offline_eligibility", Context.MODE_PRIVATE), "validated_account",
        EncryptedValueStore.androidKeyStoreKey("spofify.offline.eligibility.v1"),
        associatedData = "spofify.offline.eligibility.v1".toByteArray(),
        preserveUnavailableReads = true,
    )

    override fun read(): OfflineEligibility? = values.read { text ->
        val json = JSONObject(text)
        val version = json.getInt("version")
        require(version == 1 || version == 2)
        val origin = json.getString("origin")
        val account = json.getString("accountId")
        val username = json.getString("username")
        require(ServerAddress.parse(origin).origin == origin && account.isNotBlank() && username.isNotBlank())
        val present = json.has("localAccountEpoch")
        require(version != 2 || present)
        val epoch = if (present) json.getLong("localAccountEpoch") else 0L
        require(epoch >= 0L)
        OfflineEligibility(origin, account, username, epoch, present)
    }

    override fun revoke() {
        try { values.write(null) }
        catch (error: Exception) {
            // This dedicated key protects ONLY local eligibility, never sessions/passwords.
            // Destruction makes a stale ciphertext unusable even when preference deletion failed.
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry("spofify.offline.eligibility.v1")
            check(!keyStore.containsAlias("spofify.offline.eligibility.v1")) { "Could not revoke local access" }
        }
    }

    override fun write(value: OfflineEligibility?) = values.write(value?.let {
        JSONObject().put("version", 2).put("origin", it.origin).put("accountId", it.accountId)
            .put("username", it.username).put("localAccountEpoch", it.localAccountEpoch).toString()
    })
}
