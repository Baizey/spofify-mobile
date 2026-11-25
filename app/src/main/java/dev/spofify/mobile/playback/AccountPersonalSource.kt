package dev.spofify.mobile.playback

import android.util.Log
import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.AccountChannel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

internal interface LivePersonal : PersonalLibraryApi {
    val replica: AccountPersonalReplica
    fun command(intent: PersonalIntent): Boolean
    fun watch(id: String): AutoCloseable
    fun refreshAccount()
}

/** The account scope serializes socket results and view changes on the player looper. */
internal class AccountPersonalSource(
    root: File, private val identity: AccountIdentity, private val sessions: SessionRepository,
    private val scope: CoroutineScope, private val client: PersonalLibraryApi = PersonalLibraryClient(sessions, identity),
    override val replica: AccountPersonalReplica = AccountPersonalReplica(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val beforePersist: suspend (PersonalCacheSnapshot) -> Unit = {},
) : LivePersonal {
    private val codec = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val file = PersonalCacheFile(root, identity)
    private val persisted = MutableStateFlow(PersonalCacheSnapshot())
    private val watching = mutableMapOf<String, Int>()
    private val transfers = mutableMapOf<String, PlaylistTransfer>()
    private val failedUntil = mutableMapOf<String, Long>()
    private var waitingForAccount = false
    private var nextAccountReadAt = 0L
    private var connectionGrant: OnlineGrant? = null
    private var channel: AccountChannel? = null
    private var subscription: AutoCloseable? = null
    private var connectionEpoch = 0L
    private val beforeRestore = ArrayDeque<() -> Unit>()

    init {
        scope.launch {
            val saved = withContext(io) { file.read() }
            if (!sessions.isCurrent(identity)) return@launch
            persisted.value = saved
            replica.restore(saved)
            while (beforeRestore.isNotEmpty()) beforeRestore.removeFirst().invoke()
            var last = saved
            replica.snapshots.collect { snapshot ->
                if (snapshot == last) return@collect
                withContext(io) {
                    try {
                        beforePersist(snapshot)
                        file.write(snapshot) { commit -> sessions.commitLocalIfCurrent(identity, commit) }
                        last = snapshot
                        if (sessions.isCurrent(identity)) persisted.value = snapshot
                    } catch (e: java.io.IOException) { Log.w("SpofifyPersonal", "Could not persist saved views") }
                    catch (e: IllegalArgumentException) { Log.w("SpofifyPersonal", "Saved views exceed storage bounds") }
                }
            }
        }
        scope.launch {
            sessions.onlineGrant.collect { owner ->
                if (connectionGrant != null && connectionGrant !== owner) disconnect()
            }
        }
    }

    fun availability(online: Boolean, ids: Set<String>) = replica.availability(online, ids)

    fun attach(next: AccountChannel?) {
        if (next === channel) return
        subscription?.close()
        disconnect()
        channel = next
        subscription = next?.onPersonal { wire, owner ->
            if (channel !== next || !sessions.isCurrent(identity)) return@onPersonal
            if (wire == null) disconnect()
            else if (owner?.identity == identity) {
                val update = runCatching { codec.decodeFromJsonElement(PersonalUpdate.serializer(), wire) }.getOrNull()
                if (update != null) receive(owner, update)
                else if ("snapshot" in wire && connectionGrant === owner) {
                    sessions.publishIfCurrent(owner) { waitingForAccount = false }
                }
            }
        }
    }

    private fun disconnect() {
        connectionGrant = null
        connectionEpoch++
        waitingForAccount = false
        // Unexpected scoped read failures may close the socket; bound repeat attempts.
        // Exact missing responses are handled separately and keep the binding open.
        val until = monotonicMs() + READ_COOLDOWN_MS
        transfers.keys.forEach { failedUntil[it] = until }
        transfers.clear()
        replica.disconnected()
    }

    private fun receive(owner: OnlineGrant, update: PersonalUpdate) {
        val epoch = connectionEpoch
        afterRestore {
            if (epoch != connectionEpoch) return@afterRestore
            sessions.publishIfCurrent(owner) {
                val snapshot = update.snapshot
                if (snapshot != null) {
                    waitingForAccount = false
                    try { replica.receiveSnapshot(snapshot) } catch (_: IllegalArgumentException) { return@publishIfCurrent }
                    connectionGrant = owner
                    watching.keys.toList().forEach(::requestDetail)
                } else if (connectionGrant === owner) {
                    update.change?.let { change ->
                        try {
                            replica.receiveChange(change)?.let { id ->
                                failedUntil.remove(id)
                                requestDetail(id)
                            }
                        } catch (_: IllegalArgumentException) { requestAccount(repair = true) }
                    }
                    update.part?.let(::receivePart)
                    update.missing?.let { reply ->
                        takeCurrentTransfer(reply.playlistId)?.let { replica.receiveMissing(reply.playlistId, it.version) }
                    }
                }
            }
        }
    }

    private fun takeCurrentTransfer(id: String): PlaylistTransfer? {
        val transfer = transfers.remove(id) ?: return null
        if (transfer.version == replica.version(id) && replica.confirmsPlaylist(id)) return transfer
        // Consume the obsolete reply before starting again: the wire has no request ID.
        requestDetail(id)
        return null
    }

    private fun receivePart(part: PlaylistPart) {
        val id = part.summary.id
        val transfer = takeCurrentTransfer(id) ?: return
        val rows = try { transfer.accept(part) } catch (_: IllegalArgumentException) {
            failedUntil[id] = monotonicMs() + READ_COOLDOWN_MS
            return
        }
        if (rows != null) replica.replaceDetail(transfer.version, part.summary, rows)
        else if (id in watching) sendTransfer(transfer)
    }

    private fun afterRestore(action: () -> Unit) {
        if (replica.view.value.ready) action() else beforeRestore.addLast(action)
    }

    override fun command(intent: PersonalIntent): Boolean {
        if (intent.playlistId?.let { !replica.confirmsPlaylist(it) } == true) return false
        return send(intent)
    }

    private fun send(intent: PersonalIntent): Boolean {
        val owner = connectionGrant ?: return false
        var sent = false
        sessions.publishIfCurrent(owner) { sent = channel?.sendPersonal(codec.encodeToJsonElement(intent) as JsonObject) == true }
        return sent
    }

    fun checkFreshness() {
        refreshAccount()
        watching.keys.toList().forEach(::requestDetail)
    }

    override fun refreshAccount() = requestAccount(repair = false)

    private fun requestAccount(repair: Boolean) {
        val owner = connectionGrant ?: return // Opening the account socket already sends a snapshot.
        if (waitingForAccount || monotonicMs() < nextAccountReadAt || !repair && !replica.accountStale()) return
        sessions.publishIfCurrent(owner) {
            waitingForAccount = channel?.syncPersonal() == true
            if (waitingForAccount) nextAccountReadAt = monotonicMs() + READ_COOLDOWN_MS
        }
    }

    override fun watch(id: String): AutoCloseable {
        watching[id] = (watching[id] ?: 0) + 1
        refreshAccount()
        requestDetail(id)
        return AutoCloseable {
            val count = watching[id] ?: return@AutoCloseable
            if (count == 1) watching.remove(id) else watching[id] = count - 1
        }
    }

    private fun requestDetail(id: String) {
        if (waitingForAccount || id !in watching || id in transfers || !replica.confirmsPlaylist(id) || !replica.detailStale(id)) return
        if (failedUntil[id]?.let { monotonicMs() < it } == true) return
        if (connectionGrant != null) sendTransfer(PlaylistTransfer(id, replica.version(id)))
    }

    private fun sendTransfer(transfer: PlaylistTransfer) {
        if (send(transfer.command())) transfers[transfer.id] = transfer
    }

    suspend fun persistPlaylistForDownload(id: String, occurrenceId: String, trackId: String, owner: OnlineGrant) {
        fun ReplicaPlaylist.containsOccurrence() = rows.any { it.id == occurrenceId && it.track.id == trackId }
        check(sessions.onlineGrant.value === owner)
        check(replica.confirmsPlaylist(id))
        val current = replica.snapshots.value
        val selected = checkNotNull(current.details[id])
        check(selected.containsOccurrence())
        val revision = maxOf(selected.revision, current.playlists.firstOrNull { it.id == id }?.revision ?: 0)
        check(withTimeoutOrNull(5_000) {
            persisted.first { snapshot ->
                snapshot.details[id]?.let { it.revision >= revision && it.containsOccurrence() } == true
            }
        } != null)
        if (sessions.onlineGrant.value !== owner || !replica.confirmsPlaylist(id))
            throw CancellationException("Download authority retired")
    }

    fun retainDownload(summary: PersonalPlaylistSummary, rows: List<PersonalManualEntry>, owner: OnlineGrant?) = afterRestore {
        if (owner != null) sessions.publishIfCurrent(owner) { replica.retainDownload(summary, rows) }
        else if (sessions.isCurrent(identity)) replica.retainDownload(summary, rows)
    }

    fun recordLocal(entry: PersonalHistoryEntry) = afterRestore {
        if (sessions.isCurrent(identity)) replica.recordLocal(entry)
    }

    private suspend fun <T : Any> online(block: suspend () -> T): T = sessions.withOnlineGrant(
        connectionGrant ?: throw CancellationException("Personal edits unavailable offline")
    ) { block() }
    override suspend fun likes(limit: Int, offset: Int) = online { client.likes(limit, offset) }
    override suspend fun likeStatus(trackIds: List<String>) = online { client.likeStatus(trackIds) }
    override suspend fun setLike(trackId: String, liked: Boolean) = online { client.setLike(trackId, liked) }
}

private const val READ_COOLDOWN_MS = 30_000L
