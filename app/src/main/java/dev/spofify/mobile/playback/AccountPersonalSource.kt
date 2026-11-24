package dev.spofify.mobile.playback

import android.util.Log
import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.personal.AccountPersonalReplica.Resource
import dev.spofify.mobile.core.playback.AccountChannel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

internal fun PersonalLibraryApi.personalEditsAvailable(): Boolean =
    (this as? LivePersonal)?.replica?.view?.value?.connected ?: (this !is DevicePersonalLibrary)

internal interface LivePersonal {
    val replica: AccountPersonalReplica
    fun command(intent: PersonalIntent): Boolean
    fun watch(id: String): AutoCloseable
    fun refresh(resource: Resource) {}
}

/** The account scope serializes view changes on Main; disk and HTTP run off the player looper. */
internal class AccountPersonalSource(
    root: File, private val identity: AccountIdentity, private val sessions: SessionRepository,
    private val scope: CoroutineScope, private val client: PersonalLibraryApi = PersonalLibraryClient(sessions, identity),
    override val replica: AccountPersonalReplica = AccountPersonalReplica(),
) : LivePersonal, PersonalLibraryApi by client {
    private val codec = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val file = PersonalCacheFile(root, identity)
    private val reads = PersonalCacheReads(client)
    private val persisted = MutableStateFlow(PersonalCacheSnapshot())
    private val watching = mutableMapOf<String, Int>()
    private val requests = mutableMapOf<Resource, Job>()
    private val missed = mutableSetOf<Resource>()
    private val failed = mutableMapOf<Resource, Long>()
    private var grant: OnlineGrant? = null
    private var connectionGrant: OnlineGrant? = null
    private var channel: AccountChannel? = null
    private var subscription: AutoCloseable? = null
    private val beforeRestore = ArrayDeque<() -> Unit>()

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { file.read() }
            if (!sessions.isCurrent(identity)) return@launch
            persisted.value = saved
            replica.restore(saved)
            while (beforeRestore.isNotEmpty()) beforeRestore.removeFirst().invoke()
            checkFreshness()
            var last = saved
            replica.snapshots.collect { snapshot ->
                if (snapshot == last) return@collect
                withContext(Dispatchers.IO) {
                    try {
                        file.write(snapshot) { commit -> sessions.commitLocalIfCurrent(identity, commit) }
                        last = snapshot
                        if (sessions.isCurrent(identity)) persisted.value = snapshot
                    } catch (e: java.io.IOException) { Log.w("SpofifyPersonal", "Could not persist saved views") }
                    catch (e: IllegalArgumentException) { Log.w("SpofifyPersonal", "Saved views exceed storage bounds") }
                }
            }
        }
        scope.launch {
            sessions.onlineGrant.collect { next ->
                val owner = next?.takeIf { it.identity == identity }
                if (owner === grant) return@collect
                requests.values.forEach { it.cancel() }
                requests.clear(); missed.clear(); failed.clear()
                grant = owner
                if (connectionGrant !== owner) replica.disconnected()
                checkFreshness()
            }
        }
    }

    fun availability(online: Boolean, ids: Set<String>) {
        replica.availability(online, ids)
    }

    fun attach(next: AccountChannel?) {
        if (next === channel) return
        subscription?.close()
        beforeRestore.clear()
        replica.disconnected()
        connectionGrant = null
        channel = next
        subscription = next?.onPersonal { wire ->
            if (channel !== next || !sessions.isCurrent(identity)) return@onPersonal
            if (wire == null) {
                connectionGrant = null
                beforeRestore.clear()
                replica.disconnected()
            } else {
                val owner = sessions.onlineGrant.value?.takeIf { it.identity == identity } ?: return@onPersonal
                val update = codec.decodeFromJsonElement(PersonalUpdate.serializer(), wire)
                afterRestore {
                    sessions.publishIfCurrent(owner) {
                        connectionGrant = owner
                        val repair = replica.receive(update)
                        if (repair != null && repair.id in watching) refresh(repair)
                        if (update.change?.playlist != null || update.change?.deleted != null) refresh(Resource.Lists)
                    }
                    if (update.snapshot != null) checkFreshness()
                }
            }
        }
    }

    private fun afterRestore(action: () -> Unit) {
        if (replica.view.value.ready) action() else beforeRestore.addLast(action)
    }

    override fun command(intent: PersonalIntent): Boolean {
        val owner = connectionGrant ?: return false
        var sent = false
        sessions.publishIfCurrent(owner) { sent = channel?.sendPersonal(codec.encodeToJsonElement(intent) as JsonObject) == true }
        return sent
    }

    fun checkFreshness() {
        refresh(Resource.Lists)
        refresh(Resource.History)
        watching.keys.toList().forEach { refresh(Resource.Detail(it)) }
    }

    override fun watch(id: String): AutoCloseable {
        watching[id] = (watching[id] ?: 0) + 1
        refresh(Resource.Detail(id))
        return AutoCloseable {
            val count = watching[id] ?: return@AutoCloseable
            if (count == 1) watching.remove(id) else watching[id] = count - 1
        }
    }

    override fun refresh(resource: Resource) {
        val owner = grant ?: return
        if (!replica.view.value.ready || sessions.onlineGrant.value !== owner || !replica.stale(resource)) return
        val version = replica.version(resource)
        if (requests[resource]?.isActive == true) {
            missed.add(resource)
            return
        }
        if (failed[resource]?.let { android.os.SystemClock.elapsedRealtime() < it } == true) return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                sessions.withOnlineGrant(owner) {
                    for (attempt in 0..2) {
                        try {
                            val publish: () -> Unit = when (resource) {
                                Resource.Lists -> reads.lists().let { { replica.replaceLists(version, it) } }
                                Resource.History -> reads.history().let { { replica.replaceHistory(version, it) } }
                                is Resource.Detail -> reads.detail(resource.id).let { (summary, rows) ->
                                    { replica.replaceDetail(version, summary, rows) }
                                }
                            }
                            ensureActive()
                            sessions.publishIfCurrent(owner, publish)
                            break
                        } catch (failure: PersonalLibraryException) {
                            if (attempt == 2 || failure.code != PersonalLibraryErrorCode.CONNECTION_FAILED &&
                                failure.status !in setOf(408, 429, 502, 503, 504)) throw failure
                            delay(500L shl attempt)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed[resource] = android.os.SystemClock.elapsedRealtime() + 30_000 }
            finally {
                if (requests[resource] === coroutineContext.job) {
                    requests.remove(resource)
                    if (missed.remove(resource) && replica.version(resource) != version) refresh(resource)
                }
            }
        }
        requests[resource] = task
        task.start()
    }

    suspend fun persistPlaylistForDownload(id: String, owner: OnlineGrant) {
        check(sessions.onlineGrant.value === owner)
        check(id in replica.snapshots.value.details)
        check(withTimeoutOrNull(5_000) { persisted.first { id in it.details }; true } == true)
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
    override suspend fun toggleLike(trackId: String, currentlyLiked: Boolean) = online { client.toggleLike(trackId, currentlyLiked) }
}
