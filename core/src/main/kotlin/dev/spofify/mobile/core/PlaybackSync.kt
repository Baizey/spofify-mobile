package dev.spofify.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Collections

private const val SYNC_SAFE = 9007199254740991L
@Serializable data class PlaybackSyncReference(val incarnation: String, val revision: Long) {
    fun validated() = apply { require(validPlaybackProtocolId(incarnation) && revision in 1..SYNC_SAFE) }
    fun toJson(): JsonObject = playbackJson.encodeToJsonElement(serializer(), validated()).jsonObject
}
@Serializable data class PlaybackSyncPublicationAck(val stateSeq: Long, val base: PlaybackSyncReference) {
    fun validated() = apply { require(stateSeq in 1..SYNC_SAFE); base.validated() }
}
/** Scalar-only positive DTO. There is no route for queue structure to enter a compact write. */
@Serializable data class PlaybackSyncLive(
    val currentKey: Long?, val currentEventId: String?, val elapsed: Double, val duration: Double,
    val playing: Boolean, val waiting: Boolean, val loading: Boolean, val error: String,
    val progress: Double, val reported: Boolean,
) {
    fun validated() = apply {
        require(currentKey == null || currentKey in 1..SYNC_SAFE)
        require(currentEventId == null || currentKey != null && currentEventId.isNotEmpty() && currentEventId.length <= 128 && currentEventId.all { it.code in 33..126 })
        require(listOf(elapsed, duration, progress).all { it.isFinite() && it in 0.0..604800.0 })
        require(error in setOf("", "blocked", "media", "queue"))
        require(currentEventId != null || progress == 0.0 && !reported)
    }
    fun toJson(): JsonObject = playbackJson.encodeToJsonElement(serializer(), validated()).jsonObject
    fun applyTo(snapshot: PlaybackSnapshot): PlaybackSnapshot {
        require(currentKey == snapshot.queue.getOrNull(snapshot.position)?.key && currentEventId == snapshot.occurrence?.eventId)
        return snapshot.copy(elapsed = elapsed, duration = duration, playing = playing, waiting = waiting,
            loading = loading, error = error, occurrence = snapshot.occurrence?.copy(progress = progress, reported = reported))
    }
    companion object {
        fun from(snapshot: PlaybackSnapshot) = PlaybackSyncLive(snapshot.queue.getOrNull(snapshot.position)?.key,
            snapshot.occurrence?.eventId, snapshot.elapsed, snapshot.duration, snapshot.playing, snapshot.waiting,
            snapshot.loading, snapshot.error, snapshot.occurrence?.progress ?: 0.0, snapshot.occurrence?.reported ?: false)
        fun fromJson(json: JsonObject): PlaybackSyncLive {
            requireWireTypes(json)
            return playbackJson.decodeFromJsonElement(serializer(), json).validated()
        }
    }
}

@Serializable private data class SyncVersion(val version: Int, val incarnation: String, val revision: Long) {
    fun reference(): PlaybackSyncReference { require(version == 1); return PlaybackSyncReference(incarnation, revision).validated() }
}
@Serializable private data class Availability(val deviceId: String, val available: Boolean)
@Serializable private data class SyncQueueControl(val version: Int, val receivers: List<QueueIntentReceiver>,
    val binding: QueueIntentBinding?, val directives: List<QueueIntentDirective>, val results: List<QueueIntentCommandResult>)
@Serializable private data class SyncControl(
    val deviceId: String = "", val activeDeviceId: String, val epoch: Long, val leaseMs: Long, val quarantineMs: Long,
    val availability: List<Availability>, val transfer: PlaybackTransfer?, val directives: List<PlaybackDirective>,
    val commands: List<PlaybackOutcome>, val error: String = "", val queueIntents: SyncQueueControl? = null,
    val registration: PlaybackRegistrationView? = null, val leadership: PlaybackLeadership? = null,
)

/** Authenticated-client scoped. A fetched structure and an APPLIED observation are distinct;
 * neither is an owner publication acknowledgement. No GET occurs inside response projection. */
class PlaybackRevisionSync {
    var supported = false
        private set
    private var structure: PlaybackDevicesView? = null
    private var applied: PlaybackSyncReference? = null
    private var observed: PlaybackSyncReference? = null
    val needsSnapshot get() = supported && observed != applied

