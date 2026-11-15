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
import androidx.compose.ui.draw.clipToBounds
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
    val currentSlideOutOnCommit by rememberUpdatedState(slideOutOnCommit)
    val currentSlideOutOnLeft by rememberUpdatedState(slideOutOnLeft)
    val ownership = remember { SwipeGestureOwnership() }
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val exclusion = with(density) { excludeEnd.toPx() }
    val lockDistance = with(density) { 10.dp.toPx() }
    val scale = density.density
    DisposableEffect(binding, onRight != null, onLeft != null) {
        onDispose {
            ownership.invalidate()
            settling?.cancel()
            settling = null
            offset = 0f
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { width = it.width }
        .pointerInput(binding, width, onRight != null, onLeft != null, scale, exclusion) {
        var captured: CapturedSwipe? = null
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A new touch cancels the prior settle even when it lands on the excluded action slot.
                ownership.invalidate()
                settling?.cancel()
                settling = null
                offset = 0f
                captured = null
                val capturedRight = right
                val capturedLeft = left
                val capturedWidth = width.toFloat()
                if (capturedWidth == 0f || down.position.x > capturedWidth - exclusion ||
                    (capturedRight == null && capturedLeft == null)) return@awaitEachGesture
                val gesture = ownership.begin(currentBinding, capturedRight, capturedLeft)
                captured = gesture
                val capturedSlideOut = currentSlideOutOnCommit
                val capturedSlideOutLeft = currentSlideOutOnLeft
                val start = down.position
                var dragging = false
                var cancelled = false
                var released = false
                var peak = 0f
                var sign = 0f
                var dx = 0f
                var dy = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (pointer == null || event.changes.count { it.pressed } > 1 || !ownership.owns(gesture, currentBinding)) {
                        cancelled = true
                        break
                    }
                    dx = pointer.position.x - start.x
                    dy = pointer.position.y - start.y
                    if (pointer.isConsumed) {
                        cancelled = true
                        break
                    }
                    // Pointer-up cannot turn a still-pending touch into a newly locked drag.
                    if (!pointer.pressed && !dragging) break
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
                        val supported = gesture.action(if (dx > 0) 1 else -1) != null
                        offset = if (supported) dx.coerceIn(-capturedWidth, capturedWidth)
                            else (dx * .15f).coerceIn(-12.dp.toPx(), 12.dp.toPx())
                        pointer.consume()
                    }
                    if (!pointer.pressed) {
                        released = pointer.previousPressed
                        break
                    }
                }
                if (dragging) {
                    val direction = if (cancelled || !released) null else
                        releasedSwipeDirection(capturedWidth / scale, dx / scale, dy / scale, peak / scale, sign)
                    val action = gesture.action(direction)
                    val target = if (action != null && (capturedSlideOut || dx < 0 && capturedSlideOutLeft)) {
                        if (dx > 0) capturedWidth else -capturedWidth
                    } else 0f
                    settling = scope.launch {
                        try {
                            animate(offset, target, animationSpec = tween(if (action != null) 180 else 160)) { value, _ ->
                                if (ownership.owns(gesture, currentBinding)) offset = value
                            }
                            // A mini-player callback can install a pending identity before its face returns to rest.
                            ownership.finish(gesture, currentBinding, released && !cancelled, direction)
                        } finally {
                            ownership.cancel(gesture)
                            // A cancelled old job must not reset a newer gesture's translated face.
                            if (ownership.canResetFace(gesture)) offset = 0f
                        }
                    }
                } else if (ownership.cancel(gesture)) offset = 0f
            }
        } finally {
            // Includes pointerInput cancellation while a finger is still down, before any settle exists.
            captured?.let { gesture ->
                if (ownership.cancel(gesture)) {
                    settling?.cancel()
                    settling = null
                    offset = 0f
                }
            }
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
