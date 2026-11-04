package dev.spofify.mobile.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Existing cover/queue callers retain their current action binding and feedback. */
@Composable
internal fun Modifier.playerSwipe(area: PlayerSwipeArea, onAction: (PlayerSwipeAction) -> Unit): Modifier {
    val currentAction = rememberUpdatedState(onAction)
    return capturedPlayerSwipe(area, captureAction = { { action -> currentAction.value(action) } })
}

/** Capture transport identity once at down, not from a rebound release callback. */
@Composable
internal fun Modifier.miniPlayerSwipe(
    tapGuard: MiniPlayerTapGuard,
    captureAction: () -> (PlayerSwipeAction) -> Unit,
): Modifier = capturedPlayerSwipe(PlayerSwipeArea.MINI, captureAction, tapGuard)

/** Reject a release-click after a non-tap without consuming system Back/vertical intent. */
internal class MiniPlayerTapGuard { var allowClick = true }

private class SwipeHost {
    var windowX = 0f
    var windowWidth = 0f
}

/** Confined to its host, never installed on the sheet, seek bar, or the whole screen. */
@Composable
private fun Modifier.capturedPlayerSwipe(
    area: PlayerSwipeArea,
    captureAction: () -> (PlayerSwipeAction) -> Unit,
    tapGuard: MiniPlayerTapGuard? = null,
): Modifier {
    val currentCapture = rememberUpdatedState(captureAction)
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val fallbackEdge = with(density) { 24.dp.toPx() }
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemGestures
    val leftEdge = maxOf(fallbackEdge, insets.getLeft(density, direction).toFloat())
    val rightEdge = maxOf(fallbackEdge, insets.getRight(density, direction).toFloat())
    val currentEdges = rememberUpdatedState(leftEdge to rightEdge)
    val view = LocalView.current
    val host = remember { SwipeHost() }
    val haptics = LocalHapticFeedback.current
    return onGloballyPositioned {
        host.windowX = it.positionInWindow().x
        host.windowWidth = view.width.toFloat()
    }.pointerInput(area, threshold, fallbackEdge) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                tapGuard?.allowClick = true
                var totalX = 0f
                var totalY = 0f
                val actionAtDown = currentCapture.value()
                // Mini identity may be inset or on the physical right in RTL. Reserve system Back
                // edges in window coordinates, not relative to the artwork/identity rectangle.
                val mini = area == PlayerSwipeArea.MINI
                val edges = currentEdges.value
                val gesture = PlayerSwipeGesture(area, viewConfiguration.touchSlop, threshold,
                    if (mini) host.windowX + down.position.x else down.position.x,
                    if (mini) host.windowWidth else size.width.toFloat(),
                    if (mini) edges.first else fallbackEdge,
                    if (mini) edges.second else fallbackEdge)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    // Multi-touch and a child/nested-scroll consumer cancel rather than steal a drag.
                    if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                        gesture.cancel()
                        tapGuard?.allowClick = false
                        break
                    }
                    val delta = change.position - change.previousPosition
                    totalX += delta.x
                    totalY += delta.y
                    if (maxOf(abs(totalX), abs(totalY)) > viewConfiguration.touchSlop) tapGuard?.allowClick = false
                    val ownsPointer = gesture.move(delta.x, delta.y)
                    if (ownsPointer) change.consume()
                    if (!change.pressed) {
                        gesture.finish()?.let {
                            // A guarded transport submission is not confirmed playback. Do not give
                            // success haptics for mini skips (especially stale/rejected/remote ones).
                            if (!mini || it == PlayerSwipeAction.EXPAND) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            actionAtDown(it)
                        }
                        break
                    }
                }
                if (tapGuard != null) {
                    // Clickable observes Main pass. Keep non-taps suppressed until every release
                    // has passed it, then restore semantic/keyboard activation without a new down.
                    do {
                        val final = awaitPointerEvent(PointerEventPass.Final)
                    } while (final.changes.any { it.pressed })
                    tapGuard.allowClick = true
                }
            }
        } finally { tapGuard?.allowClick = true }
    }
}
