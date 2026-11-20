package dev.spofify.mobile.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.designsystem.SpofifySwitchIndicator
import dev.spofify.mobile.playback.NativePlayer

@Composable
internal fun CrossfadeSetting(player: NativePlayer) {
    val enabled by player.crossfadeEnabled.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp)
        .toggleable(enabled, role = Role.Switch, onValueChange = player::setCrossfadeEnabled)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Crossfade")
            Text("Overlap tracks by 5 seconds at natural transitions when the next track is ready.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SpofifySwitchIndicator(enabled, enabled = true)
    }
}
