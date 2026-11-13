package dev.spofify.mobile.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Actual sole-service saved queue operations. Android start denial deliberately keeps media
 * unopened and silent. Online resolution/publication is NOT emulated by this local fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackListActionServiceTest {
    private fun LocalQueueServiceFixture.base() {
        replace(listOf("played", "A", "C1", "C2").map { row(it) }, start = 1)
        output.seekTo(12_375)
        assertTrue(PlaybackService.localTransport(LocalPlaybackCommand.transport("pause")))
    }
    private fun assertCurrentUnchanged(before: PlaybackSnapshot, after: PlaybackSnapshot) {
        assertEquals(before.position, after.position)
        assertEquals(before.queue[before.position], after.queue[after.position])
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.occurrence, after.occurrence)
        assertEquals(before.context, after.context)
        assertEquals(before.shuffle, after.shuffle)
        assertEquals(before.repeat, after.repeat)
        assertEquals(before.error, after.error)
        assertEquals(before.playing, after.playing)
        assertEquals(before.waiting, after.waiting)
        assertEquals(before.loading, after.loading)
    }
    @Test fun highOnEmptySavedOutputDoesNotInventAnInitialSelectionOrStart() {
        LocalQueueServiceFixture().use { f ->
            assertNotNull(f.append(listOf(f.row("Not selected")), QueueIntentIntent.HIGH))
            assertTrue(f.snapshot().queue.isEmpty())
            assertEquals(0, f.output.mediaItemCount)
            f.assertSilent()
        }
    }
    @Test fun highIsFifoNextIsImmediateAndLowDoesNotRepartitionPriorityOrPlayedHistory() {
        LocalQueueServiceFixture().use { f ->
            f.base()
            val before = f.snapshot()
            assertNull(f.append(listOf(f.row("H1")), QueueIntentIntent.HIGH))
            assertNull(f.append(listOf(f.row("H2")), QueueIntentIntent.HIGH))
            assertNull(f.append(listOf(f.row("N1"), f.row("N2")), QueueIntentIntent.NEXT))
            assertNull(f.append(listOf(f.row("L1"), f.row("L2")), QueueIntentIntent.LOW))
            assertNull(f.append(listOf(f.row("H3")), QueueIntentIntent.HIGH))
            val after = f.snapshot()
            assertEquals(listOf("played", "A", "N1", "N2", "H1", "H2", "H3", "C1", "C2", "L1", "L2"), after.queue.map { it.name })
            assertCurrentUnchanged(before, after)
            assertEquals(before.queue, after.queue.filter { it.key in before.queue.map { old -> old.key } })
            f.assertSilent()
        }
    }
    @Test fun duplicatePromotionUsesOnlyTheExactUpcomingOccurrenceAndNoopDoesNotInsert() {
        LocalQueueServiceFixture().use { f ->
            f.replace(listOf("Current", "First", "Second").map { f.row(it, id = "same") })
            f.output.seekTo(42_125)
            assertTrue(PlaybackService.localTransport(LocalPlaybackCommand.transport("pause")))
            val before = f.snapshot()
            val selected = PlaybackService.upcomingEntries().last().occurrenceId
            val results = mutableListOf<PlaybackActionResult>()
            PlaybackService.localOccurrence(requireNotNull(PlaybackService.interactionKey()), selected,
                PlaybackListAction.QUEUE_HIGH, { true }, results::add)
            val after = f.snapshot()
            assertEquals(listOf("Current", "Second", "First"), after.queue.map { it.name })
            assertEquals(before.queue.map { it.key }.toSet(), after.queue.map { it.key }.toSet())
            assertEquals(before.queue.map { it.entryId }.toSet(), after.queue.map { it.entryId }.toSet())
            assertCurrentUnchanged(before, after)
            PlaybackService.localOccurrence(requireNotNull(PlaybackService.interactionKey()), selected,
                PlaybackListAction.QUEUE_HIGH, { true }, results::add)
            assertEquals(listOf(PlaybackActionStatus.ADDED, PlaybackActionStatus.NO_OP), results.map { it.status })
            assertEquals(after, f.snapshot())
            f.assertSilent()
        }
    }
    @Test fun staleHeldOccurrenceCallerCancellationAndCurrentOrMetadataIdsCannotRetarget() {
        LocalQueueServiceFixture().use { f ->
            f.base()
            val before = f.snapshot()
            val stale = requireNotNull(PlaybackService.interactionKey())
            assertNull(f.append(listOf(f.row("External"))))
            val changed = f.snapshot()
            val selected = PlaybackService.upcomingEntries().last().occurrenceId
            val results = mutableListOf<PlaybackActionResult>()
            PlaybackService.localOccurrence(stale, selected, PlaybackListAction.PLAY_NEXT, { true }, results::add)
            val live = requireNotNull(PlaybackService.interactionKey())
            PlaybackService.localOccurrence(live, selected, PlaybackListAction.PLAY_NEXT, { false }, results::add)
            PlaybackService.localOccurrence(live, requireNotNull(PlaybackMedia.occurrence(f.output.currentMediaItem)), PlaybackListAction.QUEUE_HIGH, { true }, results::add)
            PlaybackService.localOccurrence(live, before.queue[before.position].entryId, PlaybackListAction.QUEUE_HIGH, { true }, results::add)
            PlaybackService.localOccurrence(live, "C2", PlaybackListAction.QUEUE_HIGH, { true }, results::add)
            assertEquals(List(5) { PlaybackActionStatus.STALE }, results.map { it.status })
            assertEquals(changed, f.snapshot())
            f.assertSilent()
        }
    }
    @Test fun completeLargeBatchProtectsEveryPlayedCurrentAndFutureKeyWithoutCountPrefix() {
        LocalQueueServiceFixture().use { f ->
            val original = (0 until 5000).map { f.row("Old $it", entry = "old-$it", id = "same") }
            f.replace(original, start = 2500)
            f.output.seekTo(12_375)
            assertTrue(PlaybackService.localTransport(LocalPlaybackCommand.transport("pause")))
            val before = f.snapshot()
            val additions = (0 until 1001).map { f.row("New $it", entry = "new-$it", id = "same") }
            assertNull(f.append(additions, QueueIntentIntent.HIGH))
            val after = f.snapshot()
            val originalKeys = before.queue.map { it.key }.toSet()
            assertEquals(6001, after.queue.size)
            assertEquals(before.queue, after.queue.filter { it.key in originalKeys })
            assertEquals(additions.map { it.entryId }, after.queue.filterNot { it.key in originalKeys }.map { it.entryId })
            assertEquals("new-1000", after.queue[before.position + additions.size].entryId)
            assertEquals(after.queue.size, after.queue.map { it.key }.toSet().size)
            assertCurrentUnchanged(before, after)
            f.assertSilent()
        }
    }
    @Test fun invalidUtf8ByteMetadataMissingReferenceOrCanceledBatchRejectsBeforeAnyPrefixMutation() {
        LocalQueueServiceFixture().use { f ->
            f.base()
            val before = f.snapshot()
            // 129 code points, but 516 UTF-8 bytes: the local field bound is bytes, not chars.
            val tooWide = f.row("Bad", entry = "bad").copy(name = "🎵".repeat(129))
            assertNotNull(f.append(listOf(f.row("Valid", entry = "valid"), tooWide)))
            assertEquals(before, f.snapshot())
            assertNotNull(PlaybackService.enqueueLocal(listOf(f.row("Missing")), f.eligibility,
                emptyMap(), QueueIntentIntent.HIGH) { true })
            assertEquals(before, f.snapshot())
            assertNotNull(f.append(listOf(f.row("Canceled")), current = { false }))
            assertEquals(before, f.snapshot())
            assertNotNull(f.append(listOf(f.row("Not a replacement")), QueueIntentIntent.NOW))
            assertEquals(before, f.snapshot())
            f.assertSilent()
        }
    }
    @Test fun exhaustedSafeKeySpaceIsAtomicAndExactLastKeyRemainsUsable() {
        LocalQueueServiceFixture().use { f ->
            f.base()
            val before = f.snapshot()
            val serial = PlaybackService::class.java.getDeclaredField("wireSerial").apply { isAccessible = true }
            val last = LibraryPlaybackProtocol.MAX_SAFE_INTEGER
            serial.setLong(f.service, last - 1)
            assertNotNull(f.append(listOf(f.row("First"), f.row("Second"))))
            assertEquals(before, f.snapshot())
            assertEquals(last - 1, serial.getLong(f.service))
            assertNull(f.append(listOf(f.row("Last"))))
            assertEquals(last, f.snapshot().queue.single { it.name == "Last" }.key)
            assertNotNull(f.append(listOf(f.row("Overflow"))))
            assertEquals(before.queue.size + 1, f.snapshot().queue.size)
            assertCurrentUnchanged(before, f.snapshot())
            f.assertSilent()
        }
    }
    @Test fun prioritySurvivesShuffleRemovalUndoAndClearWithoutRestartingCurrent() {
        LocalQueueServiceFixture().use { f ->
            f.base()
            assertNull(f.append(listOf(f.row("H1"), f.row("H2"))))
            val before = f.snapshot()
            assertTrue(PlaybackService.localTransport(LocalPlaybackCommand.transport("shuffle")))
            assertEquals(listOf("H1", "H2"), f.snapshot().queue.drop(before.position + 1).take(2).map { it.name })
            assertTrue(PlaybackService.localTransport(LocalPlaybackCommand.transport("shuffle")))
            assertEquals(before.queue, f.snapshot().queue)
            val id = PlaybackService.upcomingEntries().first().occurrenceId
            PlaybackService.removeQueued(requireNotNull(PlaybackService.interactionKey()), id) { true }
            PlaybackService.undoQueuedRemoval()
            assertEquals(before.queue, f.snapshot().queue)
            assertNull(f.append(listOf(f.row("H3"))))
            assertEquals(listOf("played", "A", "H1", "H2", "H3", "C1", "C2"), f.snapshot().queue.map { it.name })
            PlaybackService.clearQueued()
            assertNull(f.append(listOf(f.row("After clear"))))
            assertEquals(listOf("played", "A", "After clear"), f.snapshot().queue.map { it.name })
            assertCurrentUnchanged(before, f.snapshot())
            f.assertSilent()
        }
    }
    @Test fun futureOnlyHighPreservesIdleEndedBufferingAndMediaErrorWithoutTransportCalls() {
        LocalQueueServiceFixture().use { f ->
            f.base()
            val actual = f.output
            val playerField = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
            val mediaError = ExoPlaybackException.createForSource(java.io.IOException("Synthetic media error"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            val cases = listOf(Triple(Player.STATE_READY, false, null), Triple(Player.STATE_ENDED, true, null),
                Triple(Player.STATE_IDLE, true, null), Triple(Player.STATE_BUFFERING, true, null),
                Triple(Player.STATE_READY, true, mediaError), Triple(Player.STATE_IDLE, true, mediaError))
            try {
                for ((index, case) in cases.withIndex()) {
                    val calls = mutableListOf<String>()
                    val proxy = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
                        when (method.name) {
                            "getPlaybackState" -> case.first
                            "getPlayWhenReady" -> case.second
                            "getPlayerError" -> case.third
                            "isPlaying" -> false // Never fabricate actual audio/progression.
                            else -> {
                                if (method.name in setOf("prepare", "play", "pause", "stop", "setPlayWhenReady") || method.name.startsWith("seekTo")) calls += method.name
                                try { method.invoke(actual, *(args ?: emptyArray())) }
                                catch (error: InvocationTargetException) { throw error.targetException }
                            }
                        }
                    } as ExoPlayer
                    playerField.set(f.service, proxy)
                    val before = f.snapshot()
                    assertNull(f.append(listOf(f.row("Selected $index", entry = "selected-$index"))))
                    assertCurrentUnchanged(before, f.snapshot())
                    assertTrue("Only queue operations are allowed for unchanged current: $calls", calls.isEmpty())
                }
            } finally { playerField.set(f.service, actual) }
            f.assertSilent()
        }
    }
}
