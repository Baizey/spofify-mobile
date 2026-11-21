package dev.spofify.mobile.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpofifySheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.heightIn(max = windowHeight * .94f),
        shape = SpofifySurfaces.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = .5f),
        tonalElevation = 0.dp,
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = false, isAppearanceLightNavigationBars = false),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(48.dp)
                // The visible sheet corners are smaller; retain the handle's original hit outline.
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = "Dismiss sheet" },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f),
                    RoundedCornerShape(2.dp)))
            }
        },
        content = content,
    )
}

@Composable
fun SheetHeading(title: String) {
    Text(title, Modifier.padding(bottom = 16.dp).semantics { heading() },
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.5.sp, lineHeight = 26.sp))
}

@Composable
fun SheetAction(label: String, glyph: SpofifyGlyph, enabled: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).alpha(if (enabled) 1f else .5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SpofifyIcon(glyph, null, Modifier.size(22.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun SheetGroup(title: String) {
    Text(title, Modifier.padding(top = 20.dp, bottom = 5.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
