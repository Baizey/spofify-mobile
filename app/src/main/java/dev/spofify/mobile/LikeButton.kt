package dev.spofify.mobile

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun LikeButton(liked: Boolean?, pending: Boolean, failed: Boolean, toggle: () -> Unit, retry: () -> Unit) {
    val label = when {
        pending -> "Saving like"
        liked == null && failed -> "Retry like status"
        liked == null -> "Checking like status"
        liked -> "Unlike song"
        else -> "Like song"
    }
    IconButton(onClick = { if (liked == null) retry() else toggle() },
        enabled = !pending && (liked != null || failed), modifier = Modifier.semantics { contentDescription = label }) {
        when {
            pending || (liked == null && !failed) -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            liked == null -> Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary)
            else -> Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
