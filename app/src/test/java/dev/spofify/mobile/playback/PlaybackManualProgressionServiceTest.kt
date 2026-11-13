package dev.spofify.mobile.playback

import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.SpofifyApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual local service transitions; online priority/traversal belongs only to the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackManualProgressionServiceTest {
    private lateinit var fixture: LocalQueueServiceFixture
    @Before fun setUp() {
        fixture = LocalQueueServiceFixture()
        fixture.replace(listOf("A", "C1", "C2").map { fixture.row(it) })
        enqueue("M1"); enqueue("M2")
        assertEquals(listOf("A", "M1", "M2", "C1", "C2"), fixture.snapshot().queue.map { it.name })
        assertCurrent("A")
    }
    @After fun tearDown() { if (::fixture.isInitialized) fixture.close() }
    private val output get() = fixture.output
    private fun enqueue(name: String) = fixture.snapshot().let {
        assertNull(fixture.append(listOf(fixture.row(name, id = "duplicate-track"))))
        fixture.assertSilent()
        fixture.snapshot()
    }
    private fun assertCurrent(name: String) {
        assertEquals(name, output.currentMediaItem?.mediaMetadata?.title?.toString())
    }
    private fun seekTo(name: String) {
        val index = (0 until output.mediaItemCount).single {
            output.getMediaItemAt(it).mediaMetadata.title?.toString() == name
        }
        output.seekTo(index, 0)
        assertCurrent(name)
    }
    private fun assertFreshManualPrecedesRetiredOccurrences() {
        val before = fixture.snapshot()
        val after = enqueue("X")
        assertEquals(listOf("A", "X", "M1", "M2", "C1", "C2"), after.queue.map { it.name })
        val retained = before.queue.map { it.key }.toSet()
        assertEquals(before.queue, after.queue.filter { it.key in retained })
        assertEquals(before.queue[before.position], after.queue[after.position])
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.occurrence, after.occurrence)
        assertEquals(before.context, after.context)
        assertEquals(before.shuffle, after.shuffle)
    }
    @Test fun consumedManualOccurrencesStayRetiredAcrossUnsampledPreviousAndReturn() {
        output.seekToNextMediaItem(); assertCurrent("M1")
        output.seekToNextMediaItem(); assertCurrent("M2")
        output.seekToPreviousMediaItem(); assertCurrent("M1")
        output.seekToPreviousMediaItem(); assertCurrent("A")
        assertFreshManualPrecedesRetiredOccurrences()
    }
    @Test fun skippingPastManualOccurrencesAlsoRetiresTheirProvenanceBeforeReturn() {
        seekTo("M2"); seekTo("A")
        assertFreshManualPrecedesRetiredOccurrences()
    }
    @Test fun returningFromFirstManualKeepsStillPendingSecondManualInFifoTier() {
        output.seekToNextMediaItem(); assertCurrent("M1")
        output.seekToPreviousMediaItem(); assertCurrent("A")
        assertEquals(listOf("A", "M1", "M2", "X", "C1", "C2"), enqueue("X").queue.map { it.name })
    }
    @Test fun deliberateLocalReplacementResetsPriorityWithoutReusingOldOccurrenceKeys() {
        val before = fixture.snapshot()
        val rows = before.queue.map { fixture.row(it.name, entry = it.entryId, id = it.id) }
        fixture.replace(rows)
        val replaced = fixture.snapshot()
        assertEquals(before.queue.map { it.name }, replaced.queue.map { it.name })
        assertTrue(before.queue.map { it.key }.toSet().intersect(replaced.queue.map { it.key }.toSet()).isEmpty())
        assertFreshManualPrecedesRetiredOccurrences()
    }
}
