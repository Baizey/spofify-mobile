package dev.spofify.mobile.update

import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val REPOSITORY = "Baizey/spofify-mobile-releases"
private const val APK_NAME = "spofify-mobile-release.apk"
internal val LATEST_RELEASE = URI("https://api.github.com/repos/$REPOSITORY/releases/latest")
internal const val MAX_METADATA_BYTES = 128 * 1024
internal const val MAX_APK_BYTES = 150L * 1024 * 1024
private const val MAX_VERSION_CODE = 2_100_000_000L
private val TAG = Regex("mobile-v([1-9][0-9]{0,9})")
private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
private val DIGEST = Regex("sha256:([0-9a-fA-F]{64})")

internal class RejectedUpdate : IOException()
internal class TransientUpdateFailure : IOException()

internal data class GithubMobileRelease(
    val versionCode: Long,
    val size: Long,
    val sha256: ByteArray,
    val download: URI,
)

internal fun parseMobileRelease(bytes: ByteArray): GithubMobileRelease {
    try {
        if (bytes.size > MAX_METADATA_BYTES) throw RejectedUpdate()
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        checkJsonShape(text)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw RejectedUpdate()
        if (root["draft"] != JsonPrimitive(false) || root["prerelease"] != JsonPrimitive(false)) {
            throw RejectedUpdate()
        }
        val tag = root.string("tag_name")
        val versionCode = TAG.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it <= MAX_VERSION_CODE } ?: throw RejectedUpdate()
        val asset = (root["assets"] as? JsonArray)?.singleOrNull() as? JsonObject
            ?: throw RejectedUpdate()
        if (asset.string("name") != APK_NAME || asset.string("state") != "uploaded") {
            throw RejectedUpdate()
        }
        val size = (asset["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.content
            ?.takeIf(POSITIVE_INTEGER::matches)?.toLongOrNull()?.takeIf { it <= MAX_APK_BYTES }
            ?: throw RejectedUpdate()
        val hex = DIGEST.matchEntire(asset.string("digest"))?.groupValues?.get(1)
            ?: throw RejectedUpdate()
        val canonical = "https://github.com/$REPOSITORY/releases/download/$tag/$APK_NAME"
        if (asset.string("browser_download_url") != canonical) throw RejectedUpdate()
        return GithubMobileRelease(versionCode, size, ByteArray(32) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }, URI(canonical))
    } catch (_: IllegalArgumentException) {
        throw RejectedUpdate()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw RejectedUpdate()
    }
}

private fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw RejectedUpdate()

// JsonObject normally keeps the last duplicate (even when keys use escaped aliases). Bound nesting
// and reject duplicates before parsing. The JSON parser still owns the full grammar validation.
private fun checkJsonShape(source: String) {
    data class Frame(val keys: MutableSet<String>?, var expectingKey: Boolean)
    val stack = mutableListOf<Frame>()
    var index = 0
    while (index < source.length) {
        when (source[index]) {
            '{', '[' -> {
                if (stack.size >= 32) throw RejectedUpdate()
                val objectFrame = source[index] == '{'
                stack.add(Frame(if (objectFrame) mutableSetOf() else null, objectFrame))
            }
            '}', ']' -> {
                if (stack.isEmpty()) throw RejectedUpdate()
                stack.removeAt(stack.lastIndex)
            }
            ',' -> stack.lastOrNull()?.let { it.expectingKey = it.keys != null }
            '"' -> {
                val start = index++
                while (index < source.length && source[index] != '"') {
                    if (source[index].code < 32) throw RejectedUpdate()
                    if (source[index] == '\\') index++
                    index++
                }
                if (index >= source.length) throw RejectedUpdate()
                stack.lastOrNull()?.let { frame ->
                    if (frame.expectingKey) {
                        val key = Json.decodeFromString<String>(source.substring(start, index + 1))
                        if (!checkNotNull(frame.keys).add(key)) throw RejectedUpdate()
                        frame.expectingKey = false
                    }
                }
            }
            ':', ' ', '\t', '\r', '\n' -> Unit
            else -> {
                // JsonElement accepts arbitrary bare literals; restrict them to JSON primitives.
                val start = index++
                while (index < source.length && source[index] !in "{}[],:\" \t\r\n") index++
                val token = source.substring(start, index--)
                if (token != "true" && token != "false" && token != "null" && !JSON_NUMBER.matches(token)) {
                    throw RejectedUpdate()
                }
            }
        }
        index++
    }
    if (stack.isNotEmpty()) throw RejectedUpdate()
}
