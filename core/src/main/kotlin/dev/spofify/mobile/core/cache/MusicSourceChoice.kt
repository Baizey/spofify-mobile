package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.http.AudioStream
import java.io.Closeable
import java.io.File
import java.io.IOException

private class MusicPositionOutOfBounds : IOException("Music range position out of bounds")

/** The store hold lasts through source release AND the final outstanding range close. */
class PinnedSong internal constructor(private val file: File, private val size: Long,
    private val current: () -> Boolean, private val downloadRetained: () -> Boolean = { false },
    private val downloadOnly: Boolean = false, private val release: () -> Unit) : Closeable {
    private var retired = false
    private var ranges = 0

    fun isDownloaded(): Boolean = downloadRetained()

    @Synchronized fun open(position: Long, readingAllowed: () -> Boolean = { true }): AudioStream {
        fun allowed() = current() && (!downloadOnly || isDownloaded()) && readingAllowed()
        if (retired || !allowed()) throw IOException("Music range unavailable")
        if (position !in 0..size) throw MusicPositionOutOfBounds()
        val input = file.inputStream()
        try { input.channel.position(position) } catch (e: Exception) { input.close(); throw e }
        ranges++
        return AudioStream(input, size - position, permitted = ::allowed) {
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
 * An invalid position against a verified local blob is a local error, not a cache miss. Otherwise
 * IO unavailability before the first local open may fall back; after choosing local, fail closed.
 */
class MusicSourceChoice(private val acquire: (String) -> PinnedSong?, private val release: (PinnedSong) -> Unit = PinnedSong::close) : Closeable {
    private var id: String? = null
    private var pin: PinnedSong? = null
    private var closed = false

    @Synchronized fun openLocal(track: String, position: Long,
        allowTemporary: () -> Boolean = { true }, onChoice: (isDownloaded: () -> Boolean) -> Unit = {}): AudioStream? {
        if (closed || id != null && track != id) throw IOException("Music source retired")
        fun open(held: PinnedSong): AudioStream {
            onChoice(held::isDownloaded)
            return held.open(position) { held.isDownloaded() || allowTemporary() }
        }
        if (id != null) return pin?.let(::open)
        // Cancellation while acquiring a pin is not an authoritative miss.
        val held = acquire(track)
        id = track
        if (held == null) return null
        return try { open(held).also { pin = held } }
        catch (e: MusicPositionOutOfBounds) { pin = held; throw e }
        catch (_: IOException) { release(held); null }
        catch (_: SecurityException) { release(held); null }
    }
    @Synchronized override fun close() {
        closed = true
        pin?.let(release); pin = null
    }
}
