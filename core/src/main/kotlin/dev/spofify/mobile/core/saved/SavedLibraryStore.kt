package dev.spofify.mobile.core.saved

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SavedGeneration(
    val id: String,
    val title: String,
    val items: List<SavedOccurrence>,
    val media: Map<String, SavedMediaDescriptor> = emptyMap(),
    val manifestComplete: Boolean = true,
)
@Serializable
internal data class SavedRecord(
    val target: SavedTarget,
    val installed: SavedGeneration,
    val replacement: SavedGeneration? = null,
    val transferred: Long = 0,
    val failure: String? = null,
)
internal class SavedNamespace(val scope: SavedScope, val key: String) {
    @Volatile var epoch = 0L
    @Volatile var accessEpoch = 0L
    @Volatile var retired = false
    val revoked = java.util.concurrent.atomic.AtomicBoolean()
    val certifiedMedia = mutableSetOf<String>()
    val records = linkedMapOf<SavedTarget, SavedRecord>()
    val readers = mutableMapOf<String, Int>()
    val writers = mutableMapOf<String, Int>()
}
internal data class SavedWork(
    val namespace: SavedNamespace,
    val target: SavedTarget,
    val generation: String,
    val epoch: Long,
    val cancellation: Long,
)
private data class ManifestStaging(
    val namespace: SavedNamespace,
    val epoch: Long,
    val target: SavedTarget,
    val title: String,
    val generation: String,
    val total: Int,
    val items: MutableList<SavedOccurrence> = mutableListOf(),
    var terminal: Boolean = false,
)

/**
 * One process owner per private root. Metadata is per collection, atomically replaced and fsynced.
 * Supply Android noBackupFilesDir child; all names are opaque hashes/random capabilities.
 * The default backend owns ONLY saved media, never SongCache. No count quota or eviction.
 */
