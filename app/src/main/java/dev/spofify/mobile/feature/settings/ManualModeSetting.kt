package dev.spofify.mobile.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.designsystem.SpofifySwitchIndicator
import dev.spofify.mobile.playback.PlaybackOwner
import dev.spofify.mobile.playback.project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ManualModeSetting(identity: AccountIdentity, sessions: SessionRepository, owner: PlaybackOwner) {
    val offline by sessions.manualOffline.collectAsStateWithLifecycle()
    val native by owner.state.collectAsStateWithLifecycle()
    val target = native?.takeIf { it.identity == identity }?.target?.collectAsStateWithLifecycle()?.value
    val autoOffline = target?.observeState()?.project { it.view.offline || it.view.superseded }?.value == true
    val scope = rememberCoroutineScope()
    var writing by remember(identity) { mutableStateOf(false) }
    var failed by remember(identity) { mutableStateOf(false) }
    fun setOffline(next: Boolean) {
        if (!writing) {
            writing = true; failed = false
            scope.launch {
                try { owner.switch(identity, next) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
                finally { writing = false }
            }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = offline, enabled = !writing, role = Role.Switch, onValueChange = ::setOffline),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Offline mode", Modifier.weight(1f))
        SpofifySwitchIndicator(offline, !writing)
    }
    if (!offline && autoOffline) TextButton({ setOffline(false) }, enabled = !writing) { Text("Go online") }
    if (failed) Text("Couldn’t save offline mode.", style = MaterialTheme.typography.bodySmall)
}
