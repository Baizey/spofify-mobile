package dev.spofify.mobile

import android.app.Application
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.OkHttpAuthApiFactory
import dev.spofify.mobile.storage.EncryptedAuthStores
import dev.spofify.mobile.playback.PlaybackOwner
import dev.spofify.mobile.core.palette.PaletteClient
import dev.spofify.mobile.feature.settings.AccountPaletteController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root, not a feature controller. No global singleton service locator. */
class SpofifyApplication : Application() {
    lateinit var sessions: SessionRepository
        private set

    lateinit var playback: PlaybackOwner
        private set

    internal lateinit var palettes: AccountPaletteController
        private set

    override fun onCreate() {
        super.onCreate()
        val stores = EncryptedAuthStores(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessions = SessionRepository(stores.sessions, stores.logins, OkHttpAuthApiFactory(), scope,
            manualOfflineStore = dev.spofify.mobile.storage.PreferenceManualOfflineStore(this))
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        palettes = AccountPaletteController(sessions.state, { identity ->
            val grant = requireNotNull(sessions.onlineGrant.value?.takeIf { it.identity == identity })
            val client = PaletteClient(sessions, identity)
            object : dev.spofify.mobile.core.palette.PaletteApi {
                override suspend fun read() = sessions.withOnlineGrant(grant) { client.read() }
                override suspend fun choose(id: dev.spofify.mobile.core.palette.PaletteId, expectedRevision: Long) =
                    sessions.withOnlineGrant(grant) { client.choose(id, expectedRevision) }
            }
        }, mainScope, sessions.onlineGrant)
        playback = PlaybackOwner(this, sessions, mainScope)
        palettes.start()
        playback.start()
        sessions.start()
    }
}
