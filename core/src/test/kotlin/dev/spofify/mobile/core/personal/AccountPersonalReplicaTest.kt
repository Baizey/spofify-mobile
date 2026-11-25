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

    @Test fun exactlyQuotaSnapshotIsCompleteAndDiskAloneNeverGrantsPlaylistAuthority() {
        val r = replica(PersonalCacheSnapshot(listOf(summary), details = mapOf("p" to ReplicaPlaylist(1, listOf(one, two)))))
        assertFalse(r.confirmsPlaylist("p"))
        assertFalse(r.view.value.playlists.single().capabilities.editEntries)
        assertEquals(listOf(one.id, two.id), r.view.value.details.getValue("p").rows.map { it.id })
        val canonical = List(100) { summary.copy(id = "new-$it") }
        r.receiveSnapshot(PersonalSnapshot(canonical, emptyList()))
        assertTrue(r.view.value.listsComplete)
        assertEquals(canonical, r.view.value.playlists)
        assertFalse(r.confirmsPlaylist("p"))
        assertFalse("p" in r.snapshots.value.details)
        assertTrue("p" in r.snapshots.value.deletedPlaylists)
        assertTrue(r.confirmsPlaylist(canonical.first().id))
    }

    @Test fun confirmedDeleteFencesLateDetailAndManifestHydrationAfterReopenEvenWhenCapped() {
        val r = replica()
        r.receiveSnapshot(PersonalSnapshot(listOf(summary), emptyList()))
        val beforeDelete = r.version("p")
        r.replaceDetail(beforeDelete, summary, listOf(one, two))
        r.receiveChange(PersonalChange(deleted = "p"))
        r.replaceDetail(beforeDelete, summary, listOf(one, two))
        r.retainDownload(summary, listOf(one, two))
        assertFalse(r.snapshots.value.playlists.any { it.id == "p" })
        val file = PersonalCacheFile(root.root, identity)
        file.write(r.snapshots.value)
        val reopened = replica(file.read())
        reopened.receiveSnapshot(PersonalSnapshot(List(100) { summary.copy(id = "list-$it") }, emptyList()))
        reopened.retainDownload(summary, listOf(one, two))
        assertFalse(reopened.snapshots.value.playlists.any { it.id == "p" })
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
        val edited = summary.copy(revision = 3, trackCount = 1, availableCount = 1)
        assertEquals("p", r.receiveChange(PersonalChange(playlist = edited, entries = EntryChange(removed = two.id))))
        assertTrue(r.detailStale("p"))
        assertEquals(listOf(two.copy(position = 4), one.copy(position = 8)), r.snapshots.value.details.getValue("p").rows)
        r.replaceDetail(r.version("p"), edited, listOf(one.copy(position = 1)))
        assertFalse(r.detailStale("p"))
        assertEquals(listOf(one.copy(position = 1)), r.snapshots.value.details.getValue("p").rows)
    }

    @Test fun nonReadyRulesRepliesPreserveAcceptedRowsAndRecoverAfterDiskReopen() {
        val ready = summary.copy(kind = PersonalPlaylistKind.DERIVED, evaluationState = PersonalEvaluationState.READY)
        for (evaluation in listOf(PersonalEvaluationState.STALE, PersonalEvaluationState.ERROR, PersonalEvaluationState.PENDING)) {
            val r = replica()
            r.availability(true, setOf(track.id))
            r.receiveSnapshot(PersonalSnapshot(listOf(ready), emptyList()))
            r.replaceDetail(r.version("p"), ready, listOf(one, two))
            assertTrue(r.canPlaySharedPlaylist("p"))
            assertFalse(r.detailStale("p"))
            val nonReady = ready.copy(revision = 2, evaluationState = evaluation,
                countState = if (evaluation == PersonalEvaluationState.PENDING) PersonalCountState.PENDING else PersonalCountState.STALE,
                trackCount = 1L.takeUnless { evaluation == PersonalEvaluationState.PENDING },
                availableCount = 1L.takeUnless { evaluation == PersonalEvaluationState.PENDING })
            val wireRows = if (nonReady.trackCount == null) emptyList() else listOf(two.copy(id = "not-last-good"))
            // A scoped reply may reveal materialization status before another account list arrives.
            r.replaceDetail(r.version("p"), nonReady, wireRows)
            assertEquals(nonReady, r.snapshots.value.playlists.single())
            assertEquals(ReplicaPlaylist(ready.revision, listOf(one, two)), r.snapshots.value.details.getValue("p"))
            assertTrue(r.detailStale("p"))
            assertFalse(r.canPlaySharedPlaylist("p"))
            val file = PersonalCacheFile(root.root, identity)
            file.write(r.snapshots.value)
            val reopened = replica(file.read())
            reopened.availability(false, setOf(track.id))
            assertEquals(listOf(one, two), reopened.view.value.details.getValue("p").rows)
            assertFalse(reopened.canPlaySharedPlaylist("p"))
            val recovered = ready.copy(revision = 3, trackCount = 1, availableCount = 1)
            reopened.availability(true, emptySet())
            reopened.receiveSnapshot(PersonalSnapshot(listOf(recovered), emptyList()))
            assertFalse(reopened.canPlaySharedPlaylist("p")) // A ready list summary cannot authorize old rows.
            reopened.replaceDetail(reopened.version("p"), recovered, listOf(two))
            assertEquals(listOf(two), reopened.snapshots.value.details.getValue("p").rows)
            assertEquals(at.toEpochMilli(), reopened.snapshots.value.details.getValue("p").refreshedAt)
            assertTrue(reopened.canPlaySharedPlaylist("p"))
        }
    }

    @Test fun statusOnlyPendingIsNotEmptyAndOnlyCompleteStoredRowsCanSeedACache() {
        val pending = summary.copy(kind = PersonalPlaylistKind.DERIVED, evaluationState = PersonalEvaluationState.PENDING,
            countState = PersonalCountState.PENDING, trackCount = null, availableCount = null)
        val definition = JsonObject(emptyMap())
        val part = PlaylistPart(pending, definition, "a".repeat(64), 0, emptyList(), false)
        val r = replica()
        r.availability(true, emptySet())
        r.receiveSnapshot(PersonalSnapshot(listOf(pending), emptyList()))
        val version = r.version("p")
        assertThrows(IllegalArgumentException::class.java) { PlaylistTransfer("p", version).accept(part.copy(rows = listOf(one))) }
        r.replaceDetail(version, pending, requireNotNull(PlaylistTransfer("p", version).accept(part)))
        assertFalse("p" in r.snapshots.value.details)
        val stale = pending.copy(evaluationState = PersonalEvaluationState.ERROR, countState = PersonalCountState.STALE,
            trackCount = 2, availableCount = 2)
        val stored = part.copy(summary = stale, rows = listOf(one, two))
        assertThrows(IllegalArgumentException::class.java) { PlaylistTransfer("p", version).accept(stored.copy(rows = listOf(one))) }
        assertThrows(IllegalArgumentException::class.java) { PlaylistTransfer("p", version).accept(stored.copy(rows = listOf(one, one))) }
        assertThrows(IllegalArgumentException::class.java) { PlaylistTransfer("p", version).accept(stored.copy(rows = listOf(two, one))) }
        val inconsistent = stored.copy(summary = stale.copy(countState = PersonalCountState.EXACT))
        assertThrows(IllegalArgumentException::class.java) { PlaylistTransfer("p", version).accept(inconsistent) }
        r.replaceDetail(version, stale, requireNotNull(PlaylistTransfer("p", version).accept(stored)))
        assertEquals(ReplicaPlaylist(stale.revision, listOf(one, two)), r.snapshots.value.details.getValue("p"))
        assertTrue(r.detailStale("p"))
        assertFalse(r.canPlaySharedPlaylist("p"))
        assertFalse(r.view.value.playlists.single().capabilities.play)
        assertTrue(r.view.value.details.getValue("p").rows.none { it.track.hasAudio })
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
