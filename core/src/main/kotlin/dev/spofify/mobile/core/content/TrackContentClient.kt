package dev.spofify.mobile.core.content

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.auth.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Authenticated content reads only. No fetching/import jobs, retry loop, or storage. */
class TrackContentClient(
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
) : TrackContentApi {
    override val active = sessions.state.map { (it as? AuthState.Authenticated)?.identity == identity }
        .distinctUntilChanged()
    override val isCurrent: Boolean get() = sessions.isCurrent(identity)

    override suspend fun waveform(trackId: String): TrackWaveform = read(trackId, "waveform") {
        TrackContentParser.waveform(it, trackId)
    }

    override suspend fun lyrics(trackId: String): TrackLyrics = read(trackId, "lyrics") {
        TrackContentParser.lyrics(it, trackId)
    }

    private suspend fun <T> read(trackId: String, resource: String, parse: (ByteArray) -> T): T =
        withContext(Dispatchers.Default) {
            try {
                TrackContentParser.validTrackId(trackId)
                val response = sessions.get(identity, listOf("api", "library", "tracks", trackId, resource))
                if (response.status != 200) throw TrackContentException(
                    if (response.status == 404) TrackContentFailure.NOT_FOUND else TrackContentFailure.UNAVAILABLE)
                val result = parse(response.bytes)
                ensureActive()
                if (!isCurrent) throw CancellationException("Session retired")
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: TrackContentException) {
                throw failure
            } catch (_: Exception) {
                throw TrackContentException(TrackContentFailure.UNAVAILABLE)
            }
        }
}
