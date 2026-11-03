package dev.spofify.mobile.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** Confined to its host, never installed on the sheet, seek bar, or the whole screen. */
@Composable
internal fun Modifier.playerSwipe(area: PlayerSwipeArea, onAction: (PlayerSwipeAction) -> Unit): Modifier {
    val currentAction = rememberUpdatedState(onAction)
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val edgeInset = with(density) { 24.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    return pointerInput(area, threshold, edgeInset) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val gesture = PlayerSwipeGesture(area, viewConfiguration.touchSlop, threshold,
                down.position.x, size.width.toFloat(), edgeInset)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                // Multi-touch and a child/nested-scroll consumer cancel rather than steal a drag.
                if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                    gesture.cancel()
                    break
                }
                val delta = change.position - change.previousPosition
                val ownsPointer = gesture.move(delta.x, delta.y)
                if (ownsPointer) change.consume()
                if (!change.pressed) {
                    gesture.finish()?.let {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentAction.value(it)
                    }
                    break
                }
            }
        }
    }
}
