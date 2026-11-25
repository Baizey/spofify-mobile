package dev.spofify.mobile.playback

import dev.spofify.mobile.core.offline.OfflineAttemptTrace
import dev.spofify.mobile.core.offline.OfflineDiagnosticEvent
import dev.spofify.mobile.core.offline.OfflineDiagnosticStage
import dev.spofify.mobile.core.offline.OfflineException
import dev.spofify.mobile.core.offline.OfflineFailure
import java.io.IOException
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
    private val terminal = MutableStateFlow<Set<String>>(emptySet())
    val failed = terminal.asStateFlow()

    init {
        scope.launch {
            retryPlaylistWork(diagnostics, diagnostics?.beginOperation(OfflineDiagnosticEvent.INTENT_LOAD)) { trace ->
                trace?.step(OfflineDiagnosticStage.PREFERENCES)
                val ids = read().orEmpty()
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
                        if (!save) terminal.value -= id
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
            for (id in ids) if (id !in jobs && id !in terminal.value) jobs[id] = launch {
                val success = retryPlaylistWork(diagnostics, diagnostics?.beginOperation(OfflineDiagnosticEvent.WORKER_INTENT)) { trace ->
                    refresh(id, { id in desired.value.orEmpty() }, trace)
                }
                if (!success && id in desired.value.orEmpty()) terminal.value += id
            }
        }
    }
}

private suspend fun retryPlaylistWork(diagnostics: OfflineDiagnostics? = null, operation: Long? = null,
    work: suspend (OfflineAttemptTrace?) -> Unit): Boolean {
    var wait = 2_000L
    var number = 0
    var trace: OfflineAttemptTrace? = null
    try {
        for (attempt in 0..2) {
            currentCoroutineContext().ensureActive()
            trace = operation?.let { diagnostics?.attempt(it, ++number) }
            try {
                work(trace)
                trace?.outcome(OfflineDiagnosticEvent.FINISHED)
                return true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                trace?.outcome(OfflineDiagnosticEvent.FAILED, e)
                if (attempt == 2 || !e.transientPlaylistFailure()) return false
                trace?.backoff(wait)
                delay(wait)
                wait = (wait * 2).coerceAtMost(300_000L)
            }
        }
    } catch (e: CancellationException) {
        trace?.outcome(OfflineDiagnosticEvent.CANCELLED)
        throw e
    }
    return false
}

private fun Exception.transientPlaylistFailure(): Boolean = when (this) {
    is OfflineException -> failure in setOf(OfflineFailure.UNAVAILABLE, OfflineFailure.BUSY, OfflineFailure.TIMEOUT)
    is IOException -> true // Includes interrupted streaming, bounded even if disk IO never recovers.
    else -> false
}
