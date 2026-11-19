package dev.spofify.mobile.playback

import dev.spofify.mobile.core.offline.OfflineManifest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The local preference owns the work, not a menu, connection, or download attempt. */
internal class PlaylistOfflinePreference(
    private val scope: CoroutineScope,
    read: suspend () -> Set<String>?,
    private val persist: suspend (Set<String>) -> Unit,
    private val readComplete: suspend () -> List<OfflineManifest>,
    remove: suspend (String, () -> Boolean) -> Unit,
    readRetainedIds: suspend () -> Set<String> = { readComplete().mapTo(hashSetOf()) { it.collection.reference.id } },
) {
    private val choices = Mutex()
    private val desired = MutableStateFlow<Set<String>?>(null)
    val wanted = desired.asStateFlow()
    private val writing = MutableStateFlow<Set<String>>(emptySet())
    val pending = writing.asStateFlow()

    init {
        scope.launch {
            // Existing installs have only committed manifests; those were explicitly saved.
            // Never infer intent from them again once any preference has been written.
            retryPlaylistWork { desired.value = read() ?: readComplete().mapTo(hashSetOf()) { it.collection.reference.id } }
        }
        scope.launch {
            wanted.filterNotNull().collectLatest { ids ->
                retryPlaylistWork {
                    for (id in readRetainedIds() - ids) remove(id) { id !in desired.value.orEmpty() }
                }
            }
        }
    }

    fun set(id: String, save: Boolean) {
        if (desired.value == null || id in writing.value) return
        writing.value += id
        scope.launch {
            try {
                choices.withLock {
                    val before = requireNotNull(desired.value)
                    val after = if (save) before + id else before - id
                    if (after != before) {
                        persist(after)
                        desired.value = after
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the previous durable switch state. */ }
            finally { writing.value -= id }
        }
    }

    suspend fun complete(): List<OfflineManifest> {
        wanted.filterNotNull().first()
        return readComplete().filter { it.collection.reference.id in desired.value.orEmpty() }
    }

    /** One retry loop per wanted playlist, only for the lifetime of the caller's network policy. */
    suspend fun download(refresh: suspend (String, () -> Boolean) -> Unit): Unit = coroutineScope {
        val jobs = mutableMapOf<String, Job>()
        wanted.filterNotNull().collect { ids ->
            for (id in jobs.keys.toList()) if (id !in ids) jobs.remove(id)?.cancel()
            for (id in ids) if (id !in jobs) jobs[id] = launch {
                retryPlaylistWork { refresh(id) { id in desired.value.orEmpty() } }
            }
        }
    }
}

private suspend fun retryPlaylistWork(work: suspend () -> Unit) {
    var wait = 2_000L
    while (true) {
        currentCoroutineContext().ensureActive()
        try { work(); return }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            delay(wait)
            wait = (wait * 2).coerceAtMost(300_000L)
        }
    }
}
