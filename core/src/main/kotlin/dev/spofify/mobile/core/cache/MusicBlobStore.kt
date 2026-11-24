package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.offline.OfflineAttemptTrace
import dev.spofify.mobile.core.offline.OfflineDiagnosticStage.*
import dev.spofify.mobile.core.offline.OfflineManifest
import dev.spofify.mobile.core.offline.OfflineMediaDescriptor
import dev.spofify.mobile.core.offline.PlaylistFiles
import dev.spofify.mobile.core.offline.trackKeys
import dev.spofify.mobile.core.offline.syncPlaylistDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MusicOwner(val origin: String, val accountId: String)

@Serializable
data class SongKey(val owner: MusicOwner, val id: String)

data class StoredSong(val key: SongKey, val title: String, val bytes: Long, val auto: Boolean, val offline: Boolean, val direct: Boolean = false)

/** Verified audio bytes (not manifests, metadata, or incomplete transfers). Shared blobs belong
 * to offline storage first; cache bytes also include audio still in use or awaiting a retry to unlink.
 */
data class MusicStorageUsage(
    val offlinePlaylistCount: Int = 0,
    val offlineBytes: Long = 0,
    val pendingDownloadBytes: Long = 0,
    val cachedSongCount: Int = 0,
    val cacheBytes: Long = 0,
    val inUseBytes: Long = 0,
) {
    val totalBytes: Long get() = offlineBytes + cacheBytes
}

/** Single process owner. Construct on IO and acquire playback pins on a loader thread.
 * .meta commits verified bytes, AUTO, independent refs and resumable download holds.
 * Only .playlist files publish playlist membership; download holds never authorize playback.
 */
