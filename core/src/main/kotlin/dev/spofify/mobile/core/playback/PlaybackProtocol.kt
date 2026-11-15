package dev.spofify.mobile.core.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

internal const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
internal const val MAX_LEASE_MS = 8_000L
internal const val REPORT_INTERVAL_MS = 2_000L

internal val playbackJson = Json { encodeDefaults = false }

@Serializable
internal data class LeaseTiming(val clientSequence: Long, val durationMs: Long)

@Serializable
internal data class PlaybackLease(
    val outputVersion: Long,
    val eventId: String,
    val clientSequence: Long,
    val durationMs: Long,
)

@Serializable
internal data class NextExecution(val eventId: String, val instructionId: String, val track: PlaybackTrack)

@Serializable
internal data class PlaybackInstruction(
    val id: String,
    val type: String,
    val outputVersion: Long,
    val eventId: String,
    val elapsed: Double,
    val desiredPlaying: Boolean,
    val commandId: String? = null,
    val track: PlaybackTrack? = null,
    val lease: LeaseTiming? = null,
    val planId: String? = null,
    val currentInstructionId: String? = null,
    val next: NextExecution? = null,
) {
    fun binding() = AudioBinding(id, outputVersion, eventId, track?.id)
}

@Serializable
internal data class PlaybackProtocolError(val code: String, val message: String)

@Serializable
internal data class PlaybackServerMessage(
    val type: String,
    val sessionId: String,
    val connectionId: String? = null,
    val deviceId: String? = null,
    val snapshot: PlaybackSnapshot? = null,
    val playback: PlaybackStatus? = null,
    val instruction: PlaybackInstruction? = null,
    val lease: PlaybackLease? = null,
    val result: PlaybackCommandResult? = null,
    val error: PlaybackProtocolError? = null,
)

internal fun decodePlaybackMessage(text: String): PlaybackServerMessage {
    val root = playbackJson.parseToJsonElement(text) as? JsonObject ?: error("Object required")
    val variant = root["type"]?.jsonPrimitive ?: error("Type required")
    require(variant.isString)
    val fields = when (variant.content) {
        "snapshot" -> setOf("type", "sessionId", "connectionId", "deviceId", "snapshot")
        "state" -> setOf("type", "sessionId", "snapshot")
        "playback" -> setOf("type", "sessionId", "playback")
        "instruction" -> setOf("type", "sessionId", "instruction")
        "lease" -> setOf("type", "sessionId", "lease")
        "result" -> setOf("type", "sessionId", "result")
        "error" -> setOf("type", "sessionId", "error")
        else -> error("Unknown playback variant")
    }
    require(root.keys == fields)
    return playbackJson.decodeFromJsonElement(root)
}

internal fun safeCounter(value: Long) = value in 0..MAX_SAFE_INTEGER
internal fun safeSeconds(value: Double) = value.isFinite() && value >= 0
internal fun validMediaError(value: String) = value in setOf("", "blocked", "media")
internal fun validIdentifier(value: String, bytes: Int = 128) =
    value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= bytes &&
        value.codePoints().noneMatch { Character.isISOControl(it) || it in 0xD800..0xDFFF }

internal fun PlaybackStatus.validate() {
    require(safeCounter(outputVersion) && safeCounter(stateVersion) && safeCounter(playbackSequence))
    require(safeSeconds(elapsed) && safeSeconds(duration) && validMediaError(error))
}

internal fun PlaybackTrack.validate() {
    require(validIdentifier(id, 512) && key in 1..MAX_SAFE_INTEGER && safeCounter(order))
    require(safeSeconds(duration))
}

internal fun PlaybackSnapshot.validate() {
    require(validIdentifier(sessionId) && safeCounter(stateVersion) && safeCounter(outputVersion) && safeCounter(drainUntilMs))
    playback.validate()
    require(state.version == 1 && !state.truncated && state.repeat in setOf("off", "all", "one"))
    require(safeSeconds(state.elapsed) && safeSeconds(state.duration) && validMediaError(state.error))
    require(if (state.queue.isEmpty()) state.position == -1 else state.position in state.queue.indices)
    state.queue.forEach { it.validate() }
    require(state.queue.map { it.key }.toSet().size == state.queue.size)
    val current = state.current
    require(if (current == null) state.occurrence == null else state.occurrence?.let {
        it.key == current.key && it.trackId == current.id && validIdentifier(it.eventId) && safeSeconds(it.progress)
    } == true)
    require(devices.size <= 128 && devices.map { it.deviceId }.toSet().size == devices.size)
    require(devices.all { validIdentifier(it.deviceId) && it.name.isNotBlank() })
    require(transfer == null || transfer.phase in setOf("preparing", "releasing", "draining", "activating"))
}

internal fun PlaybackInstruction.validate() {
    require(validIdentifier(id) && safeCounter(outputVersion) && safeSeconds(elapsed))
    require(eventId.isEmpty() || validIdentifier(eventId))
    require(type in setOf("prepare", "release", "activate", "execute", "stageNext", "cancelNext"))
    val grant = type == "activate" || type == "execute"
    require(grant == (lease != null))
    lease?.let { require(it.clientSequence in 1..MAX_SAFE_INTEGER && it.durationMs in 1..MAX_LEASE_MS) }
    track?.let {
        it.validate()
        require(eventId.isNotEmpty())
    }
    if (type == "stageNext" || type == "cancelNext") {
        require(planId?.let { validIdentifier(it) } == true && currentInstructionId?.let { validIdentifier(it) } == true)
        require((type == "stageNext") == (next != null))
        next?.let {
            require(validIdentifier(it.eventId) && validIdentifier(it.instructionId))
            require(it.eventId != eventId && it.instructionId != currentInstructionId)
            it.track.validate()
        }
    } else {
        require(next == null && currentInstructionId == null)
        require(planId == null || type == "execute")
    }
}

internal fun fields(vararg values: Pair<String, Any?>): JsonObject = JsonObject(values.associate { (key, value) ->
    key to when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> error("Unsupported protocol field")
    }
})
