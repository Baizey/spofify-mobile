package dev.spofify.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.SpofifyClient

/** Search focus is transient UI state, not a reason to replace the route or player. */
internal fun hideListeningChrome(searchPage: Boolean, searchEditing: Boolean, imeVisible: Boolean): Boolean =
    searchPage && (searchEditing || imeVisible)

/** Never carry focus across destinations, clients, or accounts; query/results live elsewhere. */
@Composable
internal fun rememberSearchEditing(
    client: SpofifyClient,
    accountId: String?,
    searchPage: Boolean,
): MutableState<Boolean> = remember(client, accountId, searchPage) { mutableStateOf(false) }

/**
 * A real compact navigation layout, not an 80dp Material bar clipped to a smaller box.
 * Insets belong to this outer surface exactly once; labels can grow with system font scaling.
 */
@Composable
internal fun CompactNavigation(
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(windowInsets).selectableGroup().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Every destination owns a non-overlapping, full-height target, including on reselect. */
@Composable
internal fun RowScope.CompactNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            icon()
            Spacer(Modifier.height(2.dp))
            Text(label, color = color, style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

/** The persistent listening shell; collapsing its chrome never removes the mini-player slot. */
@Composable
internal fun ListeningChrome(
    chromeHidden: Boolean,
    connectionActions: (@Composable RowScope.() -> Unit)?,
    miniPlayer: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    systemInsets: WindowInsets = WindowInsets.safeDrawing,
    navigationInsets: WindowInsets = WindowInsets.navigationBars,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.imePadding(),
        contentWindowInsets = systemInsets,
        snackbarHost = snackbarHost,
        topBar = {
            if (!chromeHidden && connectionActions != null) {
                Row(
                    Modifier.fillMaxWidth().testTag("app-actions")
                        .windowInsetsPadding(systemInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    connectionActions()
                }
            }
        },
        bottomBar = {
            // CompactNavigation owns this inset when visible. With it gone, keep the mini-player
            // (or the empty dock) above system navigation. Parent IME padding consumes overlap.
            Column(if (chromeHidden) Modifier.windowInsetsPadding(navigationInsets.only(WindowInsetsSides.Bottom)) else Modifier) {
                // Extend the mini's surface to the display edge, but keep its interactive
                // content clear of landscape cutouts. No aesthetic gutter on ordinary phones.
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("mini-player-dock")
                    .windowInsetsPadding(systemInsets.only(WindowInsetsSides.Horizontal))) {
                    miniPlayer()
                }
                if (!chromeHidden) navigation()
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}
