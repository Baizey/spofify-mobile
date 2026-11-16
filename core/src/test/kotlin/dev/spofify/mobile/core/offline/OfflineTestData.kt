package dev.spofify.mobile.core.offline

import kotlinx.serialization.json.*

internal object OfflineTestData {
    val playlist = OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, "list-one")
    const val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val receipt = OfflinePlayReceipt("stable-event", "one", 10_000, 1500, 1000)

    fun page(ref: OfflineCollectionRef = playlist, start: Int = 0, ids: List<String> = listOf("one"),
        total: Int = start + ids.size, next: String? = null, fence: String = "fence-a", revision: Long = 1): String = buildJsonObject {
        put("version", 1)
        put(if (ref.kind == OfflineCollectionKind.PLAYLIST) "playlist" else "collection", buildJsonObject {
            put("id", ref.id)
            put("kind", if (ref.kind == OfflineCollectionKind.PLAYLIST) "manual" else ref.kind.path.dropLast(1))
            put("name", "Synthetic collection")
            if (ref.kind == OfflineCollectionKind.PLAYLIST) { put("description", ""); put("revision", revision) }
        })
        put("fence", fence)
        put("items", buildJsonArray { ids.forEachIndexed { index, id -> add(buildJsonObject {
            put("occurrenceId", "occurrence-${start + index}")
            put("position", start + index)
            put("track", Json.parseToJsonElement(track(id)))
        }) } })
        put("total", total)
        put("recordedAudioTotal", total)
        put("limit", 2)
        put("nextCursor", next?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    fun track(id: String = "one"): String = buildJsonObject {
        put("kind", "track"); put("id", id); put("name", "Synthetic song"); put("hasAudio", true)
        put("coverage", buildJsonObject { put("tracks", 1); put("inLibrary", 1); put("albums", 1) })
        put("artists", buildJsonArray { add(buildJsonObject { put("id", "artist"); put("name", "Synthetic artist") }) })
        put("durationMs", 2000)
    }.toString()

    fun media(id: String = "one", encodedId: String = id): String = buildJsonObject {
        put("version", 1); put("trackId", id); put("mediaVersion", "sha256:$hash"); put("sha256", hash)
        put("byteLength", 123); put("format", "wav"); put("contentType", "audio/wav")
        put("downloadUrl", "/api/me/offline/v1/tracks/$encodedId/audio?version=sha256%3A$hash")
    }.toString()

    fun ack(id: String = receipt.eventId, duplicate: Boolean = false) =
        """{"eventId":"$id","receivedAtMs":11000,"duplicate":$duplicate}"""
}
