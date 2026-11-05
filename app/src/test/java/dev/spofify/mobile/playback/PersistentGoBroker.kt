package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual pinned broker, never a device-list fake. One instance outlives every graph in a test.
 * No Go checkout writes, inherited provider config, media import, external listener or real auth.
 * Linux integration prerequisite is explicit. Optional runtime parent is supervisor-owned;
 * absent that parent, only this fixture's unique tmpfs child is allocated/removed.
 */
internal class PersistentGoBroker : AutoCloseable {
    companion object {
        const val SOURCE = "b6eb54ffdc91e07e2ce7824b8be5f90afa8b05eb"
        const val SHA256 = "425e4bb46efcf1c25e195d92d4d1845ed2389cd35e6ebf9ad8981cd80c2c70f4"
        const val PASSWORD = "Synthetic-Registration-Only-2026!"
        private const val BUDGET = 128L * 1024 * 1024
    }
    private val binary = File(requireNotNull(System.getenv("SPOFIFY_TEST_GO_BROKER_BINARY")) {
        "RealBrokerIntegration requires SPOFIFY_TEST_GO_BROKER_BINARY: supply the private pinned executable (no automatic download or fallback)"
    }).also {
        check(it.isAbsolute && it.isFile && it.canExecute()) { "Broker binary must be an explicit executable absolute path" }
        check(MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) } == SHA256) {
            "Broker binary does not match the pinned integration artifact"
        }
    }
    private val runtime = File(System.getenv("SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT") ?: "/dev/shm").also {
        check(System.getProperty("os.name") == "Linux") { "Pinned Go integration artifact requires Linux" }
        check(it.isDirectory && it.canonicalFile == it.absoluteFile && it.canWrite()) { "Broker runtime parent must be an explicit writable directory" }
        check(it.usableSpace >= 1024L * 1024 * 1024) { "Insufficient broker runtime headroom (quota remains unknown)" }
    }
    private val token = UUID.randomUUID().toString()
    val root: File = Files.createTempDirectory(runtime.toPath(), "registration-broker-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile().also {
        // This is a synthetic scratch location, never an authenticated URL/catalog path.
        println("REGISTRATION_BROKER_RUNTIME=${it.absolutePath}; budget=$BUDGET; quota=unknown")
        File(it, ".owner").writeText(token)
    }
    val server: ServerAddress
    private val process: Process
    private val log = StringBuilder()
    private val reader: Thread
    private val budgetFailure = AtomicReference<Throwable?>()
    private val monitor: Thread
    init {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        server = ServerAddress.parse("http://127.0.0.1:$port")
        val builder = ProcessBuilder(binary.absolutePath).directory(root).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("PATH", "/usr/bin:/bin"); put("LANG", "C.UTF-8")
            put("HOME", File(root, "home").apply { check(mkdir()) }.absolutePath)
            put("TMPDIR", root.absolutePath)
            put("HTTP_PROXY", "http://127.0.0.1:1"); put("HTTPS_PROXY", "http://127.0.0.1:1")
            put("NO_PROXY", "127.0.0.1,localhost")
            put("SPOFIFY_LISTEN_ADDR", "127.0.0.1:$port")
            put("SPOFIFY_DATA_DIR", File(root, "data").absolutePath)
            put("SPOFIFY_LIBRARY_DIR", File(root, "library").absolutePath)
            put("SPOFIFY_ADMIN_PASSWORD", PASSWORD)
        }
        process = try { builder.start() } catch (failure: Throwable) {
            check(root.deleteRecursively()) { "Failed launch retained owned runtime" }
            throw failure
        }
        reader = Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(log) { log.append(line).append('\n'); if (log.length > 16384) log.delete(0, log.length - 16384) }
            } }
        }.apply { isDaemon = true; start() }
        monitor = Thread {
            while (process.isAlive) {
                try {
                    check(bytes(root.toPath()) <= BUDGET) { "Owned broker runtime exceeded 128MiB" }
                    Thread.sleep(100)
                } catch (failure: Throwable) {
                    budgetFailure.compareAndSet(null, failure)
                    process.destroy()
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
    }
    private fun bytes(path: Path): Long = Files.walk(path).use { entries -> entries.mapToLong { child ->
        try { Files.size(child) }
        catch (missing: java.nio.file.NoSuchFileException) {
            // Only an enumerated live child of this owned root may disappear during SQLite work.
            if (child == path || !process.isAlive || !child.startsWith(path)) throw missing
            0L
        }
    }.sum() }
    suspend fun login(client: SpofifyClient) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            budgetFailure.get()?.let { throw IllegalStateException("Broker runtime accounting failed", it) }
            check(process.isAlive) {
                reader.join(1000)
                "Pinned synthetic broker exited before login: " + synchronized(log) {
                    log.toString().replace(root.absolutePath, "<owned-runtime>").replace(PASSWORD, "<redacted>")
                }
            }
            try { client.login("admin", PASSWORD); return }
            catch (failure: java.io.IOException) {
                if (System.nanoTime() >= deadline) throw failure
                Thread.sleep(50)
            }
        }
    }
    override fun close() {
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(3, TimeUnit.SECONDS)) { "Owned broker did not stop; retain runtime" }
        }
        reader.join(3000); monitor.join(3000)
        check(!reader.isAlive && !monitor.isAlive) { "Owned broker helper threads did not stop; retain runtime" }
        check(root.canonicalFile == root.absoluteFile && root.parentFile == runtime && File(root, ".owner").readText() == token)
        check(root.deleteRecursively()) { "Unable to remove stopped owned broker runtime" }
        budgetFailure.get()?.let { throw IllegalStateException("Broker runtime accounting failed", it) }
    }
}
