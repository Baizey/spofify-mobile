package dev.spofify.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FontLicenseTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun apkAssetContainsTheExactOriginalCopyrightAndFullOfl() {
        val bytes = licenseBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(4399, bytes.size)
        assertEquals(
            "30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad",
            sha256,
        )
        val license = bytes.toString(Charsets.UTF_8)
        assertTrue(license.startsWith(
            "Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono)",
        ))
        assertTrue(license.contains("SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007"))
        listOf("PREAMBLE", "DEFINITIONS", "PERMISSION & CONDITIONS", "TERMINATION", "DISCLAIMER")
            .forEach { assertTrue(it, license.contains(it)) }
        assertTrue(license.trimEnd().endsWith("OTHER DEALINGS IN THE FONT SOFTWARE."))
    }

    @Test
    fun dialogExposesFullLocalNoticeAndDoneDismissesIt() {
        val visible = mutableStateOf(true)
        var dismissals = 0
        val license = licenseBytes().toString(Charsets.UTF_8)
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                if (visible.value) {
                    AppFontLicenseDialog {
                        dismissals += 1
                        visible.value = false
                    }
                }
            }
        }
        compose.onNodeWithText("JetBrains Mono font license").assertIsDisplayed()
        // Semantics expose the full notice; no rendered-glyph/pixel assertion is made here.
        compose.onNodeWithText(license).assertExists()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithText("JetBrains Mono font license").assertDoesNotExist()
        compose.onNodeWithText(license).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    private fun licenseBytes(): ByteArray = RuntimeEnvironment.getApplication().assets
        .open("licenses/jetbrains-mono/OFL.txt").use { it.readBytes() }
}
