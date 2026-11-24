package dev.spofify.mobile.core.playback

import dev.spofify.mobile.core.personal.*
import java.security.MessageDigest
import java.time.Instant

/** Same qualification as the shared player: over 30s of observed progress in a visit,
 * bounded by monotonic time plus one shared second of jitter. Seeks/restarts break the anchor. */
internal class PlaybackHistory(private val clock: PlaybackClock, private val record: (PersonalHistoryEntry) -> Unit) {
    private data class Anchor(val binding: AudioBinding?, val time: Long, val position: Long)
    private var visit: String? = null
    private var serverId: String? = null
    private var anchor: Anchor? = null
    private var progress = 0L
    private var slack = 1_000L
    private var credited = false

    fun sample(row: PlaybackTrack?, event: String?, session: String?, facts: AudioFacts, offline: Boolean) {
        if (row == null || event == null || !facts.elapsed.isFinite() || facts.elapsed < 0) { anchor = null; return }
        if (event != visit) {
            visit = event; serverId = session?.let { serverEvent(it, event) }
            anchor = null; progress = 0; slack = 1_000; credited = false
        }
        if (credited) return
        val playing = facts.isPlaying && !facts.isBuffering && !facts.ended && facts.error.isEmpty()
        val now = clock.monotonicMs()
        val position = (facts.elapsed * 1000).toLong()
        anchor?.takeIf { it.binding == facts.binding && (playing || facts.ended) }?.let {
            val delta = position - it.position
            val budget = now - it.time + slack
            if (delta in 0..budget) { progress += delta; slack = minOf(1_000, budget - delta) }
        }
        anchor = if (playing) Anchor(facts.binding, now, position) else null
        if (progress > 30_000) {
            credited = true
            val metadata = PersonalTrack("track", row.id, row.name, true, PersonalCoverage(1, 1, 0),
                artists = row.artists.map { PersonalArtist(it.id.orEmpty(), it.name) }, albumId = row.albumId,
                albumName = row.albumName, durationMs = (row.duration * 1000).toLong())
            record(PersonalHistoryEntry(serverId ?: "mobile:$event", Instant.ofEpochMilli(clock.wallTimeMs()).toString(),
                metadata, origin = "offline".takeIf { offline && serverId == null }))
        }
    }

    fun discontinuity() { anchor = null }

    private fun serverEvent(session: String, event: String) = "v3:" + MessageDigest.getInstance("SHA-256")
        .digest("$session\u0000$event".encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