class SavedLibraryStore(
    private val root: File,
    private val eligibility: SavedLocalEligibility,
    internal val backend: SavedRepresentationBackend = PrivateSavedMediaBackend(File(root, "representations")),
    private val maxManifestBytes: Int = 16 * 1024 * 1024,
    private val networkPermission: (SavedScope) -> Boolean = { true },
    /** Physical free bytes at the owned backend/metadata filesystem, not a logical saved quota. */
    private val availableBytes: () -> Long = { root.usableSpace },
) : SavedLibraryReader, Closeable {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = false }
    private val metadata = File(root, "metadata")
    private val changes = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = changes
    private val namespaces = mutableMapOf<String, SavedNamespace>()
    private val scopeNamespaces = mutableMapOf<String, SavedNamespace>()
    private val accessEpoch = java.util.concurrent.atomic.AtomicLong()
    private val immediateTransfer = java.util.concurrent.atomic.AtomicReference<SavedTransferCancellation?>()
    private val activeNamespaceKey = java.util.concurrent.atomic.AtomicReference<String?>()
    private val activeNamespace = java.util.concurrent.atomic.AtomicReference<SavedNamespace?>()
    private val transferIdentity = java.util.concurrent.atomic.AtomicReference<Pair<SavedScope, SavedTarget>?>()
    private val stages = mutableMapOf<String, ManifestStaging>()
    private val cancellations = mutableMapOf<Pair<String, SavedTarget>, Long>()
    private val activeResponses = mutableMapOf<SavedWork, Closeable>()
    private val downloading = mutableSetOf<Pair<String, SavedTarget>>()
    private var active: SavedNamespace? = null
    private val networkAllowed = java.util.concurrent.atomic.AtomicBoolean(true)
    private var closed = false
    private var transferBusy = false
    private var reservedBytes = 0L
    private class SpaceClaim(var remaining: Long)
    private val transferClaims = mutableMapOf<SavedWork, SpaceClaim>()
    private val ownerFile: RandomAccessFile
    private val ownerLock: java.nio.channels.FileLock
    /** Synthetic failure seam: critical retirement never uses discretionary space admission. */
    internal var retirementMarkerWriter: (File) -> Unit = { atomicWrite(it, byteArrayOf(1)) }

    init {
        require(maxManifestBytes > 0)
        safeDirectory(root)
        safeDirectory(metadata)
        val ownership = File(root, "owner")
        rejectLink(ownership)
        ownerFile = RandomAccessFile(ownership, "rw")
        try {
            ownerLock = ownerFile.channel.tryLock() ?: throw IOException("Saved storage is already owned")
        } catch (e: Exception) { ownerFile.close(); throw e }
        try { recoverRetiredNamespaces() } catch (e: Exception) {
            ownerLock.release(); ownerFile.close(); throw e
        }
    }

    /** Call only after the caller has validated this account/session; ordinary reattach is idempotent. */
    fun bind(scope: SavedScope) {
        val admission = accessEpoch.get()
        check(eligibility.eligible(scope)) { "Saved scope is not locally eligible" }
        synchronized(lock) {
            check(!closed && accessEpoch.get() == admission)
            if (active?.let { it.scope == scope && !it.retired && !it.revoked.get() && it.accessEpoch == accessEpoch.get() } == true) return
            active?.let { it.epoch++; cancelResponses(it); dropStages(it) }
            val scopeKey = scopeKey(scope)
            val pointer = File(metadata, scopeKey + ".current")
            rejectLink(pointer)
            var ns = scopeNamespaces[scopeKey]
            if (ns == null && pointer.isFile) {
                require(pointer.length() == 64L)
                val key = pointer.readText()
                require(key.matches(Regex("[0-9a-f]{64}")))
                ns = namespaces[key] ?: load(scope, key).also { namespaces[key] = it }
            }
            if (ns == null || ns.retired || ns.revoked.get() || ns.accessEpoch != accessEpoch.get() && active === ns) {
                ns?.let(::retireNamespace)
                val key = savedHash(scopeKey, UUID.randomUUID().toString())
                ns = load(scope, key)
                namespaces[key] = ns
                writeMetadata(File(namespaceDirectory(ns), "namespace"), OWNED_NAMESPACE_MARKER.toByteArray())
                writeMetadata(pointer, key.toByteArray())
            }
            scopeNamespaces[scopeKey] = ns
            ns.accessEpoch = admission
            ns.epoch++
            check(accessEpoch.get() == admission) { "Saved bind invalidated" }
            active = ns
            activeNamespace.set(ns)
            activeNamespaceKey.set(ns.key)
            cleanup(ns)
            changed()
        }
    }

    /** Nonblocking, no disk/store/auth locks. Safe in the shared auth callback; retire later on IO. */
    fun fenceAccess(): SavedScopeFence {
        val captured = activeNamespace.get()
        captured?.revoked?.set(true)
        val namespace = captured?.key
        accessEpoch.incrementAndGet()
        changes.update { it + 1 }
        return SavedScopeFence(namespace)
    }

    /** Deferred IO half of fenceAccess. Safe even after a newer authenticated bind. */
    fun retire(fence: SavedScopeFence) = synchronized(lock) {
        val ns = fence.namespace?.let { namespaces[it] } ?: return@synchronized
        retireNamespace(ns)
    }

    fun suspend() = setNetworkAllowed(false)

    /** Retain a visible intent before manifest network work starts; an empty staged save is not complete. */
    fun requestSave(scope: SavedScope, target: SavedTarget, title: String) = synchronized(lock) {
        val ns = requireScope(scope)
        if (ns.records[target] == null) {
            val record = SavedRecord(target, SavedGeneration(UUID.randomUUID().toString(), title, emptyList(), manifestComplete = false))
            persist(ns, record)
            ns.records[target] = record
            changed()
        }
    }

    fun failManifest(scope: SavedScope, target: SavedTarget, code: String = "manifest_failed") = synchronized(lock) {
        require(code in setOf("manifest_failed", "unavailable", "storage", "retry_later", "cancelled"))
        val ns = requireScope(scope)
        ns.records[target]?.let { old ->
            val record = old.copy(failure = code)
            persist(ns, record)
            ns.records[target] = record
            changed()
        }
    }

    /** Offline suspends network work, not eligible local readers; restoring online never auto-starts. */
    fun setNetworkAllowed(allowed: Boolean) {
        networkAllowed.set(allowed)
        if (!allowed) immediateTransfer.get()?.close()
        synchronized(lock) {
        check(!closed)
        if (!allowed) {
            namespaces.values.forEach { ns ->
            ns.records.keys.forEach { cancelTarget(ns, it) }
            cancelResponses(ns)
            }
        }
        changed()
        }
    }

    /** Immediate access/writer fence. Failed physical cleanup remains inaccessible and counted. */
    fun retire(scope: SavedScope) = synchronized(lock) {
        check(!closed)
        val scopeKey = scopeKey(scope)
        val ns = scopeNamespaces[scopeKey] ?: active?.takeIf { it.scope == scope } ?: run {
            // Logout/401 before UI binding still retires persisted bytes from an earlier process.
            val pointer = File(metadata, scopeKey + ".current")
            rejectLink(pointer)
            if (!pointer.isFile) return@synchronized
            require(pointer.length() == 64L)
            val key = pointer.readText()
            require(key.matches(Regex("[0-9a-f]{64}")))
            (namespaces[key] ?: load(scope, key).also { namespaces[key] = it }).also { scopeNamespaces[scopeKey] = it }
        }
        if (active === ns) fenceAccess()
        retireNamespace(ns)
    }

    private fun retireNamespace(ns: SavedNamespace) {
        ns.retired = true
        ns.epoch++
        if (active === ns) { active = null; activeNamespace.set(null); activeNamespaceKey.set(null) }
        cancelResponses(ns)
        changed()
        // Remove only this incarnation's existing pointer, before any allocating marker write.
        // If a new login already installed a successor pointer, leave it untouched.
        try {
            try { invalidateCurrentPointer(ns) }
            finally {
                retirementMarkerWriter(File(namespaceDirectory(ns), "retired"))
                syncSavedDirectory(namespaceDirectory(ns))
            }
        } finally {
            dropStages(ns)
            cleanup(ns)
        }
    }

    private fun invalidateCurrentPointer(ns: SavedNamespace) {
        val pointer = File(metadata, scopeKey(ns.scope) + ".current")
        rejectLink(pointer)
        if (pointer.isFile && pointer.length() == 64L && pointer.readText() == ns.key) {
            // Reuse the existing pointer inode as ownership evidence; no new file-data allocation
            // is needed, and recovery can identify this orphan even if the tombstone write fails.
            val directory = namespaceDirectory(ns)
            val ownership = File(directory, "namespace")
            rejectLink(ownership)
            Files.move(pointer.toPath(), ownership.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncSavedDirectory(metadata)
            syncSavedDirectory(directory)
        }
    }

    fun beginManifest(scope: SavedScope, target: SavedTarget, title: String, generation: String, total: Int): SavedManifestStage = synchronized(lock) {
        val ns = requireScope(scope)
        require(total >= 0 && generation.isNotBlank())
        // A new Update invalidates the old staging capability but never the installed generation.
        stages.filterValues { it.namespace === ns && it.target == target }.keys.toList().forEach(::discardStage)
        cancelTarget(ns, target)
        val token = UUID.randomUUID().toString()
        val staging = ManifestStaging(ns, ns.epoch, target, title, generation, total)
        stages[token] = staging
        SavedManifestStage(token)
    }

    /** Append only a coherent full-page sequence. Cursors/tokens remain in the API adapter, not disk. */
    fun appendManifest(stage: SavedManifestStage, offset: Int, items: List<SavedOccurrence>, terminal: Boolean) = synchronized(lock) {
        val s = requireStage(stage)
        require(!s.terminal && offset == s.items.size && items.size <= s.total - offset)
        require(items.isNotEmpty() || terminal) { "Nonprogressing saved manifest" }
        val identities = s.items.mapTo(hashSetOf()) { it.occurrenceId }
        require(items.all { identities.add(it.occurrenceId) }) { "Duplicate occurrence identity" }
        val combined = s.items + items
        require(!terminal || combined.size == s.total) { "Incomplete saved manifest" }
        val data = json.encodeToString(SavedGeneration(s.generation, s.title, combined)).toByteArray()
        require(data.size <= maxManifestBytes) { "Saved manifest byte limit exceeded" }
        writeMetadata(stageFile(s.namespace, stage.token), data)
        s.items.addAll(items)
        s.terminal = terminal
    }

    fun finishManifest(stage: SavedManifestStage) = synchronized(lock) {
        val s = requireStage(stage)
        check(s.terminal && s.items.size == s.total) { "Saved manifest has not reached its terminal page" }
        val generation = SavedGeneration(s.generation, s.title, s.items.toList())
        val old = s.namespace.records[s.target]
        val record = if (old == null || !old.installed.manifestComplete) SavedRecord(s.target, generation)
            else old.copy(replacement = generation, failure = null, transferred = 0)
        persist(s.namespace, record)
        s.namespace.records[s.target] = record
        discardStage(stage.token)
        publishIfReady(s.namespace, s.target)
        cleanup(s.namespace)
        changed()
    }

    fun cancel(scope: SavedScope, target: SavedTarget) {
        if (transferIdentity.get() == (scope to target)) immediateTransfer.get()?.close()
        synchronized(lock) {
        val ns = requireScope(scope)
        cancelTarget(ns, target)
        stages.filterValues { it.namespace === ns && it.target == target }.keys.toList().forEach(::discardStage)
        ns.records[target]?.let { old ->
            val next = old.copy(replacement = null, failure = "cancelled")
            persist(ns, next)
            ns.records[target] = next
        }
        cleanup(ns)
        changed()
        }
    }

    fun unsave(scope: SavedScope, target: SavedTarget) {
        if (transferIdentity.get() == (scope to target)) immediateTransfer.get()?.close()
        synchronized(lock) {
        val ns = requireScope(scope)
        cancelTarget(ns, target)
        stages.filterValues { it.namespace === ns && it.target == target }.keys.toList().forEach(::discardStage)
        // Durable tombstone prevents failed deletion from resurrecting membership on restart.
        writeMetadata(File(namespaceDirectory(ns), targetKey(target) + ".removed"), byteArrayOf(1))
        ns.records.remove(target)
        changed()
        cleanup(ns)
        }
    }

    override fun snapshots(scope: SavedScope): List<SavedCollectionSnapshot> = readSnapshots(scope, null)
    override fun snapshot(scope: SavedScope, target: SavedTarget): SavedCollectionSnapshot? = readSnapshots(scope, target).firstOrNull()

    private fun readSnapshots(scope: SavedScope, target: SavedTarget?): List<SavedCollectionSnapshot> {
        if (!eligibility.eligible(scope)) return emptyList()
        val ns: SavedNamespace
        val epoch: Long
        val records: List<SavedRecord>
        val running: Set<SavedTarget>
        val descriptors: List<SavedMediaDescriptor>
        synchronized(lock) {
            ns = readableScope(scope) ?: return emptyList()
            epoch = ns.epoch
            records = ns.records.values.filter { target == null || it.target == target }
            running = downloading.filter { it.first == ns.key }.map { it.second }.toSet()
            descriptors = records.flatMap { it.installed.media.values }.distinctBy(::mediaKey)
            descriptors.forEach { pinReader(ns, mediaKey(it)) }
        }
        try {
            // Hashing may be slow: no auth/store monitor is held, and duplicates are verified once.
            val readiness = descriptors.associate { mediaKey(it) to verified(ns, it) }
            val result = records.map { snapshotOf(it, readiness, it.target in running) }
            return synchronized(lock) {
                if (readableScope(scope) === ns && ns.epoch == epoch) result else emptyList()
            }
        } finally {
            synchronized(lock) {
                descriptors.forEach { decrement(ns.readers, mediaKey(it)) }
                cleanup(ns)
            }
        }
    }

    override fun open(scope: SavedScope, target: SavedTarget, occurrenceId: String): SavedMediaLease? {
        if (!eligibility.eligible(scope)) return null
        val ns: SavedNamespace
        val descriptor: SavedMediaDescriptor
        val epoch: Long
        synchronized(lock) {
            ns = readableScope(scope) ?: return null
            val generation = ns.records[target]?.installed ?: return null
            val occurrence = generation.items.find { it.occurrenceId == occurrenceId && it.hasAudio } ?: return null
            descriptor = generation.media[occurrence.trackId] ?: return null
            epoch = ns.epoch
            pinReader(ns, mediaKey(descriptor))
        }
        val key = mediaKey(descriptor)
        var handedOff = false
        try {
            if (!verified(ns, descriptor)) { changed(); return null }
            val raw = backend.open(ns.key, key)
            val released = java.util.concurrent.atomic.AtomicBoolean()
            fun release() {
                if (released.compareAndSet(false, true)) {
                    try { raw.close() } finally {
                        synchronized(lock) { decrement(ns.readers, key); cleanup(ns); changed() }
                    }
                }
            }
            if (ns.epoch != epoch || ns.accessEpoch != accessEpoch.get() || activeNamespaceKey.get() != ns.key) {
                raw.close()
                return null
            }
            handedOff = true
            return object : SavedMediaLease {
                override val descriptor = descriptor
                override val input: InputStream = object : FilterInputStream(raw) {
                    private fun guard() {
                        // Eligibility is deliberately invoked outside every store/auth monitor.
                        if (released.get() || ns.epoch != epoch || ns.accessEpoch != accessEpoch.get() ||
                            activeNamespaceKey.get() != ns.key || !eligibility.eligible(scope)) throw IOException("Saved reader fenced")
                    }
                    override fun read(): Int { guard(); return super.read() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int { guard(); return super.read(b, off, len) }
                    override fun skip(n: Long): Long { guard(); return super.skip(n) }
                    override fun close() = release()
                }
                override fun close() = release()
            }
        } finally {
            if (!handedOff) synchronized(lock) { decrement(ns.readers, key); cleanup(ns) }
        }
    }

    /** Includes real metadata, staging, partials, media and failed-cleanup bytes, not logical refs. */
    fun ownedBytes(): Long = synchronized(lock) { Math.addExact(treeBytes(metadata), backend.ownedBytes()) + File(root, "owner").length() }

    private fun snapshotOf(record: SavedRecord, readiness: Map<String, Boolean>, running: Boolean): SavedCollectionSnapshot {
        val g = record.installed
        val rows = g.items.map { item ->
            val descriptor = g.media[item.trackId]
            SavedOccurrenceSnapshot(item, descriptor, item.hasAudio && descriptor != null && readiness[mediaKey(descriptor)] == true)
        }
        val complete = g.manifestComplete && rows.all { it.available }
        return SavedCollectionSnapshot(record.target, g.title, g.id, rows,
            when {
                complete -> SavedDownloadState.COMPLETE
                running -> SavedDownloadState.DOWNLOADING
                record.failure != null -> SavedDownloadState.FAILED
                else -> SavedDownloadState.PARTIAL
            }, record.replacement != null, record.transferred, record.failure)
    }

    internal fun beginWork(scope: SavedScope, target: SavedTarget): SavedWork? {
        if (!eligibility.eligible(scope) || !networkPermission(scope)) return null
        return synchronized(lock) {
        val ns = requireScope(scope)
        if (!networkAllowed.get() || transferBusy) return null
        val record = ns.records[target] ?: return null
        val g = record.replacement ?: record.installed
        transferBusy = true
        transferIdentity.set(scope to target)
        downloading.add(ns.key to target)
        val work = SavedWork(ns, target, g.id, ns.epoch, cancellation(ns, target))
        changed()
        work
        }
    }
    internal fun registerCancellation(work: SavedWork, cancellation: SavedTransferCancellation) = synchronized(lock) {
        checkWork(work)
        immediateTransfer.set(cancellation)
        activeResponses[work] = cancellation
    }
    internal fun checkWork(work: SavedWork) = synchronized(lock) {
        val ns = work.namespace
        if (closed || active !== ns || ns.retired || ns.epoch != work.epoch ||
            cancellation(ns, work.target) != work.cancellation || !networkAllowed.get() ||
            ns.accessEpoch != accessEpoch.get() ||
            generation(work)?.id != work.generation) throw SavedWorkCancelled()
    }
    internal fun workGeneration(work: SavedWork): SavedGeneration = synchronized(lock) {
        checkWork(work)
        generation(work) ?: throw SavedWorkCancelled()
    }
    internal fun bindDescriptor(work: SavedWork, descriptor: SavedMediaDescriptor, guard: () -> Unit = {}) = synchronized(lock) {
        guard()
        checkWork(work)
        val g = generation(work)!!
        require(g.items.any { it.trackId == descriptor.trackId && it.hasAudio })
        val updated = g.copy(media = g.media + (descriptor.trackId to descriptor))
        updateGeneration(work, updated)
    }
    internal fun isVerified(work: SavedWork, descriptor: SavedMediaDescriptor, guard: () -> Unit = {}): Boolean {
        synchronized(lock) { guard(); checkWork(work); pinReader(work.namespace, mediaKey(descriptor)) }
        try {
            val valid = verified(work.namespace, descriptor, guard)
            synchronized(lock) {
                guard()
                checkWork(work)
                if (valid) work.namespace.certifiedMedia.add(mediaKey(descriptor))
                else work.namespace.certifiedMedia.remove(mediaKey(descriptor))
            }
            return valid
        } finally { synchronized(lock) { decrement(work.namespace.readers, mediaKey(descriptor)); cleanup(work.namespace) } }
    }
    internal fun pinWriter(work: SavedWork, descriptor: SavedMediaDescriptor) = synchronized(lock) {
        checkWork(work)
        val key = mediaKey(descriptor)
        work.namespace.writers[key] = (work.namespace.writers[key] ?: 0) + 1
    }
    internal fun unpinWriter(work: SavedWork, descriptor: SavedMediaDescriptor) = synchronized(lock) {
        decrement(work.namespace.writers, mediaKey(descriptor)); cleanup(work.namespace)
    }
    internal fun response(work: SavedWork, response: Closeable?) = synchronized(lock) {
        if (response == null) activeResponses.remove(work) else {
            checkWork(work); activeResponses[work] = response
        }
    }
    internal fun reserveTransfer(work: SavedWork, descriptor: SavedMediaDescriptor): Closeable = synchronized(lock) {
        checkWork(work)
        val existing = try { (backend.length(work.namespace.key, mediaKey(descriptor), true) ?: 0).coerceIn(0, descriptor.byteLength) }
            catch (e: IOException) { throw SavedStorageFailure(e) }
        val claim = claimBytes(transferReservationBytes(descriptor.byteLength - existing))
        transferClaims[work] = claim
        Closeable { synchronized(lock) { transferClaims.remove(work); releaseClaim(claim) } }
    }
    internal fun restartTransferReservation(work: SavedWork, byteLength: Long) = synchronized(lock) {
        checkWork(work)
        val claim = transferClaims[work] ?: throw SavedWorkCancelled()
        val required = transferReservationBytes(byteLength)
        val extra = required - claim.remaining
        if (extra > 0) {
            admit(extra)
            reservedBytes = Math.addExact(reservedBytes, extra)
            claim.remaining += extra
        }
    }
    internal fun transferred(work: SavedWork, count: Int) = synchronized(lock) {
        checkWork(work)
        transferClaims[work]?.let { claim ->
            val consumed = minOf(count.toLong(), (claim.remaining - TRANSFER_METADATA_RESERVE).coerceAtLeast(0))
            claim.remaining -= consumed
            reservedBytes -= consumed
        }
        val ns = work.namespace
        val record = ns.records[work.target] ?: throw SavedWorkCancelled()
        ns.records[work.target] = record.copy(transferred = Math.addExact(record.transferred, count.toLong()))
        changed()
    }
    internal fun promote(work: SavedWork, descriptor: SavedMediaDescriptor, guard: () -> Unit = {}) = synchronized(lock) {
        guard()
        checkWork(work)
        try { backend.promote(work.namespace.key, mediaKey(descriptor)) }
        catch (e: IOException) { throw SavedStorageFailure(e) }
        guard()
        checkWork(work)
        work.namespace.certifiedMedia.add(mediaKey(descriptor))
        changed()
    }
    internal fun endWork(work: SavedWork, failure: String?, canPublish: () -> Boolean = { true }) = synchronized(lock) {
        activeResponses.remove(work)
        immediateTransfer.set(null)
        transferIdentity.set(null)
        downloading.remove(work.namespace.key to work.target)
        transferBusy = false
        try {
            checkWork(work)
            val ns = work.namespace
            val record = ns.records[work.target]!!
            val terminalFailure = if (canPublish()) failure else "cancelled"
            val next = record.copy(failure = terminalFailure)
            persist(ns, next)
            ns.records[work.target] = next
            if (terminalFailure == null) publishIfReady(ns, work.target, canPublish)
        } catch (_: SavedWorkCancelled) { /* fenced completion cannot mutate a successor */ }
        finally { cleanup(work.namespace); changed() }
    }
    private fun generation(work: SavedWork): SavedGeneration? = work.namespace.records[work.target]?.let { it.replacement ?: it.installed }
    private fun updateGeneration(work: SavedWork, generation: SavedGeneration) {
        val record = work.namespace.records[work.target] ?: throw SavedWorkCancelled()
        val next = if (record.replacement != null) record.copy(replacement = generation) else record.copy(installed = generation)
        persist(work.namespace, next)
        work.namespace.records[work.target] = next
        changed()
    }
    private fun publishIfReady(ns: SavedNamespace, target: SavedTarget, canPublish: () -> Boolean = { true }) {
        val record = ns.records[target] ?: return
        val candidate = record.replacement ?: return
        if (candidate.manifestComplete && candidate.items.all { it.hasAudio && candidate.media[it.trackId]?.let { d -> mediaKey(d) in ns.certifiedMedia && backend.length(ns.key, mediaKey(d), false) == d.byteLength } == true }) {
            if (!canPublish()) return
            val next = record.copy(installed = candidate, replacement = null, failure = null)
            persist(ns, next)
            ns.records[target] = next
        }
    }
    private fun pinReader(ns: SavedNamespace, key: String) { ns.readers[key] = (ns.readers[key] ?: 0) + 1 }
    private fun verified(ns: SavedNamespace, descriptor: SavedMediaDescriptor, guard: () -> Unit = {}): Boolean {
        val key = mediaKey(descriptor)
        if (backend.length(ns.key, key, false) != descriptor.byteLength) return false
        val epoch = ns.epoch
        return try {
            backend.open(ns.key, key).use { input -> hashMatches(input, descriptor) {
                guard()
                if (ns.epoch != epoch || ns.accessEpoch != accessEpoch.get() || ns.retired) throw IOException("Saved verification fenced")
            } }
        } catch (_: IOException) { false }
    }
    private fun readableScope(scope: SavedScope): SavedNamespace? = active?.takeIf {
        !closed && !it.retired && !it.revoked.get() && it.scope == scope && it.accessEpoch == accessEpoch.get()
    }
    private fun requireScope(scope: SavedScope): SavedNamespace = readableScope(scope) ?: throw IllegalStateException("Saved scope is not locally eligible")
    private fun requireStage(stage: SavedManifestStage): ManifestStaging {
        val s = stages[stage.token] ?: throw IllegalStateException("Saved manifest stage retired")
        check(active === s.namespace && s.epoch == s.namespace.epoch && readableScope(s.namespace.scope) === s.namespace)
        return s
    }
    private fun cancellation(ns: SavedNamespace, target: SavedTarget): Long = cancellations[ns.key to target] ?: 0
    private fun cancelTarget(ns: SavedNamespace, target: SavedTarget) {
        cancellations[ns.key to target] = cancellation(ns, target) + 1
        activeResponses.filterKeys { it.namespace === ns && it.target == target }.values.toList().forEach { runCatching { it.close() } }
    }
    private fun cancelResponses(ns: SavedNamespace) { activeResponses.filterKeys { it.namespace === ns }.values.toList().forEach { runCatching { it.close() } } }
    private fun dropStages(ns: SavedNamespace) { stages.filterValues { it.namespace === ns }.keys.toList().forEach(::discardStage) }
    private fun discardStage(token: String) {
        val s = stages.remove(token) ?: return
        // A failed deletion is counted; an orphan stage is never installed by recovery.
        runCatching { Files.deleteIfExists(stageFile(s.namespace, token).toPath()) }
    }
    private fun changed() { changes.update { it + 1 } }
    private fun scopeKey(scope: SavedScope) = savedHash(scope.origin, scope.accountId)
    private fun targetKey(target: SavedTarget) = savedHash(target.kind.name, target.id)
    private fun admit(bytes: Long) {
        if (bytes < 0 || availableBytes().coerceAtLeast(0) - reservedBytes < bytes) throw SavedStorageFailure()
    }
    private fun claimBytes(bytes: Long): SpaceClaim {
        admit(bytes)
        reservedBytes = Math.addExact(reservedBytes, bytes)
        return SpaceClaim(bytes)
    }
    private fun releaseClaim(claim: SpaceClaim) { reservedBytes -= claim.remaining; claim.remaining = 0 }
    private fun writeMetadata(file: File, bytes: ByteArray) {
        val claim = claimBytes(Math.addExact(bytes.size.toLong(), 4096L))
        try { atomicWrite(file, bytes) } catch (e: IOException) { throw SavedStorageFailure(e) }
        finally { releaseClaim(claim) }
    }
    private fun recoverRetiredNamespaces() {
        val entries = metadata.listFiles() ?: throw IOException("Cannot list saved metadata")
        // Exclusive root ownership is held. Capture every validated current pointer before deciding
        // whether an explicitly format-owned namespace is an orphan. Invalid pointers fail closed.
        val referenced = entries.filter { it.name.matches(Regex("[0-9a-f]{64}\\.current")) }.map { pointer ->
            rejectLink(pointer)
            require(pointer.isFile && pointer.length() == 64L)
            pointer.readText().also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        }.toSet()
        entries.forEach { directory ->
            rejectLink(directory)
            if (directory.isDirectory && directory.name.matches(Regex("[0-9a-f]{64}"))) {
                val marker = File(directory, "retired")
                rejectLink(marker)
                val ownership = File(directory, "namespace")
                rejectLink(ownership)
                val owned = ownership.isFile && when (ownership.length()) {
                    OWNED_NAMESPACE_MARKER.length.toLong() -> ownership.readText() == OWNED_NAMESPACE_MARKER
                    64L -> ownership.readText() == directory.name // atomically invalidated old pointer
                    else -> false
                }
                if (marker.isFile || owned && directory.name !in referenced) {
                    runCatching { backend.keys(directory.name).forEach { backend.remove(directory.name, it) } }
                    runCatching {
                        (directory.listFiles() ?: throw IOException("Cannot list retired metadata")).forEach { file ->
                            rejectLink(file)
                            if (file.name != "retired" && file.name != "namespace") Files.deleteIfExists(file.toPath())
                        }
                    }
                }
            }
        }
    }
    private fun namespaceDirectory(ns: SavedNamespace): File = File(metadata, ns.key).also(::safeDirectory)
    private fun stageFile(ns: SavedNamespace, token: String): File = File(namespaceDirectory(ns), "stage-$token")
    private fun persist(ns: SavedNamespace, record: SavedRecord) {
        val directory = namespaceDirectory(ns)
        val data = json.encodeToString(record).toByteArray()
        // Bound each collection, not collection count or total retained media.
        require(data.size <= maxManifestBytes.toLong() * 2 + 1024 * 1024) { "Saved metadata byte limit exceeded" }
        writeMetadata(File(directory, targetKey(record.target) + ".json"), data)
        Files.deleteIfExists(File(directory, targetKey(record.target) + ".removed").toPath())
    }
    private fun load(scope: SavedScope, key: String): SavedNamespace {
        val ns = SavedNamespace(scope, key)
        val directory = namespaceDirectory(ns)
        ns.retired = File(directory, "retired").exists()
        if (ns.retired) return ns
        (directory.listFiles() ?: throw IOException("Cannot list saved metadata")).forEach { file ->
            rejectLink(file)
            when {
                file.name.endsWith(".json") && !File(directory, file.name.removeSuffix(".json") + ".removed").exists() -> {
                    require(file.length() <= maxManifestBytes.toLong() * 2 + 1024 * 1024) { "Oversized saved metadata" }
                    val record = json.decodeFromString<SavedRecord>(file.readText())
                    require(targetKey(record.target) + ".json" == file.name)
                    validate(record.installed)
                    record.replacement?.let(::validate)
                    ns.records[record.target] = record
                }
                file.name.startsWith("stage-") || file.name.endsWith(".tmp") -> runCatching { Files.deleteIfExists(file.toPath()) }
            }
        }
        return ns
    }
    private fun validate(generation: SavedGeneration) {
        require(generation.id.isNotBlank() && generation.items.map { it.occurrenceId }.toSet().size == generation.items.size)
        require(generation.media.all { (id, descriptor) -> id == descriptor.trackId && generation.items.any { it.trackId == id } })
    }
    private fun cleanup(ns: SavedNamespace) {
        val references = if (ns.retired) emptySet() else ns.records.values.flatMap { record ->
            listOfNotNull(record.installed, record.replacement).flatMap { it.media.values.map(::mediaKey) }
        }.toSet()
        val keep = references + ns.readers.keys + ns.writers.keys
        runCatching { backend.keys(ns.key).filterNot { it in keep }.forEach { backend.remove(ns.key, it) } }
        val directory = namespaceDirectory(ns)
        runCatching {
            (directory.listFiles() ?: throw IOException("Cannot list saved metadata")).forEach { file ->
                rejectLink(file)
                val removed = file.name.endsWith(".json") && File(directory, file.name.removeSuffix(".json") + ".removed").exists()
                if ((ns.retired && file.name != "retired" && file.name != "namespace") || removed) Files.deleteIfExists(file.toPath())
            }
        }
    }

    /** Simulate process loss in synthetic tests without orderly cleanup concealing restart bugs. */
    internal fun abandonWithoutCleanupForTest() = synchronized(lock) {
        check(!transferBusy && namespaces.values.all { it.readers.isEmpty() && it.writers.isEmpty() })
        if (!closed) {
            active = null
            activeNamespace.set(null)
            activeNamespaceKey.set(null)
            closed = true
            ownerLock.release()
            ownerFile.close()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            check(!transferBusy && namespaces.values.all { it.readers.isEmpty() && it.writers.isEmpty() }) { "Close saved operations and reader leases before store" }
            namespaces.values.forEach { it.epoch++; cancelResponses(it); dropStages(it); cleanup(it) }
            active = null
            activeNamespace.set(null)
            activeNamespaceKey.set(null)
            closed = true
            ownerLock.release()
            ownerFile.close()
            changed()
        }
    }
}

private const val OWNED_NAMESPACE_MARKER = "saved-library-v1"
private const val TRANSFER_METADATA_RESERVE = 64L * 1024
internal class SavedStorageFailure(cause: IOException? = null) : IOException("Insufficient or unavailable saved storage", cause)
private fun transferReservationBytes(bytes: Long): Long = try {
    Math.addExact(bytes, TRANSFER_METADATA_RESERVE)
} catch (_: ArithmeticException) { throw SavedStorageFailure() }
internal class SavedWorkCancelled : IOException("Saved operation cancelled")
internal fun decrement(map: MutableMap<String, Int>, key: String) {
    val count = (map[key] ?: 1) - 1
    if (count <= 0) map.remove(key) else map[key] = count
}
internal fun hashMatches(input: InputStream, descriptor: SavedMediaDescriptor, guard: () -> Unit = {}): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        guard()
        val count = input.read(buffer)
        guard()
        if (count < 0) break
        if (count == 0) throw IOException("Nonprogressing saved media stream")
        total = Math.addExact(total, count.toLong())
        if (total > descriptor.byteLength) return false
        digest.update(buffer, 0, count)
    }
    return total == descriptor.byteLength && digest.digest().joinToString("") { "%02x".format(it) } == descriptor.sha256
}
private fun syncSavedDirectory(directory: File) {
    java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
}
private fun atomicWrite(file: File, data: ByteArray) {
    rejectLink(file)
    val temporary = File(file.parentFile, UUID.randomUUID().toString() + ".tmp")
    try {
        FileOutputStream(temporary).use { out -> out.write(data); out.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary.toPath()) }
}
