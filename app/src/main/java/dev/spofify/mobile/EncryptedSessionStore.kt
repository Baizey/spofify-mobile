package dev.spofify.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A single encrypted, non-backed-up session; plaintext credentials are never saved. */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val alias = "spofify.session.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized
    override fun read(): StoredSession? {
        val encoded = preferences.getString("encrypted", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            val session = json.getJSONObject("session")
            StoredSession(json.getString("server"), json.getString("cookie"), Session(
                session.getString("accountId"), session.getString("username"),
                session.getString("role"), session.getString("csrfToken")))
        } catch (_: Exception) {
            // Keystore invalidation or corrupt data requires sign-in, never plaintext fallback.
            preferences.edit().clear().commit()
            null
        }
    }

    @Synchronized
    override fun write(value: StoredSession?) {
        if (value == null) {
            check(preferences.edit().clear().commit()) { "Could not clear the saved session" }
            return
        }
        val json = JSONObject().put("server", value.server).put("cookie", value.cookie)
            .put("session", JSONObject().put("accountId", value.session.accountId)
                .put("username", value.session.username).put("role", value.session.role)
                .put("csrfToken", value.session.csrfToken)).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Could not save the session securely"
        }
    }
}
