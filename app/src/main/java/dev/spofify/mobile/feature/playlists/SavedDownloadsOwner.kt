package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.AuthenticationChange
import dev.spofify.mobile.core.AuthenticationChangeKind
import dev.spofify.mobile.core.OfflineCollectionsApi
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.ValidatedSessionIdentity
import dev.spofify.mobile.core.saved.SavedCollectionSnapshot
import dev.spofify.mobile.core.saved.SavedDownloader
import dev.spofify.mobile.core.saved.SavedLibraryReader
import dev.spofify.mobile.core.saved.SavedLibraryStore
import dev.spofify.mobile.core.saved.SavedLocalEligibility
import dev.spofify.mobile.core.saved.SavedMediaLease
import dev.spofify.mobile.core.saved.SavedScope
import dev.spofify.mobile.core.saved.SavedScopeFence
import dev.spofify.mobile.core.saved.SavedTarget
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Offline playback admission is owned elsewhere. Retain/recheck isCurrent across preparation
 * until admission; it is a SELECTION guard and includes membership/generation. Accepted media must
 * instead use access(expectedEligibility)'s identity/eligibility guard, so Unsave does not stop it.
 * Use verified reader leases, never server Play fallback. */
typealias SavedPlaybackHandler = (scope: SavedScope, snapshot: SavedCollectionSnapshot,
    occurrenceId: String?, isCurrent: () -> Boolean) -> Unit

internal data class SavedLibraryUiState(
    val collections: List<SavedCollectionSnapshot> = emptyList(),
    val working: Set<SavedTarget> = emptySet(),
    val activeTarget: SavedTarget? = null,
    val failures: Map<SavedTarget, String> = emptyMap(),
    val networkEnabled: Boolean = false,
    val ownedBytes: Long = 0,
    val storageFailure: Boolean = false,
    val bindingRevision: Long = 0,
)

/** Application owner, independent of playback output. Every store/disk call runs on IO.
 * Auth callbacks only update atomic gates/cancel jobs/enqueue maintenance: never await disk locks. */
