package dev.spofify.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun indexSearchOnlyReturnsRealDestinationsAndNeverInventsPreferences() {
        val entries = settingsEntries("Listener 音楽", "https://music.example:443")
        assertEquals(listOf(SettingsDestination.ACCOUNT), matchingSettings(entries, "SIGN OUT").map { it.destination })
        assertEquals(listOf(SettingsDestination.ACCOUNT), matchingSettings(entries, "music.example Listener").map { it.destination })
        assertEquals(listOf(SettingsDestination.CREDITS), matchingSettings(entries, "font license").map { it.destination })
        listOf("cache", "download", "unlimited", "volume", "wifi").forEach { assertTrue(matchingSettings(entries, it).isEmpty()) }
        assertEquals(entries, matchingSettings(entries, " \n "))
    }

    @Test fun searchClearAndRestorationKeepImplementedNavigationWorking() {
        val opened = mutableListOf<SettingsDestination>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                Surface { SettingsScreen("Listener", "https://music.example", opened::add, Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithText("Account and server").assertHasClickAction()
        compose.onNodeWithText("Credits").assertHasClickAction()
        compose.onNodeWithTag("settings-query").performTextReplacement("font")
        compose.onNodeWithText("Account and server").assertDoesNotExist()
        compose.onNodeWithText("Credits").performClick()
        assertEquals(listOf(SettingsDestination.CREDITS), opened)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("settings-query").assertTextContains("font")
        compose.onNodeWithContentDescription("Clear settings search").performClick()
        compose.onNodeWithText("Account and server").performClick()
        assertEquals(listOf(SettingsDestination.CREDITS, SettingsDestination.ACCOUNT), opened)
        compose.onNodeWithTag("settings-query").performTextReplacement("no such preference")
        compose.onNodeWithText("No matching settings").assertIsDisplayed()
    }

    @Test fun compactIndexRendersRealSummaries() = renderIndex(1f, "index-320-font1")
    @Test fun largeTextIndexKeepsLastPreferenceReachable() = renderIndex(2f, "index-320-font2")

    private fun renderIndex(scale: Float, name: String) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface { Box(Modifier.requiredSize(320.dp, 460.dp)) {
                        SettingsScreen("Élodie 音楽 · Listener with a long name", "https://music.example", {}, Modifier.fillMaxSize())
                    } }
                }
            }
        }
        capture("$name-initial")
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Account and server"))
        compose.onNodeWithText("Account and server").assertIsDisplayed()
        capture("$name-account")
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Credits"))
        compose.onNodeWithText("Credits").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("$name-credits")
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-query"))
        compose.onNodeWithTag("settings-query").performTextReplacement("license")
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Credits"))
        compose.onNodeWithText("Credits").assertIsDisplayed()
        capture("$name-search")
    }

    @Test fun accountValuesAndSignOutRenderWithoutCredentials() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface { Box(Modifier.requiredSize(320.dp, 460.dp)) {
                        ProfileScreen("Élodie 音楽 · Listener with a long name", "https://a-long-server-name.example:8443", false, {}, Modifier.fillMaxSize())
                    } }
                }
            }
        }
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("account-320-font2", "profile-screen")
    }

    private fun capture(name: String, tag: String = "settings-screen") {
        val root = System.getenv("SPOFIFY_SETTINGS_RENDER_DIR") ?: return
        File(root).also { check(it.isDirectory || it.mkdirs()) }.resolve("$name.png").outputStream().use {
            check(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
