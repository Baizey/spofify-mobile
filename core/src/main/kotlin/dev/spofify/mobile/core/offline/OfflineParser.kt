package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.json.StrictJsonObject
import dev.spofify.mobile.core.personal.PersonalArtist
import dev.spofify.mobile.core.personal.PersonalCoverage
import dev.spofify.mobile.core.personal.PersonalTrack
import kotlinx.serialization.json.*

internal object OfflineParser {
    const val MAX_PAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_SAFE = 9_007_199_254_740_991L
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private val playlistId = Regex("[A-Za-z0-9_-]{1,128}")
    private val hash = Regex("[0-9a-f]{64}")
    private val formats = mapOf("mp3" to "audio/mpeg", "flac" to "audio/flac", "m4a" to "audio/mp4",
        "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav",
        "aif" to "audio/aiff", "aiff" to "audio/aiff", "webm" to "audio/webm")

    data class Page(val collection: OfflineCollection, val fence: String, val items: List<OfflineOccurrence>,
        val total: Long, val recordedAudioTotal: Long, val nextCursor: String?)

    fun validReference(ref: OfflineCollectionRef): Boolean = if (ref.kind == OfflineCollectionKind.PLAYLIST)
        playlistId.matches(ref.id) else validSegment(ref.id)

    fun validSegment(id: String): Boolean = OfflineValidation.id(id) && id != "." && id != ".."

    fun page(bytes: ByteArray, ref: OfflineCollectionRef, offset: Int, limit: Int, expected: Page? = null): Page {
        val root = StrictJsonObject.parse(bytes, MAX_PAGE_BYTES, maxDepth = 16)
        val metadata = if (ref.kind == OfflineCollectionKind.PLAYLIST) "playlist" else "collection"
        root.fields("version", metadata, "fence", "items", "total", "recordedAudioTotal", "limit", "nextCursor")
        require(root.long("version") == 1L && root.long("limit") == limit.toLong())
        val header = root.obj(metadata)
        val collection = if (ref.kind == OfflineCollectionKind.PLAYLIST) {
            header.fields("id", "kind", "name", "description", "revision")
            require(header.string("id") == ref.id)
            val kind = when (header.string("kind")) {
                "manual" -> OfflinePlaylistKind.MANUAL
                "derived" -> OfflinePlaylistKind.DERIVED
                else -> error("Invalid playlist kind")
            }
            OfflineCollection(ref, header.display("name"), kind, header.display("description"),
                header.long("revision").also { require(it in 1..MAX_SAFE) })
        } else {
            header.fields("kind", "id", "name")
            require(header.string("id") == ref.id && header.string("kind") == ref.kind.path.dropLast(1))
            OfflineCollection(ref, header.display("name"))
        }
        val fence = root.string("fence").also { require(OfflineValidation.id(it, 16 * 1024)) }
        val total = root.count("total")
        val audioTotal = root.count("recordedAudioTotal")
        require(audioTotal <= total && (audioTotal == total || ref.kind == OfflineCollectionKind.PLAYLIST))
        val incomplete = audioTotal < total
        if (expected != null && (collection != expected.collection || fence != expected.fence ||
                total != expected.total || audioTotal != expected.recordedAudioTotal))
            throw OfflineException(OfflineFailure.STALE_MANIFEST)
        val entries = root.array("items")
        require(entries.size <= limit && offset.toLong() + entries.size <= total)
        val items = entries.mapIndexed { index, element ->
            val entry = element as? JsonObject ?: error("Invalid occurrence")
            entry.fields("occurrenceId", "position", "track")
            val id = entry.string("occurrenceId").also { require(OfflineValidation.id(it, 16 * 1024)) }
            val position = entry.long("position").also { require(it == offset.toLong() + index) }
            OfflineOccurrence(id, position, track(entry.obj("track"), incomplete))
        }
        val cursor = root.getValue("nextCursor").let { if (it == JsonNull) null else root.string("nextCursor") }
        if (cursor == null) require(offset.toLong() + items.size == total)
        else {
            require(items.isNotEmpty() && offset.toLong() + items.size < total)
            require(OfflineValidation.id(cursor, 16 * 1024) &&
                cursor.startsWith(if (ref.kind == OfflineCollectionKind.PLAYLIST) "of1_" else "ofc1_"))
        }
        // Older v1 servers included unavailable playlist rows. Check this page, but
        // don't follow cursors or stage a prefix of an unsavable snapshot.
        if (incomplete) {
            val available = items.count { it.track.hasAudio }
            require(available <= audioTotal && items.size - available <= total - audioTotal)
            require(items.distinctBy { it.occurrenceId }.size == items.size)
            throw OfflineException(OfflineFailure.SOURCE_UNAVAILABLE)
        }
        return Page(collection, fence, items, total, audioTotal, cursor)
    }

