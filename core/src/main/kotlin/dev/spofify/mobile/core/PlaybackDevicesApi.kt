package dev.spofify.mobile.core

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val MAX_SAFE = 9007199254740991L
internal const val MAX_PLAYBACK_BODY_BYTES = 2 * 1024 * 1024
// Maximum escaped context/history growth + poll identity/sequence and 16 maximum ACKs.
// PlaybackPublicationTest proves this reserve; exact API body-size enforcement remains below.
internal const val PLAYBACK_PUBLICATION_RESERVE_BYTES = 16 * 1024
private val playbackJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

@Serializable data class PlaybackArtist(val id: String = "", val name: String)
@Serializable data class PlaybackContext(val kind: String, val id: String, val name: String, val revision: Long = 0, val fence: String = "")
/** Positive wire DTO: deliberately cannot contain a stream URL or credential. order is the unshuffled baseline. */
@Serializable data class PlaybackTrack(
    val id: String, val name: String, val artists: List<PlaybackArtist>, val albumName: String,
    val albumId: String, val duration: Double, val entryId: String = "", val targetId: String = "",
    val occurrenceId: String = "", val playbackContext: PlaybackContext? = null, val key: Long, val order: Long,
)
@Serializable data class PlaybackOccurrence(val key: Long, val trackId: String, val eventId: String, val progress: Double, val reported: Boolean)
@Serializable data class PlaybackSnapshot(
    val version: Int = 1, val queue: List<PlaybackTrack> = emptyList(), val position: Int = -1,
    val context: PlaybackContext? = null, val shuffle: Boolean = false, val repeat: String = "off",
    val elapsed: Double = 0.0, val duration: Double = 0.0, val playing: Boolean = false,
    val waiting: Boolean = false, val loading: Boolean = false, val error: String = "",
    val truncated: Boolean = false, val notice: String = "", val occurrence: PlaybackOccurrence? = null,
) {
    fun paused() = copy(playing = false, waiting = false, loading = false)
    fun validated(): PlaybackSnapshot = apply {
        require(version == 1 && queue.size <= 1000 && position >= -1 && position < queue.size && (queue.isEmpty() || position >= 0))
        require(repeat in setOf("off", "all", "one") && error in setOf("", "blocked", "media", "queue"))
        require(notice in setOf("", "PLAYBACK_LIMIT", "CLICKED_OCCURRENCE_ONLY"))
        require(number(elapsed) && number(duration)); context?.validate()
        require(queue.map { it.key }.distinct().size == queue.size && queue.map { it.order }.distinct().size == queue.size)
        queue.forEach { it.validate() }
        occurrence?.let {
            require(position >= 0 && it.key == queue[position].key && it.trackId == queue[position].id)
            require(it.eventId.isNotEmpty() && it.eventId.length <= 128 && it.eventId.all { c -> c.code in 33..126 } && number(it.progress))
        }
    }
    /** Wire-valid input may exceed the outbound player-body limit. Reject it BEFORE owning
     * output, not after installing/starting a queue and discovering that its ACK cannot fit. */
    fun publicationValidated(): PlaybackSnapshot = validated().apply {
        require(toJson().toString().toByteArray(Charsets.UTF_8).size <= MAX_PLAYBACK_BODY_BYTES - PLAYBACK_PUBLICATION_RESERVE_BYTES) {
            "Prepared playback state exceeds the publication limit"
        }
    }
    fun toJson(): JsonObject = playbackJson.encodeToJsonElement(serializer(), validated()).jsonObject
    companion object {
        fun fromJson(value: JsonObject): PlaybackSnapshot {
            requireSnapshotShape(value)
            return playbackJson.decodeFromJsonElement(serializer(), value).validated()
        }
    }
}
private fun number(n: Double) = n.isFinite() && n in 0.0..604800.0
private fun text(s: String, max: Int): Boolean {
    if (s.toByteArray(Charsets.UTF_8).size > max || s.any(Character::isISOControl)) return false
    var i = 0
    while (i < s.length) {
        val c = s[i++]
        if (Character.isHighSurrogate(c)) { if (i >= s.length || !Character.isLowSurrogate(s[i++])) return false }
        else if (Character.isLowSurrogate(c)) return false
    }
    return true
}
private fun requireSnapshotShape(value: JsonObject) {
    requireWireTypes(value)
    require(value.keys.containsAll(setOf("version", "queue", "position", "context", "shuffle", "repeat", "elapsed", "duration", "playing", "waiting", "error", "truncated", "notice", "occurrence")))
    value.getValue("queue").jsonArray.forEach { require(it.jsonObject.keys.containsAll(setOf("id", "name", "artists", "albumName", "albumId", "duration", "key", "order"))) }
}
private fun libraryId(s: String) = s.isNotEmpty() && s.trim() == s && text(s, 512)
private fun optionalId(s: String) = s.isEmpty() || libraryId(s)
private val wireNumbers = setOf("version", "position", "elapsed", "duration", "key", "order", "revision", "progress", "epoch", "leaseMs", "quarantineMs", "stateSeq", "stateAgeMs", "value")
private val wireBooleans = setOf("shuffle", "playing", "waiting", "loading", "truncated", "reported", "available", "first")
private val wireStrings = setOf("id", "name", "albumName", "albumId", "entryId", "targetId", "occurrenceId", "selectionToken", "kind", "fence", "repeat", "error", "notice", "trackId", "eventId", "deviceId", "activeDeviceId", "commandId", "sourceDeviceId", "targetDeviceId", "phase", "deliveryId", "type", "status", "direction")
private fun requireWireTypes(value: JsonElement, depth: Int = 0) {
    require(depth <= 16)
    when (value) {
        is JsonObject -> value.forEach { (key, child) ->
            when (key) {
                in wireNumbers -> require(child is JsonPrimitive && !child.isString && child.doubleOrNull?.isFinite() == true)
                in wireBooleans -> require(child is JsonPrimitive && !child.isString && child.booleanOrNull != null)
                in wireStrings -> require(child is JsonPrimitive && child.isString)
            }
            if (child is JsonObject || child is JsonArray) requireWireTypes(child, depth + 1)
        }
        is JsonArray -> value.forEach { requireWireTypes(it, depth + 1) }
        else -> Unit
    }
}
fun validPlaybackProtocolId(s: String) = s.matches(Regex("[A-Za-z0-9_-]{1,128}"))
fun validPlaybackDeviceName(s: String) = s.isNotBlank() && s.codePointCount(0, s.length) <= 64 && text(s, 256)
private fun PlaybackContext.validate() {
    require(kind in setOf("album", "artist", "playlist", "likes", "derived") && libraryId(id) && text(name, 512) && revision in 0..MAX_SAFE && text(fence, 4096))
    if (kind == "derived") require(validPlaybackProtocolId(id) && revision > 0 && fence.isNotEmpty() && fence.all { it.code in 33..126 })
}
private fun PlaybackTrack.validate() {
    require(libraryId(id) && text(name, 512) && text(albumName, 512) && optionalId(albumId) && number(duration))
    require(artists.size <= 64 && artists.all { optionalId(it.id) && text(it.name, 512) })
    require(optionalId(entryId) && optionalId(targetId) && text(occurrenceId, 4096) && key in 1..MAX_SAFE && order in 0..MAX_SAFE)
    playbackContext?.validate()
}

