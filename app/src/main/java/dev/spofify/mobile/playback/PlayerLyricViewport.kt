package dev.spofify.mobile.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.content.LyricsState
import kotlinx.coroutines.launch

/** Keep lyric reads accessible when jobs add files after the player opens. */
internal fun lyricSheetEntryLabel(content: TrackContentState): String? {
    if (content.lyricsFailure != null) return "Lyrics"
    return when (content.lyrics?.state) {
        LyricsState.PLAIN, LyricsState.TIMED -> "View lyrics"
        null -> null
        else -> "Lyrics"
    }
}

internal data class LyricViewportKey(
    val trackId: String?,
    val binding: String,
    val recordingVersion: String,
    val sourceGeneration: String,
    val index: Int,
    val startMs: Long,
    val text: String,
)

/** Progress is deliberately absent: equal text at a different timed index still gets a fresh viewport. */
internal fun lyricViewportKey(content: TrackContentState, elapsedMs: Long, durationMs: Long): LyricViewportKey? {
    val lyrics = content.lyrics ?: return null
    val index = content.currentLineIndex(elapsedMs, durationMs) ?: return null
    val line = lyrics.lines[index]
    if (line.text.isBlank()) return null
    return LyricViewportKey(content.trackId, content.binding, lyrics.recordingVersion, lyrics.sourceGeneration, index, line.startMs, line.text)
}

internal data class LyricOverflow(val above: Boolean, val below: Boolean)
internal fun lyricOverflow(position: Int, maximum: Int): LyricOverflow {
    val end = maximum.coerceAtLeast(0)
    val current = position.coerceIn(0, end)
    return LyricOverflow(current > 0, current < end)
}

/** Fixed-height native text scroll. It has no player-dismiss gesture and never shortens the lyric text. */
@Composable
internal fun PlayerLyricViewport(line: LyricViewportKey, height: Dp, onOpen: () -> Unit) {
    key(line) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val page = with(density) { height.roundToPx() }.coerceAtLeast(1)
        val step = with(density) { playerSupportingTextStyle.lineHeight.toPx() }.toInt().coerceAtLeast(1)
        Box(Modifier.fillMaxWidth().height(height).clipToBounds()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else {
                    val target = when (event.key) {
                        Key.DirectionUp -> scroll.value - step
                        Key.DirectionDown -> scroll.value + step
                        Key.PageUp -> scroll.value - page
                        Key.PageDown -> scroll.value + page
                        Key.MoveHome -> 0
                        Key.MoveEnd -> scroll.maxValue
                        else -> null
                    }
                    if (target == null || scroll.maxValue == 0) false else {
                        scope.launch { scroll.scrollTo(target.coerceIn(0, scroll.maxValue)) }
                        true
                    }
                }
            }
            .clickable(role = Role.Button, onClickLabel = "Open full lyrics", onClick = onOpen)
            .semantics {
                val overflow = lyricOverflow(scroll.value, scroll.maxValue)
                stateDescription = when {
                    overflow.above && overflow.below -> "More lyric text above and below. Scroll to read."
                    overflow.below -> "More lyric text below. Scroll to read."
                    overflow.above -> "More lyric text above. Scroll to read."
                    else -> "Current lyric"
                }
            }) {
            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = 8.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text(line.text, style = MaterialTheme.typography.bodyLarge.merge(playerSupportingTextStyle),
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}
