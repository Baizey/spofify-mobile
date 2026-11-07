package dev.spofify.mobile.core

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val MAX_SAFE = 9007199254740991L
internal const val MAX_PLAYBACK_BODY_BYTES = 2 * 1024 * 1024
// Maximum escaped context/history growth + poll identity/sequence and 16 maximum ACKs.
// PlaybackPublicationTest proves this reserve; exact API body-size enforcement remains below.
internal const val PLAYBACK_PUBLICATION_RESERVE_BYTES = 16 * 1024
internal val playbackJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

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
        require(version == 1 && position >= -1 && position < queue.size && (queue.isEmpty() || position >= 0))
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
internal fun libraryId(s: String) = s.isNotEmpty() && s.trim() == s && text(s, 512)
private fun optionalId(s: String) = s.isEmpty() || libraryId(s)
private val wireNumbers = setOf("version", "position", "elapsed", "duration", "key", "order", "revision", "progress", "epoch", "leaseMs", "quarantineMs", "stateSeq", "stateAgeMs", "acceptedRevision", "expectedLeadRevision", "value", "acceptedCount", "currentKey", "commitRevision", "playbackKey", "queueIntentsVersion")
private val wireBooleans = setOf("shuffle", "playing", "waiting", "loading", "truncated", "reported", "available", "first")
private val wireStrings = setOf("incarnation", "id", "name", "albumName", "albumId", "entryId", "targetId", "occurrenceId", "selectionToken", "kind", "fence", "repeat", "error", "notice", "trackId", "eventId", "deviceId", "activeDeviceId", "commandId", "sourceDeviceId", "targetDeviceId", "phase", "deliveryId", "type", "status", "direction", "receiverId", "outputDeviceId", "currentEventId", "intent", "provenance", "outcome", "placement", "playback", "generation", "admissionToken", "registrationGeneration", "targetRegistrationGeneration", "attemptId", "expectedGeneration")
internal fun requireWireTypes(value: JsonElement, depth: Int = 0) {
    require(depth <= 16)
    when (value) {
        is JsonObject -> value.forEach { (key, child) ->
            when (key) {
                in wireNumbers -> if (child != JsonNull || key !in setOf("currentKey", "commitRevision", "playbackKey")) {
                    require(child is JsonPrimitive && !child.isString && child.doubleOrNull?.isFinite() == true)
                    if (key !in setOf("elapsed", "duration", "progress", "value")) require(child.content.matches(Regex("-?(0|[1-9][0-9]*)")) && child.longOrNull?.let { it in -MAX_SAFE..MAX_SAFE } == true)
                }
                in wireBooleans -> require(child is JsonPrimitive && !child.isString && child.booleanOrNull != null)
                in wireStrings -> if (key != "currentEventId" || child != JsonNull) require(child is JsonPrimitive && child.isString)
            }
            if (child is JsonObject || child is JsonArray) requireWireTypes(child, depth + 1)
        }
        is JsonArray -> value.forEach { requireWireTypes(it, depth + 1) }
        else -> Unit
    }
}
fun validPlaybackProtocolId(s: String) = s.matches(Regex("[A-Za-z0-9_-]{1,128}"))
fun validPlaybackDeviceName(s: String) = s.isNotBlank() && s.codePointCount(0, s.length) <= 64 && text(s, 256)
internal fun PlaybackContext.validate() {
    require(kind in setOf("album", "artist", "playlist", "likes", "derived") && libraryId(id) && text(name, 512) && revision in 0..MAX_SAFE && text(fence, 4096))
    if (kind == "derived") require(validPlaybackProtocolId(id) && revision > 0 && fence.isNotEmpty() && fence.all { it.code in 33..126 })
}
internal fun PlaybackTrack.validate() {
    require(libraryId(id) && text(name, 512) && text(albumName, 512) && optionalId(albumId) && number(duration))
    require(artists.size <= 64 && artists.all { optionalId(it.id) && text(it.name, 512) })
    require(optionalId(entryId) && optionalId(targetId) && text(occurrenceId, 4096) && key in 1..MAX_SAFE && order in 0..MAX_SAFE)
    playbackContext?.validate()
}

