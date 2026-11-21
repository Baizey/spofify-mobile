package dev.spofify.mobile.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.flow.flow

@Composable
internal fun ListRowDivider(modifier: Modifier = Modifier) {
    val neutral = MaterialTheme.colorScheme.outlineVariant
    HorizontalDivider(modifier, color = neutral.copy(alpha = neutral.alpha * .75f))
}

/** A full-width paint layer for rows with a narrower primary target and optional trailing actions. */
@Composable
internal fun ListRowIndication(
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    role: Role? = null,
    content: @Composable BoxScope.(primaryTarget: Modifier) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val paint = remember(source) { RowPaintInteractions(source) }
    val row = if (onClick == null) Modifier else Modifier
        .onGloballyPositioned { paint.rowOrigin = it.positionInRoot() }
        .indication(paint, LocalIndication.current)
    val target = if (onClick == null) Modifier else Modifier
        .onGloballyPositioned { paint.targetOrigin = it.positionInRoot() }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
    Box(Modifier.fillMaxWidth().then(row)) { content(target) }
}

// Retain the theme's native press/focus/hover indication. Only the paint bounds and the
// press's coordinate space change; release and cancellation still refer to the same press.
private class RowPaintInteractions(source: InteractionSource) : InteractionSource {
    var rowOrigin = Offset.Zero
    var targetOrigin = Offset.Zero
    override val interactions = flow {
        val presses = mutableMapOf<PressInteraction.Press, PressInteraction.Press>()
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    val press = PressInteraction.Press(interaction.pressPosition + (targetOrigin - rowOrigin))
                    presses[interaction] = press
                    emit(press)
                }
                is PressInteraction.Release -> presses.remove(interaction.press)?.let { emit(PressInteraction.Release(it)) }
                is PressInteraction.Cancel -> presses.remove(interaction.press)?.let { emit(PressInteraction.Cancel(it)) }
                else -> emit(interaction)
            }
        }
    }
}
