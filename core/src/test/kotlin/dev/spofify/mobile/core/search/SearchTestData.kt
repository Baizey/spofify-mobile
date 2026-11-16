package dev.spofify.mobile.core.search

import kotlinx.serialization.json.*

internal object SearchTestData {
    const val expiry = "2026-01-01T12:05:00.123456789Z"
    fun item(kind: String = "track", id: String = "one", name: String = "Original 日本 name"): JsonObject = buildJsonObject {
        put("kind", kind); put("id", id); put("name", name)
        putJsonArray("artists") {
            add(buildJsonObject { put("id", ""); put("name", "Manual guest") })
            add(buildJsonObject { put("id", "known"); put("name", "Known artist") })
        }
        put("albumId", "album"); put("albumName", "Original album"); put("releaseDate", "2025")
        put("durationMs", 180000); put("trackNumber", 2); put("discNumber", 1)
        put("hasAudio", kind == "track")
        putJsonArray("genres") { add(JsonPrimitive("世界")) }
        putJsonObject("coverage") { put("tracks", 4); put("inLibrary", 3); put("albums", 1) }
    }
    fun page(items: List<JsonObject> = listOf(item()), count: Int = items.size, next: String? = null,
        expires: String = expiry): JsonObject = buildJsonObject {
        putJsonArray("items") { items.forEach { add(it) } }
        put("resultCount", count); put("expiresAt", expires)
        next?.let { put("nextCursor", it) }
    }
}
