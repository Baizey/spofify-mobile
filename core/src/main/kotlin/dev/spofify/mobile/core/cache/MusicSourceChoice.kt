package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.http.AudioStream
import java.io.Closeable
import java.io.File
import java.io.IOException

/** The store hold lasts through source release AND the final outstanding range close. */
class PinnedSong internal constructor(private val file: File, private val size: Long,
    private val current: () -> Boolean, private val release: () -> Unit) : Closeable {
    private var retired = false
    private var ranges = 0

    @Synchronized fun open(position: Long): AudioStream {
        if (retired || !current() || position !in 0..size) throw IOException("Music range unavailable")
        val input = file.inputStream()
        try { input.channel.position(position) } catch (e: Exception) { input.close(); throw e }
        ranges++
        return AudioStream(input, size - position, null, current) {
            try { input.close() } finally { synchronized(this) { if (--ranges == 0 && retired) release() } }
        }
    }
    @Synchronized override fun close() {
        if (retired) return
        retired = true
        if (ranges == 0) release()
    }
}

/** One decision across every DataSource/range in this prepared media source, not per HTTP open.
 * Before the first successful local open, IO unavailability is a miss. After it, fail closed on
 * local IO errors rather than feeding an extractor bytes from another representation.
 */
class MusicSourceChoice(private val acquire: (String) -> PinnedSong?, private val release: (PinnedSong) -> Unit = PinnedSong::close) : Closeable {
    private var id: String? = null
    private var pin: PinnedSong? = null
    private var closed = false

    @Synchronized fun openLocal(track: String, position: Long): AudioStream? {
        if (closed || id != null && track != id) throw IOException("Music source retired")
        if (id != null) return pin?.open(position)
        id = track
        val held = acquire(track) ?: return null
        return try { held.open(position).also { pin = held } }
        catch (_: IOException) { release(held); null }
        catch (_: SecurityException) { release(held); null }
    }
    @Synchronized override fun close() {
        closed = true
        pin?.let(release); pin = null
    }
}
