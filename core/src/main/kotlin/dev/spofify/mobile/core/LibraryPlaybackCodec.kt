package dev.spofify.mobile.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** Failures never include untrusted JSON, signed tokens, hello or backend diagnostics. */
class LibraryPlaybackProtocolException : IllegalArgumentException("Invalid library playback message")

object LibraryPlaybackCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    private val intents = setOf("low", "high", "next", "now")
    private val errors = setOf("", "blocked", "media")
    private val repeats = setOf("off", "all", "one")
    private fun check(value: Boolean) { if (!value) throw LibraryPlaybackProtocolException() }
    internal fun text(value: String, bytes: Int = 128, empty: Boolean = false) {
        check((empty || value.isNotBlank()) && value.toByteArray(Charsets.UTF_8).size <= bytes &&
            value.none { Character.isISOControl(it) } && wellFormed(value))
    }
    private fun wellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c.isHighSurrogate()) { if (i == s.length || !s[i++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }
        return true
    }
    internal fun name(value: String) {
        text(value, 256)
        val trimmed = value.trim { it.isWhitespace() || it == '\uFEFF' }
        check(trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 64)
    }
    private fun counter(n: Long, positive: Boolean = false) = check(n >= (if (positive) 1 else 0) && n <= LibraryPlaybackProtocol.MAX_SAFE_INTEGER)
    private fun seconds(n: Double) = check(n.isFinite() && n in 0.0..604800.0)
    private fun commandId(s: String) { check(s.matches(Regex("[1-9][0-9]{0,15}"))); counter(s.toLongOrNull() ?: -1, true) }
    private fun context(c: LibraryPlaybackContext) {
        check(c.kind in setOf("album", "artist", "playlist", "likes", "derived")); text(c.id, 512)
        text(c.name, 512, true); c.revision?.let { counter(it) }; c.fence?.let { text(it, 4096, true) }
        if (c.kind == "likes") check(c.id == "liked-songs")
        if (c.kind == "derived") check(c.revision != null && c.revision > 0 && !c.fence.isNullOrBlank())
    }
    private fun track(t: LibraryPlaybackTrack) {
        text(t.id, 512); text(t.name, 512, true); text(t.albumName, 512, true); text(t.albumId, 512, true)
        seconds(t.duration); counter(t.key, true); counter(t.order, true); check(t.intent in intents)
        check(t.artists.size <= 64)
        t.artists.forEach { text(it.name, 512, true); it.id?.let { id -> text(id, 512, true) } }
        t.entryId?.let { text(it, 512) }; t.targetId?.let { text(it, 512) }; t.occurrenceId?.let { text(it, 4096) }
        t.playbackContext?.let(::context)
    }
    private fun timing(sequence: Long, duration: Long) { counter(sequence, true); check(duration in 1..LibraryPlaybackProtocol.MAX_LEASE_MS) }
    private fun status(p: LibraryPlaybackStatus) {
        counter(p.outputVersion, true); counter(p.stateVersion, true); counter(p.playbackSequence)
        text(p.eventId, empty = true); seconds(p.elapsed); seconds(p.duration); check(p.error in errors)
        check(!p.isPlaying || p.error.isEmpty())
    }
    private fun snapshot(s: LibraryPlaybackSnapshot) {
        text(s.sessionId); counter(s.stateVersion, true); counter(s.outputVersion, true); counter(s.drainUntilMs)
        status(s.playback)
        check(s.playback.stateVersion == s.stateVersion && s.playback.outputVersion == s.outputVersion)
        text(s.selectedDeviceId, empty = true); text(s.selectedConnectionId, empty = true)
        check(s.selectedDeviceId.isEmpty() == s.selectedConnectionId.isEmpty())
        check(s.devices.map { it.deviceId }.distinct().size == s.devices.size)
        s.devices.forEach { text(it.deviceId); name(it.name) }
        if (s.selectedDeviceId.isNotEmpty()) check(s.devices.any { it.deviceId == s.selectedDeviceId })
        s.transfer?.let {
            text(it.sourceDeviceId, empty = true); text(it.sourceConnectionId, empty = true)
            text(it.targetDeviceId); text(it.targetConnectionId)
            check(it.phase in setOf("preparing", "releasing", "draining", "activating"))
        }
        val st = s.state
        check(st.version == 1 && !st.truncated && st.repeat in repeats && st.error in errors)
        seconds(st.elapsed); seconds(st.duration); text(st.notice, 512, true)
        st.context?.let(::context); st.queue.forEach(::track)
        check(st.queue.map { it.key }.distinct().size == st.queue.size)
        check(if (st.queue.isEmpty()) st.position == -1 else st.position in st.queue.indices)
        check(st.highKeys.distinct().size == st.highKeys.size)
        val upcoming = st.queue.drop(st.position + 1).map { it.key }
        val upcomingSet = upcoming.toHashSet()
        val highSet = st.highKeys.toHashSet()
        check(st.highKeys.all { it in upcomingSet } && upcoming.filter { it in highSet } == st.highKeys)
        st.occurrence?.let {
            counter(it.key, true); text(it.trackId, 512); text(it.eventId)
            check(it.progress.isFinite() && it.progress >= 0 && it.progress <= LibraryPlaybackProtocol.MAX_SAFE_INTEGER.toDouble())
            check(st.position >= 0 && st.queue[st.position].key == it.key && st.queue[st.position].id == it.trackId)
        }
        check(st.queue.isEmpty() == (st.occurrence == null))
        check(s.playback.eventId == (st.occurrence?.eventId ?: ""))
    }
    private fun instruction(i: LibraryPlaybackInstruction) {
        text(i.id); counter(i.outputVersion, true); text(i.eventId, empty = true); seconds(i.elapsed)
        i.commandId?.let(::commandId); i.track?.let(::track)
        when (i.type) {
            "activate", "execute" -> {
                val lease = i.lease ?: throw LibraryPlaybackProtocolException(); timing(lease.clientSequence, lease.durationMs)
                check(i.currentInstructionId == null && i.next == null)
                if (i.planId != null) { check(i.type == "execute"); text(i.planId) }
            }
            "prepare", "release" -> check(i.lease == null && i.planId == null && i.currentInstructionId == null && i.next == null && (i.type != "release" || i.track == null))
            "stageNext", "cancelNext" -> {
                check(i.lease == null && i.track == null && i.commandId == null)
                text(i.planId ?: throw LibraryPlaybackProtocolException()); text(i.currentInstructionId ?: throw LibraryPlaybackProtocolException())
                if (i.type == "stageNext") {
                    val next = i.next ?: throw LibraryPlaybackProtocolException()
                    text(next.eventId); text(next.instructionId); track(next.track)
                } else check(i.next == null)
            }
            else -> throw LibraryPlaybackProtocolException()
        }
    }
    fun decodeServer(bytes: ByteArray): LibraryPlaybackServerMessage = guarded {
        check(bytes.isNotEmpty() && bytes.size <= LibraryPlaybackProtocol.INBOUND_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        decodeServer(text)
    }
    fun decodeServer(raw: String): LibraryPlaybackServerMessage = guarded {
        check(raw.isNotEmpty() && raw.toByteArray(Charsets.UTF_8).size <= LibraryPlaybackProtocol.INBOUND_BYTES && wellFormed(raw))
        val root = StrictJSON(raw).parse().jsonObject
        val type = root["type"]?.jsonPrimitive?.content ?: throw LibraryPlaybackProtocolException()
        val fields = when (type) {
            "snapshot" -> setOf("connectionId", "deviceId", "snapshot")
            "state" -> setOf("snapshot")
            "playback" -> setOf("playback")
            "instruction" -> setOf("instruction")
            "lease" -> setOf("lease")
            "result" -> setOf("result")
            "error" -> setOf("error")
            else -> throw LibraryPlaybackProtocolException()
        }
        check(root.keys == fields + setOf("type", "sessionId"))
        validateNulls(root)
        validateSchema(root, LibraryPlaybackServerMessage.serializer().descriptor)
        val message = json.decodeFromJsonElement<LibraryPlaybackServerMessage>(root)
        text(message.sessionId)
        message.connectionId?.let { text(it) }; message.deviceId?.let { text(it) }
        message.snapshot?.let {
            check(it.sessionId == message.sessionId)
            check(root.getValue("snapshot").jsonObject.getValue("state").toString().toByteArray(Charsets.UTF_8).size <= LibraryPlaybackProtocol.STATE_BYTES)
            snapshot(it)
        }
        message.playback?.let(::status); message.instruction?.let(::instruction)
        message.lease?.let { counter(it.outputVersion, true); text(it.eventId, empty = true); timing(it.clientSequence, it.durationMs) }
        message.result?.let {
            commandId(it.id); counter(it.stateVersion); check(it.acceptedCount >= 0)
            check(it.outcome in setOf("resolving", "accepted", "rejected"))
            check(it.code in setOf("", "stale_state_version", "stale_revision", "conflict", "expired", "invalid", "unavailable", "capacity", "superseded"))
            check(it.placement in setOf("none", "low", "high", "next", "replace", "existing"))
            check(it.execution in setOf("unchanged", "pending", "confirmed", "blocked", "media", "uncertain", "superseded"))
        }
        message.error?.let { text(it.code, 128); text(it.message, 4096) }
        message
    }
    @OptIn(ExperimentalSerializationApi::class)
    private fun validateSchema(value: JsonElement, descriptor: SerialDescriptor) {
        if (value == JsonNull) { check(descriptor.isNullable); return }
        when (descriptor.kind) {
            StructureKind.CLASS -> {
                val obj = value as? JsonObject ?: throw LibraryPlaybackProtocolException()
                val known = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
                check(obj.keys.all { it in known })
                for (i in 0 until descriptor.elementsCount) {
                    val field = obj[descriptor.getElementName(i)]
                    if (field == null) check(descriptor.isElementOptional(i))
                    else validateSchema(field, descriptor.getElementDescriptor(i))
                }
            }
            StructureKind.LIST -> {
                val list = value as? JsonArray ?: throw LibraryPlaybackProtocolException()
                list.forEach { validateSchema(it, descriptor.getElementDescriptor(0)) }
            }
            PrimitiveKind.STRING -> check(value is JsonPrimitive && value.isString)
            PrimitiveKind.BOOLEAN -> check(value is JsonPrimitive && !value.isString && value.content in setOf("true", "false"))
            PrimitiveKind.INT, PrimitiveKind.LONG -> check(value is JsonPrimitive && !value.isString && value.content.matches(Regex("-?(0|[1-9][0-9]*)")) && value.content.toLongOrNull() != null)
            PrimitiveKind.DOUBLE -> check(value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull()?.isFinite() == true)
            else -> throw LibraryPlaybackProtocolException()
        }
    }
    private fun validateNulls(element: JsonElement, path: String = "") {
        when (element) {
            JsonNull -> check(path in setOf("snapshot.transfer", "snapshot.state.context", "snapshot.state.occurrence"))
            is JsonObject -> element.forEach { (key, value) -> validateNulls(value, if (path.isEmpty()) key else "$path.$key") }
            is JsonArray -> element.forEach { validateNulls(it, "$path[]") }
            else -> Unit
        }
    }
    internal fun ticket(raw: String): String = guarded {
        check(raw.toByteArray().size <= 1024)
        val root = StrictJSON(raw).parse().jsonObject
        check(root.keys == setOf("ticket"))
        val ticket = root.getValue("ticket").jsonPrimitive
        check(ticket.isString && ticket.content.length == 64)
        text(ticket.content, 64)
        ticket.content
    }
    internal fun hello(ticket: String, name: String, deviceId: String, canPlay: Boolean): String {
        name(name); text(deviceId)
        check(ticket.length == 64); text(ticket, 64)
        return buildJsonObject { put("type", "hello"); put("ticket", ticket); put("name", name); put("deviceId", deviceId); put("canPlay", canPlay) }.toString()
    }
    fun encode(message: LibraryPlaybackClientMessage, commandId: String? = null, clientSequence: Long? = null): String = guarded {
        val value = buildJsonObject {
            when (message) {
                is LibraryPlaybackClientMessage.Command -> {
                    val id = commandId ?: throw LibraryPlaybackProtocolException(); commandId(id)
                    put("type", "command"); put("id", id); put("command", command(message.command))
                    message.expectedStateVersion?.let { counter(it); put("expectedStateVersion", it) }
                }
                is LibraryPlaybackClientMessage.Ack -> {
                    fence(message.outputVersion, message.eventId, message.instructionId, clientSequence)
                    check(message.status in setOf("ok", "blocked", "media", "rejected"))
                    put("type", "ack"); put("instructionId", message.instructionId); put("outputVersion", message.outputVersion)
                    put("eventId", message.eventId); put("clientSequence", clientSequence!!); put("status", message.status)
                }
                is LibraryPlaybackClientMessage.Report -> {
                    fence(message.outputVersion, message.eventId, message.instructionId, clientSequence)
                    seconds(message.elapsed); seconds(message.duration); check(message.error in errors)
                    check(!message.isPlaying || (!message.ended && message.error.isEmpty()))
                    put("type", "report"); put("instructionId", message.instructionId); put("outputVersion", message.outputVersion)
                    put("eventId", message.eventId); put("clientSequence", clientSequence!!)
                    put("elapsed", message.elapsed); put("duration", message.duration); put("isPlaying", message.isPlaying)
                    put("isBuffering", message.isBuffering); put("isLoading", message.isLoading); put("error", message.error); put("ended", message.ended)
                    message.planId?.let { check(message.ended); text(it); put("planId", it) }
                }
            }
        }.toString()
        check(value.toByteArray(Charsets.UTF_8).size <= LibraryPlaybackProtocol.CLIENT_BYTES)
        value
    }
    private fun fence(version: Long, event: String, instruction: String, sequence: Long?) {
        counter(version, true); text(event, empty = true); text(instruction); counter(sequence ?: -1, true)
    }
    private fun contextJSON(c: LibraryPlaybackContext): JsonObject {
        context(c)
        return buildJsonObject { put("kind", c.kind); put("id", c.id); put("name", c.name); c.revision?.let { put("revision", it) }; c.fence?.let { put("fence", it) } }
    }
    private fun selection(s: LibraryPlaybackSelection): JsonObject = buildJsonObject {
        when (s) {
            is LibraryPlaybackSelection.Occurrence -> { counter(s.key, true); put("kind", "occurrence"); put("key", s.key) }
            is LibraryPlaybackSelection.Collection -> { put("kind", "collection"); put("context", contextJSON(s.context)) }
            is LibraryPlaybackSelection.Track -> {
                put("kind", "track"); text(s.track.id, 512)
                val r = s.track
                if (s.context?.kind == "playlist") check(r.entryId != null) else check(r.entryId == null)
                if (s.context?.kind == "derived") {
                    check(r.targetId == s.context.id && r.occurrenceId != null && r.selectionToken != null && s.context.revision != null && s.context.fence != null)
                } else check(r.targetId == null && r.occurrenceId == null && r.selectionToken == null)
                put("track", buildJsonObject {
                    put("id", r.id); r.entryId?.let { text(it, 512); put("entryId", it) }
                    r.targetId?.let { text(it, 512); put("targetId", it) }; r.occurrenceId?.let { text(it, 4096); put("occurrenceId", it) }
                    r.selectionToken?.let { text(it, 8192); put("selectionToken", it) }
                })
                s.context?.let { put("context", contextJSON(it)) }
            }
        }
    }
    private fun command(c: LibraryPlaybackCommand): JsonObject = buildJsonObject {
        when (c) {
            is LibraryPlaybackCommand.QueueIntent -> {
                check(c.intent in intents); put("type", "queueIntent"); put("intent", c.intent); put("selection", selection(c.selection))
                c.shuffle?.let { check(c.intent == "now" && c.selection is LibraryPlaybackSelection.Collection); put("shuffle", it) }
            }
            is LibraryPlaybackCommand.SetShuffle -> { put("type", "setShuffle"); put("shuffle", c.shuffle) }
            is LibraryPlaybackCommand.SetRepeat -> { check(c.repeat in repeats); put("type", "setRepeat"); put("repeat", c.repeat) }
            is LibraryPlaybackCommand.Seek -> { seconds(c.value); put("type", "seek"); put("value", c.value) }
            is LibraryPlaybackCommand.Jump -> { counter(c.key, true); put("type", "jump"); put("key", c.key) }
            is LibraryPlaybackCommand.Remove -> { counter(c.key, true); put("type", "remove"); put("key", c.key) }
            is LibraryPlaybackCommand.Move -> { counter(c.key, true); c.afterKey?.let { counter(it, true); check(it != c.key) }; put("type", "move"); put("key", c.key); put("afterKey", c.afterKey?.let(::JsonPrimitive) ?: JsonNull) }
            is LibraryPlaybackCommand.Select -> { text(c.deviceId); put("type", "select"); put("deviceId", c.deviceId) }
            is LibraryPlaybackCommand.Rename -> { name(c.name); put("type", "rename"); put("name", c.name); c.deviceId?.let { text(it); put("deviceId", it) } }
            LibraryPlaybackCommand.Pause -> put("type", "pause")
            LibraryPlaybackCommand.Resume -> put("type", "resume")
            LibraryPlaybackCommand.Next -> put("type", "next")
            LibraryPlaybackCommand.Previous -> put("type", "previous")
            LibraryPlaybackCommand.Retry -> put("type", "retry")
            LibraryPlaybackCommand.Clear -> put("type", "clear")
            LibraryPlaybackCommand.Close -> put("type", "close")
            LibraryPlaybackCommand.Deselect -> put("type", "deselect")
        }
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (_: Exception) { throw LibraryPlaybackProtocolException() }

    /** Token-level parsing before DTO decoding: the JSON library otherwise silently overwrites duplicate keys. */
    private class StrictJSON(private val source: String) {
        private var index = 0
        fun parse(): JsonElement { val value = value(0); whitespace(); check(index == source.length); return value }
        private fun whitespace() { while (index < source.length && source[index] in " \t\r\n") index++ }
        private fun take(c: Char): Boolean { whitespace(); return if (index < source.length && source[index] == c) { index++; true } else false }
        private fun string(): JsonPrimitive {
            whitespace(); check(index < source.length && source[index] == '"'); val start = index++
            var escaped = false
            while (index < source.length) {
                val c = source[index++]
                if (!escaped && c == '"') {
                    val token = json.parseToJsonElement(source.substring(start, index)).jsonPrimitive
                    check(token.isString && wellFormed(token.content)); return token
                }
                if (!escaped && c == '\\') escaped = true else escaped = false
            }
            throw LibraryPlaybackProtocolException()
        }
        private fun value(depth: Int): JsonElement {
            check(depth <= 16); whitespace(); check(index < source.length)
            return when (source[index]) {
                '{' -> {
                    index++; val values = linkedMapOf<String, JsonElement>()
                    if (!take('}')) {
                        do { val key = string().content; check(!values.containsKey(key)); check(take(':')); values[key] = value(depth + 1) } while (take(','))
                        check(take('}'))
                    }
                    JsonObject(values)
                }
                '[' -> {
                    index++; val values = mutableListOf<JsonElement>()
                    if (!take(']')) { do { values.add(value(depth + 1)) } while (take(',')); check(take(']')) }
                    JsonArray(values)
                }
                '"' -> string()
                else -> {
                    val start = index
                    while (index < source.length && source[index] !in ",]} \t\r\n") index++
                    val token = source.substring(start, index)
                    check(token == "true" || token == "false" || token == "null" || token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    val element = json.parseToJsonElement(token)
                    if (token !in setOf("true", "false", "null")) {
                        val n = token.toDouble(); check(n.isFinite() && kotlin.math.abs(n) <= LibraryPlaybackProtocol.MAX_SAFE_INTEGER.toDouble())
                    }
                    element
                }
            }
        }
    }
}
