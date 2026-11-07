package dev.spofify.mobile.feature.playlists

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.capturePolishEvidence
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Finite component frames only; route source/uncertain-write guards stay in their HTTP tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistDialogCopyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun storageAndLegacyErrorsStayConciseWithoutBackendTextOrCountLectures() {
        fun error(status: Int, code: String? = null) = dev.spofify.mobile.core.ApiException(status, "Synthetic backend detail", code)
        assertEquals("Playlist is too large to save.", playlistFailure(error(422, "PLAYLIST_STORAGE_EXCEEDED")))
        assertEquals("Playlist is too large to copy.", playlistFailure(error(422, "COPY_LIMIT_EXCEEDED")))
        assertEquals("Playlist change is too large.", playlistFailure(error(413)))
        assertEquals("Could not save playlist. Check its name and rules.", playlistFailure(error(422)))
        assertEquals("Save unconfirmed. Check saved playlists.", playlistFailure(error(503), mutation = true))
        assertEquals("Playlist changed. Check saved playlist.", playlistFailure(error(409), mutation = true))
    }

    @Test fun copyHasOnlyNeededFieldsAndActions() = copy(1f)
    @Test fun copyRemainsCompactAndReachableAtFontTwo() = copy(2f)
    private fun copy(fontScale: Float) {
        // Dialog owns a separate Android composition root: set synthetic resources too.
        RuntimeEnvironment.setFontScale(fontScale)
        assertEquals(fontScale, RuntimeEnvironment.getFontScale(), .01f)
        var dismiss = 0
        var checks = 0
        val saves = mutableListOf<Pair<String, String>>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    PlaylistMetadataDialog("Copy playlist", "Source (copy)", "", busy = false, blocked = false,
                        message = null, onDismiss = { dismiss++ }, onVerify = { checks++ },
                        onSave = { name, description -> saves += name to description }, saveLabel = "Create")
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Copy playlist").assertIsDisplayed()
        compose.onNodeWithText("Name").assertExists()
        compose.onNodeWithText("Description (optional)").assertExists()
        for (copy in listOf("Copies the complete result", "maximum", "Original rules", "fence", "snapshot")) {
            compose.onNodeWithText(copy, substring = true).assertDoesNotExist()
        }
        compose.onNodeWithText("Check").assertDoesNotExist()
        compose.onNodeWithText("Create").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertTrue(saves.isEmpty()); assertEquals(0, checks); assertEquals(0, dismiss)
        capturePolishEvidence("copy-component-font${fontScale.toInt()}", compose.onNode(isDialog()))
        compose.onNodeWithText("Create").performClick()
        compose.runOnIdle { assertEquals(listOf("Source (copy)" to ""), saves); assertEquals(0, checks) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, dismiss) }
    }

    @Test fun unconfirmedCopyOffersCheckNeverCreateAndDoesNotSubmitOnPresentation() {
        var checks = 0
        var saves = 0
        val busy = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                PlaylistMetadataDialog("Copy playlist", "Source (copy)", "", busy.value, blocked = true,
                    message = "Copy unconfirmed. Review saved playlists.", onDismiss = {},
                    onVerify = { checks++ }, onSave = { _, _ -> saves++ }, saveLabel = "Create")
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Create").assertDoesNotExist()
        compose.onNodeWithText("Check").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Saved manual playlists:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("A matching name", substring = true).assertDoesNotExist()
        assertEquals(0, checks); assertEquals(0, saves)
        capturePolishEvidence("copy-component-unconfirmed", compose.onNode(isDialog()))
        compose.onNodeWithText("Check").performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(0, saves); busy.value = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Check").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(0, saves) }
    }
}
