package dev.spofify.mobile.feature.player

import dev.spofify.mobile.feature.devices.PlaybackDevicesState

/** Read-only observed output, never the pending selection or a claim of local playback. */
internal fun selectedOutputLabel(state: PlaybackDevicesState?): String {
    if (state == null) return "Outputs unavailable"
    val active = state.selectedDeviceId
    if (active.isBlank()) return when {
        state.connecting -> "Loading outputs"
        state.snapshot == null && !state.connected -> "Outputs unavailable"
        else -> "No output selected"
    }
    val device = state.devices.firstOrNull { it.deviceId == active }
    val name = device?.name?.takeIf { it.isNotBlank() } ?: "Selected output"
    val location = if (active == state.deviceId) "This phone · $name" else name
    return if (device?.available != true || !state.connected) "$location · Unavailable" else location
}
