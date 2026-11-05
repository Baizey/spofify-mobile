package dev.spofify.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.feature.library.MusicCredits

/** The actual More sheet body: every action and credit stays reachable without shrinking targets. */
@Composable
internal fun MusicActionsContent(
    item: LibraryItem,
    liked: Boolean?,
    likePending: Boolean,
    likeFailed: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleLike: () -> Unit,
    onRetryLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    contextualLabel: String = "Play now",
    onContextual: (() -> Unit)? = null,
    onQueueLow: (() -> Unit)? = null,
) {
    // Keep a finite scroll viewport even if a sheet measures its body with loose constraints.
    // The sheet's own smaller constraints (safe areas, drag handle, IME) still take precedence.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(
        modifier.fillMaxWidth().heightIn(max = windowHeight).testTag("music-actions")
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.name, style = MaterialTheme.typography.titleLarge)
        MusicAction("Play now", enabled = item.playable, onClick = onPlay)
        MusicAction("Play next", enabled = item.playable, onClick = onPlayNext)
        MusicAction("Queue High", enabled = item.playable, onClick = onContextual ?: onEnqueue)
        onQueueLow?.let { action -> MusicAction("Queue Low", enabled = item.playable, onClick = action) }
        if (item.kind == "track") {
            val likeLabel = when {
                likePending -> "Saving like"
                liked == null && likeFailed -> "Retry like status"
                liked == null -> "Checking like status"
                liked -> "Unlike song"
                else -> "Like song"
            }
            MusicAction(likeLabel, enabled = !likePending && (liked != null || likeFailed),
                onClick = { if (liked == null) onRetryLike() else onToggleLike() })
            MusicAction("Add to playlist", onClick = onAddToPlaylist)
        }
        MusicAction("Open details", onClick = { onOpenItem(item) })
        MusicCredits(item, onOpenItem)
    }
}

@Composable
private fun MusicAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, Modifier.fillMaxWidth())
    }
}
