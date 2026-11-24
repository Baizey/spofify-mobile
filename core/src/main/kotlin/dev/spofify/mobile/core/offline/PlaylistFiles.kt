package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.cache.MusicOwner
import dev.spofify.mobile.core.cache.SongKey
import dev.spofify.mobile.core.personal.PersonalTrack
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One committed file per playlist; never a separate durable list of track holds. All accesses
 * run under the blob store lock, so replacement and the derived reference counts change together.
 */
internal class PlaylistFiles(private val dir: File, private val forceDirectory: (File) -> Unit = ::syncPlaylistDirectory) {
    @Serializable private data class Item(val occurrenceId: String, val track: PersonalTrack)
    @Serializable private data class Document(val version: Int, val owner: MusicOwner, val id: String,
        val kind: OfflinePlaylistKind, val name: String, val description: String, val revision: Long,
        val fence: String, val items: List<Item>) {
        fun manifest(): OfflineManifest {
            require(version == 1)
            val ref = OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, id)
            val header = OfflineCollection(ref, name, kind, description, revision)
            val rows = items.mapIndexed { i, item -> OfflineOccurrence(item.occurrenceId, i.toLong(), item.track) }
            return OfflineManifest(header, fence, rows, rows.size.toLong(), rows.size.toLong())
        }
    }
    private data class Saved(val file: File, val tracks: Set<SongKey>)
    private data class Pending(val key: Pair<MusicOwner, String>, val next: Saved, val previous: OfflineManifest?)
    data class Publication(val owner: MusicOwner, val id: String, val superseded: Set<SongKey>)
    private var pending: Pending? = null
    private val saved = mutableMapOf<Pair<MusicOwner, String>, Saved>()
    private val counts = mutableMapOf<SongKey, Int>()
    val required: Set<SongKey> get() = counts.keys
    fun committedIds(): Set<Pair<MusicOwner, String>> = saved.keys.toSet()
    fun readyIds(available: Set<SongKey>): Set<Pair<MusicOwner, String>> =
        saved.entries.mapNotNullTo(hashSetOf()) { (id, playlist) -> id.takeIf { available.containsAll(playlist.tracks) } }

    // A rename/unlink linearizes the operation. Don't delete any superseded bytes before the
    // directory entry is durable (including reopen after process death before this barrier).
    private var flushNeeded = true
    fun syncDirectory() = forceDirectory(dir)
    fun markDirectoryDirty() { flushNeeded = true }
    fun beforeReclaim() {
        if (flushNeeded) { syncDirectory(); flushNeeded = false }
    }

    private fun read(file: File) = Json.decodeFromString<Document>(file.readText())
    fun recover(files: Array<File>) {
        val manifests = files.filter { it.extension == "playlist" }
        // A prior process may have renamed a manifest but not synced the directory.
        if (manifests.isNotEmpty()) beforeReclaim()
        for (file in manifests) {
            val doc = read(file)
            val manifest = doc.manifest()
            validatePlaylist(doc.id, manifest)
            val key = doc.owner to doc.id
            check(key !in saved)
            replace(key, Saved(file, trackKeys(doc.owner, manifest)))
        }
    }
    fun contains(owner: MusicOwner, id: String, key: SongKey) = saved[owner to id]?.tracks?.contains(key) == true
    fun select(owner: MusicOwner, id: String): OfflineManifest? {
        val key = owner to id
        pending?.takeIf { it.key == key }?.let { return it.previous }
        return saved[key]?.let { read(it.file).manifest() }
    }
    fun complete(owner: MusicOwner): List<OfflineManifest> = saved.keys.asSequence()
        .filter { it.first == owner }.mapNotNull { select(it.first, it.second) }.toList()

    private fun replace(key: Pair<MusicOwner, String>, value: Saved?): Set<SongKey> {
        val old = saved.remove(key)?.tracks.orEmpty()
        for (track in old) {
            val n = counts.getValue(track) - 1
            if (n == 0) counts.remove(track) else counts[track] = n
        }
        if (value != null) {
            saved[key] = value
            value.tracks.forEach { counts[it] = counts.getOrDefault(it, 0) + 1 }
        }
        return old
    }

    fun rename(owner: MusicOwner, manifest: OfflineManifest, keys: Set<SongKey>, available: Long,
        commit: (() -> Unit) -> Boolean) {
        check(pending == null)
        val c = manifest.collection
        val key = owner to c.reference.id
        val doc = Document(1, owner, c.reference.id, requireNotNull(c.playlistKind), c.name,
            requireNotNull(c.description), requireNotNull(c.revision), manifest.fence,
            manifest.items.map { Item(it.occurrenceId, it.track) })
        val file = saved[key]?.file ?: File(dir, "${UUID.randomUUID()}.playlist")
        val next = Pending(key, Saved(file, keys), select(owner, c.reference.id))
        val tmp = File.createTempFile("manifest", ".tmp", dir)
        try {
            val bytes = Json.encodeToString(doc).toByteArray()
            if (bytes.size > available) throw IOException("Insufficient manifest space")
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            syncDirectory() // Make the verified .blob and synced .meta names durable first.
            if (!commit {
                    Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    flushNeeded = true
                    pending = next
                }) throw CancellationException("Playlist commit retired")
        } finally { tmp.delete() }
    }

    /** The rename was authorized; only the directory barrier may publish its membership.
     * Until then [saved] retains the prior manifest and blob counts. The new bytes have draft holds. */
    fun publishDurable(): Publication? {
        val next = pending ?: return null
        beforeReclaim()
        val superseded = replace(next.key, next.next)
        pending = null
        return Publication(next.key.first, next.key.second, superseded)
    }

    fun remove(owner: MusicOwner, id: String): Set<SongKey>? {
        val key = owner to id
        val value = saved[key] ?: return null
        Files.delete(value.file.toPath())
        flushNeeded = true
        return replace(key, null)
    }
}

internal fun syncPlaylistDirectory(dir: File) = FileChannel.open(dir.toPath(), READ).use { it.force(true) }

internal fun trackKeys(owner: MusicOwner, manifest: OfflineManifest) =
    manifest.items.mapTo(linkedSetOf()) { SongKey(owner, it.track.id) }

internal fun validatePlaylist(id: String, m: OfflineManifest) {
    val ref = OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, id)
    require(m.collection.reference == ref && OfflineParser.validReference(ref) && m.collection.playlistKind != null &&
        m.collection.description != null && (m.collection.revision ?: 0) > 0 && m.fence.isNotBlank())
    require(m.items.size.toLong() == m.total && m.total == m.recordedAudioTotal)
    val occurrences = hashSetOf<String>()
    val tracks = hashMapOf<String, PersonalTrack>()
    m.items.forEachIndexed { i, row ->
        require(row.position == i.toLong() && row.occurrenceId.isNotBlank() && occurrences.add(row.occurrenceId))
        require(row.track.kind == "track" && row.track.hasAudio && OfflineParser.validSegment(row.track.id))
        val previous = tracks.put(row.track.id, row.track)
        require(previous == null || previous == row.track)
    }
}
