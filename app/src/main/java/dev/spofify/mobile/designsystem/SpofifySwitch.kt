package dev.spofify.mobile.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/** Reference-sized decoration. Its enclosing 48dp-or-larger toggleable row owns input and semantics. */
@Composable
internal fun SpofifySwitchIndicator(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.size(width = 44.dp, height = 26.dp).alpha(if (enabled) 1f else .45f)
        .background(if (checked) colors.primary else colors.surfaceVariant, shape)
        .border(1.dp, if (checked) colors.primary else colors.outline, shape)
        .padding(4.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(18.dp).background(if (checked) colors.onPrimary else colors.onSurfaceVariant, CircleShape))
    }
}
