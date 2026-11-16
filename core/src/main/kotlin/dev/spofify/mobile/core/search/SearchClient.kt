package dev.spofify.mobile.core.search

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.FeatureResponse
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.library.validSearchQuery
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One account-fenced GET per call; no legacy fallback, local ranking, cache, or automatic retry. */
class SearchClient(
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
) : SearchApi {
    override suspend fun search(query: String, kind: MusicKind?, limit: Int): SearchPage = perform {
        request(query.isNotBlank() && validSearchQuery(query) && limit in 1..100)
        val params = listOf("query" to query, "limit" to limit.toString()) +
            kind?.let { listOf("kind" to it.name.lowercase(Locale.ROOT)) }.orEmpty()
        decode(sessions.get(identity, listOf("search"), params, preserveUnavailableBody = true), limit, kind)
    }

    override suspend fun next(cursor: String): SearchPage = perform {
        request(SearchParser.validCursor(cursor))
        decode(sessions.get(identity, listOf("search"), listOf("cursor" to cursor), preserveUnavailableBody = true), cursor = cursor)
    }

    private fun decode(response: FeatureResponse, limit: Int? = null, kind: MusicKind? = null, cursor: String? = null): SearchPage {
        if (response.status != 200) {
            val code = if (isJson(response)) SearchParser.errorCode(response.bytes, response.status) else null
            throw SearchException(code ?: if (response.status in 200..299) SearchErrorCode.INVALID_RESPONSE
                else SearchErrorCode.HTTP_ERROR, response.status)
        }
        return try {
            require(isJson(response))
            SearchParser.page(response.bytes, limit, kind, cursor)
        } catch (_: Exception) {
            throw SearchException(SearchErrorCode.INVALID_RESPONSE, response.status)
        }
    }

    private fun isJson(response: FeatureResponse): Boolean = response.mediaType
        ?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true

    private fun request(valid: Boolean) {
        if (!valid) throw SearchException(SearchErrorCode.INVALID_SEARCH_REQUEST)
    }

    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!sessions.isCurrent(identity)) throw CancellationException("Session retired")
    }

    private suspend fun <T> perform(action: suspend () -> T): T = withContext(Dispatchers.Default) {
        try {
            checkCurrent()
            val result = action()
            checkCurrent()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SearchException) {
            checkCurrent()
            throw failure
        } catch (failure: AuthFailure) {
            checkCurrent()
            throw when (failure.kind) {
                AuthFailure.Kind.UNAUTHORIZED -> SearchException(SearchErrorCode.HTTP_ERROR, 401)
                AuthFailure.Kind.UNAVAILABLE -> SearchException(SearchErrorCode.CONNECTION_FAILED)
                AuthFailure.Kind.INVALID_RESPONSE -> SearchException(SearchErrorCode.INVALID_RESPONSE)
            }
        } catch (_: IOException) {
            checkCurrent()
            throw SearchException(SearchErrorCode.CONNECTION_FAILED)
        } catch (_: Exception) {
            checkCurrent()
            throw SearchException(SearchErrorCode.INVALID_RESPONSE)
        }
    }
}
