package dev.spofify.mobile.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.spofify.mobile.core.auth.*
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Installed namespaces and ciphertext format are retained, not migrated:
 * session/encrypted/spofify.session.v1: Base64(12-byte IV + AES-GCM ciphertext/tag), no AAD.
 * remembered_login/encrypted_login/spofify.login.v1: same format, alias UTF-8 as AAD.
 * No read failure deletes ciphertext or replaces an unavailable/missing existing key.
 */
class EncryptedAuthStores(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val sessionValue = EncryptedValue(context.getSharedPreferences("session", Context.MODE_PRIVATE),
        "encrypted", "spofify.session.v1", null)
    private val loginValue = EncryptedValue(context.getSharedPreferences("remembered_login", Context.MODE_PRIVATE),
        "encrypted_login", "spofify.login.v1", "spofify.login.v1".toByteArray(Charsets.UTF_8))

    val sessions: SessionStore = object : SessionStore {
        override fun read(): StoredSession? = sessionValue.read()?.let { json.decodeFromString<StoredSession>(it) }
        override fun write(value: StoredSession?) { sessionValue.write(value?.let { json.encodeToString(it) }) }
    }
    val logins: LoginStore = object : LoginStore {
        override fun read(): RememberedLogin? = loginValue.read()?.let { json.decodeFromString<RememberedLogin>(it) }
        override fun write(value: RememberedLogin) { loginValue.write(json.encodeToString(value)) }
    }
}

private class EncryptedValue(
    private val preferences: SharedPreferences,
    private val name: String,
    private val alias: String,
    private val aad: ByteArray?,
) {
    @Synchronized
    fun read(): String? {
        val raw = preferences.getString(name, null) ?: return null
        try {
            return AuthCiphertext.decrypt(raw, key(create = false), aad)
        } catch (_: Exception) {
            throw IOException("Encrypted sign-in storage unavailable")
        }
    }

    // The KTX edit helper discards commit()'s result. Auth writes must report persistence failures.
    @SuppressLint("UseKtx")
    @Synchronized
    fun write(value: String?) {
        if (value == null) {
            if (!preferences.edit().remove(name).commit()) throw IOException("Could not clear saved session")
            return
        }
        // Encrypt completely before editing; do not generate a key over existing ciphertext.
        val encoded = AuthCiphertext.encrypt(value, key(create = !preferences.contains(name)), aad)
        if (!preferences.edit().putString(name, encoded).commit()) throw IOException("Could not save sign-in")
    }

    private fun key(create: Boolean): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) {
            store.getKey(alias, null) as? SecretKey ?: throw IOException("Sign-in key unavailable")
        } else {
            if (!create) throw IOException("Sign-in key unavailable")
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }

    companion object { private val keyLock = Any() }
}
