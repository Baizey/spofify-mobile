package dev.spofify.mobile.core.playback

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackArtist(val name: String, val id: String? = null)

@Serializable
data class PlaybackContext(
    val kind: String,
    val id: String,
    val name: String,
    val revision: Long? = null,
)

/** Catalog row identity: entry/derived occurrence fields must be passed unchanged. */
@Serializable
data class PlaybackTrackReference(
    val id: String,
    val entryId: String? = null,
    val targetId: String? = null,
    val occurrenceId: String? = null,
)

@Serializable
data class PlaybackTrack(
    val id: String,
    val name: String,
    val artists: List<PlaybackArtist>,
    val albumName: String,
    val albumId: String,
    val duration: Double,
    val key: Long,
    val order: Long,
    val intent: String,
    val entryId: String? = null,
    val targetId: String? = null,
    val occurrenceId: String? = null,
    val playbackContext: PlaybackContext? = null,
)

@Serializable
data class PlaybackOccurrence(
    val key: Long,
    val trackId: String,
    val eventId: String,
    val progress: Double,
    val reported: Boolean,
)

@Serializable
data class PlaybackQueueState(
    val version: Int = 1,
    val queue: List<PlaybackTrack> = emptyList(),
    val position: Int = -1,
    val context: PlaybackContext? = null,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    val desiredPlaying: Boolean = false,
    val highKeys: List<Long> = emptyList(),
    val elapsed: Double = 0.0,
    val duration: Double = 0.0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLoading: Boolean = false,
    val error: String = "",
    val truncated: Boolean = false,
    val notice: String = "",
    val occurrence: PlaybackOccurrence? = null,
) {
    val current: PlaybackTrack? get() = queue.getOrNull(position)
}

@Serializable
data class PlaybackStatus(
    val outputVersion: Long,
    val stateVersion: Long,
    val playbackSequence: Long,
    val eventId: String,
    val hasReportedPlayback: Boolean,
    val elapsed: Double,
    val duration: Double,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isLoading: Boolean,
    val error: String,
)

@Serializable
data class PlaybackDevice(val deviceId: String, val name: String, val available: Boolean, val canPlay: Boolean)

@Serializable
data class PlaybackTransfer(
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val sourceConnectionId: String,
    val targetConnectionId: String,
    val phase: String,
)

@Serializable
data class PlaybackSnapshot(
    val sessionId: String,
    val stateVersion: Long,
    val outputVersion: Long,
    val state: PlaybackQueueState,
    val playback: PlaybackStatus,
    val devices: List<PlaybackDevice>,
    val selectedDeviceId: String,
    val selectedConnectionId: String,
    val transfer: PlaybackTransfer?,
    val drainUntilMs: Long,
)

enum class PlaybackConnectionState { DISCONNECTED, CONNECTING, READY, CLOSED }

data class PlaybackView(
    val connection: PlaybackConnectionState = PlaybackConnectionState.DISCONNECTED,
    val sessionId: String = "",
    val connectionId: String = "",
    val deviceId: String = "",
    val snapshot: PlaybackSnapshot? = null,
    val playback: PlaybackStatus? = null,
    val error: String? = null,
    /** Actual execution authority on this device, online or isolated. */
    val localOutput: Boolean = false,
    /** Independent local playback is available even during admission. */
    val offline: Boolean = false,
    val superseded: Boolean = false,
    /** Increases on each completed handshake, even if READY and loss coalesce for observers. */
    val established: Long = 0,
    val transferPending: Boolean = false,
    /** An existing on-device visit remains private after transport recovery. */
    val isolatedOutput: Boolean = false,
) {
    val current: PlaybackTrack? get() = snapshot?.state?.current
    val queue: List<PlaybackTrack> get() = snapshot?.state?.queue.orEmpty()
    val desiredPlaying: Boolean get() = snapshot?.state?.desiredPlaying == true
    /** Preparation remains cancellable; a terminal output failure makes the next action Play. */
    val wantsPause: Boolean get() = desiredPlaying && playback?.error.isNullOrEmpty()
    val devices: List<PlaybackDevice> get() = snapshot?.devices.orEmpty()
    // Never use desiredPlaying, acceptance or an ACK as evidence of sound.
    val isPlaying: Boolean get() = playback?.hasReportedPlayback == true && playback.isPlaying
}
