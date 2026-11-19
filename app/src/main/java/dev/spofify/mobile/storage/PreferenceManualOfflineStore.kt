package dev.spofify.mobile.storage

import android.annotation.SuppressLint
import android.content.Context
import dev.spofify.mobile.core.auth.ManualOfflineStore
import java.io.IOException

/** Non-secret installation policy. Inject at composition time, separately from auth records. */
class PreferenceManualOfflineStore(context: Context) : ManualOfflineStore {
    private val prefs = context.getSharedPreferences("network_policy", Context.MODE_PRIVATE)
    override fun read(): Boolean = prefs.getBoolean("manual_offline", false)

    @SuppressLint("UseKtx")
    override fun write(enabled: Boolean) {
        if (!prefs.edit().putBoolean("manual_offline", enabled).commit()) throw IOException("Could not save network policy")
    }
}
