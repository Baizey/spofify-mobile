package dev.spofify.mobile.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.Coverage
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.feature.playlists.PlaylistTrackRow
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Shared-row callback evidence; no output authority or native playback is fabricated here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h960dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SongBodyActivationTest {
    @get:Rule val compose = createComposeRule()
    private val song = LibraryItem("track", "same-song", "Song", hasAudio = true)

    @Test fun songBodyUsesScopedPlayNowOnceAndMoreLongPressAndLikeDoNotBubble() {
        var plays = 0
        var more = 0
        var likes = 0
        var toggles = 0
        var source: (() -> Boolean)? = null
        val visible = mutableStateOf(true)
        val feedback = mutableStateOf(CatalogPlaybackFeedback(song.id, isPlaying = true,
            onToggle = { toggles++ }, interactionKey = PlaybackInteractionKey(),
            onPlayNow = { selected, current -> assertEquals(song, selected); assertTrue(current()); source = current; plays++ }))
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                if (visible.value) MusicRow(song, { fail("Songs do not navigate") }, { _, _ -> fail("Use guarded Play now") },
                    { more++ }, { fail("No queue fallback") }, { _, modifier -> Box(modifier) },
                    modifier = Modifier.width(320.dp).testTag("song-row"), playback = feedback.value,
                    like = CatalogLikeState(liked = false), onToggleLike = { likes++ })
            }
        }
        compose.runOnIdle { assertEquals(0, plays) }
        compose.onNodeWithText("Song").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, plays); assertEquals(0, toggles) }
        compose.onNodeWithContentDescription("More actions for Song").assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        compose.onNodeWithText("Song").performTouchInput { longClick() }
        val actions = compose.onNodeWithText("Song").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            actions.single { it.label == "Like song" }.action()
            assertEquals(1, plays); assertEquals(2, more); assertEquals(1, likes); assertEquals(0, toggles)
            feedback.value = feedback.value.copy(buffering = true)
        }
        compose.runOnIdle {
            assertTrue("Progress/buffering does not retire source", source!!.invoke())
            actions.single { it.label == "Play now" }.action()
            assertEquals(2, plays)
            feedback.value = feedback.value.copy(interactionKey = PlaybackInteractionKey())
        }
        compose.runOnIdle {
            assertTrue("Admitted source is not cancelled by the action's output transition", source!!.invoke())
            visible.value = false
        }
        compose.runOnIdle { assertFalse("Disposal retires an admitted row source", source!!.invoke()) }
    }

    @Test fun artistAndAlbumNavigateWhileUnavailableOrDisabledSongsNeverPlay() {
        val item = mutableStateOf(song.copy(kind = "artist", coverage = Coverage(inLibrary = 2)))
        val enabled = mutableStateOf(true)
        val opened = mutableListOf<String>()
        var plays = 0
        compose.setContent {
            MaterialTheme {
                MusicRow(item.value, { opened += it.kind }, { _, _ -> plays++ }, {}, {}, { _, modifier -> Box(modifier) },
                    modifier = Modifier.width(320.dp), playbackEnabled = enabled.value)
            }
        }
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { item.value = song.copy(kind = "album", coverage = Coverage(inLibrary = 2)) }
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { item.value = song.copy(hasAudio = false) }
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { item.value = song; enabled.value = false }
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { assertEquals(listOf("artist", "album"), opened); assertEquals(0, plays) }
    }

    @Test fun retainedBodyCannotBorrowOutputOrOccurrenceABAOrLatestEnablement() {
        val original = PlaybackInteractionKey()
        val feedback = mutableStateOf(CatalogPlaybackFeedback(interactionKey = original))
        val identity = mutableStateOf("entry-A")
        val enabled = mutableStateOf(true)
        var plays = 0
        compose.setContent {
            MaterialTheme {
                MusicRow(song, { fail("No navigation") }, { _, _ -> plays++ }, {}, {}, { _, modifier -> Box(modifier) },
                    modifier = Modifier.width(320.dp), playback = feedback.value, rowIdentity = identity.value,
                    playbackEnabled = enabled.value)
            }
        }
        fun click() = checkNotNull(compose.onNodeWithText("Song").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val first = click()
        compose.runOnIdle { enabled.value = false }
        compose.runOnIdle { first(); assertEquals(0, plays); enabled.value = true }
        compose.runOnIdle { first(); assertEquals(1, plays); feedback.value = feedback.value.copy(interactionKey = PlaybackInteractionKey()) }
        compose.runOnIdle { first(); assertEquals(1, plays); feedback.value = feedback.value.copy(interactionKey = original) }
        compose.runOnIdle { first(); assertEquals(1, plays) }
        val next = click()
        compose.runOnIdle { identity.value = "entry-B" }
        compose.runOnIdle { next(); assertEquals(1, plays); identity.value = "entry-A" }
        compose.runOnIdle { next(); assertEquals(1, plays) }
        compose.onNodeWithText("Song").performTouchInput { down(center) }
        compose.runOnIdle { feedback.value = feedback.value.copy(interactionKey = PlaybackInteractionKey()) }
        compose.onNodeWithText("Song").performTouchInput { up() }
        compose.runOnIdle { assertEquals("Pointer-down cannot retarget to the replacement output", 1, plays) }
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { assertEquals(2, plays) }
    }

    @Test fun sharedRowsRetainTargetsAndRenderAt320dpAndLargeFont() {
        val scale = mutableStateOf(1f)
        val rows = listOf(song.copy(name = "Playable song 音楽 🌙"), song.copy(name = "Unavailable song", hasAudio = false),
            song.copy(kind = "artist", name = "Artist", coverage = Coverage(inLibrary = 2)),
            song.copy(kind = "album", name = "Album", coverage = Coverage(inLibrary = 2)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                MaterialTheme(typography = AppTypography) {
                    Column(Modifier.width(320.dp).testTag("rows")) {
                        rows.forEach { item -> MusicRow(item, {}, { _, _ -> }, {}, {}, { _, modifier -> Box(modifier) }) }
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { scale.value = fontScale }
            rows.forEach { item ->
                compose.onNodeWithContentDescription("More actions for ${item.name}")
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
            val image = compose.onNodeWithTag("rows").captureToImage().asAndroidBitmap()
            val root = File(System.getProperty("java.io.tmpdir"), "song-body-renders").apply { mkdirs() }
            File(root, "rows-320-font${fontScale.toInt()}.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @Test fun playlistBodyRetainsExactDuplicateOccurrenceScopedTransport() {
        val occurrence = mutableStateOf("duplicate-B")
        val plays = mutableListOf<String>()
        var more = 0
        compose.setContent {
            MaterialTheme {
                val exact = occurrence.value
                PlaylistTrackRow(song, { fail("No track details") }, { _, _ -> more++ }, enabled = true,
                    onPlay = { current -> assertTrue(current()); plays += exact }, occurrenceBinding = exact,
                    playback = CatalogPlaybackFeedback(interactionKey = PlaybackInteractionKey(),
                        onPlayNow = { _, _ -> fail("Never replace an occurrence through catalog playback") }),
                    artwork = { _, modifier -> Box(modifier) })
            }
        }
        compose.onNodeWithText("Song").performTouchInput { click() }
        compose.onNodeWithContentDescription("More actions for Song").performClick()
        compose.runOnIdle { assertEquals(listOf("duplicate-B"), plays); assertEquals(1, more); occurrence.value = "duplicate-C" }
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { assertEquals(listOf("duplicate-B", "duplicate-C"), plays) }
    }
}
