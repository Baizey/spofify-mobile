package dev.spofify.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val LOCAL_MAX_SAFE = 9007199254740991L
private val localPlaybackJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

/** Local musical presentation only. These types confer no online output or selection authority. */
@Serializable data class PlaybackArtist(val id: String = "", val name: String)
@Serializable data class PlaybackContext(val kind: String, val id: String, val name: String, val revision: Long = 0, val fence: String = "")
/** Positive local metadata: no stream URL, credential or signed selection token. */
@Serializable data class PlaybackTrack(
    val id: String, val name: String, val artists: List<PlaybackArtist>, val albumName: String,
    val albumId: String, val duration: Double, val entryId: String = "", val targetId: String = "",
    val occurrenceId: String = "", val playbackContext: PlaybackContext? = null, val key: Long, val order: Long,
)
@Serializable data class PlaybackOccurrence(val key: Long, val trackId: String, val eventId: String, val progress: Double, val reported: Boolean)

/** Local/offline presentation and observation, never a socket payload or an uploadable queue. */
@Serializable data class PlaybackSnapshot(
    val version: Int = 1, val queue: List<PlaybackTrack> = emptyList(), val position: Int = -1,
    val context: PlaybackContext? = null, val shuffle: Boolean = false, val repeat: String = "off",
    val elapsed: Double = 0.0, val duration: Double = 0.0, val playing: Boolean = false,
    val waiting: Boolean = false, val loading: Boolean = false, val error: String = "",
    val truncated: Boolean = false, val notice: String = "", val occurrence: PlaybackOccurrence? = null,
) {
    fun paused() = copy(playing = false, waiting = false, loading = false)
    fun validated(): PlaybackSnapshot = apply {
        require(version == 1 && position >= -1 && position < queue.size && (queue.isEmpty() || position >= 0))
        require(repeat in setOf("off", "all", "one") && error in setOf("", "blocked", "media", "queue"))
        require(notice in setOf("", "PLAYBACK_LIMIT", "CLICKED_OCCURRENCE_ONLY"))
        require(localNumber(elapsed) && localNumber(duration)); context?.validateLocal()
        require(queue.map { it.key }.distinct().size == queue.size && queue.map { it.order }.distinct().size == queue.size)
        queue.forEach { it.validateLocal() }
        occurrence?.let {
            require(position >= 0 && it.key == queue[position].key && it.trackId == queue[position].id)
            require(it.eventId.isNotEmpty() && it.eventId.length <= 128 && it.eventId.all { c -> c.code in 33..126 } && localNumber(it.progress))
        }
    }

    /** Safe local serialization; deliberately has no online publication/envelope admission. */
    fun toJson(): JsonObject = localPlaybackJson.encodeToJsonElement(serializer(), validated()).jsonObject
    companion object {
        fun fromJson(value: JsonObject): PlaybackSnapshot {
            requireLocalTypes(value)
            require(value.keys.containsAll(setOf("version", "queue", "position", "context", "shuffle", "repeat", "elapsed", "duration", "playing", "waiting", "error", "truncated", "notice", "occurrence")))
            value.getValue("queue").jsonArray.forEach {
                require(it.jsonObject.keys.containsAll(setOf("id", "name", "artists", "albumName", "albumId", "duration", "key", "order")))
            }
            return localPlaybackJson.decodeFromJsonElement(serializer(), value).validated()
        }
    }
}

/** Shared musical action vocabulary, not a receiver mutation protocol. */
@Serializable enum class QueueIntentIntent {
    @SerialName("low") LOW,
    @SerialName("high") HIGH,
    @SerialName("next") NEXT,
    @SerialName("now") NOW;
    val wire: String get() = name.lowercase(java.util.Locale.ROOT)
    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown queue intent")
    }
}

