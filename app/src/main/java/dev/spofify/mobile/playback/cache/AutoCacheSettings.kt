package dev.spofify.mobile.playback.cache

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Installation-local automatic-cache policy, not a queue or retained-download budget. */
data class AutoCacheSettings(val maxSongs: Int = 10, val storageGb: Int? = null) {
    /** Estimate only: 50 decimal MB per FLAC song plus 100 decimal MB of headroom. */
    val minimumBytes: Long
        get() = maxSongs.coerceAtLeast(MIN_SONGS).toLong() * ESTIMATED_SONG_BYTES + HEADROOM_BYTES

    /** Decimal GB; invalid persisted values never turn into an unlimited/negative ceiling. */
    val maxBytes: Long
        get() = storageGb?.takeIf { it > 0 }?.let { it.toLong() * BYTES_PER_GB } ?: minimumBytes

    /** Recovery only. Invalid fixed targets become Minimum, never a silently raised fixed target. */
    fun normalized(): AutoCacheSettings {
        val count = maxSongs.coerceAtLeast(MIN_SONGS)
        val fixed = storageGb?.takeIf { it > 0 && it.toLong() * BYTES_PER_GB >= minimumBytes }
        return copy(maxSongs = count, storageGb = fixed)
    }

    /**
     * Call under the sole cache owner's lock with current filesystem and actual owned-byte totals.
     * Free-space safety is independent of the half-capacity ceiling. Already-owned/pinned bytes
     * need not be allocated again: a decrease can be committed before safe eviction is possible.
     * Admission must still check free space, staging/reservations and handle ENOSPC on every write.
     */
    fun validationError(capacity: Long, free: Long, owned: Long): String? {
        if (maxSongs < MIN_SONGS) return "Choose at least 2 songs"
        if (storageGb != null && storageGb <= 0) return "Choose Minimum or a positive whole GB"
        if (maxBytes < minimumBytes) return "Storage must cover the song minimum"
        if (capacity <= 0 || free < 0 || owned < 0) return "Storage information is unavailable"
        if (maxBytes > capacity / 2) return "Storage must be at most 50% of device capacity"
        val growth = (maxBytes - owned).coerceAtLeast(0)
        if (growth > 0 && (free < FREE_SPACE_RESERVE_BYTES || growth > free - FREE_SPACE_RESERVE_BYTES)) {
            return "Not enough free storage"
        }
        return null
    }

    companion object {
        const val MIN_SONGS = 2
        const val BYTES_PER_GB = 1_000_000_000L
        const val ESTIMATED_SONG_BYTES = 50_000_000L
        const val HEADROOM_BYTES = 100_000_000L
        const val FREE_SPACE_RESERVE_BYTES = 16L * 1024 * 1024
    }
}

/**
 * Credential-free configuration under Context.filesDir (already excluded from backup/transfer),
 * outside disposable media/cache directories. Construct once; the existing cache owner serializes
 * reads/writes with admission and publishes a new policy only after write succeeds. No media is
 * inspected or removed here. The owner must validate recovered settings against live capacity/free
 * space before admission; recovery alone cannot make an impossible device budget usable.
 */
class AutoCacheSettingsStore(filesDir: File) {
    private val file = AtomicFile(File(filesDir, FILE_NAME))

    /** Missing/corrupt/unknown formats fall back without rewriting configuration or deleting media. */
    fun read(): AutoCacheSettings = try {
        readRecord().normalized()
    } catch (_: IOException) {
        AutoCacheSettings()
    } catch (_: SecurityException) {
        AutoCacheSettings()
    }

    /** The owner validates live storage separately. Failure leaves its committed policy unchanged. */
    @Throws(IOException::class)
    fun write(settings: AutoCacheSettings) {
        require(settings == settings.normalized()) { "Invalid cache settings" }
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(settings.maxSongs)
            output.writeInt(settings.storageGb ?: MINIMUM_MODE)
            output.flush()
            // Explicit sync propagates failures that AtomicFile.finishWrite may only log.
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
        // AtomicFile can log a failed rename instead of throwing. Do not publish that policy.
        if (readRecord() != settings) throw IOException("Could not save cache settings")
    }

    private fun readRecord(): AutoCacheSettings = DataInputStream(file.openRead()).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Invalid cache settings")
        val count = input.readInt()
        val fixed = input.readInt()
        if (input.read() != -1) throw IOException("Invalid cache settings")
        AutoCacheSettings(count, fixed.takeUnless { it == MINIMUM_MODE })
    }

    internal companion object {
        const val FILE_NAME = "auto-cache-settings-v1"
        private const val MAGIC = 0x41435331 // ACS1
        private const val VERSION = 1
        private const val MINIMUM_MODE = -1
    }
}
