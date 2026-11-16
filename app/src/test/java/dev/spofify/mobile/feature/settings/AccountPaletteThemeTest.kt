package dev.spofify.mobile.feature.settings

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.palette.*
import org.junit.Assert.*
import org.junit.Test

class AccountPaletteThemeTest {
    private val owner = AccountIdentity("https://music.example", "account", "listener", 1)
    private val jade = PaletteTokens("#66e3b4", "#08271d", "#91bfff", "#dfbd78")
    private val jadeLight = PaletteTokens("#08704e", "#ffffff", "#2759a4", "#77551b")
    private val ember = PaletteTokens("#ffad89", "#31170d", "#c4b0ff", "#a4d7bd")
    private val confirmed = AccountPalette(owner.accountId, PaletteId.JADE, 2, 1, PaletteId.JADE, listOf(
        PalettePreset(PaletteId.JADE, "Jade", jade, jadeLight),
        PalettePreset(PaletteId.EMBER, "Ember", ember, jadeLight),
    ))

    @Test fun signedOutDifferentOriginAccountAndSessionCannotRenderCachedColours() {
        val state = AccountPaletteState(owner = owner, confirmed = confirmed, draft = PaletteId.EMBER)
        assertNull(accountPaletteTokens(state, null, false))
        assertNull(accountPaletteTokens(state, owner.copy(origin = "https://other.example"), false))
        assertNull(accountPaletteTokens(state, owner.copy(accountId = "other"), false))
        assertNull(accountPaletteTokens(state, owner.copy(incarnation = 2), false))
    }

    @Test fun liveOwnerUsesConfirmedModeOrCurrentDraftWithoutWritingAnything() {
        val state = AccountPaletteState(owner = owner, confirmed = confirmed)
        assertEquals(jade, accountPaletteTokens(state, owner, false))
        assertEquals(jadeLight, accountPaletteTokens(state, owner, true))
        assertEquals(ember, accountPaletteTokens(state.copy(draft = PaletteId.EMBER), owner, false))
    }

    @Test fun unconfirmedOwnerOrUnknownDraftHasNoEffectiveAccountTokens() {
        assertNull(accountPaletteTokens(AccountPaletteState(owner = owner), owner, false))
        assertNull(accountPaletteTokens(AccountPaletteState(owner = owner, confirmed = confirmed, draft = PaletteId.VIOLET), owner, false))
    }
}
