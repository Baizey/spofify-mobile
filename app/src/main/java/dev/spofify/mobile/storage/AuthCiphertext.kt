package dev.spofify.mobile.storage

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Installed auth payload format only; Keystore ownership remains in the platform adapter. */
internal object AuthCiphertext {
    fun decrypt(encoded: String, key: SecretKey, aad: ByteArray?): String {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString(throwOnInvalidSequence = true)
    }

    fun encrypt(value: String, key: SecretKey, aad: ByteArray?): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        aad?.let(cipher::updateAAD)
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(bytes)
    }
}
