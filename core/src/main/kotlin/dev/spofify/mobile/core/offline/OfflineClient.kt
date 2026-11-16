package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.FeatureResponse
import dev.spofify.mobile.core.personal.PersonalTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Complete-read staging is private to each call. No cross-account cache, transfer, storage or replay. */
class OfflineClient(
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
    private val limits: OfflineReadLimits = OfflineReadLimits(),
    private val pageSize: Int = 40,
) : OfflineApi {
    private val json = Json { encodeDefaults = true }
    private val root = listOf("api", "me", "offline", "v1")

    init { require(pageSize in 1..100) }

    override suspend fun manifest(collection: OfflineCollectionRef): OfflineManifest = perform {
        request(OfflineParser.validReference(collection))
        val path = root + listOf(collection.kind.path, collection.id, "manifest")
        val rows = mutableListOf<OfflineOccurrence>()
        val occurrences = hashSetOf<String>()
        val tracks = hashMapOf<String, PersonalTrack>()
        val cursors = hashSetOf<String>()
        var first: OfflineParser.Page? = null
        var cursor: String? = null
        var usedBytes = 0L
        do {
            checkCurrent()
            val query = listOf("limit" to pageSize.toString()) + cursor?.let { listOf("cursor" to it) }.orEmpty()
            val response = sessions.get(identity, path, query)
            expect(response)
            if (response.bytes.size.toLong() > limits.maxManifestBytes - usedBytes)
                throw OfflineException(OfflineFailure.LIMIT_EXCEEDED)
            usedBytes += response.bytes.size
            val page = OfflineParser.page(response.bytes, collection, rows.size, pageSize, first)
            if (page.total > limits.maxOccurrences) throw OfflineException(OfflineFailure.LIMIT_EXCEEDED)
            if (first == null) first = page
            for (row in page.items) {
                require(occurrences.add(row.occurrenceId))
                val previous = tracks.put(row.track.id, row.track)
                if (previous != null) {
                    require(collection.kind == OfflineCollectionKind.PLAYLIST)
                    if (previous != row.track) throw OfflineException(OfflineFailure.STALE_MANIFEST)
                }
                rows.add(row)
            }
            cursor = page.nextCursor
            if (cursor != null) require(cursors.add(cursor))
        } while (cursor != null)
        checkCurrent()
        val captured = requireNotNull(first)
        require(rows.size.toLong() == captured.total)
        OfflineManifest(captured.collection, captured.fence, rows.toList(), captured.total, captured.recordedAudioTotal)
    }

    override suspend fun prepareMedia(trackId: String): OfflineMediaDescriptor = perform {
        request(OfflineParser.validSegment(trackId))
        val response = sessions.get(identity, root + listOf("tracks", trackId, "media"))
        expect(response)
        OfflineParser.media(response.bytes, trackId)
    }

    override suspend fun submitReceipt(receipt: OfflinePlayReceipt): OfflineReceiptAck = perform {
        // All six fields are value-copied val properties. The caller/outbox, never this client,
        // generates IDs and owns identical retries after an uncertain commit or lost ACK.
        val body = json.encodeToString(receipt)
        request(body.toByteArray(Charsets.UTF_8).size <= 65_536)
        val response = sessions.mutate(identity, root + "play-receipts", "POST", body)
        expect(response, receipt = true)
        OfflineParser.ack(response.bytes, receipt)
    }

    private suspend fun <T> perform(action: suspend () -> T): T = withContext(Dispatchers.Default) {
        try {
            checkCurrent()
            val result = action()
            checkCurrent()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OfflineException) {
            checkCurrent()
            throw failure
        } catch (failure: AuthFailure) {
            checkCurrent()
            throw OfflineException(when (failure.kind) {
                AuthFailure.Kind.UNAUTHORIZED -> OfflineFailure.UNAUTHORIZED
                AuthFailure.Kind.INVALID_RESPONSE -> OfflineFailure.INVALID_RESPONSE
                AuthFailure.Kind.UNAVAILABLE -> OfflineFailure.UNAVAILABLE
            })
        } catch (_: java.io.IOException) {
            checkCurrent()
            throw OfflineException(OfflineFailure.UNAVAILABLE)
        } catch (_: Exception) {
            checkCurrent()
            throw OfflineException(OfflineFailure.INVALID_RESPONSE)
        }
    }

    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!sessions.isCurrent(identity)) throw CancellationException("Session retired")
    }

    private fun request(valid: Boolean) {
        if (!valid) throw OfflineException(OfflineFailure.INVALID_REQUEST)
    }

    private fun expect(response: FeatureResponse, receipt: Boolean = false) {
        if (response.status == 200) {
            require(response.mediaType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true)
            return
        }
        val code = OfflineParser.errorCode(response.bytes)
        // GET's existing ReadStatus interceptor drops 408/421/503 error bodies. Unknown 409
        // is conservatively conflict/reprepare, never an invented STALE_EVALUATION diagnosis.
        val failure = when (response.status) {
            in 200..299 -> OfflineFailure.INVALID_RESPONSE
            400 -> if (code == "INVALID_CURSOR") OfflineFailure.INVALID_CURSOR else OfflineFailure.INVALID_REQUEST
            401 -> OfflineFailure.UNAUTHORIZED
            403 -> OfflineFailure.FORBIDDEN
            404 -> if (receipt) OfflineFailure.UNSUPPORTED else OfflineFailure.NOT_FOUND
            405 -> OfflineFailure.UNSUPPORTED
            409 -> when {
                receipt -> OfflineFailure.RECEIPT_CONFLICT
                code == "STALE_EVALUATION" -> OfflineFailure.STALE_MANIFEST
                code == "SOURCE_UNAVAILABLE" -> OfflineFailure.SOURCE_UNAVAILABLE
                code == "MEDIA_CHANGED" -> OfflineFailure.MEDIA_CHANGED
                else -> OfflineFailure.CONFLICT_REPREPARE
            }
            422 -> if (receipt) OfflineFailure.CLOCK_ERROR else OfflineFailure.INVALID_REQUEST
            429 -> OfflineFailure.BUSY
            503 -> if (code in setOf("EVALUATION_TIMEOUT", "MEDIA_TIMEOUT")) OfflineFailure.TIMEOUT else OfflineFailure.UNAVAILABLE
            else -> OfflineFailure.UNAVAILABLE
        }
        throw OfflineException(failure, response.status)
    }
}
