package dev.spofify.mobile.storage

import android.content.Context
import dev.spofify.mobile.core.auth.ManualOfflineStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

internal fun installationOfflineStore(context: Context) = ReleaseOfflineStore(context.noBackupFilesDir)

/** A new release-only store: absent on first launch, never treats corrupt state as a default. */
internal class ReleaseOfflineStore(directory: File) : ManualOfflineStore {
    private val root = directory.toPath()
    private val policy = root.resolve("network-policy-v1")
    private val pending = root.resolve("network-policy-v1.pending")

    override fun read(): Boolean {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Unreadable network policy directory")
        // Interrupted or malformed state is conservatively offline. Local sessions still
        // load, and an explicit mode change can rewrite a normal policy without clearing data.
        if (!Files.notExists(pending, NOFOLLOW_LINKS)) return true
        if (Files.notExists(policy, NOFOLLOW_LINKS)) return false
        if (!Files.isRegularFile(policy, NOFOLLOW_LINKS)) throw IOException("Unreadable network policy")
        val bytes = Files.newInputStream(policy, NOFOLLOW_LINKS).use { input ->
            // Read at most one byte beyond the longest valid record.
            val bounded = ByteArray(maxOf(ONLINE.size, OFFLINE.size) + 1)
            var count = 0
            while (count < bounded.size) {
                val read = input.read(bounded, count, bounded.size - count)
                if (read < 0) break
                count += read
            }
            bounded.copyOf(count)
        }
        return !bytes.contentEquals(ONLINE)
    }

    override fun write(enabled: Boolean) {
        // SessionRepository serializes reads/writes. Preserve the pending marker on failure so
        // the next process cannot mistake an interrupted offline write for first launch/online.
        FileChannel.open(pending, CREATE, TRUNCATE_EXISTING, WRITE, NOFOLLOW_LINKS).use { output ->
            syncDirectory() // Make the fail-closed marker durable before writing the new record.
            val bytes = ByteBuffer.wrap(if (enabled) OFFLINE else ONLINE)
            while (bytes.hasRemaining()) output.write(bytes)
            output.force(true)
        }
        Files.move(pending, policy, ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory() // Do not report a successful switch until the rename is durable.
    }

    private fun syncDirectory() = FileChannel.open(root, READ).use { it.force(true) }

    private companion object {
        val OFFLINE = "spofify-offline-v1\n".encodeToByteArray()
        val ONLINE = "spofify-online-v1\n".encodeToByteArray()
    }
}
