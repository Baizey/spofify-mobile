package dev.spofify.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

fun validPlaybackInstallationId(value: String) = value.length in 32..64 && value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' }

@Serializable data class PlaybackRegistrationDevice(val deviceId: String, val receiverId: String, val registrationGeneration: String)
@Serializable data class PlaybackRegistrationView(val version: Int, val generation: String, val admissionToken: String, val devices: List<PlaybackRegistrationDevice>) {
    internal fun validated(view: PlaybackDevicesView) = apply {
        require(version == 1 && validPlaybackProtocolId(generation) && validPlaybackProtocolId(admissionToken))
        require(devices.size <= 16 && devices.map { it.deviceId }.distinct().size == devices.size)
        require(devices.all { validPlaybackInstallationId(it.deviceId) && validPlaybackProtocolId(it.receiverId) && validPlaybackProtocolId(it.registrationGeneration) && view.devices.any { d -> d.deviceId == it.deviceId } })
    }
    override fun toString() = "PlaybackRegistrationView(version=$version, admission=<redacted>)"
}

/** One enrollment operation. Retained only in memory for an exact lost-reply lookup. */
internal data class PlaybackRegistrationAttempt(val deviceId: String, val receiverId: String, val attemptId: String,
    val expectedGeneration: String, val admissionToken: String) {
    fun toJson() = buildJsonObject {
        put("deviceId", deviceId); put("receiverId", receiverId); put("attemptId", attemptId)
        put("expectedGeneration", expectedGeneration); put("admissionToken", admissionToken)
    }
    override fun toString() = "PlaybackRegistrationAttempt(<redacted>)"
}
