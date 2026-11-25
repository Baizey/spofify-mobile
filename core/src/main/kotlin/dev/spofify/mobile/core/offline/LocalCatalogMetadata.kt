package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.content.LyricReason
import dev.spofify.mobile.core.content.LyricsState
import dev.spofify.mobile.core.content.TrackLyrics
import dev.spofify.mobile.core.library.Artist
import dev.spofify.mobile.core.library.MusicArtistWire
import dev.spofify.mobile.core.library.MusicCoverageWire
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicItemWire
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.library.projectMusicItem
import dev.spofify.mobile.core.library.validMusicEntityId
import dev.spofify.mobile.core.personal.PersonalTrack
import kotlinx.serialization.Serializable

/** Capture a server-observed track, never an inferred listening item or audio-availability fact. */
sealed interface LocalTrackMetadata {
    data class Library(val item: MusicItem) : LocalTrackMetadata
    data class Personal(val track: PersonalTrack) : LocalTrackMetadata
    /** Verified blob title and optional credits observed on the playback queue. Not a server ListeningItem. */
    @Serializable data class Cached(val id: String, val title: String, val albumId: String = "", val album: String = "",
        val artists: List<dev.spofify.mobile.core.playback.PlaybackArtist> = emptyList(), val durationMs: Long = 0) : LocalTrackMetadata
}

sealed interface LocalArtwork {
    data object Unknown : LocalArtwork
    data object Missing : LocalArtwork
    data class Available(val bytes: ByteArray) : LocalArtwork
}

/** Only already-local, authorized content for the corresponding local recording; never fetch here. */
fun interface LocalLyricsSource {
    suspend fun lyrics(trackId: String): TrackLyrics?
}

@Serializable
internal data class CatalogTrackRecord(
    val library: MusicItemWire? = null,
    val personal: PersonalTrack? = null,
    val cached: LocalTrackMetadata.Cached? = null,
) {
    fun project(): MusicItem {
        val observed = library?.let { projectMusicItem(it) } ?: cached?.let {
            MusicItem(MusicKind.TRACK, it.id, it.title, it.artists.map { artist -> Artist(artist.id.orEmpty(), artist.name) },
                it.albumId, it.album, "", it.durationMs, 0, true, 1, 1)
        }
        val track = personal ?: return requireNotNull(observed)
        val personalCredits = track.artists.map { Artist(it.id, it.name) }
        val personalIds = personalCredits.mapTo(hashSetOf(), Artist::id)
        val supplementalCredits = observed?.artists.orEmpty().filter { credit ->
            if (credit.id.isEmpty()) credit !in personalCredits else credit.id !in personalIds
        }
        // Do not mix the name/type of one album with a different album's canonical ID.
        val album = observed?.takeIf { track.albumId.isEmpty() || track.albumId == it.albumId }
        return MusicItem(MusicKind.TRACK, track.id, track.name.ifBlank { observed?.name.orEmpty() },
            personalCredits + supplementalCredits, track.albumId.ifEmpty { album?.albumId.orEmpty() },
            track.albumName.ifBlank { album?.albumName.orEmpty() }, track.releaseDate.ifBlank { album?.releaseDate.orEmpty() },
            track.durationMs.takeIf { it > 0 } ?: observed?.durationMs ?: 0,
            track.trackNumber.takeIf { it > 0 } ?: album?.trackNumber ?: 0,
            track.hasAudio, track.coverage.inLibrary, track.coverage.tracks,
            track.genres.ifEmpty { observed?.genres.orEmpty() }, track.albumType.ifBlank { album?.albumType.orEmpty() },
            track.relation.ifEmpty { observed?.relation.orEmpty() })
    }
}

@Serializable
internal data class CatalogTrackData(
    val tracks: Map<String, CatalogTrackRecord> = emptyMap(),
    val lyricMisses: Map<String, CatalogLyricMiss> = emptyMap(),
)

@Serializable
internal data class CatalogLyricMiss(
    val recordingVersion: String,
    val sourceGeneration: String,
    val reason: LyricReason?,
) {
    fun lyrics(id: String) = TrackLyrics(id, LyricsState.NOT_FOUND, recordingVersion, sourceGeneration, reason = reason)
}



internal fun MusicItem.catalogWire(): MusicItemWire {
    require(kind == MusicKind.TRACK)
    validMusicEntityId(id)
    if (albumId.isNotEmpty()) validMusicEntityId(albumId)
    validateMetadata(name, albumName, releaseDate, albumType, relation, genres, artists.map { it.id to it.name })
    require(durationMs >= 0 && trackNumber >= 0 && availableTracks >= 0 && totalTracks >= availableTracks)
    return MusicItemWire("track", id, name, artists.map { MusicArtistWire(it.id, it.name) }, albumId, albumName,
        releaseDate, durationMs, trackNumber, hasAudio, coverage = MusicCoverageWire(totalTracks, availableTracks),
        genres = genres.toList(), albumType = albumType, relation = relation)
}

internal fun PersonalTrack.catalogCopy(): PersonalTrack {
    require(kind == "track")
    validMusicEntityId(id)
    if (albumId.isNotEmpty()) validMusicEntityId(albumId)
    validateMetadata(name, albumName, releaseDate, albumType, relation, genres, artists.map { it.id to it.name })
    require(durationMs >= 0 && discNumber >= 0 && trackNumber >= 0)
    require(coverage.inLibrary >= 0 && coverage.tracks >= coverage.inLibrary && coverage.albums >= 0)
    return copy(artists = artists.toList(), genres = genres.toList())
}

private fun validateMetadata(
    name: String, album: String, release: String, type: String, relation: String,
    genres: List<String>, credits: List<Pair<String, String>>,
) {
    require(credits.size <= 64 && genres.size <= 64)
    for ((id, credit) in credits) {
        if (id.isNotEmpty()) validMusicEntityId(id)
        require(credit.length <= 512)
    }
    require(genres.all { it.length <= 128 })
    require(name.length <= 2048 && album.length <= 2048)
    require(release.length <= 128 && type.length <= 128 && relation.length <= 128)
}
