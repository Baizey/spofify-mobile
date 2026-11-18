package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.offline.OfflineMediaDescriptor
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MusicOwner(val origin: String, val accountId: String)

@Serializable
data class SongKey(val owner: MusicOwner, val id: String)

data class StoredSong(val key: SongKey, val title: String, val bytes: Long, val auto: Boolean, val offline: Boolean)

/** Single process owner. Construct on IO; playback falls through while recovery or mutation is busy.
 * The .meta rename commits a verified payload AND its durable references. Reader holds are process-local.
 */
class MusicBlobStore(private val dir: File, private val freeBytes: () -> Long = { dir.usableSpace }) {
    @Serializable
    private data class Row(val key: SongKey, val title: String, val size: Long, val sha: String,
        val auto: Boolean = true, val refs: Set<String> = emptySet())
    private class Entry(val file: File, var row: Row, var last: Long, var readers: Int = 0)
    private val lock = ReentrantLock()
    private val entries = mutableMapOf<SongKey, Entry>()
    private class Ticket { @Volatile var cleared = false }
    private val pending = mutableMapOf<SongKey, Ticket>()
    private val mutable = MutableStateFlow<List<StoredSong>>(emptyList())
    val songs = mutable.asStateFlow()
    private var clock = 0L

    init {
        check(dir.isDirectory || dir.mkdirs())
        val files = dir.listFiles() ?: throw IOException("Music store unavailable")
        files.filter { it.extension == "meta" }.forEach { meta ->
            val file = File(dir, meta.nameWithoutExtension + ".blob")
            // An unreadable store is unavailable, not permission to destroy another reference.
            val row = Json.decodeFromString<Row>(meta.readText())
            val valid = file.isFile && file.length() == row.size && file.inputStream().use { digest(it) == row.sha }
            if (valid && (row.auto || row.refs.isNotEmpty())) {
                check(row.key !in entries)
                entries[row.key] = Entry(file, row, file.lastModified())
                clock = maxOf(clock, file.lastModified())
            } else if (row.refs.isEmpty()) {
                Files.deleteIfExists(meta.toPath()); Files.deleteIfExists(file.toPath())
            } else throw IOException("Referenced media unavailable")
        }
        val live = entries.values.mapTo(hashSetOf()) { it.file }
        files.filter { it.extension == "tmp" || it.extension == "blob" && it !in live }.forEach { Files.deleteIfExists(it.toPath()) }
        refresh()
    }

    private fun refresh() {
        mutable.value = entries.values.map { entry ->
            val row = entry.row
            StoredSong(row.key, row.title, row.size, row.auto, row.refs.isNotEmpty())
        }
    }
    fun hasOfflineReference(key: SongKey) = lock.withLock { entries[key]?.row?.refs?.isNotEmpty() == true }
    private fun meta(file: File) = File(dir, file.nameWithoutExtension + ".meta")
    private fun save(entry: Entry, row: Row) {
        val tmp = File.createTempFile("ref", ".tmp", dir)
        try {
            tmp.writeText(Json.encodeToString(row))
            Files.move(tmp.toPath(), meta(entry.file).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            entry.row = row
        } finally { tmp.delete() }
    }
    private fun releaseUnused(entry: Entry) {
        if (entry.readers != 0 || entry.row.auto || entry.row.refs.isNotEmpty()) return
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
            if (!current() || (!entry.row.auto && entry.row.refs.isEmpty()) ||
                entry.file.length() != entry.row.size) return null
            touch(entry)
            entry.readers++
            return PinnedSong(entry.file, entry.row.size, current) {
                lock.withLock {
                    entry.readers--
                    if (!entry.row.auto && entry.row.refs.isEmpty()) {
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
        try { entries.values.filter { matches(it.row.key) && it.row.auto }.forEach(::removeAuto) } finally { refresh() }
    }
    private fun removeAuto(entry: Entry) {
        save(entry, entry.row.copy(auto = false)); releaseUnused(entry)
    }

    /** Independent durable holders, e.g. two collections sharing a song. No offline producer here. */
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
        val budget = entries.values.filter { it.row.key.owner == owner && it.row.auto && it.row.refs.isEmpty() }
        if (budget.size <= cap) return
        budget.sortedWith(compareBy<Entry> { priority.indexOf(it.row.key.id).let { i -> if (i < 0) Int.MIN_VALUE else -i } }
            .thenBy { it.last }).take(budget.size - cap).forEach(::removeAuto)
    }

    /** Network and digest never hold the index lock. The ticket also fences clear during descriptor lookup. */
    suspend fun fill(key: SongKey, title: String, cap: Int, priority: List<String>, current: () -> Boolean,
        prepare: suspend () -> OfflineMediaDescriptor, download: suspend (OfflineMediaDescriptor, (InputStream) -> Unit) -> Unit) {
        require(cap in CacheOptions.caps)
        val ticket = Ticket()
        fun live() = !ticket.cleared && current()
        lock.withLock {
            if (!current() || key in pending) return
            entries[key]?.let {
                if (!it.row.auto) { save(it, it.row.copy(auto = true)); trim(key.owner, cap, priority); refresh() }
                return
            }
            pending[key] = ticket
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
                val row = Row(key, title, media.byteLength, media.sha256)
                val entry = Entry(File(dir, "${UUID.randomUUID()}.blob"), row, 0)
                Files.move(tmp.toPath(), entry.file.toPath(), ATOMIC_MOVE)
                try { save(entry, row) } catch (e: Exception) { entry.file.delete(); throw e }
                if (!live()) {
                    Files.deleteIfExists(meta(entry.file).toPath()); Files.deleteIfExists(entry.file.toPath())
                    return
                }
                touch(entry); entries[key] = entry
                try { trim(key.owner, cap, priority) } finally { refresh() }
            }
        } finally { stage?.delete(); lock.withLock { pending.remove(key, ticket) } }
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
