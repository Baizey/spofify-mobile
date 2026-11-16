package dev.spofify.mobile.core.palette

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.http.FeatureResponse
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Account/session-fenced online client. No cache, write queue, reconciliation, or automatic replay. */
class PaletteClient(
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
) : PaletteApi {
    private val path = listOf("api", "me", "palette")

    override suspend fun read(): AccountPalette = perform(write = false) {
        decode(sessions.get(identity, path), write = false)
    }

    override suspend fun choose(id: PaletteId, expectedRevision: Long): AccountPalette = perform(write = true) {
        if (expectedRevision !in 0 until PaletteParser.MAX_REVISION) throw PaletteException(PaletteFailure.INVALID)
        val body = Json.encodeToString(Choice(id.id, expectedRevision, 1))
        if (body.toByteArray(Charsets.UTF_8).size > 1024) throw PaletteException(PaletteFailure.INVALID)
        val result = decode(sessions.mutate(identity, path, "PUT", body), write = true)
        if (result.paletteId != id || result.revision != expectedRevision + 1)
            throw PaletteException(PaletteFailure.INVALID_RESPONSE, outcomeUncertain = true)
        result
    }

    private fun decode(response: FeatureResponse, write: Boolean): AccountPalette {
        if (response.status != 200) {
            val code = if (isJson(response)) PaletteParser.errorCode(response.bytes) else null
            val failure = when (response.status) {
                400, 405, 413, 415 -> PaletteFailure.INVALID
                401, 403, 421 -> PaletteFailure.UNAUTHORIZED
                409 -> when (code) {
                    "PALETTE_REVISION_CONFLICT" -> PaletteFailure.CONFLICT
                    "PALETTE_CATALOG_CONFLICT" -> PaletteFailure.CATALOG
                    else -> PaletteFailure.INVALID_RESPONSE
                }
                in 200..299 -> PaletteFailure.INVALID_RESPONSE
                else -> PaletteFailure.UNAVAILABLE
            }
            throw PaletteException(failure, outcomeUncertain = write &&
                failure in setOf(PaletteFailure.UNAVAILABLE, PaletteFailure.INVALID_RESPONSE))
        }
        if (!isJson(response)) throw PaletteException(PaletteFailure.INVALID_RESPONSE, outcomeUncertain = write)
        return try {
            PaletteParser.parse(response.bytes, identity.accountId)
        } catch (failure: PaletteException) {
            throw PaletteException(failure.failure, outcomeUncertain = write)
        }
    }

    private fun isJson(response: FeatureResponse): Boolean = response.mediaType
        ?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true

    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!sessions.isCurrent(identity)) throw CancellationException("Session retired")
    }

    private suspend fun <T> perform(write: Boolean, action: suspend () -> T): T = withContext(Dispatchers.Default) {
        checkCurrent()
        try {
            val result = action()
            checkCurrent()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PaletteException) {
            checkCurrent()
            throw failure
        } catch (failure: AuthFailure) {
            checkCurrent()
            val kind = when (failure.kind) {
                AuthFailure.Kind.UNAUTHORIZED -> PaletteFailure.UNAUTHORIZED
                AuthFailure.Kind.INVALID_RESPONSE -> PaletteFailure.INVALID_RESPONSE
                AuthFailure.Kind.UNAVAILABLE -> PaletteFailure.UNAVAILABLE
            }
            throw PaletteException(kind, outcomeUncertain = write && kind != PaletteFailure.UNAUTHORIZED)
        } catch (_: IOException) {
            checkCurrent()
            throw PaletteException(PaletteFailure.UNAVAILABLE, outcomeUncertain = write)
        } catch (_: Exception) {
            checkCurrent()
            throw PaletteException(PaletteFailure.INVALID_RESPONSE, outcomeUncertain = write)
        }
    }

    @Serializable private class Choice(val paletteId: String, val expectedRevision: Long, val catalogVersion: Int)
}
