package dev.spofify.mobile.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class DetailState(
    val item: MusicItem? = null,
    val rows: List<MusicItem> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val nextOffset: Int? = null,
    val revision: Long? = null,
)

internal class DetailViewModel(private val api: LibraryApi, private val kind: MusicKind, private val id: String) : ViewModel() {
    private val mutable = MutableStateFlow(DetailState())
    val state = mutable.asStateFlow()
    private var request: Job? = null

    init { load(0) }
    fun retry() = load(0)
    fun more() { mutable.value.nextOffset?.let(::load) }

    private fun load(offset: Int) {
        if (request?.isActive == true) return
        mutable.value = mutable.value.copy(loading = true, failed = false)
        request = viewModelScope.launch {
            try {
                val item = if (offset == 0) api.detail(kind, id) else requireNotNull(mutable.value.item)
                val page = when (kind) {
                    MusicKind.ARTIST -> api.artistAlbums(id, offset)
                    MusicKind.ALBUM -> api.albumTracks(id, offset)
                    MusicKind.TRACK -> null
                }
                val previous = mutable.value
                check(offset == 0 || page?.revision == previous.revision)
                val rows = if (offset == 0) page?.items.orEmpty() else previous.rows + page?.items.orEmpty()
                check(rows.distinctBy(MusicItem::key).size == rows.size)
                check(page?.hasMore != true || page.items.isNotEmpty())
                mutable.value = DetailState(item, rows, nextOffset = page?.takeIf { it.hasMore }?.let { it.offset + it.items.size },
                    revision = page?.revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loading = false, failed = true)
            } finally {
                mutable.value = mutable.value.copy(loading = false)
            }
        }
    }
}

@Composable
internal fun DetailScreen(model: DetailViewModel, loader: ImageLoader, onBack: () -> Unit, onOpen: (MusicItem) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TextButton(onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Back") }
        if (state.item?.kind == MusicKind.ARTIST) {
            LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                item("heading", span = { GridItemSpan(maxLineSpan) }) { DetailHeading(state.item, loader) }
                items(state.rows, key = MusicItem::key) { album ->
                    Column(Modifier.clickable { onOpen(album) }) {
                        MusicArtwork(album, loader, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(album.name.ifBlank { "Unknown title" }, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp))
                        if (album.releaseDate.isNotBlank()) Text(album.releaseDate, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item("footer", span = { GridItemSpan(maxLineSpan) }) { DetailFooter(state, model) }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                item("heading") { DetailHeading(state.item, loader) }
                items(state.rows, key = MusicItem::key) { row -> MusicRow(row, loader) { onOpen(row) } }
                item("footer") { DetailFooter(state, model) }
            }
        }
    }
}

@Composable
private fun DetailHeading(item: MusicItem?, loader: ImageLoader) {
    if (item == null) return
    Column {
        PageTitle(item.name.ifBlank { "Unknown title" })
        if (item.artists.isNotEmpty()) Text(item.artists.joinToString(", ") { it.name },
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.kind == MusicKind.TRACK) {
            MusicArtwork(item, loader, Modifier.padding(vertical = 24.dp).fillMaxWidth().aspectRatio(1f))
            if (item.albumName.isNotBlank()) Text(item.albumName)
            if (item.durationMs > 0) Text(duration(item.durationMs))
            if (!item.hasAudio) Text("Audio unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailFooter(state: DetailState, model: DetailViewModel) {
    when {
        state.loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
        state.failed -> TextButton(model::retry) { Text("Can't load music · Retry") }
        state.nextOffset != null -> TextButton(model::more) { Text("More") }
        state.item?.kind == MusicKind.ARTIST && state.rows.isEmpty() -> Text("No albums in library")
        state.item?.kind == MusicKind.ALBUM && state.rows.isEmpty() -> Text("No tracks in library")
    }
}
