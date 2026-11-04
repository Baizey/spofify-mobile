package dev.spofify.mobile.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CoordinatedSessionPlayerTest {
    @Test fun playPauseReadyAndStopAreExplicitIntentNotRawPlaybackOrClose() {
        val fixture = Fixture()
        with(fixture.player) {
            play()
            setPlayWhenReady(true)
            pause()
            setPlayWhenReady(false)
            stop()
        }
        fixture.assertCommands("resume", "resume", "pause", "pause", "pause")
        fixture.output.assertNoMutations()
    }

    @Test fun prepareAndReleaseNeverConsultAuthorityOrReleaseOrPrepareServiceOutput() {
        val output = Output().apply { state = Player.STATE_ENDED; ready = true }
        val player = CoordinatedSessionPlayer(output.player,
            { error("No command expected") }, { error("No snapshot expected") }, { error("No key expected") })
        repeat(3) {
            player.prepare()
            player.release()
        }
        // This also covers an old/stale controller's prepare against ended buffered output.
        assertTrue(output.reads.isEmpty())
        output.assertNoMutations()
    }

    @Test fun endedPlayIsStillResumeAndRestartBelongsToCommandExecutor() {
        val fixture = Fixture().apply { output.state = Player.STATE_ENDED }
        fixture.player.play()
        fixture.assertCommands("resume")
        assertFalse(fixture.output.reads.contains("getPlaybackState"))
        fixture.output.assertNoMutations()
    }

    @Test fun everyNextAndPreviousVariantUsesTypedTransport() {
        val fixture = Fixture()
        with(fixture.player) {
            seekToNext()
            seekToNextMediaItem()
            seekToPrevious()
            seekToPreviousMediaItem()
        }
        fixture.assertCommands("next", "next", "previous", "previous")
        fixture.output.assertNoMutations()
    }

    @Test fun ordinaryScrubUsesFractionalSecondsAndDoesNotBecomeSemanticRestart() {
        val fixture = Fixture()
        fixture.player.seekTo(12_345)
        fixture.player.seekTo(0)
        assertEquals(listOf(PlaybackDeviceCommand.seek(12.345), PlaybackDeviceCommand.seek(0.0)), fixture.commands)
        fixture.output.assertNoMutations()
    }

    @Test fun defaultsAndTimeUnsetUseJumpNotRawDefaultSeekOrNegativeWireSeek() {
        val fixture = Fixture()
        with(fixture.player) {
            seekToDefaultPosition()
            seekTo(C.TIME_UNSET)
            seekToDefaultPosition(0) // Physical 0 is key 22, NOT traversal queue[0] (key 11).
            seekTo(0, C.TIME_UNSET)
        }
        assertEquals(listOf(jump(11), jump(11), jump(22), jump(22)), fixture.commands)
        fixture.output.assertNoMutations()
    }

    @Test fun indexedSeeksResolvePhysicalOccurrenceAndOnlyRepresentableRequestsAreSent() {
        val fixture = Fixture()
        with(fixture.player) {
            seekTo(1, 2_500) // Physical 1 is current wire key 11.
            seekTo(0, 0) // Noncurrent occurrence can be selected by one jump.
            seekTo(0, 2_500) // Atomic jump-and-seek is NOT representable.
            seekTo(1, 0) // Current scrub is not a semantic restart.
        }
        assertEquals(listOf(PlaybackDeviceCommand.seek(2.5), jump(22), PlaybackDeviceCommand.seek(0.0)), fixture.commands)
        fixture.output.assertNoMutations()
    }

    @Test fun duplicateTracksRemainDistinctQueueOccurrences() {
        val fixture = Fixture()
        assertEquals(fixture.state.queue[0].id, fixture.state.queue[1].id)
        fixture.player.seekToDefaultPosition(0)
        fixture.player.seekToDefaultPosition(1)
        assertEquals(listOf(jump(22), jump(11)), fixture.commands)
        fixture.output.assertNoMutations()
    }

    @Test fun invalidMissingAndStalePhysicalKeysNeverSelectAnotherWireEntry() {
        val fixture = Fixture()
        fixture.player.seekToDefaultPosition(-1)
        fixture.player.seekToDefaultPosition(2)
        fixture.physicalKeys = listOf(99L, null)
        fixture.player.seekToDefaultPosition(0)
        fixture.player.seekTo(0, 0)
        fixture.player.seekToDefaultPosition(1)
        fixture.player.seekTo(1, 100)
        assertTrue(fixture.commands.isEmpty())
        fixture.output.assertNoMutations()
    }

    @Test fun relativeSeeksUseWireElapsedNotStaleRawPositionAndClampToWireDuration() {
        val fixture = Fixture()
        fixture.output.position = 999_000
        fixture.state = fixture.state.copy(elapsed = 2.25, duration = 10.0)
        fixture.player.seekBack()
        fixture.player.seekForward()
        fixture.state = fixture.state.copy(elapsed = 8.25)
        fixture.player.seekForward()
        assertEquals(listOf(PlaybackDeviceCommand.seek(0.0), PlaybackDeviceCommand.seek(7.25), PlaybackDeviceCommand.seek(10.0)), fixture.commands)
        assertFalse(fixture.output.reads.contains("getCurrentPosition"))
        fixture.output.assertNoMutations()
    }

    @Test fun unknownDurationStillAllowsRelativeSeekWithinProtocolBound() {
        val fixture = Fixture()
        fixture.state = fixture.state.copy(elapsed = 12.25, duration = 0.0)
        fixture.player.seekForward()
        fixture.state = fixture.state.copy(elapsed = 604799.0)
        fixture.player.seekForward()
        assertEquals(listOf(PlaybackDeviceCommand.seek(17.25), PlaybackDeviceCommand.seek(604800.0)), fixture.commands)
        fixture.output.assertNoMutations()
    }

    @Test fun outOfProtocolSeeksAndEmptyQueueDefaultsAreIgnored() {
        val fixture = Fixture()
        fixture.player.seekTo(-1)
        fixture.player.seekTo(Long.MAX_VALUE)
        fixture.state = PlaybackSnapshot()
        fixture.player.seekToDefaultPosition()
        fixture.player.seekToDefaultPosition(0)
        fixture.player.seekForward()
        fixture.player.seekBack()
        assertTrue(fixture.commands.isEmpty())
        fixture.output.assertNoMutations()
    }

    @Test fun shuffleUsesDesiredVersusLatestWireStateNotRawShuffle() {
        val fixture = Fixture()
        fixture.player.setShuffleModeEnabled(false)
        fixture.player.setShuffleModeEnabled(true)
        fixture.state = fixture.state.copy(shuffle = true)
        fixture.player.setShuffleModeEnabled(true)
        fixture.player.setShuffleModeEnabled(false)
        fixture.assertCommands("shuffle", "shuffle")
        fixture.output.assertNoMutations()
    }

    @Test fun repeatSendsExactlyOneCycleOnlyWhenDesiredIsNextRepresentableState() {
        val fixture = Fixture()
        val states = listOf("off" to Player.REPEAT_MODE_ALL, "all" to Player.REPEAT_MODE_ONE, "one" to Player.REPEAT_MODE_OFF)
        for ((current, next) in states) {
            fixture.state = fixture.state.copy(repeat = current)
            for (desired in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL, -99)) {
                val count = fixture.commands.size
                fixture.player.setRepeatMode(desired)
                assertEquals("$current -> $desired", count + if (desired == next) 1 else 0, fixture.commands.size)
            }
        }
        fixture.assertCommands("repeat", "repeat", "repeat")
        fixture.output.assertNoMutations()
    }

    @Test fun snapshotsAndPhysicalMappingAreSampledAtExecutionNotCachedAtConstruction() {
        val fixture = Fixture()
        fixture.player.seekToDefaultPosition()
        fixture.state = fixture.state.copy(position = 1)
        fixture.physicalKeys = listOf(11L, 22L)
        fixture.player.seekToDefaultPosition()
        fixture.player.seekTo(1, 4_125)
        assertEquals(listOf(jump(11), jump(22), PlaybackDeviceCommand.seek(4.125)), fixture.commands)
        fixture.output.assertNoMutations()
    }

    @Test fun allQueueAndSpeedOverloadsAreBlockedEvenForStaleOrSameProcessController() {
        val fixture = Fixture()
        val media = MediaItem.EMPTY
        val items = listOf(media)
        val blocked: List<() -> Unit> = with(fixture.player) {
            listOf(
                { setMediaItem(media) }, { setMediaItem(media, 0L) }, { setMediaItem(media, false) },
                { setMediaItems(items) }, { setMediaItems(items, false) }, { setMediaItems(items, 0, 0L) },
                { addMediaItem(media) }, { addMediaItem(0, media) }, { addMediaItems(items) }, { addMediaItems(0, items) },
                { removeMediaItem(0) }, { removeMediaItems(0, 1) },
                { replaceMediaItem(0, media) }, { replaceMediaItems(0, 1, items) },
                { moveMediaItem(0, 1) }, { moveMediaItems(0, 1, 2) }, { clearMediaItems() },
                { setPlaylistMetadata(MediaMetadata.EMPTY) },
                { setPlaybackParameters(PlaybackParameters.DEFAULT) }, { setPlaybackSpeed(1.5f) },
            )
        }
        blocked.forEachIndexed { index, operation ->
            assertThrows("Mutation overload $index", UnsupportedOperationException::class.java) { operation() }
        }
        assertTrue(fixture.commands.isEmpty())
        fixture.output.assertNoMutations()
    }

    @Test fun authorityDispatchFailureNeverFallsBackToOutput() {
        val output = Output()
        val player = CoordinatedSessionPlayer(output.player, { throw IllegalStateException("Authority unavailable") },
            { PlaybackSnapshot() }, { null })
        assertThrows(IllegalStateException::class.java) { player.play() }
        assertThrows(IllegalStateException::class.java) { player.stop() }
        output.assertNoMutations()
    }

    @Test fun advertisedCommandsExcludeRawQueueResumptionAndEveryUnsupportedMutator() {
        val fixture = Fixture()
        val blocked = listOf(
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_PREPARE,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_PLAYLIST_METADATA, Player.COMMAND_RELEASE,
            Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_SET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_VIDEO_SURFACE, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
        )
        for (command in blocked) {
            assertTrue("Fixture must advertise raw command $command", fixture.output.commands.contains(command))
            assertFalse("Raw capability leaked: $command", fixture.player.availableCommands.contains(command))
            assertFalse("Raw capability available: $command", fixture.player.isCommandAvailable(command))
        }
        for (command in listOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
        )) {
            assertTrue("Expected routed/read command $command", fixture.player.isCommandAvailable(command))
        }
        assertFalse(fixture.player.isCommandAvailable(Int.MAX_VALUE))
        fixture.output.assertNoMutations()
    }

    @Test fun advertisedCommandsTrackLiveOutputSupportWithoutSynthesizingCapabilities() {
        val fixture = Fixture()
        fixture.output.commands = Player.Commands.Builder().add(Player.COMMAND_GET_TIMELINE).build()
        assertEquals(fixture.output.commands, fixture.player.availableCommands)
        assertFalse(fixture.player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        fixture.output.commands = Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).add(Player.COMMAND_PREPARE).build()
        assertEquals(Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build(), fixture.player.availableCommands)
        fixture.output.assertNoMutations()
    }

    @Test fun capabilityListenersCannotReadvertiseRawCommandsAndInvisibleChangesAreSuppressed() {
        val fixture = Fixture()
        val changes = mutableListOf<Player.Commands>()
        val batches = mutableListOf<Player.Events>()
        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { changes += availableCommands }
            override fun onEvents(player: Player, events: Player.Events) {
                assertSame(fixture.player, player)
                batches += events
            }
        }
        fixture.player.addListener(listener)
        fixture.output.changeCommands(fixture.output.commands.buildUpon().remove(Player.COMMAND_PREPARE).build())
        assertTrue(changes.isEmpty())
        assertTrue(batches.isEmpty())
        fixture.output.changeCommands(fixture.output.commands.buildUpon().remove(Player.COMMAND_PLAY_PAUSE).build())
        assertEquals(listOf(fixture.player.availableCommands), changes)
        assertFalse(changes.single().contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertEquals(1, batches.size)
        assertTrue(batches.single().contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED))
        fixture.player.removeListener(listener)
        assertTrue(fixture.output.listeners.isEmpty())
        fixture.output.assertNoMutations()
    }

    @Test fun filteringInvisibleCommandEventPreservesUnrelatedEventsCallbacksAndListenerIdentity() {
        val fixture = Fixture()
        val batches = mutableListOf<Player.Events>()
        var playbackCallbacks = 0
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { playbackCallbacks++ }
            override fun onEvents(player: Player, events: Player.Events) {
                assertSame(fixture.player, player)
                batches += events
            }
        }
        fixture.player.addListener(listener)
        fixture.player.addListener(listener)
        assertEquals(1, fixture.output.listeners.size)
        fixture.output.listeners.forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
        fixture.output.changeCommands(fixture.output.commands.buildUpon().remove(Player.COMMAND_PREPARE).build(), Player.EVENT_PLAYBACK_STATE_CHANGED)
        assertEquals(1, playbackCallbacks)
        assertEquals(1, batches.size)
        assertTrue(batches.single().contains(Player.EVENT_PLAYBACK_STATE_CHANGED))
        assertFalse(batches.single().contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED))
        fixture.player.removeListener(listener)
        assertTrue(fixture.output.listeners.isEmpty())
        fixture.output.assertNoMutations()
    }

    @Test fun gettersRemainALiveReadThroughViewOfActualOutput() {
        val fixture = Fixture()
        fixture.output.position = 5432
        fixture.output.ready = true
        assertEquals(5432L, fixture.player.currentPosition)
        assertTrue(fixture.player.playWhenReady)
        fixture.output.state = Player.STATE_BUFFERING
        assertEquals(Player.STATE_BUFFERING, fixture.player.playbackState)
        fixture.output.assertNoMutations()
    }

    private class Fixture {
        val output = Output()
        val commands = mutableListOf<PlaybackDeviceCommand>()
        var physicalKeys: List<Long?> = listOf(22, 11)
        // Duplicate track IDs intentionally distinguish occurrence identity from track identity.
        var state = PlaybackSnapshot(queue = listOf(track(11, 1), track(22, 0)), position = 0)
        val player = CoordinatedSessionPlayer(output.player, commands::add, { state }, { physicalKeys.getOrNull(it) })

        fun assertCommands(vararg types: String) {
            assertEquals(types.map(PlaybackDeviceCommand::transport), commands)
        }
    }

    /** Any unexpected raw mutator fails the test at the moment it is invoked. */
    private class Output {
        var state = Player.STATE_READY
        var position = 0L
        var ready = false
        var commands = Player.Commands.Builder().addAllCommands().build()
        val reads = mutableListOf<String>()
        val mutations = mutableListOf<String>()
        val listeners = mutableSetOf<Player.Listener>()
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "getAvailableCommands" -> { reads += method.name; commands }
                "getPlaybackState" -> { reads += method.name; state }
                "getCurrentPosition" -> { reads += method.name; position }
                "getPlayWhenReady" -> { reads += method.name; ready }
                "getMediaItemCount" -> { reads += method.name; 2 }
                "getSeekBackIncrement" -> { reads += method.name; 3000L }
                "getSeekForwardIncrement" -> { reads += method.name; 5000L }
                else -> {
                    mutations += method.name
                    error("Session bypassed playback authority: ${method.name}")
                }
            }
        } as Player

        fun changeCommands(value: Player.Commands, vararg otherEvents: Int) {
            commands = value
            listeners.forEach { it.onAvailableCommandsChanged(value) }
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED).addAll(*otherEvents).build())
            listeners.forEach { it.onEvents(player, events) }
        }

        fun assertNoMutations() = assertEquals(emptyList<String>(), mutations)
    }

    private companion object {
        fun jump(key: Long) = PlaybackDeviceCommand.queue("jump", key)
        fun track(key: Long, order: Long) = PlaybackTrack("same-track", "Track", emptyList(), "Album", "album", 30.0, key = key, order = order)
    }
}
