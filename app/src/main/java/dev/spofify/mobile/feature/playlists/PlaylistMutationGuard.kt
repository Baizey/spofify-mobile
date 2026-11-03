package dev.spofify.mobile.feature.playlists

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Root navigation can keep a publication mounted until its acknowledgement/recovery state is known. */
@Composable
internal fun ReportPlaylistMutationPending(pending: Boolean, onChange: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onChange)
    DisposableEffect(pending) {
        callback(pending)
        onDispose { callback(false) }
    }
}
