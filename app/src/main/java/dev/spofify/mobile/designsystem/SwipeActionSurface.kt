package dev.spofify.mobile.designsystem

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Release-only actions: crossing a threshold or cancelling a pointer never dispatches a command. */
@Composable
fun SwipeActionSurface(
    binding: Any,
    onRight: (() -> Unit)?,
    onLeft: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    excludeEnd: Dp = 48.dp,
    slideOutOnCommit: Boolean = false,
    slideOutOnLeft: Boolean = false,
    background: (@Composable BoxScope.(offset: Float, width: Float) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentBinding by rememberUpdatedState(binding)
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val exclusion = with(density) { excludeEnd.toPx() }
    val lockDistance = with(density) { 10.dp.toPx() }
    val scale = density.density
    DisposableEffect(binding, onRight != null, onLeft != null) {
        onDispose {
            settling?.cancel()
            offset = 0f
        }
    }
    Box(modifier.onSizeChanged { width = it.width }.pointerInput(binding, width, onRight != null, onLeft != null) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (width == 0 || down.position.x > width - exclusion || (right == null && left == null)) return@awaitEachGesture
                settling?.cancel()
                offset = 0f
                val capturedBinding = binding
                val start = down.position
                var dragging = false
                var cancelled = false
                var peak = 0f
                var sign = 0f
                var dx = 0f
                var dy = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (pointer == null || event.changes.count { it.pressed } > 1 || currentBinding != capturedBinding) {
                        cancelled = true
                        break
                    }
                    dx = pointer.position.x - start.x
                    dy = pointer.position.y - start.y
                    if (pointer.isConsumed) {
                        cancelled = true
                        break
                    }
                    if (!dragging) {
                        if (abs(dy) > lockDistance && abs(dy) > abs(dx)) {
                            cancelled = true
                            break
                        }
                        if (abs(dx) >= lockDistance && abs(dx) >= abs(dy) * 1.3f) {
                            dragging = true
                            sign = if (dx < 0) -1f else 1f
                        }
                    }
                    if (dragging) {
                        if (cancelsHorizontalSwipe(dx / scale, dy / scale, peak / scale, sign)) {
                            cancelled = true
                            pointer.consume()
                            break
                        }
                        peak = max(peak, abs(dx))
                        val supported = if (dx > 0) right != null else left != null
                        offset = if (supported) dx.coerceIn(-width.toFloat(), width.toFloat())
                            else (dx * .15f).coerceIn(-12.dp.toPx(), 12.dp.toPx())
                        pointer.consume()
                    }
                    if (!pointer.pressed) break
                }
                if (dragging) {
                    val direction = if (cancelled) null else releasedSwipeDirection(width / scale, dx / scale, dy / scale, peak / scale, sign)
                    val action = when (direction) { 1 -> right; -1 -> left; else -> null }
                    val target = if (action != null && (slideOutOnCommit || dx < 0 && slideOutOnLeft)) {
                        if (dx > 0) width.toFloat() else -width.toFloat()
                    } else 0f
                    settling = scope.launch {
                        animate(offset, target, animationSpec = tween(if (action != null) 180 else 160)) { value, _ -> offset = value }
                        if (action != null && currentBinding == capturedBinding && (if (dx > 0) right else left) != null) action()
                        offset = 0f
                    }
                }
            }
        } finally {
            settling?.cancel()
            offset = 0f
        }
    }) {
        if (background != null) background(offset, width.toFloat())
        else if (offset != 0f) {
            Box(Modifier.matchParentSize().background(if (offset > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 18.dp),
                contentAlignment = if (offset > 0) Alignment.CenterStart else Alignment.CenterEnd) {
                SpofifyIcon(if (offset > 0) SpofifyGlyph.Queue else SpofifyGlyph.Trash, null,
                    Modifier.size(24.dp), tint = if (offset > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = offset }, content = content)
    }
}
