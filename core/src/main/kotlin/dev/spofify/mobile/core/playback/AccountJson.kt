package dev.spofify.mobile.core.playback

import kotlinx.serialization.json.*

internal val playbackJson = Json { encodeDefaults = false }
internal fun safeSeconds(value: Double) = value.isFinite() && value >= 0

internal fun fields(vararg values: Pair<String, Any?>): JsonObject = JsonObject(values.associate { (key, value) ->
    key to when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> error("Unsupported account field")
    }
})
