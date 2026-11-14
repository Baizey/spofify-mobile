package dev.spofify.mobile.storage

import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** JCE/legacy payload proof only, not Android Keystore or SharedPreferences proof. */
class AuthCiphertextTest {
    @Test fun decryptsStandardGcmVectorWithOriginalIvPlusCiphertextLayout() {
        // Public NIST GCM zero-key/zero-IV vector, no AAD, 16 zero plaintext bytes.
        val payload = "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(ByteArray(12) + payload)
        val decoded = AuthCiphertext.decrypt(encoded, SecretKeySpec(ByteArray(16), "AES"), null)
        assertArrayEquals(ByteArray(16), decoded.toByteArray(Charsets.UTF_8))
    }

    @Test fun rememberedNamespaceAadAndRawWhitespaceRemainCompatible() {
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val aad = "spofify.login.v1".toByteArray(Charsets.UTF_8)
        val plaintext = UUID.randomUUID().toString() + " \t"
        val iv = ByteArray(12) { it.toByte() }
        val legacy = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
        }
        val encoded = Base64.getEncoder().encodeToString(iv + legacy.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
        assertEquals(plaintext, AuthCiphertext.decrypt(encoded, key, aad))
        assertThrows(AEADBadTagException::class.java) { AuthCiphertext.decrypt(encoded, key, null) }
    }

    @Test fun encryptionUsesFreshIvAndTamperingFailsClosed() {
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val plaintext = UUID.randomUUID().toString()
        val first = AuthCiphertext.encrypt(plaintext, key, null)
        val second = AuthCiphertext.encrypt(plaintext, key, null)
        assertNotEquals(first, second)
        assertEquals(plaintext, AuthCiphertext.decrypt(first, key, null))
        val damaged = Base64.getDecoder().decode(first).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            AuthCiphertext.decrypt(Base64.getEncoder().encodeToString(damaged), key, null)
        }
        assertThrows(IllegalArgumentException::class.java) { AuthCiphertext.decrypt("invalid!", key, null) }
    }
}
