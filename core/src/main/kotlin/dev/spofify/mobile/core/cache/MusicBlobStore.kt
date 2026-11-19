package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.offline.OfflineManifest
import dev.spofify.mobile.core.offline.OfflineMediaDescriptor
import dev.spofify.mobile.core.offline.PlaylistFiles
import dev.spofify.mobile.core.offline.trackKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

data class StoredSong(val key: SongKey, val title: String, val bytes: Long, val auto: Boolean, val offline: Boolean)

/** Single process owner. Construct on IO; playback falls through while recovery or mutation is busy.
 * .meta commits verified bytes, AUTO, independent refs and resumable download holds.
 * Only .playlist files publish playlist membership; download holds never authorize playback.
 */
class MusicBlobStore(private val dir: File, private val freeBytes: () -> Long = { dir.usableSpace }) {
    @Serializable
    private data class Row(val key: SongKey, val title: String, val size: Long, val sha: String,
        val auto: Boolean = true, val refs: Set<String> = emptySet(), val drafts: Set<String> = emptySet())
    private class Entry(val file: File, var row: Row, var last: Long, var readers: Int = 0)
    private val lock = ReentrantLock()
    private val entries = mutableMapOf<SongKey, Entry>()
    internal val playlistWriter = Mutex()
    private val playlists = PlaylistFiles(dir)
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
    private var clock = 0L

    init {
        check(dir.isDirectory || dir.mkdirs())
        val files = dir.listFiles() ?: throw IOException("Music store unavailable")
        playlists.recover(files)
        files.filter { it.extension == "meta" }.forEach { meta ->
            val file = File(dir, meta.nameWithoutExtension + ".blob")
            // An unreadable store is unavailable, not permission to destroy another reference.
            val row = Json.decodeFromString<Row>(meta.readText())
            val valid = file.isFile && file.length() == row.size && file.inputStream().use { digest(it) == row.sha }
            if (valid && (row.auto || offline(row) || row.drafts.isNotEmpty())) {
                check(row.key !in entries)
                entries[row.key] = Entry(file, row, file.lastModified())
                clock = maxOf(clock, file.lastModified())
            } else if (!offline(row)) {
                playlists.beforeReclaim()
                Files.deleteIfExists(meta.toPath()); Files.deleteIfExists(file.toPath())
            } else throw IOException("Referenced media unavailable")
        }
        if (!entries.keys.containsAll(playlists.required)) throw IOException("Incomplete saved playlist")
        val live = entries.values.mapTo(hashSetOf()) { it.file }
        files.filter { it.extension == "tmp" || it.extension == "blob" && it !in live }.forEach { Files.deleteIfExists(it.toPath()) }
        refresh()
        committedPlaylists.value = playlists.committedIds()
    }

