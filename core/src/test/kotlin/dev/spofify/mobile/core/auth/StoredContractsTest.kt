package dev.spofify.mobile.core.auth

import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StoredContractsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun legacySessionAndRememberFlagRemainCompatibleWithoutMigration() {
        val legacy = """{"server":"http://fixture.test","cookie":"fixture-cookie","session":{"accountId":"fixture-id","username":"fixture-user","role":"user","csrfToken":"fixture-csrf"},"remember":false}"""
        val session = json.decodeFromString<StoredSession>(legacy)
        assertNull(session.sessionMode)
        assertEquals("fixture-id", session.session.validated().accountId)
        val roundTrip = json.decodeFromString<StoredSession>(json.encodeToString(session))
        assertEquals(session.cookie, roundTrip.cookie)
        assertEquals(session.session.csrfToken, roundTrip.session.csrfToken)
        val profile = RememberedLogin(session.server, session.session.username, UUID.randomUUID().toString() + " \t")
        val encoded = json.encodeToString(profile).dropLast(1) + ""","remember":false}"""
        val decoded = json.decodeFromString<RememberedLogin>(encoded)
        assertEquals(profile.password, decoded.password)
        assertEquals(profile.username, decoded.username)
        assertEquals(profile.server, decoded.server)
    }

    @Test fun redactedContractsAndPublicStateExcludeAllSecrets() {
        val secret = UUID.randomUUID().toString()
        val cookie = UUID.randomUUID().toString()
        val csrf = UUID.randomUUID().toString()
        val summary = SessionSummary("fixture-id", "fixture-user", "user", csrf)
        val session = StoredSession("http://fixture.test", cookie, summary, "rolling-v1")
        val profile = RememberedLogin(session.server, summary.username, secret)
        val state = AuthState.Authenticated(AccountIdentity(session.server, summary.accountId, summary.username, 1))
        listOf(summary, session, profile, state).forEach {
            assertFalse(it.toString().contains(secret))
            assertFalse(it.toString().contains(cookie))
            assertFalse(it.toString().contains(csrf))
        }
        assertEquals(setOf("identity", "notice"),
            AuthState.Authenticated::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet())
    }
}
