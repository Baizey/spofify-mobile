package dev.spofify.mobile.core.personal

import dev.spofify.mobile.core.auth.AccountIdentity
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.time.Instant
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** Single account-owned writer; persistence is independent of the socket and audio stores. */
class PersonalCacheFile(root: File, identity: AccountIdentity) {
    private val key = "${identity.origin.length}:${identity.origin}${identity.accountId}".let { scope ->
        MessageDigest.getInstance("SHA-256").digest(scope.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private val directory = File(root, "personal-v2")
    private val file = File(directory, "$key.json")

    fun read(): PersonalCacheSnapshot = runCatching {
        require(file.length() in 1L..MAX_BYTES.toLong())
        Json.decodeFromString<PersonalCacheSnapshot>(file.readText()).also { data ->
            (data.recent + data.localPlays).forEach { Instant.parse(it.playedAt) }
        }
    }.getOrDefault(PersonalCacheSnapshot())

    fun write(snapshot: PersonalCacheSnapshot, commit: (() -> Unit) -> Unit = { it() }) {
        val bytes = Json.encodeToString(snapshot).encodeToByteArray()
        require(bytes.size <= MAX_BYTES)
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), ".personal-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            var published = false
            commit {
                Files.move(temporary, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                published = true
            }
            if (published) FileChannel.open(directory.toPath(), READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object { const val MAX_BYTES = 64 * 1024 * 1024 }
}
