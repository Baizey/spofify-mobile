package dev.spofify.mobile

import android.app.Application
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.OkHttpAuthApiFactory
import dev.spofify.mobile.storage.EncryptedAuthStores
import dev.spofify.mobile.playback.PlaybackOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root, not a feature controller. No global singleton service locator. */
class SpofifyApplication : Application() {
    lateinit var sessions: SessionRepository
        private set

    lateinit var playback: PlaybackOwner
        private set

    override fun onCreate() {
        super.onCreate()
        val stores = EncryptedAuthStores(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessions = SessionRepository(stores.sessions, stores.logins, OkHttpAuthApiFactory(), scope,
            manualOfflineStore = dev.spofify.mobile.storage.PreferenceManualOfflineStore(this))
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        playback = PlaybackOwner(this, sessions, mainScope)
        playback.start()
        sessions.start()
    }
}
