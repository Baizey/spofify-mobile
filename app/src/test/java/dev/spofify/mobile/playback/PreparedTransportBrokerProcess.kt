package dev.spofify.mobile.playback

import java.io.InputStream
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One owned real broker across many AppGraph/controller/service lifetimes.
 * Dedicated Python launcher enforces its own 300s lifetime and subreaps descendants.
 * No force-kill, scratch deletion, broker authority synthesis, or auth logging here.
 */
internal class PreparedTransportBrokerProcess private constructor(
    private val process: Process,
    private val readers: ExecutorService,
    private val handshakeRead: Future<String>,
    private val stderrRead: Future<String>,
    private val stderrSnapshot: AtomicReference<String>,
) : AutoCloseable {
    private var parsedFixture: PreparedTransportBrokerFixture? = null
    val fixture: PreparedTransportBrokerFixture
        get() = checkNotNull(parsedFixture) { "Broker handshake is not ready" }
    @Volatile private var closed = false

    /** Safe live summary only: bounded counts keyed by allowlisted route/method/phase/error type.
     * Never returns the private stderr tail, handshake, URL, headers, body or exception messages.
     */
    fun diagnostics(): Map<String, Int> {
        val counts = sortedMapOf<String, Int>()
        fun increment(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        stderrSnapshot.get().lineSequence().forEach { line ->
            val record = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
            if (record["proxyDiagnostic"] == null) return@forEach
            fun text(key: String) = runCatching { record[key]?.jsonPrimitive?.content }.getOrNull()
            val endpoint = text("endpoint")?.takeIf { it in setOf("player", "auth-login", "auth-session", "fixture-control", "other") }
                ?: return@forEach
            val method = text("method")?.takeIf { it in setOf("GET", "POST", "OTHER") } ?: return@forEach
            val phase = text("phase")?.takeIf { it in setOf("request-body", "upstream-request", "upstream-headers",
                "upstream-body", "downstream-response", "complete", "handler") } ?: return@forEach
            increment("events")
            increment("$endpoint.$method.$phase")
            text("exceptionType")?.takeIf { it in setOf("TimeoutError", "ConnectionRefusedError", "ConnectionResetError",
                "BrokenPipeError", "RemoteDisconnected", "IncompleteRead", "BadStatusLine", "LineTooLong", "EOFError",
                "ValueError", "UnicodeDecodeError", "JSONDecodeError", "AssertionError", "OSError", "Other") }
                ?.let { increment("error.$it") }
        }
        return counts
    }

    private val controlClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Arm immediately before the intended production Pause; inspect the accepted sequence. */
    suspend fun armPauseResponse(mode: String = "hold") {
        require(mode == "hold" || mode == "drop") { "Unsupported fixture response fault" }
        control("arm", "{\"mode\":\"$mode\"}")
    }

    /** accepted:true witnesses a fully received real Go HTTP 200, not request arrival. */
    suspend fun pauseResponseStatus(): JsonObject? = control("status", null)

    suspend fun releasePauseResponse() {
        control("release", "{}")
    }

    /** Fixed attempt counters only; compare deltas, never treat them as broker ACKs/results. */
    suspend fun metrics(): JsonObject = checkNotNull(control("metrics", null)) {
        "Missing fixture metrics"
    }

    private suspend fun control(action: String, body: String?): JsonObject? = withContext(Dispatchers.IO) {
        check(!closed && process.isAlive) { "Broker fixture is not running" }
        // A separate client has no login cookie/CSRF, logging interceptor or ambient proxy.
        val request = Request.Builder()
            .url(fixture.server.origin + "/__fixture/" + action)
            .header("X-Fixture-Token", fixture.controlToken)
            .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }
            .build()
        val raw = try {
            controlClient.newCall(request).execute().use { response ->
                check(response.code == 200) { "Fixture control request was refused" }
                val source = checkNotNull(response.body) { "Missing fixture control response" }.source()
                check(!source.request(16_385)) { "Fixture control response exceeds bound" }
                source.readUtf8()
            }
        } catch (_: Exception) {
            // Never include request URL, headers, token, body or low-level exception text.
            throw IllegalStateException("Fixture control request failed or exceeded its finite deadline")
        }
        try {
            val value = Json.parseToJsonElement(raw)
            if (value == JsonNull) null else value.jsonObject
        } catch (_: Exception) {
            throw IllegalStateException("Malformed fixture control response")
        }
    }

    /** Normal shutdown is established ONLY by exit zero, ECHILD cleanup receipt and joined readers.
     * On timeout/nonzero/missing receipt this throws without killing processes or deleting scratch.
     * Caller must report unresolved cleanup, not treat a failed close as a completed disposal.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        if (process.isAlive) {
            try {
                process.outputStream.write("stop\n".toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            } catch (_: Exception) {
                // Closing stdin below is the same owner-disposal signal; still require exit proof.
            } finally {
                runCatching { process.outputStream.close() }
            }
        }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            System.err.println("PreparedTransportBrokerDiagnostics ${diagnostics()}")
            throw IllegalStateException("Broker cleanup unverified: launcher remains; no kill or scratch removal attempted")
        }
        val stderr = try {
            stderrRead.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            readers.shutdown()
            throw IllegalStateException("Broker exited but bounded stderr reader did not complete; cleanup unverified")
        }
        System.err.println("PreparedTransportBrokerDiagnostics ${diagnostics()}")
        // Process exit closes both pipes; unblock/join even if startup handshake parsing failed.
        runCatching { handshakeRead.get(2, TimeUnit.SECONDS) }
        readers.shutdown()
        check(readers.awaitTermination(2, TimeUnit.SECONDS)) { "Broker reader executor did not stop" }
        controlClient.connectionPool.evictAll()
        controlClient.dispatcher.executorService.shutdown()
        check(controlClient.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)) {
            "Fixture control executor did not stop"
        }
        check(process.exitValue() == 0) { "Broker launcher exited nonzero; inspect owned evidence without exposing credentials" }
        val receipt = stderr.lineSequence().mapNotNull { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
        }.lastOrNull { it["fixtureCleanup"]?.jsonPrimitive?.content == "complete" }
        check(receipt?.get("descendants")?.jsonPrimitive?.content == "ECHILD") {
            "Broker exit lacks successful descendant/scratch cleanup receipt; cleanup unverified"
        }
        closed = true
    }

    override fun toString(): String = "PreparedTransportBrokerProcess(private pinned loopback fixture)"

    companion object {
        /** Blocking startup: call outside a UI event callback. Handshake read has a 45s bound.
         * Runner search examines at most eight known ancestors, never traverses foreign trees.
         * Pass runner explicitly when the test JVM user.dir is not under this worktree.
         */
        fun start(runner: Path? = null): PreparedTransportBrokerProcess {
            val provisionedBinary = System.getenv("SPOFIFY_TEST_GO_BROKER_BINARY").orEmpty()
            require(provisionedBinary.isNotBlank()) {
                "SPOFIFY_TEST_GO_BROKER_BINARY is required for selected real-broker integration"
            }
            require(runCatching {
                val binary = Path.of(provisionedBinary)
                Files.isRegularFile(binary) && Files.isReadable(binary)
            }.getOrDefault(false)) {
                "SPOFIFY_TEST_GO_BROKER_BINARY must name a regular readable provisioned pinned executable"
            }
            // Resolve defaults only after the explicit binary prerequisite has passed.
            val selectedRunner = runner ?: defaultRunner()
            require(Files.isRegularFile(selectedRunner)) { "Prepared transport broker runner is missing" }
            val process = try {
                ProcessBuilder("python3", "-B", selectedRunner.toAbsolutePath().toString(), "--seconds", "300")
                    .apply { environment()["PYTHONDONTWRITEBYTECODE"] = "1" }
                    .start()
            } catch (_: Exception) {
                throw IllegalStateException("Unable to launch prepared transport broker runner")
            }
            val readers = Executors.newFixedThreadPool(2) { task ->
                Thread(task, "prepared-transport-broker-reader").apply { isDaemon = true }
            }
            val handshakeRead = readers.submit<String> { boundedLine(process.inputStream) }
            val stderrSnapshot = AtomicReference("")
            val stderrRead = readers.submit<String> { boundedTail(process.errorStream, stderrSnapshot) }
            val owner = PreparedTransportBrokerProcess(process, readers, handshakeRead, stderrRead, stderrSnapshot)
            try {
                val privateLine = handshakeRead.get(45, TimeUnit.SECONDS)
                owner.parsedFixture = PreparedTransportBrokerFixture.fromHandshakeJson(privateLine)
                return owner
            } catch (_: Exception) {
                // JSON failures can echo source text, so never attach a private-handshake cause.
                val failure = IllegalStateException("Prepared transport broker private handshake failed or exceeded 45 seconds")
                try {
                    owner.close()
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        private fun defaultRunner(): Path {
            var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
            repeat(8) {
                val current = directory ?: return@repeat
                val candidate = current.resolve("tools/testing/prepared-transport-broker/broker_fixture.py")
                if (Files.isRegularFile(candidate)) return candidate
                directory = current.parent
            }
            throw IllegalStateException("Prepared transport broker runner was not found; pass its path explicitly")
        }

        private fun boundedLine(input: InputStream): String {
            val bytes = ByteArray(16_384)
            var length = 0
            while (length < bytes.size) {
                val next = input.read()
                check(next >= 0) { "Broker ended before handshake" }
                if (next == '\n'.code) return String(bytes, 0, length, Charsets.UTF_8)
                bytes[length++] = next.toByte()
            }
            throw IllegalStateException("Broker handshake exceeds bound")
        }

        /** Drain continuously to prevent child pipe blockage; retain at most 16 KiB privately. */
        private fun boundedTail(input: InputStream, snapshot: AtomicReference<String>): String {
            val tail = ByteArray(16_384)
            val chunk = ByteArray(4_096)
            var length = 0
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                val discard = (length + count - tail.size).coerceAtLeast(0)
                if (discard > 0) {
                    System.arraycopy(tail, discard, tail, 0, length - discard)
                    length -= discard
                }
                System.arraycopy(chunk, 0, tail, length, count)
                length += count
                snapshot.set(String(tail, 0, length, Charsets.UTF_8))
            }
            return String(tail, 0, length, Charsets.UTF_8)
        }
    }
}
