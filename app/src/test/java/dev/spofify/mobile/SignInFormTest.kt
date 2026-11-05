package dev.spofify.mobile

import org.junit.Assert.*
import org.junit.Test

class SignInFormTest {
    private val saved = RememberedLogin("http://192.168.1.10:8080", "listener", " synthetic-password \t")

    @Test fun latestSuccessfulRecordPrefillsTheWholeTripleInsteadOfALaterFailedServerHint() {
        for (hint in listOf("", saved.server + "/", "https://other.example:8443", "not an origin")) {
            val form = SignInForm(hint, saved)
            assertEquals(saved.server, form.server)
            assertEquals(saved.username, form.username)
            assertEquals(saved.password, form.password) // Preserve password whitespace exactly.
            assertFalse(form.showPassword)
        }
    }

    @Test fun withoutASavedRecordOnlyTheCredentialFreeServerHintIsUsed() {
        val form = SignInForm("https://music.example:8443", null)
        assertEquals("https://music.example:8443", form.server)
        assertEquals("", form.username)
        assertEquals("", form.password)
        assertFalse(form.showPassword)
    }

    @Test fun changingCanonicalIdentityOrMakingItInvalidClearsPasswordAndVisibilityWithoutResurrection() {
        for (destination in listOf(
            "http://192.168.1.20:8080", // host
            "http://192.168.1.10:8081", // port
            "https://192.168.1.10:8080", // scheme
            saved.server + "/api", // invalid non-origin
            "",
        )) {
            val form = SignInForm(saved.server, saved)
            form.showPassword = true
            form.changeServer(destination)
            assertEquals("", form.password)
            assertFalse(form.showPassword)
            form.changeServer(saved.server)
            assertEquals("", form.password) // Returning never re-applies saved credentials.
            assertFalse(form.showPassword)
        }
    }

    @Test fun editingBetweenInvalidAddressesAlsoClearsAManuallyTypedSecret() {
        val form = SignInForm("not an origin", null)
        form.changeUsername("listener")
        form.changePassword("manually typed synthetic password")
        form.showPassword = true
        form.changeServer("still not an origin")
        assertEquals("", form.password)
        assertFalse(form.showPassword)
    }

    @Test fun cosmeticCanonicalOriginChangesDoNotEraseAValidPassword() {
        val form = SignInForm(saved.server, saved)
        form.changeServer("  ${saved.server}/  ")
        assertEquals(saved.username, form.username)
        assertEquals(saved.password, form.password)
        val https = SignInForm("https://music.example", null)
        https.changeUsername("listener")
        https.changePassword(saved.password)
        https.changeServer("https://MUSIC.EXAMPLE:443/")
        assertEquals(saved.password, https.password)
    }

    @Test fun editingUsernameClearsThePasswordAndRevealButReturningDoesNotResurrectIt() {
        val form = SignInForm(saved.server, saved)
        form.showPassword = true
        form.changeUsername("other-listener")
        assertEquals("other-listener", form.username)
        assertEquals("", form.password)
        assertFalse(form.showPassword)
        form.changeUsername(saved.username)
        assertEquals("", form.password)
        assertFalse(form.showPassword)
    }

    @Test fun anUnchangedUsernameDoesNotEraseAnEditedPassword() {
        val form = SignInForm(saved.server, saved)
        form.changePassword(" new synthetic password ")
        form.changeUsername(saved.username)
        assertEquals(" new synthetic password ", form.password)
    }

    @Test fun secretDebugRepresentationsAreRedacted() {
        val form = SignInForm(saved.server, saved)
        assertFalse(form.toString().contains(saved.password))
        assertFalse(saved.toString().contains(saved.password))
        form.clearPassword()
        assertEquals("", form.password)
        assertFalse(form.showPassword)
    }
}
