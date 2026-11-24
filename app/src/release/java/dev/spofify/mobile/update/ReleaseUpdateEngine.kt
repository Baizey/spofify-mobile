package dev.spofify.mobile.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Unavailable : UpdateStatus
    data object Checking : UpdateStatus
    data object Downloading : UpdateStatus
    data class Ready(val apk: VerifiedApk) : UpdateStatus
    data object UpToDate : UpdateStatus
    data object Failure : UpdateStatus
}

/** Identity is tied to one engine generation; do not persist or reconstruct this handle. */
class VerifiedApk internal constructor(val file: File, val versionCode: Long)

/**
 * One instance per app process. Pass the dedicated private child `File(context.cacheDir,
 * "mobile-updates")`, not the shared cache root. The verifier is called off the main thread
 * and must require the exact application package, version code and expected signing certificate(s).
 */
class ReleaseUpdateEngine internal constructor(
    private val scope: CoroutineScope,
    cacheDir: File,
    private val installedVersionCode: Long,
    private val archiveCheck: (File, Long) -> Boolean,
    private val transport: UpdateTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    constructor(
        scope: CoroutineScope,
        cacheDir: File,
        installedVersionCode: Long,
        archiveCheck: (File, Long) -> Boolean,
    ) : this(scope, cacheDir, installedVersionCode, archiveCheck, AnonymousUpdateTransport())

    private class Flight {
        lateinit var job: Job
        var call: UpdateCall? = null
        var part: Path? = null
    }

    private class ReadyFile(val apk: VerifiedApk, val release: GithubMobileRelease)

    private val lock = Any()
    private val cache = cacheDir.toPath()
    private val mutableStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = mutableStatus.asStateFlow()
    private var online: Boolean? = null
    private var foreground = false
    private var foregroundSession = 0L
    private var flight: Flight? = null
    private var ready: ReadyFile? = null
    private var lastFinished: Long? = null

    // Sweep files abandoned by process death, even if this process never goes online. Work always
    // waits for the sweep. Do not sweep arbitrary files or anything from another cache directory.
    private val initialSweep = scope.async(io) {
        try {
            if (Files.notExists(cache, NOFOLLOW_LINKS)) true else {
                if (!Files.isDirectory(cache, NOFOLLOW_LINKS)) throw RejectedUpdate()
                Files.newDirectoryStream(cache) { path ->
                    val name = path.fileName.toString()
                    name.startsWith(FILE_PREFIX) && name.endsWith(".apk")
                }.use { paths -> paths.forEach { Files.deleteIfExists(it) } }
                true
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            false
        }
    }

    /** Only explicit true permits work or admission of an APK. Fencing and call.cancel precede return. */
    fun setOnline(value: Boolean?) = synchronized(lock) {
        if (value == online) return@synchronized
        online = value
        if (value != true) {
            lastFinished = null
            discardReadyLocked()
            cancelFlightLocked()
            mutableStatus.value = UpdateStatus.Unavailable
        } else if (foreground && scope.isActive) {
            maybeStartLocked()
        } else {
            mutableStatus.value = UpdateStatus.Idle
        }
    }

    /** Call on foreground transitions, not on visiting Settings. Backgrounding preserves Ready. */
    fun setForeground(value: Boolean) = synchronized(lock) {
        if (value == foreground) return@synchronized
        foreground = value
        if (!value) foregroundSession++
        if (!value && flight != null) {
            cancelFlightLocked()
            mutableStatus.value = if (online == true) UpdateStatus.Idle else UpdateStatus.Unavailable
        } else if (value) {
            if (online != true) mutableStatus.value = UpdateStatus.Unavailable else maybeStartLocked()
        }
    }

    /** Recheck size, digest and archive identity off-main on explicit tap. A stale handle returns null. */
    suspend fun revalidate(apk: VerifiedApk): VerifiedApk? {
        val (snapshot, session) = synchronized(lock) {
            (currentLocked(apk) ?: return null) to foregroundSession
        }
        fun stillCurrent() = synchronized(lock) { foregroundSession == session && currentLocked(apk) === snapshot }
        return withContext(io) {
            val caller = coroutineContext
            val valid = try {
                val path = apk.file.toPath()
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != snapshot.release.size) {
                    throw RejectedUpdate()
                }
                Files.newInputStream(path).use { input ->
                    checkApk(snapshot.release, input, guard = {
                        caller.ensureActive()
                        if (!stillCurrent()) throw RejectedUpdate()
                    })
                }
                stillCurrent() && archiveCheck(apk.file, apk.versionCode)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                false
            }
            caller.ensureActive()
            synchronized(lock) {
                if (foregroundSession != session || currentLocked(apk) !== snapshot) return@synchronized null
                if (valid) apk else {
                    discardReadyLocked()
                    lastFinished = nowMillis()
                    mutableStatus.value = UpdateStatus.Failure
                    null
                }
            }
        }
    }

    fun isCurrent(apk: VerifiedApk): Boolean = synchronized(lock) { currentLocked(apk) != null }

    /** After revalidate, run only the fast, synchronous installer launch/commit here (no disk I/O). */
    fun <T> withCurrentApk(apk: VerifiedApk, action: () -> T): T? = synchronized(lock) {
        if (currentLocked(apk) == null) null else action()
    }

    private fun currentLocked(apk: VerifiedApk): ReadyFile? =
        ready?.takeIf { foreground && online == true && scope.isActive && it.apk === apk }

    private fun discardReadyLocked() {
        val file = ready ?: return
        ready = null
        deleteInBackground(file.apk.file.toPath())
    }

    private fun cancelFlightLocked() {
        val stale = flight ?: return
        flight = null
        stale.call?.cancel()
        stale.job.cancel()
        stale.part?.let(::deleteInBackground)
    }

    private fun deleteInBackground(path: Path) {
        scope.launch(io) { removeFile(path) }
    }

    private fun maybeStartLocked() {
        if (!foreground || online != true || flight != null || !scope.isActive) return
        // Keep a verified APK for the user's explicit install, including while the installer is
        // opening. No further polling until process restart, offline/unknown, or failed revalidation.
        if (ready != null) return
        if (lastFinished?.let { nowMillis() - it < REFRESH_INTERVAL_MS } == true) return
        val next = Flight()
        next.job = scope.launch(io, start = CoroutineStart.LAZY) { runFlight(next) }
        flight = next
        mutableStatus.value = UpdateStatus.Checking
        next.job.start()
    }

    private suspend fun runFlight(current: Flight) = coroutineScope {
        // Offline cancellation calls cancel inline. This also cancels the network call if the
        // owner cancels its process scope while a worker is blocked in synchronous HTTP I/O.
        val cancelOnScopeEnd = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                synchronized(lock) { current.call?.cancel() }
            }
        }
        try {
            if (!initialSweep.await()) throw RejectedUpdate()
            for (attempt in 0..RETRY_DELAYS_MS.size) {
                try {
                    synchronized(lock) {
                        requireFlightLocked(current)
                        mutableStatus.value = UpdateStatus.Checking
                    }
                    checkAndDownload(current)
                    break
                } catch (transient: TransientUpdateFailure) {
                    val backoff = RETRY_DELAYS_MS.getOrNull(attempt) ?: throw transient
                    delay(backoff)
                }
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            synchronized(lock) {
                if (flight === current && current.job.isActive) finishLocked(UpdateStatus.Failure)
            }
        } finally {
            cancelOnScopeEnd.cancel()
        }
    }

    private fun checkAndDownload(current: Flight) {
        val release = fetchRelease(current)
        if (release.versionCode <= installedVersionCode) {
            synchronized(lock) {
                requireFlightLocked(current)
                finishLocked(UpdateStatus.UpToDate)
            }
            return
        }
        synchronized(lock) {
            requireFlightLocked(current)
            mutableStatus.value = UpdateStatus.Downloading
        }
        Files.createDirectories(cache)
        if (!Files.isDirectory(cache, NOFOLLOW_LINKS)) throw RejectedUpdate()
        // Android's archive parser may require the .apk extension, even for a staging file.
        val part = Files.createTempFile(cache, FILE_PREFIX, PART_SUFFIX)
        try {
            synchronized(lock) {
                requireFlightLocked(current)
                current.part = part
            }
            download(current, release, part)
            requireFlight(current)
            if (!archiveCheck(part.toFile(), release.versionCode)) throw RejectedUpdate()
            synchronized(lock) {
                requireFlightLocked(current)
                val target = part.resolveSibling(part.fileName.toString().removeSuffix(PART_SUFFIX) + ".apk")
                Files.move(part, target, ATOMIC_MOVE)
                val apk = VerifiedApk(target.toFile(), release.versionCode)
                ready = ReadyFile(apk, release)
                finishLocked(UpdateStatus.Ready(apk))
            }
        } finally {
            removeFile(part)
            synchronized(lock) { current.part = null }
        }
    }

    private fun finishLocked(result: UpdateStatus) {
        flight = null
        lastFinished = nowMillis()
        mutableStatus.value = result
    }

    private fun requireFlight(current: Flight) = synchronized(lock) { requireFlightLocked(current) }

    private fun requireFlightLocked(current: Flight) {
        if (flight !== current || !current.job.isActive) throw CancellationException()
    }

    private fun fetchRelease(current: Flight): GithubMobileRelease {
        val deadline = Deadline(METADATA_TIMEOUT_MS)
        return exchange(current, LATEST_RELEASE, UpdateResource.METADATA, deadline) { response ->
            val length = response.requireFullBody()
            if (length != null && length > MAX_METADATA_BYTES) throw RejectedUpdate()
            val output = ByteArrayOutputStream()
            val body = response.body
            val actual = stream(MAX_METADATA_BYTES.toLong(),
                guard = { requireFlight(current); deadline.remaining() },
                read = { buffer, count -> networkIo { body.read(buffer, 0, count) } },
                consume = { buffer, count -> output.write(buffer, 0, count) },
            )
            if (length != null && length != actual) throw RejectedUpdate()
            parseMobileRelease(output.toByteArray())
        }
    }

    private fun download(current: Flight, release: GithubMobileRelease, part: Path) {
        val deadline = Deadline(DOWNLOAD_TIMEOUT_MS)
        var url = release.download
        for (hop in 0..MAX_REDIRECTS) {
            url = exchange(current, url, UpdateResource.APK, deadline) { response ->
                if (response.code in REDIRECT_CODES) {
                    if (hop == MAX_REDIRECTS) throw RejectedUpdate()
                    assetRedirect(response)
                } else {
                    val length = response.requireFullBody()
                    if (length != null && length != release.size) throw RejectedUpdate()
                    Files.newOutputStream(part).use { output ->
                        checkApk(release, response.body, network = true,
                            guard = { requireFlight(current); deadline.remaining() },
                            consume = { buffer, count -> output.write(buffer, 0, count) },
                        )
                    }
                    null
                }
            } ?: return
        }
    }

    private fun <T> exchange(
        current: Flight,
        url: URI,
        resource: UpdateResource,
        deadline: Deadline,
        consume: (UpdateResponse) -> T,
    ): T {
        val call = transport.newCall(url, resource, deadline.remaining())
        synchronized(lock) {
            requireFlightLocked(current)
            current.call = call
        }
        try {
            return networkIo { call.execute() }.use { response ->
                requireFlight(current)
                deadline.remaining()
                consume(response)
            }
        } finally {
            synchronized(lock) { current.call = null }
        }
    }

    private inner class Deadline(private val budget: Long) {
        private val start = nowMillis()
        fun remaining(): Long = (budget - (nowMillis() - start)).takeIf { it > 0 }
            ?: throw TransientUpdateFailure()
    }

    private companion object {
        const val FILE_PREFIX = "spofify-update-"
        const val PART_SUFFIX = ".part.apk"
        const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
        const val METADATA_TIMEOUT_MS = 15_000L
        const val DOWNLOAD_TIMEOUT_MS = 120_000L
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val RETRY_DELAYS_MS = listOf(2000L, 8000L)
    }
}

