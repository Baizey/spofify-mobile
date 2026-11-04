package dev.spofify.mobile.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicActionsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allCreditsAndActionsAreReachableOnNarrowPhoneAt135PercentText() = checkReachability(1.35f)

    @Test fun allCreditsAndActionsAreReachableOnNarrowPhoneAt200PercentText() = checkReachability(2f)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun checkReachability(fontScale: Float) {
        val item = LibraryItem("track", "song", "A long song title with many individually navigable music credits",
            artists = (1..24).map { ArtistCredit("artist-$it", "Credited artist $it") },
            albumId = "album", albumName = "Final credited album", hasAudio = true)
        val events = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(typography = AppTypography) {
                    ModalBottomSheet(onDismissRequest = { events += "dismiss" },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        MusicActionsContent(item, liked = false, likePending = false, likeFailed = false,
                            onPlay = { events += "play" }, onPlayNext = { events += "next" },
                            onEnqueue = { events += "queue" }, onToggleLike = { events += "like" },
                            onRetryLike = { events += "retry" }, onAddToPlaylist = { events += "playlist" },
                            onOpenItem = { events += "${it.kind}:${it.id}" })
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(events.isEmpty()) } // Opening/scrolling cannot start audio.
        compose.onNodeWithText("Final credited album").assertIsNotDisplayed()
        for ((label, expected) in listOf("Final credited album" to "album:album", "Credited artist 24" to "artist:artist-24")) {
            val credit = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val viewport = compose.onNodeWithTag("music-actions").getUnclippedBoundsInRoot()
            val target = credit.getUnclippedBoundsInRoot()
            assertTrue("Credit must be fully inside its scroll viewport",
                target.top >= viewport.top - 0.5.dp && target.bottom <= viewport.bottom + 0.5.dp)
            credit.performClick()
            compose.runOnIdle { assertEquals(expected, events.last()) }
        }
        // Traverse back through the actual actions, not a duplicate test-only menu.
        for (label in listOf("Open details", "Add to playlist", "Add to queue", "Play next", "Play")) {
            val action = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val viewport = compose.onNodeWithTag("music-actions").getUnclippedBoundsInRoot()
            val target = action.getUnclippedBoundsInRoot()
            assertEquals(viewport.left.value + 24f, target.left.value, 0.5f)
            assertEquals(viewport.right.value - 24f, target.right.value, 0.5f)
            assertTrue("Action must be fully inside its scroll viewport",
                target.top >= viewport.top - 0.5.dp && target.bottom <= viewport.bottom + 0.5.dp)
            action.performClick()
        }
        compose.onNodeWithContentDescription("Like song").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(listOf("album:album", "artist:artist-24", "track:song", "playlist", "queue", "next", "play", "like"), events)
        }
    }
}