/** Native outgoing transport is explicit intent. Decode legacy toggle only for owner-side resolution. */
@ConsistentCopyVisibility
data class PlaybackDeviceCommand private constructor(val json: JsonObject) {
    val type: String get() = json.getValue("type").jsonPrimitive.content
    // Musical commands may carry ephemeral signed selection authority. Serialize only via
    // the intentional wire json boundary, never via a generated diagnostic data-class string.
    override fun toString(): String = "PlaybackDeviceCommand(type=$type, payload=<redacted>)"
    companion object {
        fun transport(type: String): PlaybackDeviceCommand {
            require(type in setOf("pause", "resume", "next", "previous", "retry", "shuffle", "repeat", "close", "clear"))
            return PlaybackDeviceCommand(buildJsonObject { put("type", type) })
        }
        fun seek(seconds: Double): PlaybackDeviceCommand {
            require(number(seconds)); return PlaybackDeviceCommand(buildJsonObject { put("type", "seek"); put("value", seconds) })
        }
        fun queue(type: String, key: Long, direction: String? = null): PlaybackDeviceCommand {
            require(type in setOf("jump", "remove", "move") && key in 1..MAX_SAFE)
            require(if (type == "move") direction in setOf("up", "down", "next") else direction == null)
            return PlaybackDeviceCommand(buildJsonObject { put("type", type); put("key", key); direction?.let { put("direction", it) } })
        }
        /** Server-validated musical selection authority remains explicit; prepared snapshots never contain it. */
        fun musical(type: String, track: JsonObject? = null, context: PlaybackContext? = null, first: Boolean? = null,
            shuffle: Boolean? = null, trackId: String? = null, entryId: String? = null,
            occurrenceId: String? = null, selectionToken: String? = null): PlaybackDeviceCommand {
            require(type in setOf("play", "playCollection", "enqueue", "enqueueContext"))
            require((track != null) == (type == "play" || type == "enqueue"))
            require((first != null) == type.startsWith("enqueue"))
            if (type == "enqueue") require(context == null && shuffle == null && trackId == null && entryId == null && occurrenceId == null && selectionToken == null)
            if (type == "enqueueContext") require(shuffle == null && trackId == null && entryId == null && occurrenceId == null && selectionToken == null)
            if (type == "playCollection" || type == "enqueueContext") require(context != null)
            context?.validate()
            track?.let {
                val allowed = setOf("id", "name", "artists", "albumName", "albumId", "duration", "entryId", "targetId", "occurrenceId", "selectionToken", "playbackContext", "key", "order")
                require(it.keys.all(allowed::contains))
                val withoutToken = JsonObject(it.filterKeys { key -> key != "selectionToken" } + mapOf("key" to JsonPrimitive(1), "order" to JsonPrimitive(0)))
                playbackJson.decodeFromJsonElement(PlaybackTrack.serializer(), withoutToken).validate()
            }
            require(trackId == null || libraryId(trackId)); require(entryId == null || libraryId(entryId))
            require(occurrenceId == null || text(occurrenceId, 4096)); require(selectionToken == null || text(selectionToken, 8192))
            return PlaybackDeviceCommand(buildJsonObject {
                put("type", type); track?.let { put("track", it) }; context?.let { put("context", playbackJson.encodeToJsonElement(PlaybackContext.serializer(), it)) }
                first?.let { put("first", it) }; shuffle?.let { require(type == "playCollection"); put("shuffle", it) }
                trackId?.let { require(type == "playCollection"); put("trackId", it) }; entryId?.let { put("entryId", it) }
                occurrenceId?.let { put("occurrenceId", it) }; selectionToken?.let { put("selectionToken", it) }
            })
        }
        fun fromJson(value: JsonObject): PlaybackDeviceCommand {
            requireWireTypes(value)
            val type = value["type"]?.jsonPrimitive?.content ?: error("Missing command type")
            val result = when (type) {
                // Shipped web clients still send toggle. It is incoming-only: transport("toggle")
                // stays forbidden so native controls never toggle against a stale remote display.
                "toggle" -> PlaybackDeviceCommand(buildJsonObject { put("type", "toggle") })
                "seek" -> seek(value.getValue("value").jsonPrimitive.double)
                "jump", "remove", "move" -> queue(type, value.getValue("key").jsonPrimitive.long, value["direction"]?.jsonPrimitive?.content)
                "play", "playCollection", "enqueue", "enqueueContext" -> musical(type, value["track"]?.jsonObject,
                    value["context"]?.takeUnless { it is JsonNull }?.let { playbackJson.decodeFromJsonElement(PlaybackContext.serializer(), it) },
                    value["first"]?.jsonPrimitive?.boolean, value["shuffle"]?.jsonPrimitive?.boolean,
                    value["trackId"]?.jsonPrimitive?.content, value["entryId"]?.jsonPrimitive?.content,
                    value["occurrenceId"]?.jsonPrimitive?.content, value["selectionToken"]?.jsonPrimitive?.content)
                else -> transport(type)
            }
            // Compare allowed/required field NAMES, not re-encoded numbers or omitted context defaults.
            // Go emits 10 where Kotlin encodes 10.0, and omits zero revision/fence.
            require(result.json.keys == value.keys)
            return PlaybackDeviceCommand(value)
        }
    }
}
@Serializable data class PlaybackDevice(val deviceId: String, val name: String, val available: Boolean)
@Serializable data class PlaybackTransfer(val commandId: String, val sourceDeviceId: String, val targetDeviceId: String, val phase: String)
@Serializable data class PlaybackOutcome(val commandId: String, val status: String)
@Serializable data class PlaybackDirective(val deliveryId: String, val epoch: Long, val type: String, val commandId: String = "", val command: JsonObject? = null, val state: PlaybackSnapshot? = null)
@Serializable data class PlaybackAck(val deliveryId: String, val epoch: Long, val status: String)
@Serializable data class PlaybackDevicesView(
    val deviceId: String = "", val devices: List<PlaybackDevice>, val activeDeviceId: String, val epoch: Long,
    val leaseMs: Long, val quarantineMs: Long = 0, val state: PlaybackSnapshot?, val stateSeq: Long,
    val stateAgeMs: Long, val transfer: PlaybackTransfer?, val directives: List<PlaybackDirective>, val commands: List<PlaybackOutcome>, val error: String = "",
) {
    fun validated() = apply {
        require(epoch in 1..MAX_SAFE && stateSeq in 0..MAX_SAFE && leaseMs in 0..8000 && quarantineMs in 0..8000 && stateAgeMs >= 0)
        require(deviceId.isEmpty() || validPlaybackProtocolId(deviceId))
        require(devices.size <= 16 && devices.map { it.deviceId }.distinct().size == devices.size)
        require(devices.all { validPlaybackProtocolId(it.deviceId) && validPlaybackDeviceName(it.name) })
        require(activeDeviceId.isEmpty() || devices.any { it.deviceId == activeDeviceId })
        state?.validated(); require(directives.size <= 32 && commands.size <= 128)
        transfer?.let { require(validPlaybackProtocolId(it.commandId) && validPlaybackProtocolId(it.targetDeviceId) && (it.sourceDeviceId.isEmpty() || validPlaybackProtocolId(it.sourceDeviceId)) && it.phase in setOf("waiting", "preparing", "releasing", "activating")) }
        directives.forEach { require(validPlaybackProtocolId(it.deliveryId) && it.epoch == epoch && it.type in setOf("prepare", "release", "activate", "command")); it.state?.validated(); it.command?.let(PlaybackDeviceCommand::fromJson) }
        commands.forEach { require(validPlaybackProtocolId(it.commandId) && it.status in setOf("pending", "ok", "blocked", "media", "rejected", "timeout", "cancelled")) }
    }
    fun toJson(): JsonObject = playbackJson.encodeToJsonElement(serializer(), validated()).jsonObject
    companion object {
        fun fromJson(value: JsonObject): PlaybackDevicesView {
            requireWireTypes(value)
            value["state"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::requireSnapshotShape)
            value["directives"]?.jsonArray?.forEach { directive ->
                directive.jsonObject["state"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::requireSnapshotShape)
            }
            return playbackJson.decodeFromJsonElement(serializer(), value).validated()
        }
    }
}

