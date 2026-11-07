package dev.spofify.mobile.core.saved

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Representation ownership seam. Implementations exclusively own their files (never point the
 * default implementation at SongCache). Calls for one key are serialized by SavedLibraryStore.
 * Cleanup failures must throw and remain included in ownedBytes(). No automatic eviction.
 */
interface SavedRepresentationBackend {
    fun keys(namespace: String): Set<String>
    fun ownedBytes(): Long
    fun length(namespace: String, key: String, partial: Boolean): Long?
    fun open(namespace: String, key: String): InputStream
    fun openPartial(namespace: String, key: String): InputStream
    fun writePartial(namespace: String, key: String, offset: Long): OutputStream
    fun promote(namespace: String, key: String)
    fun discardPartial(namespace: String, key: String)
    fun remove(namespace: String, key: String)
}

/** Dedicated app-private saved-only files; the caller supplies a backup-excluded directory. */
class PrivateSavedMediaBackend(private val root: File) : SavedRepresentationBackend {
    init { safeDirectory(root) }
    private fun path(namespace: String, key: String, partial: Boolean): File {
        require(namespace.matches(Regex("[0-9a-f]{64}")) && key.matches(Regex("[0-9a-f]{64}")))
        val directory = File(root, namespace)
        safeDirectory(directory)
        return File(directory, key + if (partial) ".part" else ".media").also(::rejectLink)
    }
    override fun keys(namespace: String): Set<String> {
        require(namespace.matches(Regex("[0-9a-f]{64}")))
        val directory = File(root, namespace)
        if (!directory.exists()) return emptySet()
        rejectLink(directory)
        return (directory.listFiles() ?: throw java.io.IOException("Cannot list saved media")).mapNotNull {
            rejectLink(it)
            it.name.substringBefore('.').takeIf { key -> key.matches(Regex("[0-9a-f]{64}")) }
        }.toSet()
    }
    override fun ownedBytes(): Long = treeBytes(root)
    override fun length(namespace: String, key: String, partial: Boolean): Long? =
        path(namespace, key, partial).takeIf { it.isFile }?.length()
    override fun open(namespace: String, key: String): InputStream = FileInputStream(path(namespace, key, false))
    override fun openPartial(namespace: String, key: String): InputStream = FileInputStream(path(namespace, key, true))
    override fun writePartial(namespace: String, key: String, offset: Long): OutputStream {
        val file = path(namespace, key, true)
        require(offset >= 0)
        if (offset > 0 && (!file.isFile || file.length() != offset)) throw java.io.IOException("Saved partial changed")
        val out = FileOutputStream(file, offset > 0)
        return object : OutputStream() {
            override fun write(b: Int) = out.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { try { out.fd.sync() } finally { out.close() } }
        }
    }
    override fun promote(namespace: String, key: String) {
        Files.move(path(namespace, key, true).toPath(), path(namespace, key, false).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    override fun discardPartial(namespace: String, key: String) { Files.deleteIfExists(path(namespace, key, true).toPath()) }
    override fun remove(namespace: String, key: String) {
        Files.deleteIfExists(path(namespace, key, true).toPath())
        Files.deleteIfExists(path(namespace, key, false).toPath())
    }
}

internal fun rejectLink(file: File) {
    if (Files.isSymbolicLink(file.toPath())) throw java.io.IOException("Saved storage link refused")
}
internal fun safeDirectory(file: File) {
    rejectLink(file)
    if (!file.isDirectory && !file.mkdirs()) throw java.io.IOException("Cannot create saved storage")
    rejectLink(file)
}
internal fun treeBytes(root: File): Long {
    if (!root.exists()) return 0
    rejectLink(root)
    if (root.isFile) return root.length()
    return (root.listFiles() ?: throw java.io.IOException("Cannot account saved storage")).fold(0L) { sum, file ->
        Math.addExact(sum, treeBytes(file))
    }
}
internal fun savedHash(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach {
        val bytes = it.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun mediaKey(d: SavedMediaDescriptor): String = savedHash(d.mediaVersion, d.sha256, d.byteLength.toString(), d.strongETag)
