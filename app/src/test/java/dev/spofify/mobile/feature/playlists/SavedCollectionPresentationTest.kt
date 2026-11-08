package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.capturePolishEvidence
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PersonalPlaylistSummary
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.core.saved.SavedLibraryStore
import dev.spofify.mobile.core.saved.SavedLocalEligibility
import dev.spofify.mobile.core.saved.SavedScope
import kotlinx.coroutines.runBlocking
import org.junit.rules.TemporaryFolder
import dev.spofify.mobile.core.saved.SavedCollectionSnapshot
import dev.spofify.mobile.core.saved.SavedDownloadState
import dev.spofify.mobile.core.saved.SavedOccurrence
import dev.spofify.mobile.core.saved.SavedOccurrenceSnapshot
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind
import dev.spofify.mobile.feature.library.musicTypeLabel
import dev.spofify.mobile.feature.library.MusicRowActionGuard
import dev.spofify.mobile.feature.library.sourceCurrent as sourceLifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCollectionPresentationTest {
    private fun snapshot(kind: SavedTargetKind = SavedTargetKind.ALBUM,
        state: SavedDownloadState = SavedDownloadState.PARTIAL, updating: Boolean = false,
        failure: String? = null, rows: List<SavedOccurrenceSnapshot> = listOf(
            SavedOccurrenceSnapshot(SavedOccurrence("one", "track", "Song"), null, true),
            SavedOccurrenceSnapshot(SavedOccurrence("two", "track", "Song"), null, true),
            SavedOccurrenceSnapshot(SavedOccurrence("missing", "absent", "Missing", hasAudio = false), null, false),
        )) = SavedCollectionSnapshot(SavedTarget(kind, "same-id"), "Collection", "generation", rows,
            state, updating, 4096, failure)

    @Test fun savedEntitiesKeepTheirTrueKindsAndIdentity() {
        for ((kind, wireKind) in listOf(SavedTargetKind.ALBUM to "album", SavedTargetKind.ARTIST to "artist",
            SavedTargetKind.MANUAL_PLAYLIST to "playlist", SavedTargetKind.DERIVED_PLAYLIST to "derived")) {
            val item = snapshot(kind).libraryItem()
            assertEquals(wireKind, item.kind)
            assertEquals("same-id", item.id)
            assertEquals(SavedTarget(kind, "same-id"), item.savedTarget())
        }
        assertNull(LibraryItem("track", "track", "Song").savedTarget())
    }

    @Test fun collectionAccessibilityNeverCallsPlaylistsSongs() {
        assertEquals("Playlist", musicTypeLabel("playlist"))
        assertEquals("Derived playlist", musicTypeLabel("derived"))
        assertEquals("Album", musicTypeLabel("album"))
        assertEquals("Artist", musicTypeLabel("artist"))
        assertEquals("Song", musicTypeLabel("track"))
    }

    @Test fun duplicateOccurrencesRemainCountedAndMissingAudioPreventsCompletion() {
        val presentation = snapshot(state = SavedDownloadState.COMPLETE).presentation()
        assertEquals(2, presentation.ready)
        assertEquals(3, presentation.total)
        assertEquals(SavedDownloadPhase.PARTIAL, presentation.phase)
        assertEquals(4096L, presentation.transferredBytes)
    }

    @Test fun incompleteReplacementIsNotAnEndlessDownload() {
        val presentation = snapshot(state = SavedDownloadState.COMPLETE, updating = true).presentation()
        assertEquals(SavedDownloadPhase.PARTIAL, presentation.phase)
        assertTrue(presentation.updating)
        assertEquals(SavedDownloadPhase.DOWNLOADING, snapshot(state = SavedDownloadState.DOWNLOADING).presentation().phase)
    }

    @Test fun cancelledPartialIsResumableWithoutFailureBlob() {
        val presentation = snapshot(state = SavedDownloadState.FAILED, failure = "cancelled").presentation()
        assertEquals(SavedDownloadPhase.PAUSED, presentation.phase)
        assertNull(presentation.failure)
        assertEquals("Download could not finish", snapshot(failure = "arbitrary remote error").presentation().failure)
        assertEquals("Not enough storage", snapshot(failure = "storage").presentation().failure)
        assertEquals("Download could not finish", snapshot(failure = "auth").presentation().failure)
    }

    @Test fun coherentEmptyCollectionCanBeComplete() {
        assertEquals(SavedDownloadPhase.COMPLETE,
            snapshot(state = SavedDownloadState.COMPLETE, rows = emptyList()).presentation().phase)
    }

    @Test fun onlyPlaylistTargetsMergeWithServerPlaylistRows() {
        assertFalse(SavedTarget(SavedTargetKind.ALBUM, "same-id").matchesPlaylist("same-id"))
        assertFalse(SavedTarget(SavedTargetKind.ARTIST, "same-id").matchesPlaylist("same-id"))
        assertTrue(SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, "same-id").matchesPlaylist("same-id"))
        assertTrue(SavedTarget(SavedTargetKind.DERIVED_PLAYLIST, "same-id").matchesPlaylist("same-id"))
        assertFalse(SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, "same-id").matchesPlaylist("other"))
    }

    @Test fun unchangedDetailRefreshPairsControlsWithTheActuallyRetainedSummary() {
        val source = SpofifyClient(ServerAddress.parse("https://saved.example"), object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        })
        try {
            val browse = PlaylistDetailBrowseMemory(0)
            val original = PersonalPlaylistSummary("playlist", "Saved playlist", trackCount = 1, availableCount = 1, revision = 1)
            val firstRead = PlaylistSourceStamp(source)
            browse.publishRead(original, firstRead, 7)
            val retained = browse.summary.value
            val equalResponse = original.copy()
            assertNotSame(original, equalResponse)
            assertEquals(original, equalResponse)
            val refreshedRead = PlaylistSourceStamp(source)
            browse.publishRead(equalResponse, refreshedRead, 8)
            assertSame("Compose deliberately keeps the equal summary reference", retained, browse.summary.value)
            val refreshedControl = requireNotNull(browse.savedControlFrame.value)
            assertSame("Controls must pair with the actual retained value", browse.summary.value, refreshedControl.summary)
            assertSame(refreshedRead, browse.readSource.value)
            assertSame(refreshedRead, refreshedControl.source)
            assertEquals(8L, refreshedControl.bindingRevision)
            browse.publishRead(equalResponse, PlaylistSourceStamp(source), null)
            assertNull("A missing originating Owner frame never falls back to fresh authority", browse.savedControlFrame.value)
        } finally {
            source.httpClient.dispatcher.cancelAll()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun demandFrontierUsesRenderedServerRowsNotRelocatedSavedRows() {
        assertEquals(listOf("server-2", "server-3", "server-4"),
            savedIndexFrontier(listOf("server-1", "server-2", "server-3", "server-4"),
                SavedTarget(SavedTargetKind.MANUAL_PLAYLIST, "server-page-tail")))
    }

    @Test fun entirelyFilteredServerRowsUseTheActualSavedListBottom() {
        assertEquals(listOf("saved:ALBUM:last-saved"),
            savedIndexFrontier(emptyList(), SavedTarget(SavedTargetKind.ALBUM, "last-saved")))
    }

    @Test fun emptyRenderedListHasNoArtificialDemandAnchor() {
        assertTrue(savedIndexFrontier(emptyList(), null).isEmpty())
    }

    @Test fun bytesAreActualBinaryUnitsWithoutMadeUpPercentage() {
        assertEquals("0 B", savedByteLabel(0))
        assertEquals("512 B", savedByteLabel(512))
        assertEquals("1.0 KiB", savedByteLabel(1024))
        assertEquals("1.0 MiB", savedByteLabel(1024L * 1024))
        assertEquals("1.0 GiB", savedByteLabel(1024L * 1024 * 1024))
    }
}

