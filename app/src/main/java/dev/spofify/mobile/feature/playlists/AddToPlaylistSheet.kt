package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PersonalCollectionsApi
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.ui.AutomaticListDemand

/** Creating a playlist never implicitly adds the song. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(client: SpofifyClient, track: LibraryItem, onDismiss: () -> Unit) {
    key(client, track.id) {
        val scope = rememberCoroutineScope()
        val state = remember(client, track.id) {
            AddToPlaylistState(PersonalCollectionsApi(client), track, scope,
                personalRevision = { client.personalRevision.value.playlists }, currentSession = { client.currentSession() })
        }
        val playlistRevision = client.personalRevision.collectAsState().value.playlists
        SideEffect { state.checkFreshness(playlistRevision) }
        val listState = rememberLazyListState()
        val editorListState = rememberLazyListState()
        val reviewListState = rememberLazyListState()
        DisposableEffect(state) { onDispose { state.dispose() } }
        AutomaticListDemand(listState, state.targetIdentity,
            state.targets?.items.orEmpty().takeLast(3).map { it.id }, state.canLoadMoreTargets && state.query.isBlank(),
            state::moreTargets)
        AutomaticListDemand(reviewListState, state.verificationIdentity,
            state.verifiedTargets?.items.orEmpty().takeLast(3).map { "review-${it.id}" },
            state.canLoadMoreVerification, state::moreVerification)
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !state.mutating })
        LaunchedEffect(state) { state.loadTargets(0) }
        LaunchedEffect(state.finished) { if (state.finished) onDismiss() }
        ModalBottomSheet(
            onDismissRequest = { if (!state.mutating) onDismiss() }, sheetState = sheet,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !state.mutating,
                shouldDismissOnClickOutside = !state.mutating),
        ) {
            AddToPlaylistContent(state.presentation, AddPlaylistActions(
                select = state::select, query = { state.query = it }, searchMore = state::moreTargets, name = { state.name = it }, description = { state.description = it },
                beginCreate = state::beginCreate, cancelCreate = state::cancelCreate,
                create = state::create, add = state::add, refresh = state::refreshTargets,
                retry = state::retryTargets, review = { state.verify() }, retryReview = state::retryVerification,
                dismiss = { if (!state.mutating) onDismiss() },
            ), if (state.blocked != null) reviewListState else if (state.creating) editorListState else listState,
                Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f))
        }
    }
}
