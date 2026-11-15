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
    val fence: String? = null,
)

/** Catalog row identity: entry/derived occurrence fields must be passed unchanged. */
@Serializable
data class PlaybackTrackReference(
    val id: String,
    val entryId: String? = null,
    val targetId: String? = null,
    val occurrenceId: String? = null,
    val selectionToken: String? = null,
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

@Serializable
data class PlaybackCommandResult(
    val id: String,
    val outcome: String,
    val code: String,
    val stateVersion: Long,
    val acceptedCount: Int,
    val placement: String,
    val execution: String,
    val final: Boolean,
    val undo: PlaybackUndoCapability? = null,
)

/** Runtime-only capability; never persist or log. */
@Serializable
data class PlaybackUndoCapability(val token: String, val expiresAtMs: Long)

/** The token is deliberately not exposed to presentation code. */
data class PlaybackUndo(val removedKey: Long, val remainingMs: Long)

enum class PlaybackConnectionState { DISCONNECTED, CONNECTING, READY, CLOSED }

data class PlaybackView(
    val connection: PlaybackConnectionState = PlaybackConnectionState.DISCONNECTED,
    val sessionId: String = "",
    val connectionId: String = "",
    val deviceId: String = "",
    val snapshot: PlaybackSnapshot? = null,
    val playback: PlaybackStatus? = null,
    val pendingCommands: Int = 0,
    val latestResult: PlaybackCommandResult? = null,
    val undo: PlaybackUndo? = null,
    val error: String? = null,
) {
    val current: PlaybackTrack? get() = snapshot?.state?.current
    val queue: List<PlaybackTrack> get() = snapshot?.state?.queue.orEmpty()
    val desiredPlaying: Boolean get() = snapshot?.state?.desiredPlaying == true
    val devices: List<PlaybackDevice> get() = snapshot?.devices.orEmpty()
    // Never use desiredPlaying, acceptance or an ACK as evidence of sound.
    val isPlaying: Boolean get() = playback?.hasReportedPlayback == true && playback.isPlaying
}
