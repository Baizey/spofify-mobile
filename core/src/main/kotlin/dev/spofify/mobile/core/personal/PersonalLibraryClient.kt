package dev.spofify.mobile.core.personal

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.FeatureResponse
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Account-bound online personal library. No read aggregation, write retry, or local membership cache. */
class PersonalLibraryClient(
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
) : PersonalLibraryApi {
    // Positive DTOs deliberately ignore unrelated fields but never infer hasAudio from a path.
    private val json = Json { ignoreUnknownKeys = true }
    private val root = listOf("api", "me")

    override suspend fun playlists(limit: Int, offset: Int, kind: PersonalPlaylistKind?): PersonalPage<PersonalPlaylistSummary> = perform {
        val query = pageQuery(limit, offset) + if (kind == null) emptyList() else listOf("kind" to kind.name.lowercase())
        decode<PersonalPage<PersonalPlaylistSummary>>(sessions.get(identity, root + "playlists", query)) { page ->
            validatePage(page.items.size, page.total, page.limit, page.offset, limit, offset)
            page.items.forEach { summary ->
                validateSummary(summary)
                check(kind == null || summary.kind == kind)
            }
            check(page.items.map { it.id }.toSet().size == page.items.size)
        }
    }

    override suspend fun playlist(id: String): PersonalPlaylistSummary = perform {
        decode<PersonalPlaylistSummary>(sessions.get(identity, playlistPath(id))) {
            validateSummary(it)
            check(it.id == id)
        }
    }

    override suspend fun manualEntries(id: String, limit: Int, offset: Int): PersonalPage<PersonalManualEntry> = perform {
        decode<PersonalPage<PersonalManualEntry>>(sessions.get(identity, playlistPath(id) + "entries", pageQuery(limit, offset))) { page ->
            validatePage(page.items.size, page.total, page.limit, page.offset, limit, offset)
            page.items.forEach {
                check(validId(it.id) && safeNumber(it.position))
                validateTrack(it.track, available = true)
            }
            check(page.items.map { it.id }.toSet().size == page.items.size)
            check(page.items.zipWithNext().all { (first, second) -> first.position < second.position })
        }
    }

    override suspend fun derivedEntries(id: String, limit: Int, cursor: String?): PersonalDerivedPage = perform {
        request(limit in 1..100)
        request(cursor == null || asciiToken(cursor, 4096))
        val query = listOf("limit" to limit.toString()) + if (cursor == null) emptyList() else listOf("cursor" to cursor)
        decode<PersonalDerivedPage>(sessions.get(identity, playlistPath(id) + "entries", query)) { page ->
            check(page.limit == limit && page.items.size <= limit)
            check(safeNumber(page.total) && safeNumber(page.availableTotal) && page.availableTotal <= page.total)
            check(page.revision == null || validRevision(page.revision))
            validateEvaluationCode(page.evaluationErrorCode)
            check(page.evaluationState != null)
            check(page.complete == (page.evaluationState == PersonalEvaluationState.READY))
            if (page.complete) {
                check(asciiToken(page.fence, 4096))
                check(page.nextCursor == null || asciiToken(page.nextCursor, 4096))
                check(page.nextCursor == null || page.items.isNotEmpty())
            } else {
                check(page.fence.isEmpty() && page.nextCursor == null)
            }
            page.items.forEach {
                check(asciiToken(it.occurrenceId, 4096) && safeNumber(it.position))
                check(if (page.complete) it.selectionToken?.let { token -> asciiToken(token, 8192) } == true else it.selectionToken == null)
                validateTrack(it.track, available = true)
            }
            check(page.items.map { it.occurrenceId }.toSet().size == page.items.size)
            check(page.items.zipWithNext().all { (first, second) -> first.position < second.position })
        }
    }

    override suspend fun likes(limit: Int, offset: Int): PersonalLikesPage = perform {
        decode<PersonalLikesPage>(sessions.get(identity, root + "likes", pageQuery(limit, offset))) { page ->
            validatePage(page.items.size, page.total, page.limit, page.offset, limit, offset)
            check(safeNumber(page.availableTotal) && page.availableTotal <= page.total)
            page.items.forEach {
                validateTrack(it.track, available = true)
                validateTimestamp(it.likedAt)
            }
        }
    }

    override suspend fun likeStatus(trackIds: List<String>): List<PersonalLikeStatus> = perform {
        request(trackIds.size <= 100 && trackIds.all(::validId))
        val response = mutate(root + listOf("likes", "status"), "POST", LikeStatusRequest(trackIds))
        decode<LikeStatuses>(response) { statuses ->
            check(statuses.items.map { it.trackId } == trackIds)
        }.items
    }

    override suspend fun setLike(trackId: String, liked: Boolean): PersonalLikeStatus = perform {
        request(validId(trackId))
        decode<PersonalLikeStatus>(mutate(root + "likes", "PUT", LikeRequest(trackId, liked))) {
            check(it.trackId == trackId && it.liked == liked)
        }
    }

    override suspend fun toggleLike(trackId: String, currentlyLiked: Boolean): PersonalLikeStatus = setLike(trackId, !currentlyLiked)

    override suspend fun createManualPlaylist(name: String, description: String): PersonalPlaylistSummary = perform {
        val metadata = metadata(name, description)
        decode<PersonalPlaylistSummary>(mutate(root + "playlists", "POST", metadata), expectedStatus = 201) {
            validateSummary(it)
            check(it.kind == PersonalPlaylistKind.MANUAL)
        }
    }

    override suspend fun renamePlaylist(id: String, name: String, description: String, expectedRevision: Long): PersonalPlaylistSummary = perform {
        val metadata = metadata(name, description)
        request(validRevision(expectedRevision))
        decode<PersonalPlaylistSummary>(mutate(playlistPath(id), "PATCH", MetadataUpdate(metadata.name, metadata.description, expectedRevision))) {
            validateSummary(it)
            check(it.id == id && it.revision > expectedRevision)
        }
    }

    override suspend fun deletePlaylist(id: String, expectedRevision: Long): Unit = perform {
        request(validRevision(expectedRevision))
        val response = mutate(playlistPath(id), "DELETE", RevisionRequest(expectedRevision))
        expectStatus(response, 204)
        checkEmpty(response)
    }

    override suspend fun addTrack(id: String, trackId: String, expectedRevision: Long): PersonalPlaylistSummary = perform {
        request(validId(trackId) && validRevision(expectedRevision))
        decode<PersonalPlaylistSummary>(mutate(playlistPath(id) + "entries", "POST", AddTrackRequest(trackId, expectedRevision))) {
            validateManualMutation(it, id, expectedRevision)
        }
    }

    override suspend fun removeEntry(id: String, entryId: String, expectedRevision: Long): PersonalPlaylistSummary = perform {
        request(validId(entryId) && validRevision(expectedRevision))
        decode<PersonalPlaylistSummary>(mutate(playlistPath(id) + listOf("entries", entryId), "DELETE", RevisionRequest(expectedRevision))) {
            validateManualMutation(it, id, expectedRevision)
        }
    }

    override suspend fun reorderEntries(id: String, entryIds: List<String>, expectedRevision: Long): PersonalPlaylistSummary = perform {
        request(validRevision(expectedRevision) && entryIds.all(::validId) && entryIds.toSet().size == entryIds.size)
        decode<PersonalPlaylistSummary>(mutate(playlistPath(id) + "order", "PUT", OrderRequest(entryIds, expectedRevision))) {
            validateManualMutation(it, id, expectedRevision)
        }
    }

    override suspend fun recent(limit: Int): PersonalRecent = perform {
        request(limit in 1..100)
        val page = decode<PersonalPage<PersonalHistoryEntry>>(sessions.get(identity, root + "recent", listOf("limit" to limit.toString()))) {
            validatePage(it.items.size, it.total, it.limit, it.offset, limit, 0)
            it.items.forEach(::validateHistory)
        }
        PersonalRecent(page.items, page.total, page.limit)
    }

    override suspend fun history(limit: Int, offset: Int): PersonalPage<PersonalHistoryEntry> = perform {
        decode<PersonalPage<PersonalHistoryEntry>>(sessions.get(identity, root + "history", pageQuery(limit, offset))) {
            validatePage(it.items.size, it.total, it.limit, it.offset, limit, offset)
            it.items.forEach(::validateHistory)
        }
    }

    private fun playlistPath(id: String): List<String> {
        request(validId(id))
        return root + listOf("playlists", id)
    }

    private fun pageQuery(limit: Int, offset: Int): List<Pair<String, String>> {
        request(limit in 1..100 && offset >= 0)
        return listOf("limit" to limit.toString(), "offset" to offset.toString())
    }

    private fun metadata(name: String, description: String): Metadata {
        val trimmed = name.trim()
        request(validUnicode(trimmed) && trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 100 && trimmed.none(Char::isISOControl))
        request(validUnicode(description) && description.codePointCount(0, description.length) <= 500 &&
            description.none { it.isISOControl() && it != '\n' && it != '\t' })
        return Metadata(trimmed, description)
    }

    private suspend inline fun <reified T> mutate(path: List<String>, method: String, body: T): FeatureResponse {
        val encoded = json.encodeToString(body)
        request(encoded.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        return sessions.mutate(identity, path, method, encoded)
    }

    private suspend fun <T> perform(action: suspend () -> T): T = withContext(Dispatchers.Default) {
        try {
            val result = action()
            ensureActive()
            if (!sessions.isCurrent(identity)) throw CancellationException("Session retired")
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PersonalLibraryException) {
            throw failure
        } catch (failure: AuthFailure) {
            throw when (failure.kind) {
                AuthFailure.Kind.UNAUTHORIZED -> PersonalLibraryException(401, PersonalLibraryErrorCode.HTTP_ERROR)
                AuthFailure.Kind.INVALID_RESPONSE -> PersonalLibraryException(0, PersonalLibraryErrorCode.INVALID_RESPONSE)
                AuthFailure.Kind.UNAVAILABLE -> PersonalLibraryException(0, PersonalLibraryErrorCode.CONNECTION_FAILED)
            }
        } catch (_: IOException) {
            throw PersonalLibraryException(0, PersonalLibraryErrorCode.CONNECTION_FAILED)
        } catch (_: Exception) {
            throw PersonalLibraryException(0, PersonalLibraryErrorCode.INVALID_RESPONSE)
        }
    }

    private inline fun <reified T> decode(response: FeatureResponse, expectedStatus: Int = 200, validate: (T) -> Unit): T {
        expectStatus(response, expectedStatus)
        try {
            check(response.mediaType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true)
            check(response.bytes.size <= 2 * 1024 * 1024)
            val value = json.decodeFromString<T>(response.bytes.decodeToString(throwOnInvalidSequence = true))
            validate(value)
            return value
        } catch (_: Exception) {
            throw PersonalLibraryException(response.status, PersonalLibraryErrorCode.INVALID_RESPONSE)
        }
    }

    private fun expectStatus(response: FeatureResponse, expected: Int) {
        if (response.status == expected) return
        if (response.status in 200..299) throw PersonalLibraryException(response.status, PersonalLibraryErrorCode.INVALID_RESPONSE)
        var code = PersonalLibraryErrorCode.HTTP_ERROR
        if (response.bytes.size <= 4096 && response.mediaType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true) {
            try {
                val candidate = json.decodeFromString<ErrorEnvelope>(response.bytes.decodeToString(throwOnInvalidSequence = true)).error.code
                code = knownErrorStatuses.entries.firstOrNull { it.key.name == candidate && it.value == response.status }?.key ?: code
            } catch (_: Exception) {
                // Error bodies and diagnostic messages are never returned or attached as causes.
            }
        }
        throw PersonalLibraryException(response.status, code)
    }

    private fun checkEmpty(response: FeatureResponse) {
        if (response.bytes.isNotEmpty()) throw PersonalLibraryException(response.status, PersonalLibraryErrorCode.INVALID_RESPONSE)
    }

    private fun validatePage(size: Int, total: Long, limit: Int, offset: Int, requestedLimit: Int, requestedOffset: Int) {
        check(safeNumber(total) && limit == requestedLimit && offset == requestedOffset && size <= limit)
        check(size == 0 || offset.toLong() + size <= total)
        check(size > 0 || offset.toLong() >= total)
    }

    private fun validateSummary(summary: PersonalPlaylistSummary) {
        check(validId(summary.id) && validRevision(summary.revision))
        check(summary.trackCount == null || safeNumber(summary.trackCount))
        check(summary.availableCount == null || safeNumber(summary.availableCount))
        if (summary.kind == PersonalPlaylistKind.DERIVED && summary.countState == PersonalCountState.PENDING) {
            check(summary.trackCount == null && summary.availableCount == null)
        } else {
            check(summary.trackCount != null && summary.availableCount != null && summary.availableCount <= summary.trackCount)
        }
        if (summary.kind == PersonalPlaylistKind.MANUAL) check(summary.countState == PersonalCountState.EXACT)
        else check(summary.evaluationState != null)
        validateEvaluationCode(summary.evaluationErrorCode)
        validateTimestamp(summary.createdAt)
        validateTimestamp(summary.updatedAt)
    }

    private fun validateManualMutation(summary: PersonalPlaylistSummary, id: String, previousRevision: Long) {
        validateSummary(summary)
        check(summary.id == id && summary.kind == PersonalPlaylistKind.MANUAL && summary.revision > previousRevision)
    }

    private fun validateTrack(track: PersonalTrack, available: Boolean = false) {
        check(track.kind == "track" && validId(track.id))
        check(!available || track.hasAudio)
        check(safeNumber(track.durationMs) && track.discNumber >= 0 && track.trackNumber >= 0)
        check(safeNumber(track.coverage.tracks) && safeNumber(track.coverage.inLibrary) && safeNumber(track.coverage.albums))
        check(track.coverage.inLibrary <= track.coverage.tracks)
    }

    private fun validateHistory(entry: PersonalHistoryEntry) {
        check(validId(entry.id))
        validateTrack(entry.track)
        validateTimestamp(entry.playedAt)
        entry.receivedAt?.let(::validateTimestamp)
        check(entry.origin == null || entry.origin == "" || entry.origin == "offline")
    }

    private fun validateEvaluationCode(code: String?) {
        check(code == null || code.isEmpty() || knownErrorStatuses.keys.any { it.name == code })
    }

    private fun validateTimestamp(value: String) { Instant.parse(value) }
    private fun request(valid: Boolean) {
        if (!valid) throw PersonalLibraryException(400, PersonalLibraryErrorCode.INVALID_REQUEST)
    }

    @Serializable private data class Metadata(val name: String, val description: String)
    @Serializable private data class MetadataUpdate(val name: String, val description: String, val expectedRevision: Long)
    @Serializable private data class RevisionRequest(val expectedRevision: Long)
    @Serializable private data class AddTrackRequest(val trackId: String, val expectedRevision: Long)
    @Serializable private data class OrderRequest(val entryIds: List<String>, val expectedRevision: Long)
    @Serializable private data class LikeRequest(val trackId: String, val liked: Boolean)
    @Serializable private data class LikeStatusRequest(val trackIds: List<String>)
    @Serializable private data class LikeStatuses(val items: List<PersonalLikeStatus>)
    @Serializable private data class ErrorEnvelope(val error: ErrorCode)
    @Serializable private data class ErrorCode(val code: String)

    private companion object {
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        fun safeNumber(value: Long) = value in 0..MAX_SAFE_INTEGER
        fun validRevision(value: Long) = value in 1..MAX_SAFE_INTEGER
        fun asciiToken(value: String, max: Int) = value.length in 1..max && value.all { it in '!'..'~' }
        fun validUnicode(value: String) = value.codePoints().noneMatch { it in 0xD800..0xDFFF }
        fun validId(value: String) = value.isNotEmpty() && value != "." && value != ".." && value.trim() == value &&
            value.toByteArray(Charsets.UTF_8).size <= 512 && validUnicode(value) && value.none(Char::isISOControl)

        val knownErrorStatuses = mapOf(
            PersonalLibraryErrorCode.INVALID_REQUEST to 400,
            PersonalLibraryErrorCode.INVALID_CURSOR to 400,
            PersonalLibraryErrorCode.PLAYLIST_NOT_FOUND to 404,
            PersonalLibraryErrorCode.REFERENCE_NOT_FOUND to 404,
            PersonalLibraryErrorCode.COLLECTION_NOT_FOUND to 404,
            PersonalLibraryErrorCode.REVISION_CONFLICT to 409,
            PersonalLibraryErrorCode.STALE_EVALUATION to 409,
            PersonalLibraryErrorCode.PLAYLIST_KIND_MISMATCH to 409,
            PersonalLibraryErrorCode.SOURCE_REFERENCED to 409,
            PersonalLibraryErrorCode.SOURCE_UNAVAILABLE to 409,
            PersonalLibraryErrorCode.RECIPE_CYCLE to 422,
            PersonalLibraryErrorCode.RECIPE_LIMIT_EXCEEDED to 422,
            PersonalLibraryErrorCode.COPY_LIMIT_EXCEEDED to 422,
            PersonalLibraryErrorCode.PLAYLIST_STORAGE_EXCEEDED to 422,
            PersonalLibraryErrorCode.PLAYLIST_QUOTA_EXCEEDED to 422,
            PersonalLibraryErrorCode.PUBLICATION_LIMIT to 422,
            PersonalLibraryErrorCode.EVALUATION_BUSY to 429,
            PersonalLibraryErrorCode.EVALUATION_TIMEOUT to 503,
            PersonalLibraryErrorCode.EVALUATION_FAILED to 500,
        )
    }
}