    private fun refresh() {
        mutable.value = entries.values.mapNotNull { entry ->
            val row = entry.row
            if (!row.auto && !offline(row) && entry.readers == 0) null
            else StoredSong(row.key, row.title, row.size, row.auto, offline(row))
        }
    }
    private fun offline(row: Row) = row.refs.isNotEmpty() || row.key in playlists.required
    fun hasOfflineReference(key: SongKey) = lock.withLock { entries[key]?.row?.let(::offline) == true }
    private fun meta(file: File) = File(dir, file.nameWithoutExtension + ".meta")
    private fun save(entry: Entry, row: Row) {
        val tmp = File.createTempFile("ref", ".tmp", dir)
        try {
            tmp.outputStream().use { it.write(Json.encodeToString(row).toByteArray()); it.fd.sync() }
            Files.move(tmp.toPath(), meta(entry.file).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            entry.row = row
        } finally { tmp.delete() }
    }
    private fun releaseUnused(entry: Entry) {
        if (entry.readers != 0 || entry.row.auto || offline(entry.row) || entry.row.drafts.isNotEmpty()) return
        playlists.beforeReclaim()
        Files.deleteIfExists(entry.file.toPath()); Files.deleteIfExists(meta(entry.file).toPath())
        entries.remove(entry.row.key)
    }
    private fun touch(entry: Entry) {
        clock = maxOf(System.currentTimeMillis(), clock + 1)
        entry.last = clock
        entry.file.setLastModified(clock)
    }

    /** Try, never wait for a fill, recovery scan or retention transaction. AudioStream fences each read. */
    fun pin(key: SongKey, current: () -> Boolean): PinnedSong? {
        if (!lock.tryLock()) return null
        try {
            val entry = entries[key] ?: return null
            if (!current() || (!entry.row.auto && !offline(entry.row)) ||
                entry.file.length() != entry.row.size) return null
            touch(entry)
            entry.readers++
            return PinnedSong(entry.file, entry.row.size, current) {
                lock.withLock {
                    entry.readers--
                    if (!entry.row.auto && !offline(entry.row)) {
                        try { releaseUnused(entry) } catch (_: IOException) { /* Retry orphan cleanup at reopen. */ }
                        refresh()
                    }
                }
            }
        } catch (_: IOException) { return null } catch (_: SecurityException) { return null }
        finally { lock.unlock() }
    }

    fun clearAuto(owner: MusicOwner, id: String? = null) = lock.withLock {
        fun matches(key: SongKey) = key.owner == owner && (id == null || key.id == id)
        pending.forEach { (key, ticket) -> if (matches(key)) ticket.cleared = true }
        autoJoining.forEach { (key, ticket) -> if (matches(key)) ticket.cleared = true }
        try { entries.values.filter { matches(it.row.key) && it.row.auto }.forEach(::removeAuto) } finally { refresh() }
    }
    private fun removeAuto(entry: Entry) {
        save(entry, entry.row.copy(auto = false)); releaseUnused(entry)
    }

    /** Independent durable holders; saved playlists do not duplicate their membership here. */
    fun offlineReference(key: SongKey, reference: String, retain: Boolean, cap: Int) = lock.withLock {
        require(reference.isNotBlank() && cap in CacheOptions.caps)
        val entry = entries[key] ?: return@withLock false
        val refs = if (retain) entry.row.refs + reference else entry.row.refs - reference
        save(entry, entry.row.copy(refs = refs)); releaseUnused(entry)
        trim(key.owner, cap, emptyList()); refresh(); true
    }

    fun limit(owner: MusicOwner, cap: Int, priority: List<String>) = lock.withLock {
        require(cap in CacheOptions.caps)
        trim(owner, cap, priority); refresh()
    }
    private fun trim(owner: MusicOwner, cap: Int, priority: List<String>) {
        val budget = entries.values.filter { it.row.key.owner == owner && it.row.auto && !offline(it.row) }
        if (budget.size <= cap) return
        budget.sortedWith(compareBy<Entry> { priority.indexOf(it.row.key.id).let { i -> if (i < 0) Int.MIN_VALUE else -i } }
            .thenBy { it.last }).take(budget.size - cap).forEach(::removeAuto)
    }

    internal fun complete(owner: MusicOwner) = lock.withLock { playlists.complete(owner) }
    internal fun select(owner: MusicOwner, id: String) = lock.withLock { playlists.select(owner, id) }

    private fun reclaim(keys: Set<SongKey>) {
        for (key in keys) try { entries[key]?.let(::releaseUnused) }
        catch (_: IOException) { /* No durable hold; reopen reaps garbage after failed unlink. */ }
    }
    internal fun retainedPlaylistIds(owner: MusicOwner) = lock.withLock {
        playlists.committedIds().filter { it.first == owner }.mapTo(hashSetOf()) { it.second }.apply {
            entries.values.filter { it.row.key.owner == owner }.forEach { addAll(it.row.drafts) }
        }
    }
    internal fun resumePlaylist(owner: MusicOwner, id: String, keys: Set<SongKey>, checkCurrent: () -> Unit): Set<SongKey> = lock.withLock {
        checkCurrent()
        clearDraft(owner, id, keys, checkCurrent)
        keys.filterTo(hashSetOf()) { key ->
            entries[key]?.let { entry ->
                (id in entry.row.drafts || playlists.contains(owner, id, key)) && entry.file.length() == entry.row.size
            } == true
        }
    }
    private fun clearDraft(owner: MusicOwner, id: String, keep: Set<SongKey>, checkCurrent: () -> Unit): Boolean {
        val affected = entries.values.filter { it.row.key.owner == owner && id in it.row.drafts && it.row.key !in keep }
        if (affected.isEmpty()) return false
        playlists.beforeReclaim()
        for (entry in affected) {
            checkCurrent()
            save(entry, entry.row.copy(drafts = entry.row.drafts - id))
            releaseUnused(entry)
        }
        return true
    }
    private fun afterCommit(owner: MusicOwner, cap: Int, old: Set<SongKey>) {
        try { playlists.beforeReclaim() } catch (_: IOException) { /* Retried before any actual unlink. */ }
        reclaim(old)
        // Commit has succeeded. A failure trimming AUTO is not a failed playlist replacement.
        try { trim(owner, cap, emptyList()) } catch (_: IOException) { /* Next limit retries. */ }
        refresh()
        committedPlaylists.value = playlists.committedIds()
    }
    internal fun commit(owner: MusicOwner, manifest: OfflineManifest, cap: Int, checkCurrent: () -> Unit) = lock.withLock {
        val keys = trackKeys(owner, manifest)
        val id = manifest.collection.reference.id
        check(keys.all { key -> entries.getValue(key).let { e ->
            (id in e.row.drafts || playlists.contains(owner, id, key)) && e.file.length() == e.row.size
        } })
        afterCommit(owner, cap, playlists.commit(owner, manifest, keys, freeBytes() - RESERVE, checkCurrent))
        // Keep the hold until the complete manifest rename is durable. A failed cleanup
        // is harmless; the tracks are already published and cleanup can run on retry.
        try { clearDraft(owner, id, emptySet(), {}) } catch (_: IOException) { }
    }
    internal fun remove(owner: MusicOwner, id: String, cap: Int, checkCurrent: () -> Unit) = lock.withLock {
        checkCurrent()
        val old = playlists.remove(owner, id)
        if (old != null) afterCommit(owner, cap, old)
        val removedDraft = clearDraft(owner, id, emptySet(), checkCurrent)
        old != null || removedDraft
    }

    /** Network and digest never hold the index lock. The ticket also fences clear during descriptor lookup. */
    suspend fun fill(key: SongKey, title: String, cap: Int, priority: List<String>, current: () -> Boolean,
        prepare: suspend () -> OfflineMediaDescriptor, download: suspend (OfflineMediaDescriptor, (InputStream) -> Unit) -> Unit) =
        fillShared(key, title, cap, priority, null, current, prepare, download)

    internal suspend fun stage(playlistId: String, key: SongKey, title: String, cap: Int, current: () -> Boolean,
        prepare: suspend () -> OfflineMediaDescriptor, download: suspend (OfflineMediaDescriptor, (InputStream) -> Unit) -> Unit) =
        fillShared(key, title, cap, emptyList(), playlistId, current, prepare, download)

    private suspend fun fillShared(key: SongKey, title: String, cap: Int, priority: List<String>, playlistId: String?,
        current: () -> Boolean, prepare: suspend () -> OfflineMediaDescriptor,
        download: suspend (OfflineMediaDescriptor, (InputStream) -> Unit) -> Unit) {
        require(cap in CacheOptions.caps)
        val auto = playlistId == null
        val ticket = Ticket(auto)
        fun live() = (!auto || !ticket.cleared) && current()
        var joined = false
        while (true) {
            val existing = lock.withLock {
                if (!current()) return
                entries[key]?.let {
                    if (it.file.length() != it.row.size) throw IOException("Verified media unavailable")
                    if (playlistId != null) {
                        if (playlistId !in it.row.drafts && !playlists.contains(key.owner, playlistId, key)) {
                            save(it, it.row.copy(drafts = it.row.drafts + playlistId))
                            playlists.syncDirectory()
                        }
                    } else if (!it.row.auto) { save(it, it.row.copy(auto = true)); trim(key.owner, cap, priority); refresh() }
                    return
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
                        if (live() && entry != null && !entry.row.auto) {
                            save(entry, entry.row.copy(auto = true)); trim(key.owner, cap, priority); refresh()
                        }
                    }
                } finally { lock.withLock { autoJoining.remove(key, ticket) } }
                return
            }
            if (joined) throw IOException("Concurrent fill superseded")
            existing.finished.await()
            joined = true
        }
        var stage: File? = null
        try {
            val media = prepare()
            require(media.trackId == key.id && media.byteLength in 1..MAX_BLOB && media.byteLength <= freeBytes() - RESERVE)
            if (!live()) return
            var verified = false
            val tmp = File.createTempFile("fill", ".tmp", dir).also { stage = it }
            download(media) { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var left = media.byteLength
                    while (left > 0) {
                        if (!live() || freeBytes() < RESERVE) throw IOException("Fill interrupted")
                        val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        if (n <= 0) throw IOException("Incomplete media")
                        out.write(buffer, 0, n); hash.update(buffer, 0, n); left -= n
                    }
                    require(input.read() == -1 && hash.digest().hex() == media.sha256)
                    out.fd.sync()
                    verified = true
                }
            }
            require(verified)
            lock.withLock {
                // Linearization point: session/window cancellation before admission cannot publish;
                // clears after admission serialize here and release AUTO before returning.
                if (!live()) return
                val row = Row(key, title, media.byteLength, media.sha256, auto = auto, drafts = setOfNotNull(playlistId))
                val entry = Entry(File(dir, "${UUID.randomUUID()}.blob"), row, 0)
                Files.move(tmp.toPath(), entry.file.toPath(), ATOMIC_MOVE)
                try {
                    save(entry, row)
                    if (!auto) playlists.syncDirectory()
                } catch (e: Exception) {
                    meta(entry.file).delete(); entry.file.delete(); throw e
                }
                if (!live()) {
                    Files.deleteIfExists(meta(entry.file).toPath()); Files.deleteIfExists(entry.file.toPath())
                    return
                }
                touch(entry); entries[key] = entry
                if (auto) try { trim(key.owner, cap, priority) } finally { refresh() }
            }
        } finally {
            stage?.delete()
            lock.withLock { pending.remove(key, ticket) }
            ticket.finished.complete(Unit)
        }
    }

    private companion object {
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
