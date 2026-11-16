package dev.spofify.mobile.core.palette

import java.util.Locale
import dev.spofify.mobile.core.json.StrictJsonObject
import kotlinx.serialization.json.*

/** Bounded positive projection of the immutable v1 catalog; no CSS interpreter or fallback. */
internal object PaletteParser {
    const val MAX_REVISION = 9_007_199_254_740_991L
    private const val MAX_BODY = 16 * 1024
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private val hex = Regex("#[0-9a-fA-F]{6}")
    private val catalog = listOf(
        PalettePreset(PaletteId.JADE, "Jade",
            PaletteTokens("#66e3b4", "#08271d", "#91bfff", "#dfbd78"),
            PaletteTokens("#08704e", "#ffffff", "#2759a4", "#77551b")),
        PalettePreset(PaletteId.EMBER, "Ember",
            PaletteTokens("#ffad89", "#31170d", "#c4b0ff", "#a4d7bd"),
            PaletteTokens("#aa431b", "#ffffff", "#654199", "#23644a")),
        PalettePreset(PaletteId.VIOLET, "Violet",
            PaletteTokens("#c8b5ff", "#22183c", "#6de2d5", "#e8b4c5"),
            PaletteTokens("#6442a5", "#ffffff", "#0c6d67", "#8b465e")),
    )

    fun parse(bytes: ByteArray, expectedAccountId: String): AccountPalette = try {
        val root = document(bytes, MAX_BODY)
        require(root.keys == setOf("accountId", "paletteId", "revision", "catalogVersion", "defaultPaletteId", "presets"))
        val accountId = root.string("accountId")
        require(accountId.isNotEmpty() && accountId == expectedAccountId)
        val revision = root.number("revision").also { require(it in 0..MAX_REVISION) }
        val version = root.number("catalogVersion").also { require(it in 1..MAX_REVISION) }
        if (version != 1L) throw PaletteException(PaletteFailure.CATALOG)
        val id = paletteId(root.string("paletteId"))
        val default = paletteId(root.string("defaultPaletteId"))
        require(default == PaletteId.JADE)
        val entries = root.getValue("presets") as? JsonArray ?: error("Invalid catalog")
        require(entries.size == catalog.size)
        val presets = entries.map { element ->
            val preset = element as? JsonObject ?: error("Invalid preset")
            require(preset.keys == setOf("id", "name", "dark", "light"))
            PalettePreset(paletteId(preset.string("id")), preset.string("name"),
                tokens(preset.getValue("dark")), tokens(preset.getValue("light"))).also { parsed ->
                require(parsed == catalog.single { it.id == parsed.id })
            }
        }
        require(presets.map { it.id }.toSet() == PaletteId.entries.toSet())
        AccountPalette(accountId, id, revision, 1, default, presets)
    } catch (failure: PaletteException) {
        throw failure
    } catch (_: Exception) {
        throw PaletteException(PaletteFailure.INVALID_RESPONSE)
    }

    /** Only known codes are used by the client; error text never leaves this parser. */
    fun errorCode(bytes: ByteArray): String? = try {
        val root = document(bytes, 4096)
        require(root.keys == setOf("code", "error"))
        root.string("error")
        root.string("code")
    } catch (_: Exception) { null }

    private fun tokens(element: JsonElement): PaletteTokens {
        val value = element as? JsonObject ?: error("Invalid tokens")
        require(value.keys == setOf("primary", "onPrimary", "secondary", "tertiary"))
        fun token(key: String): String = value.string(key).also { require(hex.matches(it)) }.lowercase(Locale.ROOT)
        return PaletteTokens(token("primary"), token("onPrimary"), token("secondary"), token("tertiary"))
    }

    private fun paletteId(value: String): PaletteId = PaletteId.entries.single { it.id == value }
    private fun JsonObject.string(key: String): String = (getValue(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: error("Invalid string")
    private fun JsonObject.number(key: String): Long {
        val value = getValue(key) as? JsonPrimitive ?: error("Invalid integer")
        require(!value.isString && integer.matches(value.content))
        return value.content.toLong()
    }

    private fun document(bytes: ByteArray, limit: Int): JsonObject =
        StrictJsonObject.parse(bytes, limit, maxDepth = 16)
}
