package dev.spofify.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test fun moreLikeActionPreservesLoadingRetryAndToggleStates() {
        val liked = mutableStateOf<Boolean?>(null)
        val pending = mutableStateOf(false)
        val failed = mutableStateOf(false)
        var retries = 0
        var toggles = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                MusicActionsContent(LibraryItem("track", "song", "Song", hasAudio = true),
                    liked.value, pending.value, failed.value, onPlay = {}, onPlayNext = {}, onEnqueue = {},
                    onToggleLike = { toggles++ }, onRetryLike = { retries++ }, onAddToPlaylist = {}, onOpenItem = {})
            }
        }
        compose.onNodeWithText("Checking like status").assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
        compose.runOnIdle { failed.value = true }
        compose.onNodeWithText("Retry like status").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, toggles); liked.value = false }
        compose.onNodeWithText("Like song").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, toggles); pending.value = true }
        compose.onNodeWithText("Saving like").assertIsNotEnabled()
        compose.runOnIdle { pending.value = false; liked.value = true }
        compose.onNodeWithText("Unlike song").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, toggles); assertEquals(1, retries) }
    }

    @Test fun fourPriorityActionsAreIndependentOfSampledTransportState() {
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                MusicActionsContent(LibraryItem("track", "song", "Song", hasAudio = true),
                    liked = false, likePending = false, likeFailed = false,
                    onPlay = { events += "now" }, onPlayNext = { events += "next" },
                    onEnqueue = { error("High must use its explicit callback") }, onToggleLike = {}, onRetryLike = {},
                    onAddToPlaylist = {}, onOpenItem = {}, onQueueLow = { events += "low" },
                    onContextual = { events += "high" })
            }
        }
        for (label in listOf("Queue Low", "Queue High", "Play next", "Play now")) {
            compose.onNodeWithText(label).performScrollTo().assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("low", "high", "next", "now"), events) }
    }

    private fun checkReachability(fontScale: Float) {
        val item = LibraryItem("track", "song", "A long song title with many individually navigable music credits",
            artists = (1..24).map { ArtistCredit("artist-$it", "Credited artist $it") },
            albumId = "album", albumName = "Final credited album", hasAudio = true)
        val events = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(typography = AppTypography) {
                    // Supported component host: this proves body geometry, not native modal/Back acceptance.
                    Surface(Modifier.fillMaxSize()) {
                        MusicActionsContent(item, liked = false, likePending = false, likeFailed = false,
                            onPlay = { events += "play" }, onPlayNext = { events += "next" },
                            onEnqueue = { events += "queue" }, onQueueLow = { events += "low" }, onToggleLike = { events += "like" },
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
        for (label in listOf("Open details", "Add to playlist", "Queue Low", "Queue High", "Play next", "Play now", "Like song")) {
            val action = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val viewport = compose.onNodeWithTag("music-actions").getUnclippedBoundsInRoot()
            val target = action.getUnclippedBoundsInRoot()
            assertEquals(viewport.left.value + 24f, target.left.value, 0.5f)
            assertEquals(viewport.right.value - 24f, target.right.value, 0.5f)
            assertTrue("Action must be fully inside its scroll viewport",
                target.top >= viewport.top - 0.5.dp && target.bottom <= viewport.bottom + 0.5.dp)
            action.performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("album:album", "artist:artist-24", "track:song", "playlist", "low", "queue", "next", "play", "like"), events)
        }
    }
}
