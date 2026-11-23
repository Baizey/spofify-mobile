package dev.spofify.mobile.playback

import dev.spofify.mobile.core.offline.OfflineAttemptTrace
import dev.spofify.mobile.core.offline.OfflineDiagnosticEvent
import dev.spofify.mobile.core.offline.OfflineDiagnosticStage
import dev.spofify.mobile.core.offline.OfflineDiagnostics
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
    private val diagnostics: OfflineDiagnostics? = null,
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
            retryPlaylistWork(diagnostics, diagnostics?.beginOperation(OfflineDiagnosticEvent.INTENT_LOAD)) { trace ->
                trace?.step(OfflineDiagnosticStage.PREFERENCES)
                val ids = read() ?: readComplete().mapTo(hashSetOf()) { it.collection.reference.id }
                desired.value = ids
                diagnostics?.event(OfflineDiagnosticEvent.INTENT_READY, count = ids.size)
            }
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
            val operation = diagnostics?.beginOperation(OfflineDiagnosticEvent.INTENT_WRITE_WAIT) ?: 0
            try {
                choices.withLock {
                    val before = requireNotNull(desired.value)
                    val after = if (save) before + id else before - id
                    if (after != before) {
                        diagnostics?.event(OfflineDiagnosticEvent.INTENT_WRITE, operation)
                        persist(after)
                        desired.value = after
                        diagnostics?.event(OfflineDiagnosticEvent.INTENT_PUBLISHED, operation, count = after.size)
                    } else diagnostics?.event(OfflineDiagnosticEvent.INTENT_UNCHANGED, operation)
                }
            } catch (e: CancellationException) {
                diagnostics?.event(OfflineDiagnosticEvent.INTENT_WRITE_CANCELLED, operation)
                throw e
            } catch (e: Exception) {
                diagnostics?.event(OfflineDiagnosticEvent.INTENT_WRITE_FAILED, operation, error = e)
                /* Keep the previous durable switch state. */
            } finally { writing.value -= id }
        }
    }

    suspend fun complete(): List<OfflineManifest> {
        wanted.filterNotNull().first()
        return readComplete().filter { it.collection.reference.id in desired.value.orEmpty() }
    }

    /** One retry loop per wanted playlist, only for the lifetime of the caller's network policy. */
    suspend fun download(refresh: suspend (String, () -> Boolean, OfflineAttemptTrace?) -> Unit): Unit = coroutineScope {
        val jobs = mutableMapOf<String, Job>()
        wanted.filterNotNull().collect { ids ->
            for (id in jobs.keys.toList()) if (id !in ids) jobs.remove(id)?.cancel()
            for (id in ids) if (id !in jobs) jobs[id] = launch {
                retryPlaylistWork(diagnostics, diagnostics?.beginOperation(OfflineDiagnosticEvent.WORKER_INTENT)) { trace ->
                    refresh(id, { id in desired.value.orEmpty() }, trace)
                }
            }
        }
    }
}

private suspend fun retryPlaylistWork(diagnostics: OfflineDiagnostics? = null, operation: Long? = null,
    work: suspend (OfflineAttemptTrace?) -> Unit) {
    var wait = 2_000L
    var number = 0
    var trace: OfflineAttemptTrace? = null
    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            trace = operation?.let { diagnostics?.attempt(it, ++number) }
            try {
                work(trace)
                trace?.outcome(OfflineDiagnosticEvent.FINISHED)
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                trace?.outcome(OfflineDiagnosticEvent.FAILED, e)
                trace?.backoff(wait)
                delay(wait)
                wait = (wait * 2).coerceAtMost(300_000L)
            }
        }
    } catch (e: CancellationException) {
        trace?.outcome(OfflineDiagnosticEvent.CANCELLED)
        throw e
    }
}
