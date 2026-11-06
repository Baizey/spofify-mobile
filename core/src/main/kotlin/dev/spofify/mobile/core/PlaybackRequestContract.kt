package dev.spofify.mobile.core

import kotlinx.serialization.json.*

/** Strict raw wire-shape boundary shared by outbound player envelopes, frozen corpus and
 * synthetic fixtures. Shape-valid requests are NOT authority: owner/session/delivery and
 * exact-self guarded-transfer admission remain the coordinator/broker's responsibility. */
object PlaybackRequestContract {
    fun fromRawJson(raw: String): JsonObject {
        val root = PlaybackRawJson.parse(raw)
        requireWireTypes(root)
        val action = root.getValue("action").jsonPrimitive.content
        root["registrationGeneration"]?.let { require(action != "register" && validPlaybackProtocolId(it.jsonPrimitive.content)) }
        root["targetRegistrationGeneration"]?.let { require(action == "transfer" && validPlaybackProtocolId(it.jsonPrimitive.content)) }
        root["registration"]?.let {
            require(action == "register")
            val registration = it.jsonObject
            require(registration.keys == setOf("deviceId", "receiverId", "attemptId", "expectedGeneration", "admissionToken"))
            require(validPlaybackInstallationId(registration.getValue("deviceId").jsonPrimitive.content))
            listOf("receiverId", "attemptId", "expectedGeneration", "admissionToken").forEach { key ->
                require(validPlaybackProtocolId(registration.getValue(key).jsonPrimitive.content))
            }
        }
        when (action) {
            "register" -> {
                require(validPlaybackDeviceName(root.getValue("name").jsonPrimitive.content))
                if ("queueIntentsVersion" in root) require(root.getValue("queueIntentsVersion").jsonPrimitive.int == 1)
            }
            "command" -> {
                identity(root)
                require(validPlaybackProtocolId(root.getValue("commandId").jsonPrimitive.content))
                PlaybackDeviceCommand.fromJson(root.getValue("command").jsonObject)
            }
            "poll" -> {
                identity(root)
                val snapshot = root["state"]?.let { PlaybackSnapshot.fromJson(it.jsonObject) }
                require((snapshot == null) == (root["stateSeq"] == null))
                if (snapshot != null) require(root.getValue("stateSeq").jsonPrimitive.long in 1..9007199254740991L)
                val metadata = root["queueIntentState"]?.let { QueueIntentState.fromJson(it.jsonObject, requireNotNull(snapshot)) }
                val acks = root["acks"]?.jsonArray ?: JsonArray(emptyList())
                require(acks.size <= 16)
                acks.forEach {
                    val ack = it.jsonObject
                    require(validPlaybackProtocolId(ack.getValue("deliveryId").jsonPrimitive.content))
                    require(ack.getValue("epoch") == root.getValue("epoch"))
                    val status = ack.getValue("status").jsonPrimitive.content
                    require(status in setOf("ok", "blocked", "media", "rejected"))
                    ack["queueIntentResult"]?.let { result -> QueueIntentResult.fromJson(result.jsonObject).validated(requireNotNull(snapshot), requireNotNull(metadata), status) }
                }
            }
            "rename" -> { require(validPlaybackProtocolId(root.getValue("deviceId").jsonPrimitive.content)); require(validPlaybackDeviceName(root.getValue("name").jsonPrimitive.content)) }
            "leave" -> require(validPlaybackProtocolId(root.getValue("deviceId").jsonPrimitive.content))
            "transfer" -> {
                identity(root)
                require(validPlaybackProtocolId(root.getValue("commandId").jsonPrimitive.content) && validPlaybackProtocolId(root.getValue("targetDeviceId").jsonPrimitive.content))
                root["selectionSeq"]?.let { require(it.jsonPrimitive.long in 1..9007199254740991L) }
                root["requireUnselected"]?.let { require(it == JsonPrimitive(true)) }
            }
        }
        return root
    }
    private fun identity(root: JsonObject) {
        require(validPlaybackProtocolId(root.getValue("deviceId").jsonPrimitive.content) && root.getValue("epoch").jsonPrimitive.long in 1..9007199254740991L)
    }
}
