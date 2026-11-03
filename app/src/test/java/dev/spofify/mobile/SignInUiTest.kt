package dev.spofify.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

    @Test fun lanHttpRequiresAnExplicitWarningAcknowledgement() {
        compose.onNodeWithText("Server URL").performTextReplacement("http://192.168.1.10:8080")
        compose.onNodeWithText("Username").performTextReplacement("listener")
        compose.onNodeWithText("Password").performTextReplacement("not-a-real-password")
        compose.onNodeWithText("Connect & listen").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Connect & listen").performScrollTo().assertIsEnabled()
    }

    @Test fun refusesAnApiPathWithoutSendingCredentials() {
        compose.onNodeWithText("Server URL").performTextReplacement("https://music.example.com/api")
        compose.onNodeWithText("Username").performTextReplacement("listener")
        compose.onNodeWithText("Password").performTextReplacement("not-a-real-password")
        compose.onNodeWithText("Connect & listen").performScrollTo().performClick()
        compose.onNodeWithText("Enter only an HTTP(S) origin, without credentials, path, query or fragment")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect & listen").performScrollTo().assertIsNotEnabled()
    }
}
