package dev.spofify.mobile.feature.auth

import dev.spofify.mobile.core.auth.RememberedLogin
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LoginInputsTest {
    private fun profile() = RememberedLogin("http://fixture.test:6030", "fixture-user", UUID.randomUUID().toString() + " \t")

    @Test fun autofillIsCompleteButNeverChangesAfterAnEdit() {
        val input = LoginInputs()
        val profile = profile()
        input.autofill(profile, input.revision)
        assertEquals(profile.server, input.server)
        assertEquals(profile.username, input.username)
        assertEquals(profile.password, input.password)
        val captured = input.revision
        input.username("edited")
        input.autofill(profile, captured)
        assertEquals("edited", input.username)
        assertEquals("", input.password)
    }

    @Test fun identityChangeClearsSecretAndRevealButEquivalentOriginDoesNot() {
        val input = LoginInputs()
        val profile = profile()
        input.autofill(profile, input.revision)
        input.reveal()
        input.server("http://FIXTURE.test:6030/")
        assertEquals(profile.password, input.password)
        assertTrue(input.revealed)
        input.server("http://other.test")
        assertEquals("", input.password)
        assertFalse(input.revealed)
        input.password(profile.password)
        input.reveal()
        input.username("other-user")
        assertEquals("", input.password)
        assertFalse(input.revealed)
    }

    @Test fun successfulClearFencesDelayedAutofillAndSecretTypesAreRedacted() {
        val input = LoginInputs()
        val pending = input.revision
        input.clearSecret()
        val profile = profile()
        input.autofill(profile, pending)
        assertEquals("", input.password)
        assertFalse(profile.toString().contains(profile.password))
        assertFalse(profile.toString().contains(profile.username))
    }
}
