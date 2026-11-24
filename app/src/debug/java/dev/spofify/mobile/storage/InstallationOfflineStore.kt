package dev.spofify.mobile.storage

import android.content.Context

internal fun installationOfflineStore(context: Context) = PreferenceManualOfflineStore(context)
