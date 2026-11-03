package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.spofify.mobile.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface HomeReader {
    suspend fun recent(): List<LibraryItem>
    suspend fun likedCount(): Int
    suspend fun playlists(): List<PersonalPlaylistSummary>
    suspend fun catalogRevision(): Long = 0
}
class ClientHomeReader(private val client: SpofifyClient) : HomeReader {
    override suspend fun recent() = client.recent().items.take(8)
    override suspend fun likedCount() = client.likes().total
    override suspend fun playlists() = PersonalCollectionsApi(client).playlists(limit = 6).items
    override suspend fun catalogRevision() = CatalogApi(client).revision()
}
data class HomeShelf<T>(val value: T? = null, val loading: Boolean = false, val error: String? = null)
data class ListenerHomeState(
    val recent: HomeShelf<List<LibraryItem>> = HomeShelf(),
    val likedCount: HomeShelf<Int> = HomeShelf(),
    val playlists: HomeShelf<List<PersonalPlaylistSummary>> = HomeShelf(),
)

/** Independent reads and retention: a failing or slow playlist shelf cannot blank recent listening. */
class HomeStateHolder(private val reader: HomeReader, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(ListenerHomeState())
    val state = mutable.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private var visible = false
    private val dirty = mutableSetOf("recent", "likes", "playlists")
    private val failedShelves = mutableSetOf<String>()
    private var generation = 0L
    private var knownCatalogRevision: Long? = null

    suspend fun checkCatalogRevision() {
        if (!visible) return
        val token = generation
        try {
            val revision = reader.catalogRevision()
            if (visible && token == generation) {
                val previous = knownCatalogRevision
                knownCatalogRevision = maxOf(previous ?: revision, revision)
                if (previous == null || revision > previous) {
                    // These personal envelopes have no catalog revision. One trailing validation
                    // fences reads that started before the first cheap catalog snapshot.
                    invalidate("recent", "likes", "playlists")
                } else failedShelves.toList().forEach(::refresh)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Quietly retain personal content until a later visible check.
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) (dirty + failedShelves).toList().forEach(::refresh)
        else {
            generation++
            dirty.addAll(jobs.keys)
            jobs.values.forEach { it.cancel() }; jobs.clear()
            mutable.value = mutable.value.let { it.copy(recent = it.recent.copy(loading = false), likedCount = it.likedCount.copy(loading = false), playlists = it.playlists.copy(loading = false)) }
        }
    }
    fun invalidate(vararg shelves: String) {
        dirty.addAll(shelves)
        if (visible) shelves.forEach(::refresh)
    }
    fun refresh(shelf: String) {
        if (!visible) { dirty.add(shelf); return }
        if (jobs[shelf]?.isActive == true) { dirty.add(shelf); return }
        dirty.remove(shelf)
        val token = generation
        mutable.value = mutable.value.let { when (shelf) {
            "recent" -> it.copy(recent = it.recent.copy(loading = true, error = null))
            "likes" -> it.copy(likedCount = it.likedCount.copy(loading = true, error = null))
            else -> it.copy(playlists = it.playlists.copy(loading = true, error = null))
        } }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                when (shelf) {
                    "recent" -> { val result = reader.recent().take(8); if (token == generation) mutable.value = mutable.value.copy(recent = HomeShelf(result)) }
                    "likes" -> { val result = reader.likedCount(); if (token == generation) mutable.value = mutable.value.copy(likedCount = HomeShelf(result)) }
                    else -> { val result = reader.playlists().take(6); if (token == generation) mutable.value = mutable.value.copy(playlists = HomeShelf(result)) }
                }
                if (token == generation) failedShelves.remove(shelf)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (token == generation) {
                    failedShelves.add(shelf)
                    mutable.value = mutable.value.let { when (shelf) {
                        "recent" -> it.copy(recent = it.recent.copy(loading = false, error = catalogError(error)))
                        "likes" -> it.copy(likedCount = it.likedCount.copy(loading = false, error = catalogError(error)))
                        else -> it.copy(playlists = it.playlists.copy(loading = false, error = catalogError(error)))
                    } }
                }
            } finally {
                if (token == generation) { jobs.remove(shelf); if (shelf in dirty) refresh(shelf) }
            }
        }
        jobs[shelf] = job
        job.start()
    }
}

@Composable
fun LibraryHome(
    holder: HomeStateHolder,
    onOpenItem: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem, Boolean) -> Unit,
    onShowActions: (LibraryItem) -> Unit,
    onEnqueueItem: (LibraryItem) -> Unit,
    onOpenLikes: () -> Unit,
    onOpenPlaylists: (String?) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(),
    likes: Map<String, CatalogLikeState> = emptyMap(),
    onToggleLike: ((LibraryItem) -> Unit)? = null,
    onVisibleItems: (List<LibraryItem>) -> Unit = {},
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(holder, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            holder.setVisible(true)
            try {
                while (true) { holder.checkCatalogRevision(); kotlinx.coroutines.delay(15_000) }
            } finally { holder.setVisible(false) }
        }
    }
    DisposableEffect(holder) { onDispose { holder.setVisible(false) } }
    val callback by rememberUpdatedState(onVisibleItems)
    LaunchedEffect(state.recent.value) { callback(state.recent.value.orEmpty()) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(onClick = onOpenLikes, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Column {
                        Text("Liked songs", style = MaterialTheme.typography.titleMedium)
                        state.likedCount.value?.let { Text("$it songs", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (state.likedCount.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.likedCount.error?.let { LoadError(it, "Retry liked count") { holder.refresh("likes") } }
        }
        item {
            Text("Recently played", style = MaterialTheme.typography.titleLarge)
            if (state.recent.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.recent.error?.let { LoadError(it, "Retry recently played") { holder.refresh("recent") } }
            if (state.recent.value?.isEmpty() == true) Text("Your recent listening will appear here. Search for your first song.")
        }
        items(state.recent.value.orEmpty(), key = { "recent:${it.id}" }) { item ->
            MusicRow(item, onOpenItem, onPlayItem, onShowActions, onEnqueueItem, artwork,
                playback = playback, like = likes[item.id], onToggleLike = onToggleLike)
        }
        item {
            Text("Your playlists", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onOpenPlaylists(null) }) { Text("All playlists") }
            if (state.playlists.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.playlists.error?.let { LoadError(it, "Retry playlists") { holder.refresh("playlists") } }
            if (state.playlists.value?.isEmpty() == true) Text("Create a playlist to collect your music.")
        }
        items(state.playlists.value.orEmpty(), key = { "playlist:${it.id}" }) { playlist ->
            ElevatedCard(onClick = { onOpenPlaylists(playlist.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (playlist.kind == PlaylistKind.DERIVED) "Rules playlist" else "${playlist.trackCount} songs", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
