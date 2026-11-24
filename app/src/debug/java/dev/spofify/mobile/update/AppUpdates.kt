package dev.spofify.mobile.update

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope

/** The debug variant has no update UI, transport or install permission. */
@Suppress("UNUSED_PARAMETER")
internal class AppUpdates(app: Application, scope: CoroutineScope) {
    fun setOnline(allowed: Boolean) = Unit
    fun setForeground(foreground: Boolean) = Unit

    @Composable
    fun SettingsRow(activity: ComponentActivity, blocksInstall: () -> Boolean) = Unit
}
