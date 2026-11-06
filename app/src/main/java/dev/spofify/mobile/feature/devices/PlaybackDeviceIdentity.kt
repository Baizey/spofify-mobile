package dev.spofify.mobile.feature.devices

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import dev.spofify.mobile.core.validPlaybackInstallationId
import dev.spofify.mobile.EncryptedValueStore
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.validPlaybackDeviceName
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey
import org.json.JSONObject

/** Stable metadata, never a credential, receiver incarnation, generation or playback grant. */
data class PlaybackDeviceIdentity(val installationId: String, val name: String, val stableDeviceId: String) {
    init {
        require(UUID.fromString(installationId).toString() == installationId)
        require(validPlaybackDeviceName(name) && validPlaybackInstallationId(stableDeviceId))
    }
}

/**
 * Construct only after a real authenticated session supplies its exact origin and account ID.
 * Retained across sign-out, scoped to that origin/account, encrypted and covered by app backup exclusion.
 * Valid app-scoped Android ID is domain-separated and hashed with exact origin/account context.
 * The encrypted random installation token is the fallback, not receiver/session authority.
 * No raw Android ID, cookie, queue, playback intent or server-issued generation is stored.
 */
class PlaybackDeviceIdentityStore internal constructor(
    preferences: SharedPreferences,
    keyProvider: () -> SecretKey,
    server: ServerAddress,
    accountId: String,
    private val androidId: () -> String? = { null },
) {
    constructor(context: Context, server: ServerAddress, accountId: String) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        EncryptedValueStore.androidKeyStoreKey(KEY_ALIAS), server, accountId,
        { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) },
    )

    private val encrypted: EncryptedValueStore
    private val identityScope = "${server.origin.length}:${server.origin}:${accountId.length}:$accountId"

    init {
        require(accountId.isNotBlank() && accountId.length <= 1024 && accountId.none { it.isISOControl() })
        // Length-delimited fields prevent ambiguous origin/account combinations. Hashing also keeps
        // account/origin names out of preference keys; AEAD binds the ciphertext to the same scope.
        val binding = "$KEY_ALIAS:${server.origin.length}:${server.origin}:${accountId.length}:$accountId".toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(binding).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        encrypted = EncryptedValueStore(preferences, key, keyProvider, binding)
    }

    fun readOrCreate(defaultName: String = "Android phone"): PlaybackDeviceIdentity = synchronized(lock) {
        require(validPlaybackDeviceName(defaultName))
        read() ?: UUID.randomUUID().toString().let { PlaybackDeviceIdentity(it, defaultName, stableId(it)).also(::write) }
    }

    /** Call only for an accepted server view's own-device name, not an optimistic rename draft. */
    fun confirmName(name: String, defaultName: String = "Android phone"): PlaybackDeviceIdentity = synchronized(lock) {
        require(validPlaybackDeviceName(name))
        val current = readOrCreate(defaultName)
        if (current.name == name) current else current.copy(name = name).also(::write)
    }

    private fun read(): PlaybackDeviceIdentity? = encrypted.read { value ->
        val json = JSONObject(value)
        require(json.getInt("version") == 1)
        val installation = json.get("installationId") as String
        PlaybackDeviceIdentity(installation, json.get("name") as String, stableId(installation))
    }

    private fun stableId(fallback: String): String {
        val candidate = try { androidId()?.lowercase(java.util.Locale.ROOT) } catch (_: Exception) { null }
        val source = if (candidate != null && candidate.matches(Regex("[0-9a-f]{16}")) &&
            candidate !in setOf("9774d56d682e549c", "0000000000000000", "ffffffffffffffff")) "android:$candidate" else "random:$fallback"
        return MessageDigest.getInstance("SHA-256").digest(
            "spofify.android.playback.installation.v1:$identityScope:${source.length}:$source".toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun write(value: PlaybackDeviceIdentity) = encrypted.write(
        JSONObject().put("version", 1).put("installationId", value.installationId).put("name", value.name).toString(),
    )

    internal companion object {
        const val PREFERENCES = "playback_device_identity"
        const val KEY_ALIAS = "spofify.playback.identity.v1"
        // Shared between instances so simultaneous recreation cannot mint two installation tokens.
        private val lock = Any()
    }
}
