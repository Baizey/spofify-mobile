@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.spofify.mobile.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** Durable preference only. Never a receiver grant, lease, ACK or permission to import state. */
@Serializable data class PlaybackLeadership(
    val version: Int, val revision: Long, val state: String, val deviceId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val acceptedRevision: Long? = null,
) {
    fun validated() = apply {
        require(version == 1 && revision in 0..9007199254740991L)
        require(state in setOf("unset", "installation", "legacy", "cleared"))
        require(if (state == "installation") validPlaybackInstallationId(deviceId) else deviceId.isEmpty())
        require(acceptedRevision == null || acceptedRevision in 1..revision)
    }
    fun names(device: String) = state == "installation" && deviceId == device
}