/** Native outgoing transport is explicit intent. Decode legacy toggle only for owner-side resolution. */
@ConsistentCopyVisibility
data class PlaybackDeviceCommand private constructor(val json: JsonObject) {
    val type: String get() = json.getValue("type").jsonPrimitive.content
    val intent: String? get() = json["intent"]?.jsonPrimitive?.content
    val expected: QueueIntentBinding? get() = json["expected"]?.jsonObject?.let(QueueIntentBinding::fromJson)
    val selection: QueueIntentSelection? get() = json["selection"]?.jsonObject?.let(QueueIntentSelection::fromJson)
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
        fun queueIntent(intent: QueueIntentIntent, expected: QueueIntentBinding, selection: QueueIntentSelection): PlaybackDeviceCommand =
            PlaybackDeviceCommand(buildJsonObject {
                put("type", "queueIntent"); put("intent", intent.wire); put("expected", expected.toJson()); put("selection", selection.toJson())
            })
        fun queueIntent(intent: String, expected: QueueIntentBinding, selection: QueueIntentSelection): PlaybackDeviceCommand =
            queueIntent(QueueIntentIntent.fromWire(intent), expected, selection)
        fun fromRawJson(raw: String): PlaybackDeviceCommand = fromJson(PlaybackRawJson.parse(raw))
        fun fromJson(value: JsonObject): PlaybackDeviceCommand {
            requireWireTypes(value)
            val type = value["type"]?.jsonPrimitive?.content ?: error("Missing command type")
            val result = when (type) {
                // Shipped web clients still send toggle. It is incoming-only: transport("toggle")
                // stays forbidden so native controls never toggle against a stale remote display.
                "toggle" -> PlaybackDeviceCommand(buildJsonObject { put("type", "toggle") })
                "queueIntent" -> queueIntent(QueueIntentIntent.fromWire(value.getValue("intent").jsonPrimitive.content),
                    QueueIntentBinding.fromJson(value.getValue("expected").jsonObject), QueueIntentSelection.fromJson(value.getValue("selection").jsonObject))
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
@Serializable data class PlaybackAck(val deliveryId: String, val epoch: Long, val status: String, val queueIntentResult: QueueIntentResult? = null)
@Serializable data class PlaybackDevicesView(
    val deviceId: String = "", val devices: List<PlaybackDevice>, val activeDeviceId: String, val epoch: Long,
    val leaseMs: Long, val quarantineMs: Long = 0, val state: PlaybackSnapshot?, val stateSeq: Long,
    val stateAgeMs: Long, val transfer: PlaybackTransfer?, val directives: List<PlaybackDirective>, val commands: List<PlaybackOutcome>, val error: String = "", val queueIntents: QueueIntentsView? = null,
    val registration: PlaybackRegistrationView? = null,
    val leadership: PlaybackLeadership? = null,
) {
    // Body properties are deliberately not copied by data-class copy(). Only the exact
    // instance validated at this boundary may skip a later redundant traversal.
    @kotlinx.serialization.Transient private var validationComplete = false
    @kotlinx.serialization.Transient var syncSnapshotReference: PlaybackSyncReference? = null
        internal set
    @kotlinx.serialization.Transient var syncStatusReference: PlaybackSyncReference? = null
        internal set
    @kotlinx.serialization.Transient var syncLive: PlaybackSyncLive? = null
        internal set
    @kotlinx.serialization.Transient var publicationAck: PlaybackSyncPublicationAck? = null
        internal set
    fun validated(): PlaybackDevicesView = validate(false)
    internal fun validatedCachedStructure(): PlaybackDevicesView = validate(true)
    private fun validate(cachedStructure: Boolean): PlaybackDevicesView = apply {
        if (validationComplete) return@apply
        require(epoch in 1..MAX_SAFE && stateSeq in 0..MAX_SAFE && leaseMs in 0..8000 && quarantineMs in 0..8000 && stateAgeMs in 0..MAX_SAFE)
        require(deviceId.isEmpty() || validPlaybackProtocolId(deviceId))
        require(devices.size <= 16 && devices.map { it.deviceId }.distinct().size == devices.size)
        require(devices.all { validPlaybackProtocolId(it.deviceId) && validPlaybackDeviceName(it.name) })
        require(activeDeviceId.isEmpty() || devices.any { it.deviceId == activeDeviceId })
        if (!cachedStructure) state?.validated()
        require(directives.size <= 32 && commands.size <= 128)
        transfer?.let { require(validPlaybackProtocolId(it.commandId) && validPlaybackProtocolId(it.targetDeviceId) && (it.sourceDeviceId.isEmpty() || validPlaybackProtocolId(it.sourceDeviceId)) && it.phase in setOf("waiting", "preparing", "releasing", "activating")) }
        directives.forEach { require(validPlaybackProtocolId(it.deliveryId) && it.epoch == epoch && it.type in setOf("prepare", "release", "activate", "command")); it.state?.validated(); it.command?.let(PlaybackDeviceCommand::fromJson) }
        commands.forEach { require(validPlaybackProtocolId(it.commandId) && it.status in setOf("pending", "ok", "blocked", "media", "rejected", "timeout", "cancelled")) }
        leadership?.validated()
        registration?.validated(this)
        queueIntents?.validated(this, cachedStructure)
        if (queueIntents == null) require(directives.none { it.command?.get("type")?.jsonPrimitive?.content == "queueIntent" })
        validationComplete = cachedStructure
    }
    fun toJson(): JsonObject = JsonObject(playbackJson.encodeToJsonElement(serializer(), validated()).jsonObject.filterKeys { (it != "queueIntents" || queueIntents != null) && (it != "registration" || registration != null) && (it != "leadership" || leadership != null) })
    companion object {
        fun fromRawJson(raw: String, allowQueueIntents: Boolean = false, allowRegistration: Boolean = false): PlaybackDevicesView = fromJson(PlaybackRawJson.parse(raw), allowQueueIntents, allowRegistration)
        fun fromJson(value: JsonObject): PlaybackDevicesView = fromJson(value, allowQueueIntents = true, allowRegistration = true)
        fun fromJson(value: JsonObject, allowQueueIntents: Boolean, allowRegistration: Boolean = false): PlaybackDevicesView {
            require(allowRegistration || "registration" !in value)
            require("registration" !in value || value["registration"] is JsonObject)
            require(allowQueueIntents || "queueIntents" !in value)
            require("queueIntents" !in value || value["queueIntents"] is JsonObject)
            requireWireTypes(value)
            value["state"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::requireSnapshotShape)
            value["directives"]?.jsonArray?.forEach { directive ->
                directive.jsonObject["state"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::requireSnapshotShape)
            }
            // Unknown versions are unsupported, never assumed to provide singleton safety.
            val normalized = if (value["registration"]?.jsonObject?.get("version")?.jsonPrimitive?.intOrNull?.let { it != 1 } == true)
                JsonObject(value.filterKeys { it != "registration" }) else value
            return playbackJson.decodeFromJsonElement(serializer(), normalized).validated()
        }
    }
}

/** Injectable protocol boundary for deterministic service tests. No write is ever automatically retried. */
interface PlaybackDevicesProtocol {
    suspend fun view(): PlaybackDevicesView
    val revisionSyncSupported: Boolean get() = false
    val syncSnapshotNeeded: Boolean get() = false
    fun acceptSyncSnapshot(view: PlaybackDevicesView) {}
    suspend fun fetchSyncSnapshot(): PlaybackDevicesView = view()
    suspend fun pollLive(deviceId: String, epoch: Long, stateSeq: Long, base: PlaybackSyncReference, live: PlaybackSyncLive): PlaybackDevicesView =
        throw UnsupportedOperationException("Revision sync is unavailable")
    suspend fun register(name: String): PlaybackDevicesView
    suspend fun probeQueueIntents(): PlaybackDevicesView = view()
    suspend fun register(name: String, queueIntentsVersion: Int?, installationId: String?, receiverId: String): PlaybackDevicesView = register(name, queueIntentsVersion)
    suspend fun register(name: String, queueIntentsVersion: Int?): PlaybackDevicesView {
        if (queueIntentsVersion != null) throw UnsupportedOperationException("Queue intents receiver is unavailable")
        return register(name)
    }
    suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long? = null, state: PlaybackSnapshot? = null, acks: List<PlaybackAck> = emptyList()): PlaybackDevicesView
    suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>, queueIntentState: QueueIntentState?): PlaybackDevicesView {
        if (queueIntentState != null || acks.any { it.queueIntentResult != null }) throw UnsupportedOperationException("Queue intents publication is unavailable")
        return poll(deviceId, epoch, stateSeq, state, acks)
    }
    /** Restore a genuinely continuing local output only; never selection, activation or publication. */
    suspend fun continueReceiver(registration: PlaybackRegistrationDevice, epoch: Long, leadRevision: Long): PlaybackDevicesView =
        throw UnsupportedOperationException("Receiver continuation is unavailable")
    suspend fun rename(deviceId: String, name: String): PlaybackDevicesView
    suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView
    suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView
    suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long, targetRegistrationGeneration: String?): PlaybackDevicesView =
        transfer(deviceId, epoch, commandId, targetDeviceId, selectionSeq)
    /** Explicit first-Play admission only. Unsupported implementations must never fall back to
     * picker takeover. Acceptance still requires the normal prepare/activate/terminal ACK path. */
    suspend fun claimUnselected(deviceId: String, epoch: Long, commandId: String, selectionSeq: Long): PlaybackDevicesView =
        throw UnsupportedOperationException("Conditional playback selection is unavailable")
    suspend fun leave(deviceId: String)
    /** Capture before retiring ordinary auth/work. Production supplies a bounded, exact-ID,
     * single-use disposal capability, never a replacement authenticated client. Without an
     * already connected direct exact-origin route, cleanup fails closed rather than resolving. */
    fun prepareLeave(deviceId: String): suspend () -> Unit = { leave(deviceId) }
}
class PlaybackDevicesApi(private val client: SpofifyClient) : PlaybackDevicesProtocol {
    private val transport = ApiTransport(client)
    private val path = listOf("api", "me", "player")
    @Volatile private var extensions = false
    @Volatile private var probed = false
    @Volatile private var receiverCapable = false
    @Volatile private var registrationSupported = false
    private var leadershipSupported = false
    private var continuationUnsupported = false
    private var observedRegistration: PlaybackRegistrationView? = null
    private var ownRegistration: PlaybackRegistrationDevice? = null
    private data class PendingRegistration(val name: String, val queueVersion: Int?, val attempt: PlaybackRegistrationAttempt)
    private var pendingRegistration: PendingRegistration? = null
    private var rejectedReceiver: String? = null
    private val revisionSync = PlaybackRevisionSync()
    override val revisionSyncSupported get() = revisionSync.supported
    override val syncSnapshotNeeded get() = revisionSync.needsSnapshot
    override fun acceptSyncSnapshot(view: PlaybackDevicesView) = revisionSync.accept(view)
    override suspend fun fetchSyncSnapshot(): PlaybackDevicesView = withTimeout(4000) {
        revisionSync.snapshot(requireNotNull(transport.send("GET", path, playbackExtensions = extensions,
            playbackRegistration = registrationSupported, playbackSync = true)), extensions, registrationSupported)
    }
    override suspend fun probeQueueIntents(): PlaybackDevicesView = withTimeout(4000) {
        val raw = requireNotNull(transport.send("GET", path, playbackExtensions = true, playbackRegistration = true, playbackSync = true))
        val version = raw["sync"]?.let { advertised ->
            require(advertised is JsonObject) { "Invalid revision sync advertisement" }
            val value = advertised["version"]
            require(value is JsonPrimitive && !value.isString &&
                value.content.matches(Regex("[1-9][0-9]*")) && value.longOrNull?.let { it in 1..MAX_SAFE } == true) {
                "Invalid revision sync version"
            }
            value.long
        }
        val view = if (version != null && version != 1L) {
            check(!revisionSync.supported) { "Revision sync capability changed" }
            PlaybackDevicesView.fromJson(requireNotNull(transport.send("GET", path, playbackExtensions = true, playbackRegistration = true)), true, true)
        } else revisionSync.probe(raw)
        check(!registrationSupported || view.registration != null) { "Negotiated registration metadata is missing" }
        check(!leadershipSupported || view.leadership != null) { "Negotiated leadership metadata is missing" }
        leadershipSupported = view.leadership != null
        extensions = view.queueIntents != null; probed = true
        registrationSupported = view.registration != null
        observedRegistration = view.registration
        view
    }
    private suspend fun requireExtensions() {
        if (!probed) probeQueueIntents()
        check(extensions) { "Queue intents are unsupported by this server" }
    }
    private suspend fun post(action: String, fields: JsonObjectBuilder.() -> Unit): PlaybackDevicesView = withTimeout(4000) {
        val supplied = buildJsonObject { put("action", action); fields() }
        val body = buildJsonObject {
            supplied.forEach { (key, value) -> put(key, value) }
            val id = (supplied["deviceId"] as? JsonPrimitive)?.content
            val target = (supplied["targetDeviceId"] as? JsonPrimitive)?.content
            id?.let { generationFor(it)?.let { g -> put("registrationGeneration", g) } }
            if ("targetRegistrationGeneration" !in supplied) target?.let { generationFor(it)?.let { g -> put("targetRegistrationGeneration", g) } }
        }
        require(body.toString().toByteArray(Charsets.UTF_8).size <= MAX_PLAYBACK_BODY_BYTES) { "Queue is too large to share" }
        PlaybackRequestContract.fromRawJson(body.toString())
        val sync = action == "poll" && revisionSync.supported
        val raw = requireNotNull(transport.send("POST", path, body, playbackExtensions = extensions,
            playbackRegistration = registrationSupported, playbackSync = sync))
        if (sync) revisionSync.response(raw, extensions, registrationSupported).also { observedRegistration = it.registration }
        else decodeView(raw)
    }
    private fun decodeView(json: JsonObject): PlaybackDevicesView = PlaybackDevicesView.fromJson(json, extensions, registrationSupported).also {
        require(!leadershipSupported || it.leadership != null) { "Negotiated leadership metadata is missing" }
        require(!extensions || it.queueIntents != null) { "Negotiated queue metadata is missing" }
        require(!registrationSupported || it.registration != null) { "Negotiated registration metadata is missing" }
        observedRegistration = it.registration
    }
    // Observations may refresh targets, NEVER the issuer's captured registration authority.
    private fun generationFor(id: String): String? = ownRegistration?.takeIf { it.deviceId == id }?.registrationGeneration
        ?: observedRegistration?.devices?.find { it.deviceId == id }?.registrationGeneration
    override suspend fun view() = withTimeout(4000) { decodeView(transport.send("GET", path, playbackExtensions = extensions, playbackRegistration = registrationSupported)!!) }
    override suspend fun register(name: String): PlaybackDevicesView = register(name, null)
    override suspend fun register(name: String, queueIntentsVersion: Int?): PlaybackDevicesView {
        require(validPlaybackDeviceName(name) && (queueIntentsVersion == null || queueIntentsVersion == 1))
        if (queueIntentsVersion != null) requireExtensions()
        val view = post("register") { put("name", name); queueIntentsVersion?.let { put("queueIntentsVersion", it) } }
        require(queueIntentsVersion == null || view.queueIntents?.receivers?.any { it.deviceId == view.deviceId && it.version == 1 } == true)
        receiverCapable = queueIntentsVersion == 1
        return view
    }
    override suspend fun register(name: String, queueIntentsVersion: Int?, installationId: String?, receiverId: String): PlaybackDevicesView {
        if (!probed) probeQueueIntents()
        if (!registrationSupported) return register(name, queueIntentsVersion)
        require(validPlaybackDeviceName(name) && installationId != null && validPlaybackInstallationId(installationId) && validPlaybackProtocolId(receiverId))
        require(queueIntentsVersion == null || queueIntentsVersion == 1)
        if (queueIntentsVersion != null) requireExtensions()
        val observed = observedRegistration?.devices?.find { it.deviceId == installationId }
        // A new incarnation may replace a predecessor once with fresh admission. An old
        // accepted/rejected incarnation cannot refresh CAS just to replace its successor.
        val previouslyEnrolled = ownRegistration?.let { it.deviceId == installationId && it.receiverId == receiverId } == true
        check(!(previouslyEnrolled || rejectedReceiver == receiverId) || observed == null || observed.receiverId == receiverId) {
            "Playback receiver was superseded"
        }
        val pending = pendingRegistration?.takeIf { it.attempt.deviceId == installationId && it.attempt.receiverId == receiverId }
            ?: requireNotNull(observedRegistration).let { admission ->
                PendingRegistration(name, queueIntentsVersion, PlaybackRegistrationAttempt(installationId, receiverId,
                    java.util.UUID.randomUUID().toString(), admission.generation, admission.admissionToken)).also { pendingRegistration = it }
            }
        val view = try {
            post("register") {
                put("name", pending.name); pending.queueVersion?.let { put("queueIntentsVersion", it) }
                put("registration", pending.attempt.toJson())
            }
        } catch (error: ApiException) {
            // Definitive rejection retires this operation. No immediate replay with a new CAS.
            if (error.status in setOf(400, 401, 403, 404, 409, 429)) pendingRegistration = null
            if (error.status == 409) rejectedReceiver = receiverId
            throw error
        }
        require(view.deviceId == installationId)
        val accepted = requireNotNull(view.registration?.devices?.singleOrNull { it.deviceId == installationId })
        require(accepted.receiverId == receiverId)
        require(pending.queueVersion == null || view.queueIntents?.receivers?.any { it.deviceId == installationId && it.version == 1 } == true)
        ownRegistration = accepted
        rejectedReceiver = null
        pendingRegistration = null
        receiverCapable = pending.queueVersion == 1
        return view
    }
    override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView =
        poll(deviceId, epoch, stateSeq, state, acks, null)
    override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>, queueIntentState: QueueIntentState?): PlaybackDevicesView {
        if (queueIntentState != null || acks.any { it.queueIntentResult != null }) { requireExtensions(); check(receiverCapable) }
        require(!receiverCapable || state == null || queueIntentState != null)
        val body = PlaybackPublication.pollBody(deviceId, epoch, stateSeq, state, acks, queueIntentState)
        return post("poll") { body.forEach { (key, value) -> if (key != "action") put(key, value) } }
    }
    override suspend fun pollLive(deviceId: String, epoch: Long, stateSeq: Long, base: PlaybackSyncReference, live: PlaybackSyncLive): PlaybackDevicesView {
        check(revisionSync.supported)
        return post("poll") {
            identity(deviceId, epoch); put("stateSeq", stateSeq); put("base", base.toJson()); put("live", live.toJson())
            put("acks", JsonArray(emptyList()))
        }
    }
    override suspend fun continueReceiver(registration: PlaybackRegistrationDevice, epoch: Long, leadRevision: Long): PlaybackDevicesView {
        check(registrationSupported && leadershipSupported && !continuationUnsupported) { "Receiver continuation is unavailable" }
        check(ownRegistration == registration) { "Playback registration changed" }
        require(leadRevision in 1..MAX_SAFE)
        return try {
            post("continue") { identity(registration.deviceId, epoch); put("expectedLeadRevision", leadRevision) }
        } catch (error: ApiException) {
            // Older servers reject this new action. Never substitute selection or retry
            // an unsupported mutation on every heartbeat of the same authenticated client.
            if (error.status in setOf(400, 405)) continuationUnsupported = true
            throw error
        }
    }
    override suspend fun rename(deviceId: String, name: String): PlaybackDevicesView { require(validPlaybackProtocolId(deviceId) && validPlaybackDeviceName(name)); return post("rename") { put("deviceId", deviceId); put("name", name) } }
    override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView { require(validPlaybackProtocolId(commandId)); if (command.type == "queueIntent") requireExtensions(); return post("command") { identity(deviceId, epoch); put("commandId", commandId); put("command", command.json) } }
    override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
        require(validPlaybackProtocolId(commandId) && validPlaybackProtocolId(targetDeviceId) && selectionSeq in 1..MAX_SAFE)
        return post("transfer") { identity(deviceId, epoch); put("commandId", commandId); put("targetDeviceId", targetDeviceId); put("selectionSeq", selectionSeq) }
    }
    override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long, targetRegistrationGeneration: String?): PlaybackDevicesView {
        require(validPlaybackProtocolId(commandId) && validPlaybackProtocolId(targetDeviceId) && selectionSeq in 1..MAX_SAFE)
        require(targetRegistrationGeneration == null || validPlaybackProtocolId(targetRegistrationGeneration))
        // Null is an observed legacy destination, not permission to borrow a later incarnation.
        if (targetRegistrationGeneration == null) check(observedRegistration?.devices?.none { it.deviceId == targetDeviceId } != false)
        return post("transfer") {
            identity(deviceId, epoch); put("commandId", commandId); put("targetDeviceId", targetDeviceId); put("selectionSeq", selectionSeq)
            targetRegistrationGeneration?.let { put("targetRegistrationGeneration", it) }
        }
    }
    override suspend fun claimUnselected(deviceId: String, epoch: Long, commandId: String, selectionSeq: Long): PlaybackDevicesView {
        require(validPlaybackProtocolId(commandId) && selectionSeq in 1..MAX_SAFE)
        return post("transfer") {
            identity(deviceId, epoch); put("commandId", commandId); put("targetDeviceId", deviceId)
            put("selectionSeq", selectionSeq); put("requireUnselected", true)
        }
    }
    override suspend fun leave(deviceId: String) { require(validPlaybackProtocolId(deviceId)); post("leave") { put("deviceId", deviceId) } }
    override fun prepareLeave(deviceId: String): suspend () -> Unit = client.preparePlaybackLeave(deviceId,
        registrationGeneration = generationFor(deviceId))
    private fun JsonObjectBuilder.identity(id: String, epoch: Long) { require(validPlaybackProtocolId(id) && epoch in 1..MAX_SAFE); put("deviceId", id); put("epoch", epoch) }
}