class MusicBlobStore internal constructor(private val dir: File, private val freeBytes: () -> Long,
    private val unlink: (Path) -> Boolean, syncDirectory: (File) -> Unit) {
    internal constructor(dir: File, freeBytes: () -> Long, unlink: (Path) -> Boolean) :
        this(dir, freeBytes, unlink, ::syncPlaylistDirectory)
    constructor(dir: File, freeBytes: () -> Long = { dir.usableSpace }) : this(dir, freeBytes, Files::deleteIfExists)

    @Serializable
    private data class Row(val key: SongKey, val title: String, val size: Long, val sha: String,
        val auto: Boolean = true, val refs: Set<String> = emptySet(), val drafts: Set<String> = emptySet())
    private class Entry(val file: File, var row: Row, var last: Long, var readers: Int = 0) {
        // Pins preserve bytes, not download authority. Loader threads recheck this on each read.
        @Volatile var downloaded = false
    }
    private val lock = ReentrantLock()
    private val entries = mutableMapOf<SongKey, Entry>()
    // A metadata-only remnant never occupies a track's live entry.
    private val staleMetadata = mutableMapOf<Path, SongKey>()
    private val playlistWriters = KeyedMutex<Pair<MusicOwner, String>>()
    private val manifestReaders = KeyedMutex<MusicOwner>()
    // Two disjoint downloads can make progress without opening a stream per wanted playlist.
    private val playlistAttempts = Semaphore(2)
    private val diskWrites = ReentrantLock()
    private val playlists = PlaylistFiles(dir, syncDirectory)
    private class Ticket(val auto: Boolean) {
        @Volatile var cleared = false
        val finished = CompletableDeferred<Unit>()
    }
    private val pending = mutableMapOf<SongKey, Ticket>()
    private val autoJoining = mutableMapOf<SongKey, Ticket>()
    private val mutable = MutableStateFlow<List<StoredSong>>(emptyList())
    val songs = mutable.asStateFlow()
    private val committedPlaylists = MutableStateFlow<Set<Pair<MusicOwner, String>>>(emptySet())
    internal fun completePlaylistIds(owner: MusicOwner) = committedPlaylists.map { playlists ->
        playlists.filter { it.first == owner }.mapTo(hashSetOf()) { it.second }
    }.distinctUntilChanged()
    private val usageByOwner = mutableMapOf<MusicOwner, Usage>()
    private val mutableUsage = MutableStateFlow<Map<MusicOwner, MusicStorageUsage>>(emptyMap())
    fun storageUsage(owner: MusicOwner): Flow<MusicStorageUsage> =
        mutableUsage.map { it[owner] ?: MusicStorageUsage() }.distinctUntilChanged()
    private var clock = 0L

    init {
        check(dir.isDirectory || dir.mkdirs())
        val files = dir.listFiles() ?: throw IOException("Music store unavailable")
        playlists.recover(files)
        files.filter { it.extension == "meta" }.forEach { meta ->
            val file = File(dir, meta.nameWithoutExtension + ".blob")
            // An unreadable store is unavailable, not permission to destroy another reference.
            val row = Json.decodeFromString<Row>(meta.readText())
            if (!row.auto && row.refs.isEmpty() && row.drafts.isEmpty() && Files.notExists(file.toPath())) {
                // A newer blob may now satisfy this key's playlist references. Check required
                // keys after recovery, separately from retrying this stale metadata unlink.
                staleMetadata[meta.toPath()] = row.key
                return@forEach
            }
            val valid = canOpen(file, row, verifyDigest = true)
            if (valid && (row.auto || offline(row) || row.drafts.isNotEmpty())) {
                check(row.key !in entries)
                entries[row.key] = Entry(file, row, file.lastModified())
                clock = maxOf(clock, file.lastModified())
            } else if (!offline(row)) {
                playlists.beforeReclaim()
                Files.deleteIfExists(meta.toPath()); Files.deleteIfExists(file.toPath())
            } else {
                // Keep the saved collection metadata, but do not grant this blob's playback.
                staleMetadata[meta.toPath()] = row.key
            }
        }
        for (path in staleMetadata.keys.toList()) {
            try { unlinkStaleMetadata(path) } catch (_: IOException) { /* Clear can retry. */ }
        }
        val live = entries.values.mapTo(hashSetOf()) { it.file }
        files.filter { it.extension == "tmp" || it.extension == "blob" && it !in live }.forEach { Files.deleteIfExists(it.toPath()) }
        refresh()
    }

    private class Usage {
        var playlists = 0
        private var offlineBytes = 0L
        private var pendingBytes = 0L
        private var cachedSongs = 0
        private var cacheBytes = 0L
        private var inUseBytes = 0L

        fun change(row: Row, offline: Boolean, reading: Boolean, direction: Int = 1) {
            val bytes = row.size * direction
            when {
                offline -> offlineBytes += bytes
                row.drafts.isNotEmpty() -> { offlineBytes += bytes; pendingBytes += bytes }
                else -> {
                    cacheBytes += bytes
                    if (row.auto) cachedSongs += direction
                    else if (reading) inUseBytes += bytes
                }
            }
        }

        fun snapshot() = MusicStorageUsage(playlists, offlineBytes, pendingBytes, cachedSongs, cacheBytes, inUseBytes)
    }

    private fun refresh() {
        entries.values.forEach { it.downloaded = offline(it.row) }
        val complete = playlists.readyIds(entries.keys)
        usageByOwner.clear()
        for ((owner, _) in complete) usageByOwner.getOrPut(owner, ::Usage).playlists++
        mutable.value = entries.values.mapNotNull { entry ->
            val row = entry.row
            val offline = offline(row)
            val reading = entry.readers > 0
            usageByOwner.getOrPut(row.key.owner, ::Usage).change(row, offline, reading)
            if (!row.auto && !offline && !reading) null
            else StoredSong(row.key, row.title, row.size, row.auto, offline, DIRECT_DOWNLOAD in row.refs)
        }
        mutableUsage.value = usageByOwner.mapValues { it.value.snapshot() }
        committedPlaylists.value = complete
    }

    // A playlist can stage many verified tracks without changing the playable inventory.
    // Update only the affected account rather than scanning the store on each staged track.
    private fun publishStage(entry: Entry, before: Row? = null) {
        if (before != null && (offline(before) || before.drafts.isNotEmpty())) return
        val row = entry.row
        val owner = row.key.owner
        val usage = usageByOwner.getOrPut(owner, ::Usage)
        val reading = entry.readers > 0
        before?.let { usage.change(it, offline = false, reading = reading, direction = -1) }
        usage.change(row, offline(row), reading)
        mutableUsage.value = mutableUsage.value + (owner to usage.snapshot())
    }

    private fun offline(row: Row) = row.refs.isNotEmpty() || row.key in playlists.required
    fun hasOfflineReference(key: SongKey) = lock.withLock { entries[key]?.row?.let(::offline) == true }
    private fun meta(file: File) = File(dir, file.nameWithoutExtension + ".meta")
    private fun save(entry: Entry, row: Row, commit: ((() -> Unit) -> Boolean)? = null, durable: () -> Unit = {}): Boolean {
        val tmp = File.createTempFile("ref", ".tmp", dir)
        try {
            tmp.outputStream().use { it.write(Json.encodeToString(row).toByteArray()); it.fd.sync() }
            val replace = {
                Files.move(tmp.toPath(), meta(entry.file).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                entry.row = row
                entry.downloaded = offline(row)
                durable()
            }
            return commit?.invoke(replace) ?: run { replace(); true }
        } finally { tmp.delete() }
    }
    private fun unused(entry: Entry) = entry.readers == 0 && !entry.row.auto && !offline(entry.row) && entry.row.drafts.isEmpty()
    private fun releaseUnused(entry: Entry) {
        if (!unused(entry)) return
        playlists.beforeReclaim()
        unlink(entry.file.toPath())
        entries.remove(entry.row.key, entry)
        val path = meta(entry.file).toPath()
        staleMetadata[path] = entry.row.key
        unlinkStaleMetadata(path)
    }
    private fun unlinkStaleMetadata(path: Path) {
        unlink(path)
        staleMetadata.remove(path)
    }
    private fun touch(entry: Entry) {
        clock = maxOf(System.currentTimeMillis(), clock + 1)
        entry.last = clock
        entry.file.setLastModified(clock)
    }

    /** Serialize with retention transactions so contention cannot become an HTTP source decision.
     * Transfer and verification run outside this lock. Call on a loader/IO thread.
     */
    fun pin(key: SongKey, current: () -> Boolean): PinnedSong? = pin(key, false, current)

    fun pin(key: SongKey, downloadOnly: Boolean, current: () -> Boolean): PinnedSong? {
        try { lock.lockInterruptibly() }
        catch (_: InterruptedException) { throw InterruptedIOException("Music pin interrupted") }
        try {
            val entry = entries[key] ?: return null
            if (!current() || ((downloadOnly || !entry.row.auto) && !offline(entry.row))) return null
            if (!canOpen(entry)) { invalidate(entry); return null }
            touch(entry)
            entry.readers++
            return PinnedSong(entry.file, entry.row.size, current, downloadRetained = { entry.downloaded }, downloadOnly = downloadOnly) {
                lock.withLock {
                    entry.readers--
                    if (!entry.row.auto && !offline(entry.row)) {
                        try { releaseUnused(entry) } catch (_: IOException) { /* Clear or reopen can retry. */ }
                        refresh()
                    }
                }
            }
        } catch (_: IOException) { return null } catch (_: SecurityException) { return null }
        finally { lock.unlock() }
    }

    private fun canOpen(entry: Entry, verifyDigest: Boolean = false): Boolean = canOpen(entry.file, entry.row, verifyDigest)

    private fun canOpen(file: File, row: Row, verifyDigest: Boolean): Boolean = runCatching {
        file.isFile && file.inputStream().use { it.channel.size() == row.size && (!verifyDigest || digest(it) == row.sha) }
    }.getOrDefault(false)

    private fun invalidate(entry: Entry, publish: Boolean = true) {
        entry.downloaded = false
        entries.remove(entry.row.key, entry)
        staleMetadata[meta(entry.file).toPath()] = entry.row.key
        if (publish) refresh()
    }

    fun verifyDownloads(owner: MusicOwner, current: () -> Boolean) = lock.withLock {
        if (!current()) return@withLock
        val invalid = entries.values.filter { it.row.key.owner == owner && offline(it.row) && !canOpen(it) }
        invalid.forEach { invalidate(it, publish = false) }
        if (invalid.isNotEmpty()) refresh()
    }

    fun clearAuto(owner: MusicOwner, id: String? = null) = lock.withLock {
        fun matches(key: SongKey) = key.owner == owner && (id == null || key.id == id)
        pending.forEach { (key, ticket) -> if (matches(key)) ticket.cleared = true }
        autoJoining.forEach { (key, ticket) -> if (matches(key)) ticket.cleared = true }
        try {
            val metadataRetries = staleMetadata.filterValues(::matches).keys
            val candidates = entries.values.filter { matches(it.row.key) && (it.row.auto || unused(it)) }
            for (entry in candidates) {
                if (entry.row.auto) removeAuto(entry)
                else releaseUnused(entry) // Retry an unlink after retention was already revoked.
            }
            metadataRetries.forEach(::unlinkStaleMetadata)
        } finally { refresh() }
    }
    private fun removeAuto(entry: Entry) {
        save(entry, entry.row.copy(auto = false)); releaseUnused(entry)
    }
    private fun retainAuto(entry: Entry, cap: Int, priority: List<String>) {
        save(entry, entry.row.copy(auto = true))
        try { trim(entry.row.key.owner, cap, priority) } finally { refresh() }
    }

    /** Independent durable holders; saved playlists do not duplicate their membership here. */
    fun offlineReference(key: SongKey, reference: String, retain: Boolean, cap: Int) = lock.withLock {
        require(reference.isNotBlank() && cap in CacheOptions.caps)
        val entry = entries[key] ?: return@withLock false
        val refs = if (retain) entry.row.refs + reference else entry.row.refs - reference
        try {
            save(entry, entry.row.copy(refs = refs)); releaseUnused(entry)
            trim(key.owner, cap, emptyList()); true
        } finally { refresh() }
    }

    fun limit(owner: MusicOwner, cap: Int, priority: List<String>) = lock.withLock {
        require(cap in CacheOptions.caps)
        try { trim(owner, cap, priority) } finally { refresh() }
    }
    private fun trim(owner: MusicOwner, cap: Int, priority: List<String>) {
        val budget = entries.values.filter { it.row.key.owner == owner && it.row.auto && !offline(it.row) }
        if (budget.size <= cap) return
        budget.sortedWith(compareBy<Entry> { priority.indexOf(it.row.key.id).let { i -> if (i < 0) Int.MIN_VALUE else -i } }
            .thenBy { it.last }).take(budget.size - cap).forEach(::removeAuto)
    }

    internal suspend fun <T> attemptPlaylist(owner: MusicOwner, id: String, trace: OfflineAttemptTrace?, write: suspend () -> T): T {
        trace?.step(PLAYLIST_LOCK)
        return writePlaylist(owner, id) {
            trace?.step(ATTEMPT_SLOT)
            playlistAttempts.withPermit {
                trace?.step(ATTEMPT_ADMITTED)
                write()
            }
        }
    }

    // Keep same-playlist refresh/removal serialized, including across account facade lifetimes.
    internal suspend fun <T> writePlaylist(owner: MusicOwner, id: String, write: suspend () -> T): T =
        playlistWriters.run(owner to id, write)

    // The server admits one playlist evaluation per account. Keep the whole paginated
    // read together, but allow other manifests to load while this playlist transfers audio.
    internal suspend fun <T> readPlaylistManifest(owner: MusicOwner, trace: OfflineAttemptTrace?, read: suspend () -> T): T {
        trace?.step(MANIFEST_LOCK)
        return manifestReaders.run(owner, read)
    }

    private class KeyedMutex<K> {
        private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
        private val entries = mutableMapOf<K, Entry>()

        suspend fun <T> run(key: K, block: suspend () -> T): T {
            val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
            try { return entry.mutex.withLock { block() } }
            finally { synchronized(entries) { if (--entry.users == 0) entries.remove(key) } }
        }
    }

    internal fun complete(owner: MusicOwner) = lock.withLock {
        recoverPendingPlaylist()
        playlists.complete(owner)
    }
    internal fun select(owner: MusicOwner, id: String) = lock.withLock {
        recoverPendingPlaylist()
        playlists.select(owner, id)?.takeIf { entries.keys.containsAll(trackKeys(owner, it)) }
    }

    private fun recoverPendingPlaylist() {
        try { publishDurablePlaylist() } catch (_: IOException) { /* Serve the last durable view. */ }
    }

    private fun publishDurablePlaylist(cap: Int? = null, recovering: Boolean = true): Boolean? {
        val change = playlists.publishDurable() ?: return null
        // A failed sync allows arbitrary time before a retry. Recheck those bytes just as
        // on reopen. Even a new commit needs to prove its verified bytes still open after sync.
        for (key in change.required) {
            val entry = entries[key] ?: continue
            if (!canOpen(entry, verifyDigest = recovering)) invalidate(entry, publish = false)
        }
        val playable = entries.keys.containsAll(change.required)
        refresh()
        reclaim(change.superseded)
        if (cap != null) try { trim(change.owner, cap, emptyList()) } catch (_: IOException) { /* Next limit retries. */ }
        try { clearDraft(change.owner, change.id, emptySet(), {}) } catch (_: IOException) { /* Already durable. */ }
        refresh()
        return playable
    }

    private fun reclaim(keys: Set<SongKey>) {
        for (key in keys) try { entries[key]?.let(::releaseUnused) }
        catch (_: IOException) { /* No durable hold; reopen reaps garbage after failed unlink. */ }
    }
    internal fun retainedPlaylistIds(owner: MusicOwner) = lock.withLock {
        playlists.committedIds().filter { it.first == owner }.mapTo(hashSetOf()) { it.second }.apply {
            entries.values.filter { it.row.key.owner == owner }.forEach { addAll(it.row.drafts) }
        }
    }
    internal fun resumePlaylist(owner: MusicOwner, id: String, keys: Set<SongKey>,
        trace: OfflineAttemptTrace? = null, checkCurrent: () -> Unit): Set<SongKey> = lock.withLock {
        trace?.step(RESUME_SCAN)
        checkCurrent()
        publishDurablePlaylist() // Do not remove drafts held by an unsynced manifest.
        checkCurrent()
        clearDraft(owner, id, keys, checkCurrent)
        keys.filterTo(hashSetOf()) { key ->
            entries[key]?.let { entry ->
                (id in entry.row.drafts || playlists.contains(owner, id, key)) && canOpen(entry)
            } == true
        }
    }
    private fun clearDraft(owner: MusicOwner, id: String, keep: Set<SongKey>, checkCurrent: () -> Unit): Boolean {
        val affected = entries.values.filter { it.row.key.owner == owner && id in it.row.drafts && it.row.key !in keep }
        if (affected.isEmpty()) return false
        val changesUsage = affected.any { !offline(it.row) && it.row.drafts.size == 1 }
        playlists.beforeReclaim()
        try {
            for (entry in affected) {
                checkCurrent()
                save(entry, entry.row.copy(drafts = entry.row.drafts - id))
                releaseUnused(entry)
            }
            return true
        } finally { if (changesUsage) refresh() }
    }
    private fun afterCommit(owner: MusicOwner, cap: Int, old: Set<SongKey>) {
        try { playlists.beforeReclaim() } catch (_: IOException) { /* Retried before any actual unlink. */ }
        reclaim(old)
        // Commit has succeeded. A failure trimming AUTO is not a failed playlist replacement.
        try { trim(owner, cap, emptyList()) } catch (_: IOException) { /* Next limit retries. */ }
        finally { refresh() }
    }
    internal fun commit(owner: MusicOwner, manifest: OfflineManifest, cap: Int,
        trace: OfflineAttemptTrace? = null, commit: (() -> Unit) -> Boolean) = lock.withLock {
        trace?.step(COMMIT_INDEX)
        publishDurablePlaylist(cap)
        val keys = trackKeys(owner, manifest)
        val id = manifest.collection.reference.id
        check(keys.all { key -> entries.getValue(key).let { e ->
            (id in e.row.drafts || playlists.contains(owner, id, key)) && canOpen(e)
        } })
        trace?.step(COMMIT_DISK_WAIT)
        diskWrites.withLock {
            trace?.step(COMMIT_WRITE)
            playlists.rename(owner, manifest, keys, freeBytes() - RESERVE, commit)
        }
        trace?.step(COMMIT_CLEANUP)
        if (publishDurablePlaylist(cap, recovering = false) != true) throw IOException("Playlist audio is unavailable")
    }
    internal fun remove(owner: MusicOwner, id: String, cap: Int, checkCurrent: () -> Unit) = lock.withLock {
        checkCurrent()
        publishDurablePlaylist()
        checkCurrent()
        val old = playlists.remove(owner, id)
        if (old != null) {
            old.forEach { key -> entries[key]?.let { it.downloaded = offline(it.row) } }
            afterCommit(owner, cap, old)
        }
        val removedDraft = clearDraft(owner, id, emptySet(), checkCurrent)
        old != null || removedDraft
    }

    /** Network and digest never hold the index lock. The ticket also fences clear during descriptor lookup. */
    suspend fun fill(key: SongKey, title: String, cap: Int, priority: List<String>, current: () -> Boolean,
        prepare: suspend () -> OfflineMediaDescriptor, download: suspend (OfflineMediaDescriptor, (InputStream) -> Unit) -> Unit) =
        fillShared(key, title, cap, priority, null, null, current, prepare, null, download)

    suspend fun download(key: SongKey, title: String, cap: Int, current: () -> Boolean,
        prepare: suspend () -> OfflineMediaDescriptor,
        commit: (() -> Unit) -> Boolean,
        consume: suspend (OfflineMediaDescriptor, (InputStream) -> Unit) -> Unit) =
        fillShared(key, title, cap, emptyList(), null, commit, current, prepare, null, consume)

    fun removeDownload(key: SongKey, cap: Int, commit: (() -> Unit) -> Boolean): Boolean = lock.withLock {
        require(cap in CacheOptions.caps)
        val entry = entries[key] ?: return@withLock false
        if (DIRECT_DOWNLOAD !in entry.row.refs) return@withLock false
        // Stage the rewrite before checking the exact incarnation. Only its final replace is fenced.
        if (!save(entry, entry.row.copy(refs = entry.row.refs - DIRECT_DOWNLOAD), commit, playlists::markDirectoryDirty))
            return@withLock false
        try {
            // Never reclaim the blob while the durable metadata may still name its direct hold.
            playlists.beforeReclaim()
            releaseUnused(entry)
            trim(key.owner, cap, emptyList())
            true
        } finally { refresh() }
    }

    internal suspend fun stage(playlistId: String, key: SongKey, title: String, cap: Int, current: () -> Boolean,
        prepare: suspend () -> OfflineMediaDescriptor, trace: OfflineAttemptTrace? = null,
        download: suspend (OfflineMediaDescriptor, (InputStream) -> Unit) -> Unit) =
        fillShared(key, title, cap, emptyList(), playlistId, null, current, prepare, trace, download)

    private suspend fun fillShared(key: SongKey, title: String, cap: Int, priority: List<String>, playlistId: String?, commitDownload: ((() -> Unit) -> Boolean)?,
        current: () -> Boolean, prepare: suspend () -> OfflineMediaDescriptor, trace: OfflineAttemptTrace?,
        download: suspend (OfflineMediaDescriptor, (InputStream) -> Unit) -> Unit) {
        require(cap in CacheOptions.caps)
        val direct = commitDownload != null
        val auto = playlistId == null && !direct
        val ticket = Ticket(auto)
        fun live() = (!auto || !ticket.cleared) && current()
        var joined = false
        while (true) {
            trace?.step(MEDIA_LOOKUP)
            val existing = lock.withLock {
                if (!current()) return
                entries[key]?.let {
                    trace?.step(MEDIA_REUSE)
                    if (!canOpen(it)) {
                        invalidate(it)
                    } else {
                        if (playlistId != null) {
                            if (playlistId !in it.row.drafts && !playlists.contains(key.owner, playlistId, key)) {
                                val before = it.row
                                save(it, before.copy(drafts = before.drafts + playlistId))
                                try { playlists.syncDirectory() } finally { publishStage(it, before) }
                            }
                        } else if (commitDownload != null) {
                            val previous = it.row
                            if (DIRECT_DOWNLOAD !in previous.refs) {
                                save(it, previous.copy(refs = previous.refs + DIRECT_DOWNLOAD), commitDownload) {
                                    playlists.syncDirectory()
                                    refresh()
                                }
                            }
                        } else if (!it.row.auto) retainAuto(it, cap, priority)
                        return
                    }
                }
                pending[key].also {
                    if (it == null) pending[key] = ticket
                    else if (auto) {
                        if (it.auto || key in autoJoining) return // Existing duplicate AUTO behavior.
                        autoJoining[key] = ticket
                    }
                }
            } ?: break
            if (auto) {
                // Hold our own clear fence until adoption, including after the network ticket retires.
                try {
                    existing.finished.await()
                    lock.withLock {
                        val entry = entries[key]
                        if (live() && entry != null && !entry.row.auto) retainAuto(entry, cap, priority)
                    }
                } finally { lock.withLock { autoJoining.remove(key, ticket) } }
                return
            }
            trace?.step(MEDIA_JOIN)
            if (joined) throw IOException("Concurrent fill superseded")
            existing.finished.await()
            joined = true
        }
        var stage: File? = null
        try {
            val media = prepare()
            trace?.step(MEDIA_SETUP)
            require(media.trackId == key.id && media.byteLength in 1..MAX_BLOB && media.byteLength <= freeBytes() - RESERVE)
            if (!live()) return
            var verified = false
            val tmp = File.createTempFile("fill", ".tmp", dir).also { stage = it }
            download(media) { input ->
                trace?.step(MEDIA_BODY)
                val hash = MessageDigest.getInstance("SHA-256")
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var left = media.byteLength
                    while (left > 0) {
                        if (!live()) throw IOException("Fill interrupted")
                        val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        if (n <= 0) throw IOException("Incomplete media")
                        // Parallel transfers must not both spend the same free space. Never
                        // hold this lock across a network read or a full audio download.
                        val firstWriteTrace = trace?.takeIf { left == media.byteLength }
                        firstWriteTrace?.step(MEDIA_WRITE_WAIT)
                        diskWrites.withLock {
                            firstWriteTrace?.step(MEDIA_WRITE)
                            if (!live() || n > freeBytes() - RESERVE) throw IOException("Fill interrupted")
                            out.write(buffer, 0, n)
                        }
                        firstWriteTrace?.step(MEDIA_BODY)
                        hash.update(buffer, 0, n); left -= n
                    }
                    // The final EOF check can still block on the response body.
                    val eof = input.read() == -1
                    trace?.step(MEDIA_VERIFY)
                    require(eof && hash.digest().hex() == media.sha256)
                    out.fd.sync()
                    verified = true
                }
            }
            require(verified)
            trace?.step(MEDIA_PUBLISH)
            lock.withLock {
                trace?.step(MEDIA_RETAIN)
                // Linearization point: session/window cancellation before admission cannot publish;
                // clears after admission serialize here and release AUTO before returning.
                if (!live()) return
                val row = Row(key, title, media.byteLength, media.sha256, auto = auto,
                    refs = if (direct) setOf(DIRECT_DOWNLOAD) else emptySet(), drafts = setOfNotNull(playlistId))
                val entry = Entry(File(dir, "${UUID.randomUUID()}.blob"), row, 0)
                Files.move(tmp.toPath(), entry.file.toPath(), ATOMIC_MOVE)
                try {
                    if (commitDownload != null) {
                        if (!canOpen(entry)) throw IOException("Verified audio cannot be opened")
                        if (!save(entry, row, commitDownload) {
                                playlists.syncDirectory()
                                entries[key] = entry
                                touch(entry)
                                refresh()
                            }) Files.deleteIfExists(entry.file.toPath())
                        return
                    }
                    save(entry, row)
                    if (!auto) playlists.syncDirectory()
                } catch (e: Exception) {
                    meta(entry.file).delete(); entry.file.delete(); throw e
                }
                val opened = canOpen(entry)
                if (!live() || !opened) {
                    Files.deleteIfExists(meta(entry.file).toPath()); Files.deleteIfExists(entry.file.toPath())
                    if (!opened) throw IOException("Verified audio cannot be opened")
                    return
                }
                touch(entry); entries[key] = entry
                if (auto) try { trim(key.owner, cap, priority) } finally { refresh() }
                else publishStage(entry)
            }
        } finally {
            stage?.delete()
            lock.withLock { pending.remove(key, ticket) }
            ticket.finished.complete(Unit)
        }
    }

    private companion object {
        const val DIRECT_DOWNLOAD = "offline-v2-direct"
        const val MAX_BLOB = 512L * 1024 * 1024
        const val RESERVE = 64L * 1024 * 1024
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        fun digest(input: InputStream): String {
            val hash = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; hash.update(buf, 0, n) }
            return hash.digest().hex()
        }
    }
}
