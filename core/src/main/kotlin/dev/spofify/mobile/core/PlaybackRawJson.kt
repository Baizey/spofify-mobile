package dev.spofify.mobile.core

import kotlinx.serialization.json.*

/** Player-only raw boundary. Do not feed untrusted player bytes through JsonObject first:
 * duplicate members (including escaped spellings) would already have been lost. */
internal object PlaybackRawJson {
    private sealed interface Node
    private data class Obj(val fields: Map<String, Node>) : Node
    private data class Arr(val values: List<Node>) : Node
    private data class Atom(val value: JsonPrimitive) : Node

    // Requests/publications default to 2MiB. Successful inbound views retain the
    // transport's larger bounded budget so the receiver can reject an unpublishable
    // prepare/activate snapshot and ACK that rejection instead of losing the view.
    fun parse(raw: String, maxBytes: Long = MAX_PLAYBACK_BODY_BYTES.toLong()): JsonObject {
        require(maxBytes > 0 && raw.toByteArray(Charsets.UTF_8).size.toLong() <= maxBytes)
        val parser = Parser(raw)
        val root = parser.value(0)
        parser.end()
        require(root is Obj)
        checkShape(root, "root")
        return materialize(root).jsonObject
    }

    private fun materialize(n: Node): JsonElement = when (n) {
        is Obj -> JsonObject(n.fields.mapValues { materialize(it.value) })
        is Arr -> JsonArray(n.values.map(::materialize))
        is Atom -> n.value
    }
    private fun Obj.string(key: String) = (fields[key] as? Atom)?.value?.takeIf { it.isString }?.content
    private fun names(vararg s: String) = s.toSet()
    private fun checkShape(n: Node, role: String) {
        if (n is Arr) {
            val max = when (role) { "artists" -> 64; "devices", "receivers", "acks" -> 16; "directives" -> 32; "commands", "results" -> 128; else -> 1000 }
            require(n.values.size <= max)
            n.values.forEach { checkShape(it, role) }
            return
        }
        if (n !is Obj) return
        val actualRole = if (role == "root") when {
            "action" in n.fields -> "request"
            "devices" in n.fields -> "view"
            "type" in n.fields -> "command"
            "outcome" in n.fields -> "result"
            "receiverId" in n.fields && "origins" in n.fields -> "metadata"
            else -> "snapshot"
        } else role
        val allowed = when (actualRole) {
            "view" -> names("deviceId", "devices", "activeDeviceId", "epoch", "leaseMs", "quarantineMs", "state", "stateSeq", "stateAgeMs", "transfer", "directives", "commands", "error", "queueIntents")
            "request" -> when (n.string("action")) {
                "register" -> names("action", "name", "queueIntentsVersion")
                "poll" -> names("action", "deviceId", "epoch", "stateSeq", "state", "queueIntentState", "acks")
                "command" -> names("action", "deviceId", "epoch", "commandId", "command")
                "rename" -> names("action", "deviceId", "name")
                "leave" -> names("action", "deviceId")
                "transfer" -> names("action", "deviceId", "epoch", "commandId", "targetDeviceId", "selectionSeq", "requireUnselected")
                else -> throw IllegalArgumentException("Unknown player action")
            }
            "snapshot" -> names("version", "queue", "position", "context", "shuffle", "repeat", "elapsed", "duration", "playing", "waiting", "loading", "error", "truncated", "notice", "occurrence")
            "queue" -> names("id", "name", "artists", "albumName", "albumId", "duration", "entryId", "targetId", "occurrenceId", "playbackContext", "key", "order")
            "track" -> names("id", "name", "artists", "albumName", "albumId", "duration", "entryId", "targetId", "occurrenceId", "selectionToken", "playbackContext", "key", "order")
            "artists" -> names("id", "name")
            "context", "playbackContext" -> names("kind", "id", "name", "revision", "fence")
            "occurrence" -> names("key", "trackId", "eventId", "progress", "reported")
            "devices" -> names("deviceId", "name", "available")
            "transfer" -> names("commandId", "sourceDeviceId", "targetDeviceId", "phase")
            "directives" -> if ("type" in n.fields) names("deliveryId", "epoch", "type", "commandId", "command", "state") else names("deliveryId", "state")
            "commands" -> names("commandId", "status")
            "acks" -> names("deliveryId", "epoch", "status", "queueIntentResult")
            "queueIntents" -> names("version", "receivers", "binding", "state", "directives", "results")
            "receivers" -> names("deviceId", "version")
            "expected", "binding" -> names("outputDeviceId", "receiverId", "revision", "currentKey", "currentEventId")
            "metadata", "queueIntentState" -> names("receiverId", "revision", "provenance", "origins", "highKeys")
            "origins" -> names("key", "intent")
            "results" -> names("commandId", "result")
            "result", "queueIntentResult" -> names("outcome", "acceptedCount", "commitRevision", "placement", "playback", "playbackKey", "notice")
            "selection" -> when (n.string("kind")) {
                "track" -> names("kind", "track", "context")
                "collection" -> names("kind", "context")
                "occurrence" -> names("kind", "key")
                else -> throw IllegalArgumentException("Unknown selection")
            }
            "command" -> when (n.string("type")) {
                "queueIntent" -> names("type", "intent", "expected", "selection")
                "seek" -> names("type", "value")
                "jump", "remove" -> names("type", "key")
                "move" -> names("type", "key", "direction")
                "play" -> names("type", "track", "context", "entryId", "occurrenceId", "selectionToken")
                "playCollection" -> names("type", "context", "shuffle", "trackId", "entryId", "occurrenceId", "selectionToken")
                "enqueue" -> names("type", "track", "first")
                "enqueueContext" -> names("type", "context", "first")
                "pause", "resume", "next", "previous", "retry", "shuffle", "repeat", "close", "clear", "toggle" -> names("type")
                else -> throw IllegalArgumentException("Unknown playback command")
            }
            else -> throw IllegalArgumentException("Unknown player object")
        }
        require(n.fields.keys.all { it in allowed }) { "Unknown player field" }
        n.fields.forEach { (key, child) ->
            if (child is Atom && !child.value.isString && child.value != JsonNull && key !in setOf("duration", "elapsed", "progress", "value") && child.value.booleanOrNull == null) {
                require(child.value.content.matches(Regex("-?(0|[1-9][0-9]*)")))
                require(child.value.longOrNull?.let { it in -9007199254740991L..9007199254740991L } == true)
            }
            val next = when {
                key == "state" && (actualRole == "queueIntents" || actualRole == "directives" && "type" !in n.fields) -> "metadata"
                key == "state" -> "snapshot"
                key == "track" && actualRole == "selection" -> "unkeyedTrack"
                else -> key
            }
            if (next == "unkeyedTrack") {
                require(child is Obj && "key" !in child.fields && "order" !in child.fields)
                checkShape(child, "track")
            } else if (key == "highKeys" && child is Arr) {
                require(child.values.size <= 1000 && child.values.all { it is Atom && !it.value.isString && it.value.content.matches(Regex("[1-9][0-9]*")) && it.value.longOrNull?.let { v -> v <= 9007199254740991L } == true })
            } else checkShape(child, next)
        }
    }

