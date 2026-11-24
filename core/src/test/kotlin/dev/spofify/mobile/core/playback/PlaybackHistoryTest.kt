package dev.spofify.mobile.core.playback

import dev.spofify.mobile.core.personal.PersonalHistoryEntry
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryTest {
    @Test fun usesObservedProgressNotPositionAndCreditsEachVisitOnce() {
        var time = 0L
        val clock = object : PlaybackClock {
            override fun monotonicMs() = time
            override fun wallTimeMs() = 1_700_000_000_000L + time
        }
        val local = mutableListOf<PersonalHistoryEntry>()
        val history = PlaybackHistory(clock, local::add)
        val row = PlaybackTrack("song", "Song", emptyList(), "", "", 120.0, 1, 1, "")
        var binding = AudioBinding("start", 1, "a", row.id)
        fun sample(ms: Long, position: Double, event: String = "a", playing: Boolean = true) {
            time += ms
            history.sample(row, event, null, AudioFacts(binding, position, isPlaying = playing), offline = true)
        }
        sample(0, 0.0)
        sample(10, 60.0) // seek alone earns nothing
        sample(20_000, 80.0)
        sample(5_000, 80.0, playing = false)
        binding = binding.copy(instructionId = "resume")
        sample(0, 80.0)
        sample(10_000, 90.0)
        assertTrue(local.isEmpty())
        sample(1, 90.001)
        sample(5_000, 95.001)
        assertEquals(1, local.size)
        sample(0, 0.0, "b")
        sample(31_000, 31.0, "b")
        assertEquals(listOf("mobile:a", "mobile:b"), local.map { it.id })
        assertTrue(local.all { it.track.id == row.id && it.origin == "offline" })
    }
}
