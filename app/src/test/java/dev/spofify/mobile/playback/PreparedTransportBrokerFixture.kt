package dev.spofify.mobile.playback

import android.content.Context
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Consumes the private stdout handshake from tools/testing/prepared-transport-broker/broker_fixture.py.
 * The test owner keeps ONE runner alive across graph/service/controller lifetimes, then closes
 * runner stdin and awaits its exit (which reaps descendants before deleting exact scratch).
 * No mocked cookies, playback views, registration, endpoint or successful start are installed.
 */
internal class PreparedTransportBrokerFixture private constructor(
    val server: ServerAddress,
    private val username: String,
    private val password: String,
    val tracks: Map<String, String>,
    val albumId: String,
    val artistId: String,
    val controlToken: String,
) {
    /** Fresh real login/session on the same long-lived server, through the production client. */
    suspend fun authenticatedClient(store: SessionStore = MemorySessions()): SpofifyClient =
        SpofifyClient(server, store).also { it.login(username, password) }

    /** Configures the genuine AppGraph; production coordinator/service participation stays intact.
     * Call on the Robolectric main thread using the test's ordinary coroutine/main-looper setup.
     * Owner must graph.clear() at each graph lifetime boundary; this never restarts the broker.
     */
    suspend fun authenticatedGraph(context: Context, store: SessionStore = MemorySessions()): AppGraph {
        val graph = AppGraph(context, store, VolatileLogins())
        try {
            graph.configure(server).login(username, password)
            return graph
        } catch (failure: Throwable) {
            graph.clear()
            throw failure
        }
    }

    // Never let generated synthetic credentials or the private control token appear in failures.
    override fun toString(): String = "PreparedTransportBrokerFixture(real pinned loopback broker)"

    class MemorySessions : SessionStore {
        private var value: StoredSession? = null
        @Synchronized override fun read(): StoredSession? = value
        @Synchronized override fun write(value: StoredSession?) { this.value = value }
    }

    private class VolatileLogins : LoginStore {
        private var value: RememberedLogin? = null
        override fun read(): RememberedLogin? = value
        override fun write(value: RememberedLogin?) { this.value = value }
    }

    companion object {
        fun fromHandshakeJson(raw: String): PreparedTransportBrokerFixture {
            require(raw.length <= 16_384) { "Broker handshake exceeds bound" }
            val root = Json.parseToJsonElement(raw).jsonObject
            fun text(key: String): String = requireNotNull(root[key]).jsonPrimitive.content
            require(text("version") == "1") { "Unsupported fixture handshake" }
            require(text("brokerRevision") == "b6eb54ffdc91e07e2ce7824b8be5f90afa8b05eb") { "Unexpected broker revision" }
            require(text("brokerSha256") == "425e4bb46efcf1c25e195d92d4d1845ed2389cd35e6ebf9ad8981cd80c2c70f4") {
                "Unexpected broker checksum"
            }
            val origin = text("baseUrl")
            require(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}").matches(origin)) { "Fixture must use literal loopback HTTP" }
            val server = ServerAddress.parse(origin)
            val tracks = requireNotNull(root["tracks"]).jsonArray.associate {
                val track = it.jsonObject
                requireNotNull(track["name"]).jsonPrimitive.content to requireNotNull(track["id"]).jsonPrimitive.content
            }
            require(tracks.keys == setOf("A", "H1", "C1", "C2", "B1", "B2")) { "Incomplete synthetic catalog" }
            require(tracks.values.toSet().size == 6 && tracks.values.none(String::isBlank)) { "Invalid synthetic track IDs" }
            return PreparedTransportBrokerFixture(server, text("username"), text("password"), tracks,
                text("albumId"), text("artistId"), text("controlToken"))
        }
    }
}