private fun checkApk(
    release: GithubMobileRelease,
    input: InputStream,
    network: Boolean = false,
    guard: () -> Unit,
    consume: (ByteArray, Int) -> Unit = { _, _ -> },
) {
    val digest = MessageDigest.getInstance("SHA-256")
    val actual = stream(release.size, guard,
        read = { buffer, count ->
            if (network) networkIo { input.read(buffer, 0, count) } else input.read(buffer, 0, count)
        },
        consume = { buffer, count ->
            digest.update(buffer, 0, count)
            consume(buffer, count)
        },
    )
    if (actual != release.size || !MessageDigest.isEqual(digest.digest(), release.sha256)) {
        throw RejectedUpdate()
    }
}

private inline fun stream(
    max: Long,
    guard: () -> Unit,
    read: (ByteArray, Int) -> Int,
    consume: (ByteArray, Int) -> Unit,
): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        guard()
        val count = read(buffer, minOf(buffer.size.toLong(), max - total + 1).toInt())
        guard()
        if (count < 0) return total
        if (count == 0 || count > max - total) throw RejectedUpdate()
        consume(buffer, count)
        total += count
    }
}

private fun removeFile(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        // The file is never admitted again; the next process startup retries cleanup.
    } catch (_: SecurityException) {
        // Same policy for a temporarily inaccessible cache.
    }
}
