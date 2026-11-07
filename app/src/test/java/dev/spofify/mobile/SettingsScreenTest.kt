package dev.spofify.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.playback.cache.AutoCacheSettings
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.CompletableDeferred
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

    @Test fun automaticCacheIndexMatchesOnlySuppliedControlsAndCurrentValues() {
        val settings = AutoCacheSettings()
        assertEquals(AutoCachePreference.entries, matchingAutoCachePreferences(settings, "AUTOMATIC cache"))
        assertEquals(listOf(AutoCachePreference.MAX_SONGS), matchingAutoCachePreferences(settings, "maximum 10"))
        assertEquals(listOf(AutoCachePreference.STORAGE), matchingAutoCachePreferences(settings, "minimum storage"))
        assertTrue(matchingAutoCachePreferences(settings, "playlist downloads").isEmpty())
        assertEquals(listOf(AutoCachePreference.STORAGE), matchingAutoCachePreferences(settings.copy(storageGb = 3), "3 gb"))
        assertEquals("Minimum · 600 MB", autoCacheSummary(AutoCachePreference.STORAGE, settings))
        assertEquals("Minimum · 200 MB", autoCacheSummary(AutoCachePreference.STORAGE, settings.copy(maxSongs = 2)))
        listOf("", "0", "-1", "1.5", "1e3", "2147483648", "99999999999999999999999").forEach {
            assertNotNull(it, autoCacheStorageError(10, it))
        }
        assertNull(autoCacheStorageError(10, "1"))
        assertNull(autoCacheStorageError(2, "2"))
    }

    @Test fun rootSearchFindsActualCacheRowsWithoutAddingRoutes() {
        var available by mutableStateOf<AutoCacheSettings?>(AutoCacheSettings())
        compose.setContent {
            MaterialTheme { SettingsScreen("Listener", "https://music.example", {}, cacheSettings = available) }
        }
        compose.onNodeWithTag("settings-query").performTextReplacement("maximum songs")
        compose.onNodeWithText("Maximum songs").assertHasClickAction()
        compose.onNodeWithText("Storage").assertDoesNotExist()
        compose.onNodeWithText("Account and server").assertDoesNotExist()
        compose.onNodeWithText("No matching settings").assertDoesNotExist()
        compose.runOnIdle { available = null }
        compose.onNodeWithText("Maximum songs").assertDoesNotExist()
        compose.onNodeWithText("No matching settings").assertIsDisplayed()
    }

    @Test fun songDialogOffersOnlyTwoThroughTenAndCancelDoesNotSave() {
        var committed by mutableStateOf(AutoCacheSettings(storageGb = 1))
        val saved = mutableListOf<AutoCacheSettings>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen("Listener", "https://music.example", {}, cacheSettings = committed,
                    onSaveCacheSettings = { saved += it; committed = it; null })
            }
        }
        compose.onNodeWithText("Maximum songs").performScrollTo().performClick()
        compose.onNodeWithTag("auto-cache-songs-1").assertDoesNotExist()
        compose.onNodeWithTag("auto-cache-songs-11").assertDoesNotExist()
        (2..10).forEach { count ->
            compose.onNodeWithTag("auto-cache-songs-$count").performScrollTo()
                .assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("auto-cache-songs-2").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(saved.isEmpty())
        assertEquals(10, committed.maxSongs)
        compose.onNodeWithText("Maximum songs").performClick()
        compose.onNodeWithTag("auto-cache-songs-10").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("auto-cache-songs-2").performScrollTo().performClick()
        compose.onNodeWithTag("auto-cache-save").performClick()
        compose.waitForIdle()
        assertEquals(listOf(AutoCacheSettings(2, 1)), saved)
        compose.onNode(hasText("Maximum songs") and hasText("2")).assertIsDisplayed()
        compose.onNode(hasText("Storage") and hasText("1 GB")).assertIsDisplayed()
        compose.onNodeWithTag("auto-cache-dialog").assertDoesNotExist()
    }

    @Test fun storageValidationAndOwnerFailureStayLocalAndRetryPreservesFixedTarget() {
        var committed by mutableStateOf(AutoCacheSettings())
        val saved = mutableListOf<AutoCacheSettings>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen("Listener", "https://music.example", {}, cacheSettings = committed,
                    onSaveCacheSettings = {
                        saved += it
                        if (saved.size == 1) "Choose a smaller storage limit." else { committed = it; null }
                    })
            }
        }
        compose.onNodeWithText("Storage").performScrollTo().performClick()
        compose.onNodeWithTag("auto-cache-minimum").assertIsSelected()
        compose.onNodeWithTag("auto-cache-fixed").performClick()
        compose.onNodeWithTag("auto-cache-gb").performTextReplacement("1.5")
        compose.onNodeWithTag("auto-cache-save").performClick()
        compose.onNodeWithTag("auto-cache-error").assertTextEquals("Enter a whole number of GB (at least 1).")
        assertTrue(saved.isEmpty())
        compose.onNodeWithTag("auto-cache-gb").performTextReplacement("2")
        compose.onNodeWithTag("auto-cache-save").performClick()
        compose.onNodeWithTag("auto-cache-error").assertTextEquals("Choose a smaller storage limit.")
        assertEquals(AutoCacheSettings(), committed)
        compose.onNodeWithTag("auto-cache-gb").assertTextContains("2")
        compose.onNodeWithTag("auto-cache-gb").performTextReplacement("1")
        compose.onNodeWithTag("auto-cache-save").performClick()
        compose.waitForIdle()
        assertEquals(listOf(AutoCacheSettings(10, 2), AutoCacheSettings(10, 1)), saved)
        compose.onNode(hasText("Storage") and hasText("1 GB")).assertIsDisplayed().performClick()
        compose.onNodeWithTag("auto-cache-minimum").performClick()
        compose.onNodeWithTag("auto-cache-save").performClick()
        compose.waitForIdle()
        assertNull(committed.storageGb)
        compose.onNode(hasText("Storage") and hasText("Minimum · 600 MB")).assertIsDisplayed()
    }

    @Test fun suspendedSaveIsSingleFlightAndDisplayedValuesComeOnlyFromOwner() {
        var committed by mutableStateOf(AutoCacheSettings())
        val pending = CompletableDeferred<String?>()
        val saved = mutableListOf<AutoCacheSettings>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen("Listener", "https://music.example", {}, cacheSettings = committed,
                    onSaveCacheSettings = { saved += it; pending.await() })
            }
        }
        compose.onNodeWithText("Maximum songs").performScrollTo().performClick()
        compose.onNodeWithTag("auto-cache-songs-2").performScrollTo().performClick()
        compose.onNodeWithTag("auto-cache-save").performClick().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithTag("auto-cache-songs-2").assertIsNotEnabled()
        assertEquals(listOf(AutoCacheSettings(2)), saved)
        compose.runOnIdle { pending.complete(null) }
        compose.onNodeWithTag("auto-cache-dialog").assertDoesNotExist()
        compose.onNode(hasText("Maximum songs") and hasText("10")).assertIsDisplayed()
        compose.runOnIdle { committed = saved.single() }
        compose.onNode(hasText("Maximum songs") and hasText("2")).assertIsDisplayed()
    }

    @Test fun dialogUsesLatestOwnerValueForTheFieldNotBeingEdited() {
        var committed by mutableStateOf(AutoCacheSettings(storageGb = 1))
        val saved = mutableListOf<AutoCacheSettings>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen("Listener", "https://music.example", {}, cacheSettings = committed,
                    onSaveCacheSettings = { saved += it; committed = it; null })
            }
        }
        compose.onNodeWithText("Maximum songs").performScrollTo().performClick()
        compose.onNodeWithTag("auto-cache-songs-2").performScrollTo().performClick()
        compose.runOnIdle { committed = committed.copy(storageGb = 2) }
        compose.onNodeWithTag("auto-cache-save").performClick()
        compose.waitForIdle()
        assertEquals(listOf(AutoCacheSettings(2, 2)), saved)
        compose.onNode(hasText("Storage") and hasText("2 GB")).assertIsDisplayed()
    }

    @Test fun restoringStorageDraftDoesNotCommitIt() {
        val saved = mutableListOf<AutoCacheSettings>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                SettingsScreen("Listener", "https://music.example", {}, cacheSettings = AutoCacheSettings(),
                    onSaveCacheSettings = { saved += it; null })
            }
        }
        compose.onNodeWithTag("settings-query").performTextReplacement("storage")
        compose.onNodeWithText("Storage").performClick()
        compose.onNodeWithTag("auto-cache-fixed").performClick()
        compose.onNodeWithTag("auto-cache-gb").performTextReplacement("2")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("auto-cache-fixed").assertIsSelected()
        compose.onNodeWithTag("auto-cache-gb").assertTextContains("2")
        assertTrue(saved.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasText("Storage") and hasText("Minimum · 600 MB")).assertIsDisplayed()
        compose.onNodeWithTag("settings-query").assertTextContains("storage")
    }

    @Test fun largeTextCacheControlsAndBothDialogEndsRemainReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface { Box(Modifier.requiredSize(320.dp, 460.dp)) {
                        SettingsScreen("Listener", "https://music.example", {}, Modifier.fillMaxSize(),
                            cacheSettings = AutoCacheSettings(), onSaveCacheSettings = { null })
                    } }
                }
            }
        }
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Maximum songs"))
        compose.onNodeWithText("Maximum songs").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("auto-cache-songs-2").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("automatic-cache-320-font2-count-first", "auto-cache-dialog")
        compose.onNodeWithTag("auto-cache-songs-10").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("auto-cache-save").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("automatic-cache-320-font2-count-last", "auto-cache-dialog")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Storage"))
        compose.onNodeWithText("Storage").assertHeightIsAtLeast(48.dp)
        capture("automatic-cache-320-font2-rows")
        compose.onNodeWithText("Storage").performClick()
        compose.onNodeWithTag("auto-cache-fixed").performScrollTo().performClick()
        compose.onNodeWithTag("auto-cache-gb").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auto-cache-save").assertIsDisplayed()
        capture("automatic-cache-320-font2-storage", "auto-cache-dialog")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Credits"))
        compose.onNodeWithText("Credits").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
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
