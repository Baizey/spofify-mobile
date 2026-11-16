package dev.spofify.mobile.feature.settings

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.palette.PaletteTokens

/** Check live auth at render time as well as at asynchronous controller publication. */
internal fun accountPaletteTokens(state: AccountPaletteState, identity: AccountIdentity?, light: Boolean): PaletteTokens? {
    if (identity == null || state.owner != identity) return null
    val confirmed = state.confirmed ?: return null
    val id = state.draft ?: confirmed.paletteId
    val selected = confirmed.presets.singleOrNull { it.id == id } ?: return null
    return if (light) selected.light else selected.dark
}
