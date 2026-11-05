package dev.spofify.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Visible rows, not footer composition, express demand. Each stationary viewport may fill twice;
 * further batches require actual movement of its first visible anchor. No progress/heartbeat input.
 * The owner must reserve its request synchronously and latch failures before returning.
 */
@Composable
internal fun AutomaticListDemand(
    state: LazyListState,
    identity: Any,
    frontierKeys: List<Any>,
    enabled: Boolean,
    onLoad: () -> Unit,
) {
    val currentFrontier = rememberUpdatedState(frontierKeys)
    val currentEnabled = rememberUpdatedState(enabled)
    val currentLoad = rememberUpdatedState(onLoad)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, identity, lifecycle) {
        var anchor: Pair<Any?, Int>? = null
        var fills = 0
        var requested: List<Any>? = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
          snapshotFlow {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo.filter {
                it.offset < info.viewportEndOffset && it.offset + it.size > info.viewportStartOffset
            }
            DemandViewport(visible.firstOrNull()?.let { it.key to it.offset },
                visible.map { it.key }, currentFrontier.value, currentEnabled.value)
        }.collect { view ->
            if (view.anchor != anchor) {
                anchor = view.anchor
                fills = 0
            }
            if (view.enabled && view.frontier.isNotEmpty() &&
                view.visible.any { it in view.frontier } && requested != view.frontier && fills < 2) {
                requested = view.frontier.toList()
                fills++
                currentLoad.value()
            }
          }
        }
    }
}

private data class DemandViewport(
    val anchor: Pair<Any?, Int>?, val visible: List<Any>, val frontier: List<Any>, val enabled: Boolean,
)

@Composable
internal fun AppendStatus(loading: Boolean, error: String?, onRetry: () -> Unit,
    loadingLabel: String = "Loading more", retryLabel: String = "Retry") {
    // A bottom-visible Retry must not shrink the scroll range while its same batch is pending.
    // Release the temporary space only with the successful append (or the next error) publication.
    var errorHeight by remember { mutableIntStateOf(0) }
    val reserved = with(LocalDensity.current) { if (loading) errorHeight.toDp() else 0.dp }
    if (!loading && error == null) SideEffect { errorHeight = 0 }
    Column(Modifier.fillMaxWidth().heightIn(min = reserved).onSizeChanged {
        if (error != null) errorHeight = it.height
    }) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = loadingLabel })
        if (error != null) {
            Text(error)
            TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(retryLabel) }
        }
    }
}
