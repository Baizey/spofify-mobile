package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.library.MusicKind
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** All callers are serialized by the catalog owner. No file here can stand in for an audio blob. */
internal class LocalCatalogStorage(root: File, identity: AccountIdentity) {
    private val directory = File(root, "catalog-v1-${key(identity.origin, identity.accountId)}")
    private val artwork = File(directory, "artwork")
    private val tracksFile = File(directory, "tracks.json")
    private val historyFile = File(directory, "history.json")

    fun loadTracks(): CatalogTrackData = readJson(tracksFile, MAX_TRACK_BYTES) ?: CatalogTrackData()
    fun loadHistory(): CatalogHistory = readJson(historyFile, MAX_HISTORY_BYTES) ?: CatalogHistory()
    fun saveTracks(data: CatalogTrackData) = writeJson(tracksFile, MAX_TRACK_BYTES, data)
    fun saveHistory(data: CatalogHistory) = writeJson(historyFile, MAX_HISTORY_BYTES, data)

    fun artwork(kind: MusicKind, id: String): LocalArtwork {
        val bytes = read(artworkFile(kind, id), MAX_ARTWORK_BYTES + 1) ?: return LocalArtwork.Unknown
        return when {
            bytes.size == 1 && bytes[0] == MISSING -> LocalArtwork.Missing
            bytes.size > 1 && bytes[0] == PRESENT -> LocalArtwork.Available(bytes.copyOfRange(1, bytes.size))
            else -> LocalArtwork.Unknown
        }
    }

    fun saveArtwork(kind: MusicKind, id: String, bytes: ByteArray?) {
        require(bytes == null || bytes.size in 1..MAX_ARTWORK_BYTES)
        val target = artworkFile(kind, id)
        atomicWrite(target, if (bytes == null) byteArrayOf(MISSING) else byteArrayOf(PRESENT) + bytes)
        val entries = artwork.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".art") }
        var size = entries.sumOf(File::length)
        var count = entries.size
        // Oldest observation first; keep the just-written entry even when the clock moves backwards.
        for (file in entries.filterNot { it == target }.sortedWith(compareBy(File::lastModified, File::getName))) {
            if (size <= MAX_ARTWORK_TOTAL_BYTES && count <= MAX_ARTWORK_ENTRIES) break
            val length = file.length()
            if (Files.deleteIfExists(file.toPath())) { size -= length; count-- }
        }
    }

    private fun artworkFile(kind: MusicKind, id: String) = File(artwork, "${key(kind.name, id)}.art")

    private inline fun <reified T> readJson(file: File, max: Int): T? = try {
        val bytes = read(file, max)
        bytes?.let { Json.decodeFromString<T>(it.decodeToString(throwOnInvalidSequence = true)) }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    private inline fun <reified T> writeJson(file: File, max: Int, data: T) {
        val bytes = Json.encodeToString(data).encodeToByteArray()
        require(bytes.size <= max) { "Local metadata limit exceeded" }
        atomicWrite(file, bytes)
    }

    private fun read(file: File, max: Int): ByteArray? = try {
        FileInputStream(file).use { stream ->
            val size = stream.channel.size()
            if (size !in 1..max.toLong()) return null
            val result = ByteArray(size.toInt())
            var used = 0
            while (used < result.size) {
                val count = stream.read(result, used, result.size - used)
                if (count == -1) return null
                used += count
            }
            result.takeIf { stream.read() == -1 }
        }
    } catch (_: IOException) {
        null
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val parent = requireNotNull(file.parentFile)
        Files.createDirectories(parent.toPath())
        val temporary = Files.createTempFile(parent.toPath(), ".catalog-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(temporary, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_TRACK_BYTES = 8 * 1024 * 1024
        private const val MAX_HISTORY_BYTES = 4 * 1024 * 1024
        private const val MAX_ARTWORK_BYTES = 1024 * 1024
        private const val MAX_ARTWORK_TOTAL_BYTES = 32 * 1024 * 1024L
        private const val MAX_ARTWORK_ENTRIES = 512
        private const val MISSING: Byte = 0
        private const val PRESENT: Byte = 1

        private fun key(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in parts) {
                val bytes = part.encodeToByteArray()
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
