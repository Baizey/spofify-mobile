package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Dedicated retained supervisor; no sibling-checkout/default-binary/old-pin fallback. */
internal class LibraryPlaybackBroker : AutoCloseable {
    companion object {
        const val SHA256 = "492a8dd2d834a3e48d10246b0459d12e9bba18322b6c3aa99693760e123a0de4"
        const val BINARY_VCS = "92f3e12a1a1f7b02fdd6586786646cf8c14dbca0"
        const val SOURCE = "c23a9db747b20b492697f141369ad9388e729a53"
        const val TREE = "842a060f65396e3fd1ad5d5c314874ecfaa10ee7"
    }
    private val binary = File(requireNotNull(System.getenv("SPOFIFY_TEST_GO_BROKER_BINARY")) {
        "Selected current library integration requires an explicit current SHA256-pinned broker; obsolete hosted b6 asset is not compatible"
    }).also { file ->
        check(file.isAbsolute && file.isFile && file.canExecute()) { "Explicit executable broker path required" }
        val hash = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        check(hash == SHA256) { "Current library broker pin mismatch; no legacy fallback or automatic download" }
    }
    private val parent = File(requireNotNull(System.getenv("SPOFIFY_TEST_LIBRARY_PLAYBACK_RUNTIME_ROOT")) {
        "Selected library integration requires SPOFIFY_TEST_LIBRARY_PLAYBACK_RUNTIME_ROOT (private supervisor-owned SSD parent)"
    }).also {
        check(it.isAbsolute && it.isDirectory && it.canonicalFile == it.absoluteFile)
        check(it.usableSpace >= (2L shl 30) + (128L shl 20)) { "Insufficient runtime headroom; quota unknown" }
    }
    private val script = sequenceOf(File("tools/testing/library-playback-fixture/broker_fixture.py"),
        File("../tools/testing/library-playback-fixture/broker_fixture.py")).firstOrNull { it.isFile }?.canonicalFile
        ?: error("Current library fixture script is missing from this worktree")
    private val control = Files.createTempDirectory(parent.toPath(), "library-controller-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile()
    private val process: Process
    private val stderr = StringBuilder()
    private val stderrReader: Thread
    private val handshake: JsonObject
    val server: ServerAddress
    val tracks: List<Pair<String, String>>
    val albumId: String
    private val accounts: List<Pair<String, String>>

    init {
        val builder = ProcessBuilder("/usr/bin/python3", "-B", script.absolutePath, "--seconds", "120")
            .directory(control)
        builder.environment().apply {
            clear(); put("PATH", "/usr/bin:/bin"); put("LANG", "C.UTF-8")
            for (key in listOf("HOME", "TMPDIR", "TMP", "TEMP", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME")) {
                val directory = File(control, key).apply { check(mkdir()) }
                put(key, directory.absolutePath)
            }
            put("SPOFIFY_TEST_GO_BROKER_BINARY", binary.absolutePath)
            put("SPOFIFY_TEST_LIBRARY_PLAYBACK_RUNTIME_ROOT", parent.absolutePath)
        }
        process = try { builder.start() } catch (failure: Throwable) { control.deleteRecursively(); throw failure }
        stderrReader = Thread({
            process.errorStream.bufferedReader().useLines { lines -> lines.forEach { line -> synchronized(stderr) {
                stderr.append(line).append('\n')
                if (stderr.length > 65536) stderr.delete(0, stderr.length - 65536)
            } } }
        }, "library-fixture-stderr").apply { start() }
        val reader = Executors.newSingleThreadExecutor()
        try {
            // Bound bytes as well as time: no readLine() on an untrusted unlimited stream.
            val raw = reader.submit<String> {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val value = process.inputStream.read()
                    check(value >= 0) { "Current library fixture exited before handshake" }
                    if (value == 10) break
                    check(bytes.size() < 16384) { "Fixture handshake exceeded 16KiB" }
                    bytes.write(value)
                }
                bytes.toString(Charsets.UTF_8.name())
            }.get(45, TimeUnit.SECONDS)
            handshake = Json.parseToJsonElement(raw).jsonObject
            check(handshake.getValue("version").jsonPrimitive.int == 2)
            check(handshake.text("brokerSha256") == SHA256 && handshake.text("binaryVcsRevision") == BINARY_VCS)
            check(!handshake.getValue("binaryVcsModified").jsonPrimitive.boolean)
            check(handshake.text("sourceRevision") == SOURCE && handshake.text("sourceTree") == TREE)
            server = ServerAddress.parse(handshake.text("baseUrl"))
            check(server.origin.startsWith("http://127.0.0.1:"))
            tracks = handshake.getValue("tracks").jsonArray.map { it.jsonObject.let { item -> item.text("id") to item.text("name") } }
            albumId = handshake.text("albumId")
            accounts = handshake.getValue("accounts").jsonArray.map { it.jsonObject.let { item -> item.text("username") to item.text("password") } }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        } finally {
            reader.shutdownNow()
            // On timeout close() awaits the producer first, unblocking its pipe reader.
            check(reader.awaitTermination(3, TimeUnit.SECONDS)) { "Fixture handshake reader remains" }
        }
    }

    suspend fun client(account: Int = 0): SpofifyClient {
        val store = object : SessionStore {
            private var value: StoredSession? = null
            override fun read() = value
            override fun write(value: StoredSession?) { this.value = value }
        }
        return SpofifyClient(server, store).also { authenticate(it, account) }
    }

    /** Synthetic fixture login only. Lets an actual AppGraph own the client/store lifetime. */
    suspend fun authenticate(client: SpofifyClient, account: Int = 0): Session {
        check(client.server.origin == server.origin)
        return client.login(accounts[account].first, accounts[account].second)
    }

    override fun close() {
        // Never kill the subreaper merely to make a test finish; retain state on missing proof.
        if (process.isAlive) {
            try { process.outputStream.write("stop\n".toByteArray()); process.outputStream.flush() }
            catch (_: java.io.IOException) { /* exited concurrently; still await original launcher */ }
        }
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Library fixture shutdown unknown: retain private runtime and supervisor" }
        stderrReader.join(3000)
        check(!stderrReader.isAlive) { "Fixture stderr reader remains; retain runtime" }
        val receipt = synchronized(stderr) { stderr.toString() }
        // Preserve only structured, redacted supervisor metadata BEFORE checking success.
        // A nonzero launcher must not make the original-child/ECHILD evidence disappear.
        val records = receipt.lineSequence().mapNotNull { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()?.takeIf { record ->
                record.keys.any { it in setOf("fixtureAllocated", "fixtureStopped", "fixtureCleanup", "fixtureFailure") }
            }
        }.toList()
        val diagnostics = File(control, "supervisor-diagnostics.json")
        diagnostics.writeText(JsonArray(records).toString())
        check(process.exitValue() == 0 && receipt.lineSequence().any { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()?.let {
                it["fixtureCleanup"]?.jsonPrimitive?.content == "complete" && it["descendants"]?.jsonPrimitive?.content == "ECHILD"
            } == true
        }) { "Current library fixture failed or has no ECHILD cleanup proof; redacted diagnostics retained at ${diagnostics.absolutePath}" }
        check(control.canonicalFile == control.absoluteFile && control.parentFile == parent)
        check(control.deleteRecursively()) { "Stopped fixture control state could not be removed" }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
