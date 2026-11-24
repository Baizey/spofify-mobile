package dev.spofify.mobile.core.personal

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import dev.spofify.mobile.core.auth.AccountIdentity
import org.junit.Assert.*
import org.junit.Test

class AccountPersonalReplicaTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val summary = PersonalPlaylistSummary("p", "Duplicates", "", PersonalPlaylistKind.MANUAL,
        PersonalCountState.EXACT, 2, 2, PersonalPlaylistCapabilities(true, true, false, true, false, true, true), 1, "", "")
    private val track = PersonalTrack("track", "song", "Song", true, PersonalCoverage(1, 1, 1))
    private val one = PersonalManualEntry("e1", 0, track)
    private val two = PersonalManualEntry("e2", 1, track)
    private val detail = AccountPersonalReplica.Resource.Detail(summary.id)
    private val lists = AccountPersonalReplica.Resource.Lists
    private val history = AccountPersonalReplica.Resource.History

    @Test fun relaunchKeepsDuplicateOccurrencesAndDistinctLocalPlaysScopedToAccount() {
        val temp = Files.createTempDirectory("personal-cache-test").toFile()
        try {
            val identity = AccountIdentity("https://example.test", "a", "A", 1)
            val file = PersonalCacheFile(temp, identity)
            val replica = AccountPersonalReplica(clock)
            replica.restore(file.read())
            replica.replaceLists(0, listOf(summary, summary.copy(id = "summary-only")))
            replica.replaceDetail(0, summary, listOf(one, two))
            val local = PersonalHistoryEntry("local", now.toString(), track, origin = "offline")
            replica.recordLocal(local)
            replica.recordLocal(local.copy(id = "second"))
            file.write(replica.snapshots.value)
            val relaunched = AccountPersonalReplica(clock)
            relaunched.restore(PersonalCacheFile(temp, identity.copy(incarnation = 2)).read())
            assertFalse(relaunched.stale(detail))
            assertFalse(relaunched.stale(lists))
            assertTrue(AccountPersonalReplica(Clock.offset(clock, java.time.Duration.ofMinutes(5))).apply {
                restore(file.read())
            }.stale(detail))
            assertNull(relaunched.view.value.details["summary-only"])
            assertEquals(listOf("e1", "e2"), relaunched.view.value.details.getValue(summary.id).rows.map { it.id })
            assertFalse(relaunched.view.value.details.getValue(summary.id).rows.first().track.hasAudio)
            relaunched.availability(false, setOf(track.id))
            relaunched.replaceHistory(0, listOf(local, local.copy(id = "server", origin = null)))
            assertEquals(setOf("local", "second", "server"), relaunched.view.value.recent.map { it.id }.toSet())
            assertTrue(relaunched.view.value.recent.all { it.track.hasAudio })
            assertEquals(PersonalCacheSnapshot(), PersonalCacheFile(temp, identity.copy(accountId = "b")).read())
            assertEquals(PersonalCacheSnapshot(), PersonalCacheFile(temp, identity.copy(origin = "https://other.test")).read())
        } finally { temp.deleteRecursively() }
    }

    @Test fun confirmedChangeFencesOlderRefreshAndTargetsOccurrence() {
        val replica = AccountPersonalReplica(clock)
        replica.restore(PersonalCacheSnapshot())
        replica.replaceLists(0, listOf(summary))
        replica.replaceDetail(0, summary, listOf(one, two))
        replica.receive(PersonalUpdate(snapshot = PersonalSnapshot(listOf(summary), emptyList())))
        val listVersion = replica.version(lists)
        val detailVersion = replica.version(detail)
        val edit = summary.copy(name = "edited", trackCount = 1, availableCount = 1, revision = 2)
        replica.receive(PersonalUpdate(change = PersonalChange(playlist = edit, entries = EntryChange(removed = one.id))))
        replica.replaceLists(listVersion, emptyList())
        replica.replaceDetail(detailVersion, summary, listOf(one, two))
        assertEquals(edit, replica.view.value.playlists.single())
        assertEquals(listOf(two.id), replica.view.value.details.getValue(summary.id).rows.map { it.id })
    }

    @Test fun olderHistoryRefreshCannotUndoSocketDeltaOrLocalPlay() {
        val replica = AccountPersonalReplica(clock)
        replica.restore(PersonalCacheSnapshot())
        val online = PersonalHistoryEntry("event", now.toString(), track)
        val offline = online.copy(origin = "offline")
        replica.receive(PersonalUpdate(snapshot = PersonalSnapshot(emptyList(), listOf(online))))
        val old = replica.version(history)
        replica.recordLocal(offline)
        replica.receive(PersonalUpdate(change = PersonalChange(recent = RecentChange(removed = listOf(RecentKey(online.id))))))
        replica.replaceHistory(old, listOf(online))
        assertEquals(listOf("offline"), replica.view.value.recent.map { it.origin })
    }
}
