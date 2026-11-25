package dev.spofify.mobile.playback

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.cache.*
import dev.spofify.mobile.core.http.OkHttpAuthApiFactory
import dev.spofify.mobile.core.offline.OfflineClient
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses pinned Go's production account socket and real per-track transfer; no mock responses. */
class AccountPersonalGoFixtureTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 90_000)
    fun cleanAndroidCacheOpensExistingPlaylistAndDownloadsOneTrackAcrossDiskReopen() = runBlocking {
        val executable = System.getenv("SPOFIFY_GO_FIXTURE_BIN")
        assumeTrue("Run scripts/test-current-go-offline.sh to build the pinned fixture", !executable.isNullOrEmpty())
        val fixtureRoot = Files.createDirectory(folder.root.toPath().resolve("go"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val errors = File(folder.root, "go-stderr")
        val port = (System.getenv("SPOFIFY_GO_FIXTURE_PORT") ?: "0").toInt().also { require(it in 0..65535) }
        val process = ProcessBuilder(executable!!, "--root", fixtureRoot.toString(), "--listen", "127.0.0.1:$port")
            .redirectError(errors).start()
        // The real controller/source each use one looper. An executor models that here without Android APIs.
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { looper ->
            val scope = CoroutineScope(SupervisorJob() + looper)
            try {
                val ready = Json.parseToJsonElement(checkNotNull(process.inputStream.bufferedReader().readLine()) {
                    "Pinned Go fixture exited before its ready descriptor"
                }).jsonObject
                fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
                assertEquals("ready", ready.string("event"))
                assertEquals("d17804f0d5c033cb6737e3fdbbc5cada5dd06dad", ready.string("sourceRevision"))
                val origin = ready.string("origin").also { require(it.startsWith("http://127.0.0.1:")) }
                val targets = ready.getValue("targets").jsonArray.map { it.jsonObject }
                val manual = targets.single { it["playlistKind"]?.jsonPrimitive?.content == "manual" }.string("id")
                val derived = targets.single { it["playlistKind"]?.jsonPrimitive?.content == "derived" }.string("id")
                val trackIds = ready.getValue("trackIds").jsonArray.map { it.jsonPrimitive.content }
                val duplicate = ready.string("duplicateTrackId")
                val expected = listOf(duplicate, duplicate) + trackIds.filterNot { it == ready.string("metadataOnlyTrackId") }
                val login = object : LoginStore {
                    override fun read(): RememberedLogin? = null
                    override fun write(value: RememberedLogin) = Unit
                }
                val sessions = SessionRepository(object : SessionStore {
                    override fun read(): StoredSession? = null
                    override fun write(value: StoredSession?) = Unit
                }, login, OkHttpAuthApiFactory(), scope, manualOfflineStore = object : ManualOfflineStore {
                    private var offline = false
                    override fun read() = offline
                    override fun write(enabled: Boolean) { offline = enabled }
                })
                val cacheDir = folder.newFolder("personal")
                val blobDir = folder.newFolder("audio")
                val stamp = AtomicLong(1000)
                val clock = object : Clock() {
                    override fun getZone() = ZoneOffset.UTC
                    override fun withZone(zone: java.time.ZoneId): Clock = this
                    override fun instant(): Instant = Instant.ofEpochMilli(stamp.get())
                }
                withTimeout(35_000) {
                    sessions.start()
                    sessions.state.first { it is AuthState.SignedOut }
                    sessions.signIn(origin, ready.string("username"), ready.string("password"))
                    val grant = requireNotNull(sessions.onlineGrant.first { it != null })
                    val identity = grant.identity
                    assertEquals(ready.string("userId"), identity.accountId)
                    val source = withContext(looper) {
                        AccountPersonalSource(cacheDir, identity, sessions, scope, replica = AccountPersonalReplica(clock),
                            monotonicMs = { System.nanoTime() / 1_000_000 })
                    }
                    val channel = withContext(looper) {
                        AccountChannel(sessions.playbackTransport(identity), object : AudioExecutor {
                            override val observations = emptyFlow<AudioObservation>()
                            override fun facts() = AudioFacts()
                            override fun pause() = Unit
                            override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double) = AudioResult.OK
                            override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
                                desiredPlaying: Boolean, isAuthorized: () -> Boolean) = AudioResult.OK
                        }, scope).also {
                            source.attach(it)
                            it.connect("Android JVM fixture", "fixture-jvm", grant)
                        }
                    }
                    val initial = source.replica.view.first { it.connected }
                    assertEquals(setOf(manual, derived), initial.playlists.map { it.id }.toSet())
                    assertTrue(initial.recent.isEmpty())
                    assertTrue(initial.details.isEmpty())
                    val watches = withContext(looper) { listOf(source.watch(manual), source.watch(derived)) }
                    val detail = source.replica.snapshots.first { manual in it.details && derived in it.details }.details
                    for (id in listOf(manual, derived)) {
                        val rows = detail.getValue(id).rows
                        assertEquals(expected, rows.map { it.track.id })
                        assertEquals(rows.size, rows.map { it.id }.toSet().size)
                        assertEquals(rows.indices.map(Int::toLong), rows.map { it.position })
                    }
                    stamp.addAndGet(300_001)
                    withContext(looper) { source.refreshAccount(); source.refreshAccount() }
                    source.replica.snapshots.first { it.listsAt == stamp.get() && it.recentAt == stamp.get() }
                    assertEquals(detail, source.replica.snapshots.value.details)
                    val row = detail.getValue(manual).rows[1]
                    val store = MusicBlobStore(blobDir, freeBytes = { Long.MAX_VALUE })
                    val media = OfflineClient(sessions, identity)
                    val key = SongKey(MusicOwner(identity.origin, identity.accountId), row.track.id)
                    withContext(looper) { source.persistPlaylistForDownload(manual, row.id, row.track.id, grant) }
                    withContext(Dispatchers.IO) {
                        sessions.withOnlineGrant(grant) {
                            store.download(key, row.track.name, 3, { sessions.onlineGrant.value === grant },
                                { media.prepareMedia(row.track.id) }, { sessions.publishIfCurrent(grant, it) }) { descriptor, consume ->
                                sessions.versionedAudio(identity, descriptor).use { consume(it.input) }
                            }
                        }
                    }
                    assertTrue(store.songs.value.single().direct)

                    // Exercise the production remove delta and scoped repair, not just startup order.
                    val manualBefore = detail.getValue(manual)
                    val removed = manualBefore.rows.first()
                    val survivors = manualBefore.rows.drop(1)
                    withContext(looper) {
                        watches[1].close() // Keep the last accepted derived rows; do not fetch a new materialization here.
                        assertTrue(source.command(PersonalIntent("removeEntry", playlistId = manual, entryId = removed.id)))
                    }
                    val repaired = source.replica.snapshots.first { snapshot ->
                        snapshot.details[manual]?.let { it.revision > manualBefore.revision && it.refreshedAt != null } == true
                    }.details.getValue(manual)
                    assertEquals(survivors.map { it.id }, repaired.rows.map { it.id })
                    assertEquals(survivors.indices.map(Int::toLong), repaired.rows.map { it.position })
                    assertEquals(1L, repaired.rows[1].position) // Former slot 2 is now the second visible duplicate.
                    withContext(looper) { source.persistPlaylistForDownload(manual, row.id, row.track.id, grant) }

                    // A raw non-owned probe is admitted only by this test transport, not Android UI.
                    // Verify Go returns the exact scoped missing envelope and still handles sync.
                    val foreign = ready.string("otherPlaylistId")
                    val missing = CompletableDeferred<JsonObject>()
                    val listener = withContext(looper) {
                        channel.onPersonal { update, _ -> if (update?.containsKey("missing") == true) missing.complete(update) }
                            .also {
                                assertTrue(channel.sendPersonal(buildJsonObject { put("type", "syncPlaylist"); put("playlistId", foreign) }))
                            }
                    }
                    try {
                        assertEquals(buildJsonObject { putJsonObject("missing") { put("playlistId", foreign) } }, missing.await())
                        stamp.addAndGet(300_001)
                        withContext(looper) { source.refreshAccount() }
                        source.replica.snapshots.first { snapshot -> snapshot.listsAt == stamp.get() }
                    } finally { withContext(looper) { listener.close() } }
                    sessions.setManualOffline(true)
                    withContext(looper) { watches[0].close(); source.attach(null); channel.close() }
                    val reopenedStore = MusicBlobStore(blobDir)
                    reopenedStore.pin(key, downloadOnly = true) { true }!!.use { pin ->
                        pin.open(0).use { assertTrue(it.input.readBytes().isNotEmpty()) }
                    }
                    assertNull(reopenedStore.pin(SongKey(key.owner, trackIds[1]), downloadOnly = true) { true })
                    val reopened = withContext(looper) {
                        AccountPersonalSource(cacheDir, identity, sessions, scope, monotonicMs = { System.nanoTime() / 1_000_000 })
                            .also { it.availability(false, setOf(key.id)) }
                    }.replica.view.first { it.ready }
                    assertFalse(reopened.connected)
                    for (id in listOf(manual, derived)) {
                        val rows = reopened.details.getValue(id).rows
                        val accepted = if (id == manual) repaired else detail.getValue(derived)
                        assertEquals(accepted.rows.map { it.track.id }, rows.map { it.track.id })
                        assertEquals(accepted.rows.map { it.id to it.position }, rows.map { it.id to it.position })
                        assertEquals(if (id == manual) 2 else 3, rows.count { it.track.hasAudio })
                    }
                }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                process.outputStream.close()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                    fail("Go fixture did not stop after closing its owned stdin")
                }
                if (process.exitValue() == 0) {
                    val stopped = Json.parseToJsonElement(errors.readLines().last()).jsonObject
                    assertEquals(JsonPrimitive("stopped"), stopped["event"])
                    assertEquals(JsonPrimitive(true), stopped["clean"])
                }
            }
        }
    }
}
