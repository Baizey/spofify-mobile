package dev.spofify.mobile.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Padding belongs inside the button's focus/ripple/click bounds, not outside its text. */
@Composable
internal fun MusicDetailLink(name: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall)
    }
}
