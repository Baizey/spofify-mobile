package dev.spofify.mobile.feature.devices

import android.content.Context
import android.content.SharedPreferences
import dev.spofify.mobile.EncryptedValueStore
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.validPlaybackDeviceName
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey
import org.json.JSONObject

/** Local metadata only. installationId is NEVER a player protocol deviceId or an authority grant. */
data class PlaybackDeviceIdentity(val installationId: String, val name: String) {
    init {
        require(UUID.fromString(installationId).toString() == installationId)
        require(validPlaybackDeviceName(name))
    }
}

/**
 * Construct only after a real authenticated session supplies its exact origin and account ID.
 * Retained across sign-out, scoped to that origin/account, encrypted and covered by app backup exclusion.
 * The server accepts register(name) only: every registration still receives a new ephemeral deviceId.
 * No cookie, credentials, queue, playback intent or server-issued device ID belongs in this store.
 */
class PlaybackDeviceIdentityStore internal constructor(
    preferences: SharedPreferences,
    keyProvider: () -> SecretKey,
    server: ServerAddress,
    accountId: String,
) {
    constructor(context: Context, server: ServerAddress, accountId: String) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        EncryptedValueStore.androidKeyStoreKey(KEY_ALIAS), server, accountId,
    )

    private val encrypted: EncryptedValueStore

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
        read() ?: PlaybackDeviceIdentity(UUID.randomUUID().toString(), defaultName).also(::write)
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
        PlaybackDeviceIdentity(json.get("installationId") as String, json.get("name") as String)
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
