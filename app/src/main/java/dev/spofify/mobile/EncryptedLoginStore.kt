package dev.spofify.mobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import javax.crypto.SecretKey

/** The latest successful login, encrypted separately from the session and excluded from backup. */
class EncryptedLoginStore internal constructor(private val encrypted: EncryptedValueStore) : LoginStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        EncryptedValueStore.androidKeyStoreKey(KEY_ALIAS),
    )

    internal constructor(preferences: SharedPreferences, keyProvider: () -> SecretKey) : this(
        EncryptedValueStore(preferences, BLOB_KEY, keyProvider, KEY_ALIAS.toByteArray(Charsets.UTF_8)),
    )

    @Synchronized
    override fun read(): RememberedLogin? = encrypted.read { plaintext ->
        val json = JSONObject(plaintext)
        RememberedLogin(json.get("server") as String, json.get("username") as String, json.get("password") as String)
    }

    @Synchronized
    override fun write(value: RememberedLogin?) {
        val json = value?.let {
            JSONObject().put("server", it.server).put("username", it.username).put("password", it.password).toString()
        }
        encrypted.write(json)
    }

    internal companion object {
        const val PREFERENCES = "remembered_login"
        const val BLOB_KEY = "encrypted_login"
        const val KEY_ALIAS = "spofify.login.v1"
    }
}
