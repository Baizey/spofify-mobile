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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.playback.PlaybackListAction
import dev.spofify.mobile.playback.defaultQueueAction
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
                    liked.value, pending.value, failed.value, onPlay = {}, onPlayNext = {}, onQueue = {},
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

    @Test fun queueIsOneActionSeparateFromPlayNextAndPlayNow() {
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                MusicActionsContent(LibraryItem("track", "song", "Song", hasAudio = true),
                    liked = false, likePending = false, likeFailed = false,
                    onPlay = { events += "now" }, onPlayNext = { events += "next" },
                    onQueue = { events += "queue" }, onToggleLike = {}, onRetryLike = {},
                    onAddToPlaylist = {}, onOpenItem = {})
            }
        }
        compose.onAllNodesWithText("Queue").assertCountEquals(1)
        compose.onNodeWithText("Queue High").assertDoesNotExist()
        compose.onNodeWithText("Queue Low").assertDoesNotExist()
        for (label in listOf("Queue", "Play next", "Play now")) {
            compose.onNodeWithText(label).performScrollTo().assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("queue", "next", "now"), events) }
    }

    @Test fun allItemKindsSharePlaybackOrderWithOneQueueAndHonestLibraryCapabilities() {
        val item = mutableStateOf(LibraryItem("track", "opaque/song", "Song", hasAudio = true))
        val queued = mutableListOf<PlaybackListAction>()
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                MusicActionsContent(item.value, liked = false,
                    onPlay = {}, onPlayNext = {}, onQueue = { queued += item.value.defaultQueueAction() },
                    onToggleLike = {}, onRetryLike = {}, onAddToPlaylist = {}, onOpenItem = {}, playbackEnabled = true)
            }
        }
        for (kind in listOf("track", "album", "artist", "playlist", "derived", "likes")) {
            compose.runOnIdle { item.value = LibraryItem(kind, "opaque/$kind", "Music", hasAudio = true) }
            compose.onAllNodesWithText("Queue").assertCountEquals(1)
            compose.onNodeWithText("Queue High").assertDoesNotExist()
            compose.onNodeWithText("Queue Low").assertDoesNotExist()
            val now = compose.onNodeWithText("Play now").getUnclippedBoundsInRoot()
            val next = compose.onNodeWithText("Play next").getUnclippedBoundsInRoot()
            val queue = compose.onNodeWithText("Queue").getUnclippedBoundsInRoot()
            assertTrue("Shared playback order must not depend on item kind", now.bottom <= next.top && next.bottom <= queue.top)
            compose.onNodeWithText("Queue").performScrollTo().performClick()
            if (kind == "track") {
                compose.onNodeWithText("Like song").assertExists()
                compose.onNodeWithText("Add to playlist").assertExists()
            } else {
                compose.onNodeWithText("Library").assertDoesNotExist()
                compose.onNodeWithText("Like song").assertDoesNotExist()
                compose.onNodeWithText("Add to playlist").assertDoesNotExist()
            }
        }
        assertEquals(listOf(PlaybackListAction.QUEUE_HIGH) + List(5) { PlaybackListAction.QUEUE_LOW }, queued)
    }

    @Test fun tracksOmitRedundantDetailsButPreserveRealMetadataAndMissingAlbumHonesty() {
        val item = mutableStateOf(LibraryItem("track", "opaque/song", "Song", albumId = "opaque/album", albumName = "Album"))
        val opened = mutableListOf<LibraryItem>()
        compose.setContent {
            MaterialTheme(colorScheme = dev.spofify.mobile.listeningColorScheme(), typography = AppTypography) {
                Surface { MusicActionsContent(item.value, onOpenItem = opened::add) }
            }
        }
        compose.onNodeWithText("Open details").assertDoesNotExist()
        compose.onNodeWithText("Song information").assertDoesNotExist()
        dev.spofify.mobile.capturePolishEvidence("track-album-navigation", compose.onRoot())
        compose.onNodeWithText("Go to album").performScrollTo().performClick()
        assertEquals("opaque/album", opened.single().id)
        assertEquals("album", opened.single().kind)
        compose.runOnIdle { item.value = item.value.copy(albumId = null, trackNumber = 2, genres = listOf("Ambient")) }
        compose.onNodeWithText("Go to album").assertDoesNotExist()
        compose.onNode(hasText("Album") and hasClickAction()).assertIsNotEnabled()
        dev.spofify.mobile.capturePolishEvidence("track-metadata-missing-album", compose.onRoot())
        compose.onNodeWithText("Song information").performScrollTo().performClick()
        assertEquals("opaque/song", opened.last().id)
        assertEquals("track", opened.last().kind)
        compose.runOnIdle { item.value = item.value.copy(albumName = null, trackNumber = 0, genres = emptyList()) }
        compose.onNodeWithText("Navigation").assertDoesNotExist()
        compose.onNodeWithText("Song information").assertDoesNotExist()
    }

    @Test fun unsupportedActionsAreOmittedWithoutInventingPlaybackOrLibraryCapabilities() {
        compose.setContent {
            MaterialTheme {
                MusicActionsContent(LibraryItem("album", "opaque/album", "Album"), onQueue = {}, onOpenItem = {})
            }
        }
        compose.onNodeWithText("Play now").assertDoesNotExist()
        compose.onNodeWithText("Play next").assertDoesNotExist()
        compose.onNodeWithText("Library").assertDoesNotExist()
        compose.onNodeWithText("Queue").assertExists()
        compose.onNodeWithText("Open details").performScrollTo().assertIsEnabled()
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
                            onQueue = { events += "queue" }, onToggleLike = { events += "like" },
                            onRetryLike = { events += "retry" }, onAddToPlaylist = { events += "playlist" },
                            onOpenItem = { events += "${it.kind}:${it.id}" })
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(events.isEmpty()) } // Opening/scrolling cannot start audio.
        compose.onNodeWithText("Open details").assertDoesNotExist()
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
        for (label in listOf("Add to playlist", "Queue", "Play next", "Play now", "Like song")) {
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
            assertEquals(listOf("album:album", "artist:artist-24", "playlist", "queue", "next", "play", "like"), events)
        }
    }
}