class SavedDownloadsOwner private constructor(private val root: () -> File, ioDispatcher: CoroutineDispatcher,
    private val beforeSnapshotPublish: () -> Unit, private val beforeWorkerExit: suspend () -> Unit,
    private val beforeLocalCommit: () -> Unit, private val beforeAuthCommit: (AuthenticationChange) -> Unit,
    private val beforeIntentPersist: () -> Unit, private val beforeManifestPublish: suspend () -> Unit) {
    constructor(root: () -> File) : this(root, Dispatchers.IO, {}, {}, {}, {}, {}, {})
    internal constructor(ioDispatcher: CoroutineDispatcher, root: () -> File) : this(root, ioDispatcher, {}, {}, {}, {}, {}, {})
    internal constructor(ioDispatcher: CoroutineDispatcher, beforeSnapshotPublish: () -> Unit, root: () -> File) :
        this(root, ioDispatcher, beforeSnapshotPublish, {}, {}, {}, {}, {})
    internal constructor(ioDispatcher: CoroutineDispatcher, beforeWorkerExit: suspend () -> Unit, root: () -> File) :
        this(root, ioDispatcher, {}, beforeWorkerExit, {}, {}, {}, {})
    internal class TestHooks(val beforeAuthCommit: (AuthenticationChange) -> Unit = {},
        val beforeIntentPersist: () -> Unit = {}, val beforeManifestPublish: suspend () -> Unit = {},
        val beforeLocalCommit: () -> Unit = {})
    internal constructor(ioDispatcher: CoroutineDispatcher, hooks: TestHooks, root: () -> File) :
        this(root, ioDispatcher, {}, {}, hooks.beforeLocalCommit, hooks.beforeAuthCommit, hooks.beforeIntentPersist, hooks.beforeManifestPublish)
    private data class Binding(val client: SpofifyClient, val scope: SavedScope,
        val identity: ValidatedSessionIdentity?, val localEligibility: SavedLocalEligibility? = null) {
        constructor(client: SpofifyClient, identity: ValidatedSessionIdentity) :
            this(client, SavedScope(identity.origin, identity.accountId), identity)
    }
    private data class Lifecycle(val epoch: Long, val client: SpofifyClient?, val policy: SavedLocalEligibility?,
        val binding: Binding?, val configuration: Long)
    // Never call a client/policy predicate or disk/store locking method under this monitor.
    // Only atomic state, cancellation, presentation fencing and ordered command enqueue belong here.
    private val lifecycleLock = Any()
    private val lifecycleEpoch = AtomicLong()
    private val configurationEpoch = AtomicLong()
    private var authenticationRevision = -1L // lifecycleLock; reset for every configured client.
    private val io = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val binding = AtomicReference<Binding?>()
    private val lastScope = AtomicReference<SavedScope?>()
    private val source = AtomicReference<SpofifyClient?>()
    private val authenticated = AtomicBoolean(false)
    private val online = AtomicBoolean(true)
    private val localPolicy = AtomicReference<SavedLocalEligibility?>()
    private val storeRef = AtomicReference<SavedLibraryStore?>()
    private val jobs = ConcurrentHashMap<SavedTarget, Job>()
    private data class RetentionKey(val scope: SavedScope, val target: SavedTarget)
    private class RetentionIntent(val epoch: Long, val saved: Boolean)
    private val retentionEpochs = ConcurrentHashMap<SavedScope, AtomicLong>()
    private val retentionIntents = ConcurrentHashMap<RetentionKey, RetentionIntent>()
    private class DownloadPlan(val active: Binding, val key: RetentionKey, val intent: RetentionIntent,
        val title: String, val order: Long, val cancellationEpoch: Long) {
        val persisted = AtomicBoolean(false)
        val manifestInstalled = AtomicBoolean(false)
        val priorityPaused = AtomicBoolean(false)
        val priorityVersion = AtomicLong()
    }
    private class DemandRecord(val client: SpofifyClient, active: Boolean) { val active = AtomicBoolean(active) }
    private val demand = AtomicReference<DemandRecord?>()
    private val plans = ConcurrentHashMap<RetentionKey, DownloadPlan>()
    private val planOrder = AtomicLong()
    private val cancellationEpoch = AtomicLong()
    private val stoppingJobs = ConcurrentHashMap.newKeySet<Job>()
    private val joiningStops = AtomicBoolean(false)
    private val downloads = Mutex() // One bounded manifest/media worker; explicit actions only.
    private val mutableState = MutableStateFlow(SavedLibraryUiState())
    internal val state = mutableState.asStateFlow()
    private val readerRevision = MutableStateFlow(0L)
    private val refreshing = AtomicBoolean(false)
    private val playbackHandler = MutableStateFlow<SavedPlaybackHandler?>(null)
    val localPlayback = playbackHandler.asStateFlow()

    private fun captureLifecycle(): Lifecycle = synchronized(lifecycleLock) {
        Lifecycle(lifecycleEpoch.get(), source.get(), localPolicy.get(), binding.get(), configurationEpoch.get())
    }
    private fun lifecycleCurrent(captured: Lifecycle): Boolean = lifecycleEpoch.get() == captured.epoch &&
        source.get() === captured.client && localPolicy.get() === captured.policy && binding.get() === captured.binding &&
        lifecycleEpoch.get() == captured.epoch && configurationEpoch.get() == captured.configuration

    /** Current service record only. New records conservatively start active until their first
     * aggregate false; an old close/false cannot release or race the successor's initial demand. */
    fun playbackDemand(client: SpofifyClient): SavedPlaybackDemand {
        val record = DemandRecord(client, true)
        synchronized(lifecycleLock) {
            if (source.get() === client) {
                demand.set(record)
                pauseForPlayback(record)
                enqueue { reconcileDownloads() }
            }
        }
        return SavedPlaybackDemand(update = { active ->
            synchronized(lifecycleLock) {
                if (source.get() === client && demand.get() === record && record.active.getAndSet(active) != active) {
                    if (active) pauseForPlayback(record)
                    enqueue { reconcileDownloads() }
                }
            }
        }, release = {
            synchronized(lifecycleLock) {
                if (demand.compareAndSet(record, null)) enqueue { reconcileDownloads() }
            }
        })
    }

    private fun playbackBusy(client: SpofifyClient): Boolean = demand.get()?.let {
        it.client === client && source.get() === client && it.active.get()
    } == true

    private fun pauseForPlayback(record: DemandRecord) {
        if (demand.get() !== record || source.get() !== record.client || !record.active.get()) return
        plans.values.filter { it.active.client === record.client }.forEach {
            it.priorityPaused.set(true)
            it.priorityVersion.incrementAndGet()
        }
        jobs.values.forEach { stoppingJobs.add(it); it.cancel() }
    }

    /** Null removes the local Play affordance; no audio/output side effects on installation. */
    fun setPlaybackHandler(handler: SavedPlaybackHandler?) {
        playbackHandler.value = handler
    }

    fun playAvailable(snapshot: SavedCollectionSnapshot, occurrenceId: String? = null,
        expectedBindingRevision: Long? = null) {
        val active = synchronized(lifecycleLock) {
            binding.get()?.takeIf { presentationCurrent(expectedBindingRevision) }
        } ?: return
        val visible = state.value.collections.firstOrNull { it.target == snapshot.target && it.generation == snapshot.generation } ?: return
        val current = {
            presentationCurrent(expectedBindingRevision) && binding.get() === active && eligible(active.scope) &&
                !removing(active.scope, snapshot.target) &&
                state.value.collections.any { it.target == snapshot.target && it.generation == snapshot.generation } &&
                binding.get() === active && presentationCurrent(expectedBindingRevision)
        }
        if (!current() || visible.occurrences.none { it.available && (occurrenceId == null || it.occurrence.occurrenceId == occurrenceId) }) return
        playbackHandler.value?.invoke(active.scope, visible, occurrenceId, current)
    }

    /** Offline owner supplies a nonblocking local/dormant eligibility gate for IO/store readers.
     * A matching live validated incarnation independently permits private store access. Without an
     * injected fallback, ONLY that live incarnation is eligible; a fallback never grants network auth. */
    fun setLocalEligibility(policy: SavedLocalEligibility) {
        val committed = synchronized(lifecycleLock) {
            localPolicy.set(policy)
            lifecycleEpoch.incrementAndGet()
            readerRevision.update { it + 1 }
            enqueue { refresh() }
            Lifecycle(lifecycleEpoch.get(), source.get(), policy, binding.get(), configurationEpoch.get())
        }
        val canRead = committed.binding?.scope?.let(::eligible) == true
        mutableState.update {
            if (!lifecycleCurrent(committed)) it
            else if (canRead) it.copy(bindingRevision = it.bindingRevision + 1)
            else it.copy(collections = emptyList(), networkEnabled = false, bindingRevision = it.bindingRevision + 1)
        }
    }

    private fun eligible(scope: SavedScope): Boolean {
        val active = binding.get() ?: return false
        if (active.scope != scope || source.get() !== active.client) return false
        val policy = localPolicy.get()
        if (active.identity == null && (active.localEligibility == null || active.localEligibility !== policy)) return false
        // Live server-validated private access is distinct from delegated dormant/local eligibility.
        // The offline playback access handle also retains the root's separate mode/incarnation gate.
        val live = active.identity != null && authenticated.get() && active.client.validatedSessionIdentity() == active.identity
        val allowed = live || policy?.let { runCatching { it.eligible(scope) }.getOrDefault(false) } == true
        return allowed && binding.get() === active && source.get() === active.client
    }

    private fun networkCurrent(active: Binding): Boolean = binding.get() === active && active.identity != null &&
        source.get() === active.client && authenticated.get() && online.get() &&
        active.client.validatedSessionIdentity() == active.identity && binding.get() === active

    /** Explicit attach by the trusted offline owner, never inferred from stored downloads.
     * Requires its injected policy and this graph's exact client/origin. Does NOT create a
     * ValidatedSessionIdentity or network authority. True means admitted, not disk/media ready.
     * Replace the policy or attach again on local-authority incarnation changes. */
    fun bindLocal(client: SpofifyClient, scope: SavedScope): Boolean {
        val captured = captureLifecycle() // BEFORE the external admission predicate.
        val policy = captured.policy ?: return false
        if (captured.client !== client || client.server.origin != scope.origin ||
            !runCatching { policy.eligible(scope) }.getOrDefault(false)) return false
        beforeLocalCommit() // Test seam only; never invoked under the lifecycle monitor.
        return synchronized(lifecycleLock) {
            if (!lifecycleCurrent(captured)) return@synchronized false
            val active = Binding(client, scope, identity = null, localEligibility = policy)
            val previous = lastScope.getAndSet(scope)
            if (previous != null && previous != scope) retireIntents(previous)
            authenticated.set(false)
            binding.set(active)
            lifecycleEpoch.incrementAndGet()
            cancelWork()
            clearPresentation()
            enqueue {
                if (previous != null && previous != scope) retireStored(previous)
                if (binding.get() !== active || !eligible(scope)) return@enqueue
                val store = store()
                store.bind(scope)
                applyPendingRemovals(scope, store)
                store.setNetworkAllowed(false)
                refresh()
            }
            true
        }
    }

    /** Blocking reader surface for the offline playback owner. Call only from IO. */
    val reader: SavedLibraryReader = object : SavedLibraryReader {
        override val revision: StateFlow<Long> = readerRevision
        override fun snapshots(scope: SavedScope) = if (eligible(scope))
            storeRef.get()?.snapshots(scope).orEmpty().filter { !removing(scope, it.target) } else emptyList()
        override fun snapshot(scope: SavedScope, target: SavedTarget) =
            if (eligible(scope) && !removing(scope, target)) storeRef.get()?.snapshot(scope, target) else null
        override fun open(scope: SavedScope, target: SavedTarget, occurrenceId: String): SavedMediaLease? {
            val captured = binding.get() ?: return null
            fun current() = binding.get() === captured && captured.scope == scope && eligible(scope) && binding.get() === captured
            if (!current() || removing(scope, target)) return null
            val lease = storeRef.get()?.open(scope, target, occurrenceId) ?: return null
            if (!current()) { lease.close(); return null }
            return object : SavedMediaLease {
                override val descriptor = lease.descriptor
                override val input = object : FilterInputStream(lease.input) {
                    private fun guard() { if (!current()) throw IOException("Saved reader fenced") }
                    override fun read(): Int { guard(); return super.read().also { guard() } }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        guard(); return super.read(bytes, offset, length).also { guard() }
                    }
                    override fun skip(count: Long): Long { guard(); return super.skip(count).also { guard() } }
                    override fun available(): Int { guard(); return super.available().also { guard() } }
                    override fun close() = lease.close()
                }
                override fun close() = lease.close()
            }
        }
    }
    /** A reader namespace, not playback authority. Always capture the separate action guard. */
    fun currentScope(): SavedScope? = binding.get()?.scope?.takeIf(::eligible)

    /** The offline owner passes its captured origin/account/username/local-incarnation guard.
     * Both guards must be nonblocking. Await ordered IO bind readiness, not merely attach admission.
     * Accepted streams do NOT depend on continued membership. */
    suspend fun access(expectedEligibility: () -> Boolean): SavedLibraryAccess? {
        val captured = binding.get() ?: return null
        val current = {
            binding.get() === captured && eligible(captured.scope) &&
                runCatching(expectedEligibility).getOrDefault(false) && binding.get() === captured
        }
        if (!current()) return null
        val ready = CompletableDeferred<Boolean>()
        enqueue {
            try {
                if (!current()) ready.complete(false)
                else {
                    val store = store()
                    store.bind(captured.scope)
                    applyPendingRemovals(captured.scope, store)
                    store.setNetworkAllowed(networkCurrent(captured))
                    ready.complete(current())
                }
            } catch (error: Exception) {
                if (!current()) ready.complete(false)
                else {
                    updateFor(captured) { it.copy(storageFailure = true) }
                    ready.completeExceptionally(error)
                }
            }
        }
        if (!ready.await() || !current()) return null
        return SavedLibraryAccess(reader, captured.scope, current)
    }

    /** Real owned bytes, including retired/failed-cleanup data. Settings may query without auth. */
    suspend fun ownedBytes(): Long {
        val result = CompletableDeferred<Long>()
        enqueue {
            try { result.complete(store().ownedBytes()) }
            catch (e: Exception) { result.completeExceptionally(e) }
        }
        return result.await()
    }

    /** Terminal owned shutdown, not an auth callback. Caller must first close all reader leases
     * and stop submitting actions. Unlike logout, this preserves retained data for a new owner. */
    internal suspend fun close() = withContext(NonCancellable) {
        synchronized(lifecycleLock) {
            binding.set(null)
            source.set(null)
            demand.set(null)
            configurationEpoch.incrementAndGet()
            authenticated.set(false)
            lifecycleEpoch.incrementAndGet()
            cancelWork()
            clearPresentation()
        }
        stoppingJobs.toList().forEach { it.join() }
        val closed = CompletableDeferred<Unit>()
        enqueue {
            try { storeRef.getAndSet(null)?.close(); closed.complete(Unit) }
            catch (error: Exception) { closed.completeExceptionally(error) }
        }
        try { closed.await() }
        finally { commands.close(); io.cancel() }
    }

    private fun presentationCurrent(expectedBindingRevision: Long?): Boolean =
        expectedBindingRevision == null || mutableState.value.bindingRevision == expectedBindingRevision

    /** UI callers retain the revision of their displayed frame, never sample new authority beside
     * old metadata. Mutations must also carry that revision through the intent commit below. */
    internal fun captureAction(expectedBindingRevision: Long? = null): () -> Boolean {
        val captured = synchronized(lifecycleLock) {
            binding.get()?.takeIf { presentationCurrent(expectedBindingRevision) }
        }
        return { captured != null && presentationCurrent(expectedBindingRevision) && binding.get() === captured &&
            eligible(captured.scope) && binding.get() === captured && presentationCurrent(expectedBindingRevision) }
    }

    init {
        io.launch {
            for (command in commands) try { command() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(storageFailure = true) } }
        }
    }
    private fun enqueue(command: suspend () -> Unit) { commands.trySend(command) }
    private fun cancelWork() {
        cancellationEpoch.incrementAndGet()
        plans.values.forEach { retentionIntents.remove(it.key, it.intent) }
        plans.clear()
        jobs.values.forEach { stoppingJobs.add(it); it.cancel() }
        jobs.clear()
    }
    private fun clearPresentation() {
        mutableState.update { SavedLibraryUiState(ownedBytes = it.ownedBytes, bindingRevision = it.bindingRevision + 1) }
        readerRevision.value++
    }

    /** Called before replacing a client. knownOldScope is a trusted genuine-account cleanup
     * fallback only, never read/network authority. Runtime binding/last scope always takes priority. */
    fun prepareConfigure(origin: String, knownOldScope: SavedScope? = null) = synchronized(lifecycleLock) {
        val old = binding.getAndSet(null)
        source.set(null)
        demand.set(null)
        configurationEpoch.incrementAndGet()
        authenticationRevision = -1L
        authenticated.set(false)
        lifecycleEpoch.incrementAndGet()
        cancelWork()
        clearPresentation()
        val previous = old?.scope ?: lastScope.get() ?: knownOldScope
        if (previous != null && previous.origin != origin) {
            retireIntents(previous)
            val fence = storeRef.get()?.fenceAccess() // Pure atomic capability capture, never store/disk locks.
            lastScope.set(null)
            enqueue { retireStored(previous, fence) }
        } else {
            // Metadata-only memory permits a later genuine different-account validation to retire it.
            if (previous != null) lastScope.set(previous)
            enqueue { storeRef.get()?.setNetworkAllowed(false) }
        }
    }

    fun configure(next: SpofifyClient) = synchronized(lifecycleLock) {
        if (source.get() !== next) {
            val previous = binding.getAndSet(null)?.scope ?: lastScope.get()
            authenticated.set(false)
            demand.set(null)
            authenticationRevision = -1L
            cancelWork()
            clearPresentation()
            if (previous != null && previous.origin != next.server.origin) {
                retireIntents(previous)
                lastScope.set(null)
                val fence = storeRef.get()?.fenceAccess()
                enqueue { retireStored(previous, fence) }
            } else enqueue { storeRef.get()?.setNetworkAllowed(false) }
        }
        source.set(next)
        configurationEpoch.incrementAndGet()
        lifecycleEpoch.incrementAndGet()
        Unit
    }

    private enum class AuthCommit { APPLIED, RETRY, SUPERSEDED }

    fun authenticationChanged(client: SpofifyClient, change: AuthenticationChange) {
        val configuration = synchronized(lifecycleLock) {
            if (source.get() === client) configurationEpoch.get() else null
        } ?: return
        if (change.kind == AuthenticationChangeKind.INVALIDATED) {
            synchronized(lifecycleLock) {
                if (configurationEpoch.get() != configuration || source.get() !== client || change.revision <= authenticationRevision) return@synchronized
                authenticationRevision = change.revision
                retireLocked()
            }
            return
        }
        // Unlike a local admission, a genuine SDK event must survive benign mode/policy churn.
        // Retry validation only, never HTTP, and stop for a retired client/newer event/identity.
        while (true) {
            val captured = synchronized(lifecycleLock) {
                if (configurationEpoch.get() != configuration || source.get() !== client || change.revision <= authenticationRevision) null
                else Lifecycle(lifecycleEpoch.get(), client, localPolicy.get(), binding.get(), configuration)
            } ?: return
            val identity = client.validatedSessionIdentity() // External callbacks stay outside the monitor.
            if (change.kind == AuthenticationChangeKind.VALIDATED) {
                if (change.identity == null || identity != change.identity) return
            } else if (identity != null) return // A newer genuine live validation superseded this non-live event.
            val previous = captured.binding
            val locallyReadable = previous != null && captured.policy != null &&
                (previous.identity != null || previous.localEligibility === captured.policy) &&
                runCatching { captured.policy.eligible(previous.scope) }.getOrDefault(false)
            beforeAuthCommit(change)
            val result = synchronized(lifecycleLock) {
                when {
                    configurationEpoch.get() != configuration || source.get() !== client || change.revision <= authenticationRevision -> AuthCommit.SUPERSEDED
                    !lifecycleCurrent(captured) -> AuthCommit.RETRY
                    else -> {
                        authenticationRevision = change.revision
                        lifecycleEpoch.incrementAndGet()
                        if (change.kind == AuthenticationChangeKind.VALIDATED) {
                            val actual = checkNotNull(identity)
                            val scope = SavedScope(actual.origin, actual.accountId)
                            val alreadyLive = previous?.identity == actual && authenticated.get()
                            val chosenLocal = previous != null && previous.identity == null && previous.scope == scope && locallyReadable
                            // Do not replace a successor that already adopted this genuine identity or
                            // a newer policy-approved local incarnation of its same account.
                            if (!alreadyLive && !chosenLocal) {
                                val active = Binding(client, actual)
                                val old = lastScope.getAndSet(active.scope)
                                if (old != null && old != active.scope) retireIntents(old)
                                binding.set(active)
                                authenticated.set(true)
                                cancelWork()
                                clearPresentation()
                                enqueue {
                                    if (old != null && old != active.scope) retireStored(old)
                                    if (binding.get() !== active) return@enqueue
                                    val store = store()
                                    store.bind(active.scope)
                                    applyPendingRemovals(active.scope, store)
                                    store.setNetworkAllowed(networkCurrent(active))
                                    refresh()
                                }
                            }
                        } else {
                            // Ordinary RESET/expiry preserves an already-local eligible reader;
                            // live authority is fenced, but retained bytes are not retired.
                            val keepLocal = previous != null && previous.identity == null && locallyReadable
                            val next = when {
                                keepLocal -> previous
                                change.kind == AuthenticationChangeKind.RESET -> null
                                else -> previous?.copy()
                            }
                            authenticated.set(false)
                            binding.set(next)
                            cancelWork()
                            mutableState.update { it.copy(networkEnabled = false, working = emptySet(), activeTarget = null,
                                bindingRevision = it.bindingRevision + 1,
                                collections = if (next != null && locallyReadable) it.collections else emptyList()) }
                            readerRevision.update { it + 1 }
                            enqueue { storeRef.get()?.setNetworkAllowed(false); refresh() }
                        }
                        AuthCommit.APPLIED
                    }
                }
            }
            if (result != AuthCommit.RETRY) return
        }
    }

    /** knownOldScope may only come from a trusted genuine-auth snapshot. It cannot attach or
     * authorize a namespace; it is used solely when this owner has no runtime/remembered scope. */
    fun retire(knownOldScope: SavedScope? = null) = synchronized(lifecycleLock) {
        // Public logout is terminal for this configured ownership, even if an executing callback
        // still sees a valid SDK session while Graph.clear waits for its authentication monitor.
        source.set(null)
        demand.set(null)
        configurationEpoch.incrementAndGet()
        retireLocked(knownOldScope)
    }

    private fun retireLocked(knownOldScope: SavedScope? = null) {
        authenticated.set(false)
        val old = binding.getAndSet(null)?.scope ?: lastScope.getAndSet(null) ?: knownOldScope
        if (old != null) retireIntents(old)
        lastScope.set(null)
        lifecycleEpoch.incrementAndGet()
        cancelWork()
        clearPresentation()
        val fence = storeRef.get()?.fenceAccess()
        enqueue {
            try { retireStored(old, fence) }
            finally { refresh() }
        }
    }

    /** IO actor only. Every store bind uses this same actor, so both retirement halves finish
     * before a queued successor bind. An opaque fence can contain no namespace (cold/unbound store):
     * always apply the known-scope fallback too, loading its persisted pointer even without a bind. */
    private fun retireStored(scope: SavedScope?, fence: SavedScopeFence? = null) {
        if (scope == null && fence == null) return
        val store = store()
        if (fence != null) store.retire(fence)
        if (scope != null) store.retire(scope)
    }

    /** Explicit offline mode stops work immediately; going online never restarts anything. */
    fun setNetworkAllowed(allowed: Boolean) = synchronized(lifecycleLock) {
        online.set(allowed)
        lifecycleEpoch.incrementAndGet()
        if (!allowed) cancelWork()
        mutableState.update { it.copy(networkEnabled = allowed && authenticated.get() && binding.get()?.identity != null,
            working = if (allowed) it.working else emptySet(), activeTarget = if (allowed) it.activeTarget else null) }
        enqueue {
            if (allowed && online.get()) restoreNetworkBinding()
            else { storeRef.get()?.setNetworkAllowed(false); refresh() }
        }
    }

    /** IO actor only. Metadata from the original client, not a request or manufactured identity.
     * Mode return admits NEW explicit downloads; it never restarts cancelled jobs or plays audio. */
    private fun restoreNetworkBinding() {
        val captured = captureLifecycle()
        val previous = captured.binding ?: return
        if (captured.client !== previous.client || !online.get()) return
        val identity = previous.client.validatedSessionIdentity() // No owner monitor around client callbacks.
        if (identity == null || identity.origin != previous.scope.origin || identity.accountId != previous.scope.accountId) {
            if (lifecycleCurrent(captured)) { storeRef.get()?.setNetworkAllowed(false); refresh() }
            return
        }
        val live = synchronized(lifecycleLock) {
            if (!lifecycleCurrent(captured) || !online.get()) return@synchronized null
            val next = if (previous.identity == identity && authenticated.get()) previous else Binding(previous.client, identity)
            binding.set(next)
            authenticated.set(true)
            lifecycleEpoch.incrementAndGet()
            if (next !== previous) {
                cancelWork()
                mutableState.update { it.copy(working = emptySet(), activeTarget = null, bindingRevision = it.bindingRevision + 1) }
                readerRevision.update { it + 1 }
            }
            enqueue {
                if (binding.get() !== next || !networkCurrent(next) || !eligible(next.scope)) return@enqueue
                val store = store()
                store.bind(next.scope)
                applyPendingRemovals(next.scope, store)
                store.setNetworkAllowed(networkCurrent(next))
                refresh()
            }
            next
        } ?: return
        updateFor(live) { it.copy(networkEnabled = networkCurrent(live)) }
    }

    private fun store(): SavedLibraryStore {
        storeRef.get()?.let { return it }
        return SavedLibraryStore(root(), SavedLocalEligibility(::eligible),
            networkPermission = { scope -> binding.get()?.let {
                it.scope == scope && networkCurrent(it) && !playbackBusy(it.client)
            } == true }
        ).also { created ->
            storeRef.set(created)
            io.launch { created.revision.collect { readerRevision.update { it + 1 }; scheduleRefresh() } }
        }
    }

    private fun scheduleRefresh() {
        // Coalesce byte-level revisions without delaying auth/Unsave maintenance or starving progress.
        if (refreshing.compareAndSet(false, true)) io.launch {
            delay(150)
            enqueue { refreshing.set(false); refresh() }
        }
    }

    private fun retentionEpoch(scope: SavedScope): Long = retentionEpochs.computeIfAbsent(scope) { AtomicLong() }.get()
    private fun currentIntent(key: RetentionKey, intent: RetentionIntent): Boolean =
        retentionIntents[key] === intent && intent.epoch == retentionEpoch(key.scope)
    private fun removing(scope: SavedScope, target: SavedTarget): Boolean {
        val intent = retentionIntents[RetentionKey(scope, target)] ?: return false
        return !intent.saved && intent.epoch == retentionEpoch(scope)
    }
    private fun retireIntents(scope: SavedScope) {
        retentionEpochs.computeIfAbsent(scope) { AtomicLong() }.incrementAndGet()
        retentionIntents.keys.removeIf { it.scope == scope }
    }

    /** IO actor only. Retained same-scope rebinds drain accepted removals before publishing rows.
     * Real retirement invalidates their epoch; a newer Save replaces their identity ticket. */
    private fun applyPendingRemovals(scope: SavedScope, store: SavedLibraryStore) {
        for ((key, intent) in retentionIntents) {
            if (key.scope != scope || intent.saved || !currentIntent(key, intent)) continue
            val active = binding.get()?.takeIf { it.scope == scope && eligible(scope) } ?: return
            try {
                store.unsave(scope, key.target)
                retentionIntents.remove(key, intent)
            } catch (_: Exception) {
                if (retentionIntents.remove(key, intent)) updateFor(active) {
                    if (intent.epoch != retentionEpoch(scope)) it else it.copy(
                        failures = it.failures + (key.target to "Could not unsave. Try again"), storageFailure = true)
                }
            }
        }
    }

    private fun updateFor(active: Binding, transform: (SavedLibraryUiState) -> SavedLibraryUiState) {
        mutableState.update { previous ->
            if (binding.get() !== active || !eligible(active.scope) || binding.get() !== active) previous else transform(previous)
        }
    }

    private fun refresh() {
        val store = storeRef.get()
        val bytes = store?.ownedBytes() ?: state.value.ownedBytes
        val active = binding.get()
        if (active == null || !eligible(active.scope)) {
            mutableState.update { previous ->
                if (binding.get() !== active || active != null && eligible(active.scope)) previous
                else previous.copy(collections = emptyList(), networkEnabled = false, ownedBytes = bytes)
            }
            return
        }
        if (store == null) return
        val snapshots = store.snapshots(active.scope)
        updateFor(active) {
            beforeSnapshotPublish()
            it.copy(collections = snapshots.filter { row -> !removing(active.scope, row.target) },
                networkEnabled = networkCurrent(active), ownedBytes = bytes)
        }
    }

    fun save(target: SavedTarget, title: String, expectedBindingRevision: Long? = null) {
        val captured = captureLifecycle()
        val active = captured.binding ?: return
        if (!networkCurrent(active)) return
        synchronized(lifecycleLock) {
            if (!lifecycleCurrent(captured) || !presentationCurrent(expectedBindingRevision)) return@synchronized
            val key = RetentionKey(active.scope, target)
            discardPlan(key)
            val intent = RetentionIntent(retentionEpoch(active.scope), saved = true)
            val plan = DownloadPlan(active, key, intent, title, planOrder.incrementAndGet(), cancellationEpoch.get())
            retentionIntents[key] = intent
            plans[key] = plan
            mutableState.update { it.copy(working = it.working + target, failures = it.failures - target) }
            enqueue { persistPlan(plan); reconcileDownloads() }
        }
    }

    private fun planCurrent(plan: DownloadPlan): Boolean = plan.cancellationEpoch == cancellationEpoch.get() &&
        plans[plan.key] === plan && currentIntent(plan.key, plan.intent) && networkCurrent(plan.active)
    private fun workerCurrent(plan: DownloadPlan, priorityVersion: Long): Boolean = planCurrent(plan) &&
        plan.priorityVersion.get() == priorityVersion && !plan.priorityPaused.get() && !playbackBusy(plan.active.client)

    /** IO actor: metadata is retained even while playback owns transfer priority. */
    private fun persistPlan(plan: DownloadPlan) {
        if (!planCurrent(plan) || plan.persisted.get()) return
        beforeIntentPersist()
        try {
            val store = store()
            store.bind(plan.active.scope)
            applyPendingRemovals(plan.active.scope, store)
            // Applying an accepted removal may consume its ticket. Never recreate its membership.
            if (!planCurrent(plan)) return
            store.requestSave(plan.active.scope, plan.key.target, plan.title)
            plan.persisted.set(true)
            refresh()
        } catch (_: Exception) {
            updateFor(plan.active) { if (!planCurrent(plan)) it else it.copy(
                failures = it.failures + (plan.key.target to "Saved storage is unavailable"), storageFailure = true) }
            forgetPlan(plan)
        }
    }

    /** Same actor as removal: a later Unsave either rejects this publication or runs after it.
     * Never consume removal tickets between this final admission check and synchronous publication. */
    private suspend fun publishManifest(plan: DownloadPlan, priorityVersion: Long, worker: Job,
        manifest: dev.spofify.mobile.core.OfflineManifest): Boolean {
        val completed = CompletableDeferred<Boolean>()
        enqueue {
            try {
                val store = storeRef.get()
                if (store == null || !worker.isActive || !workerCurrent(plan, priorityVersion)) completed.complete(false)
                else {
                    val stage = store.beginManifest(plan.active.scope, plan.key.target, manifest.title,
                        manifest.generation, manifest.occurrences.size)
                    store.appendManifest(stage, 0, manifest.occurrences, terminal = true)
                    store.finishManifest(stage)
                    plan.manifestInstalled.set(true)
                    completed.complete(worker.isActive && workerCurrent(plan, priorityVersion))
                }
            } catch (error: Exception) { completed.completeExceptionally(error) }
        }
        return completed.await()
    }

    private fun stopJob(target: SavedTarget) {
        jobs.remove(target)?.let { stoppingJobs.add(it); it.cancel() }
    }

    private fun discardPlan(key: RetentionKey) {
        plans.remove(key)?.let { retentionIntents.remove(key, it.intent) }
        stopJob(key.target)
    }

    private fun forgetPlan(plan: DownloadPlan) {
        if (plans.remove(plan.key, plan)) retentionIntents.remove(plan.key, plan.intent)
        updateFor(plan.active) { if (plans[plan.key] == null) it.copy(working = it.working - plan.key.target) else it }
    }

    private suspend fun awaitStoppedJobs() {
        while (true) {
            val pending = stoppingJobs.toList()
            if (pending.isEmpty()) return
            pending.forEach { it.join(); stoppingJobs.remove(it) }
        }
    }

    /** IO actor. No successor worker until the prior coroutine AND response callback have joined. */
    private suspend fun reconcileDownloads() {
        if (stoppingJobs.any { !it.isCompleted }) {
            // Waiting for an old callback must not stall metadata/auth/Unsave actor commands.
            if (joiningStops.compareAndSet(false, true)) io.launch {
                try { awaitStoppedJobs() }
                finally { joiningStops.set(false); enqueue { reconcileDownloads() } }
            }
            return
        }
        awaitStoppedJobs()
        plans.values.filterNot(::planCurrent).forEach(::forgetPlan)
        if (jobs.values.any { !it.isCompleted }) return
        while (true) {
            val next = plans.values.minByOrNull { it.order } ?: return
            persistPlan(next)
            if (!planCurrent(next)) continue
            val priorityVersion = next.priorityVersion.get()
            if (!next.persisted.get() || playbackBusy(next.active.client)) return
            next.priorityPaused.set(false)
            startWorker(next, priorityVersion)
            return
        }
    }

    private fun startWorker(plan: DownloadPlan, priorityVersion: Long) {
        val active = plan.active
        val target = plan.key.target
        val job = io.launch(start = CoroutineStart.LAZY) {
            try {
                downloads.withLock {
                    if (!workerCurrent(plan, priorityVersion)) return@withLock
                    val store = storeRef.get() ?: return@withLock
                    updateFor(active) { it.copy(activeTarget = target) }
                    try {
                        scheduleRefresh()
                        val api = OfflineCollectionsApi(active.client)
                        if (!plan.manifestInstalled.get()) {
                            val manifest = api.manifest(target)
                            if (!workerCurrent(plan, priorityVersion)) return@withLock
                            val worker = kotlinx.coroutines.currentCoroutineContext()[Job]!!
                            beforeManifestPublish()
                            if (!publishManifest(plan, priorityVersion, worker, manifest)) return@withLock
                        }
                        if (!workerCurrent(plan, priorityVersion)) return@withLock
                        SavedDownloader(store, api.savedTransport(scope = active.scope, isCurrent = { workerCurrent(plan, priorityVersion) }))
                            .download(active.scope, target)
                    } finally {
                        updateFor(active) { it.copy(activeTarget = null) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (workerCurrent(plan, priorityVersion)) {
                    val code = when {
                        e is ApiException && e.status == 404 -> "unavailable"
                        e is ApiException && e.status == 429 -> "retry_later"
                        else -> "manifest_failed"
                    }
                    val failureCommitted = CompletableDeferred<Unit>()
                    enqueue {
                        try {
                            if (workerCurrent(plan, priorityVersion)) runCatching { storeRef.get()?.failManifest(active.scope, target, code) }
                        } finally { failureCommitted.complete(Unit) }
                    }
                    failureCommitted.await() // Keep this intent current until its ordered failure commit finishes.
                    updateFor(active) {
                        if (!workerCurrent(plan, priorityVersion)) it else it.copy(failures = it.failures + (target to when (code) {
                            "unavailable" -> "Collection is unavailable"
                            "retry_later" -> "Server busy. Try again later"
                            else -> "Download could not finish"
                        }))
                    }
                }
            } finally {
                val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]!!
                stoppingJobs.add(currentJob)
                withContext(NonCancellable) { beforeWorkerExit() }
                jobs.remove(target, currentJob)
                val interruptedByPriority = plan.priorityPaused.get() || plan.priorityVersion.get() != priorityVersion || playbackBusy(active.client)
                if (!(interruptedByPriority && planCurrent(plan))) forgetPlan(plan)
                enqueue { refresh(); reconcileDownloads() }
            }
        }
        jobs[target] = job
        if (workerCurrent(plan, priorityVersion)) job.start()
        else {
            stoppingJobs.add(job)
            jobs.remove(target, job)
            job.cancel()
            enqueue { reconcileDownloads() }
        }
    }

    fun cancel(target: SavedTarget, expectedBindingRevision: Long? = null) {
        val captured = captureLifecycle()
        val active = captured.binding ?: return
        if (!eligible(active.scope)) return
        synchronized(lifecycleLock) {
            if (!lifecycleCurrent(captured) || !presentationCurrent(expectedBindingRevision)) return@synchronized
            discardPlan(RetentionKey(active.scope, target))
            mutableState.update { it.copy(working = it.working - target,
                activeTarget = it.activeTarget?.takeUnless { selected -> selected == target }) }
            enqueue {
                if (binding.get() === active) { storeRef.get()?.cancel(active.scope, target); refresh() }
            }
        }
    }

    fun unsave(target: SavedTarget, expectedBindingRevision: Long? = null) {
        val captured = captureLifecycle()
        val active = captured.binding ?: return
        if (!eligible(active.scope)) return
        synchronized(lifecycleLock) {
            if (!lifecycleCurrent(captured) || !presentationCurrent(expectedBindingRevision)) return@synchronized
            val key = RetentionKey(active.scope, target)
            val intent = RetentionIntent(retentionEpoch(active.scope), saved = false)
            retentionIntents[key] = intent
            discardPlan(key)
            mutableState.update { it.copy(collections = it.collections.filterNot { row -> row.target == target },
                working = it.working - target, failures = it.failures - target,
                activeTarget = it.activeTarget?.takeUnless { selected -> selected == target }) }
            readerRevision.update { it + 1 }
            enqueue {
                if (!currentIntent(key, intent) || binding.get()?.scope != key.scope || !eligible(key.scope)) return@enqueue
                val store = store()
                store.bind(key.scope)
                applyPendingRemovals(key.scope, store)
                refresh()
            }
        }
    }
}
