package dev.spofify.mobile

import android.app.Application
import dev.spofify.mobile.core.auth.AuthApiFactory
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.storage.EncryptedAuthStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root, not a feature controller. No global singleton service locator. */
class SpofifyApplication : Application() {
    lateinit var sessions: SessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val stores = EncryptedAuthStores(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpAuthApi.defaultClient()
        sessions = SessionRepository(stores.sessions, stores.logins, AuthApiFactory { origin ->
            OkHttpAuthApi(origin, http)
        }, scope)
        sessions.start()
    }
}
