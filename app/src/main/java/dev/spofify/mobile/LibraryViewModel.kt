package dev.spofify.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import dev.spofify.mobile.core.PlaybackQueue
import dev.spofify.mobile.core.PlaylistSummary
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.playback.PlaybackConnection
import dev.spofify.mobile.playback.PlaybackControls
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Page {
    data object Home : Page
    data class Catalog(val kind: String = "albums") : Page
    data class Search(val query: String = "") : Page
    data object Likes : Page
    data object Playlists : Page
    data class Detail(val item: LibraryItem) : Page
    data class Playlist(val playlist: PlaylistSummary) : Page
}

enum class Tab { HOME, SEARCH, LIBRARY }

data class LibraryState(
    val restoring: Boolean = true,
    val busy: Boolean = false,
    val canRetryConnection: Boolean = false,
    val server: String = "",
    val session: Session? = null,
    val tab: Tab = Tab.HOME,
    val page: Page = Page.Home,
    val canGoBack: Boolean = false,
    val items: List<LibraryItem> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val preparing: Boolean = false,
    val likes: Map<String, Boolean> = emptyMap(),
    val pendingLikes: Set<String> = emptySet(),
    val failedLikeStatus: Set<String> = emptySet(),
)

class LibraryViewModel(application: Application, val graph: AppGraph, val playback: PlaybackControls) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        (application as SpofifyApplication).graph, PlaybackConnection(application))
    private val mutable = MutableStateFlow(LibraryState(server = graph.lastServer))
    val state = mutable.asStateFlow()
    private val stack = mutableListOf<Page>(Page.Home)
    private var loadJob: Job? = null
    private var queueJob: Job? = null
    private var generation = 0
    private var queueGeneration = 0
    private var signingOut = false
    private val likeOperations = LikeOperations(viewModelScope, { graph.client },
        changed = { id, liked -> mutable.update { it.copy(likes = it.likes + (id to liked), failedLikeStatus = it.failedLikeStatus - id) } },
        pending = { id, pending -> mutable.update { it.copy(pendingLikes = if (pending) it.pendingLikes + id else it.pendingLikes - id) } },
        failed = { error -> mutable.update { it.copy(notice = message(error)) } },
        statusFailed = { id -> mutable.update { it.copy(failedLikeStatus = it.failedLikeStatus + id) } })

    init {
        viewModelScope.launch {
            graph.sessionExpired.collect { expired ->
                if (expired && !signingOut) {
                    generation++
                    loadJob?.cancel()
                    queueJob?.cancel()
                    playback.stop()
                    mutable.update { LibraryState(restoring = false, server = it.server,
                        error = "Your session expired. Sign in again to keep listening.") }
                }
            }
        }
        reconnect()
    }

    fun reconnect() {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val session = graph.restore()
                mutable.update { it.copy(restoring = false, busy = false, canRetryConnection = false, session = session) }
                if (session != null) {
                    playback.connect()
                    load(Page.Home)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(restoring = false, busy = false, canRetryConnection = true,
                    error = "Could not reconnect. Check your network or retry the saved connection.") }
            }
        }
    }

    fun signIn(address: String, username: String, password: String) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val server = ServerAddress.parse(address)
                playback.stop()
                val client = graph.configure(server)
                val session = client.login(username.trim(), password)
                stack.clear(); stack.add(Page.Home)
                mutable.value = LibraryState(restoring = false, server = server.origin, session = session)
                playback.connect()
                load(Page.Home)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, error = message(error)) }
            }
        }
    }

    fun logout() {
        if (state.value.busy) return
        generation++
        loadJob?.cancel(); queueJob?.cancel()
        playback.stop()
        val client = graph.client
        signingOut = true
        mutable.value = LibraryState(restoring = false, server = state.value.server, busy = true)
        viewModelScope.launch {
            var failure: String? = null
            try { client?.logout() } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = "Signed out on this device. The server could not be reached to revoke the session; it will expire automatically."
            } finally {
                graph.clear(client)
                stack.clear(); stack.add(Page.Home)
                signingOut = false
                mutable.value = LibraryState(restoring = false, server = graph.lastServer, error = failure)
            }
        }
    }

    fun switchTab(tab: Tab) {
        stack.clear()
        val page = when (tab) {
            Tab.HOME -> Page.Home
            Tab.SEARCH -> Page.Search()
            Tab.LIBRARY -> Page.Catalog()
        }
        stack.add(page)
        mutable.update { it.copy(tab = tab) }
        load(page)
    }

    fun open(page: Page) {
        stack.add(page)
        load(page)
    }

    fun back() {
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex)
        load(stack.last())
    }

    fun search(query: String) {
        val page = Page.Search(query)
        stack[stack.lastIndex] = page
        load(page)
    }

    fun refresh() = load(state.value.page)
    fun more() {
        val current = state.value
        if (!current.loading && current.offset < current.total) load(current.page, append = true)
    }

    private fun load(page: Page, append: Boolean = false) {
        val client = graph.client ?: return
        if (state.value.session == null) return
        loadJob?.cancel()
        val request = ++generation
        val offset = if (append) state.value.offset else 0
        mutable.update { it.copy(page = page, canGoBack = stack.size > 1, loading = true, error = null,
            items = if (append) it.items else emptyList(), playlists = if (append) it.playlists else emptyList(),
            total = if (append) it.total else 0, offset = offset) }
        loadJob = viewModelScope.launch {
            try {
                if (page is Page.Search && page.query.isNotBlank()) delay(250)
                if (page == Page.Playlists) {
                    val result = client.playlists(offset)
                    if (request == generation) mutable.update { it.copy(
                        playlists = (if (append) it.playlists else emptyList()) + result.items,
                        total = result.total, offset = offset + result.items.size, loading = false) }
                } else {
                    val result = when (page) {
                        Page.Home -> client.recent()
                        is Page.Catalog -> client.library(kind = page.kind, offset = offset)
                        is Page.Search -> if (page.query.isBlank()) LibraryPage(emptyList(), 0)
                            else client.library(kind = "all", query = page.query, offset = offset)
                        Page.Likes -> client.likes(offset)
                        is Page.Playlist -> client.playlistEntries(page.playlist.id, offset)
                        is Page.Detail -> when (page.item.kind) {
                            "artist" -> client.library(kind = "albums", artistId = page.item.id, offset = offset)
                            "album" -> client.library(kind = "tracks", albumId = page.item.id, offset = offset)
                            else -> LibraryPage(listOf(page.item), 1)
                        }
                        Page.Playlists -> error("Unreachable")
                    }
                    if (request == generation) mutable.update { it.copy(
                        items = (if (append) it.items else emptyList()) + result.items,
                        total = displayedTotal(page, result.items.size, result.total),
                        offset = offset + result.items.size, loading = false) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation) mutable.update { it.copy(loading = false, error = message(error)) }
            }
        }
    }

    fun play(item: LibraryItem, shuffle: Boolean = false) {
        val source = state.value.page
        val selected = item.takeIf { it.kind == "track" }?.let {
            if (source is Page.Playlist) it else it.copy(entryId = null)
        }
        prepare(selected, shuffle) { playbackForSelection(it, source, item) }
    }

    fun playPage(shuffle: Boolean = false) {
        when (val page = state.value.page) {
            Page.Likes -> prepare(null, shuffle) { it.likedPlayback() }
            is Page.Playlist -> prepare(null, shuffle) { it.playlistPlayback(page.playlist.id) }
            is Page.Detail -> play(page.item, shuffle)
            else -> Unit
        }
    }

    private fun prepare(start: LibraryItem?, shuffle: Boolean, fetch: suspend (SpofifyClient) -> PlaybackQueue) {
        val client = graph.client ?: return
        if (state.value.session == null || state.value.busy) return
        queueJob?.cancel()
        val request = ++queueGeneration
        mutable.update { it.copy(preparing = true) }
        queueJob = viewModelScope.launch {
            try {
                val queue = fetch(client)
                if (request != queueGeneration || client !== graph.client || client.currentSession() == null) return@launch
                if (queue.items.isEmpty()) mutable.update { it.copy(notice = "No audio is available in this collection.") }
                else {
                    playback.play(queue.items, client, start?.id, shuffle, startEntryId = start?.entryId)
                    if (queue.truncated) mutable.update { it.copy(notice = "This queue contains the first 1,000 available songs.") }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = message(error)) }
            } finally {
                if (request == queueGeneration) mutable.update { it.copy(preparing = false) }
            }
        }
    }

    fun checkLike(item: LibraryItem) {
        if (!state.value.likes.containsKey(item.id)) {
            mutable.update { it.copy(failedLikeStatus = it.failedLikeStatus - item.id) }
            likeOperations.check(item.id)
        }
    }

    fun toggleLike(item: LibraryItem) {
        val old = state.value.likes[item.id] ?: return
        likeOperations.toggle(item.id, old)
    }

    fun stopPlayback() {
        queueGeneration++
        queueJob?.cancel()
        mutable.update { it.copy(preparing = false) }
        playback.stop()
    }

    fun clearNotice() { mutable.update { it.copy(notice = null) } }

    private fun message(error: Exception): String = when (error) {
        is ApiException -> error.message
        is IllegalArgumentException -> error.message ?: "Check the server URL."
        is IOException -> "Cannot reach your server. Check the address, network and TLS certificate."
        else -> "Something went wrong. Please try again."
    }

    override fun onCleared() {
        playback.release() // Service keeps playing across Activity recreation.
        super.onCleared()
    }
}
