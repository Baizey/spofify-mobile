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
import dev.spofify.mobile.playback.PlaybackRequests
import dev.spofify.mobile.feature.library.CatalogStateHolder
import dev.spofify.mobile.feature.library.ClientCatalogReader
import dev.spofify.mobile.feature.library.ClientHomeReader
import dev.spofify.mobile.feature.library.HomeStateHolder
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
    data class Detail(val item: LibraryItem, val navigationId: Long = 0) : Page
    data class Playlist(val playlist: PlaylistSummary) : Page
}

enum class Tab { HOME, SEARCH, PLAYLISTS }

data class LibraryState(
    val restoring: Boolean = true,
    val busy: Boolean = false,
    val canRetryConnection: Boolean = false,
    val server: String = "",
    val rememberLogin: Boolean = true,
    val hasSavedLogin: Boolean = false,
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
    val playbackIntentGeneration: Int = 0,
    val likes: Map<String, Boolean> = emptyMap(),
    val pendingLikes: Set<String> = emptySet(),
    val failedLikeStatus: Set<String> = emptySet(),
)

class LibraryViewModel(application: Application, val graph: AppGraph, val playback: PlaybackControls) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        (application as SpofifyApplication).graph, PlaybackConnection(application))
    // Passwords stay out of public UI state, saved state, parcels and logs.
    private var remembered = runCatching { graph.rememberedLogin() }.getOrNull()
    val rememberedLogin: RememberedLogin? get() = remembered
    private var queueGeneration = 0
    private fun freshState(restoring: Boolean = false, server: String = graph.lastServer.ifBlank { remembered?.server.orEmpty() },
        session: Session? = null, busy: Boolean = false, error: String? = null, notice: String? = null) =
        LibraryState(restoring = restoring, server = server, session = session, busy = busy, error = error, notice = notice,
            rememberLogin = graph.rememberLoginEnabled, hasSavedLogin = remembered != null,
            playbackIntentGeneration = queueGeneration)
    private val mutable = MutableStateFlow(freshState(restoring = true))
    val state = mutable.asStateFlow()
    private val stack = mutableListOf<Page>(Page.Home)
    private val tabStacks = mutableMapOf<Tab, List<Page>>()
    private var featureClient: SpofifyClient? = null
    private var featureAccount: String? = null
    private val catalogHolders = linkedMapOf<String, CatalogStateHolder>()
    private var navigationSerial = 0L
    private var home: HomeStateHolder? = null
    private var personalJob: Job? = null
    private var likesCache: LibraryPage? = null
    private var likesDirty = true

    private fun ensureFeatureOwner(): SpofifyClient? {
        val client = graph.client ?: return null
        val account = client.currentSession()?.accountId ?: return null
        if (featureClient !== client || featureAccount != account) {
            clearFeatureState()
            featureClient = client; featureAccount = account
            var previous = client.personalRevision.value
            personalJob = viewModelScope.launch {
                client.personalRevision.collect { revision ->
                    if (graph.client !== client || client.currentSession()?.accountId != account) return@collect
                    if (revision.likes != previous.likes) {
                        personalChanged("likes")
                        likesDirty = true
                        if (state.value.page == Page.Likes) refresh()
                    }
                    if (revision.history != previous.history) personalChanged("recent")
                    if (revision.playlists != previous.playlists) personalChanged("playlists")
                    previous = revision
                }
            }
        }
        return client
    }

    fun catalogHolder(item: LibraryItem? = null, navigationId: Long = 0): CatalogStateHolder? {
        val client = ensureFeatureOwner() ?: return null
        val key = item?.let { "${it.kind}:${it.id}:$navigationId" } ?: "search"
        return catalogHolders.getOrPut(key) {
            if (catalogHolders.size >= 24) {
                val oldest = catalogHolders.keys.firstOrNull { it != "search" }
                oldest?.let { catalogHolders.remove(it)?.close() }
            }
            CatalogStateHolder(ClientCatalogReader(client), viewModelScope, "all", initialItem = item)
        }
    }

    fun homeHolder(): HomeStateHolder? {
        val client = ensureFeatureOwner() ?: return null
        return home ?: HomeStateHolder(ClientHomeReader(client), viewModelScope).also { home = it }
    }

    private fun clearFeatureState() {
        catalogHolders.values.forEach { it.close() }; catalogHolders.clear()
        home?.setVisible(false); home = null
        personalJob?.cancel(); personalJob = null
        likesCache = null; likesDirty = true
        featureClient = null; featureAccount = null
    }

    fun personalChanged(vararg shelves: String) { home?.invalidate(*shelves) }
    private var loadJob: Job? = null
    private var queueJob: Job? = null
    private var generation = 0
    private var signingOut = false
    private val likeOperations = LikeOperations(viewModelScope, { graph.client },
        changed = { id, liked ->
            mutable.update { it.copy(likes = it.likes + (id to liked), failedLikeStatus = it.failedLikeStatus - id) }
        },
        pending = { id, pending -> mutable.update { it.copy(pendingLikes = if (pending) it.pendingLikes + id else it.pendingLikes - id) } },
        failed = { error -> mutable.update { it.copy(notice = message(error)) } },
        statusFailed = { id -> mutable.update { it.copy(failedLikeStatus = it.failedLikeStatus + id) } })

    init {
        viewModelScope.launch {
            graph.sessionExpired.collect { expired ->
                if (expired && !signingOut) {
                    generation++
                    queueGeneration++
                    loadJob?.cancel()
                    queueJob?.cancel()
                    clearFeatureState(); tabStacks.clear()
                    playback.stop()
                    mutable.update { freshState(server = it.server,
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
                queueGeneration++; generation++
                queueJob?.cancel(); loadJob?.cancel()
                clearFeatureState(); tabStacks.clear()
                playback.stop()
                val client = graph.configure(server)
                val session = client.login(username.trim(), password)
                if (graph.client !== client || client.currentSession() != session) {
                    mutable.update { it.copy(busy = false, error = "The connection changed. Sign in again.") }
                    return@launch
                }
                var notice: String? = null
                try {
                    graph.saveLogin(client, session.username, password)
                    remembered = graph.rememberedLogin()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    notice = "Signed in, but sign-in details could not be saved securely on this device."
                }
                if (graph.client !== client || client.currentSession() != session) {
                    mutable.update { it.copy(busy = false, error = "The connection changed. Sign in again.") }
                    return@launch
                }
                stack.clear(); stack.add(Page.Home)
                mutable.value = freshState(server = server.origin, session = session, notice = notice)
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
        generation++; queueGeneration++
        loadJob?.cancel(); queueJob?.cancel()
        clearFeatureState(); tabStacks.clear()
        playback.stop()
        val client = graph.client
        signingOut = true
        mutable.value = freshState(server = state.value.server, busy = true)
        viewModelScope.launch {
            var failure: String? = null
            try { client?.logout() } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = "Signed out on this device. The server could not be reached to revoke the session; it will expire automatically."
            } finally {
                graph.clear(client)
                stack.clear(); stack.add(Page.Home)
                signingOut = false
                mutable.value = freshState(error = failure)
            }
        }
    }

    fun setRememberLogin(enabled: Boolean) {
        if (state.value.busy) return
        try {
            graph.setRememberLogin(enabled)
            if (!enabled) remembered = null
            mutable.update { it.copy(rememberLogin = enabled, hasSavedLogin = remembered != null, error = null) }
        } catch (_: Exception) {
            mutable.update { if (it.session == null) it.copy(error = "Could not update saved sign-in details. Please try again.")
                else it.copy(notice = "Could not forget saved sign-in details. Please try again.") }
        }
    }

    fun forgetSavedLogin() = setRememberLogin(false)

    fun switchTab(tab: Tab) {
        if (tab == state.value.tab) return
        tabStacks[state.value.tab] = stack.toList()
        stack.clear()
        val page = when (tab) {
            Tab.HOME -> Page.Home
            Tab.SEARCH -> Page.Search()
            Tab.PLAYLISTS -> Page.Playlists
        }
        stack.addAll(tabStacks[tab] ?: listOf(page))
        mutable.update { it.copy(tab = tab) }
        load(stack.last())
    }

    fun open(page: Page) {
        val destination = if (page is Page.Detail) page.copy(navigationId = ++navigationSerial) else page
        stack.add(destination)
        load(destination)
    }

    fun back() {
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex)
        load(stack.last())
    }

    fun search(query: String) {
        catalogHolder()?.commitQuery(query)
        val page = Page.Search(query)
        stack[stack.lastIndex] = page
        load(page)
    }

    fun refresh() { if (state.value.page == Page.Likes) likesDirty = true; load(state.value.page) }
    fun more() {
        val current = state.value
        if (!current.loading && current.offset < current.total) load(current.page, append = true)
    }

    private fun load(page: Page, append: Boolean = false) {
        val client = graph.client ?: return
        if (state.value.session == null) return
        loadJob?.cancel()
        val request = ++generation
        // These routes own independent bounded state holders. Never prefetch a global catalog or
        // decode rules playlists through the old manual-only compatibility API.
        if (page != Page.Likes) {
            mutable.update { it.copy(page = page, canGoBack = stack.size > 1, loading = false,
                error = null, items = emptyList(), playlists = emptyList(), total = 0, offset = 0) }
            return
        }
        val cached = likesCache
        if (!append && !likesDirty && cached != null) {
            mutable.update { it.copy(page = page, canGoBack = stack.size > 1, loading = false, error = null,
                items = cached.items, total = cached.total, offset = cached.items.size) }
            return
        }
        val offset = if (append) state.value.offset else 0
        mutable.update { it.copy(page = page, canGoBack = stack.size > 1, loading = true, error = null,
            items = if (append || it.page == page) it.items else emptyList(), playlists = if (append) it.playlists else emptyList(),
            total = if (append) it.total else 0, offset = offset) }
        loadJob = viewModelScope.launch {
            try {
                val result = client.likes(offset)
                if (request == generation && graph.client === client && state.value.session != null) {
                    val items = (if (append) state.value.items else emptyList()) + result.items
                    likesCache = result.copy(items = items, offset = 0)
                    likesDirty = false
                    mutable.update { it.copy(items = items, total = result.total,
                        offset = offset + result.items.size, loading = false) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation && graph.client === client) mutable.update { it.copy(loading = false, error = message(error)) }
            }
        }
    }

    fun play(item: LibraryItem, shuffle: Boolean = false) {
        val source = state.value.page
        val selected = item.takeIf { it.kind == "track" }?.let {
            if (source is Page.Playlist) it else it.copy(entryId = null)
        }
        prepare(selected, shuffle, context = when {
            source == Page.Likes -> LibraryItem("likes", "", "Liked songs")
            item.kind != "track" -> item
            !item.albumId.isNullOrBlank() -> item.albumId?.let { LibraryItem("album", it, item.albumName ?: "Album") }
            else -> null
        }) { playbackForSelection(it, source, item) }
    }

    fun playPage(shuffle: Boolean = false) {
        when (val page = state.value.page) {
            Page.Likes -> prepare(null, shuffle, context = LibraryItem("likes", "", "Liked songs")) { it.likedPlayback() }
            is Page.Playlist -> prepare(null, shuffle) { it.playlistPlayback(page.playlist.id) }
            is Page.Detail -> play(page.item, shuffle)
            else -> Unit
        }
    }

    fun playFrom(expectedClient: SpofifyClient, item: LibraryItem, shuffle: Boolean = false) {
        if (graph.client === expectedClient && state.value.session != null) play(item, shuffle)
    }

    fun enqueueFrom(expectedClient: SpofifyClient, item: LibraryItem, playNext: Boolean = false) {
        if (graph.client !== expectedClient || state.value.session == null || !item.playable) return
        prepare(null, false, enqueue = true, playNext = playNext) { client ->
            if (item.kind == "track") PlaybackQueue(listOf(item.copy(entryId = null)), total = 1) else client.playback(item)
        }
    }

    /** A prepared playlist callback is authorized only for the client/account that rendered it. */
    fun playPrepared(expectedClient: SpofifyClient, expectedAccountId: String?, queue: PlaybackQueue,
        startTrackId: String?, startEntryId: String?, expectedGeneration: Int = queueGeneration) {
        if (expectedGeneration != queueGeneration || graph.client !== expectedClient || state.value.busy || state.value.session?.accountId != expectedAccountId ||
            expectedClient.currentSession()?.accountId != expectedAccountId || expectedAccountId == null) return
        queueGeneration++; queueJob?.cancel()
        mutable.update { it.copy(preparing = false, playbackIntentGeneration = queueGeneration) }
        if (queue.items.isEmpty()) { mutable.update { it.copy(notice = "No audio is available in this collection.") }; return }
        if ((startEntryId != null && queue.items.none { it.entryId == startEntryId }) ||
            (startEntryId == null && startTrackId != null && queue.items.none { it.id == startTrackId })) {
            mutable.update { it.copy(notice = "The selected song is not in this prepared queue. Reload the collection and try again.") }
            return
        }
        if (!preparedSelectionValid(queue, expectedClient)) {
            mutable.update { it.copy(notice = "This prepared selection expired. Reload the collection and choose again.") }; return
        }
        playback.playPrepared(queue, expectedClient, startTrackId, startEntryId = startEntryId)
        if (queue.truncated) mutable.update { it.copy(notice = "This queue is limited to 1,000 available songs.") }
    }

    private fun preparedSelectionValid(queue: PlaybackQueue, client: SpofifyClient): Boolean {
        if (queue.context?.kind != "derived" && queue.preparedState?.context?.kind != "derived") return true
        val prepared = queue.preparedState ?: return false
        return queue.selectionAuthority?.isValidFor(client, prepared) == true
    }

    private fun prepare(start: LibraryItem?, shuffle: Boolean, enqueue: Boolean = false, playNext: Boolean = false,
        context: LibraryItem? = null, fetch: suspend (SpofifyClient) -> PlaybackQueue) {
        val client = graph.client ?: return
        if (state.value.session == null || state.value.busy) return
        queueJob?.cancel()
        val request = ++queueGeneration
        // Capture BEFORE fetching: PlaybackConnection cannot tell a late queue response from
        // a new tap if pause, output handoff or logout invalidates playback while we await it.
        val playbackEpoch = PlaybackRequests.epoch
        val account = state.value.session?.accountId
        fun isCurrent() = request == queueGeneration && playbackEpoch == PlaybackRequests.epoch &&
            graph.client === client && account != null && client.currentSession()?.accountId == account &&
            state.value.session?.accountId == account
        mutable.update { it.copy(preparing = true, playbackIntentGeneration = request) }
        queueJob = viewModelScope.launch {
            try {
                val queue = fetch(client)
                if (!isCurrent()) return@launch
                if (queue.items.isEmpty()) mutable.update { it.copy(notice = "No audio is available in this collection.") }
                else {
                    if (!preparedSelectionValid(queue, client)) {
                        mutable.update { it.copy(notice = "This prepared selection expired. Reload the collection and choose again.") }
                        return@launch
                    }
                    if (enqueue) playback.enqueue(queue.items, client, playNext)
                    else playback.playPrepared(queue, client, start?.id, shuffle, startEntryId = start?.entryId, context = context)
                    if (queue.truncated) mutable.update { it.copy(notice = "This queue is limited to 1,000 available songs.") }
                    else if (enqueue) mutable.update { it.copy(notice = if (playNext) "Added to play next." else "Added to queue.") }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isCurrent()) mutable.update { it.copy(notice = message(error)) }
            } finally {
                if (request == queueGeneration) mutable.update { it.copy(preparing = false) }
            }
        }
    }

    fun checkVisibleLikes(items: List<LibraryItem>) { items.filter { it.kind == "track" }.forEach(::checkLike) }

    fun likeAction(item: LibraryItem) {
        if (state.value.likes[item.id] == null || item.id in state.value.failedLikeStatus) checkLike(item)
        else toggleLike(item)
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
        mutable.update { it.copy(preparing = false, playbackIntentGeneration = queueGeneration) }
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
        clearFeatureState()
        playback.release() // Service keeps playing across Activity recreation.
        super.onCleared()
    }
}
