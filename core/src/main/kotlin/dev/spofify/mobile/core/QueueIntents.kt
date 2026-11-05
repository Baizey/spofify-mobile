package dev.spofify.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val SAFE = 9007199254740991L
private fun requireWireTypes(value: JsonObject) { requireWireTypes(value as JsonElement) }
@Serializable enum class QueueIntentIntent {
    @kotlinx.serialization.SerialName("low") LOW,
    @kotlinx.serialization.SerialName("high") HIGH,
    @kotlinx.serialization.SerialName("next") NEXT,
    @kotlinx.serialization.SerialName("now") NOW;
    val wire: String get() = name.lowercase(java.util.Locale.ROOT)
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("Unknown queue intent") }
}
@Serializable data class QueueIntentBinding(val outputDeviceId: String, val receiverId: String, val revision: Long, val currentKey: Long?, val currentEventId: String?) {
    fun validated() = apply {
        require(validPlaybackProtocolId(outputDeviceId) && validPlaybackProtocolId(receiverId) && revision in 1..SAFE)
        require(currentKey == null || currentKey in 1..SAFE)
        require(currentEventId == null || currentKey != null && currentEventId.isNotEmpty() && currentEventId.length <= 128 && currentEventId.all { it.code in 33..126 })
    }
    fun toJson() = playbackJson.encodeToJsonElement(serializer(), validated()).jsonObject
    companion object { fun fromJson(value: JsonObject) = playbackJson.decodeFromJsonElement(serializer(), value.also(::requireWireTypes)).validated() }
}
@Serializable data class QueueIntentOrigin(val key: Long, val intent: String)
@Serializable data class QueueIntentState(val receiverId: String, val revision: Long, val provenance: String, val origins: List<QueueIntentOrigin>, val highKeys: List<Long>) {
    fun validated() = apply {
        require(validPlaybackProtocolId(receiverId) && revision in 1..SAFE && provenance in setOf("tracked", "legacy-reset"))
        require(origins.size <= 1000 && highKeys.size <= 1000 && origins.map { it.key }.distinct().size == origins.size && highKeys.distinct().size == highKeys.size)
        require(origins.all { it.key in 1..SAFE && it.intent in setOf("low", "high", "next", "now", "legacy") } && highKeys.all { it in 1..SAFE })
    }
    fun validated(snapshot: PlaybackSnapshot) = validated().apply {
        snapshot.validated()
        require(origins.map { it.key } == snapshot.queue.map { it.key })
        var next = snapshot.position + 1
        highKeys.forEach { key ->
            while (next < snapshot.queue.size && snapshot.queue[next].key != key) next++
            require(next < snapshot.queue.size); next++
        }
    }
    fun toJson() = playbackJson.encodeToJsonElement(serializer(), validated()).jsonObject
    companion object {
        fun fromJson(value: JsonObject) = playbackJson.decodeFromJsonElement(serializer(), value.also(::requireWireTypes)).validated()
        fun fromJson(value: JsonObject, snapshot: PlaybackSnapshot) = fromJson(value).validated(snapshot)
    }
}
@Serializable data class QueueIntentResult(val outcome: String, val acceptedCount: Int, val commitRevision: Long?, val placement: String, val playback: String, val playbackKey: Long?, val notice: String) {
    fun validated() = apply {
        require(acceptedCount in 0..1000 && (commitRevision == null || commitRevision in 1..SAFE) && (playbackKey == null || playbackKey in 1..SAFE))
        require(notice in setOf("", "PLAYBACK_LIMIT", "CLICKED_OCCURRENCE_ONLY", "STALE_BINDING", "INVALID_SELECTION", "OUTPUT_UNAVAILABLE", "PUBLICATION_LIMIT", "PREPARATION_FAILED", "UNSUPPORTED_RECEIVER"))
        require(placement in setOf("low", "high", "next", "replace", "existing", "none"))
        require(playback in setOf("unchanged", "started", "resumed", "blocked", "media", "superseded"))
        require((playback in setOf("started", "resumed", "blocked", "media")) == (playbackKey != null))
        if (outcome in setOf("full", "stale", "unavailable", "unsupported", "no_op")) {
            require(acceptedCount == 0 && commitRevision == null && placement == "none" && playback == "unchanged")
            require(when (outcome) {
                "full" -> notice == "PLAYBACK_LIMIT"
                "stale" -> notice == "STALE_BINDING"
                "unsupported" -> notice == "UNSUPPORTED_RECEIVER"
                "no_op" -> notice == ""
                else -> notice in setOf("", "INVALID_SELECTION", "OUTPUT_UNAVAILABLE", "PUBLICATION_LIMIT", "PREPARATION_FAILED")
            })
        } else {
            require(commitRevision != null && placement != "none" && acceptedCount > 0 && (placement != "existing" || acceptedCount == 1))
            require(when (outcome) {
                "playing" -> playback in setOf("started", "resumed") && notice in setOf("", "CLICKED_OCCURRENCE_ONLY")
                "queued" -> playback in setOf("unchanged", "superseded") && (acceptedCount > 0 || playback == "superseded") && notice in setOf("", "CLICKED_OCCURRENCE_ONLY")
                "limited" -> acceptedCount > 0 && notice == "PLAYBACK_LIMIT" && playback in setOf("started", "resumed", "unchanged", "superseded")
                "blocked", "media" -> playback == outcome && notice in setOf("", "PLAYBACK_LIMIT", "CLICKED_OCCURRENCE_ONLY")
                else -> false
            })
        }
    }
    val status: String get() = when (outcome) { "blocked", "media" -> outcome; "full", "stale", "unavailable", "unsupported" -> "rejected"; else -> "ok" }
    /** Actual ACK state must align; retained view results are historical and must NOT be
     * compared to a later view snapshot. Older revision is only the BR-001 receipt. */
    fun validated(snapshot: PlaybackSnapshot, metadata: QueueIntentState, status: String) = validated().apply {
        metadata.validated(snapshot); require(this.status == status)
        commitRevision?.let { revision ->
            require(revision <= metadata.revision && acceptedCount > 0)
            require(revision == metadata.revision || playback == "superseded" && outcome in setOf("queued", "limited"))
        }
        playbackKey?.let { require(snapshot.position >= 0 && snapshot.queue[snapshot.position].key == it) }
        when (playback) {
            "started", "resumed" -> require(snapshot.playing && snapshot.error.isEmpty())
            "blocked", "media" -> require(!snapshot.playing && snapshot.error == playback)
            "superseded" -> if (commitRevision == metadata.revision) require(!snapshot.playing && snapshot.occurrence != null)
            else -> Unit
        }
    }
    fun toJson() = playbackJson.encodeToJsonElement(serializer(), validated()).jsonObject
    companion object { fun fromJson(value: JsonObject) = playbackJson.decodeFromJsonElement(serializer(), value.also(::requireWireTypes)).validated() }
}

