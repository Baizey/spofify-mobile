package dev.spofify.mobile

import android.content.Context
import android.content.SharedPreferences
import android.os.Parcelable
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.StoredSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Serializable
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val key: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val wrongKey: SecretKey = SecretKeySpec(ByteArray(32) { (it + 2).toByte() }, "AES")
    private val login = RememberedLogin("https://music.example", "listener-private-名前", "  pässword-private-秘密\n ")
    private val session = StoredSession("https://music.example", "spofify_session=private-session-token",
        Session("account-private", "listener-private-名前", "user", "private-csrf-token"))

    private fun preferences(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun sessions(prefs: SharedPreferences = preferences(EncryptedSessionStore.PREFERENCES), provider: () -> SecretKey = { key }) =
        EncryptedSessionStore(prefs, provider)
    private fun logins(prefs: SharedPreferences = preferences(EncryptedLoginStore.PREFERENCES), provider: () -> SecretKey = { key }) =
        EncryptedLoginStore(prefs, provider)
    private fun bytes(prefs: SharedPreferences, blobKey: String): ByteArray =
        Base64.decode(prefs.getString(blobKey, null)!!, Base64.NO_WRAP)
    private fun assertLogin(expected: RememberedLogin, actual: RememberedLogin?) {
        assertNotNull(actual)
        assertEquals(expected.server, actual!!.server)
        assertEquals(expected.username, actual.username)
        assertEquals(expected.password, actual.password)
    }

    @Before fun resetPreferences() {
        listOf(EncryptedSessionStore.PREFERENCES, EncryptedLoginStore.PREFERENCES, "shared-storage-test").forEach {
            assertTrue(preferences(it).edit().clear().commit())
        }
    }

    @Test fun loginRoundTripsExactlyAndOnlyTheLatestCredentialSetIsRemembered() {
        assertNull(logins().read())
        logins().write(login)
        assertLogin(login, logins().read()) // A fresh store instance can decrypt it.
        val replacement = RememberedLogin("https://other.example:8443", "other-user", "different-password")
        logins().write(replacement)
        assertLogin(replacement, logins().read())
        assertEquals(setOf(EncryptedLoginStore.BLOB_KEY), preferences(EncryptedLoginStore.PREFERENCES).all.keys)
    }

    @Test fun loginIsRedactedAndCannotAccidentallyUseStateSerialization() {
        val description = login.toString()
        assertTrue(description.contains("REDACTED"))
        listOf(login.server, login.username, login.password).forEach { assertFalse(description.contains(it)) }
        assertFalse(Serializable::class.java.isAssignableFrom(RememberedLogin::class.java))
        assertFalse(Parcelable::class.java.isAssignableFrom(RememberedLogin::class.java))
        assertTrue(RememberedLogin::class.java.declaredMethods.none { it.name == "copy" || it.name.startsWith("component") })
    }

    @Test fun rememberedLoginRequiresACanonicalCredentialFreeOrigin() {
        listOf("https://music.example/", "HTTPS://MUSIC.EXAMPLE", "https://user:password@music.example",
            "https://music.example/path", "https://music.example?token=secret").forEach { server ->
            assertThrows(IllegalArgumentException::class.java) { RememberedLogin(server, "listener", "password") }
        }
    }

    @Test fun bothStoresEncryptAllSensitiveFieldsWithRandomizedTwelveByteIvs() {
        val loginPreferences = preferences(EncryptedLoginStore.PREFERENCES)
        val sessionPreferences = preferences(EncryptedSessionStore.PREFERENCES)
        logins().write(login)
        sessions().write(session)
        val firstLogin = bytes(loginPreferences, EncryptedLoginStore.BLOB_KEY)
        val firstSession = bytes(sessionPreferences, EncryptedSessionStore.BLOB_KEY)
        logins().write(login)
        sessions().write(session)
        val secondLogin = bytes(loginPreferences, EncryptedLoginStore.BLOB_KEY)
        val secondSession = bytes(sessionPreferences, EncryptedSessionStore.BLOB_KEY)
        assertFalse(firstLogin.contentEquals(secondLogin))
        assertFalse(firstSession.contentEquals(secondSession))
        assertFalse(firstLogin.take(12) == secondLogin.take(12))
        assertFalse(firstSession.take(12) == secondSession.take(12))
        for (ciphertext in listOf(firstLogin, firstSession, secondLogin, secondSession)) {
            for (secret in listOf(login.server, login.username, login.password, session.cookie, session.session.csrfToken)) {
                assertFalse(containsBytes(ciphertext, secret.toByteArray(Charsets.UTF_8)))
            }
        }
        assertLogin(login, logins().read())
        assertEquals(session, sessions().read())
        // Real JVM AES-GCM verifies the wire layout and tag length, not a mocked cipher.
        val decoded = decrypt(secondLogin, EncryptedLoginStore.KEY_ALIAS.toByteArray(Charsets.UTF_8))
        assertEquals(login.password, JSONObject(decoded).getString("password"))
        assertEquals(secondLogin.size, 12 + decoded.toByteArray(Charsets.UTF_8).size + 16)
    }

    @Test fun existingSessionPreferencesAliasAndWireFormatRemainCompatible() {
        assertEquals("session", EncryptedSessionStore.PREFERENCES)
        assertEquals("encrypted", EncryptedSessionStore.BLOB_KEY)
        assertEquals("spofify.session.v1", EncryptedSessionStore.KEY_ALIAS)
        val legacyJson = JSONObject().put("server", session.server).put("cookie", session.cookie)
            .put("session", JSONObject().put("accountId", session.session.accountId)
                .put("username", session.session.username).put("role", session.session.role)
                .put("csrfToken", session.session.csrfToken)).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val legacyBlob = Base64.encodeToString(cipher.iv + cipher.doFinal(legacyJson.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val prefs = preferences("session")
        assertTrue(prefs.edit().putString("encrypted", legacyBlob).putString("unrelated", "keep").commit())
        assertEquals(session, sessions().read())
        sessions().write(session)
        val rewritten = JSONObject(decrypt(bytes(prefs, "encrypted")))
        assertEquals(session.server, rewritten.getString("server"))
        assertEquals(session.cookie, rewritten.getString("cookie"))
        assertEquals(session.session.csrfToken, rewritten.getJSONObject("session").getString("csrfToken"))
        assertEquals("keep", prefs.getString("unrelated", null))
    }

    @Test fun productionNamespacesKeysAndAliasesAreSeparate() {
        assertNotEquals(EncryptedSessionStore.PREFERENCES, EncryptedLoginStore.PREFERENCES)
        assertNotEquals(EncryptedSessionStore.BLOB_KEY, EncryptedLoginStore.BLOB_KEY)
        assertNotEquals(EncryptedSessionStore.KEY_ALIAS, EncryptedLoginStore.KEY_ALIAS)
    }

    @Test fun clearingEitherStoreLeavesTheOtherAndUnrelatedSettingsIntact() {
        // Sharing preferences deliberately makes a broad clear() fail this test too.
        val prefs = preferences("shared-storage-test")
        val sessionStore = sessions(prefs)
        val loginStore = logins(prefs)
        assertTrue(prefs.edit().putString("lastServer", login.server).putInt("unrelated", 42).commit())
        sessionStore.write(session)
        loginStore.write(login)
        sessionStore.write(null)
        assertNull(sessionStore.read())
        assertFalse(prefs.contains(EncryptedSessionStore.BLOB_KEY))
        assertLogin(login, loginStore.read())
        sessionStore.write(session)
        loginStore.write(null)
        assertNull(loginStore.read())
        assertFalse(prefs.contains(EncryptedLoginStore.BLOB_KEY))
        assertEquals(session, sessionStore.read())
        assertEquals(login.server, prefs.getString("lastServer", null))
        assertEquals(42, prefs.getInt("unrelated", 0))
    }

    @Test fun corruptedLoginAndWrongKeyFailClosedWithoutRemovingTheSession() {
        val prefs = preferences("shared-storage-test")
        sessions(prefs).write(session)
        logins(prefs).write(login)
        val ciphertext = bytes(prefs, EncryptedLoginStore.BLOB_KEY)
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        assertTrue(prefs.edit().putString(EncryptedLoginStore.BLOB_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit())
        assertNull(logins(prefs).read())
        assertFalse(prefs.contains(EncryptedLoginStore.BLOB_KEY))
        assertEquals(session, sessions(prefs).read())
        logins(prefs).write(login)
        assertNull(logins(prefs) { wrongKey }.read())
        assertFalse(prefs.contains(EncryptedLoginStore.BLOB_KEY))
        assertEquals(session, sessions(prefs).read())
    }

    @Test fun corruptedSessionAndWrongKeyFailClosedWithoutRemovingTheLogin() {
        val prefs = preferences("shared-storage-test")
        logins(prefs).write(login)
        sessions(prefs).write(session)
        val ciphertext = bytes(prefs, EncryptedSessionStore.BLOB_KEY)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        assertTrue(prefs.edit().putString(EncryptedSessionStore.BLOB_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit())
        assertNull(sessions(prefs).read())
        assertFalse(prefs.contains(EncryptedSessionStore.BLOB_KEY))
        assertLogin(login, logins(prefs).read())
        sessions(prefs).write(session)
        assertNull(sessions(prefs) { wrongKey }.read())
        assertFalse(prefs.contains(EncryptedSessionStore.BLOB_KEY))
        assertLogin(login, logins(prefs).read())
    }

    @Test fun malformedPreferenceTypesBase64TruncatedBlobsAndJsonAreRemovedIndividually() {
        val prefs = preferences("shared-storage-test")
        assertTrue(prefs.edit().putString("unrelated", "keep").commit())
        for (blobKey in listOf(EncryptedLoginStore.BLOB_KEY, EncryptedSessionStore.BLOB_KEY)) {
            for (malformed in listOf("%%% not base64 %%%", "", Base64.encodeToString(ByteArray(27), Base64.NO_WRAP))) {
                assertTrue(prefs.edit().putString(blobKey, malformed).commit())
                if (blobKey == EncryptedLoginStore.BLOB_KEY) assertNull(logins(prefs).read()) else assertNull(sessions(prefs).read())
                assertFalse(prefs.contains(blobKey))
            }
            assertTrue(prefs.edit().putInt(blobKey, 1).commit())
            if (blobKey == EncryptedLoginStore.BLOB_KEY) assertNull(logins(prefs).read()) else assertNull(sessions(prefs).read())
            assertFalse(prefs.contains(blobKey))
            val aad = if (blobKey == EncryptedLoginStore.BLOB_KEY) EncryptedLoginStore.KEY_ALIAS.toByteArray(Charsets.UTF_8) else null
            val encrypted = EncryptedValueStore(prefs, blobKey, { key }, aad)
            for (invalidJson in listOf("not-json", "{}", """{"server":"https://music.example","username":42,"password":true}""")) {
                encrypted.write(invalidJson)
                if (blobKey == EncryptedLoginStore.BLOB_KEY) assertNull(logins(prefs).read()) else assertNull(sessions(prefs).read())
                assertFalse(prefs.contains(blobKey))
            }
        }
        assertEquals("keep", prefs.getString("unrelated", null))
    }

    @Test fun loginCiphertextIsBoundToItsNamespaceEvenWithTheSameInjectedKey() {
        val prefs = preferences("shared-storage-test")
        logins(prefs).write(login)
        assertTrue(prefs.edit().putString(EncryptedSessionStore.BLOB_KEY, prefs.getString(EncryptedLoginStore.BLOB_KEY, null)).commit())
        assertNull(sessions(prefs).read())
        assertLogin(login, logins(prefs).read())
    }

    @Test fun keyFailureDuringWritePreservesPreviousCiphertextsWithoutFallbackOrKeyRegeneration() {
        val prefs = preferences("shared-storage-test")
        var failKey = false
        var calls = 0
        val provider = {
            calls++
            if (failKey) throw GeneralSecurityException("Key unavailable")
            key
        }
        val sessionStore = sessions(prefs, provider)
        val loginStore = logins(prefs, provider)
        sessionStore.write(session)
        loginStore.write(login)
        val before = prefs.all.toMap()
        failKey = true
        assertThrows(GeneralSecurityException::class.java) { sessionStore.write(session) }
        assertThrows(GeneralSecurityException::class.java) { loginStore.write(login) }
        assertEquals(4, calls) // No retry with a replacement key.
        assertEquals(before, prefs.all)
        failKey = false
        assertEquals(session, sessionStore.read())
        assertLogin(login, loginStore.read())
    }

    @Test fun failedCommitsAreSurfacedAndNeverProducePlaintextFallback() {
        val prefs = preferences("shared-storage-test")
        sessions(prefs).write(session)
        logins(prefs).write(login)
        val before = prefs.all.toMap()
        val failing = RejectingCommits(prefs)
        assertThrows(IllegalStateException::class.java) { sessions(failing).write(session) }
        assertThrows(IllegalStateException::class.java) { logins(failing).write(login) }
        assertThrows(IllegalStateException::class.java) { sessions(failing).write(null) }
        assertThrows(IllegalStateException::class.java) { logins(failing).write(null) }
        assertEquals(before, prefs.all)
        assertEquals(session, sessions(prefs).read())
        assertLogin(login, logins(prefs).read())
    }

    @Test fun unavailableSessionKeyReadPreservesCiphertextAndRetriesWithoutAReplacementKey() {
        val prefs = preferences("shared-storage-test")
        sessions(prefs).write(session)
        logins(prefs).write(login)
        val before = prefs.all.toMap()
        var calls = 0
        val error = assertThrows(java.io.IOException::class.java) {
            sessions(prefs) { calls++; throw GeneralSecurityException("private provider detail") }.read()
        }
        assertEquals(1, calls)
        assertFalse(error.toString().contains("private")); assertNull(error.cause)
        assertEquals(before, prefs.all)
        assertEquals(session, sessions(prefs).read())
        assertLogin(login, logins(prefs).read())
        // Remembered-password policy is separate; this task never auto-submits it.
        assertNull(logins(prefs) { throw GeneralSecurityException("Key unavailable") }.read())
        assertFalse(prefs.contains(EncryptedLoginStore.BLOB_KEY))
        assertEquals(session, sessions(prefs).read())
    }

    @Test fun negotiatedRollingMetadataRoundTripsInsideExistingEncryptedSessionNamespace() {
        val rolling = session.copy(sessionMode = "rolling-v1")
        sessions().write(rolling)
        assertEquals(rolling, sessions().read())
        val prefs = preferences(EncryptedSessionStore.PREFERENCES)
        assertEquals(setOf(EncryptedSessionStore.BLOB_KEY), prefs.all.keys)
        val json = JSONObject(decrypt(bytes(prefs, EncryptedSessionStore.BLOB_KEY)))
        assertEquals("rolling-v1", json.getString("sessionMode"))
        assertEquals(session.cookie, json.getString("cookie"))
        assertEquals(session.session.csrfToken, json.getJSONObject("session").getString("csrfToken"))
    }

    private fun decrypt(bytes: ByteArray, aad: ByteArray? = null): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        aad?.let { cipher.updateAAD(it) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }

    private class RejectingCommits(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }
                override fun commit(): Boolean = false
            }
        }
    }
}
