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
import kotlinx.serialization.json.Json

/** Likes remain HTTP. Playlists and history are owned by the account socket. */
class PersonalLibraryClient(
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
) : PersonalLibraryApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val likes = listOf("api", "me", "likes")

    override suspend fun likes(limit: Int, offset: Int): PersonalLikesPage = perform {
        request(limit in 1..100 && offset >= 0)
        decode<PersonalLikesPage>(sessions.get(identity, likes, listOf("limit" to limit.toString(), "offset" to offset.toString()))) { page ->
            check(page.limit == limit && page.offset == offset && page.items.size <= limit)
            check(page.total >= 0 && page.availableTotal in 0..page.total)
            check(if (page.items.isEmpty()) offset.toLong() >= page.total else offset.toLong() + page.items.size <= page.total)
            page.items.forEach { check(it.track.hasAudio && validPlaylistTrack(it.track)); Instant.parse(it.likedAt) }
        }
    }

    override suspend fun likeStatus(trackIds: List<String>): List<PersonalLikeStatus> = perform {
        request(trackIds.size <= 100 && trackIds.all(::validId))
        decode<LikeStatuses>(sessions.mutate(identity, likes + "status", "POST", json.encodeToString(LikeStatusRequest(trackIds)))) {
            check(it.items.map(PersonalLikeStatus::trackId) == trackIds)
        }.items
    }

    override suspend fun setLike(trackId: String, liked: Boolean): PersonalLikeStatus = perform {
        request(validId(trackId))
        decode<PersonalLikeStatus>(sessions.mutate(identity, likes, "PUT", json.encodeToString(LikeRequest(trackId, liked)))) {
            check(it.trackId == trackId && it.liked == liked)
        }
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

    private inline fun <reified T> decode(response: FeatureResponse, validate: (T) -> Unit): T {
        if (response.status != 200) {
            val code = if (response.status in 200..299) PersonalLibraryErrorCode.INVALID_RESPONSE else PersonalLibraryErrorCode.HTTP_ERROR
            throw PersonalLibraryException(response.status, code)
        }
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

    private fun request(valid: Boolean) {
        if (!valid) throw PersonalLibraryException(400, PersonalLibraryErrorCode.INVALID_REQUEST)
    }
    private fun validId(value: String) = value.isNotEmpty() && value != "." && value != ".." && value.trim() == value &&
        value.toByteArray(Charsets.UTF_8).size <= 512 && value.none(Char::isISOControl) &&
        value.codePoints().noneMatch { it in 0xD800..0xDFFF }

    @Serializable private data class LikeRequest(val trackId: String, val liked: Boolean)
    @Serializable private data class LikeStatusRequest(val trackIds: List<String>)
    @Serializable private data class LikeStatuses(val items: List<PersonalLikeStatus>)
}
