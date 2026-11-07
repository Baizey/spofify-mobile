package dev.spofify.mobile.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.feature.playlists.PlaylistOccurrenceActionsContent
import dev.spofify.mobile.ui.theme.AppTypography
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** HOST component renders only: no installed Android, native sheet motion/Back or output authority proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SongActionSheetContentTest {
    @get:Rule val compose = createComposeRule()
    private val track = LibraryItem("track", "song/opaque", "A song for the evening", hasAudio = true,
        artists = listOf(ArtistCredit("artist/one", "First artist"), ArtistCredit("artist/two", "Second artist")),
        albumId = "album/opaque", albumName = "Evening sessions")

    @Test fun neutralGroupedSupportedActionsAndNavigationRender() {
        val targets = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.fillMaxSize()) {
                    MusicActionsContent(track, false, false, false, {}, {}, {}, {}, {}, {},
                        onOpenItem = { targets += "${it.kind}:${it.id}" }, onQueueLow = {})
                }
            }
        }
        compose.onNodeWithText("Playback").assertIsDisplayed()
        compose.onNodeWithText("Library").assertIsDisplayed()
        capture("supported-top")
        for ((name, target) in listOf("First artist" to "artist:artist/one", "Second artist" to "artist:artist/two",
            "Evening sessions" to "album:album/opaque")) {
            compose.onNodeWithText(name).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(target, targets.last())
        }
        capture("supported-navigation")
        compose.onNodeWithText("Remove from this playlist").assertDoesNotExist()
    }

    @Test fun unavailablePendingAndRtlLargeFontRemainHonestAndReachable() {
        var writes = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface(Modifier.fillMaxSize()) {
                        MusicActionsContent(track.copy(hasAudio = false), null, true, false,
                            {}, {}, {}, { writes++ }, { writes++ }, {}, {}, onQueueLow = {})
                    }
                }
            }
        }
        for (label in listOf("Play now", "Play next", "Queue High", "Queue Low", "Saving like")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
                .assertHeightIsAtLeast(48.dp)
        }
        capture("rtl-font200-pending")
        compose.onNodeWithText("Evening sessions").performScrollTo().assertIsDisplayed()
        assertEquals(0, writes)
        capture("rtl-font200-navigation")
    }

    @Test fun removalIsCapabilitySuppliedDisabledAndKeepsExactCapturedOccurrence() {
        val enabled = mutableStateOf(false)
        val remove = mutableStateOf<(() -> Unit)?>(null)
        val removed = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.fillMaxSize()) {
                    PlaylistOccurrenceActionsContent(track, false, false, null, true, true,
                        {}, {}, {}, {}, {}, {}, {}, remove.value, enabled.value, {}, {})
                }
            }
        }
        compose.onNodeWithText("Remove from this playlist").assertDoesNotExist()
        // Duplicate songs do not imply duplicate deletion; this callback holds one occurrence, not song ID.
        compose.runOnIdle { remove.value = { removed += "entry/second-duplicate" } }
        capture("occurrence-supported")
        compose.onNodeWithText("Remove from this playlist").performScrollTo().assertIsNotEnabled()
        capture("remove-disabled")
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("Remove from this playlist").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("entry/second-duplicate"), removed)
        capture("remove-enabled")
    }

    @Test fun occurrenceUncertainStateOffersReadNotMutationAndStalePresentationDisablesActions() {
        val current = mutableStateOf(true)
        var reads = 0
        var writes = 0
        val notice = "The like response was not confirmed. Check saved status before another change; nothing was resubmitted."
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.fillMaxSize()) {
                    PlaylistOccurrenceActionsContent(track, null, false, notice, true, current.value,
                        {}, {}, {}, {}, {}, {}, {}, { writes++ }, true, { reads++ }, { writes++ })
                }
            }
        }
        compose.onNodeWithText("Like song").assertDoesNotExist()
        compose.onNodeWithText(notice).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check like status").performScrollTo().performClick()
        assertEquals(1, reads)
        assertEquals(0, writes)
        capture("occurrence-uncertain")
        compose.runOnIdle { current.value = false }
        for (label in listOf("Play now", "Queue High", "Queue Low", "Check like status", "Add to playlist",
            "First artist", "Evening sessions", "Remove from this playlist")) {
            compose.onNodeWithText(label).performScrollTo().assertIsNotEnabled()
        }
        capture("occurrence-stale")
        assertEquals(0, writes)
    }

    @Test fun equalNamedArtistsKeepIndependentOpaqueTargets() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.fillMaxSize()) {
                    SongActionLayout(track) {
                        SongActionNavigation(track.copy(artists = listOf(ArtistCredit("opaque/one", "Same name"),
                            ArtistCredit("opaque/two", "Same name")))) { opened += it.id }
                    }
                }
            }
        }
        compose.onAllNodesWithText("Same name").assertCountEquals(2)
        compose.onAllNodesWithText("Same name")[0].assertHeightIsAtLeast(48.dp).performClick()
        compose.onAllNodesWithText("Same name")[1].assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("opaque/one", "opaque/two"), opened)
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-xhdpi")
    fun shortLandscapeViewportReachesTerminalContextAction() {
        var removed = 0
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.fillMaxSize()) {
                    PlaylistOccurrenceActionsContent(track, false, false, null, true, true,
                        {}, {}, {}, {}, {}, {}, {}, { removed++ }, true, {}, {})
                }
            }
        }
        compose.onNodeWithText("Remove from this playlist").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, removed)
        capture("landscape-terminal")
    }

    @Test fun missingOpaqueIdsAreHonestNonNavigableCredits() {
        var navigation = 0
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface(Modifier.fillMaxSize()) {
                    SongActionLayout(track) {
                        SongActionNavigation(track.copy(artists = listOf(ArtistCredit("", "Unknown artist")),
                            albumId = null)) { navigation++ }
                    }
                }
            }
        }
        compose.onNodeWithText("Unknown artist").assertIsNotEnabled()
        compose.onNodeWithText("Evening sessions").assertIsNotEnabled()
        assertEquals(0, navigation)
    }

    private fun capture(name: String) {
        val output = File(System.getProperty("java.io.tmpdir"), "song-action-renders").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { stream ->
            check(compose.onNodeWithTag("music-actions").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
