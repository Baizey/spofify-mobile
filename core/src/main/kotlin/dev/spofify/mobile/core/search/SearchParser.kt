package dev.spofify.mobile.core.search

import dev.spofify.mobile.core.json.StrictJsonObject
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.library.PositiveMusicItemProjection
import java.time.Instant
import kotlinx.serialization.json.*

internal object SearchParser {
    private val integer = Regex("0|[1-9][0-9]*")
    private val timestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})")
    private val statuses = mapOf(
        SearchErrorCode.INVALID_SEARCH_REQUEST to 400,
        SearchErrorCode.SEARCH_EXPIRED to 410,
        SearchErrorCode.SEARCH_INDEX_NOT_READY to 503,
        SearchErrorCode.SEARCH_UNAVAILABLE to 503,
        SearchErrorCode.SEARCH_BUSY to 503,
    )

    fun page(bytes: ByteArray, limit: Int? = null, kind: MusicKind? = null, cursor: String? = null): SearchPage {
        val root = StrictJsonObject.parse(bytes, 2 * 1024 * 1024, maxDepth = 16)
        require(root.keys.containsAll(setOf("items", "resultCount", "expiresAt")))
        require(root.keys.all { it in setOf("items", "resultCount", "expiresAt", "nextCursor") })
        val count = root.getValue("resultCount") as? JsonPrimitive ?: error("Invalid count")
        require(!count.isString && integer.matches(count.content))
        val resultCount = count.content.toInt().also { require(it in 0..MAX_RETAINED_SEARCH_RESULTS) }
        val wireItems = root.getValue("items") as? JsonArray ?: error("Invalid items")
        require(wireItems.size <= 100 && wireItems.size <= resultCount)
        val items = wireItems.map(PositiveMusicItemProjection::decode)
        require(items.distinctBy { it.key }.size == items.size)
        if (kind != null) require(items.all { it.kind == kind })
        val next = root["nextCursor"]?.let {
            root.string("nextCursor").also { require(validCursor(it) && it != cursor) }
        }
        if (limit != null) {
            require(items.size == minOf(limit, resultCount))
            require((next != null) == (items.size < resultCount))
        } else {
            require(items.isNotEmpty()) // A continuation is only issued for a nonempty retained suffix.
        }
        if (next != null) require(items.isNotEmpty() && items.size < resultCount)
        val expires = root.string("expiresAt").also { require(timestamp.matches(it)) }
        // Snapshot metadata, not local-clock authority: never expire/retry based on device clock skew.
        return SearchPage(items, resultCount, next, Instant.parse(expires))
    }

    fun errorCode(bytes: ByteArray, status: Int): SearchErrorCode? = try {
        val root = StrictJsonObject.parse(bytes, 8 * 1024, maxDepth = 8)
        require(root.keys == setOf("code", "error"))
        root.string("error") // Validate but never retain diagnostic text.
        val code = root.string("code")
        statuses.entries.firstOrNull { it.key.name == code && it.value == status }?.key
    } catch (_: Exception) { null }

    fun validCursor(value: String): Boolean = value.isNotBlank() &&
        value.toByteArray(Charsets.UTF_8).size <= 512 && value.none(Char::isISOControl) &&
        value.toByteArray(Charsets.UTF_8).decodeToString() == value

    private fun JsonObject.string(key: String): String = (getValue(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: error("Invalid string")
}
