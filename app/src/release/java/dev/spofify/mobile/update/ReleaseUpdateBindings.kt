package dev.spofify.mobile.update

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.R
import dev.spofify.mobile.feature.settings.SettingsLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class AppUpdates(app: Application, scope: CoroutineScope) {
    private val installer = AndroidUpdateInstaller(app)
    private val engine = installer.installedVersionCode?.let { installedCode ->
        ReleaseUpdateEngine(scope, File(app.cacheDir, UPDATE_DIRECTORY), installedCode, installer::verify)
    }

    fun setOnline(allowed: Boolean) { engine?.setOnline(allowed) }
    fun setForeground(foreground: Boolean) { engine?.setForeground(foreground) }

    @Composable
    fun SettingsRow(activity: ComponentActivity, blocksInstall: () -> Boolean) {
        val owner = engine
        if (owner == null) {
            UpdateStatusRow(R.string.updates_unavailable)
            return
        }
        val state by owner.status.collectAsStateWithLifecycle()
        val current = state
        if (current !is UpdateStatus.Ready) {
            UpdateStatusRow(when (current) {
                UpdateStatus.Unavailable -> R.string.updates_paused
                UpdateStatus.Idle, UpdateStatus.Checking -> R.string.updates_checking
                UpdateStatus.Downloading -> R.string.updates_downloading
                UpdateStatus.UpToDate -> R.string.updates_current
                UpdateStatus.Failure -> R.string.updates_unavailable
                is UpdateStatus.Ready -> error("Handled separately")
            })
            return
        }
        val apk = current.apk
        val scope = rememberCoroutineScope()
        var opening by remember(apk) { mutableStateOf(false) }
        var notice by remember(apk) { mutableStateOf<Int?>(null) }
        if (opening) UpdateStatusRow(R.string.updates_preparing)
        else SettingsLink(stringResource(R.string.updates_install), notice?.let { stringResource(it) }) {
            if (opening || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@SettingsLink
            if (blocksInstall()) {
                notice = R.string.updates_pause_playback
                return@SettingsLink
            }
            notice = null
            opening = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Register on this tap, before any suspension. A pause permanently retires
                // the gesture even if the same Activity resumes before preparation returns.
                val gestureScope = this
                val cancelOnPause = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE) gestureScope.cancel()
                }
                activity.lifecycle.addObserver(cancelOnPause)
                try {
                    val verified = owner.revalidate(apk) ?: return@launch
                    val intent = withContext(Dispatchers.IO) { installer.prepare(verified.file) }
                    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                    if (blocksInstall()) {
                        notice = R.string.updates_pause_playback
                        return@launch
                    }
                    // Only this explicit tap reaches the installer. Offline transition, stale
                    // generation or leaving the foreground can still fence it after revalidation.
                    owner.withCurrentApk(verified) {
                        if (intent == null || !installer.open(activity, intent)) {
                            notice = R.string.updates_installer_unavailable
                        }
                    }
                } finally {
                    activity.lifecycle.removeObserver(cancelOnPause)
                    opening = false
                }
            }
        }
    }
}

@Composable
private fun UpdateStatusRow(@StringRes status: Int) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(stringResource(R.string.app_updates))
                Text(stringResource(status), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
