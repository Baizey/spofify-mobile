package dev.spofify.mobile.playback

import android.app.Application
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Source-only regressions: this projection must never prepare a second output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CoordinatedRemoteSessionPlayerTest {
    @Test fun populatedRemoteDoesNotBorrowCommandsOrTimelineFromEmptyLocalOutput() {
        val f = Fixture()
        f.output.commands = Player.Commands.EMPTY
        f.remote = state(position = 1).copy(playing = true, elapsed = 4.125, duration = 20.0, shuffle = true)
        with(f.player) {
            assertTrue(isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(isCommandAvailable(Player.COMMAND_STOP))
            assertTrue(isCommandAvailable(Player.COMMAND_GET_TIMELINE))
            assertTrue(isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))
            assertTrue(isCommandAvailable(Player.COMMAND_GET_METADATA))
            assertEquals(3, mediaItemCount)
            assertEquals(3, currentTimeline.windowCount)
            assertEquals(1, currentMediaItemIndex)
            assertEquals(1, currentPeriodIndex)
            assertEquals(4125L, currentPosition)
            assertEquals(20_000L, duration)
            assertEquals(currentPosition, contentPosition)
            assertEquals(duration, contentDuration)
            assertEquals(Player.STATE_READY, playbackState)
            assertTrue(playWhenReady)
            assertTrue(isPlaying)
            assertTrue(shuffleModeEnabled)
            assertEquals(Player.REPEAT_MODE_OFF, repeatMode)
        }
        assertTrue(f.output.reads.isEmpty())
    }

    @Test fun repeatedGettersOfSameImmutableSourceDoNotWalkItsQueueAgain() {
        val f = Fixture()
        val source = state()
        var queueReads = 0
        val queue = object : AbstractList<PlaybackTrack>() {
            override val size: Int get() = source.queue.size
            override fun get(index: Int): PlaybackTrack { queueReads++; return source.queue[index] }
        }
        f.remote = source.copy(queue = queue)
        val timeline = f.player.currentTimeline
        assertTrue(queueReads > 0)
        queueReads = 0
        repeat(20) {
            assertSame(timeline, f.player.currentTimeline)
            assertEquals(3, f.player.mediaItemCount)
            assertEquals(0L, f.player.currentPosition)
            assertTrue(f.player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        }
        assertEquals(0, queueReads)
    }

    @Test fun emptyRemoteIsNotNullAndNeverFallsBackToPopulatedStaleLocalState() {
        val f = Fixture()
        f.output.item = MediaItem.Builder().setMediaId("stale-local").build()
        f.remote = PlaybackSnapshot()
        with(f.player) {
            assertTrue(currentTimeline.isEmpty)
            assertEquals(0, mediaItemCount)
            assertEquals(0, currentMediaItemIndex) // Media3's prospective empty index, not wire -1.
            assertEquals(0, currentPeriodIndex)
            assertNull(currentMediaItem)
            assertEquals(MediaMetadata.EMPTY, mediaMetadata)
            assertEquals(0L, currentPosition)
            assertEquals(C.TIME_UNSET, duration)
            assertEquals(Player.STATE_IDLE, playbackState)
            assertFalse(playWhenReady)
            assertFalse(isPlaying)
            assertFalse(isLoading)
            assertFalse(hasNextMediaItem())
            assertFalse(hasPreviousMediaItem())
            assertEquals(C.INDEX_UNSET, nextMediaItemIndex)
            assertEquals(C.INDEX_UNSET, previousMediaItemIndex)
            for (command in SEEK_COMMANDS + listOf(Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE))
                assertFalse("Empty remote command $command", isCommandAvailable(command))
            setShuffleModeEnabled(true)
            setRepeatMode(Player.REPEAT_MODE_ALL)
            seekTo(10_000)
            seekForward()
            seekBack()
            seekToNext()
            seekToPrevious()
            seekToNextMediaItem()
            seekToPreviousMediaItem()
            seekToDefaultPosition()
            seekToDefaultPosition(0)
            seekTo(0, 0)
            play()
            stop()
        }
        assertEquals(listOf(PlaybackDeviceCommand.transport("resume"), PlaybackDeviceCommand.transport("pause")), f.sent)
        assertFalse(f.output.reads.contains("getMediaItemCount"))
    }

    @Test fun traversalBoundariesIgnoreLocalQueueAndRepeatOneDoesNotInventASuccessor() {
        val f = Fixture()
        for (repeat in listOf("off", "all", "one")) {
            for (index in 0..2) {
                f.remote = state(index).copy(repeat = repeat, shuffle = true)
                val next = index < 2 || repeat == "all"
                val previous = index > 0 || repeat == "all"
                with(f.player) {
                    assertEquals("$repeat / $index next", next, isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                    assertEquals(next, isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                    assertEquals(previous, isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
                    assertTrue(isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) // Restart remains valid.
                    assertTrue(isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                    assertEquals(next, hasNextMediaItem())
                    assertEquals(previous, hasPreviousMediaItem())
                    assertEquals(if (index < 2) index + 1 else if (repeat == "all") 0 else C.INDEX_UNSET, nextMediaItemIndex)
                    assertEquals(if (index > 0) index - 1 else if (repeat == "all") 2 else C.INDEX_UNSET, previousMediaItemIndex)
                    f.sent.clear()
                    seekToNext()
                    seekToNextMediaItem()
                    seekToPrevious()
                    seekToPreviousMediaItem()
                    val expected = mutableListOf<String>()
                    if (next) expected.addAll(listOf("next", "next"))
                    expected.add("previous")
                    if (previous) expected.add("previous")
                    assertEquals(expected.map(PlaybackDeviceCommand::transport), f.sent)
                }
            }
        }
    }

    @Test fun oneItemRepeatAllAllowsWrappingButOffAndOneOnlyAllowRestart() {
        val f = Fixture()
        for (repeat in listOf("off", "one", "all")) {
            f.remote = state().copy(queue = listOf(track(11, 0)), repeat = repeat)
            assertEquals(repeat == "all", f.player.hasNextMediaItem())
            assertEquals(repeat == "all", f.player.hasPreviousMediaItem())
            assertTrue(f.player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        }
    }

    @Test fun duplicateIdsRetainOccurrenceKeysAndMetadataContainsNoPlayableUri() {
        val f = Fixture()
        f.remote = state(1)
        with(f.player) {
            assertEquals(getMediaItemAt(0).mediaId, getMediaItemAt(1).mediaId)
            assertEquals(11L, getMediaItemAt(0).mediaMetadata.extras!!.getLong(RemoteSessionView.WIRE_KEY))
            assertEquals(22L, currentMediaItem!!.mediaMetadata.extras!!.getLong(RemoteSessionView.WIRE_KEY))
            assertNotEquals(currentTimeline.getUidOfPeriod(0), currentTimeline.getUidOfPeriod(1))
            assertEquals("Remote 22", mediaMetadata.title.toString())
            repeat(mediaItemCount) { index ->
                val item = getMediaItemAt(index)
                assertNull(item.localConfiguration)
                assertNull(item.mediaMetadata.artworkUri)
                val window = currentTimeline.getWindow(index, Timeline.Window())
                assertNull(window.mediaItem.localConfiguration)
                assertEquals(item, window.mediaItem)
            }
        }
    }

    @Test fun staleIndexAndDefaultRequestsFailClosedWithoutConsultingLocalMapping() {
        val f = Fixture()
        f.remote = state(1)
        with(f.player) {
            for (index in listOf(-1, 0, 1, 2, 99)) {
                seekToDefaultPosition(index)
                seekTo(index, 0)
                seekTo(index, 1250)
                seekTo(index, C.TIME_UNSET)
            }
            seekToDefaultPosition()
            seekTo(C.TIME_UNSET)
            for (command in listOf(Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION))
                assertFalse(isCommandAvailable(command))
        }
        assertTrue(f.sent.isEmpty())
        assertTrue(f.output.reads.isEmpty())
    }

    @Test fun relativeAndCurrentSeekUseLatestRemotePositionAndBoundedDurationNotLocalSnapshot() {
        val f = Fixture()
        f.remote = state().copy(elapsed = 2.25, duration = 10.0)
        f.player.seekBack()
        f.player.seekForward()
        f.remote = f.remote!!.copy(elapsed = 8.25)
        f.player.seekForward()
        f.player.seekTo(90_000)
        f.player.seekTo(-1)
        f.player.seekTo(Long.MAX_VALUE)
        assertEquals(listOf(0.0, 7.25, 10.0, 10.0).map(PlaybackDeviceCommand::seek), f.sent)
        assertFalse(f.output.reads.contains("getCurrentPosition"))
    }

    @Test fun shuffleAndRepeatSampleRemoteInsteadOfStaleLocalState() {
        val f = Fixture()
        f.remote = state().copy(shuffle = true, repeat = "all")
        f.player.setShuffleModeEnabled(true)
        f.player.setShuffleModeEnabled(false)
        f.player.setRepeatMode(Player.REPEAT_MODE_ALL)
        f.player.setRepeatMode(Player.REPEAT_MODE_OFF) // Two cycles cannot be represented atomically.
        f.player.setRepeatMode(Player.REPEAT_MODE_ONE)
        assertEquals(listOf("shuffle", "repeat").map(PlaybackDeviceCommand::transport), f.sent)
    }

    @Test fun bufferingErrorsPausedAndEmptyAreTruthfulAndDoNotInventLocalBufferedProgress() {
        val f = Fixture()
        f.remote = state().copy(playing = true, waiting = true)
        with(f.player) {
            assertEquals(Player.STATE_BUFFERING, playbackState)
            assertTrue(playWhenReady)
            assertFalse(isPlaying)
            assertTrue(isLoading)
            assertNull(playerError)
            assertEquals(currentPosition, bufferedPosition)
            assertEquals(0L, totalBufferedDuration)
            assertEquals(0, bufferedPercentage)
            f.remote = state().copy(playing = false, loading = true)
            assertEquals(Player.STATE_BUFFERING, playbackState)
            assertFalse(playWhenReady)
            f.remote = state()
            assertEquals(Player.STATE_READY, playbackState)
            assertFalse(isLoading)
            assertFalse(isPlaying)
            for (code in listOf("blocked", "media", "queue")) {
                f.remote = state().copy(error = code, playing = true, waiting = true)
                assertEquals(Player.STATE_IDLE, playbackState)
                assertFalse(playWhenReady)
                assertFalse(isPlaying)
                assertFalse(isLoading)
                assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, playerError!!.errorCode)
                assertSame(playerError, playerError)
            }
            f.remote = PlaybackSnapshot()
            assertNull(playerError)
            assertEquals(Player.STATE_IDLE, playbackState)
        }
    }

    @Test fun remoteCommandsDoNotExposePreparationRawMutatorsOrVolumeControlsButAudioStaysLocal() {
        val f = Fixture()
        f.remote = state()
        for (command in listOf(
            Player.COMMAND_PREPARE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_PLAYLIST_METADATA, Player.COMMAND_RELEASE,
            Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )) assertFalse("Raw capability $command", f.player.isCommandAvailable(command))
        assertEquals(0.7f, f.player.volume, 0f)
        assertSame(f.output.audioAttributes, f.player.audioAttributes)
        f.player.prepare()
        f.player.release()
        assertTrue(f.sent.isEmpty())
    }

    @Test fun refreshNotifiesRemoteChangesOnceWithWrapperIdentityAndPreservesListenerBookkeeping() {
        val f = Fixture()
        f.remote = state()
        val observer = Observer(f.player)
        f.player.addListener(observer)
        f.player.addListener(observer)
        assertEquals(1, f.output.listeners.size)
        f.player.refreshSessionState()
        assertTrue(observer.batches.isEmpty())
        f.remote = state(2).copy(playing = true, loading = true, repeat = "all", shuffle = true)
        f.player.refreshSessionState()
        assertEquals(1, observer.batches.size)
        val batch = observer.batches.single()
        for (event in listOf(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_IS_LOADING_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            Player.EVENT_POSITION_DISCONTINUITY)) assertTrue("Missing event $event", batch.contains(event))
        assertEquals(listOf(Player.STATE_BUFFERING), observer.states)
        assertEquals(listOf("Remote 33"), observer.titles)
        assertEquals(listOf("duplicate"), observer.items)
        assertEquals(1, observer.commands.size)
        f.player.refreshSessionState()
        assertEquals(1, observer.batches.size)
        f.remote = f.remote!!.copy(loading = false)
        f.player.refreshSessionState()
        assertTrue(observer.batches.last().contains(Player.EVENT_IS_PLAYING_CHANGED))
        f.remote = f.remote!!.copy(error = "media")
        f.player.refreshSessionState()
        assertEquals(listOf("Remote playback: media"), observer.errors)
        assertTrue(observer.batches.last().contains(Player.EVENT_PLAYER_ERROR))
        f.remote = PlaybackSnapshot()
        f.player.refreshSessionState()
        assertNull(f.player.currentMediaItem)
        assertEquals("", observer.titles.last())
        val count = observer.batches.size
        f.player.removeListener(observer)
        assertTrue(f.output.listeners.isEmpty())
        f.remote = state()
        f.player.refreshSessionState()
        assertEquals(count, observer.batches.size)
    }

    @Test fun pausedRemoteScrubPublishesPositionButOrdinaryPlayingProgressDoesNotInventASeek() {
        val f = Fixture()
        val observer = Observer(f.player)
        f.player.addListener(observer)
        f.remote = state().copy(elapsed = 5.0)
        f.player.refreshSessionState()
        assertTrue(observer.batches.single().contains(Player.EVENT_POSITION_DISCONTINUITY))
        f.remote = f.remote!!.copy(playing = true)
        f.player.refreshSessionState()
        val count = observer.batches.size
        f.remote = f.remote!!.copy(elapsed = 6.0)
        f.player.refreshSessionState()
        assertEquals(count, observer.batches.size)
        assertEquals(6000L, f.player.currentPosition)
    }

    @Test fun forwardSeekWhilePlayingPublishesExactPositionDiscontinuityOnce() {
        val f = Fixture()
        f.remote = state().copy(playing = true, elapsed = 5.0, duration = 120.0)
        val observer = Observer(f.player)
        f.player.addListener(observer)
        f.remote = f.remote!!.copy(elapsed = 60.0)
        f.player.refreshSessionState()
        assertEquals(listOf(Triple(5_000L, 60_000L, Player.DISCONTINUITY_REASON_INTERNAL)), observer.positions)
        assertTrue(observer.batches.single().contains(Player.EVENT_POSITION_DISCONTINUITY))
        assertEquals(60_000L, f.player.currentPosition)
        f.player.refreshSessionState()
        assertEquals(1, observer.batches.size)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun delayedOrdinaryProgressUsesTimeSinceChangedSnapshotNotEveryServiceTick() {
        val f = Fixture()
        f.remote = state().copy(playing = true, elapsed = 5.0, duration = 120.0)
        val observer = Observer(f.player)
        f.player.addListener(observer)
        repeat(55) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            f.player.refreshSessionState() // Unchanged polls must not reset the progress clock.
        }
        f.remote = f.remote!!.copy(elapsed = 60.0)
        f.player.refreshSessionState()
        assertTrue(observer.positions.isEmpty())
        assertTrue(observer.batches.isEmpty())
        assertEquals(60_000L, f.player.currentPosition)
    }

    @Test fun ordinaryProgressAfterResumeDoesNotHideALaterForwardSeek() {
        val f = Fixture()
        f.remote = state().copy(elapsed = 5.0, duration = 120.0)
        val observer = Observer(f.player)
        f.player.addListener(observer)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        f.remote = f.remote!!.copy(playing = true)
        f.player.refreshSessionState()
        val count = observer.batches.size
        f.remote = f.remote!!.copy(elapsed = 6.0)
        f.player.refreshSessionState()
        assertEquals(count, observer.batches.size)
        f.remote = f.remote!!.copy(elapsed = 60.0)
        f.player.refreshSessionState()
        assertEquals(listOf(Triple(6_000L, 60_000L, Player.DISCONTINUITY_REASON_INTERNAL)), observer.positions)
        assertTrue(observer.batches.last().contains(Player.EVENT_POSITION_DISCONTINUITY))
    }

    @Test fun rawLocalMetadataStateAndCommandEventsAreSuppressedButLocalVolumeStillPasses() {
        val f = Fixture()
        f.remote = state()
        val observer = Observer(f.player)
        f.player.addListener(observer)
        val stale = MediaItem.Builder().setMediaId("stale").setMediaMetadata(MediaMetadata.Builder().setTitle("WRONG").build()).build()
        f.output.listeners.toList().forEach {
            it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            it.onMediaItemTransition(stale, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            it.onMediaMetadataChanged(stale.mediaMetadata)
            it.onPlaybackStateChanged(Player.STATE_ENDED)
            it.onIsPlayingChanged(true)
            it.onPlayerError(PlaybackException("WRONG", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
            it.onAvailableCommandsChanged(Player.Commands.Builder().addAllCommands().build())
            it.onVolumeChanged(0.4f)
        }
        f.output.emit(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYER_ERROR,
            Player.EVENT_AVAILABLE_COMMANDS_CHANGED, Player.EVENT_VOLUME_CHANGED)
        assertTrue(observer.titles.isEmpty())
        assertTrue(observer.items.isEmpty())
        assertTrue(observer.states.isEmpty())
        assertTrue(observer.errors.isEmpty())
        assertTrue(observer.commands.isEmpty())
        assertEquals(listOf(0.4f), observer.volumes)
        assertEquals(1, observer.batches.single().size())
        assertTrue(observer.batches.single().contains(Player.EVENT_VOLUME_CHANGED))
        f.output.emit(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_PLAYER_ERROR)
        assertEquals(1, observer.batches.size)
    }

    @Test fun selectionChangesPublishAndNullReturnsLiveLocalSemanticsWithoutOutputMutation() {
        val f = Fixture()
        f.remote = null
        val observer = Observer(f.player)
        f.player.addListener(observer)
        assertEquals(900_000L, f.player.currentPosition)
        assertEquals(Player.STATE_ENDED, f.player.playbackState)
        f.remote = state().copy(playing = true)
        f.player.refreshSessionState()
        assertEquals("Remote 11", observer.titles.last())
        assertEquals(Player.STATE_READY, observer.states.last())
        f.output.item = MediaItem.Builder().setMediaId("local").setMediaMetadata(MediaMetadata.Builder().setTitle("Local").build()).build()
        f.remote = null
        f.player.refreshSessionState()
        assertEquals("Local", observer.titles.last())
        assertEquals(Player.STATE_ENDED, observer.states.last())
        assertEquals(900_000L, f.player.currentPosition)
        f.output.listeners.forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
        assertEquals(Player.STATE_READY, observer.states.last())
        f.player.seekToDefaultPosition(0)
        assertEquals(listOf(PlaybackDeviceCommand.queue("jump", 999)), f.sent)
    }

    private class Fixture {
        val output = Output()
        val sent = mutableListOf<PlaybackDeviceCommand>()
        var remote: PlaybackSnapshot? = state()
        val local = PlaybackSnapshot(queue = listOf(track(999, 0)), position = 0, elapsed = 900.0, duration = 1000.0)
        // The fourth/trailing lambda remains physical-index mapping, preserving existing callers.
        val player = CoordinatedSessionPlayer(output.player, sent::add, { local }) { index ->
            check(remote == null) { "Remote request consulted local physical indices" }
            if (index == 0) 999L else null
        }.apply { setRemoteSnapshotProvider { remote } }
    }

    private class Observer(private val expected: Player) : Player.Listener {
        val batches = mutableListOf<Player.Events>()
        val states = mutableListOf<Int>()
        val titles = mutableListOf<String>()
        val items = mutableListOf<String?>()
        val errors = mutableListOf<String?>()
        val commands = mutableListOf<Player.Commands>()
        val volumes = mutableListOf<Float>()
        val positions = mutableListOf<Triple<Long, Long, Int>>()
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            positions += Triple(oldPosition.positionMs, newPosition.positionMs, reason)
        }
        override fun onEvents(player: Player, events: Player.Events) { assertSame(expected, player); batches += events }
        override fun onPlaybackStateChanged(playbackState: Int) { states += playbackState }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { titles += mediaMetadata.title?.toString().orEmpty() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { items += mediaItem?.mediaId }
        override fun onPlayerError(error: PlaybackException) { errors += error.message }
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { commands += availableCommands }
        override fun onVolumeChanged(volume: Float) { volumes += volume }
    }

    /** Unknown calls (in particular all raw mutations) fail immediately. */
    private class Output {
        var commands = Player.Commands.Builder().addAllCommands().build()
        var item: MediaItem? = null
        val reads = mutableListOf<String>()
        val listeners = mutableSetOf<Player.Listener>()
        val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                else -> {
                    reads += method.name
                    when (method.name) {
                        "getAvailableCommands" -> commands
                        "getCurrentTimeline" -> Timeline.EMPTY
                        "getMediaItemCount" -> if (item == null) 0 else 1
                        "getCurrentMediaItem", "getMediaItemAt" -> item
                        "getCurrentMediaItemIndex", "getCurrentPeriodIndex" -> 0
                        "getCurrentPosition", "getContentPosition" -> 900_000L
                        "getDuration", "getContentDuration" -> 1_000_000L
                        "getPlaybackState" -> Player.STATE_ENDED
                        "getPlayWhenReady", "isPlaying", "isLoading", "getShuffleModeEnabled" -> false
                        "getRepeatMode" -> Player.REPEAT_MODE_OFF
                        "getMediaMetadata" -> item?.mediaMetadata ?: MediaMetadata.EMPTY
                        "getPlaylistMetadata" -> MediaMetadata.EMPTY
                        "getCurrentTracks" -> Tracks.EMPTY
                        "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                        "getPlayerError" -> null
                        "getSeekBackIncrement" -> 3000L
                        "getSeekForwardIncrement" -> 5000L
                        "getVolume" -> 0.7f
                        "getAudioAttributes" -> audioAttributes
                        "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                        else -> error("Projection bypassed authority: ${method.name}")
                    }
                }
            }
        } as Player
        fun emit(vararg events: Int) {
            val batch = Player.Events(FlagSet.Builder().addAll(*events).build())
            listeners.toList().forEach { it.onEvents(player, batch) }
        }
    }

    private companion object {
        fun track(key: Long, order: Long) = PlaybackTrack("duplicate", "Remote $key", emptyList(), "Album", "album", 30.0, key = key, order = order)
        fun state(position: Int = 0) = PlaybackSnapshot(queue = listOf(track(11, 0), track(22, 1), track(33, 2)), position = position)
        val SEEK_COMMANDS = listOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
    }
}
