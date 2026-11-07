package dev.spofify.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import dev.spofify.mobile.core.PlaybackQueue
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.QueueIntentSelection
import dev.spofify.mobile.playback.queueIntentSelection
import dev.spofify.mobile.playback.defaultQueueAction
import dev.spofify.mobile.core.PlaylistSummary
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.playback.PlaybackConnection
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackRequests
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import dev.spofify.mobile.playback.PlaybackActionResult
import dev.spofify.mobile.playback.PlaybackFeedback
import dev.spofify.mobile.playback.PlaybackActionStatus
import dev.spofify.mobile.playback.nextPlaybackFeedback
import dev.spofify.mobile.playback.PlaybackListActionTickets
import dev.spofify.mobile.feature.library.CatalogStateHolder
import dev.spofify.mobile.feature.library.ClientCatalogReader
import dev.spofify.mobile.feature.library.ClientHomeReader
import dev.spofify.mobile.feature.library.HomeStateHolder
import dev.spofify.mobile.feature.playlists.PlaylistBrowseMemory
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface Page {
    data object Home : Page
    data class Catalog(val kind: String = "albums") : Page
    data class Search(val query: String = "") : Page
    data object Likes : Page
    data object Playlists : Page
    data object Profile : Page
    data object Credits : Page
    data class Detail(val item: LibraryItem, val navigationId: Long = 0) : Page
    data class Playlist(val playlist: PlaylistSummary) : Page
}

