package dev.spofify.mobile.core

import kotlinx.serialization.Serializable

/** Only the server allocates queue keys, event IDs and execution identities. Runtime only. */
object LibraryPlaybackProtocol {
    const val SUBPROTOCOL = "spofify.library-playback.v2"
    const val MAX_SAFE_INTEGER = 9007199254740991L
    const val CLIENT_BYTES = 64 * 1024
    const val STATE_BYTES = 2 * 1024 * 1024
    const val INBOUND_BYTES = 16 * 1024 * 1024
    const val MAX_LEASE_MS = 8000L
}

@Serializable data class LibraryPlaybackArtist(val name: String, val id: String? = null)
@Serializable data class LibraryPlaybackContext(val kind: String, val id: String, val name: String, val revision: Long? = null, val fence: String? = null)
@Serializable data class LibraryPlaybackTrack(
    val id: String, val name: String, val artists: List<LibraryPlaybackArtist>,
    val albumName: String, val albumId: String, val duration: Double,
    val key: Long, val order: Long, val intent: String,
    val entryId: String? = null, val targetId: String? = null, val occurrenceId: String? = null,
    val playbackContext: LibraryPlaybackContext? = null,
)
@Serializable data class LibraryPlaybackOccurrence(val key: Long, val trackId: String, val eventId: String, val progress: Double, val reported: Boolean)
@Serializable data class LibraryPlaybackState(
    val version: Int, val queue: List<LibraryPlaybackTrack>, val position: Int,
    val context: LibraryPlaybackContext?, val shuffle: Boolean, val repeat: String,
    val desiredPlaying: Boolean, val highKeys: List<Long>, val elapsed: Double, val duration: Double,
    val isPlaying: Boolean, val isBuffering: Boolean, val isLoading: Boolean, val error: String,
    val truncated: Boolean, val notice: String, val occurrence: LibraryPlaybackOccurrence?,
)
@Serializable data class LibraryPlaybackStatus(
    val outputVersion: Long, val stateVersion: Long, val playbackSequence: Long,
    val eventId: String, val hasReportedPlayback: Boolean, val elapsed: Double, val duration: Double,
    val isPlaying: Boolean, val isBuffering: Boolean, val isLoading: Boolean, val error: String,
)
@Serializable data class LibraryPlaybackDevice(val deviceId: String, val name: String, val available: Boolean, val canPlay: Boolean)
@Serializable data class LibraryPlaybackTransfer(
    val sourceDeviceId: String, val targetDeviceId: String,
    val sourceConnectionId: String, val targetConnectionId: String, val phase: String,
)
@Serializable data class LibraryPlaybackSnapshot(
    val sessionId: String, val stateVersion: Long, val outputVersion: Long,
    val state: LibraryPlaybackState, val playback: LibraryPlaybackStatus,
    val devices: List<LibraryPlaybackDevice>, val selectedDeviceId: String, val selectedConnectionId: String,
    val transfer: LibraryPlaybackTransfer?, val drainUntilMs: Long,
)
@Serializable data class LibraryPlaybackLeaseTiming(val clientSequence: Long, val durationMs: Long)
@Serializable data class LibraryPlaybackLease(val outputVersion: Long, val eventId: String, val clientSequence: Long, val durationMs: Long)
@Serializable data class LibraryPlaybackNext(val eventId: String, val instructionId: String, val track: LibraryPlaybackTrack)
@Serializable data class LibraryPlaybackInstruction(
    val id: String, val type: String, val outputVersion: Long, val eventId: String,
    val elapsed: Double, val desiredPlaying: Boolean,
    val commandId: String? = null, val track: LibraryPlaybackTrack? = null,
    val lease: LibraryPlaybackLeaseTiming? = null, val planId: String? = null,
    val currentInstructionId: String? = null, val next: LibraryPlaybackNext? = null,
)
@Serializable data class LibraryPlaybackResult(
    val id: String, val outcome: String, val code: String, val stateVersion: Long,
    val acceptedCount: Int, val placement: String, val execution: String, val final: Boolean,
)
@Serializable data class LibraryPlaybackError(val code: String, val message: String)

