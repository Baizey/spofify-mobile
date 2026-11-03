package dev.spofify.mobile

import android.content.Context
import android.content.SharedPreferences
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import org.json.JSONObject
import javax.crypto.SecretKey

/** A single encrypted, non-backed-up session, independent of remembered login credentials. */
class EncryptedSessionStore internal constructor(private val encrypted: EncryptedValueStore) : SessionStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        EncryptedValueStore.androidKeyStoreKey(KEY_ALIAS),
    )

    internal constructor(preferences: SharedPreferences, keyProvider: () -> SecretKey) : this(
        // Keep the original preference name, key alias, blob key, and unaadorned IV+ciphertext format.
        EncryptedValueStore(preferences, BLOB_KEY, keyProvider),
    )

    @Synchronized
    override fun read(): StoredSession? = encrypted.read { plaintext ->
        val json = JSONObject(plaintext)
        val session = json.getJSONObject("session")
        StoredSession(json.get("server") as String, json.get("cookie") as String, Session(
            session.get("accountId") as String, session.get("username") as String,
            session.get("role") as String, session.get("csrfToken") as String))
    }

    @Synchronized
    override fun write(value: StoredSession?) {
        val json = value?.let {
            JSONObject().put("server", it.server).put("cookie", it.cookie)
                .put("session", JSONObject().put("accountId", it.session.accountId)
                    .put("username", it.session.username).put("role", it.session.role)
                    .put("csrfToken", it.session.csrfToken)).toString()
        }
        encrypted.write(json)
    }

    internal companion object {
        const val PREFERENCES = "session"
        const val BLOB_KEY = "encrypted"
        const val KEY_ALIAS = "spofify.session.v1"
    }
}