enum class Tab { HOME, SEARCH, PLAYLISTS, PROFILE }

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
    /** One-shot current-session nav intent; never saved/restored with route/query state. */
    val searchActivationId: Long? = null,
    val items: List<LibraryItem> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val loading: Boolean = false,
    val likesStale: Boolean = false,
    val likesEnded: Boolean = false,
    val likesGeneration: Int = 0,
    val error: String? = null,
    val notice: String? = null,
    val feedback: PlaybackFeedback? = null,
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
    private var actionFeedbackSerial = 0L
    private class ListActionTicket(var key: PlaybackInteractionKey?,
        var binding: dev.spofify.mobile.core.QueueIntentBinding? = null) {
        val serial = PlaybackListActionTickets.commit()
    }
    private val listActionTickets = mutableSetOf<ListActionTicket>()
    private fun freshState(restoring: Boolean = false, server: String = remembered?.server ?: graph.lastServer,
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
    private var featureSession: Session? = null
    private var playlistBrowse: PlaylistBrowseMemory? = null
    private val catalogHolders = linkedMapOf<String, CatalogStateHolder>()
    private var navigationSerial = 0L
    private var searchActivationSerial = 0L
    private var home: HomeStateHolder? = null
    private var personalJob: Job? = null
    private var likesCache: LibraryPage? = null
    private var likesNextOffset = 0
    private var likesFailure: String? = null
    private var likesStale = false
    private var likesEnded = false
    private var likesDirty = true
    private var loadSerial = 0L

    private fun ensureFeatureOwner(): SpofifyClient? {
        val client = graph.client ?: return null
        val session = client.currentSession() ?: return null
        val account = session.accountId
        if (featureClient !== client || featureAccount != account || featureSession !== session) {
            clearFeatureState()
            featureClient = client; featureAccount = account; featureSession = session
            var previous = client.personalRevision.value
            personalJob = viewModelScope.launch {
                client.personalRevision.collect { revision ->
                    if (graph.client !== client || client.currentSession() !== session) return@collect
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

    private fun catalogKey(item: LibraryItem?, navigationId: Long = 0) =
        item?.let { "${it.kind}:${it.id}:$navigationId" } ?: "search"

    fun catalogHolder(item: LibraryItem? = null, navigationId: Long = 0): CatalogStateHolder? {
        val client = ensureFeatureOwner() ?: return null
        val key = catalogKey(item, navigationId)
        return catalogHolders.getOrPut(key) {
            if (catalogHolders.size >= 24) {
                val oldest = catalogHolders.keys.firstOrNull { it != "search" }
                oldest?.let { catalogHolders.remove(it)?.close() }
            }
            CatalogStateHolder(ClientCatalogReader(client), viewModelScope, "all", initialItem = item)
        }
    }

    fun playlistBrowseMemory(): PlaylistBrowseMemory? {
        ensureFeatureOwner() ?: return null
        return playlistBrowse ?: PlaylistBrowseMemory().also { playlistBrowse = it }
    }

    fun homeHolder(): HomeStateHolder? {
        val client = ensureFeatureOwner() ?: return null
        return home ?: HomeStateHolder(ClientHomeReader(client), viewModelScope).also { home = it }
    }

    private fun clearFeatureState() {
        mutable.update { it.copy(searchActivationId = null) }
        catalogHolders.values.forEach { it.close() }; catalogHolders.clear()
        home?.setVisible(false); home = null
        personalJob?.cancel(); personalJob = null
        likesCache = null; likesDirty = true
        likesNextOffset = 0; likesFailure = null; likesStale = false; likesEnded = false
        loadSerial++
        playlistBrowse = null
        featureClient = null; featureAccount = null; featureSession = null
    }

    fun personalChanged(vararg shelves: String) { home?.invalidate(*shelves) }
    private var loadJob: Job? = null
    private var queueJob: Job? = null
    private val listPreparationJobs = linkedSetOf<Job>()
    private var listPreparationTail: Job? = null
    private var listPreparationGeneration = -1
    private var generation = 0
    private var signingOut = false
    private var authenticationSerial = 0L
    private var cleared = false

    val browseSourceEpoch: Int get() = generation

    /** Capture at composition/event acquisition, not at a held row's eventual release. */
    fun captureBrowseSource(expectedClient: SpofifyClient): () -> Boolean {
        val session = expectedClient.currentSession()
        val page = state.value.page
        val sourceGeneration = generation
        return { !cleared && graph.client === expectedClient && session != null &&
            expectedClient.currentSession() === session && state.value.page == page && generation == sourceGeneration }
    }

    private fun captureExplicitPlayOrigin(): () -> Boolean {
        val client = graph.client
        val session = client?.currentSession()
        val account = state.value.session?.accountId
        val route = state.value.page
        val routeGeneration = generation
        val playbackEpoch = PlaybackRequests.epoch
        return { !cleared && client != null && session != null && account != null &&
            graph.client === client && client.currentSession() === session && state.value.session?.accountId == account &&
            state.value.page == route && generation == routeGeneration && playbackEpoch == PlaybackRequests.epoch }
    }
    private val likeOperations = LikeOperations(viewModelScope, { graph.client },
        changed = { id, liked ->
            mutable.update { it.copy(likes = it.likes + (id to liked), failedLikeStatus = it.failedLikeStatus - id) }
        },
        pending = { id, pending -> mutable.update { it.copy(pendingLikes = if (pending) it.pendingLikes + id else it.pendingLikes - id) } },
        failed = { error -> mutable.update { it.copy(notice = message(error)) } },
        statusFailed = { id -> mutable.update { it.copy(failedLikeStatus = it.failedLikeStatus + id) } })

    init {
        playback.bindExplicitPlayOrigin(::captureExplicitPlayOrigin)
        viewModelScope.launch {
            graph.sessionNeedsReconnect.collect { needsReconnect ->
                if (needsReconnect && graph.sessionNeedsReconnect.value && !signingOut &&
                    state.value.session != null && graph.client?.currentSession() == null) {
                    generation++
                    queueGeneration++
                    loadJob?.cancel(); queueJob?.cancel()
                    clearFeatureState(); tabStacks.clear()
                    stack.clear(); stack.add(Page.Home)
                    playback.stop()
                    // Use the existing SignIn saved-connection Retry action. Do not
                    // clear auth or cancel an explicit reconnect already in flight.
                    mutable.update { freshState(server = it.server, busy = it.busy,
                        error = "Saved connection needs verification. Retry to reconnect.")
                        .copy(canRetryConnection = true) }
                }
            }
        }
        viewModelScope.launch {
            graph.sessionExpired.collect { expired ->
                if (expired && !signingOut) {
                    authenticationSerial++
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
        val attempt = ++authenticationSerial
        val client = graph.client
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val session = graph.restore()
                val restoredClient = graph.client
                if (attempt != authenticationSerial || (client != null && restoredClient !== client) ||
                    restoredClient?.currentSession() !== session) return@launch
                mutable.update { it.copy(restoring = false, busy = false, canRetryConnection = false, session = session,
                    server = if (session != null) restoredClient!!.server.origin else it.server) }
                if (session != null) {
                    playback.connect()
                    load(Page.Home)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attempt != authenticationSerial || (client != null && graph.client !== client)) return@launch
                mutable.update { it.copy(restoring = false, busy = false, canRetryConnection = true,
                    error = "Could not reconnect. Check your network or retry the saved connection.") }
            }
        }
    }

    fun signIn(address: String, username: String, password: String) {
        if (state.value.busy) return
        val attempt = ++authenticationSerial
        var client = graph.client
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val server = ServerAddress.parse(address)
                queueGeneration++; generation++
                queueJob?.cancel(); loadJob?.cancel()
                clearFeatureState(); tabStacks.clear()
                playback.stop()
                val active = graph.configure(server)
                client = active
                val session = active.login(username.trim(), password)
                if (attempt != authenticationSerial || graph.client !== active || active.currentSession() !== session) return@launch
                var notice: String? = null
                try {
                    graph.saveLogin(active, session.username, password, session)
                    // Read back only after a successful durable write; failures retain the old record.
                    remembered = graph.rememberedLogin()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    notice = "Signed in. Could not save sign-in details securely."
                }
                if (attempt != authenticationSerial || graph.client !== active || active.currentSession() !== session) return@launch
                stack.clear(); stack.add(Page.Home)
                mutable.value = freshState(server = server.origin, session = session, notice = notice)
                playback.connect()
                load(Page.Home)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attempt != authenticationSerial || graph.client !== client) return@launch
                mutable.update { it.copy(busy = false, error = message(error)) }
            }
        }
    }

    fun logout() {
        if (state.value.busy) return
        authenticationSerial++
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
            mutable.update { it.copy(rememberLogin = true, hasSavedLogin = remembered != null, error = null) }
        } catch (_: Exception) {
            mutable.update { if (it.session == null) it.copy(error = "Could not update saved sign-in details. Please try again.")
                else it.copy(notice = "Could not forget saved sign-in details. Please try again.") }
        }
    }

    fun forgetSavedLogin() = setRememberLogin(false)

    /** Explicit bottom-nav Search intent, including reselect; not an ordinary tab return. */
    fun activateSearch() {
        val client = graph.client ?: return
        val account = state.value.session?.accountId ?: return
        if (state.value.busy || client.currentSession()?.accountId != account) return
        val holder = catalogHolder() ?: return
        holder.activateSearchRoot()
        if (state.value.tab != Tab.SEARCH) tabStacks[state.value.tab] = stack.toList()
        val root = Page.Search()
        stack.clear(); stack.add(root)
        tabStacks[Tab.SEARCH] = listOf(root)
        loadJob?.cancel(); generation++
        val activation = ++searchActivationSerial
        mutable.update { it.copy(tab = Tab.SEARCH, page = root, canGoBack = false,
            searchActivationId = activation, loading = false, error = null,
            items = emptyList(), playlists = emptyList(), total = 0, offset = 0) }
    }

    fun consumeSearchActivation(expectedClient: SpofifyClient, expectedAccount: String?, activation: Long) {
        if (!ownsSearchActivation(expectedClient, expectedAccount)) return
        mutable.update { if (it.searchActivationId == activation) it.copy(searchActivationId = null) else it }
    }

    /** Stop a pending keyboard delivery when its host/client/lifecycle is leaving. */
    fun cancelSearchActivation(expectedClient: SpofifyClient, expectedAccount: String?) {
        if (ownsSearchActivation(expectedClient, expectedAccount)) mutable.update { it.copy(searchActivationId = null) }
    }

    private fun ownsSearchActivation(client: SpofifyClient, account: String?): Boolean =
        account != null && graph.client === client && state.value.session?.accountId == account &&
            client.currentSession()?.accountId == account

    fun switchTab(tab: Tab) {
        if (!ownsNavigation() || tab == state.value.tab) return
        tabStacks[state.value.tab] = stack.toList()
        stack.clear()
        val page = when (tab) {
            Tab.HOME -> Page.Home
            Tab.SEARCH -> Page.Search()
            Tab.PLAYLISTS -> Page.Playlists
            Tab.PROFILE -> Page.Profile
        }
        stack.addAll(tabStacks[tab] ?: listOf(page))
        mutable.update { it.copy(tab = tab, searchActivationId = null) }
        load(stack.last())
    }

    fun open(page: Page) {
        if (!ownsNavigation()) return
        if (page is Page.Playlist) playlistBrowseMemory()?.openPlaylist(page.playlist.id)
        mutable.update { it.copy(searchActivationId = null) }
        val destination = if (page is Page.Detail) page.copy(navigationId = ++navigationSerial) else page
        stack.add(destination)
        load(destination)
    }

    private fun ownsNavigation(): Boolean {
        val account = state.value.session?.accountId ?: return false
        return !state.value.busy && graph.client?.currentSession()?.accountId == account
    }

    fun back() {
        if (!ownsNavigation() || stack.size <= 1) return
        mutable.update { it.copy(searchActivationId = null) }
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
        if (current.page == Page.Likes && !current.loading && current.error == null && !current.likesStale && !current.likesEnded &&
            current.offset < current.total) load(current.page, append = true)
    }

    /** A failed append is retried at its retained raw offset, never by resetting the prefix. */
    fun retryLikes() {
        val current = state.value
        if (current.page != Page.Likes || current.loading || current.error == null || current.likesStale) return
        // Bypass restoration once, without erasing the latch if this attempt is canceled.
        load(Page.Likes, append = current.offset > 0, retry = true)
    }

    private fun load(page: Page, append: Boolean = false, retry: Boolean = false) {
        val client = graph.client ?: return
        val session = client.currentSession() ?: return
        if (state.value.session == null || append && state.value.loading) return
        loadJob?.cancel()
        val request = ++loadSerial
        // Read lifetime is not source/action lifetime: an unchanged held row survives append.
        if (!append) generation++
        // These routes own independent bounded state holders. Never prefetch a global catalog or
        // decode rules playlists through the old manual-only compatibility API.
        if (page != Page.Likes) {
            mutable.update { it.copy(page = page, canGoBack = stack.size > 1, loading = false,
                error = null, likesStale = false, items = emptyList(), playlists = emptyList(), total = 0, offset = 0) }
            return
        }
        val cached = likesCache
        if (!append && !retry && !likesDirty && (cached != null || likesFailure != null)) {
            mutable.update { it.copy(page = page, canGoBack = stack.size > 1, loading = false,
                error = likesFailure, likesStale = likesStale, likesEnded = likesEnded, likesGeneration = generation,
                items = cached?.items.orEmpty(), total = cached?.total ?: 0, offset = likesNextOffset) }
            return
        }
        if (!append && !retry) { likesFailure = null; likesStale = false; likesEnded = false }
        val offset = if (append) state.value.offset else 0
        val expectedTotal = state.value.total
        val personalRevision = client.personalRevision.value.likes
        fun isCurrent() = request == loadSerial && graph.client === client && client.currentSession() === session &&
            state.value.page == Page.Likes && state.value.session?.accountId == session.accountId
        mutable.update { it.copy(page = page, canGoBack = stack.size > 1, loading = true, error = null, likesStale = likesStale, likesEnded = likesEnded,
            likesGeneration = if (append) it.likesGeneration else generation,
            items = if (append || it.page == page) it.items else emptyList(), playlists = if (append) it.playlists else emptyList(),
            total = if (append) it.total else 0, offset = offset) }
        loadJob = viewModelScope.launch {
            try {
                val result = client.likes(offset)
                if (!isCurrent()) return@launch
                if (result.offset != offset || result.total < 0 || result.limit != 40 ||
                    result.items.size > result.limit || offset.toLong() + result.items.size > result.total.toLong() ||
                    result.items.any { it.kind != "track" || it.id.isBlank() })
                    throw IllegalArgumentException("Could not continue liked songs. Retry this batch.")
                if (append && (result.total != expectedTotal || client.personalRevision.value.likes != personalRevision)) {
                    likesStale = true
                    generation++
                    throw IllegalArgumentException("Liked songs changed. Refresh to load the current list.")
                }
                val prefix = if (append) state.value.items else emptyList()
                val identities = prefix.associateBy { it.id }.toMutableMap()
                val added = result.items.filter { item ->
                    val previous = identities.put(item.id, item)
                    if (previous != null && previous != item) {
                        likesStale = true
                        generation++
                        throw IllegalArgumentException("Liked songs changed. Refresh to load the current list.")
                    }
                    previous == null
                }
                val items = prefix + added
                if (append && result.items.isNotEmpty() && items.size == prefix.size)
                    throw IllegalArgumentException("Could not continue liked songs. Retry this batch.")
                // Empty pages stop at the real available frontier without repeatedly requesting it.
                val next = offset + result.items.size
                val total = result.total
                likesEnded = result.items.isEmpty() || next >= total
                likesNextOffset = next
                likesCache = result.copy(items = items, offset = 0, total = total)
                likesFailure = null
                likesDirty = false
                mutable.update { it.copy(items = items, total = total,
                    offset = next, loading = false, error = null, likesStale = false, likesEnded = likesEnded) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isCurrent()) {
                    likesFailure = message(error)
                    likesNextOffset = offset
                    // Keep exact failed batch and its latch even after Back/tab restoration.
                    likesDirty = false
                    mutable.update { it.copy(loading = false, error = likesFailure, likesStale = likesStale,
                        likesGeneration = if (likesStale) generation else it.likesGeneration) }
                }
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
        if (!shuffle) {
            val item = when (val page = state.value.page) {
                Page.Likes -> LibraryItem("likes", "likes", "Liked songs")
                is Page.Playlist -> LibraryItem("playlist", page.playlist.id, page.playlist.name)
                is Page.Detail -> page.item
                else -> return
            }
            graph.client?.let { queueIntentFrom(it, item, PlaybackListAction.PLAY_NOW) }
            return
        }
        when (val page = state.value.page) {
            Page.Likes -> prepare(null, shuffle, context = LibraryItem("likes", "", "Liked songs")) { it.likedPlayback() }
            is Page.Playlist -> prepare(null, shuffle) { it.playlistPlayback(page.playlist.id) }
            is Page.Detail -> play(page.item, shuffle)
            else -> Unit
        }
    }

    fun playFrom(expectedClient: SpofifyClient, item: LibraryItem, shuffle: Boolean = false) {
        if (shuffle) { if (graph.client === expectedClient && state.value.session != null) play(item, true) }
        else queueIntentFrom(expectedClient, item, PlaybackListAction.PLAY_NOW)
    }

    /** Source/Host captures the exact selection and binding before the admission lane awaits.
     * Collections are resolved once by the receiver; row L/H/Next never fetch the context here. */
    fun queueIntentFrom(expectedClient: SpofifyClient, item: LibraryItem, action: PlaybackListAction,
        expectedKey: PlaybackInteractionKey? = playback.state.value.interactionKey,
        callerIsCurrent: () -> Boolean = { true },
        expectedBinding: dev.spofify.mobile.core.QueueIntentBinding? = playback.state.value.queueIntentBinding,
        unselectedOrigin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null) {
        if (cleared || !callerIsCurrent() || graph.client !== expectedClient || state.value.session == null ||
            !item.playable && item.kind !in setOf("likes", "playlist", "derived")) return
        val source = state.value.page
        // Catalog navigation is nested inside its outer route. Capture the displayed
        // collection once, without creating a holder or borrowing a later screen at
        // admission. The same source incarnation guards every asynchronous boundary.
        val holder = when (source) {
            is Page.Detail -> catalogHolders[catalogKey(source.item, source.navigationId)]
            is Page.Search, is Page.Catalog -> catalogHolders[catalogKey(null)]
            else -> null
        }
        val screen = holder?.state?.value?.current
        val version = holder?.navigationVersion
        val collection = if (holder != null) screen?.item else (source as? Page.Detail)?.item
        val context = when {
            source == Page.Likes && item.kind == "track" -> PlaybackContext("likes", "likes", "Liked songs")
            source is Page.Playlist && item.kind == "track" -> PlaybackContext("playlist", source.playlist.id, source.playlist.name)
            item.kind == "track" -> collection?.takeIf { it.kind in setOf("album", "artist") }
                ?.let { PlaybackContext(it.kind, it.id, it.name) }
            else -> null
        }
        val sourceIsCurrent = {
            callerIsCurrent() && (holder == null || holder.navigationVersion == version && holder.state.value.current.let {
                it.key == screen?.key && it.resultGeneration == screen?.resultGeneration && !it.detailUnavailable
            })
        }
        queueIntentSelectionFrom(expectedClient, queueIntentSelection(item, context), action, expectedKey, sourceIsCurrent, expectedBinding, unselectedOrigin)
    }

    fun queueIntentSelectionFrom(expectedClient: SpofifyClient, selection: QueueIntentSelection,
        action: PlaybackListAction, expectedKey: PlaybackInteractionKey? = playback.state.value.interactionKey,
        callerIsCurrent: () -> Boolean = { true },
        expectedBinding: dev.spofify.mobile.core.QueueIntentBinding? = playback.state.value.queueIntentBinding,
        unselectedOrigin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null) {
        if (cleared || !callerIsCurrent() || graph.client !== expectedClient || state.value.session == null || state.value.busy) return
        if (expectedKey != null && playback.state.value.interactionKey !== expectedKey) return
        val binding = expectedBinding
        if (action == PlaybackListAction.PLAY_NOW) {
            ++queueGeneration; queueJob?.cancel(); queueJob = null; cancelListPreparations()
            mutable.update { it.copy(playbackIntentGeneration = queueGeneration) }
        }
        prepareListAction(null, null, action, expectedKey, null, callerIsCurrent,
            selection = selection, expectedBinding = binding, unselectedOrigin = unselectedOrigin) { PlaybackQueue(emptyList(), total = 0) }
    }

    fun enqueueFrom(expectedClient: SpofifyClient, item: LibraryItem, playNext: Boolean = false) {
        if (graph.client !== expectedClient || state.value.session == null) return
        queueIntentFrom(expectedClient, item, if (playNext) PlaybackListAction.PLAY_NEXT else item.defaultQueueAction())
    }

    /** A held row supplies its original binding, never a later UI isPlaying decision. */
    fun contextualFrom(expectedClient: SpofifyClient, item: LibraryItem, expectedKey: PlaybackInteractionKey,
        callerIsCurrent: () -> Boolean = { true }) {
        if (!callerIsCurrent() || graph.client !== expectedClient || state.value.session == null ||
            playback.state.value.interactionKey != expectedKey) return
        queueIntentFrom(expectedClient, item, item.defaultQueueAction(), expectedKey, callerIsCurrent)
    }

    /** Signed/manual playlist selection stays prepared; no token or occurrence ID is repackaged. */
    fun preparedListAction(expectedClient: SpofifyClient, expectedAccountId: String?, queue: PlaybackQueue,
        startTrackId: String?, startEntryId: String?, action: PlaybackListAction,
        expectedKey: PlaybackInteractionKey?, expectedGeneration: Int = queueGeneration,
        callerIsCurrent: () -> Boolean = { true }) {
        if (!callerIsCurrent() || expectedGeneration != queueGeneration || graph.client !== expectedClient || state.value.busy ||
            expectedAccountId == null || state.value.session?.accountId != expectedAccountId ||
            expectedClient.currentSession()?.accountId != expectedAccountId) return
        if (action == PlaybackListAction.CONTEXTUAL &&
            (expectedKey == null || playback.state.value.interactionKey != expectedKey)) return
        if (!preparedSelectionValid(queue, expectedClient)) {
            mutable.update { it.copy(notice = "This prepared selection expired. Reload the collection and choose again.") }
            return
        }
        // Already-prepared manual/derived actions share the same FIFO admission lane as
        // catalog rows. Selection IDs remain separate parameters, never fabricated entryId.
        prepareListAction(startTrackId, startEntryId, action, expectedKey, null, callerIsCurrent) { queue }
    }

    /** A prepared playlist callback is authorized only for the client/account that rendered it. */
    fun playPrepared(expectedClient: SpofifyClient, expectedAccountId: String?, queue: PlaybackQueue,
        startTrackId: String?, startEntryId: String?, expectedGeneration: Int = queueGeneration) {
        if (expectedGeneration != queueGeneration || graph.client !== expectedClient || state.value.busy || state.value.session?.accountId != expectedAccountId ||
            expectedClient.currentSession()?.accountId != expectedAccountId || expectedAccountId == null) return
        queueGeneration++; queueJob?.cancel(); cancelListPreparations()
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
        val request = queueGeneration
        val playbackEpoch = PlaybackRequests.epoch
        val session = expectedClient.currentSession()
        val route = state.value.page
        val routeGeneration = generation
        fun originLifetime() = !cleared && playbackEpoch == PlaybackRequests.epoch && graph.client === expectedClient &&
            session != null && expectedClient.currentSession() === session &&
            state.value.session?.accountId == expectedAccountId && state.value.page == route && generation == routeGeneration
        playback.playPreparedGuarded(queue, expectedClient, startTrackId, startEntryId = startEntryId,
            isCurrent = { request == queueGeneration && originLifetime() }, admissionIsCurrent = ::originLifetime)
        if (queue.truncated) mutable.update { it.copy(notice = "The selected device loaded an incomplete queue.") }
    }

    private fun preparedSelectionValid(queue: PlaybackQueue, client: SpofifyClient): Boolean {
        if (queue.context?.kind != "derived" && queue.preparedState?.context?.kind != "derived") return true
        val prepared = queue.preparedState ?: return false
        return queue.selectionAuthority?.isValidFor(client, prepared) == true
    }

    private fun cancelListPreparations() {
        listPreparationJobs.toList().forEach { it.cancel() }
        listPreparationJobs.clear()
        listActionTickets.clear()
        listPreparationTail = null
    }

    /** FIFO preparation and acknowledged dispatch. A second Add/swipe never cancels the first.
     * Held gestures keep their original key. Already-committed tickets may follow only an
     * exact additive receipt from their own predecessor, never an external edit or fresh sample. */
    private fun prepareListAction(startTrackId: String?, startEntryId: String?, action: PlaybackListAction,
        expectedKey: PlaybackInteractionKey?, context: LibraryItem?, callerIsCurrent: () -> Boolean,
        selection: QueueIntentSelection? = null,
        expectedBinding: dev.spofify.mobile.core.QueueIntentBinding? = null,
        unselectedOrigin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null,
        fetch: suspend (SpofifyClient) -> PlaybackQueue) {
        val client = graph.client ?: return
        if (state.value.session == null || state.value.busy || !callerIsCurrent()) return
        // A newer additive intent supersedes only an older, still-uninstalled replacement
        // preparation. Otherwise that delayed Play could erase an already accepted Add.
        // Earlier additive requests retain their own admission order and are not superseded.
        if (queueJob?.isActive == true) {
            ++queueGeneration
            queueJob?.cancel()
            queueJob = null
            mutable.update { it.copy(playbackIntentGeneration = queueGeneration) }
        }
        if (listPreparationGeneration != queueGeneration) {
            cancelListPreparations()
            listPreparationGeneration = queueGeneration
        }
        if (listPreparationJobs.size >= 1_000) {
            mutable.update { it.copy(notice = "Too many pending queue actions. Wait for the current actions to finish.") }
            return
        }
        val request = queueGeneration
        val feedbackRequest = ++actionFeedbackSerial
        val playbackEpoch = PlaybackRequests.epoch
        val session = client.currentSession()
        val account = state.value.session?.accountId
        val route = state.value.page
        val routeGeneration = generation
        val predecessor = listPreparationTail
        val ticket = ListActionTicket(expectedKey, expectedBinding)
        listActionTickets.add(ticket)
        fun isCurrent() = !cleared && callerIsCurrent() && request == queueGeneration && playbackEpoch == PlaybackRequests.epoch &&
            graph.client === client && account != null && client.currentSession() === session &&
            state.value.session?.accountId == account && state.value.page == route && generation == routeGeneration
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                predecessor?.join()
                if (!isCurrent()) return@launch
                val queue = fetch(client)
                if (!isCurrent()) return@launch
                if (selection == null && queue.items.isEmpty()) {
                    if (feedbackRequest == actionFeedbackSerial) mutable.update { it.copy(notice = "No audio is available in this collection.") }
                    return@launch
                }
                if (!preparedSelectionValid(queue, client)) {
                    if (feedbackRequest == actionFeedbackSerial) mutable.update { it.copy(notice = "This prepared selection expired. Reload the collection and choose again.") }
                    return@launch
                }
                val result = suspendCancellableCoroutine<PlaybackActionResult> { continuation ->
                    val complete: (PlaybackActionResult) -> Unit = { accepted ->
                        if (continuation.isActive) {
                            if (isCurrent()) accepted.additiveTransition?.let { receipt ->
                                listActionTickets.forEach { pending ->
                                    if (pending !== ticket && pending.serial <= receipt.committedThrough &&
                                        pending.key === receipt.before && pending.binding == receipt.beforeBinding) {
                                        pending.key = receipt.after
                                        pending.binding = receipt.afterBinding
                                    }
                                }
                            }
                            continuation.resume(accepted)
                        }
                    }
                    val live = { continuation.isActive && isCurrent() }
                    if (selection != null && action == PlaybackListAction.QUEUE_HIGH && unselectedOrigin != null)
                        playback.queueHighFromUnselected(selection, client, unselectedOrigin, live, complete)
                    else if (selection != null) playback.queueIntent(selection, client, action, ticket.key, ticket.binding, live, complete)
                    else playback.performPreparedAction(queue, client, action, ticket.key, startTrackId, startEntryId, context,
                        isCurrent = live, onResult = complete)
                }
                if (isCurrent() && feedbackRequest == actionFeedbackSerial) {
                    val feedback = PlaybackFeedback.from(result, ::isCurrent, action)
                    mutable.update {
                        // Contextual diagnostics have no transient collector. Never persist success copy.
                        val diagnostic = result.userMessage.takeUnless {
                            feedback != null || result.status in setOf(
                                PlaybackActionStatus.PLAYING, PlaybackActionStatus.ADDED, PlaybackActionStatus.LIMITED)
                        }
                        it.copy(notice = diagnostic, feedback = nextPlaybackFeedback(it.feedback, feedback))
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isCurrent() && feedbackRequest == actionFeedbackSerial) mutable.update { it.copy(notice = message(error), feedback = null) }
            }
        }
        listPreparationJobs.add(job)
        listPreparationTail = job
        job.invokeOnCompletion {
            listActionTickets.remove(ticket)
            listPreparationJobs.remove(job)
            if (listPreparationTail === job) listPreparationTail = null
            mutable.update { it.copy(preparing = queueJob?.isActive == true || listPreparationJobs.any { pending -> pending.isActive }) }
        }
        mutable.update { it.copy(preparing = true) }
        job.start()
    }

    private fun prepare(start: LibraryItem?, shuffle: Boolean, listAction: PlaybackListAction? = null,
        expectedKey: PlaybackInteractionKey? = null, context: LibraryItem? = null,
        callerIsCurrent: () -> Boolean = { true }, fetch: suspend (SpofifyClient) -> PlaybackQueue) {
        if (listAction != null) {
            prepareListAction(start?.id, start?.entryId, listAction, expectedKey, context, callerIsCurrent, fetch = fetch)
            return
        }
        val client = graph.client ?: return
        if (state.value.session == null || state.value.busy) return
        queueJob?.cancel(); cancelListPreparations()
        val request = ++queueGeneration
        ++actionFeedbackSerial
        // Capture BEFORE fetching: PlaybackConnection cannot tell a late queue response from
        // a new tap if pause, output handoff or logout invalidates playback while we await it.
        val playbackEpoch = PlaybackRequests.epoch
        val account = state.value.session?.accountId
        val session = client.currentSession()
        val route = state.value.page
        val routeGeneration = generation
        fun originLifetime() = !cleared && callerIsCurrent() && playbackEpoch == PlaybackRequests.epoch &&
            graph.client === client && account != null && client.currentSession() === session &&
            state.value.session?.accountId == account && state.value.page == route && generation == routeGeneration
        fun isCurrent() = request == queueGeneration && originLifetime()
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
                    playback.playPreparedGuarded(queue, client, start?.id, shuffle, startEntryId = start?.entryId,
                        context = context, isCurrent = ::isCurrent, admissionIsCurrent = ::originLifetime)
                    if (queue.truncated) mutable.update { it.copy(notice = "The selected device loaded an incomplete queue.") }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isCurrent()) mutable.update { it.copy(notice = message(error)) }
            } finally {
                if (request == queueGeneration) mutable.update { it.copy(preparing = listPreparationJobs.any { pending -> pending.isActive }) }
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
        queueJob?.cancel(); cancelListPreparations()
        mutable.update { it.copy(preparing = false, playbackIntentGeneration = queueGeneration) }
        playback.stop()
    }

    fun clearNotice() { mutable.update { it.copy(notice = null) } }

    /** A completed timer must never consume a newer identical action. */
    fun consumeFeedback(id: Long) {
        mutable.update { state ->
            if (state.feedback?.id != id) state else state.copy(feedback = null)
        }
    }

    private fun message(error: Exception): String = when (error) {
        is ApiException -> error.message
        is IllegalArgumentException -> error.message ?: "Check the server URL."
        is IOException -> "Cannot reach your server. Check the address, network and TLS certificate."
        else -> "Something went wrong. Please try again."
    }

    override fun onCleared() {
        cleared = true // Even a retained callback invoked AFTER disposal cannot capture a fresh origin.
        // Retire only this host's still-pending intent, never healthy service-owned audio.
        queueGeneration++
        generation++
        authenticationSerial++
        clearFeatureState()
        playback.release() // Service keeps playing across Activity recreation.
        super.onCleared()
    }
}