    private fun track(root: JsonObject, incomplete: Boolean): PersonalTrack {
        require(root.keys.all { it in setOf("kind", "id", "name", "hasAudio", "coverage", "artists", "genres",
            "albumId", "albumName", "albumType", "releaseDate", "relation", "durationMs", "discNumber", "trackNumber") })
        val hasAudio = root.bool("hasAudio")
        require(root.string("kind") == "track" && (hasAudio || incomplete))
        val id = root.string("id").also { require(OfflineValidation.id(it)) }
        val coverage = root.obj("coverage")
        coverage.fields("tracks", "inLibrary", "albums")
        val tracks = coverage.count("tracks")
        val available = coverage.count("inLibrary").also { require(it <= tracks) }
        val artists = root["artists"]?.let { root.array("artists").map { value ->
            val artist = value as? JsonObject ?: error("Invalid artist")
            artist.fields("id", "name")
            PersonalArtist(artist.string("id").also { require(OfflineValidation.id(it)) }, artist.display("name"))
        } }.orEmpty()
        val genres = root["genres"]?.let { root.array("genres").map { value -> displayText(value.stringValue()) } }.orEmpty()
        val albumId = root.optionalDisplay("albumId").also { require(it.isEmpty() || OfflineValidation.id(it)) }
        return PersonalTrack("track", id, root.display("name"), hasAudio,
            PersonalCoverage(tracks, available, coverage.count("albums")), artists, genres, albumId,
            root.optionalDisplay("albumName"), root.optionalDisplay("albumType"), root.optionalDisplay("releaseDate"),
            root.optionalDisplay("relation"), root.optionalCount("durationMs"),
            Math.toIntExact(root.optionalCount("discNumber")), Math.toIntExact(root.optionalCount("trackNumber")))
    }

    fun media(bytes: ByteArray, requestedId: String): OfflineMediaDescriptor {
        val root = StrictJsonObject.parse(bytes, 16 * 1024, maxDepth = 16)
        root.fields("version", "trackId", "mediaVersion", "sha256", "byteLength", "format", "contentType", "downloadUrl")
        require(root.long("version") == 1L && root.string("trackId") == requestedId)
        val digest = root.string("sha256").also { require(hash.matches(it)) }
        val version = root.string("mediaVersion").also { require(it == "sha256:$digest") }
        val length = root.long("byteLength").also { require(it > 0) }
        val format = root.string("format")
        val mime = root.string("contentType").also { require(formats[format] == it) }
        // Exact Go url.PathEscape segment and QueryEscape(version), never an arbitrary authenticated URL.
        val path = "/api/me/offline/v1/tracks/${escapeSegment(requestedId)}/audio?version=sha256%3A$digest"
        require(root.string("downloadUrl") == path)
        return OfflineMediaDescriptor(requestedId, version, digest, length, format, mime, path)
    }

    fun ack(bytes: ByteArray, receipt: OfflinePlayReceipt): OfflineReceiptAck {
        val root = StrictJsonObject.parse(bytes, 8 * 1024, maxDepth = 16)
        root.fields("eventId", "receivedAtMs", "duplicate")
        require(root.string("eventId") == receipt.eventId)
        val received = root.long("receivedAtMs").also { require(it >= 0) }
        return OfflineReceiptAck(receipt.eventId, received, root.bool("duplicate"))
    }

    fun errorCode(bytes: ByteArray): String? = try {
        val root = StrictJsonObject.parse(bytes, 8 * 1024, maxDepth = 16)
        root.fields("error")
        val error = root.obj("error")
        error.fields("code", "message")
        error.string("message") // Checked but never returned or displayed.
        error.string("code")
    } catch (_: Exception) { null }

    private fun escapeSegment(id: String): String = buildString {
        val hex = "0123456789ABCDEF"
        for (byte in id.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 255
            val char = value.toChar()
            if (value < 128 && (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.~\$&+:=@")) append(char)
            else { append('%'); append(hex[value shr 4]); append(hex[value and 15]) }
        }
    }

    private fun JsonObject.fields(vararg fields: String) { require(keys == fields.toSet()) }
    private fun JsonElement.stringValue(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("Invalid string")
    private fun JsonObject.string(key: String): String = getValue(key).stringValue()
    private fun JsonObject.long(key: String): Long {
        val value = getValue(key) as? JsonPrimitive ?: error("Invalid integer")
        require(!value.isString && integer.matches(value.content))
        return value.content.toLong()
    }
    private fun JsonObject.count(key: String): Long = long(key).also { require(it in 0..MAX_SAFE) }
    private fun JsonObject.optionalCount(key: String): Long = if (containsKey(key)) count(key) else 0
    private fun JsonObject.obj(key: String): JsonObject = getValue(key) as? JsonObject ?: error("Invalid object")
    private fun JsonObject.array(key: String): JsonArray = getValue(key) as? JsonArray ?: error("Invalid array")
    private fun JsonObject.bool(key: String): Boolean {
        val value = getValue(key) as? JsonPrimitive ?: error("Invalid boolean")
        require(!value.isString && value.content in setOf("true", "false"))
        return value.content == "true"
    }
    private fun displayText(value: String): String = value.also { require(OfflineValidation.text(it, MAX_PAGE_BYTES, multiline = true)) }
    private fun JsonObject.display(key: String): String = displayText(string(key))
    private fun JsonObject.optionalDisplay(key: String): String = if (containsKey(key)) display(key) else ""
}
