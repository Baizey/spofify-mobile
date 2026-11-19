package dev.spofify.mobile.core.personal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPersonalReplicaTest {
    private val summary = PersonalPlaylistSummary("p", "Duplicates", "", PersonalPlaylistKind.MANUAL,
        PersonalCountState.EXACT, 2, 2, PersonalPlaylistCapabilities(true, true, false, true, false, true, true), 1, "", "")
    private val track = PersonalTrack("track", "song", "Song", true, PersonalCoverage(1, 1, 1))
    private val one = PersonalManualEntry("e1", 0, track)
    private val two = PersonalManualEntry("e2", 1, track)

    @Test fun snapshotIsAtomicAndReconnectKeepsRowsWithoutReplayingActions() {
        val replica = AccountPersonalReplica()
        val initial = PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary), emptyList()))
        assertNull(replica.watch(summary.id))
        assertEquals(listOf(PersonalIntent("syncPlaylist", playlistId = summary.id)), replica.receive(initial))
        assertEquals(listOf(PersonalIntent("syncPlaylist", playlistId = summary.id, known = "t", offset = 1)),
            replica.receive(PersonalUpdate(part = PersonalPart(summary, "t", 0, listOf(one), true))))
        assertTrue(replica.view.value.details.isEmpty())
        replica.receive(PersonalUpdate(part = PersonalPart(summary, "t", 1, listOf(two), false)))
        assertEquals(listOf(one, two), replica.view.value.details.getValue(summary.id).rows)
        replica.disconnected()
        assertFalse(replica.view.value.connected)
        assertEquals(listOf(one, two), replica.view.value.details.getValue(summary.id).rows)
        assertEquals(listOf(PersonalIntent("syncPlaylist", playlistId = summary.id, known = "t")), replica.receive(initial))
        replica.clear()
        assertEquals(PersonalReplicaView(), replica.view.value)
    }

    @Test fun deltasTargetPlaylistAndHistoryOccurrencesNotRecordingIds() {
        val replica = AccountPersonalReplica()
        val online = PersonalHistoryEntry("event", "2025-01-01T00:00:00Z", track)
        val offline = online.copy(origin = "offline")
        replica.watch(summary.id)
        replica.receive(PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary), listOf(online, offline))))
        replica.receive(PersonalUpdate(part = PersonalPart(summary, "t", 0, listOf(one, two), false)))
        val edit = PersonalUpdate(change = PersonalChange(playlist = summary.copy(trackCount = 1, revision = 2),
            entries = EntryChange(removed = one.id), recent = RecentChange(removed = listOf(RecentKey(online.id)))))
        val wire = Json.encodeToString(edit)
        assertEquals(emptyList<PersonalIntent>(), replica.receive(Json.decodeFromString<PersonalUpdate>(wire)))
        assertEquals(listOf(two), replica.view.value.details.getValue(summary.id).rows)
        assertEquals(listOf(offline), replica.view.value.recent)
    }
}
