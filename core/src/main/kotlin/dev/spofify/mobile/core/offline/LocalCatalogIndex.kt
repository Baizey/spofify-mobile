package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.library.Artist
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.personal.PersonalTrack
import java.text.Normalizer
import java.util.Locale

/** Rebuilt on metadata or inventory changes, not per query or page. Counts describe this index only. */
internal class LocalCatalogIndex(
    val revision: Long,
    available: Set<String>,
    metadata: CatalogTrackData,
    history: CatalogHistory,
) {
    val tracks: Map<String, MusicItem>
    val personal: Map<String, PersonalTrack>
    val artists: Map<String, MusicItem>
    val albums: Map<String, MusicItem>
    val albumTracks: Map<String, List<MusicItem>>
    val artistAlbums: Map<String, List<MusicItem>>
    private val documents: List<Document>
    private val postings: Map<Char, List<Document>>

    init {
        val records = metadata.tracks.filterKeys(available::contains).toMutableMap()
        for (entry in history.entries) {
            val id = entry.track.id
            if (id !in available || entry.metadataMissing) continue
            val observed = records[id] ?: CatalogTrackRecord()
            if (observed.personal == null) records[id] = observed.copy(personal = entry.track)
        }
        personal = records.mapNotNull { (id, record) -> record.personal?.let { id to it } }.toMap()
        tracks = records.toSortedMap().mapValues { (_, record) -> record.project().copy(
            hasAudio = true, availableTracks = 1, totalTracks = 1,
        ) }
        val byArtist = linkedMapOf<String, MutableList<MusicItem>>()
        val artistNames = mutableMapOf<String, String>()
        for (track in tracks.values) {
            for (credit in track.artists.distinctBy(Artist::id)) {
                if (credit.id.isEmpty()) continue // Keep credit on the track, never make a navigable ID.
                byArtist.getOrPut(credit.id, ::mutableListOf).add(track)
                if (credit.name.isNotBlank()) artistNames.putIfAbsent(credit.id, credit.name)
            }
        }
        artists = byArtist.mapValues { (id, rows) ->
            collection(MusicKind.ARTIST, id, artistNames[id].orEmpty(), rows.size)
        }
        val titleOrder = compareBy<MusicItem>({ fold(it.name) }, { it.id })
        albumTracks = tracks.values.filter { it.albumId.isNotEmpty() }.groupBy(MusicItem::albumId)
            .mapValues { (_, rows) -> rows.sortedWith(compareBy<MusicItem>(
                { personal[it.id]?.discNumber ?: 0 }, { it.trackNumber }, { fold(it.name) }, { it.id },
            )) }
        albums = albumTracks.mapValues { (id, rows) ->
            collection(MusicKind.ALBUM, id, rows.firstNonBlank(MusicItem::albumName), rows.size).copy(
                artists = rows.flatMap(MusicItem::artists).distinctBy { it.id.ifEmpty { "\u0000${fold(it.name)}" } },
                albumId = id,
                albumName = rows.firstNonBlank(MusicItem::albumName),
                releaseDate = rows.firstNonBlank(MusicItem::releaseDate),
                albumType = rows.firstNonBlank(MusicItem::albumType),
            )
        }
        artistAlbums = byArtist.mapValues { (_, rows) ->
            rows.asSequence().map(MusicItem::albumId).distinct().mapNotNull(albums::get).toList().sortedWith(titleOrder)
        }
        documents = (tracks.values + artists.values + albums.values).map { item ->
            Document(item, fold(item.name), (item.artists.map(Artist::name) + item.albumName)
                .filter(String::isNotBlank).map(::fold).distinct())
        }
        // A bounded Latin/digit prefilter; other scripts still use the same folded matcher.
        val buckets = mutableMapOf<Char, MutableList<Document>>()
        for (doc in documents) {
            val chars = (sequenceOf(doc.name) + doc.context.asSequence()).flatMap { it.asSequence() }
            chars.filter(::indexedChar).distinct().forEach { buckets.getOrPut(it, ::mutableListOf).add(doc) }
        }
        postings = buckets
    }

    fun detail(kind: MusicKind, id: String): MusicItem? = when (kind) {
        MusicKind.TRACK -> tracks[id]
        MusicKind.ARTIST -> artists[id]
        MusicKind.ALBUM -> albums[id]
    }

    fun ranked(query: String, kind: MusicKind?): List<MusicItem> {
        val folded = fold(query)
        if (folded.isEmpty()) return emptyList()
        val tokens = folded.split(' ').distinct()
        val candidates = folded.asSequence().filter(::indexedChar).distinct()
            .map { postings[it].orEmpty() }.minByOrNull(List<Document>::size) ?: documents
        return candidates.asSequence().filter { kind == null || it.item.kind == kind }
            .mapNotNull { doc -> doc.rank(folded, tokens)?.let { Match(doc, it) } }
            .sortedWith(compareBy<Match>({ it.rank }, { it.doc.name }, { it.doc.item.kind.ordinal }, { it.doc.item.id }))
            .map { it.doc.item }.toList()
    }

    private data class Match(val doc: Document, val rank: Int)
    private data class Document(val item: MusicItem, val name: String, val context: List<String>) {
        fun rank(query: String, tokens: List<String>): Int? = when {
            name == query -> 0
            name.startsWith(query) -> 1
            tokens.all { token -> name.contains(token) } -> 2
            tokens.all { token -> name.contains(token) || context.any { it.contains(token) } } -> 3
            else -> null
        }
    }

    private companion object {
        val marks = Regex("\\p{M}+")
        val whitespace = Regex("[\\p{Z}\\s]+")
        fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT).replace(marks, "").replace("ß", "ss")
            .replace('ς', 'σ').replace(whitespace, " ").trim()

        fun indexedChar(c: Char) = c in 'a'..'z' || c in '0'..'9'
        fun List<MusicItem>.firstNonBlank(value: (MusicItem) -> String) = firstNotNullOfOrNull {
            value(it).takeIf(String::isNotBlank)
        }.orEmpty()

        fun collection(kind: MusicKind, id: String, name: String, count: Int) = MusicItem(
            kind, id, name, emptyList(), "", "", "", 0, 0, false, count.toLong(), count.toLong(),
        )
    }
}
