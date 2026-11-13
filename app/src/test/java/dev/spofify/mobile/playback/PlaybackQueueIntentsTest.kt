package dev.spofify.mobile.playback

import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.LocalPlaybackCommand
import dev.spofify.mobile.core.QueueIntentIntent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual local service placement; no retained client-side online reducer/revision protocol.
 * Frozen historical corpus bytes remain checked by HistoricalQueueIntentCorpusTest.
 * Current online byte/identity validation is LibraryPlaybackCodecTest, not a poll publication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackQueueIntentsTest {
    private lateinit var fixture: LocalQueueServiceFixture
    @Before fun setUp() { fixture = LocalQueueServiceFixture() }
    @After fun tearDown() { if (::fixture.isInitialized) fixture.close() }
    private fun base() {
        fixture.replace(listOf("A", "C1", "C2").map { fixture.row(it) })
        assertNull(fixture.append(listOf(fixture.row("H1")), QueueIntentIntent.HIGH))
        fixture.output.seekTo(42_125)
        assertTrue(PlaybackService.localTransport(LocalPlaybackCommand.transport("pause")))
        assertEquals(listOf("A", "H1", "C1", "C2"), fixture.snapshot().queue.map { it.name })
        assertEquals(42.125, fixture.snapshot().elapsed, 0.0)
    }
    private fun assertUnchangedCurrent(before: dev.spofify.mobile.core.PlaybackSnapshot) {
        val after = fixture.snapshot()
        assertEquals(before.queue[before.position], after.queue[after.position])
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.occurrence, after.occurrence)
        assertEquals(before.context, after.context)
        assertEquals(before.repeat, after.repeat)
        assertEquals(before.shuffle, after.shuffle)
        assertEquals(before.playing, after.playing)
        assertEquals(before.error, after.error)
        fixture.assertSilent()
    }
    @Test fun pausedA42LowBPreservesCompletePriorityAndContextQueue() {
        base()
        val before = fixture.snapshot()
        assertNull(fixture.append(listOf(fixture.row("B1"), fixture.row("B2")), QueueIntentIntent.LOW))
        assertEquals(listOf("A", "H1", "C1", "C2", "B1", "B2"), fixture.snapshot().queue.map { it.name })
        assertUnchangedCurrent(before)
    }
    @Test fun highIsFifoAndNextIsImmediateWithoutRepartitioningExistingFuture() {
        base()
        val before = fixture.snapshot()
        assertNull(fixture.append(listOf(fixture.row("B1"), fixture.row("B2")), QueueIntentIntent.HIGH))
        assertNull(fixture.append(listOf(fixture.row("N1"), fixture.row("N2")), QueueIntentIntent.NEXT))
        assertNull(fixture.append(listOf(fixture.row("X")), QueueIntentIntent.HIGH))
        assertEquals(listOf("A", "N1", "N2", "H1", "B1", "B2", "X", "C1", "C2"),
            fixture.snapshot().queue.map { it.name })
        assertUnchangedCurrent(before)
    }
    @Test fun largeCompleteBatchesRetainLastDuplicateAndEveryExistingKey() {
        for (size in listOf(1001, 5000)) for (intent in listOf(QueueIntentIntent.LOW, QueueIntentIntent.HIGH, QueueIntentIntent.NEXT)) {
            base()
            val before = fixture.snapshot()
            val rows = (0 until size).map { fixture.row("Duplicate $it", entry = "e$size-${intent.wire}-$it", id = "same") }
            assertNull(fixture.append(rows, intent))
            val after = fixture.snapshot()
            val oldKeys = before.queue.map { it.key }.toSet()
            assertEquals(before.queue, after.queue.filter { it.key in oldKeys })
            assertEquals(rows.map { it.entryId }, after.queue.filter { it.key !in oldKeys }.map { it.entryId })
            assertEquals(size + before.queue.size, after.queue.size)
            assertEquals(after.queue.size, after.queue.map { it.key }.toSet().size)
            assertEquals(if (intent == QueueIntentIntent.NEXT) size + 1 else 1,
                after.queue.indexOfFirst { it.name == "H1" })
            assertUnchangedCurrent(before)
        }
    }
    @Test fun completeLocalReplacementCanSelectLastDuplicateWithoutPrefixOrCountCap() {
        for (size in listOf(1001, 5000)) {
            val rows = (0 until size).map { fixture.row("Duplicate", entry = "e$size-$it", id = "same") }
            fixture.replace(rows, start = size - 1)
            val snapshot = fixture.snapshot()
            assertEquals(size, snapshot.queue.size)
            assertEquals(size - 1, snapshot.position)
            assertEquals(rows.map { it.entryId }, snapshot.queue.map { it.entryId })
            assertEquals(rows.last().entryId, snapshot.queue[snapshot.position].entryId)
            fixture.assertSilent()
        }
    }
    @Test fun missingReferenceInvalidMetadataAndCanceledAdmissionAreAtomicNotPartial() {
        base()
        val before = fixture.snapshot()
        val rows = listOf(fixture.row("Valid"), fixture.row("Invalid").copy(name = "x".repeat(513)))
        assertNotNull(fixture.append(rows))
        assertEquals(before, fixture.snapshot())
        assertNotNull(PlaybackService.enqueueLocal(listOf(fixture.row("Missing")), fixture.eligibility,
            emptyMap(), QueueIntentIntent.LOW) { true })
        assertEquals(before, fixture.snapshot())
        assertNotNull(fixture.append(listOf(fixture.row("Cancelled")), current = { false }))
        assertEquals(before, fixture.snapshot())
        fixture.assertSilent()
    }
    @Test fun exactUpcomingPromotionNeverDuplicatesOrChangesCurrentAndStaleKeyCannotEdit() {
        base()
        val before = fixture.snapshot()
        val key = requireNotNull(PlaybackService.interactionKey())
        val selected = PlaybackService.upcomingEntries().last().occurrenceId
        var result: PlaybackActionResult? = null
        PlaybackService.localOccurrence(PlaybackInteractionKey(), selected, PlaybackListAction.PLAY_NEXT,
            { true }, { result = it })
        assertEquals(PlaybackActionStatus.STALE, result!!.status)
        assertEquals(before, fixture.snapshot())
        PlaybackService.localOccurrence(key, selected, PlaybackListAction.PLAY_NEXT, { true }, { result = it })
        assertEquals(PlaybackActionStatus.ADDED, result!!.status)
        assertEquals(listOf("A", "C2", "H1", "C1"), fixture.snapshot().queue.map { it.name })
        assertEquals(before.queue.map { it.key }.toSet(), fixture.snapshot().queue.map { it.key }.toSet())
        assertUnchangedCurrent(before)
    }
}
