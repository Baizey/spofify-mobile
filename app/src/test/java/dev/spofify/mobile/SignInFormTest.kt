package dev.spofify.mobile

import org.junit.Assert.*
import org.junit.Test

class SignInFormTest {
    private val saved = RememberedLogin("http://192.168.1.10:8080", "listener", "synthetic-password")

    @Test fun prefillIsBoundToTheExactCanonicalOrigin() {
        val form = SignInForm(saved.server + "/", saved)
        assertEquals(saved.username, form.username)
        assertEquals(saved.password, form.password)
        assertFalse(form.showPassword)
        val otherPort = SignInForm("http://192.168.1.10:8081", saved)
        assertEquals("", otherPort.username)
        assertEquals("", otherPort.password)
        val otherScheme = SignInForm("https://192.168.1.10:8080", saved)
        assertEquals("", otherScheme.password)
    }

    @Test fun changingTheDestinationOrMakingItInvalidClearsCredentialsAndVisibility() {
        val form = SignInForm(saved.server, saved)
        form.showPassword = true
        form.changeServer("http://192.168.1.20:8080")
        assertEquals("", form.username)
        assertEquals("", form.password)
        assertFalse(form.showPassword)
        form.useSaved(saved)
        form.changeServer(saved.server + "/api")
        assertEquals("", form.username)
        assertEquals("", form.password)
        form.changeServer(saved.server)
        assertEquals("", form.password) // Never silently refill after a destination edit.
    }

    @Test fun cosmeticOriginChangesDoNotEraseAValidPrefill() {
        val form = SignInForm(saved.server, saved)
        form.changeServer("  ${saved.server}/  ")
        assertEquals(saved.password, form.password)
    }

    @Test fun editingTheUsernameClearsThePasswordButNotTheNewUsername() {
        val form = SignInForm(saved.server, saved)
        form.showPassword = true
        form.changeUsername("other-listener")
        assertEquals("other-listener", form.username)
        assertEquals("", form.password)
        assertFalse(form.showPassword)
    }

    @Test fun forgettingClearsCredentialsAndRedactsDebugRepresentations() {
        val form = SignInForm(saved.server, saved)
        assertFalse(form.toString().contains(saved.password))
        assertFalse(saved.toString().contains(saved.password))
        form.forgetFields()
        assertEquals(saved.server, form.server)
        assertEquals("", form.username)
        assertEquals("", form.password)
    }
}
