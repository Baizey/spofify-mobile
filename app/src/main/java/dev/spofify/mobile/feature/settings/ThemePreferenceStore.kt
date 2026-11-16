package dev.spofify.mobile.feature.settings

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface ThemePreferenceStore {
    suspend fun read(): Boolean
    suspend fun write(light: Boolean)
}

/** A single device preference, separate from account/session storage. All disk work stays on IO. */
internal class SharedPreferencesThemePreferenceStore(
    private val preferences: () -> SharedPreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ThemePreferenceStore {
    constructor(context: Context) : this(preferences = context.applicationContext.let { app ->
        { app.getSharedPreferences("device_appearance", Context.MODE_PRIVATE) }
    })

    override suspend fun read(): Boolean = withContext(io) {
        preferences().getBoolean("light", false)
    }

    override suspend fun write(light: Boolean): Unit = withContext(io) {
        if (!preferences().edit().putBoolean("light", light).commit())
            throw IOException("Device theme persistence failed")
    }
}
