package dev.spofify.mobile.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spofify.mobile.core.content.TrackWaveform
import dev.spofify.mobile.feature.library.duration
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSeek(state: PlayerPresentation, binding: String, content: TrackContentState, onSeek: (Double) -> Unit) {
    val seconds = state.elapsed
    val total = state.duration
    val waveform = content.waveformForDuration(total)
    key(binding) {
        var preview by remember { mutableStateOf<Float?>(null) }
        var focused by remember { mutableStateOf(false) }
        val value = (preview ?: seconds.toFloat()).coerceIn(0f, total.toFloat().coerceAtLeast(1f))
        val enabled = state.ready && total > 0
        val active = MaterialTheme.colorScheme.primary
        val inactive = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.fillMaxWidth()) {
            Slider(value, onValueChange = { preview = it }, enabled = enabled,
                valueRange = 0f..total.toFloat().coerceAtLeast(1f),
                onValueChangeFinished = { preview?.let { onSeek(it.toDouble()) }; preview = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).onFocusChanged { focused = it.hasFocus }.semantics {
                    stateDescription = "${duration((value * 1000).toLong())} of ${duration((total * 1000).toLong())}"
                    contentDescription = if (waveform == null) "Playback position" else "Waveform seek"
                },
                thumb = {
                    if (waveform == null) Box(Modifier.size(14.dp).background(if (enabled) active else inactive, CircleShape))
                    else Spacer(Modifier.size(0.dp))
                },
                track = {
                    if (waveform == null) Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                        val x = size.width * value / total.toFloat().coerceAtLeast(1f)
                        drawLine(inactive.copy(alpha = .5f), Offset.Zero.copy(y = size.height / 2),
                            Offset(size.width, size.height / 2), strokeWidth = 3.dp.toPx())
                        drawLine(active, Offset(0f, size.height / 2), Offset(x, size.height / 2), strokeWidth = 3.dp.toPx())
                    } else WaveformTrack(waveform, value / total.toFloat().coerceAtLeast(1f), preview != null, focused)
                })
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f).playerTimeLabelColumn(end = false)) {
                    Text(duration(((preview?.toDouble() ?: seconds) * 1000).toLong()),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.95.sp), color = inactive)
                }
                Box(Modifier.weight(1f).playerTimeLabelColumn(end = true)) {
                    Text(duration((total * 1000).toLong()),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.95.sp), color = inactive)
                }
            }
        }
    }
}

// Each label can use half the row for large fonts/long durations, while ordinary
// labels center above the first/last of the five equal transport columns.
internal fun playerTimeLabelOffset(halfWidth: Int, textWidth: Int, end: Boolean): Int {
    require(halfWidth >= 0 && textWidth >= 0)
    val center = halfWidth * if (end) .8f else .2f
    return (center - textWidth / 2f).roundToInt().coerceIn(0, (halfWidth - textWidth).coerceAtLeast(0))
}

private fun Modifier.playerTimeLabelColumn(end: Boolean): Modifier = layout { measurable, constraints ->
    val label = measurable.measure(constraints.copy(minWidth = 0))
    layout(constraints.maxWidth, label.height) {
        label.placeRelative(playerTimeLabelOffset(constraints.maxWidth, label.width, end), 0)
    }
}

/** Max pooling preserves peaks and original amplitude; there is no per-song gain/autoscaling. */
internal fun displayWaveformBins(bins: List<Int>, maximum: Int = 56): List<Int> {
    require(maximum > 0)
    if (bins.size <= maximum) return bins
    return List(maximum) { index ->
        val start = index * bins.size / maximum
        val end = (index + 1) * bins.size / maximum
        var peak = 0
        for (sample in start until end) peak = max(peak, bins[sample])
        peak
    }
}

@Composable
private fun WaveformTrack(waveform: TrackWaveform, progress: Float, seeking: Boolean, focused: Boolean) {
    val bins = remember(waveform) { displayWaveformBins(waveform.bins) }
    val played = if (seeking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val unplayed = if (seeking) MaterialTheme.colorScheme.tertiary.copy(alpha = .8f)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f)
    val ink = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.background
    val focus = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        if (bins.isEmpty()) return@Canvas
        val step = size.width / bins.size
        val cursor = (size.width * progress).coerceIn(size.width * 4f / 560, size.width * 556f / 560)
        bins.forEachIndexed { index, amplitude ->
            val height = amplitude / 255f * size.height * 34f / 48f
            drawRect(if (index * step < cursor) played else unplayed,
                topLeft = Offset(index * step + step * .3f, size.height * 22f / 48f - height / 2),
                size = Size(step * .4f, height))
        }
        drawLine(if (focused) focus else ink, Offset(cursor, size.height * 3f / 48f), Offset(cursor, size.height * 45f / 48f),
            strokeWidth = (if (seeking || focused) 4.dp else 2.dp).toPx())
        val radius = (if (focused) 5.dp else 3.dp).toPx()
        drawCircle(if (focused) focus else background, radius, Offset(cursor, size.height * 3f / 48f))
        drawCircle(ink, radius, Offset(cursor, size.height * 3f / 48f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}
