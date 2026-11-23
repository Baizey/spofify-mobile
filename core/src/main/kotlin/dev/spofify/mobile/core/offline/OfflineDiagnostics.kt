package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.http.TransientAudioLoadFailure
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic vocabulary. Keep arbitrary strings and domain identifiers out of this boundary. */
enum class OfflineDiagnosticEvent {
    CACHE_RECOVERY_START, CACHE_RECOVERY_READY, CACHE_RECOVERY_UNAVAILABLE,
    POLICY_NO_GRANT, POLICY_NO_LINK, POLICY_WAIT_STORAGE, POLICY_NO_STORAGE, POLICY_ELIGIBLE, POLICY_ELIGIBLE_METERED,
    INTENT_LOAD, INTENT_READY, INTENT_WRITE_WAIT, INTENT_WRITE, INTENT_PUBLISHED, INTENT_UNCHANGED,
    INTENT_WRITE_FAILED, INTENT_WRITE_CANCELLED, WORKER_INTENT,
    ATTEMPT, STEP, METADATA_RETRY, FAILED, BACKOFF, CANCELLED, FINISHED,
}

enum class OfflineDiagnosticStage {
    PREFERENCES, GRANT, METERED_LOOKUP, METERED_SKIP, PLAYLIST_LOCK, ATTEMPT_SLOT, ATTEMPT_ADMITTED,
    MANIFEST_LOCK, MANIFEST_REQUEST, MANIFEST_VALIDATION, RESUME, RESUME_SCAN, RESUMED, PROGRESS,
    MEDIA_LOOKUP, MEDIA_JOIN, MEDIA_REUSE, MEDIA_PREPARE, MEDIA_SETUP, MEDIA_OPEN, MEDIA_BODY,
    MEDIA_WRITE_WAIT, MEDIA_WRITE, MEDIA_VERIFY, MEDIA_PUBLISH, MEDIA_RETAIN,
    COMMIT, COMMIT_INDEX, COMMIT_DISK_WAIT, COMMIT_WRITE, COMMIT_CLEANUP, COMMITTED, CATALOG, METADATA_BACKOFF, BACKOFF,
}

/** Optional observer, not work authority. The app must supply a non-blocking, best-effort sink. */
class OfflineDiagnostics(private val sink: (String) -> Unit) {
    private val operations = AtomicLong()

    fun beginOperation(event: OfflineDiagnosticEvent): Long = operations.incrementAndGet().also { event(event, operation = it) }

    fun attempt(operation: Long, number: Int): OfflineAttemptTrace = OfflineAttemptTrace(this, operation, number)

    fun event(event: OfflineDiagnosticEvent, operation: Long = 0, number: Int = 0,
        stage: OfflineDiagnosticStage? = null, elapsedMs: Long? = null, previousMs: Long? = null,
        count: Int = -1, total: Int = -1, error: Exception? = null, waitMs: Long? = null) {
        // Contain sink failures, including cancellation from the diagnostic path alone. Never render a Throwable.
        try {
            sink(buildString {
                append("event=").append(event.name)
                if (operation > 0) append(" op=").append(operation)
                if (number > 0) append(" attempt=").append(number)
                if (stage != null) append(" stage=").append(stage.name)
                if (elapsedMs != null) append(" elapsed_ms=").append(elapsedMs)
                if (previousMs != null) append(" previous_ms=").append(previousMs)
                if (count >= 0) append(" count=").append(count)
                if (total >= 0) append(" total=").append(total)
                if (error != null) append(" failure=").append(failureCategory(error))
                if (error is OfflineException && error.status in 100..599) append(" http=").append(error.status)
                if (waitMs != null) append(" wait_ms=").append(waitMs)
            })
        } catch (_: Throwable) { /* Diagnostics cannot own or interrupt work. */ }
    }

