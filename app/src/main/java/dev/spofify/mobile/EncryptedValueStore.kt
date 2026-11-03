package dev.spofify.mobile

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only Base64(12-byte IV + GCM ciphertext/tag). Never falls back to plaintext. */
internal class EncryptedValueStore(
    private val preferences: SharedPreferences,
    private val valueKey: String,
    private val keyProvider: () -> SecretKey,
    associatedData: ByteArray? = null,
) {
    private val associatedData = associatedData?.copyOf()

    @Synchronized
    fun <T : Any> read(decode: (String) -> T): T? = try {
        val encoded = preferences.getString(valueKey, null)
        if (encoded == null) {
            null
        } else {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size >= IV_BYTES + TAG_BITS / 8)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
            associatedData?.let { cipher.updateAAD(it) }
            decode(String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8))
        }
    } catch (_: Exception) {
        // Invalidated keys, malformed preferences/JSON, and tampering all require a fresh login.
        // Do not clear unrelated settings or delete/regenerate a key shared with another instance.
        try { preferences.edit().remove(valueKey).commit() } catch (_: Exception) { /* Still fail closed. */ }
        null
    }

    @Synchronized
    fun write(value: String?) {
        if (value == null) {
            check(preferences.edit().remove(valueKey).commit()) { "Could not clear encrypted storage" }
            return
        }
        // Complete encryption before touching preferences, so crypto failures preserve the old value.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        require(cipher.iv.size == IV_BYTES) { "Unexpected encrypted storage IV size" }
        associatedData?.let { cipher.updateAAD(it) }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        check(preferences.edit().putString(valueKey, encoded).commit()) { "Could not save encrypted storage" }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val keyStoreLock = Any()

        fun androidKeyStoreKey(alias: String): () -> SecretKey = {
            // Multiple store instances must not race to replace a newly generated alias.
            synchronized(keyStoreLock) {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val existing = store.getKey(alias, null)
                if (existing != null) {
                    check(existing is SecretKey) { "Unexpected encrypted storage key type" }
                    existing
                } else {
                    check(!store.containsAlias(alias)) { "Encrypted storage key is unavailable" }
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build())
                    }.generateKey()
                }
            }
        }
    }
}
