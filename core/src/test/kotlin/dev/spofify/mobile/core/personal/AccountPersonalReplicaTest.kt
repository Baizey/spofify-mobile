package dev.spofify.mobile.core.personal

import dev.spofify.mobile.core.auth.AccountIdentity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountPersonalReplicaTest {
    @get:Rule val root = TemporaryFolder()
    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private val track = PersonalTrack("track", "t", "Song", true, PersonalCoverage(1, 1, 1))
    private val summary = PersonalPlaylistSummary("p", "Saved", "", PersonalPlaylistKind.MANUAL,
        PersonalCountState.EXACT, 2, 2, PersonalPlaylistCapabilities(true, true, false, true, false, true, true), 1, "", "")
    private val one = PersonalManualEntry("one", 4, track)
    private val two = PersonalManualEntry("two", 8, track)
    private fun replica(saved: PersonalCacheSnapshot = PersonalCacheSnapshot()) =
        AccountPersonalReplica(Clock.fixed(at, ZoneOffset.UTC)).apply { restore(saved) }
    private val identity = AccountIdentity("https://example.invalid", "account", "fixture", 1)

    @Test fun cappedPrefixDoesNotDeleteUnmentionedAcceptedDetailsButShortSnapshotDoes() {
        val r = replica(PersonalCacheSnapshot(listOf(summary), details = mapOf("p" to ReplicaPlaylist(1, listOf(one, two)))))
        val capped = List(100) { summary.copy(id = "new-$it") }
        r.receiveSnapshot(PersonalSnapshot(capped, emptyList()))
        assertFalse(r.view.value.listsComplete)
        assertEquals(101, r.view.value.playlists.size)
        assertEquals(listOf(one, two), r.snapshots.value.details.getValue("p").rows)
        r.receiveSnapshot(PersonalSnapshot(capped.dropLast(1), emptyList()))
        assertTrue(r.view.value.listsComplete)
        assertFalse(r.knowsPlaylist("p"))
        assertFalse("p" in r.snapshots.value.details)
    }

    @Test fun confirmedDeleteFencesLateDetailAndManifestHydrationAfterReopenEvenWhenCapped() {
        val r = replica()
        r.receiveSnapshot(PersonalSnapshot(listOf(summary), emptyList()))
        val beforeDelete = r.version("p")
        r.replaceDetail(beforeDelete, summary, listOf(one, two))
        r.receiveChange(PersonalChange(deleted = "p"))
        r.replaceDetail(beforeDelete, summary, listOf(one, two))
        r.retainDownload(summary, listOf(one, two))
        assertFalse(r.knowsPlaylist("p"))
        val file = PersonalCacheFile(root.root, identity)
        file.write(r.snapshots.value)
        val reopened = replica(file.read())
        reopened.receiveSnapshot(PersonalSnapshot(List(100) { summary.copy(id = "list-$it") }, emptyList()))
        reopened.retainDownload(summary, listOf(one, two))
        assertFalse(reopened.knowsPlaylist("p"))
        assertFalse("p" in reopened.snapshots.value.details)
    }

    @Test fun reconnectAndDiskReopenPreserveDistinctRepeatedTrackEventsAndOrigins() {
        val r = replica()
        val online = PersonalHistoryEntry("same-event", at.toString(), track)
        val fromOtherDevice = online.copy(origin = "offline")
        val local = online.copy(id = "local", origin = "offline")
        r.receiveSnapshot(PersonalSnapshot(emptyList(), listOf(online)))
        r.recordLocal(local)
        r.disconnected()
        val file = PersonalCacheFile(root.root, identity)
        file.write(r.snapshots.value)
        val reopened = replica(file.read())
        reopened.receiveSnapshot(PersonalSnapshot(emptyList(), listOf(online, fromOtherDevice)))
        assertEquals(3, reopened.view.value.recent.size)
        reopened.receiveChange(PersonalChange(recent = RecentChange(removed = listOf(RecentKey(online.id)))))
        assertEquals(setOf("local" to "offline", "same-event" to "offline"), reopened.view.value.recent.map { it.id to it.origin }.toSet())
        assertTrue(PersonalCacheFile(root.root, identity.copy(accountId = "different")).read().recent.isEmpty())
    }

    @Test fun manualMoveKeepsOccurrenceOrderAndStoredSlotsAndFencesOlderReads() {
        val r = replica()
        r.receiveSnapshot(PersonalSnapshot(listOf(summary), emptyList()))
        val version = r.version("p")
        r.replaceDetail(version, summary, listOf(one, two))
        r.receiveChange(PersonalChange(playlist = summary.copy(revision = 2), entries = EntryChange(moved = two.id)))
        r.replaceDetail(version, summary, listOf(one, two))
        assertEquals(listOf(two.copy(position = 4), one.copy(position = 8)), r.snapshots.value.details.getValue("p").rows)
        r.receiveChange(PersonalChange(playlist = summary.copy(revision = 3, trackCount = 1, availableCount = 1),
            entries = EntryChange(removed = two.id)))
        assertEquals(listOf(one.copy(position = 7)), r.snapshots.value.details.getValue("p").rows)
    }

    @Test fun transferChecksContinuationAndAllowsGoTokenResetWithoutCollapsingTrackDuplicates() {
        val derived = summary.copy(kind = PersonalPlaylistKind.DERIVED, evaluationState = PersonalEvaluationState.READY)
        val definition = JsonObject(emptyMap())
        val token = "a".repeat(64)
        val initial = PlaylistPart(derived, definition, token, 0, listOf(one), true)
        val bad = PlaylistTransfer("p", 1)
        assertNull(bad.accept(initial))
        assertThrows(IllegalArgumentException::class.java) { bad.accept(initial.copy(offset = 1, rows = listOf(one), more = false)) }
        val short = PlaylistTransfer("p", 1)
        assertThrows(IllegalArgumentException::class.java) { short.accept(initial.copy(more = false)) }
        val good = PlaylistTransfer("p", 1)
        assertNull(good.accept(initial))
        assertEquals(1, good.command().offset)
        assertEquals(token, good.command().known)
        val replacement = initial.copy(token = "b".repeat(64), rows = listOf(two.copy(position = 3)))
        assertNull(good.accept(replacement))
        assertEquals(listOf(two.copy(position = 3), one), good.accept(replacement.copy(offset = 1, rows = listOf(one), more = false)))
    }
}
