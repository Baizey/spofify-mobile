package dev.spofify.mobile.playback

import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.*
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

/** Real client/coordinator/connection with a controlled CURRENT socket, not an old receiver or
 * fake server reducer. Tests explicitly deliver authoritative states/results; acceptance is not sound.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class OnlineQueueConnectionFixture(private val scope: TestScope) : AutoCloseable {
    private val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
    private val web = MockWebServer().apply {
        enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")
            .setBody("""{"accountId":"queue-account","username":"listener","role":"user","csrfToken":"synthetic-csrf"}"""))
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    val graph: AppGraph
    val client: SpofifyClient
    val owner: PlaybackCoordinator
    val connection: PlaybackConnection
    val commands = mutableListOf<LibraryPlaybackClientMessage.Command>()
    private val incoming = Channel<LibraryPlaybackEvent>(32)
    private var serial = 0L
    private val socket = object : LibraryPlaybackTransport {
        override val identity = ValidatedSessionIdentity("http://127.0.0.1:${web.port}", "queue-account", 1)
        override var isCurrent = true
        override val events = incoming.receiveAsFlow()
        override fun send(message: LibraryPlaybackClientMessage): LibraryPlaybackSend? {
            if (!isCurrent) return null
            check(message is LibraryPlaybackClientMessage.Command) { "Controller cannot report executor facts" }
            commands += message
            return LibraryPlaybackSend(0, commandId = (++serial).toString())
        }
        override fun close() { isCurrent = false; incoming.close() }
    }
    var snapshot = testLibrarySnapshot(devices = listOf(
        LibraryPlaybackDevice("phone", "Phone", true, true), LibraryPlaybackDevice("speaker", "Speaker", true, true)),
        selected = "speaker").let { base -> base.copy(state = base.state.copy(
            queue = listOf(7L, 32L, 91L).mapIndexed { index, key ->
                LibraryPlaybackTrack("duplicate", "Occurrence $key", emptyList(), "Album", "album", 120.0,
                    key, index.toLong(), "low", entryId = "entry-$key")
            }, position = 0, elapsed = 42.125,
            occurrence = LibraryPlaybackOccurrence(7, "duplicate", "event", 3.25, false)),
            playback = base.playback.copy(eventId = "event", elapsed = 42.125, duration = 120.0)) }
    init {
        app.graph.clear()
        app.getSharedPreferences("connection", 0).edit().clear().commit()
        graph = AppGraph(app, object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin?) = Unit
        })
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        PlaybackService.onServiceActiveChanged = null
        runBlocking { client.login("listener", "synthetic-password") }
        graph.playbackCoordinator?.close()
        val endpoint = object : NativePlaybackEndpoint {
            override var onObservation: ((String, Boolean) -> Unit)? = null
            override suspend fun prepare(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean): String = error("No instruction admitted")
            override fun release(): Boolean = error("No instruction admitted")
            override suspend fun execute(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority): String = error("No instruction admitted")
            override fun observe() = LibraryPlaybackObservation()
            override fun suspendOutput() = Unit
        }
        owner = PlaybackCoordinator(client, endpoint, scope.backgroundScope,
            clockMs = { scope.testScheduler.currentTime }, mediaEventUptimeMs = { android.os.SystemClock.uptimeMillis() },
            authenticationIdentity = { client.validatedSessionIdentity() }, deviceIdentity = { "phone" to "Phone" },
            openTransport = { _, _, _, _ -> socket })
        AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
        owner.retry(); scope.testScheduler.runCurrent()
        message(LibraryPlaybackServerMessage("snapshot", "room", "socket-phone", "phone", snapshot = snapshot))
        scope.testScheduler.runCurrent()
        connection = PlaybackConnection(app)
        // Presentation observation only; no MediaController or player is needed for online commands.
        PlaybackConnection::class.java.getDeclaredField("observing").apply { isAccessible = true; setBoolean(connection, true) }
        refresh()
    }
    fun refresh() {
        PlaybackConnection::class.java.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(connection)
    }
    fun message(message: LibraryPlaybackServerMessage) {
        incoming.trySend(LibraryPlaybackEvent.Message(message)).getOrThrow()
    }
    fun result(outcome: String = "accepted", code: String = "", count: Int = 1,
        placement: String = "next", execution: String = "unchanged", final: Boolean = true, id: String = serial.toString()) {
        message(LibraryPlaybackServerMessage("result", "room", result = LibraryPlaybackResult(
            id, outcome, code, snapshot.stateVersion, count, placement, execution, final)))
        scope.testScheduler.runCurrent()
    }
    fun publish(value: LibraryPlaybackSnapshot, refreshConnection: Boolean = true) {
        snapshot = value
        message(LibraryPlaybackServerMessage("state", "room", snapshot = value))
        scope.testScheduler.runCurrent()
        if (refreshConnection) refresh()
    }
    fun assertNoHttpPlayback() { org.junit.Assert.assertEquals(1, web.requestCount) }
    override fun close() {
        connection.release(); owner.close()
        try { graph.clear() } finally { web.shutdown() }
    }
}
