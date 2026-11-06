package dev.spofify.mobile.feature.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackDeviceIdentityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences(PlaybackDeviceIdentityStore.PREFERENCES, Context.MODE_PRIVATE)
    private val key: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private fun store(origin: String = "https://music.example", account: String = "account-one", androidId: String? = null) =
        PlaybackDeviceIdentityStore(preferences, { key }, ServerAddress.parse(origin), account, { androidId })

    @Before fun reset() { assertTrue(preferences.edit().clear().commit()) }

    @Test fun validAndroidIdIsDomainSeparatedStableAndPrivateAcrossRecreationAndStorageReset() {
        val raw = "1234567890abcdef"
        val first = store(androidId = raw).readOrCreate("Phone")
        assertTrue(validPlaybackInstallationId(first.stableDeviceId))
        assertEquals(64, first.stableDeviceId.length)
        assertFalse(first.stableDeviceId.contains(raw))
        assertEquals(first.stableDeviceId, store(androidId = raw.uppercase()).readOrCreate().stableDeviceId)
        val distinct = listOf(first.stableDeviceId,
            store(account = "account-two", androidId = raw).readOrCreate().stableDeviceId,
            store(origin = "http://music.example", androidId = raw).readOrCreate().stableDeviceId,
            store(origin = "https://music.example:8443", androidId = raw).readOrCreate().stableDeviceId,
            store(androidId = "abcdef1234567890").readOrCreate().stableDeviceId)
        assertEquals(5, distinct.toSet().size)
        assertFalse(preferences.all.toString().contains(raw))
        assertTrue(preferences.edit().clear().commit())
        val recreated = store(androidId = raw).readOrCreate()
        assertNotEquals(first.installationId, recreated.installationId)
        assertEquals(first.stableDeviceId, recreated.stableDeviceId)
    }

    @Test fun invalidAbsentAndKnownBadAndroidIdsUseSecurelyPersistedDistinctRandomFallback() {
        for (bad in listOf(null, "", "null", "short", "1234567890abcdeg", "9774d56d682e549c", "0000000000000000", "ffffffffffffffff")) {
            assertTrue(preferences.edit().clear().commit())
            val one = store(androidId = bad).readOrCreate()
            assertTrue(validPlaybackInstallationId(one.stableDeviceId))
            assertEquals(one.stableDeviceId, store(androidId = bad).readOrCreate().stableDeviceId)
            assertNotEquals(one.stableDeviceId, store(account = "other", androidId = bad).readOrCreate().stableDeviceId)
            assertTrue(preferences.edit().clear().commit())
            assertNotEquals(one.stableDeviceId, store(androidId = bad).readOrCreate().stableDeviceId)
        }
    }

    @Test fun installationAndConfirmedNameSurviveStoreRecreationWithoutPersistingServerDeviceId() {
        val original = store().readOrCreate("Phone 🎵")
        val renamed = store().confirmName("My phone")
        assertEquals(original.installationId, renamed.installationId)
        assertEquals(renamed, store().readOrCreate("Different default"))
        assertEquals("My phone", renamed.name)
        assertFalse(PlaybackDeviceIdentity::class.java.declaredFields.any { it.name == "deviceId" })
        val stored = preferences.all.toString()
        listOf(original.installationId, "My phone", "music.example", "account-one").forEach { assertFalse(stored.contains(it)) }
    }

    @Test fun exactOriginAndAccountHaveIndependentTokensAndNames() {
        val original = store().readOrCreate("First")
        val otherAccount = store(account = "account-two").readOrCreate("Second")
        val otherOrigin = store(origin = "https://other.example").readOrCreate("Third")
        val otherPort = store(origin = "https://music.example:8443").readOrCreate("Fourth")
        assertEquals(4, setOf(original.installationId, otherAccount.installationId, otherOrigin.installationId, otherPort.installationId).size)
        store(account = "account-two").confirmName("Renamed second")
        assertEquals(original, store().readOrCreate())
        assertEquals("Renamed second", store(account = "account-two").readOrCreate().name)
        assertEquals(4, preferences.all.size)
    }

    @Test fun ciphertextCannotBeMovedToAnotherAccountEvenWithTheSameKey() {
        val original = store().readOrCreate("First")
        val firstKey = preferences.all.keys.single()
        val second = store(account = "account-two").readOrCreate("Second")
        val secondKey = (preferences.all.keys - firstKey).single()
        assertTrue(preferences.edit().putString(secondKey, preferences.getString(firstKey, null)).commit())
        val recovered = store(account = "account-two").readOrCreate("Recovered")
        assertNotEquals(original.installationId, recovered.installationId)
        assertNotEquals(second.installationId, recovered.installationId)
        assertEquals("Recovered", recovered.name)
        assertEquals(original, store().readOrCreate())
    }

    @Test fun invalidNameCannotOverwriteConfirmedIdentityAndCorruptionNeverCreatesAuthority() {
        val original = store().readOrCreate()
        assertThrows(IllegalArgumentException::class.java) { store().confirmName(" ") }
        assertThrows(IllegalArgumentException::class.java) { store().confirmName("x".repeat(65)) }
        assertEquals(original, store().readOrCreate())
        assertTrue(preferences.edit().putString(preferences.all.keys.single(), "not ciphertext").commit())
        val replacement = store().readOrCreate()
        assertNotEquals(original.installationId, replacement.installationId)
        assertEquals("Android phone", replacement.name)
        assertThrows(IllegalArgumentException::class.java) { store(account = "") }
    }

    @Test fun coordinatorResolvesIdentityLazilyAndPersistsOwnConfirmedNameFromAcceptedViews() = runTest {
        val identity = store().readOrCreate("Saved phone")
        var providerCalls = 0
        var registeredName = ""
        var current = PlaybackDevicesView("session-phone", listOf(PlaybackDevice("session-phone", "Saved phone", true)),
            "session-phone", 1, 8000, state = null, stateSeq = 0, stateAgeMs = 0, transfer = null,
            directives = emptyList(), commands = emptyList())
        val api = object : PlaybackDevicesProtocol {
            override suspend fun view() = current
            override suspend fun register(name: String): PlaybackDevicesView { registeredName = name; return current }
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>) = current
            override suspend fun rename(deviceId: String, name: String): PlaybackDevicesView = error("Unconfirmed rename")
            override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand) = current
            override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = current
            override suspend fun leave(deviceId: String) = Unit
        }
        val endpoint = object : NativePlaybackEndpoint {
            override val supportsExactHandoff = true
            override fun suspendOutput() = Unit
            override fun exportSnapshot() = PlaybackSnapshot()
            override suspend fun prepare(snapshot: PlaybackSnapshot) = true
            override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority) = PlaybackEndpointResult("ok", snapshot)
            override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority) = PlaybackEndpointResult("ok", PlaybackSnapshot())
        }
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, clockMs = { testScheduler.currentTime },
            identity = { providerCalls++; store() })
        runCurrent(); assertEquals(0, providerCalls)
        c.retry(); runCurrent(); assertEquals(0, providerCalls)
        c.setServiceActive(true); runCurrent()
        assertEquals("Saved phone", registeredName)
        assertNotEquals(identity.installationId, c.state.value.deviceId)
        current = current.copy(devices = listOf(PlaybackDevice("session-phone", "Renamed remotely", true)))
        c.retry(); runCurrent()
        assertEquals("Renamed remotely", store().readOrCreate().name)
        assertEquals(identity.installationId, store().readOrCreate().installationId)
        c.rename("session-phone", "Unconfirmed draft"); runCurrent()
        assertEquals("Renamed remotely", store().readOrCreate().name)
        c.close()
    }
}
