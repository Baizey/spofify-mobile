package dev.spofify.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launches the real Compose Activity without contacting any server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SignInUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startsOnServerSetupWithSignInDisabled() {
        compose.onNodeWithText("Your music.\nYour server.").assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertIsDisplayed()
        compose.onNodeWithText("Connect & listen").performScrollTo().assertIsNotEnabled()
    }

    @Test fun lanHttpShowsAWarningWithoutAnUnexplainedAcknowledgementGate() {
        compose.onNodeWithText("Server URL").performTextReplacement("http://192.168.1.10:8080")
        compose.onNodeWithText("HTTP is not encrypted.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).performScrollTo().assertIsOn()
        compose.onNodeWithText("Remember sign-in on this device").assertIsDisplayed()
        compose.onNodeWithText("Username").performScrollTo().performTextReplacement("listener")
        compose.onNodeWithText("Password").performScrollTo().performTextReplacement("not-a-real-password")
        compose.onNodeWithText("Connect & listen").performScrollTo().assertIsEnabled()
    }

    @Test fun refusesAnApiPathWithoutSendingCredentials() {
        compose.onNodeWithText("Server URL").performTextReplacement("https://music.example.com/api")
        compose.onNodeWithText("Username").performScrollTo().performTextReplacement("listener")
        compose.onNodeWithText("Password").performScrollTo().performTextReplacement("not-a-real-password")
        compose.onNodeWithText("Connect & listen").performScrollTo().performClick()
        compose.onNodeWithText("Enter only an HTTP(S) origin, without credentials, path, query or fragment")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect & listen").performScrollTo().assertIsNotEnabled()
    }
}
