package dev.spofify.mobile

import android.app.Application
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.OkHttpAuthApiFactory
import dev.spofify.mobile.storage.EncryptedAuthStores
import dev.spofify.mobile.playback.PlaybackOwner
import dev.spofify.mobile.core.palette.PaletteClient
import dev.spofify.mobile.feature.settings.AccountPaletteController
import dev.spofify.mobile.feature.settings.DeviceThemeController
import dev.spofify.mobile.feature.settings.SharedPreferencesThemePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root, not a feature controller. No global singleton service locator. */
class SpofifyApplication : Application() {
    lateinit var sessions: SessionRepository
        private set

    lateinit var playback: PlaybackOwner
        private set

    internal lateinit var theme: DeviceThemeController
        private set

    internal lateinit var palettes: AccountPaletteController
        private set

    override fun onCreate() {
        super.onCreate()
        val stores = EncryptedAuthStores(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessions = SessionRepository(stores.sessions, stores.logins, OkHttpAuthApiFactory(), scope)
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        theme = DeviceThemeController(SharedPreferencesThemePreferenceStore(this), mainScope)
        palettes = AccountPaletteController(sessions.state, { identity -> PaletteClient(sessions, identity) }, mainScope)
        playback = PlaybackOwner(this, sessions, mainScope)
        theme.start()
        palettes.start()
        playback.start()
        sessions.start()
    }
}
