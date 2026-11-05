package dev.spofify.mobile.ui.theme

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Full, unmodified bundled notice, available offline without an account or external viewer. */
@Composable
fun AppFontLicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val license = remember(context) {
        context.assets.open("licenses/jetbrains-mono/OFL.txt")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    AlertDialog(
        // AlertDialog's native Back/outside dismissal uses this same callback.
        onDismissRequest = onDismiss,
        title = { Text("JetBrains Mono font license") },
        text = {
            Text(
                text = license,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
        },
    )
}
