package dev.spofify.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/** The persistent listening shell; collapsing its chrome never removes the mini-player slot. */
@Composable
internal fun ListeningChrome(
    chromeHidden: Boolean,
    connectionActions: @Composable RowScope.() -> Unit,
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
            if (!chromeHidden) {
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
            // NavigationBar owns this inset when visible. With it gone, keep the mini-player
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
