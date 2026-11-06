package dev.spofify.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.playback.PlaybackFeedback
import dev.spofify.mobile.playback.nextPlaybackFeedback
import kotlinx.coroutines.delay

/** One presentation owner above both listening windows. Switching windows cannot restart a timer. */
@Stable
internal class PlaybackFeedbackPresentation {
    var current by mutableStateOf<PlaybackFeedback?>(null)
        private set
    private var seen = 0L
    private var announced = 0L
    fun claimAnnouncement(id: Long): Boolean {
        if (id <= announced) return false
        announced = id
        return true
    }
    fun offer(event: PlaybackFeedback) {
        if (event.id <= seen || !event.isCurrent()) return
        seen = event.id
        current = nextPlaybackFeedback(current, event)
    }
    fun consume(id: Long) { if (current?.id == id) current = null }
    fun discardSuccess() { current = null }
}

@Composable
internal fun PlaybackFeedbackTimer(presentation: PlaybackFeedbackPresentation, visible: Boolean = true,
    onConsumed: (Long) -> Unit) {
    val event = presentation.current
    val accessibility = LocalAccessibilityManager.current
    val consume by rememberUpdatedState(onConsumed)
    // One timer belongs to the presentation, not the root/expanded host mount.
    LaunchedEffect(event?.id, visible) {
        if (!visible) return@LaunchedEffect
        event ?: return@LaunchedEffect
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(event.baseTimeoutMillis,
            containsIcons = false, containsText = true, containsControls = false) ?: event.baseTimeoutMillis
        delay(timeout.coerceAtLeast(event.baseTimeoutMillis))
        presentation.consume(event.id)
        consume(event.id)
    }
}

/** No popup window, focus request, animation, icon, scrim or musical side effect. */
@Composable
internal fun PlaybackFeedbackHost(event: PlaybackFeedback?, modifier: Modifier = Modifier,
    announcementOwner: PlaybackFeedbackPresentation? = null) {
    event ?: return
    val announce = remember(event.id) { announcementOwner?.claimAnnouncement(event.id) ?: true }
    val status = Modifier.semantics { if (announce) liveRegion = LiveRegionMode.Polite }
    key(event.id) {
        Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 320.dp).then(status),
                shape = RoundedCornerShape(12.dp), color = Color(0xFF1A211D).copy(alpha = .90f),
                contentColor = Color.White, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                Text(event.message, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