    fun probe(json: JsonObject): PlaybackDevicesView {
        if ("sync" !in json && "kind" !in json) {
            check(!supported) { "Revision sync capability disappeared" }
            return PlaybackDevicesView.fromJson(json, true, true)
        }
        return snapshot(json, true, true).also { supported = true }
    }
    private fun reference(json: JsonObject): PlaybackSyncReference {
        requireWireTypes(json)
        return playbackJson.decodeFromJsonElement(SyncVersion.serializer(), json.getValue("sync")).reference()
    }
    fun snapshot(json: JsonObject, queue: Boolean, registration: Boolean): PlaybackDevicesView {
        require(json.keys == setOf("kind", "sync", "snapshot") && json.getValue("kind") == JsonPrimitive("snapshot"))
        val ref = reference(json)
        val decoded = PlaybackDevicesView.fromJson(json.getValue("snapshot").jsonObject, queue, registration)
        // Own immutable queue arrays once at this boundary, not on each live tick.
        val state = decoded.state?.let { s -> s.copy(queue = Collections.unmodifiableList(s.queue.map { t ->
            t.copy(artists = Collections.unmodifiableList(t.artists.toList()))
        })) }
        val metadata = decoded.queueIntents?.state?.let { it.copy(
            origins = Collections.unmodifiableList(it.origins.toList()), highKeys = Collections.unmodifiableList(it.highKeys.toList())) }
        val view = decoded.copy(state = state,
            queueIntents = decoded.queueIntents?.copy(state = metadata)).validated()
        view.syncSnapshotReference = ref
        return view
    }
    fun accept(view: PlaybackDevicesView) {
        val ref = view.syncSnapshotReference ?: return
        view.validated()
        structure = view; applied = ref; observed = ref
    }
    fun response(json: JsonObject, queue: Boolean, registration: Boolean): PlaybackDevicesView {
        if (json["kind"] == JsonPrimitive("snapshot")) return snapshot(json, queue, registration)
        require(json.keys.containsAll(setOf("kind", "sync", "live", "control")) &&
            json.keys.all { it in setOf("kind", "sync", "live", "control", "publicationAck") } && json["kind"] == JsonPrimitive("status"))
        val ref = reference(json)
        val controlJson = json.getValue("control").jsonObject
        require(queue || "queueIntents" !in controlJson); require(registration || "registration" !in controlJson)
        val control = playbackJson.decodeFromJsonElement(SyncControl.serializer(), controlJson)
        require(!queue || control.queueIntents != null); require(!registration || control.registration != null)
        val liveJson = json.getValue("live")
        var seq = 0L; var age = 0L
        val live = if (liveJson == JsonNull) null else liveJson.jsonObject.let {
            require(it.keys.containsAll(setOf("stateSeq", "stateAgeMs")))
            seq = it.getValue("stateSeq").jsonPrimitive.long; age = it.getValue("stateAgeMs").jsonPrimitive.long
            PlaybackSyncLive.fromJson(JsonObject(it.filterKeys { k -> k != "stateSeq" && k != "stateAgeMs" }))
        }
        val cached = structure
        val matching = cached?.syncSnapshotReference == ref
        val state = cached?.state?.let { previous ->
            if (matching) requireNotNull(live).applyTo(previous)
            else if (live != null && live.currentKey == previous.queue.getOrNull(previous.position)?.key &&
                live.currentEventId == previous.occurrence?.eventId) live.applyTo(previous) else null
        }
        if (matching && cached?.state == null) require(live == null)
        val devices = control.availability.map { a -> PlaybackDevice(a.deviceId,
            cached?.devices?.find { it.deviceId == a.deviceId }?.name ?: "Player", a.available) }
        val qc = control.queueIntents
        val metadata = if (matching) cached?.queueIntents?.state else null
        val qi = qc?.let { QueueIntentsView(it.version, it.receivers, if (matching) it.binding else null,
            metadata, it.directives, it.results) }
        qc?.binding?.validated()?.let { binding ->
            require(binding.outputDeviceId == control.activeDeviceId && qc.receivers.any { it.deviceId == binding.outputDeviceId })
            require(control.transfer == null && control.quarantineMs == 0L && control.leaseMs > 0 && devices.any { it.deviceId == binding.outputDeviceId && it.available })
        }
        val view = PlaybackDevicesView(control.deviceId, devices, control.activeDeviceId, control.epoch, control.leaseMs,
            control.quarantineMs, state, seq, age, control.transfer, control.directives, control.commands, control.error,
            qi, control.registration, control.leadership).validatedCachedStructure()
        view.syncStatusReference = ref
        view.syncLive = live
        view.publicationAck = json["publicationAck"]?.let {
            playbackJson.decodeFromJsonElement(PlaybackSyncPublicationAck.serializer(), it).validated().also { ack -> require(ack.base == ref) }
        }
        observed = ref
        return view
    }
}
