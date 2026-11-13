package dev.spofify.mobile.playback

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicBoolean

/** Real graph/client/coordinator/controller lifetimes using the current controlled socket.
 * Authoritative snapshots/results are supplied explicitly; this fixture is NOT a server reducer,
 * collection resolver, executor, media or native-device acceptance test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraphPlaybackParticipationTest {
    private fun fixture(block: suspend TestScope.(OnlineQueueConnectionFixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { OnlineQueueConnectionFixture(this).use { block(it) } }
        finally { Dispatchers.resetMain() }
    }
    private fun server(fixture: OnlineQueueConnectionFixture): MockWebServer =
        OnlineQueueConnectionFixture::class.java.getDeclaredField("web").let {
            it.isAccessible = true; it.get(fixture) as MockWebServer
        }
    private fun loginResponse(account: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setHeader("Set-Cookie", "spofify_session=synthetic-successor; Path=/; HttpOnly; Max-Age=3600")
        .setBody("""{"accountId":"$account","username":"listener","role":"user","csrfToken":"synthetic-csrf"}""")
    private fun observe(connection: PlaybackConnection) {
        // Presentation-only attachment: no MediaController/service creation or output permission.
        PlaybackConnection::class.java.getDeclaredField("observing").apply { isAccessible = true; setBoolean(connection, true) }
        PlaybackConnection::class.java.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(connection)
    }
    @Test fun repeatedRestoreAndPresentationReattachmentRetainOneCoordinatorWithoutMusicOrQueueImport() = fixture { f ->
        val session = requireNotNull(f.client.currentSession())
        val owner = f.owner
        val snapshot = f.snapshot
        repeat(3) {
            assertSame(session, f.graph.restore())
            assertSame(owner, f.graph.playbackCoordinator)
        }
        f.connection.release()
        assertTrue(owner.state.value.connected)
        assertEquals(snapshot, owner.state.value.snapshot)
        val replacement = PlaybackConnection(ApplicationProvider.getApplicationContext<SpofifyApplication>())
        try {
            observe(replacement)
            assertSame(owner, f.graph.playbackCoordinator)
            assertEquals(snapshot.state.queue.map { it.key }, replacement.state.value.remoteSnapshot!!.queue.map { it.key })
            assertFalse(replacement.state.value.isPlaying)
            assertTrue(f.commands.isEmpty())
            assertTrue("Display attachment must never install server music into the local queue", PlaybackService.displayLocalSnapshot().queue.isEmpty())
            f.assertNoHttpPlayback()
        } finally { replacement.release() }
    }
    @Test fun graphClearRetiresPendingConnectionAndNeverReplaysUncertainCommands() = fixture { f ->
        val results = mutableListOf<PlaybackActionResult>()
        f.connection.queueIntent(LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef("one")), f.client,
            PlaybackListAction.PLAY_NOW, f.connection.state.value.interactionKey, f.owner.captureBinding(), onResult = results::add)
        assertEquals(1, f.commands.size)
        assertTrue(results.isEmpty())
        f.graph.fenceForLogout(f.client)
        f.graph.clear(f.client)
        runCurrent()
        assertFalse(f.owner.state.value.connected)
        assertNull(f.owner.captureBinding())
        assertEquals(PlaybackActionStatus.UNAVAILABLE, results.single().status)
        assertNull(f.graph.client)
        advanceTimeBy(20_001); runCurrent()
        assertEquals(1, results.size)
        assertEquals(1, f.commands.size)
        assertTrue(PlaybackService.displayLocalSnapshot().queue.isEmpty())
        f.assertNoHttpPlayback()
    }
    @Test fun replacementAccountRejectsHeldClientAndLateOldUnauthorizedCannotRetireSuccessor() = fixture { f ->
        val oldSession = requireNotNull(f.client.currentSession())
        val oldUnauthorized = requireNotNull(f.client.onUnauthorized)
        val binding = requireNotNull(f.owner.captureBinding())
        val key = f.connection.state.value.interactionKey
        val successor = f.graph.configure(f.client.server)
        server(f).enqueue(loginResponse("other-account"))
        successor.login("listener", "synthetic-password")
        runCurrent()
        val successorSession = requireNotNull(successor.currentSession())
        assertEquals("other-account", successorSession.accountId)
        assertNotEquals(oldSession.accountId, successorSession.accountId)
        assertNull(f.client.currentSession())
        oldUnauthorized()
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertSame(successor, f.graph.client)
        assertSame(successorSession, successor.currentSession())
        assertFalse(f.graph.sessionExpired.value)
        val results = mutableListOf<PlaybackActionResult>()
        f.connection.queueIntent(LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef("old-account-song")), f.client,
            PlaybackListAction.QUEUE_HIGH, key, binding, onResult = results::add)
        assertEquals(PlaybackActionStatus.STALE, results.single().status)
        assertTrue(f.commands.isEmpty())
        assertEquals("Only the two declared synthetic logins", 2, server(f).requestCount)
    }
    @Test fun equalValuedReauthenticationCannotRefreshAHeldSignedSelectionOrBinding() = fixture { f ->
        val session = requireNotNull(f.client.currentSession())
        val binding = requireNotNull(f.owner.captureBinding())
        val key = f.connection.state.value.interactionKey
        val context = LibraryPlaybackContext("derived", "rules", "Rules", 7, "original-fence")
        val selection = LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef("duplicate", targetId = "rules",
            occurrenceId = "ordered/path/second", selectionToken = "opaque.original.signature"), context)
        server(f).enqueue(loginResponse(session.accountId))
        f.client.login("listener", "synthetic-password")
        assertEquals(session, f.client.currentSession())
        assertNotSame(session, f.client.currentSession())
        val results = mutableListOf<PlaybackActionResult>()
        f.connection.queueIntent(selection, f.client, PlaybackListAction.PLAY_NOW, key, binding,
            isCurrent = { f.client.currentSession() === session }, onResult = results::add)
        assertEquals(PlaybackActionStatus.STALE, results.single().status)
        assertTrue(f.commands.isEmpty())
        assertEquals(2, server(f).requestCount)
    }
    @Test fun exactManualAndDerivedRowsSendOnlyIdentifiersAndWaitForAuthoritativeAcceptance() = fixture { f ->
        val manual = queueIntentSelection(LibraryItem("track", "duplicate", "Untrusted display title",
            hasAudio = true, entryId = "manual-second"), LibraryPlaybackContext("playlist", "manual", "Mix"))
        val manualResults = mutableListOf<PlaybackActionResult>()
        f.connection.queueIntent(manual, f.client, PlaybackListAction.PLAY_NOW, f.connection.state.value.interactionKey,
            f.owner.captureBinding(), onResult = manualResults::add)
        assertTrue(manualResults.isEmpty())
        val first = (f.commands.single().command as LibraryPlaybackCommand.QueueIntent).selection as LibraryPlaybackSelection.Track
        assertEquals("manual-second", first.track.entryId)
        assertNull(first.track.occurrenceId)
        f.result(placement = "replace", execution = "confirmed", count = 3)
        assertNotEquals(PlaybackActionStatus.PLAYING, manualResults.single().status)
        val derivedContext = LibraryPlaybackContext("derived", "rules", "Rules", 9, "fresh-fence")
        val token = "opaque.signature_-/+="
        val derived = queueIntentSelection(LibraryItem("track", "duplicate", "Display only", hasAudio = true,
            entryId = "not-a-manual-entry"), derivedContext, "ordered/path/second", token)
        val results = mutableListOf<PlaybackActionResult>()
        f.connection.queueIntent(derived, f.client, PlaybackListAction.QUEUE_HIGH, f.connection.state.value.interactionKey,
            f.owner.captureBinding(), onResult = results::add)
        val sent = f.commands.last()
        val encoded = Json.parseToJsonElement(LibraryPlaybackCodec.encode(sent, commandId = "2")).jsonObject
        val command = encoded.getValue("command").jsonObject
        assertEquals(setOf("type", "intent", "selection"), command.keys)
        val selection = command.getValue("selection").jsonObject
        val ref = selection.getValue("track").jsonObject
        assertEquals(setOf("id", "targetId", "occurrenceId", "selectionToken"), ref.keys)
        assertEquals(token, ref.getValue("selectionToken").jsonPrimitive.content)
        assertEquals("ordered/path/second", ref.getValue("occurrenceId").jsonPrimitive.content)
        assertFalse(sent.toString().contains(token))
        assertEquals(derivedContext, (derived as LibraryPlaybackSelection.Track).context)
        assertTrue(results.isEmpty())
        f.result(placement = "high", execution = "unchanged")
        assertEquals(PlaybackActionStatus.ADDED, results.single().status)
        assertEquals(1, results.single().acceptedCount)
        assertEquals(2, f.commands.size)
        f.assertNoHttpPlayback()
    }
    @Test fun canceledSourceBeforePostedDispatchNeverResolvesUploadsOrStarts() = fixture { f ->
        val live = AtomicBoolean(true)
        val results = mutableListOf<PlaybackActionResult>()
        val key = f.connection.state.value.interactionKey
        val binding = f.owner.captureBinding()
        val thread = Thread {
            f.connection.queueIntent(LibraryPlaybackSelection.Collection(LibraryPlaybackContext("album", "album", "Album")),
                f.client, PlaybackListAction.PLAY_NOW, key, binding, isCurrent = live::get, onResult = results::add)
        }
        thread.start(); thread.join(1_000)
        assertFalse("Owned dispatch thread must terminate", thread.isAlive)
        assertTrue(results.isEmpty())
        live.set(false)
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(PlaybackActionStatus.STALE, results.single().status)
        assertTrue(f.commands.isEmpty())
        assertTrue(PlaybackService.displayLocalSnapshot().queue.isEmpty())
        f.assertNoHttpPlayback()
    }
    @Test fun completeLongDerivedSnapshotRetainsEveryOccurrenceAndNeverBecomesLocalQueueAuthority() = fixture { f ->
        val context = LibraryPlaybackContext("derived", "rules", "Rules", 7, "fresh-fence")
        val tracks = (1L..1001L).map { key -> LibraryPlaybackTrack("duplicate", "Song $key", emptyList(), "Album", "album", 120.0,
            key, 1002 - key, "low", targetId = "rules", occurrenceId = "ordered/path/🎵-$key", playbackContext = context) }
        val before = f.snapshot
        val state = before.state.copy(queue = tracks, position = 17, context = context, shuffle = true,
            occurrence = LibraryPlaybackOccurrence(18, "duplicate", "event", 17.875, true))
        f.publish(before.copy(stateVersion = 2, state = state, playback = before.playback.copy(stateVersion = 2)))
        val display = f.connection.state.value
        assertEquals(1001, display.queue.size)
        assertEquals(17, display.queueIndex)
        assertEquals("wire-18", display.currentEntryId)
        assertEquals("ordered/path/🎵-18", display.item?.entryId)
        assertEquals(tracks.map { it.key }, display.remoteSnapshot!!.queue.map { it.key })
        assertEquals(tracks.map { it.occurrenceId }, display.remoteSnapshot!!.queue.map { it.occurrenceId })
        assertEquals(tracks.map { it.order }, display.remoteSnapshot!!.queue.map { it.order })
        assertEquals("ordered/path/🎵-1001", display.remoteSnapshot!!.queue.last().occurrenceId)
        assertTrue(display.remoteSnapshot!!.queue.all { it.entryId.isEmpty() })
        assertTrue(display.queue.all { it.libraryPath == null })
        assertTrue(PlaybackService.displayLocalSnapshot().queue.isEmpty())
        assertTrue(f.commands.isEmpty())
        val results = mutableListOf<PlaybackActionResult>()
        f.connection.queueIntent(LibraryPlaybackSelection.Collection(LibraryPlaybackContext("album", "oversized", "Album")),
            f.client, PlaybackListAction.QUEUE_LOW, display.interactionKey, f.owner.captureBinding(), onResult = results::add)
        assertTrue(results.isEmpty())
        assertEquals(display.remoteSnapshot, f.connection.state.value.remoteSnapshot)
        f.result(outcome = "rejected", code = "capacity", count = 0, placement = "none")
        assertEquals(PlaybackActionStatus.FULL, results.single().status)
        assertEquals(0, results.single().acceptedCount)
        assertEquals(display.remoteSnapshot, f.connection.state.value.remoteSnapshot)
        assertEquals(1, f.commands.size)
        f.assertNoHttpPlayback()
    }
}