/** Synthetic component frames/semantics only, not installed-device or audio acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h520dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedDownloadControlRenderTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun withLocalOwner(test: (SavedDownloadsOwner, SpofifyClient, SavedScope, SavedTarget) -> Unit) {
        RuntimeEnvironment.setFontScale(1f)
        val root = temporary.newFolder("frame-ui")
        val scope = SavedScope("https://saved.example", "account")
        val target = SavedTarget(SavedTargetKind.ARTIST, "artist")
        SavedLibraryStore(root, SavedLocalEligibility { it == scope }).use { store ->
            store.bind(scope)
            val stage = store.beginManifest(scope, target, "Saved artist", "generation", 1)
            store.appendManifest(stage, 0, listOf(SavedOccurrence("entry", "missing", "Missing song", hasAudio = false)), terminal = true)
            store.finishManifest(stage)
        }
        val source = SpofifyClient(ServerAddress.parse(scope.origin), object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        })
        val owner = SavedDownloadsOwner { root }
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            assertTrue(owner.bindLocal(source, scope))
            assertEquals(1, runBlocking { requireNotNull(owner.access { true }).collections().size })
            test(owner, source, scope, target)
        } finally {
            runBlocking { owner.close() }
            source.httpClient.dispatcher.cancelAll()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    private fun mount(state: SavedDownloadPresentation, network: Boolean = true, scale: Float = 1f,
        rtl: Boolean = false, save: () -> Unit = {}, cancel: () -> Unit = {}, unsave: () -> Unit = {}) {
        RuntimeEnvironment.setFontScale(scale)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface(Modifier.width(320.dp).testTag("saved-controls")) {
                        SavedDownloadControl(state, save, {}, cancel, unsave, networkEnabled = network)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun serverRetentionUsesCapturedSourceButNeverOutputAdmission() = withLocalOwner { owner, _, _, target ->
        val sourceCurrent = mutableStateOf(true)
        val outputCurrent = mutableStateOf(false)
        val action = MusicRowActionGuard({ sourceCurrent.value }, { outputCurrent.value })
        val frameRevision = owner.state.value.bindingRevision
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.width(320.dp)) {
                    SavedCollectionControl(owner, target, "Server artist", isCurrent = sourceLifetime(action),
                        expectedBindingRevision = frameRevision)
                }
            }
        }
        compose.onNodeWithText("Unsave").assertIsEnabled()
        compose.runOnIdle { outputCurrent.value = true }
        compose.onNodeWithText("Unsave").assertIsEnabled()
        compose.runOnIdle { outputCurrent.value = false; sourceCurrent.value = false }
        compose.onNodeWithText("Unsave").assertIsNotEnabled()
        assertEquals(1, runBlocking { requireNotNull(owner.access { true }).collections().size })
    }

    @Test fun staleParentFrameDisablesRetentionAgainstFreshChildState() = withLocalOwner { owner, source, scope, target ->
        val parentRevision = owner.state.value.bindingRevision
        assertTrue(owner.bindLocal(source, scope))
        runBlocking { owner.access { true } }
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.width(320.dp)) {
                    SavedCollectionControl(owner, target, "Old parent title", expectedBindingRevision = parentRevision)
                }
            }
        }
        compose.onNodeWithText("Unsave").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(1, runBlocking { requireNotNull(owner.access { true }).collections().size })
    }

    @Test fun openDialogAndHeldMoreDismissOnBindingRevisionChange() = withLocalOwner { owner, source, scope, _ ->
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.width(320.dp)) {
                    val frame = owner.state.collectAsState().value
                    frame.collections.singleOrNull()?.let { snapshot ->
                        SavedCollectionIndexRow(snapshot, owner, frame.bindingRevision)
                    }
                }
            }
        }
        compose.onNodeWithText("Saved artist").performClick()
        compose.onNodeWithContentDescription("More actions for Missing song").performClick()
        assertTrue(compose.onAllNodesWithText("Audio unavailable").fetchSemanticsNodes().isNotEmpty())
        assertTrue(owner.bindLocal(source, scope))
        runBlocking { owner.access { true } }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("More actions for Missing song").assertCountEquals(0)
        compose.onAllNodesWithText("Audio unavailable").assertCountEquals(0)
        assertEquals(1, runBlocking { requireNotNull(owner.access { true }).collections().size })
    }

    @Test fun initialSaveIsOneExplicit48DpActionWithoutHelperCopy() {
        var saves = 0
        mount(SavedDownloadPresentation(SavedDownloadPhase.NOT_SAVED), save = { saves++ })
        compose.onNodeWithText("Save").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithText("Unsave").assertDoesNotExist()
        compose.onNodeWithText("offline", substring = true, ignoreCase = true).assertDoesNotExist()
        capturePolishEvidence("saved-controls-initial", compose.onNodeWithTag("saved-controls"))
    }

    @Test fun offlineQueuedWorkCanBeCancelledOrUnsavedWithoutStartingPlayback() {
        var cancellations = 0; var removals = 0
        mount(SavedDownloadPresentation(SavedDownloadPhase.QUEUED, ready = 1, total = 3), network = false,
            cancel = { cancellations++ }, unsave = { removals++ })
        compose.onNodeWithText("Cancel").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Unsave").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, cancellations); assertEquals(1, removals) }
        compose.onNodeWithText("Play").assertDoesNotExist()
        capturePolishEvidence("saved-controls-queued", compose.onNodeWithTag("saved-controls"))
    }

    @Test fun fontTwoRtlPartialControlsStayReachableAndRetryNeedsNetwork() {
        mount(SavedDownloadPresentation(SavedDownloadPhase.PARTIAL, ready = 2, total = 3,
            failure = "Some audio is unavailable"), network = false, scale = 2f, rtl = true)
        compose.onNodeWithText("Retry").assertIsDisplayed().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Unsave").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("2/3 downloaded").assertIsDisplayed()
        compose.onNodeWithText("Some audio is unavailable").assertIsDisplayed()
        capturePolishEvidence("saved-controls-rtl-font2", compose.onNodeWithTag("saved-controls"))
    }
}