    private class Parser(val source: String) {
        var i = 0
        fun ws() { while (i < source.length && source[i] in " \t\r\n") i++ }
        fun end() { ws(); require(i == source.length) }
        fun take(c: Char) { ws(); require(i < source.length && source[i++] == c) }
        fun string(): String {
            ws(); val start = i; require(i < source.length && source[i++] == '"')
            while (i < source.length) {
                val c = source[i++]
                if (c == '"') return Json.parseToJsonElement(source.substring(start, i)).jsonPrimitive.content
                if (c == '\\') { require(i < source.length); i++ }
                else require(c.code >= 32)
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }
        fun value(depth: Int): Node {
            require(depth <= 16); ws(); require(i < source.length)
            return when (source[i]) {
                '{' -> {
                    i++; ws(); val fields = linkedMapOf<String, Node>(); val seen = hashSetOf<String>()
                    if (i < source.length && source[i] == '}') { i++; Obj(fields) } else {
                        while (true) {
                            val key = string(); require(seen.add(key.lowercase(java.util.Locale.ROOT))) { "Duplicate player field" }
                            take(':'); fields[key] = value(depth + 1); ws(); require(i < source.length)
                            if (source[i++] == '}') break
                            require(source[i - 1] == ',')
                        }
                        Obj(fields)
                    }
                }
                '[' -> {
                    i++; ws(); val values = arrayListOf<Node>()
                    if (i < source.length && source[i] == ']') { i++; Arr(values) } else {
                        while (true) {
                            require(values.size < 1000); values.add(value(depth + 1)); ws(); require(i < source.length)
                            if (source[i++] == ']') break
                            require(source[i - 1] == ',')
                        }
                        Arr(values)
                    }
                }
                '"' -> Atom(JsonPrimitive(string()))
                else -> {
                    val start = i
                    while (i < source.length && source[i] !in ",]} \t\r\n") i++
                    val token = source.substring(start, i)
                    require(token in setOf("true", "false", "null") || token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    Atom(Json.parseToJsonElement(token).jsonPrimitive)
                }
            }
        }
    }
}