/** A validated strict envelope. Nullable arms are absent, never explicit wire nulls. */
@Serializable data class LibraryPlaybackServerMessage(
    val type: String, val sessionId: String,
    val connectionId: String? = null, val deviceId: String? = null,
    val snapshot: LibraryPlaybackSnapshot? = null, val playback: LibraryPlaybackStatus? = null,
    val instruction: LibraryPlaybackInstruction? = null, val lease: LibraryPlaybackLease? = null,
    val result: LibraryPlaybackResult? = null, val error: LibraryPlaybackError? = null,
)

sealed interface LibraryPlaybackSelection {
    data class Track(val track: LibraryPlaybackTrackRef, val context: LibraryPlaybackContext? = null) : LibraryPlaybackSelection
    data class Collection(val context: LibraryPlaybackContext) : LibraryPlaybackSelection
    data class Occurrence(val key: Long) : LibraryPlaybackSelection
}
/** Signed selectionToken is ephemeral and deliberately excluded from generated toString. */
class LibraryPlaybackTrackRef(
    val id: String, val entryId: String? = null, val targetId: String? = null,
    val occurrenceId: String? = null, val selectionToken: String? = null,
) { override fun toString(): String = "LibraryPlaybackTrackRef(redacted)" }

sealed interface LibraryPlaybackCommand {
    data class QueueIntent(val intent: String, val selection: LibraryPlaybackSelection, val shuffle: Boolean? = null) : LibraryPlaybackCommand
    data class SetShuffle(val shuffle: Boolean) : LibraryPlaybackCommand
    data class SetRepeat(val repeat: String) : LibraryPlaybackCommand
    data class Seek(val value: Double) : LibraryPlaybackCommand
    data class Jump(val key: Long) : LibraryPlaybackCommand
    data class Remove(val key: Long) : LibraryPlaybackCommand
    data class Move(val key: Long, val afterKey: Long?) : LibraryPlaybackCommand
    data class Select(val deviceId: String) : LibraryPlaybackCommand
    data class Rename(val name: String, val deviceId: String? = null) : LibraryPlaybackCommand
    data object Pause : LibraryPlaybackCommand
    data object Resume : LibraryPlaybackCommand
    data object Next : LibraryPlaybackCommand
    data object Previous : LibraryPlaybackCommand
    data object Retry : LibraryPlaybackCommand
    data object Clear : LibraryPlaybackCommand
    data object Close : LibraryPlaybackCommand
    data object Deselect : LibraryPlaybackCommand
}

/** Counters belong to one transport, not callers; they cannot reset on output changes. */
sealed interface LibraryPlaybackClientMessage {
    data class Command(val command: LibraryPlaybackCommand, val expectedStateVersion: Long? = null) : LibraryPlaybackClientMessage
    data class Ack(val instructionId: String, val outputVersion: Long, val eventId: String, val status: String) : LibraryPlaybackClientMessage
    data class Report(
        val outputVersion: Long, val eventId: String, val instructionId: String,
        val elapsed: Double, val duration: Double, val isPlaying: Boolean, val isBuffering: Boolean,
        val isLoading: Boolean, val error: String, val ended: Boolean, val planId: String? = null,
    ) : LibraryPlaybackClientMessage
}

data class LibraryPlaybackSend(val sentAtNanos: Long, val commandId: String? = null, val clientSequence: Long? = null)
sealed interface LibraryPlaybackEvent {
    data class Message(val message: LibraryPlaybackServerMessage) : LibraryPlaybackEvent
    /** Deliberately excludes server close reason, raw frames and Throwable messages. */
    data class Closed(val reason: Reason) : LibraryPlaybackEvent
    enum class Reason { CLOSED, NETWORK, PROTOCOL, AUTHENTICATION, OFFLINE, CAPACITY }
}
