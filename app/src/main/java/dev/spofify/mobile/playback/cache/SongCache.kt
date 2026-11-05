package dev.spofify.mobile.playback.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Audio-only, app-private retention. Construct with Context.cacheDir, never an external directory.
 * Merely constructing this object does not read media or establish playback authority. The graph
 * must call [bind] only after validating the exact origin/account session. One instance per app.
 *
 * SimpleCache owns its span/index format. A small credential-free manifest adds whole-object
 * completion and FIFO (not access-LRU). The legacy index is intentional: with <=100 opaque keys
 * and no content metadata its atomic-file overhead has a finite bound, unlike a shared SQLite WAL.
 * All mutations and reservations are serialized; network I/O is never under the storage lock.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SongCache(
    cacheDir: File,
    val limits: Limits = Limits(),
    private val deleteFile: (File) -> Boolean = { it.delete() },
) : Closeable {
    data class Limits(
        val maxTracks: Int = MAX_TRACKS,
        val maxBytes: Long = MAX_BYTES,
        val chunkBytes: Int = 16 * 1024,
        val attemptMillis: Long = 30_000,
    ) {
        init {
            require(maxTracks in 1..MAX_TRACKS && maxBytes in 1..MAX_BYTES)
            require(chunkBytes in 1..64 * 1024 && attemptMillis > 0)
        }
        // Two manifest generations plus both Media3 legacy index generations, with margin.
        internal val metadataReserve: Long get() = 4096L + maxTracks * 512L
    }

    data class Inventory(
        val ownedBytes: Long,
        val entries: Int,
        val complete: Int,
        val pinned: Int,
        val cleanupFailed: Boolean,
        val bypassed: Boolean,
    )

    /** Cancellation interrupts Media3's asynchronous OkHttp open as well as blocking body reads. */
    class Cancellation {
        @Volatile var isCancelled: Boolean = false
            private set
        private var thread: Thread? = null
        private var closeNetwork: (() -> Unit)? = null
        @Synchronized fun cancel() {
            isCancelled = true
            thread?.interrupt()
            closeNetwork?.let { close -> cancellations.execute(close) }
        }
        @Synchronized internal fun onCancel(close: () -> Unit) {
            closeNetwork = close
            if (isCancelled) cancellations.execute(close)
        }
        @Synchronized internal fun attach(): Boolean {
            if (isCancelled) return false
            thread = Thread.currentThread()
            return true
        }
        @Synchronized internal fun detach() { thread = null; closeNetwork = null }
        internal fun check() {
            if (isCancelled || Thread.currentThread().isInterrupted) throw IOException("Audio prefetch cancelled")
        }
    }

    private val root = File(cacheDir, "song-cache-v1")
    private val lock = Any()
    private val states = LinkedHashMap<String, State>()
    private var current: SessionCache? = null
    private var speculation: Cancellation? = null
    private var speculativeSession: SessionCache? = null
    private var closed = false
    private var cleanupFailed = false

    /** Only validated authentication may call this. A suspended handle is never reauthorized. */
    fun bind(origin: String, accountId: String): SessionCache = synchronized(lock) {
        val scope = opaque("scope", origin, accountId)
        current?.takeIf { it.active && it.state.scope == scope && !it.state.retired && !it.state.disabled }?.let { return it }
        current?.let { if (it.state.scope != scope) it.retire() else it.suspend() }
        // Old scopes are retired only after successful validation of a different identity. An
        // offline/failed restore never calls bind and therefore leaves the directory dormant.
        root.listFiles()?.filter { it.isDirectory && it.name != scope && it.name !in states }?.forEach {
            if (!removeTree(it)) cleanupFailed = true
        }
        val state = states[scope]?.takeUnless { it.released } ?: State(scope).also { states[scope] = it }
        SessionCache(state).also { current = it; if (closed) it.suspend() }
    }

    /** Offline restore/configure: fence the live incarnation without touching dormant files. */
    fun fence() = synchronized(lock) { current?.suspend() }

    /** Explicit logout/401/server change also retires namespaces not opened in this process. */
    fun retire() = synchronized(lock) {
        current?.retire()
        states.values.forEach { state ->
            state.retired = true
            state.persistSafely()
            state.finishIfIdle()
        }
        root.listFiles().orEmpty().filter { it.isDirectory && it.name !in states }.forEach { directory ->
            // Mark the existing fixed-size header before cleanup. No new bytes are allocated.
            try {
                val manifest = File(directory, "order")
                if (manifest.isFile) java.io.RandomAccessFile(manifest, "rw").use { it.seek(4); it.writeBoolean(true) }
            } catch (_: Exception) { cleanupFailed = true }
            if (!removeTree(directory)) cleanupFailed = true
        }
    }

    inner class SessionCache internal constructor(@Volatile internal var state: State) {
        @Volatile internal var active = true
            private set

        fun factory(
            upstream: DataSource.Factory,
            trackId: (DataSpec) -> String?,
            authorize: (DataSpec) -> Unit,
            onDemandNetworkChange: (Boolean) -> Unit = {},
        ): DataSource.Factory = DataSource.Factory {
            SongCacheDataSource(this, upstream, trackId, authorize, onDemandNetworkChange)
        }

        /** Explicit successful-play/resume promotion only; reads and fills never call this. */
        fun promote(trackId: String) = synchronized(lock) {
            if (!active || state.retired || state.disabled) return@synchronized
            val key = key(trackId)
            val entry = state.entries.remove(key) ?: return@synchronized
            state.entries[key] = entry
            state.persistSafely()
        }

        /** Fence immediately, retain bytes dormant. Existing reads fail their next guard. */
        fun suspend() = synchronized(lock) {
            active = false
            if (speculativeSession === this) speculation?.cancel()
            state.finishIfIdle()
        }

        /** Fence first; never delete files under a reader/writer or a successor handle. */
        fun retire() = synchronized(lock) {
            active = false
            if (speculativeSession === this) speculation?.cancel()
            if (current?.let { it !== this && it.active && it.state === state } == true) return@synchronized
            state.retired = true
            state.persistSafely()
            state.finishIfIdle()
        }

        /** Blocking worker operation. The service owns the single immutable plan and its order. */
        fun prefetch(
            trackId: String,
            spec: DataSpec,
            upstream: DataSource.Factory,
            authorize: (DataSpec) -> Unit,
            cancellation: Cancellation,
        ): Boolean {
            synchronized(lock) {
                if (!active || state.disabled || state.retired || speculation != null || !cancellation.attach()) return false
                speculation = cancellation
                speculativeSession = this
            }
            val timeout = deadlines.schedule({ cancellation.cancel() }, limits.attemptMillis, TimeUnit.MILLISECONDS)
            val source = SongCacheDataSource(this, upstream, { trackId }, {
                cancellation.check()
                authorize(it)
            }, {}, speculative = true)
            cancellation.onCancel(source::cancelNetwork)
            return try {
                source.open(spec)
                val buffer = ByteArray(limits.chunkBytes)
                while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) cancellation.check()
                synchronized(lock) { state.entries[key(trackId)]?.length?.let { it >= 0 } == true }
            } catch (_: IOException) {
                false
            } finally {
                try { source.close() } catch (_: IOException) { /* speculative failure is silent */ }
                timeout.cancel(false)
                synchronized(lock) {
                    if (speculation === cancellation) { speculation = null; speculativeSession = null }
                }
                cancellation.detach()
                // Do not leak our interrupt into a reused scheduler worker.
                if (cancellation.isCancelled) Thread.interrupted()
            }
        }

        internal fun check() {
            if (!active || closed) throw IOException("Audio cache session is no longer authorized")
        }
        internal fun chunkBytes() = limits.chunkBytes
        internal fun key(trackId: String) = opaque("track", state.scope, trackId)
        internal fun demandNetwork() = synchronized(lock) { speculation?.cancel() }
        private fun storageState() {
            if (!state.retired && !state.released) return
            // Serialize same-scope ABA: never open a second cache while the retired incarnation
            // has pins or failed cleanup. Released states are inert, including late retire().
            if (state.entries.values.any { it.pins != 0 }) return
            state.finishIfIdle()
            if (state.directory.exists()) return
            val successor = State(state.scope)
            states[state.scope] = successor
            state = successor
        }
        internal fun pin(trackId: String): Entry? = synchronized(lock) {
            check()
            storageState()
            state.initialize()
            state.entries[key(trackId)]?.takeIf { !state.disabled && !state.retired && !state.released && it.length >= 0 }?.also { it.pins++ }
        }
        internal fun unpin(entry: Entry) = synchronized(lock) {
            entry.pins--
            if (entry.pins == 0 && entry.length < 0) state.remove(entry)
            state.finishIfIdle()
        }
        internal fun invalidate(entry: Entry) = synchronized(lock) {
            entry.length = -1
            state.persistSafely()
        }
        internal fun file(entry: Entry): File? = synchronized(lock) {
            try {
                val spans = state.cache?.getCachedSpans(entry.key).orEmpty()
                spans.singleOrNull()?.takeIf { it.position == 0L && it.length == entry.length }?.file
                    ?.takeIf { it.isFile && it.length() == entry.length }
            } catch (_: Exception) { null }
        }
        internal fun writer(trackId: String): Writer? = synchronized(lock) {
            check()
            storageState()
            state.initialize()
            if (state.disabled || state.retired || state.released) return null
            val key = key(trackId)
            // Never wait on, read, or splice an in-flight partial. Demand simply uses upstream.
            if (key in state.entries) return null
            if (!makeRoom(0, newEntry = true)) return null
            val entry = Entry(key)
            entry.pins = 1
            state.entries[key] = entry
            if (!state.persistSafely()) { entry.pins = 0; state.remove(entry); return null }
            try {
                Writer(state, entry, FileOutputStream(state.stage(entry))) { active && !closed }
            } catch (_: Exception) {
                entry.pins = 0
                state.disabled = true
                state.remove(entry)
                null
            }
        }
    }

    internal class Entry(val key: String, var length: Long = -1, var digest: ByteArray = ByteArray(32), var artifact: String = "") {
        var pins = 0
    }

    internal inner class Writer(
        private val state: State,
        private val entry: Entry,
        private val output: FileOutputStream,
        private val authorized: () -> Boolean,
    ) {
        private val hash = MessageDigest.getInstance("SHA-256")
        private var written = 0L
        private var stopped = false
        fun append(bytes: ByteArray, offset: Int, length: Int): Boolean = synchronized(lock) {
            if (stopped) return false
            if (!authorized() || state.disabled || state.retired || !makeRoom(length.toLong(), false)) {
                stop(false); return false
            }
            try {
                output.write(bytes, offset, length)
                hash.update(bytes, offset, length)
                written += length
                true
            } catch (_: Exception) {
                state.disabled = true
                stop(false)
                false
            }
        }
        /** Completion is supplied only after the response coverage/EOF contract is validated. */
        fun finish() = synchronized(lock) { stop(true) }
        fun abort() = synchronized(lock) { stop(false) }
        private fun stop(complete: Boolean) {
            if (stopped) return
            stopped = true
            try {
                output.close()
                if (complete && authorized() && written > 0 && !state.retired && !state.disabled && makeRoom(0, false)) {
                    val cache = state.cache ?: throw IOException("Cache unavailable")
                    val hole = cache.startReadWriteNonBlocking(entry.key, 0, written)
                        ?: throw IOException("Cache busy")
                    try {
                        if (hole.isCached) throw IOException("Unexpected cache span")
                        val target = cache.startFile(entry.key, 0, written)
                        entry.artifact = target.relativeTo(state.directory).invariantSeparatorsPath
                        if (!state.persistSafely()) throw IOException("Cache commit unavailable")
                        if (!state.stage(entry).renameTo(target)) throw IOException("Cache commit failed")
                        cache.commitFile(target, written)
                        entry.length = written
                        entry.digest = hash.digest()
                        if (!state.persistSafely()) entry.length = -1
                    } finally { if (!hole.isCached) cache.releaseHoleSpan(hole) }
                }
            } catch (_: Exception) {
                entry.length = -1
                state.disabled = true
            } finally {
                entry.pins--
                if (entry.length < 0) state.remove(entry)
                state.finishIfIdle()
            }
        }
    }

    internal inner class State(val scope: String) {
        val directory = File(root, scope)
        val entries = LinkedHashMap<String, Entry>()
        var cache: SimpleCache? = null
        var initialized = false
        var inventoryKnown = false
        var disabled = false
        var retired = false
        var released = false
        private val manifest = File(directory, "order")
        private val pending = File(directory, "order.new")
        fun stage(entry: Entry) = File(directory, "${entry.key}.part")

        fun initialize() {
            if (initialized || released || retired) return
            initialized = true
            try {
                if (!directory.exists() && !directory.mkdirs()) throw IOException("Cache directory unavailable")
                if (manifest.exists()) readManifest()
                else if (directory.listFiles().orEmpty().isNotEmpty()) throw IOException("Cache manifest missing")
                inventoryKnown = true
                if (retired) { finishIfIdle(); return }
                if (!makeRoom(0, false)) throw IOException("Cache metadata budget unavailable")
                cache = SimpleCache(File(directory, "media"), NoOpCacheEvictor(), null, null, false, true)
                cache!!.checkInitialization()
                // Incomplete entries survived a crash. They are never hits and have no live pins.
                entries.values.toList().filter { it.length < 0 }.forEach { remove(it) }
                val known = entries.keys
                cache!!.keys.filter { it !in known }.forEach { key ->
                    // An interrupted commit may leave a span before manifest completion. Adopt a
                    // tombstone before trying deletion so failed cleanup still consumes a slot.
                    entries[key] = Entry(key)
                    remove(entries.getValue(key))
                }
                if (entries.size > limits.maxTracks || ownedBytes() > limits.maxBytes) throw IOException("Cache inventory exceeds budget")
                persist()
            } catch (_: Exception) {
                disabled = true
                // Do not repeatedly reopen a corrupt/failed path. Bytes remain measured by the
                // root inventory, and the next validated incarnation can retry safe cleanup.
                cleanupFailed = directory.exists()
            }
        }

        private fun readManifest() {
            if (manifest.length() > limits.metadataReserve) throw IOException("Invalid cache index")
            DataInputStream(manifest.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Invalid cache index")
                retired = input.readBoolean()
                val count = input.readInt()
                if (count !in 0..limits.maxTracks) throw IOException("Invalid cache index")
                repeat(count) {
                    val key = input.readUTF()
                    if (!key.matches(Regex("[0-9a-f]{64}")) || key in entries) throw IOException("Invalid cache key")
                    val length = input.readLong()
                    if (length < -1 || length > limits.maxBytes) throw IOException("Invalid cache length")
                    val digest = ByteArray(32).also { input.readFully(it) }
                    val artifact = input.readUTF()
                    if (artifact.isNotEmpty() && !artifact.matches(Regex("media/[0-9]+/[0-9]+\\.[0-9]+\\.[0-9]+\\.v3\\.exo"))) throw IOException("Invalid cache artifact")
                    entries[key] = Entry(key, length, digest, artifact)
                }
                if (input.read() != -1) throw IOException("Invalid cache index")
            }
        }

        fun persistSafely(): Boolean = try {
            if (released) true else if (!initialized) {
                if (retired && manifest.isFile) java.io.RandomAccessFile(manifest, "rw").use { it.seek(4); it.writeBoolean(true) }
                true
            } else { persist(); true }
        } catch (_: Exception) { disabled = true; cleanupFailed = true; false }

        private fun persist() {
            // This reserve bounds the simultaneous old/new manifest and Media3 index copies.
            val manifestBytes = 9L + entries.values.sumOf { 110L + it.artifact.length }
            if (ownedBytes() > limits.maxBytes - manifestBytes) throw IOException("Cache metadata budget unavailable")
            DataOutputStream(FileOutputStream(pending)).use { out ->
                out.writeInt(MAGIC)
                out.writeBoolean(retired)
                out.writeInt(entries.size)
                entries.values.forEach { entry ->
                    out.writeUTF(entry.key)
                    out.writeLong(entry.length)
                    out.write(entry.digest)
                    out.writeUTF(entry.artifact)
                }
            }
            if (!pending.renameTo(manifest)) throw IOException("Cache index commit failed")
        }

        /** Delete before dropping the index entry: Media3 itself ignores File.delete failures. */
        fun remove(entry: Entry): Boolean {
            if (entry.pins != 0) return false
            entry.length = -1
            if (!persistSafely()) return false
            val files = try {
                cache?.getCachedSpans(entry.key).orEmpty().mapNotNull { it.file } + stage(entry) +
                    listOfNotNull(entry.artifact.takeIf { it.isNotEmpty() }?.let { File(directory, it) })
            }
                catch (_: Exception) { cleanupFailed = true; return false }
            if (files.any { it.exists() && !deleteFile(it) }) { cleanupFailed = true; return false }
            try { cache?.removeResource(entry.key) } catch (_: Exception) { cleanupFailed = true; return false }
            entries.remove(entry.key)
            persistSafely()
            return true
        }

        fun finishIfIdle() {
            if (released || entries.values.any { it.pins != 0 }) return
            val liveHandle = current?.let { it.active && it.state === this } == true
            if (!retired && liveHandle) return
            if (!initialized) {
                if (retired) { released = true; if (!removeTree(directory)) cleanupFailed = true }
                return
            }
            try { cache?.release() } catch (_: Exception) { cleanupFailed = true }
            cache = null
            released = true
            if (retired && !removeTree(directory)) cleanupFailed = true
        }
    }

    private fun makeRoom(addBytes: Long, newEntry: Boolean): Boolean {
        fun fits(): Boolean = ownedBytes() <= limits.maxBytes - limits.metadataReserve - addBytes &&
            (!newEntry || entryCount() < limits.maxTracks)
        if (fits()) return true
        // Insertion order is FIFO; skipping a pin never reorders another track.
        for (state in states.values) {
            for (entry in state.entries.values.toList()) {
                if (entry.pins == 0 && state.cache != null && state.remove(entry) && fits()) return true
            }
        }
        return fits()
    }

    private fun entryCount(): Int {
        // Unopened/failed-retirement directories cannot be treated as reclaimed capacity.
        val directories = root.listFiles()
        if (root.exists() && directories == null) return limits.maxTracks
        val unknown = directories.orEmpty().any { it.isDirectory && states[it.name]?.inventoryKnown != true }
        return if (unknown) limits.maxTracks else states.values.filter { !it.released || it.directory.exists() }.sumOf { it.entries.size }
    }

    fun inventory(): Inventory = synchronized(lock) {
        Inventory(ownedBytes(), entryCount(), states.values.sumOf { it.entries.values.count { e -> e.length >= 0 } },
            states.values.sumOf { it.entries.values.count { e -> e.pins > 0 } }, cleanupFailed,
            states.values.any { it.disabled })
    }

    private fun ownedBytes(): Long {
        var visited = 0
        fun size(file: File, depth: Int): Long {
            if (!file.exists()) return 0
            if (++visited > 4096 || depth > 8) throw IOException("Unrecognized cache inventory")
            if (file.isFile) return file.length()
            if (!file.isDirectory) throw IOException("Unreadable cache inventory")
            val children = file.listFiles() ?: throw IOException("Unreadable cache inventory")
            return children.fold(0L) { total, child -> Math.addExact(total, size(child, depth + 1)) }
        }
        return try { size(root, 0) } catch (_: Exception) { Long.MAX_VALUE / 2 }
    }

    private fun removeTree(file: File): Boolean {
        return try {
            if (!file.exists()) true else if (file.isDirectory) {
                val children = file.listFiles() ?: return false
                val removed = children.map { removeTree(it) }.all { it }
                removed && deleteFile(file)
            } else deleteFile(file)
        } catch (_: Exception) { false }
    }

    /** App/test teardown, not logout: preserve validated content, fence all old handles. */
    override fun close() = synchronized(lock) {
        closed = true
        current?.suspend()
        states.values.forEach { it.finishIfIdle() }
    }

    companion object {
        const val MAX_TRACKS = 100
        const val MAX_BYTES = 1_000_000_000L
        private const val MAGIC = 0x53434731

        fun authorizedFactory(
            cache: SessionCache?,
            upstream: DataSource.Factory,
            trackId: (DataSpec) -> String?,
            authorize: (DataSpec) -> Unit,
            onDemandNetworkChange: (Boolean) -> Unit = {},
        ): DataSource.Factory = DataSource.Factory {
            SongCacheDataSource(cache, upstream, trackId, authorize, onDemandNetworkChange)
        }

        private val cancellations = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "song-cache-cancel").apply { isDaemon = true }
        }
        private val deadlines = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "song-cache-deadline").apply { isDaemon = true }
        }
        private fun opaque(vararg values: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
                hash.update(bytes)
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