/** Process-local service controls only. No decoder, online musical selections or receiver binding. */
@ConsistentCopyVisibility
data class LocalPlaybackCommand private constructor(val json: JsonObject) {
    val type: String get() = json.getValue("type").jsonPrimitive.content
    companion object {
        fun transport(type: String): LocalPlaybackCommand {
            require(type in setOf("pause", "resume", "next", "previous", "retry", "shuffle", "repeat", "close", "clear"))
            return LocalPlaybackCommand(buildJsonObject { put("type", type) })
        }
        fun seek(seconds: Double): LocalPlaybackCommand {
            require(localNumber(seconds))
            return LocalPlaybackCommand(buildJsonObject { put("type", "seek"); put("value", seconds) })
        }
        fun queue(type: String, key: Long, direction: String? = null): LocalPlaybackCommand {
            require(type in setOf("jump", "remove", "move") && key in 1..LOCAL_MAX_SAFE)
            require(if (type == "move") direction in setOf("up", "down", "next") else direction == null)
            return LocalPlaybackCommand(buildJsonObject {
                put("type", type); put("key", key); direction?.let { put("direction", it) }
            })
        }
    }
}

/** Local secure identity metadata validator; installation identity is not registration authority. */
fun validPlaybackInstallationId(value: String) = value.length in 32..64 && value.length % 2 == 0 &&
    value.all { it in '0'..'9' || it in 'a'..'f' }
fun validPlaybackDeviceName(value: String) = value.isNotBlank() && value.codePointCount(0, value.length) <= 64 && localText(value, 256)

private fun localNumber(value: Double) = value.isFinite() && value in 0.0..604800.0
private fun localText(value: String, max: Int): Boolean {
    if (value.toByteArray(Charsets.UTF_8).size > max || value.any(Character::isISOControl)) return false
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (Character.isHighSurrogate(c)) {
            if (i >= value.length || !Character.isLowSurrogate(value[i++])) return false
        } else if (Character.isLowSurrogate(c)) return false
    }
    return true
}
private fun localId(value: String) = value.isNotEmpty() && value.trim() == value && localText(value, 512)
private fun optionalLocalId(value: String) = value.isEmpty() || localId(value)
private fun PlaybackContext.validateLocal() {
    require(kind in setOf("album", "artist", "playlist", "likes", "derived") && localId(id) && localText(name, 512) &&
        revision in 0..LOCAL_MAX_SAFE && localText(fence, 4096))
    if (kind == "derived") require(revision > 0 && fence.isNotEmpty() && fence.all { it.code in 33..126 })
}
private fun PlaybackTrack.validateLocal() {
    require(localId(id) && localText(name, 512) && localText(albumName, 512) && optionalLocalId(albumId) && localNumber(duration))
    require(artists.size <= 64 && artists.all { optionalLocalId(it.id) && localText(it.name, 512) })
    require(optionalLocalId(entryId) && optionalLocalId(targetId) && localText(occurrenceId, 4096) &&
        key in 1..LOCAL_MAX_SAFE && order in 0..LOCAL_MAX_SAFE)
    playbackContext?.validateLocal()
}

private val localIntegers = setOf("version", "position", "key", "order", "revision")
private val localNumbers = setOf("elapsed", "duration", "progress")
private val localBooleans = setOf("shuffle", "playing", "waiting", "loading", "truncated", "reported")
private val localStrings = setOf("id", "name", "albumName", "albumId", "entryId", "targetId", "occurrenceId", "kind", "fence", "repeat", "error", "notice", "trackId", "eventId")
private fun requireLocalTypes(value: JsonElement, depth: Int = 0) {
    require(depth <= 16)
    when (value) {
        is JsonObject -> value.forEach { (key, child) ->
            when (key) {
                in localIntegers -> require(child is JsonPrimitive && !child.isString &&
                    child.content.matches(Regex("-?(0|[1-9][0-9]*)")) && child.longOrNull?.let { it in -LOCAL_MAX_SAFE..LOCAL_MAX_SAFE } == true)
                in localNumbers -> require(child is JsonPrimitive && !child.isString && child.doubleOrNull?.isFinite() == true)
                in localBooleans -> require(child is JsonPrimitive && !child.isString && child.booleanOrNull != null)
                in localStrings -> require(child is JsonPrimitive && child.isString)
            }
            if (child is JsonObject || child is JsonArray) requireLocalTypes(child, depth + 1)
        }
        is JsonArray -> value.forEach { requireLocalTypes(it, depth + 1) }
        else -> Unit
    }
}
