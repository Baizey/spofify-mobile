package dev.spofify.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.playback.PlaybackInteractionKey
import kotlin.math.abs
import kotlin.math.roundToInt

/** Physical-right recognizer. Only an observed primary-pointer release can commit. */
internal class ContextualRowGesture(
    private val slop: Float,
    private val threshold: Float,
    downX: Float,
    width: Float,
    edge: Float,
) {
    private var cancelled = downX < edge || downX > width - edge
    private var horizontal = false
    private var finished = false
    var distance = 0f
        private set
    private var vertical = 0f

    fun cancel() { cancelled = true; distance = 0f }

    fun move(dx: Float, dy: Float): Boolean {
        if (cancelled || finished) return false
        // Reversing a locked swipe cancels; returning right never revives it.
        if (horizontal && dx < 0f) { cancel(); return false }
        distance += dx
        vertical += dy
        if (!horizontal) {
            if (distance < -slop || (abs(vertical) > slop && abs(vertical) >= abs(distance))) {
                cancel(); return false
            }
            horizontal = distance > slop && distance > abs(vertical) * 1.5f
        }
        return horizontal
    }

    fun release(observedFinalRelease: Boolean): Boolean {
        if (finished) return false
        finished = true
        return observedFinalRelease && !cancelled && horizontal && distance >= threshold
    }
}

internal class RowActionLifetime {
    private var alive = true
    fun invalidate() { alive = false }
    fun isCurrent(): Boolean = alive
}

/**
 * Shared list-only supplement to More/accessibility, never a player or queue policy owner.
 * [identity] includes the complete rendered occurrence/preparation binding, not just track ID.
 * A changed binding cancels the pointer coroutine (including A→B→A transitions); progress does not.
 */
@Composable
internal fun ContextualSwipeRow(
    identity: Any,
    interactionKey: PlaybackInteractionKey?,
    enabled: Boolean,
    label: String,
    onCommit: (PlaybackInteractionKey, () -> Boolean) -> Unit,
    modifier: Modifier = Modifier,
    excludeLeadingHandle: Boolean = false,
    content: @Composable (Modifier, () -> Boolean) -> Unit,
) {
    val lifetime = remember(identity) { RowActionLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.invalidate() } }
    var drag by remember(identity, interactionKey, enabled) { mutableFloatStateOf(0f) }
    val latestIdentity by rememberUpdatedState(identity)
    val latestKey by rememberUpdatedState(interactionKey)
    val latestEnabled by rememberUpdatedState(enabled)
    val commit by rememberUpdatedState(onCommit)
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.fillMaxWidth()) {
        if (drag > 0f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp), contentAlignment = AbsoluteAlignment.CenterLeft) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
        val gesture = Modifier.pointerInput(identity, lifetime, interactionKey, enabled, rtl, excludeLeadingHandle) {
            if (!enabled || interactionKey == null) return@pointerInput
            val capturedIdentity = identity
            val capturedKey = interactionKey
            val isCurrent = lifetime::isCurrent
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val recognizer = ContextualRowGesture(viewConfiguration.touchSlop, 72.dp.toPx(),
                    down.position.x, size.width.toFloat(), 24.dp.toPx())
                // More and dedicated reorder handles are independent 48dp targets. Physical
                // right still means right in RTL; only target geometry follows layout direction.
                val inLeadingTarget = if (rtl) down.position.x > size.width - 48.dp.toPx() else down.position.x < 48.dp.toPx()
                val inTrailingTarget = if (rtl) down.position.x < 48.dp.toPx() else down.position.x > size.width - 48.dp.toPx()
                // Click/long-click candidates consume down normally; movement consumption
                // below (not the down alone) determines ownership of the drag.
                if (inTrailingTarget || (excludeLeadingHandle && inLeadingTarget)) recognizer.cancel()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.any { it.id != down.id } ||
                            latestIdentity != capturedIdentity || latestKey !== capturedKey || !latestEnabled || !isCurrent()) {
                            recognizer.cancel()
                            break
                        }
                        val delta = change.position - change.previousPosition
                        if (recognizer.move(delta.x, delta.y)) {
                            change.consume()
                            drag = recognizer.distance.coerceIn(0f, 120.dp.toPx())
                        } else drag = 0f
                        if (!change.pressed) {
                            if (recognizer.release(change.previousPressed) && latestIdentity == capturedIdentity &&
                                latestKey === capturedKey && latestEnabled && isCurrent()) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                commit(capturedKey, isCurrent)
                            }
                            break
                        }
                    }
                } finally { drag = 0f }
            }
        }
        content(Modifier.absoluteOffset { IntOffset(drag.roundToInt(), 0) }.then(gesture), lifetime::isCurrent)
    }
}