sealed interface QueueIntentSelection {
    data class Track(val track: JsonObject, val context: PlaybackContext?) : QueueIntentSelection {
        override fun toString() = "QueueIntentSelection.Track(<redacted>)"
    }
    data class Collection(val context: PlaybackContext) : QueueIntentSelection
    data class Occurrence(val key: Long) : QueueIntentSelection
    fun toJson(): JsonObject = buildJsonObject {
        when (val s = this@QueueIntentSelection) {
            is Track -> { put("kind", "track"); put("track", s.track); put("context", s.context?.let { playbackJson.encodeToJsonElement(PlaybackContext.serializer(), it) } ?: JsonNull) }
            is Collection -> { put("kind", "collection"); put("context", playbackJson.encodeToJsonElement(PlaybackContext.serializer(), s.context)) }
            is Occurrence -> { put("kind", "occurrence"); put("key", s.key) }
        }
    }.also { validateSelection(it) }
    companion object {
        fun fromJson(value: JsonObject): QueueIntentSelection {
            validateSelection(value)
            fun context() = value["context"]?.takeUnless { it is JsonNull }?.let { playbackJson.decodeFromJsonElement(PlaybackContext.serializer(), it) }
            return when (value.getValue("kind").jsonPrimitive.content) {
                "track" -> Track(value.getValue("track").jsonObject, context())
                "collection" -> Collection(requireNotNull(context()))
                else -> Occurrence(value.getValue("key").jsonPrimitive.long)
            }
        }
    }
}
private fun validateSelection(value: JsonObject) {
    requireWireTypes(value)
    when (value.getValue("kind").jsonPrimitive.content) {
        "occurrence" -> require(value.keys == setOf("kind", "key") && value.getValue("key").jsonPrimitive.long in 1..SAFE)
        "collection" -> {
            require(value.keys == setOf("kind", "context"))
            playbackJson.decodeFromJsonElement(PlaybackContext.serializer(), value.getValue("context")).validate()
        }
        "track" -> {
            require(value.keys == setOf("kind", "track", "context"))
            val t = value.getValue("track").jsonObject
            require("key" !in t && "order" !in t)
            require(t.keys.containsAll(setOf("id", "name", "artists", "albumName", "albumId", "duration")))
            val context = value.getValue("context").takeUnless { it is JsonNull }?.let { playbackJson.decodeFromJsonElement(PlaybackContext.serializer(), it).also { c -> c.validate() } }
            val track = playbackJson.decodeFromJsonElement(PlaybackTrack.serializer(), JsonObject(t.filterKeys { it != "selectionToken" } + mapOf("key" to JsonPrimitive(1), "order" to JsonPrimitive(0))))
            track.validate()
            require(track.playbackContext == null || track.playbackContext == context)
            val token = t["selectionToken"]?.jsonPrimitive?.also { require(it.isString) }?.content ?: ""
            if (context?.kind == "derived") {
                require(track.targetId == context.id && track.entryId.isEmpty() && track.playbackContext == context)
                require(ascii(track.occurrenceId, 4096) && ascii(token, 8192))
            } else {
                require(track.targetId.isEmpty() && track.occurrenceId.isEmpty() && token.isEmpty())
                require(if (context?.kind == "playlist") libraryId(track.entryId) else track.entryId.isEmpty())
            }
        }
        else -> throw IllegalArgumentException("Unknown queue selection")
    }
}
private fun ascii(value: String, max: Int) = value.isNotEmpty() && value.length <= max && value.all { it.code in 33..126 }

