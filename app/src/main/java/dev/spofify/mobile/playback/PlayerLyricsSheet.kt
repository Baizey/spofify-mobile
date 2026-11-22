package dev.spofify.mobile.playback

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spofify.mobile.core.content.LyricsState
import dev.spofify.mobile.core.playback.PlaybackTrack
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.duration

private data class LyricsFrame(val timed: Boolean, val index: Int?, val elapsedSecond: Long)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerLyricsSheet(
    track: PlaybackTrack,
    content: State<TrackContentState>,
    playback: State<PlayerPresentation>,
    onDismiss: () -> Unit,
) {
    val boundContent = content.value
    if (lyricEntry(boundContent) != LyricEntry.AVAILABLE) return
    val lyrics = boundContent.lyrics ?: return
    val frame by playback.project {
        val elapsedMs = (it.elapsed * 1000).toLong()
        val durationMs = (it.duration * 1000).toLong()
        val timed = lyrics.state == LyricsState.TIMED && lyrics.withinDuration(durationMs)
        LyricsFrame(timed, lyrics.currentLineIndex(elapsedMs, durationMs), if (timed) elapsedMs / 1000 else 0)
    }
    val (timed, index, elapsedSecond) = frame
    val list = rememberLazyListState()
    var following by remember(boundContent.binding, lyrics.recordingVersion) { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(index, following) {
        if (following && timed && index != null) list.animateScrollToItem(index + 1)
    }
    SpofifySheet(onDismiss) {
        LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionDown, Key.DirectionUp,
                        Key.PageDown, Key.PageUp, Key.MoveHome, Key.MoveEnd, Key.Spacebar)) following = false
                false
            }.pointerInput(boundContent.binding) {
                awaitPointerEventScope {
                    while (true) if (awaitPointerEvent().type == PointerEventType.Scroll) following = false
                }
            }, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            stickyHeader("heading") {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(bottom = 16.dp)) {
                    SheetHeading(track.name)
                    Text(track.artists.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (lyrics.state) {
                LyricsState.PLAIN -> item("plain") {
                    Text(lyrics.plainText, Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.25.sp, lineHeight = 30.38.sp))
                }
                LyricsState.TIMED -> itemsIndexed(lyrics.lines, key = { i, _ -> i }) { i, line ->
                    Text(line.text, Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        color = if (timed && i == index) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.25.sp, lineHeight = 30.38.sp,
                            fontWeight = if (timed && i == index) FontWeight.Bold else FontWeight.Normal))
                }
                else -> Unit
            }
        }
        if (timed) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(duration(elapsedSecond * 1000), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ following = true }, shape = SpofifySurfaces.control) { Text(if (following) "Following" else "Current line") }
        }
    }
}