    private fun failureCategory(error: Exception): String = when (error) {
        is OfflineException -> error.failure.name
        is AuthFailure -> when (error.kind) {
            AuthFailure.Kind.UNAUTHORIZED -> "AUTH_UNAUTHORIZED"
            AuthFailure.Kind.UNAVAILABLE -> "AUTH_UNAVAILABLE"
            AuthFailure.Kind.INVALID_RESPONSE -> "AUTH_INVALID_RESPONSE"
        }
        is TransientAudioLoadFailure -> "AUDIO_INTERRUPTED"
        is SocketTimeoutException -> "IO_TIMEOUT"
        is IOException -> "IO"
        is SecurityException -> "ACCESS"
        is IllegalArgumentException -> "REJECTED"
        is IllegalStateException -> "STATE"
        else -> "OTHER"
    }
}

/** Owned by one attempt coroutine. Counters are generated in memory and never derived from IDs. */
class OfflineAttemptTrace internal constructor(private val diagnostics: OfflineDiagnostics,
    private val operation: Long, private val number: Int) {
    private val started = System.nanoTime()
    private var stageStart = started
    private var stage: OfflineDiagnosticStage? = null
    private var media = 0
    private var progress = 0

    init { record(OfflineDiagnosticEvent.ATTEMPT, started) }

    fun beginMedia() { media++ }

    fun step(next: OfflineDiagnosticStage, count: Int = -1, total: Int = -1) {
        val now = System.nanoTime()
        val previous = millis(now - stageStart)
        stage = next
        stageStart = now
        val report = when (next) {
            OfflineDiagnosticStage.MEDIA_LOOKUP, OfflineDiagnosticStage.MEDIA_JOIN, OfflineDiagnosticStage.MEDIA_REUSE,
            OfflineDiagnosticStage.MEDIA_PREPARE, OfflineDiagnosticStage.MEDIA_SETUP, OfflineDiagnosticStage.MEDIA_OPEN,
            OfflineDiagnosticStage.MEDIA_BODY, OfflineDiagnosticStage.MEDIA_WRITE_WAIT, OfflineDiagnosticStage.MEDIA_WRITE,
            OfflineDiagnosticStage.MEDIA_VERIFY, OfflineDiagnosticStage.MEDIA_PUBLISH, OfflineDiagnosticStage.MEDIA_RETAIN -> sampled(media)
            OfflineDiagnosticStage.PROGRESS -> sampled(++progress) || count == total
            else -> true
        }
        if (report) record(OfflineDiagnosticEvent.STEP, now, previous = previous, count = count, total = total)
    }

    fun metadataRetry(error: OfflineException, waitMs: Long) {
        val now = System.nanoTime()
        if (stage != OfflineDiagnosticStage.MEDIA_PREPARE || sampled(media)) {
            record(OfflineDiagnosticEvent.METADATA_RETRY, now, previous = millis(now - stageStart), error = error, waitMs = waitMs)
        }
        stage = OfflineDiagnosticStage.METADATA_BACKOFF
        stageStart = now
    }

    fun backoff(waitMs: Long) {
        val now = System.nanoTime()
        stage = OfflineDiagnosticStage.BACKOFF
        stageStart = now
        record(OfflineDiagnosticEvent.BACKOFF, now, waitMs = waitMs)
    }

    fun outcome(event: OfflineDiagnosticEvent, error: Exception? = null, waitMs: Long? = null) {
        val now = System.nanoTime()
        record(event, now, previous = millis(now - stageStart), error = error, waitMs = waitMs)
    }

    private fun record(event: OfflineDiagnosticEvent, now: Long, previous: Long? = null,
        count: Int = -1, total: Int = -1, error: Exception? = null, waitMs: Long? = null) =
        diagnostics.event(event, operation, number, stage, millis(now - started), previous, count, total, error, waitMs)

    // Complete traces for small playlists; logarithmic detail for large ones (failures are never sampled).
    private fun sampled(n: Int) = n <= 8 || (n and (n - 1)) == 0
    private fun millis(nanos: Long) = (nanos / 1_000_000).coerceAtLeast(0)
}
