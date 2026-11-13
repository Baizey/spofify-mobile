package dev.spofify.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SessionStoreOwner
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.core.AuthenticationChange
import dev.spofify.mobile.core.AuthenticationChangeKind
import dev.spofify.mobile.core.ObservedAuthenticatedScope
import dev.spofify.mobile.playback.cache.SongCache
import dev.spofify.mobile.playback.cache.AutoCacheSettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.spofify.mobile.playback.PlaybackService
import dev.spofify.mobile.playback.Media3PlaybackEndpoint
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.feature.devices.PlaybackDeviceIdentityStore
import dev.spofify.mobile.playback.PlaybackRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpofifyApplication : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(private val context: Context, store: SessionStore = EncryptedSessionStore(context),
    private val logins: LoginStore = EncryptedLoginStore(context),
    audioCacheLimits: SongCache.Limits? = null,
    private val authenticationTimeMillis: () -> Long = System::currentTimeMillis,
    private val offlineEligibilityStore: OfflineEligibilityStore = EncryptedOfflineEligibilityStore(context)) {
    private val restoreMutex = Mutex()
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val deliberateOffline = MutableStateFlow(preferences.getBoolean("offline_mode", false))
    val offlineMode = deliberateOffline.asStateFlow()
    @Volatile private var offlineRevision = 0L // Mode edges only; namespace retirement has its own publication revision.
    // Shared across graph replacement to serialize publication/security reservations.
    // No core auth, storage-owner locks, cache or media under this tiny lock. The journal
    // has its own leaf mutex; it never calls back here or acquires localStorageLock.
    private companion object { val localPublicationLock = Any() }
    private val localStorageLock = Any()
    private var localPublicationRevision = 0L
    private var offlineModeChanging = false // Protected by localPublicationLock.
    // A revoked/older-epoch encrypted record is retirement evidence ONLY, never a grant.
    private val initialEligibilityRead = runCatching { offlineEligibilityStore.read() }
    private var knownRetirementScope = initialEligibilityRead.getOrNull()
    private val accountJournal = OfflineAccountJournal.obtain(context, preferences)
    private val initialAccountHistory = runCatching {
        // Unavailable storage is not confirmed absence: never initialize a new journal
        // until we know whether an existing protected record requires one.
        initialEligibilityRead.getOrThrow()
        val history = accountJournal.seedLegacy(knownRetirementScope)
        knownRetirementScope?.takeUnless { it.accountEpochPresent }?.let { legacy ->
            // Format-only preservation of an ALREADY genuine record, never a new scope
            // or epoch grant. Protect migration even if the whole journal file is lost.
            val protected = OfflineEligibility(legacy.origin, legacy.accountId, legacy.username, legacy.localAccountEpoch)
            offlineEligibilityStore.write(protected)
            knownRetirementScope = protected
        }
        history
    }
    private var securityRetirementEpoch: Long? = null // One epoch per unfinished explicit security denial.
    private var securityEpochCommitFailed = false // Protected by localPublicationLock.
    private var localRetirementPending = preferences.getBoolean("offline_revoked", false) ||
        initialAccountHistory.isFailure || knownRetirementScope?.let {
            val history = initialAccountHistory.getOrNull()
            it.localAccountEpoch != (history?.epoch ?: 0L) || history != null &&
                (it.origin != history.origin || it.accountId != history.accountId)
        } == true
    internal val retirementScope: OfflineEligibility?
        get() = synchronized(localPublicationLock) { knownRetirementScope }
    private val localEligibilityValues = MutableStateFlow(if (localRetirementPending) null else knownRetirementScope)
    val localEligibilityState = localEligibilityValues.asStateFlow()
    val localEligibility: OfflineEligibility? get() = localEligibilityValues.value
    private data class LocalScopeBinding(
        val record: OfflineEligibility, val client: SpofifyClient, val witness: ObservedAuthenticatedScope,
    )
    @Volatile private var localScopeBinding: LocalScopeBinding? = null
    private data class LocalRetirement(val revision: Long, val source: SpofifyClient?, val old: OfflineEligibility?)
    private class RetiringClient(val source: SpofifyClient) {
        var unauthorized = false // Protected by localPublicationLock; denial only.
    }
    private var retiringClient: RetiringClient? = null
    private class UnauthorizedDrain(val source: SpofifyClient, val retirement: RetiringClient?)
    private var pendingUnauthorizedDrain: UnauthorizedDrain? = null
    private val localRevocationFailure = MutableStateFlow(initialAccountHistory.isFailure)
    val offlineStorageFailed = localRevocationFailure.asStateFlow()
    private val expired = MutableStateFlow(false)
    val sessionExpired = expired.asStateFlow()
    private val reconnectRequired = MutableStateFlow(false)
    val sessionNeedsReconnect = reconnectRequired.asStateFlow()
    @Volatile var client: SpofifyClient? = null
        private set
    var imageLoader: ImageLoader? = null
        private set
    var playbackCoordinator: PlaybackCoordinator? = null
        private set
    private var coordinationScope: CoroutineScope? = null

    // SessionStoreOwner checks the lease BEFORE reaching this delegate. A stale client
    // cannot tombstone its successor. Only genuine core install/touch writes are non-null;
    // RESET and ordinary cookie expiry must not revoke matching retained eligibility.
    private val sessions = SessionStoreOwner(object : SessionStore {
        override fun read() = store.read()
        override fun write(value: StoredSession?) {
            if (value != null) fencePersistedScopeChange(value)
            store.write(value)
        }
    })

    // Pure immutable COMMITTED snapshots only, never the SharedPreferences RAM map.
    private fun readAccountHistory() = accountJournal.snapshot.history
    private fun durableAccountEpoch(): Long? = accountJournal.snapshot.let {
        if (it.failed) null else it.history?.epoch ?: 0L
    }

    private fun accountEpochMatches(record: OfflineEligibility): Boolean {
        val snapshot = accountJournal.snapshot
        val history = snapshot.history
        return !snapshot.failed && record.localAccountEpoch == (history?.epoch ?: 0L) &&
            (history != null && record.origin == history.origin && record.accountId == history.accountId ||
                history == null && !record.accountEpochPresent)
    }

    private fun persistAccountHistory(history: OfflineAccountJournal.History, revoked: Boolean) = accountJournal.commit(history, revoked)

    /** Explicit logout/accepted-401/origin fences only. Generic memory retirement, first
     * validation, offline mode, RESET and ordinary expiry must NOT advance this epoch.
     * Repeated 401 notification/cleanup retries the same reserved epoch, never a new one. */
    private fun advanceSecurityDenialEpoch(): Boolean = synchronized(localPublicationLock) {
        runCatching {
            val history = readAccountHistory() ?: knownRetirementScope?.let {
                OfflineAccountJournal.History(it.origin, it.accountId, it.localAccountEpoch)
            }
            if (history == null) {
                check(preferences.edit().putBoolean("offline_revoked", true).commit())
            } else {
                val next = if (history.epoch == securityRetirementEpoch) history
                    else history.copy(epoch = Math.addExact(history.epoch, 1L))
                securityRetirementEpoch = next.epoch // Retain even a failed commit for a same-epoch retry.
                persistAccountHistory(next, revoked = true)
            }
        }.isSuccess.also {
            securityEpochCommitFailed = !it
            if (!it) localRevocationFailure.value = true
        }
    }

    /** Called under core auth -> SessionStoreOwner. No core calls, storage lock or media I/O.
     * Never clears history/epoch or the tombstone, including A -> B -> A. Bootstrap metadata
     * can only deny an older genuine record; it cannot replace newer durable history. */
    private fun fencePersistedScopeChange(value: StoredSession, bootstrap: Boolean = false) {
        var retired: LocalRetirement? = null
        val durable = synchronized(localPublicationLock) {
            runCatching {
                check(initialAccountHistory.isSuccess) { "Local account history migration is incomplete" }
                val old = localEligibility ?: knownRetirementScope
                if (bootstrap && (old == null || old.origin == value.server && old.accountId == value.session.accountId))
                    return
                val history = readAccountHistory()
                val baseline = history ?: old?.let { OfflineAccountJournal.History(it.origin, it.accountId, it.localAccountEpoch) }
                val changed = baseline != null && (baseline.origin != value.server || baseline.accountId != value.session.accountId)
                val next = if (bootstrap && history != null && history.epoch != old?.localAccountEpoch) history
                    else OfflineAccountJournal.History(value.server, value.session.accountId,
                        if (changed) Math.addExact(baseline!!.epoch, 1L) else baseline?.epoch ?: 0L)
                val deny = changed || old != null && (old.origin != next.origin || old.accountId != next.accountId ||
                    old.localAccountEpoch != next.epoch)
                if (deny) retired = fenceLocalEligibilityMemory()
                // Recommit even unchanged history before every genuine non-null session
                // write: a failed SharedPreferences commit may have changed MEMORY only.
                // Same-account deadline touches never increment the epoch.
                persistAccountHistory(next, revoked = deny)
            }.isSuccess.also {
                if (!it) {
                    retired = fenceLocalEligibilityMemory()
                    localRevocationFailure.value = true
                }
            }
        }
        // Post only; the auth/store-lock caller must never perform runtime retirement.
        retired?.let { ticket -> Handler(Looper.getMainLooper()).post {
            runCatching {
                drainRetiredLocalOutput(ticket)
                finishLocalRetirement(ticket)
            }.onFailure {
                synchronized(localPublicationLock) {
                    if (localPublicationRevision == ticket.revision) {
                        localRetirementPending = true
                        localRevocationFailure.value = true
                    }
                }
            }
        } }
        if (!durable) throw java.io.IOException("Could not fence retained offline access.")
    }

    // Audio bytes confer no authority. Construction is dormant: only a server-validated
    // authentication event can bind the origin/account namespace to a live session.
    internal val songCache = SongCache(context.cacheDir, audioCacheLimits ?: SongCache.Limits(),
        settingsStore = if (audioCacheLimits == null) AutoCacheSettingsStore(context.filesDir) else null)
    internal val savedDownloads = dev.spofify.mobile.feature.playlists.SavedDownloadsOwner {
        java.io.File(context.noBackupFilesDir, "saved-collections") }
    @Volatile private var audioCache: SongCache.SessionCache? = null
    private var savedOwnerRetired = false // Graph terminal fences are idempotent until configure.
    private fun knownSavedScope() = (localEligibility ?: retirementScope)?.let {
        dev.spofify.mobile.core.saved.SavedScope(it.origin, it.accountId)
    }
    private fun retireSavedOwner() {
        if (savedOwnerRetired) return
        val known = knownSavedScope() // Capture genuine cleanup evidence BEFORE memory retirement.
        savedOwnerRetired = true
        savedDownloads.retire(known)
    }
    private val cacheAuthenticationLock = Any()
    @Volatile private var cacheSource: SpofifyClient? = null
    private var authenticationRevision = -1L
    private var hadValidatedSession = false // Protected by cacheAuthenticationLock.

    // Never borrow the graph monitor: configure/clear hold it while releasing Media3,
    // whose loading thread can synchronously observe cookie expiry during a read.
    private fun authenticationChanged(source: SpofifyClient, change: AuthenticationChange) {
        // Reserve BEFORE core reads: an immediate under-auth-lock 401 fence invalidates even
        // a VALIDATED callback already waiting on storage/cache. No inverse auth-lock edge.
        val reserved = synchronized(localPublicationLock) {
            if (client !== source || change.kind == AuthenticationChangeKind.VALIDATED &&
                (offlineModeChanging || offlineMode.value)) return
            // Capture BEFORE cache/storage/core waits. An older validation must never
            // stamp its encrypted record with a successor's already-advanced epoch.
            val committed = accountJournal.snapshot
            if (change.kind == AuthenticationChangeKind.VALIDATED && (committed.failed || committed.history == null)) return
            Triple(localPublicationRevision, offlineRevision, committed.history)
        }
        var publication = reserved.first
        val validationMode = reserved.second
        val validationHistory = reserved.third
        val validationAccountEpoch = validationHistory?.epoch ?: -1L
        synchronized(cacheAuthenticationLock) {
            if (cacheSource !== source || client !== source || change.revision <= authenticationRevision) return
            authenticationRevision = change.revision
            savedDownloads.authenticationChanged(source, change)
            when (change.kind) {
                AuthenticationChangeKind.VALIDATED -> {
                    val identity = change.identity ?: return
                    val capturedHistory = validationHistory ?: return
                    if (capturedHistory.origin != identity.origin || capturedHistory.accountId != identity.accountId) return
                    if (source.validatedSessionIdentity() != identity || !publicationCurrent(source, publication, validationMode) ||
                        durableAccountEpoch() != validationAccountEpoch) return
                    val validated = source.currentSession() ?: return
                    val scope = source.observedAuthenticatedScope() ?: return
                    if (scope.origin != identity.origin || scope.accountId != identity.accountId) return
                    synchronized(localPublicationLock) {
                        if (!publicationCurrent(source, publication, validationMode) ||
                            durableAccountEpoch() != validationAccountEpoch || source.observedAuthenticatedScope() != scope) return
                        // A genuinely newer authentication is a new revocation lifetime,
                        // even while its local grant is still waiting on storage. Only
                        // repeated delivery/cleanup of the SAME denial may reuse an epoch.
                        securityRetirementEpoch = null
                    }
                    hadValidatedSession = true
                    val prior = localEligibility
                    if (prior == null || prior.localAccountEpoch != validationAccountEpoch || !retainedScopeMatches(prior, source, scope)) {
                        val retired = fenceLocalEligibilityMemory(source, publication) ?: return
                        publication = retired.revision
                        drainRetiredLocalOutput(retired)
                        if (!finishLocalRetirement(retired)) return
                        val eligible = OfflineEligibility(identity.origin, identity.accountId, validated.username, validationAccountEpoch,
                            accountEpochPresent = true)
                        synchronized(localStorageLock) {
                            if (!publicationCurrent(source, publication, validationMode) || durableAccountEpoch() != validationAccountEpoch) return
                            val saved = runCatching {
                                offlineEligibilityStore.write(eligible)
                                check(preferences.edit().putBoolean("offline_revoked", false).commit())
                            }.isSuccess
                            // Core reads remain OUTSIDE the publication lock. The subsequent
                            // revision comparison closes the read -> publish invalidation race.
                            val live = saved && source.validatedSessionIdentity() == identity
                            val published = synchronized(localPublicationLock) {
                                if (live && client === source && localPublicationRevision == publication &&
                                    offlineRevision == validationMode && !offlineModeChanging && !offlineMode.value &&
                                    source.observedAuthenticatedScope() == scope && durableAccountEpoch() == validationAccountEpoch) {
                                    knownRetirementScope = eligible
                                    securityRetirementEpoch = null
                                    securityEpochCommitFailed = false
                                    localScopeBinding = LocalScopeBinding(eligible, source, scope)
                                    localEligibilityValues.value = eligible
                                    localPublicationRevision++
                                    publication = localPublicationRevision
                                    pendingUnauthorizedDrain = null
                                    true
                                } else false
                            }
                            if (!published) {
                                // Still serialized with successor saves; never retire successor
                                // MEMORY merely because this older durable write completed late.
                                val tombstoned = runCatching { preferences.edit().putBoolean("offline_revoked", true).commit() }.getOrDefault(false)
                                val erased = runCatching { offlineEligibilityStore.revoke() }.isSuccess
                                if (!tombstoned && !erased) synchronized(localPublicationLock) {
                                    localRetirementPending = true
                                    localRevocationFailure.value = true
                                }
                            }
                        }
                    } else {
                        // Only a still-current genuine VALIDATED event may establish a cold
                        // record's baseline. The denial-only witness alone never grants access.
                        val live = source.validatedSessionIdentity() == identity
                        synchronized(localPublicationLock) {
                            if (!live || !publicationCurrent(source, publication, validationMode) ||
                                localEligibility !== prior || source.observedAuthenticatedScope() != scope ||
                                durableAccountEpoch() != validationAccountEpoch) return
                            localScopeBinding = LocalScopeBinding(prior, source, scope)
                            localPublicationRevision++
                            publication = localPublicationRevision
                            pendingUnauthorizedDrain = null
                        }
                    }
                    if (!publicationCurrent(source, publication, validationMode) || source.observedAuthenticatedScope() != scope) return
                    val bound = runCatching { songCache.bind(identity.origin, identity.accountId) }.getOrNull()
                    val accepted = synchronized(localPublicationLock) {
                        if (client === source && localPublicationRevision == publication && offlineRevision == validationMode &&
                            !offlineModeChanging && !offlineMode.value && source.observedAuthenticatedScope() == scope &&
                            durableAccountEpoch() == validationAccountEpoch) {
                            reconnectRequired.value = false
                            audioCache = bound
                            true
                        } else false
                    }
                    if (!accepted) bound?.suspend()
                }
                AuthenticationChangeKind.DORMANT -> {
                    if (hadValidatedSession) reconnectRequired.value = true
                    audioCache?.suspend(); audioCache = null; songCache.fence()
                    if (!offlineMode.value) revokePlayback(source, signedOut = false)
                }
                AuthenticationChangeKind.RESET -> {
                    // RESET/elapsed expiry are not an observed authentication rejection.
                    hadValidatedSession = false; reconnectRequired.value = false
                    audioCache?.retire(); audioCache = null
                }
                AuthenticationChangeKind.INVALIDATED, AuthenticationChangeKind.EXPIRED -> {
                    if (change.kind == AuthenticationChangeKind.INVALIDATED) {
                        if (source.currentSession() != null) return // A delayed old event cannot retire a successor.
                        val retired = synchronized(localPublicationLock) {
                            val ticket = fenceLocalEligibilityMemory(source) ?: return
                            advanceSecurityDenialEpoch() // Dedupe the under-auth-lock 401 fence.
                            ticket
                        }
                        postUnauthorizedDrain(source) // Schedule BEFORE any potentially blocking purge.
                        finishLocalRetirement(retired)
                    }
                    hadValidatedSession = false; reconnectRequired.value = false
                    audioCache = null; songCache.retire()
                    if (change.kind == AuthenticationChangeKind.EXPIRED) {
                        source.httpClient.dispatcher.cancelAll()
                        if (!offlineMode.value) revokePlayback(source)
                    }
                }
            }
        }
    }

    private fun publicationCurrent(source: SpofifyClient, revision: Long, validationMode: Long): Boolean = synchronized(localPublicationLock) {
        client === source && localPublicationRevision == revision && offlineRevision == validationMode &&
            !offlineModeChanging && !offlineMode.value
    }

    /** Called under auth: memory fence + denial-tuple commit/posting only. No core,
     * retirement/storage lock, encrypted-store, cache or media calls. */
    private fun unauthorized(source: SpofifyClient) {
        val admitted = synchronized(localPublicationLock) {
            val retirement = retiringClient?.takeIf { it.source === source }
            if (client !== source || cacheSource !== source && retirement == null) return
            retirement?.unauthorized = true
            val admitted = fenceLocalEligibilityMemory(source) != null
            if (admitted) advanceSecurityDenialEpoch()
            admitted
        }
        if (admitted) postUnauthorizedDrain(source)
    }

    private fun postUnauthorizedDrain(source: SpofifyClient) {
        val ticket = synchronized(localPublicationLock) {
            val retirement = retiringClient?.takeIf { it.source === source }
            if (client !== source || cacheSource !== source && retirement == null || pendingUnauthorizedDrain?.source === source) return
            UnauthorizedDrain(source, retirement).also { pendingUnauthorizedDrain = it }
        }
        Handler(Looper.getMainLooper()).post {
            fun current() = synchronized(localPublicationLock) {
                client === source && pendingUnauthorizedDrain === ticket &&
                    (if (ticket.retirement == null) cacheSource === source else retiringClient === ticket.retirement)
            }
            try {
                if (current() && source.currentSession() == null && current()) {
                    try { playbackCoordinator?.authLost() }
                    finally {
                        try { PlaybackService.stopActivePlayback() }
                        finally { context.stopService(Intent(context, PlaybackService::class.java)) }
                    }
                    expired.value = true
                }
            } finally {
                synchronized(localPublicationLock) { if (pendingUnauthorizedDrain === ticket) pendingUnauthorizedDrain = null }
            }
        }
    }

    private fun revokePlayback(source: SpofifyClient, signedOut: Boolean = true) {
        // Expiry is observed on audio/cache reads as well as API responses. Fence pending
        // starts synchronously; only player mutation is posted. The 401 callback must not
        // cancel its own call before the existing API error classifier sees the response.
        // onUnauthorized runs under the client's auth-generation lock: do not borrow
        // the cache lock here (cache authentication fencing also reads client authority).
        if (client !== source || source.currentSession() != null) return
        PlaybackRequests.invalidate()
        val revokedEpoch = PlaybackRequests.epoch
        val wasOffline = offlineMode.value
        Handler(Looper.getMainLooper()).post {
            if (client === source && source.currentSession() == null && revokedEpoch == PlaybackRequests.epoch &&
                wasOffline == offlineMode.value && !(offlineMode.value && localEligibility != null)) {
                playbackCoordinator?.authLost()
                PlaybackService.stopActivePlayback()
                context.stopService(Intent(context, PlaybackService::class.java))
                if (signedOut) expired.value = true
            }
        }
    }

    /** This pure, credential-free witness can DENY an existing record; it never creates one.
     * Revisions are meaningful only within the exact client. A cold record with no baseline
     * allows no observed scope, or the first matching scope, never an unobserved account ABA. */
    private fun retainedScopeMatches(expected: OfflineEligibility, source: SpofifyClient,
        observed: ObservedAuthenticatedScope?): Boolean {
        val binding = localScopeBinding?.takeIf { it.record === expected && it.client === source }
        if (observed == null) return binding == null
        if (observed.origin != expected.origin || observed.accountId != expected.accountId) return false
        return if (binding == null) observed.scopeRevision == 1L else observed == binding.witness
    }

    /** Mode-independent, nonblocking retained-record check for saved-owner policies. The
     * record must already exist; this can only deny it, never mint auth or local eligibility. */
    internal fun localEligibilityIsCurrent(expected: OfflineEligibility): Boolean {
        val source = client ?: return false
        return !localRevocationFailure.value && localEligibility === expected && accountEpochMatches(expected) &&
            source.server.origin == expected.origin && retainedScopeMatches(expected, source, source.observedAuthenticatedScope()) &&
            client === source && localEligibility === expected && accountEpochMatches(expected)
    }

    /** Actual local playback additionally requires deliberate offline mode. */
    fun localPlaybackIsCurrent(expected: OfflineEligibility): Boolean =
        offlineMode.value && localEligibilityIsCurrent(expected)

    internal fun captureLocalRead(expected: OfflineEligibility): () -> Boolean {
        val capturedClient = client
        val capturedRevision = offlineRevision
        return { localPlaybackIsCurrent(expected) && client === capturedClient && offlineRevision == capturedRevision }
    }

    private val localLibraryAccess = MutableStateFlow<OfflineLibraryAccess?>(null)
    val offlineLibraryAccess = localLibraryAccess.asStateFlow()
    // Existing trusted test/host port remains injectable; production binds only the real owner.
    private var offlineLibraryOverride = false
    internal var offlineLibrary: OfflineLibraryAccess?
        get() = localLibraryAccess.value
        set(value) { offlineLibraryOverride = true; localLibraryAccess.value = value }

    private fun refreshSavedLocalLibrary() {
        if (offlineLibraryOverride) return
        val previous = localLibraryAccess.value
        localLibraryAccess.value = null
        previous?.retire() // Read fence only; namespace deletion belongs solely to Downloads.
        val expected = localEligibility ?: return
        val source = client ?: return
        if (!localPlaybackIsCurrent(expected)) return
        val guard = captureLocalRead(expected)
        val scope = dev.spofify.mobile.core.saved.SavedScope(expected.origin, expected.accountId)
        if (!savedDownloads.bindLocal(source, scope) || !guard()) return
        val adapter = SavedOfflineLibraryAdapter(savedDownloads, expected, guard)
        val accepted = synchronized(localPublicationLock) {
            if (guard() && client === source && localEligibility === expected && !offlineLibraryOverride) {
                localLibraryAccess.value = adapter
                true
            } else false
        }
        if (!accepted) adapter.retire()
    }
    private val pendingRetiredLibraries = linkedSetOf<OfflineLibraryAccess>()

    internal fun retryOfflineCollections(expected: OfflineEligibility) {
        if (localPlaybackIsCurrent(expected))
            (offlineLibrary as? SavedOfflineLibraryAdapter)?.retryFailedPreparation()
    }

    suspend fun offlineCollections(expected: OfflineEligibility): List<OfflineSavedCollection> {
        if (!localPlaybackIsCurrent(expected)) return emptyList()
        val revision = offlineRevision
        val library = offlineLibrary ?: throw java.io.IOException("Saved library unavailable.")
        val rows = library.collections(expected.origin, expected.accountId)
        return if (revision == offlineRevision && localPlaybackIsCurrent(expected) && offlineLibrary === library) rows else emptyList()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun openOfflineMedia(expected: OfflineEligibility, reference: OfflineMediaReference): androidx.media3.datasource.DataSource? {
        if (!localPlaybackIsCurrent(expected) || reference.origin != expected.origin || reference.accountId != expected.accountId) return null
        val revision = offlineRevision
        val library = offlineLibrary ?: return null
        if (reference.owner !== library) return null
        val source = reference.openExactSnapshotOccurrence() ?: return null
        if (revision == offlineRevision && localPlaybackIsCurrent(expected) && offlineLibrary === library) return source
        runCatching { source.close() }
        return null
    }

    private fun fenceLocalEligibilityMemory(source: SpofifyClient? = client, expectedRevision: Long? = null): LocalRetirement? =
        synchronized(localPublicationLock) {
            if (client !== source || expectedRevision != null && localPublicationRevision != expectedRevision) return null
            val old = localEligibility ?: knownRetirementScope
            if (old != null) knownRetirementScope = old // Capture genuine evidence BEFORE any revoke.
            localPublicationRevision++
            localScopeBinding = null
            localRetirementPending = true
            localEligibilityValues.value = null
            offlineLibrary?.let { pendingRetiredLibraries.add(it) }
            localLibraryAccess.value = null
            offlineLibraryOverride = false
            PlaybackRequests.invalidate() // BEFORE disk, adapter retirement or cache-lock waits.
            LocalRetirement(localPublicationRevision, source, old)
        }

    private fun drainRetiredLocalOutput(retired: LocalRetirement) {
        val old = retired.old ?: return
        val drain = { PlaybackService.retireLocalEligibility(old) }
        if (Looper.myLooper() === Looper.getMainLooper()) drain() else Handler(Looper.getMainLooper()).post { drain() }
    }

    private fun finishLocalRetirement(retired: LocalRetirement): Boolean = synchronized(localStorageLock) {
        fun current() = synchronized(localPublicationLock) { localPublicationRevision == retired.revision }
        if (!current()) return@synchronized !localRevocationFailure.value
        val pending = synchronized(localPublicationLock) { pendingRetiredLibraries.toList() }
        val removed = pending.filter { runCatching { it.retire() }.isSuccess }
        synchronized(localPublicationLock) { pendingRetiredLibraries.removeAll(removed.toSet()) }
        if (!current()) return@synchronized !localRevocationFailure.value
        val securityReady = synchronized(localPublicationLock) {
            !securityEpochCommitFailed || advanceSecurityDenialEpoch()
        }
        val tombstoned = runCatching { preferences.edit().putBoolean("offline_revoked", true).commit() }.getOrDefault(false)
        // Keep encrypted scope evidence when an owner failed to retire; the tombstone
        // remains authoritative on a subsequent cold launch. Never delete saved media here.
        val ownersRetired = synchronized(localPublicationLock) { pendingRetiredLibraries.isEmpty() }
        // Preserve unknown/protected ciphertext as denial evidence after an initial read
        // failure; erasing it could disguise migrated storage as a fresh installation.
        val journalReady = initialAccountHistory.isSuccess && !accountJournal.snapshot.failed
        val erased = ownersRetired && securityReady && journalReady && runCatching { offlineEligibilityStore.revoke() }.isSuccess
        synchronized(localPublicationLock) {
            if (localPublicationRevision == retired.revision) {
                localRevocationFailure.value = !securityReady || !journalReady || pendingRetiredLibraries.isNotEmpty() || (!tombstoned && !erased)
                localRetirementPending = localRevocationFailure.value
            }
        }
        !localRevocationFailure.value
    }

    @Synchronized
    fun setOfflineMode(value: Boolean): Boolean {
        if (offlineMode.value == value) return true
        val source = client
        val captured = synchronized(localPublicationLock) {
            // Block both an in-flight validation's final publication and a callback that has
            // not reserved yet. Keep this separate from the durable retirement generation.
            offlineModeChanging = true
            offlineRevision++
            localEligibility to localPublicationRevision
        }
        var retired: LocalRetirement? = null
        try {
            // Mark cancellation benign before the central HTTP fence cancels owned transfers.
            if (value) savedDownloads.setNetworkAllowed(false)
            // Serializes with real core installation; no auth/core call under publication lock.
            source?.setOffline(value)
            val actual = if (value) source?.observedAuthenticatedScope() else null
            synchronized(localPublicationLock) {
                val eligible = captured.first
                if (value && source != null && client === source && localEligibility === eligible &&
                    localPublicationRevision == captured.second &&
                    (eligible == null && actual != null || eligible != null && !retainedScopeMatches(eligible, source, actual))) {
                    retired = fenceLocalEligibilityMemory(source, captured.second)
                }
                deliberateOffline.value = value
                offlineModeChanging = false
                PlaybackRequests.invalidate()
            }
        } finally {
            synchronized(localPublicationLock) { offlineModeChanging = false }
        }
        // Drain before preferences or retired-owner I/O. No queue starts on either edge.
        if (value) {
            endCoordination()
            imageLoader?.shutdown()
            imageLoader = null
            PlaybackService.enterOfflineMode()
        } else {
            PlaybackService.retireLocalEligibility()
            source?.let { startCoordination(it) }
        }
        retired?.let { drainRetiredLocalOutput(it); finishLocalRetirement(it) }
        if (!value) savedDownloads.setNetworkAllowed(true) // Never restarts cancelled work or output.
        refreshSavedLocalLibrary()
        return runCatching { preferences.edit().putBoolean("offline_mode", value).commit() }.getOrDefault(false)
    }

    internal fun audioCache(source: SpofifyClient, expected: Session): SongCache.SessionCache? =
        if (client === source && source.hasValidSession(expected)) audioCache else null

    private fun endCoordination() {
        PlaybackService.onServiceActiveChanged = null
        val owner = playbackCoordinator
        val scope = coordinationScope
        playbackCoordinator = null
        coordinationScope = null
        try { owner?.close() }
        finally { scope?.cancel() }
    }
    val lastServer: String get() = preferences.getString("server", "") ?: ""
    // Compatibility for the legacy signed-in account UI. Forgetting is not a future save opt-out.
    val rememberLoginEnabled: Boolean get() = true

    @Synchronized
    fun rememberedLogin(): RememberedLogin? {
        val saved = logins.read() ?: return null
        val valid = runCatching { ServerAddress.parse(saved.server).origin == saved.server }.getOrDefault(false) &&
            saved.username.isNotBlank() && saved.password.isNotEmpty()
        if (!valid) { logins.write(null); return null }
        return saved
    }

    @Synchronized
    fun setRememberLogin(enabled: Boolean) {
        if (!enabled) logins.write(null)
        // Retain this compatibility API while the account dialog still calls Forget.
        // A legacy false flag must never suppress the next successful automatic save.
        preferences.edit().remove("remember_login").commit()
    }

    @Synchronized
    fun saveLogin(expected: SpofifyClient, username: String, password: String,
        expectedSession: Session? = expected.currentSession()) {
        check(client === expected && expectedSession != null && expected.currentSession() === expectedSession &&
            username == expectedSession.username) { "The sign-in session changed" }
        logins.write(RememberedLogin(expected.server.origin, username, password))
    }

    init {
        savedDownloads.setLocalEligibility(dev.spofify.mobile.core.saved.SavedLocalEligibility { scope ->
            val expected = localEligibility
            expected != null && expected.origin == scope.origin && expected.accountId == scope.accountId &&
                localEligibilityIsCurrent(expected)
        })
        savedDownloads.setNetworkAllowed(!offlineMode.value)
        // Ignore even an old false value. Best-effort removal cannot break manual authentication,
        // and must not recreate data already deleted through the former opt-out action.
        runCatching { preferences.edit().remove("remember_login").commit() }
        var unavailable = false
        val saved = try { sessions.read() } catch (_: Exception) { unavailable = true; null }
        // Historical versions could persist B before retiring A. Trusted stored metadata
        // (even an expired cookie) may DENY A, but can never grant B or a live Session.
        saved?.let { runCatching { fencePersistedScopeChange(it, bootstrap = true) } }
        val origin = saved?.server ?: if (unavailable) lastServer else ""
        val retainedOrigin = retirementScope?.origin
        if (origin.isNotEmpty() || retainedOrigin != null)
            runCatching { configure(ServerAddress.parse(origin.ifEmpty { retainedOrigin.orEmpty() })) }
        // A transient read/configuration failure must not delete encrypted auth.
        // restore() retries safely, without granting authority from this preference.
    }

    @Synchronized
    fun configure(server: ServerAddress): SpofifyClient {
        val knownOldScope = knownSavedScope()
        savedDownloads.prepareConfigure(server.origin, knownOldScope)
        val oldClient = client
        val retirement = synchronized(localPublicationLock) {
            // Fence publication BEFORE waiting on core/store/cache. Keep the old record and
            // its exact witness baseline until core retirement has serialized with installers.
            localPublicationRevision++
            cacheSource = null
            PlaybackRequests.invalidate()
            oldClient?.let { RetiringClient(it) }.also { retiringClient = it }
        }
        var failed = false
        fun attempt(action: () -> Unit) { if (runCatching(action).isFailure) failed = true }
        // Acquire FIRST: clearSession must retire runtime auth without deleting persisted auth
        // owned by the replacement lease. Neither call may run under publication/cache locks.
        val lease = sessions.acquire()
        attempt { oldClient?.clearSession() }
        val finalScope = oldClient?.observedAuthenticatedScope()
        val retired = synchronized(localPublicationLock) {
            val record = localEligibility // Never restore a captured record after a 401 fence.
            val originChanged = oldClient != null && oldClient.server.origin != server.origin ||
                record != null && record.origin != server.origin
            val deny = originChanged || localRetirementPending || retirement?.unauthorized == true || record != null &&
                (record.origin != server.origin || oldClient != null && !retainedScopeMatches(record, oldClient, finalScope))
            val ticket = if (deny) fenceLocalEligibilityMemory(oldClient) else null
            if (originChanged) advanceSecurityDenialEpoch()
            localScopeBinding = null // Only AFTER the final old-core witness/401 reconciliation.
            retiringClient = null
            ticket
        }
        oldClient?.onAuthenticationChanged = null
        oldClient?.onUnauthorized = null
        if (retired != null) retireSavedOwner() // Terminal fence before any successor configure.
        // Fallible output teardown must not skip durable retirement or owner bookkeeping.
        attempt { PlaybackService.stopActivePlayback() }
        attempt { context.stopService(Intent(context, PlaybackService::class.java)) }
        attempt { retired?.let { drainRetiredLocalOutput(it) } }
        attempt { retired?.let { finishLocalRetirement(it) } }
        synchronized(cacheAuthenticationLock) {
            hadValidatedSession = false
            reconnectRequired.value = false
            val audio = audioCache
            audioCache = null
            attempt { audio?.suspend() }
            attempt { songCache.fence() }
            attempt { if (lastServer.isNotEmpty() && lastServer != server.origin) songCache.retire() }
        }
        attempt { endCoordination() }
        attempt { PlaybackService.stopActivePlayback() }
        attempt { context.stopService(Intent(context, PlaybackService::class.java)) }
        val images = imageLoader
        imageLoader = null
        attempt { images?.shutdown() }
        attempt { oldClient?.httpClient?.dispatcher?.cancelAll() }
        if (failed) throw java.io.IOException("Could not retire the previous connection.")
        expired.value = false
        val next = SpofifyClient(server, lease, authenticationTimeMillis)
        next.setOffline(offlineMode.value)
        savedDownloads.configure(next)
        savedOwnerRetired = false
        next.onAuthenticationChanged = { authenticationChanged(next, it) }
        // Runs under core auth: fence memory + the durable denial epoch, then post drainage.
        // Encrypted/owner/media cleanup remains outside auth, in INVALIDATED delivery.
        next.onUnauthorized = { unauthorized(next) }
        synchronized(cacheAuthenticationLock) {
            client = next
            cacheSource = next
            authenticationRevision = -1L
        }
        if (!offlineMode.value) startCoordination(next)
        refreshSavedLocalLibrary()
        preferences.edit().putString("server", server.origin).apply()
        return next
    }

    private fun startCoordination(next: SpofifyClient) {
        if (playbackCoordinator != null || offlineMode.value) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val coordinator = PlaybackCoordinator(next, Media3PlaybackEndpoint(context), scope,
            identity = { next.currentSession()?.let { PlaybackDeviceIdentityStore(context, next.server, it.accountId) } },
            mediaEventUptimeMs = { android.os.SystemClock.uptimeMillis() })
        coordinationScope = scope
        playbackCoordinator = coordinator
        PlaybackService.onServiceActiveChanged = { active ->
            if (client === next && playbackCoordinator === coordinator && !offlineMode.value &&
                !PlaybackService.isLocalOnlyQueue()) coordinator.setServiceActive(active)
        }
        // One account coordinator survives screen/activity reattachment. The identity/name
        // preference persists, but socket/session/queue/output authority never does.
        if (next.validatedSessionIdentity() != null) coordinator.retry()
        imageLoader = ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { next.httpClient })) }
            .diskCache(null).memoryCache(null).build()
    }

    suspend fun restore(): Session? = restoreMutex.withLock {
        if (offlineMode.value) return@withLock null
        val active = client ?: sessions.read()?.let { configure(ServerAddress.parse(it.server)) }
            ?: return@withLock null
        // Reattaching a screen must not null out a session used by background audio.
        active.currentSession()?.let { return@withLock it }
        val restored = active.restore()
        if (client === active) restored else null
    }

    /** Immediate user-logout fence before any coroutine or remote revocation wait. */
    @Synchronized
    fun fenceForLogout(expected: SpofifyClient? = client) {
        if (client !== expected) return
        retireSavedOwner()
        expected?.onAuthenticationChanged = null
        expected?.onUnauthorized = null
        val retired = synchronized(localPublicationLock) {
            cacheSource = null
            val ticket = fenceLocalEligibilityMemory(expected) ?: return
            advanceSecurityDenialEpoch()
            ticket
        }
        PlaybackService.stopActivePlayback()
        context.stopService(Intent(context, PlaybackService::class.java))
        cacheSource = null
        synchronized(cacheAuthenticationLock) { audioCache = null; songCache.retire() }
        finishLocalRetirement(retired)
    }

    @Synchronized
    fun clear(expected: SpofifyClient? = client) {
        if (client !== expected) return
        retireSavedOwner()
        val source = client
        source?.onAuthenticationChanged = null
        source?.onUnauthorized = null
        val retired = synchronized(localPublicationLock) {
            val ticket = fenceLocalEligibilityMemory(source) ?: return
            advanceSecurityDenialEpoch()
            client = null // Detach before ANY fallible store/cache/platform cleanup.
            cacheSource = null
            pendingUnauthorizedDrain = null
            ticket
        }
        cacheSource = null
        audioCache = null
        reconnectRequired.value = false
        expired.value = false
        val images = imageLoader
        imageLoader = null
        var failed = false
        fun attempt(action: () -> Unit) { if (runCatching(action).isFailure) failed = true }
        attempt { PlaybackService.stopActivePlayback() }
        attempt { context.stopService(Intent(context, PlaybackService::class.java)) }
        attempt { endCoordination() }
        attempt { images?.shutdown() }
        attempt { source?.httpClient?.dispatcher?.cancelAll() }
        attempt { synchronized(cacheAuthenticationLock) { hadValidatedSession = false; songCache.retire() } }
        // SessionStoreOwner retires the lease even if its delegate throws. Still clear the
        // captured client's runtime auth and attempt every remaining retirement operation.
        attempt { sessions.clear() }
        attempt { source?.clearSession() }
        attempt { if (!finishLocalRetirement(retired)) failed = true }
        if (failed || localRevocationFailure.value) throw java.io.IOException("Could not clear local sign-in data.")
    }
}
