package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.library.MusicItem

internal data class ArtistDiscography(
    val albums: List<MusicItem>,
    val singles: List<MusicItem>,
    val appearsOn: List<MusicItem>,
)

/** Exact server relations establish ownership; credited names and guest album types never do. */
internal fun groupArtistDiscography(releases: List<MusicItem>): ArtistDiscography {
    val albums = mutableListOf<MusicItem>()
    val singles = mutableListOf<MusicItem>()
    val appearsOn = mutableListOf<MusicItem>()
    for (release in releases) {
        when {
            release.relation == "release" && release.albumType == "album" -> albums.add(release)
            release.relation == "release" && release.albumType == "single" -> singles.add(release)
            else -> appearsOn.add(release)
        }
    }
    return ArtistDiscography(albums.toList(), singles.toList(), appearsOn.toList())
}