@Serializable data class QueueIntentReceiver(val deviceId: String, val version: Int)
@Serializable data class QueueIntentDirective(val deliveryId: String, val state: QueueIntentState?)
@Serializable data class QueueIntentCommandResult(val commandId: String, val result: QueueIntentResult)
@Serializable data class QueueIntentsView(val version: Int, val receivers: List<QueueIntentReceiver>, val binding: QueueIntentBinding?, val state: QueueIntentState?, val directives: List<QueueIntentDirective>, val results: List<QueueIntentCommandResult>) {
    fun validated(view: PlaybackDevicesView) = apply {
        require(version == 1 && receivers.size <= 16 && directives.size <= 16 && results.size <= 128)
        require(receivers.map { it.deviceId }.distinct().size == receivers.size && receivers.all { it.version == 1 && validPlaybackProtocolId(it.deviceId) && view.devices.any { d -> d.deviceId == it.deviceId } })
        state?.validated(requireNotNull(view.state))
        binding?.validated()?.let {
            require(it.outputDeviceId == view.activeDeviceId && receivers.any { r -> r.deviceId == it.outputDeviceId })
            require(view.transfer == null && view.quarantineMs == 0L && view.leaseMs > 0 && view.devices.any { d -> d.deviceId == it.outputDeviceId && d.available })
            val snapshot = requireNotNull(view.state); val metadata = requireNotNull(state)
            require(it.receiverId == metadata.receiverId && it.revision == metadata.revision)
            require(it.currentKey == snapshot.queue.getOrNull(snapshot.position)?.key && it.currentEventId == snapshot.occurrence?.eventId)
        }
        require(directives.map { it.deliveryId }.distinct().size == directives.size)
        if (receivers.any { it.deviceId == view.deviceId }) {
            // Missing is not an explicit legacy hop: capable registrations must receive
            // a sidecar for every stateful delivery, even when that sidecar's state is null.
            val covered = directives.mapTo(hashSetOf()) { it.deliveryId }
            require(view.directives.filter { it.type in setOf("prepare", "activate") }.all { it.deliveryId in covered }) {
                "Capable handoff delivery is missing queue metadata"
            }
        }
        directives.forEach { sidecar ->
            val d = view.directives.single { it.deliveryId == sidecar.deliveryId }
            require(d.type in setOf("prepare", "activate")); sidecar.state?.validated(requireNotNull(d.state))
        }
        require(results.map { it.commandId }.distinct().size == results.size)
        results.forEach { r -> require(validPlaybackProtocolId(r.commandId)); r.result.validated(); require(view.commands.single { it.commandId == r.commandId }.status == r.result.status) }
    }
}

/** Exact joint admission, including every ACK and metadata byte. Use before installing a
 * prospective musical queue; the old musical-only reserve is not an extension budget. */
object PlaybackPublication {
    fun pollBody(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>, queueIntentState: QueueIntentState? = null): JsonObject {
        require(validPlaybackProtocolId(deviceId) && epoch in 1..SAFE)
        require((state == null) == (stateSeq == null) && (stateSeq == null || stateSeq in 1..SAFE) && acks.size <= 16)
        require(acks.map { it.deliveryId }.distinct().size == acks.size)
        queueIntentState?.validated(requireNotNull(state))
        acks.forEach {
            require(validPlaybackProtocolId(it.deliveryId) && it.epoch == epoch && it.status in setOf("ok", "blocked", "media", "rejected"))
            it.queueIntentResult?.validated(requireNotNull(state), requireNotNull(queueIntentState), it.status)
        }
        val body = buildJsonObject {
            put("action", "poll"); put("deviceId", deviceId); put("epoch", epoch)
            state?.let { put("state", it.toJson()); put("stateSeq", stateSeq!!) }
            queueIntentState?.let { put("queueIntentState", it.toJson()) }
            put("acks", JsonArray(acks.map { ack -> buildJsonObject {
                put("deliveryId", ack.deliveryId); put("epoch", ack.epoch); put("status", ack.status)
                ack.queueIntentResult?.let { put("queueIntentResult", it.toJson()) }
            } }))
        }
        require(body.toString().toByteArray(Charsets.UTF_8).size <= MAX_PLAYBACK_BODY_BYTES) { "Queue is too large to share" }
        PlaybackRawJson.parse(body.toString()) // exact depth, lexical and recursive shape admission
        return body
    }
    fun validatePoll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>, queueIntentState: QueueIntentState? = null) {
        pollBody(deviceId, epoch, stateSeq, state, acks, queueIntentState)
    }
}