/** Injectable protocol boundary for deterministic service tests. No write is ever automatically retried. */
interface PlaybackDevicesProtocol {
    suspend fun view(): PlaybackDevicesView
    suspend fun register(name: String): PlaybackDevicesView
    suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long? = null, state: PlaybackSnapshot? = null, acks: List<PlaybackAck> = emptyList()): PlaybackDevicesView
    suspend fun rename(deviceId: String, name: String): PlaybackDevicesView
    suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView
    suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView
    suspend fun leave(deviceId: String)
}
class PlaybackDevicesApi(client: SpofifyClient) : PlaybackDevicesProtocol {
    private val transport = ApiTransport(client)
    private val path = listOf("api", "me", "player")
    private suspend fun post(action: String, fields: JsonObjectBuilder.() -> Unit): PlaybackDevicesView = withTimeout(4000) {
        val body = buildJsonObject { put("action", action); fields() }
        require(body.toString().toByteArray(Charsets.UTF_8).size <= MAX_PLAYBACK_BODY_BYTES) { "Queue is too large to share" }
        PlaybackDevicesView.fromJson(requireNotNull(transport.send("POST", path, body)))
    }
    override suspend fun view() = withTimeout(4000) { PlaybackDevicesView.fromJson(transport.get(path)) }
    override suspend fun register(name: String): PlaybackDevicesView { require(validPlaybackDeviceName(name)); return post("register") { put("name", name) } }
    override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
        require((state == null) == (stateSeq == null) && acks.size <= 16)
        require(stateSeq == null || stateSeq in 1..MAX_SAFE)
        acks.forEach { require(validPlaybackProtocolId(it.deliveryId) && it.epoch in 1..MAX_SAFE && it.status in setOf("ok", "blocked", "media", "rejected")) }
        return post("poll") { identity(deviceId, epoch); state?.let { put("state", it.toJson()); put("stateSeq", stateSeq!!) }; put("acks", playbackJson.encodeToJsonElement(acks)) }
    }
    override suspend fun rename(deviceId: String, name: String): PlaybackDevicesView { require(validPlaybackProtocolId(deviceId) && validPlaybackDeviceName(name)); return post("rename") { put("deviceId", deviceId); put("name", name) } }
    override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView { require(validPlaybackProtocolId(commandId)); return post("command") { identity(deviceId, epoch); put("commandId", commandId); put("command", command.json) } }
    override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
        require(validPlaybackProtocolId(commandId) && validPlaybackProtocolId(targetDeviceId) && selectionSeq in 1..MAX_SAFE)
        return post("transfer") { identity(deviceId, epoch); put("commandId", commandId); put("targetDeviceId", targetDeviceId); put("selectionSeq", selectionSeq) }
    }
    override suspend fun leave(deviceId: String) { require(validPlaybackProtocolId(deviceId)); post("leave") { put("deviceId", deviceId) } }
    private fun JsonObjectBuilder.identity(id: String, epoch: Long) { require(validPlaybackProtocolId(id) && epoch in 1..MAX_SAFE); put("deviceId", id); put("epoch", epoch) }
}
