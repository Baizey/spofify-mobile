package dev.spofify.mobile.feature.playlists

import java.text.Normalizer
import java.util.Locale
import java.security.SecureRandom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val recipeRandom = SecureRandom()

/** New occurrences get 128 random bits once. Editing and reordering never regenerate them. */
internal fun newRecipeSource(): JsonObject = buildJsonObject {
    val bytes = ByteArray(16).also(recipeRandom::nextBytes)
    put("slotId", "s_" + bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
    put("kind", "likes")
}

fun blankRecipe(): JsonObject = buildJsonObject {
    put("version", 1)
    put("sources", JsonArray(listOf(newRecipeSource())))
    put("duplicates", "unique")
    put("sort", "sourceOrder")
}

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.withField(key: String, value: JsonElement?): JsonObject =
    JsonObject(toMutableMap().apply { if (value == null) remove(key) else put(key, value) })

/** Invalid text deliberately becomes a JSON string: it remains editable but cannot be submitted. */
internal fun recipeTakeCount(text: String): JsonPrimitive =
    if (text.matches(Regex("[1-9][0-9]*")) && text.toLongOrNull()?.let { it in 1L..Int.MAX_VALUE.toLong() } == true) {
        JsonPrimitive(text.toInt())
    } else JsonPrimitive(text)

internal fun initialRecipePredicate(op: String): JsonObject = buildJsonObject {
    put("op", op)
    when (op) {
        "all", "any" -> put("args", JsonArray(listOf(initialRecipePredicate("availability"))))
        "not" -> put("arg", initialRecipePredicate("genre"))
        "genre" -> put("value", "")
        "genreKnown" -> put("value", false)
        "availability" -> put("value", "available")
        "membership" -> put("source", buildJsonObject { put("kind", "likes") })
        "artist", "album" -> put("${op}Id", "")
    }
}

internal fun changeRecipeSource(source: JsonObject, selection: String): JsonObject {
    val playlist = selection.startsWith("playlist:")
    return source.withField("kind", JsonPrimitive(if (playlist) "playlist" else selection))
        .withField("playlistId", if (playlist) JsonPrimitive(selection.removePrefix("playlist:")) else null)
}

/** Typed, bounded display references only; malformed deep trees are never enumerated. */
internal fun recipeMusicReferences(definition: JsonObject): List<Pair<String, String>> {
    val found = linkedSetOf<Pair<String, String>>()
    var visited = 0
    fun visit(value: JsonObject?, depth: Int) {
        if (value == null || depth > 4 || found.size >= 32 || visited++ >= 128) return
        when (val op = value.text("op")) {
            "artist", "album" -> value.text("${op}Id")?.takeIf { it.isNotEmpty() }?.let { found.add(op to it) }
            "all", "any" -> (value["args"] as? JsonArray)?.take(32)?.forEach { visit(it as? JsonObject, depth + 1) }
            "not" -> visit(value["arg"] as? JsonObject, depth + 1)
        }
    }
    visit(definition["include"] as? JsonObject, 1)
    visit(definition["exclude"] as? JsonObject, 1)
    return found.toList()
}

/** Bounded traversal for disclosure/rendering; 33 also represents over-depth input. */
internal fun recipePredicateLeafCount(definition: JsonObject): Int {
    fun count(value: JsonElement?, depth: Int): Int {
        if (value == null) return 0
        if (depth > 4) return 33
        val node = value as? JsonObject ?: return 1
        return when (node.text("op")) {
            "all", "any" -> {
                val args = node["args"] as? JsonArray ?: return 1
                var total = 0
                for (child in args.take(33)) {
                    total += count(child, depth + 1)
                    if (total > 32) return 33
                }
                if (args.size > 32) 33 else total
            }
            "not" -> count(node["arg"], depth + 1)
            else -> 1
        }
    }
    return (count(definition["include"], 1) + count(definition["exclude"], 1)).coerceAtMost(33)
}

/**
 * Local structural checks only. This cannot prove ownership, saved catalog identity, cycles,
 * referenced-graph budgets, or that evaluation will finish. The server remains authoritative.
 * JsonObject has already lost duplicate-key information; wire parsing must reject duplicates.
 */
fun recipeValidationError(definition: JsonObject): String? = try {
    RecipeStructure().validate(definition)
    null
} catch (error: IllegalArgumentException) {
    error.message ?: "The recipe is not structurally valid."
}

private class RecipeStructure {
    private var leaves = 0

    fun validate(value: JsonObject) {
        fields(value, setOf("version", "sources", "duplicates", "sort"), setOf("include", "exclude", "take"))
        check(integer(value["version"], 1L, 1L), "Recipe version must be 1.")
        val sources = value["sources"] as? JsonArray
        check(sources != null && sources.size in 1..4, "Choose between one and four sources.")
        val slots = mutableSetOf<String>()
        sources!!.forEach { element ->
            val source = obj(element, "A source is not valid.")
            val kind = source.text("kind")
            fields(source, if (kind == "playlist") setOf("slotId", "kind", "playlistId") else setOf("slotId", "kind"), setOf("take"))
            val slot = source.text("slotId")
            check(slot != null && slot.matches(Regex("s_[0-9a-f]{32}")), "A source has an invalid occurrence identity.")
            check(slots.add(slot!!), "Each source must have its own occurrence identity.")
            check(kind in setOf("likes", "playlist", "allSaved"), "Choose a supported music source.")
            if (kind == "playlist") check(id(source.text("playlistId")), "Select a saved collection.")
            if ("take" in source) take(source["take"])
        }
        check(value.text("duplicates") in setOf("unique", "keep"), "Choose how duplicates are handled.")
        check(value.text("sort") in setOf("sourceOrder", "title"), "Choose a supported result order.")
        if ("include" in value) predicate(value["include"], 1)
        if ("exclude" in value) predicate(value["exclude"], 1)
        if ("take" in value) take(value["take"])
    }

    private fun take(element: JsonElement?) {
        val value = obj(element, "Choose a valid first/last window.")
        fields(value, setOf("side", "count"))
        check(value.text("side") in setOf("first", "last"), "Choose first or last for the window.")
        check(integer(value["count"], 1, Int.MAX_VALUE.toLong()), "Use a whole window size between 1 and 2,147,483,647.")
    }

    private fun predicate(element: JsonElement?, depth: Int) {
        check(depth <= 4, "Conditions can be nested at most four levels deep.")
        val value = obj(element, "A condition is not valid.")
        val op = value.text("op")
        when (op) {
            "all", "any" -> {
                fields(value, setOf("op", "args"))
                val args = value["args"] as? JsonArray
                check(args != null && args.size in 1..32, "Use between one and 32 conditions in a group.")
                args!!.forEach { predicate(it, depth + 1) }
            }
            "not" -> {
                fields(value, setOf("op", "arg"))
                predicate(value["arg"], depth + 1)
            }
            else -> {
                leaves++
                check(leaves <= 32, "Use at most 32 leaf conditions across keep and exclude.")
                when (op) {
                    "genre" -> {
                        fields(value, setOf("op", "value"))
                        val raw = value.text("value")
                        check(raw != null && unicode(raw) && raw.none { Character.isISOControl(it) }, "Enter one exact genre label without controls.")
                        // Screening, not a substitute for the server's Unicode case-fold normalization.
                        val normalized = Normalizer.normalize(raw!!, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                            .split(Regex("[\\s\\p{Z}]+" )).filter { it.isNotEmpty() }.joinToString(" ")
                        check(normalized !in setOf("", "unknown", "undefined", "n/a") && ',' !in normalized,
                            "Enter one genre label; use Genre known / unknown for unknown genres.")
                        check(normalized.codePointCount(0, normalized.length) <= 100 && normalized.toByteArray(Charsets.UTF_8).size <= 200,
                            "Genre labels can contain at most 100 characters and 200 UTF-8 bytes.")
                    }
                    "genreKnown" -> {
                        fields(value, setOf("op", "value"))
                        val known = value["value"] as? JsonPrimitive
                        check(known != null && !known.isString && known.booleanOrNull != null, "Choose known or unknown genre.")
                    }
                    "availability" -> {
                        fields(value, setOf("op", "value"))
                        check(value.text("value") in setOf("available", "unavailable"), "Choose recorded audio availability.")
                    }
                    "artist", "album" -> {
                        fields(value, setOf("op", "${op}Id"))
                        check(id(value.text("${op}Id")), "Select a saved $op.")
                    }
                    "membership" -> {
                        fields(value, setOf("op", "source"))
                        val source = obj(value["source"], "Select a membership collection.")
                        when (source.text("kind")) {
                            "likes" -> fields(source, setOf("kind"))
                            "playlist" -> {
                                fields(source, setOf("kind", "playlistId"))
                                check(id(source.text("playlistId")), "Select a saved membership collection.")
                            }
                            else -> check(false, "Membership supports only likes or a saved collection.")
                        }
                    }
                    else -> check(false, "Choose a supported condition.")
                }
            }
        }
    }

    private fun fields(value: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        check(value.keys.containsAll(required) && value.keys.all { it in required || it in optional }, "The recipe contains missing or unsupported fields.")
    }

    private fun obj(value: JsonElement?, message: String): JsonObject {
        check(value is JsonObject, message)
        return value as JsonObject
    }

    private fun integer(value: JsonElement?, min: Long, max: Long): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        return !primitive.isString && primitive.content.matches(Regex("[1-9][0-9]*")) && primitive.content.toLongOrNull()?.let { it in min..max } == true
    }

    private fun id(value: String?): Boolean = value != null && value.isNotEmpty() && unicode(value) &&
        value.toByteArray(Charsets.UTF_8).size <= 512 && value.trim { it.isWhitespace() || Character.isSpaceChar(it) } == value &&
        value.none { Character.isISOControl(it) }

    private fun unicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isHighSurrogate(char)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(char)) return false
        }
        return true
    }

    private fun check(valid: Boolean, message: String) { require(valid) { message } }
}

/**
 * Instance-local request generation guard, usable for preview, saved evaluation and pickers.
 * Cancellation alone is not authority: invalidate immediately on edits, Back/account changes,
 * save, or cancel. Keep rows/cursors outside this class; clear their authority on invalidation.
 */
class RecipeAuthority {
    private var generation = 0L
    var pending: Boolean = false
        private set
    var stale: Boolean = true
        private set

    @Synchronized fun begin(): Long {
        generation++
        pending = true
        stale = true
        return generation
    }

    @Synchronized fun isCurrent(ticket: Long): Boolean = pending && ticket == generation

    @Synchronized fun complete(ticket: Long): Boolean {
        if (!isCurrent(ticket)) return false
        pending = false
        stale = false
        return true
    }

    @Synchronized fun fail(ticket: Long): Boolean {
        if (!isCurrent(ticket)) return false
        pending = false
        stale = true
        return true
    }

    @Synchronized fun invalidate() {
        generation++
        pending = false
        stale = true
    }
}
