package dev.spofify.mobile.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Shared catalog metadata only. The legacy administrator path never enters MusicItem. */
@Serializable internal class MusicArtistWire(val id: String, val name: String)
@Serializable internal class MusicCoverageWire(val tracks: Long, val inLibrary: Long)
@Serializable internal class MusicItemWire(
    val kind: String, val id: String, val name: String = "",
    val artists: List<MusicArtistWire> = emptyList(), val albumId: String = "", val albumName: String = "",
    val releaseDate: String = "", val durationMs: Long = 0, val trackNumber: Int = 0,
    val hasAudio: Boolean? = null, private val libraryPath: String? = null, val coverage: MusicCoverageWire,
    val genres: List<String> = emptyList(),
    val albumType: String = "", val relation: String = "",
) {
    fun audio(legacyAdministratorFallback: Boolean): Boolean = hasAudio ?: if (legacyAdministratorFallback)
        !libraryPath.isNullOrEmpty() else throw IllegalArgumentException("Missing audio availability")
}

internal fun validMusicEntityId(value: String): String = value.also {
    require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 512 && it.none(Char::isISOControl))
}

/** Legacy fallback is opt-in at the old Library endpoint only; new clients default to positive facts. */
internal fun projectMusicItem(wire: MusicItemWire, legacyAdministratorFallback: Boolean = false): MusicItem {
    val kind = MusicKind.entries.single { it.plural == wire.kind + "s" }
    require(wire.durationMs >= 0 && wire.trackNumber >= 0)
    require(wire.coverage.inLibrary >= 0 && wire.coverage.tracks >= wire.coverage.inLibrary)
    val audio = wire.audio(legacyAdministratorFallback)
    val artists = wire.artists.map { artist ->
        Artist(if (artist.id.isEmpty()) "" else validMusicEntityId(artist.id), artist.name)
    }
    return MusicItem(kind, validMusicEntityId(wire.id), wire.name, artists, wire.albumId, wire.albumName,
        wire.releaseDate, wire.durationMs, wire.trackNumber, audio,
        wire.coverage.inLibrary, wire.coverage.tracks, wire.genres,
        albumType = wire.albumType, relation = wire.relation)
}

/** Strict positive wire types, followed by the same metadata rules as Library browsing. */
internal object PositiveMusicItemProjection {
    private val json = Json { ignoreUnknownKeys = true }
    private val integer = Regex("-?(0|[1-9][0-9]*)")

    fun decode(element: JsonElement): MusicItem {
        val item = element as? JsonObject ?: error("Invalid item")
        for (key in listOf("kind", "id", "name")) item.string(key)
        for (key in listOf("albumId", "albumName", "releaseDate", "albumType", "relation"))
            if (key in item) item.string(key)
        for (key in listOf("durationMs", "trackNumber", "discNumber"))
            if (key in item) require(item.number(key) >= 0)
        val audio = item.getValue("hasAudio") as? JsonPrimitive ?: error("Invalid audio fact")
        require(!audio.isString && audio.booleanOrNull != null)
        val coverage = item.getValue("coverage") as? JsonObject ?: error("Invalid coverage")
        coverage.number("tracks")
        coverage.number("inLibrary")
        if ("albums" in coverage) require(coverage.number("albums") >= 0)
        item["artists"]?.let { artists ->
            require(artists is JsonArray)
            artists.forEach { element ->
                val artist = element as? JsonObject ?: error("Invalid artist")
                artist.string("id")
                artist.string("name")
            }
        }
        item["genres"]?.let { genres ->
            require(genres is JsonArray)
            genres.forEach { string(it) }
        }
        val wire = json.decodeFromJsonElement<MusicItemWire>(JsonObject(item - "libraryPath"))
        if (wire.albumId.isNotEmpty()) validMusicEntityId(wire.albumId)
        return projectMusicItem(wire) // Explicit hasAudio is mandatory for every role.
    }

    private fun JsonObject.string(key: String): String = string(getValue(key))

    private fun string(element: JsonElement): String {
        val value = element as? JsonPrimitive ?: error("Invalid string")
        require(value.isString)
        // JSON escape sequences must not smuggle an unpaired UTF-16 surrogate into an ID/display field.
        val text = value.content
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            if (Character.isHighSurrogate(character)) require(index < text.length && Character.isLowSurrogate(text[index++]))
            else require(!Character.isLowSurrogate(character))
        }
        return text
    }

    private fun JsonObject.number(key: String): Long {
        val value = getValue(key) as? JsonPrimitive ?: error("Invalid integer")
        require(!value.isString && integer.matches(value.content))
        return value.content.toLong()
    }
}
