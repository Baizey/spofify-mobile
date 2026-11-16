package dev.spofify.mobile.core.palette

/** The complete coordinated v1 catalog. These IDs are never CSS or resource paths. */
enum class PaletteId(val id: String) { JADE("jade"), EMBER("ember"), VIOLET("violet") }

data class PaletteTokens(val primary: String, val onPrimary: String, val secondary: String, val tertiary: String)

data class PalettePreset(val id: PaletteId, val name: String, val dark: PaletteTokens, val light: PaletteTokens)

data class AccountPalette(
    val accountId: String,
    val paletteId: PaletteId,
    val revision: Long,
    val catalogVersion: Int,
    val defaultPaletteId: PaletteId,
    val presets: List<PalettePreset>,
) {
    val selected: PalettePreset get() = presets.single { it.id == paletteId }
}

interface PaletteApi {
    suspend fun read(): AccountPalette
    suspend fun choose(id: PaletteId, expectedRevision: Long): AccountPalette
}

enum class PaletteFailure { CONFLICT, CATALOG, INVALID, UNAVAILABLE, UNAUTHORIZED, INVALID_RESPONSE }

/**
 * No server diagnostics or transport causes escape this boundary. An uncertain
 * write needs a fresh read and a new explicit choice, never automatic replay.
 * Cancellation is propagated unchanged; a cancelled dispatched PUT is also uncertain.
 */
class PaletteException(
    val failure: PaletteFailure,
    val outcomeUncertain: Boolean = false,
) : Exception("Account palette request failed")
