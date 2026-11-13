package dev.spofify.mobile.feature.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.receiveAsFlow
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

    @Test fun coordinatorResolvesIdentityLazilyAndPersistsOnlyServerConfirmedName() = runTest {
        val identity = store().readOrCreate("Saved phone")
        var providerCalls = 0
        var helloName = ""
        val incoming = kotlinx.coroutines.channels.Channel<LibraryPlaybackEvent>(8)
        val socket = object : LibraryPlaybackTransport {
            override val identity = ValidatedSessionIdentity("https://music.example", "account-one", 1)
            override var isCurrent = true
            override val events = incoming.receiveAsFlow()
            override fun send(message: LibraryPlaybackClientMessage) = LibraryPlaybackSend(0, commandId = "1")
            override fun close() { isCurrent = false; incoming.close() }
        }
        val endpoint = object : NativePlaybackEndpoint {
            override var onObservation: ((String, Boolean) -> Unit)? = null
            override fun suspendOutput() = Unit
            override suspend fun prepare(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean) = "ok"
            override fun release() = true
            override suspend fun execute(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority) = "ok"
            override fun observe() = LibraryPlaybackObservation()
        }
        val client = SpofifyClient(ServerAddress.parse("https://music.example"), object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        })
        val c = PlaybackCoordinator(client, endpoint, backgroundScope,
            clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { testScheduler.currentTime },
            identity = { providerCalls++; store() }, authenticationIdentity = { "authenticated-incarnation" },
            openTransport = { _, name, id, capable ->
                helloName = name
                assertEquals(identity.stableDeviceId, id)
                assertTrue(capable)
                socket
            })
        try {
            runCurrent()
            assertEquals(0, providerCalls)
            c.setServiceActive(true); runCurrent()
            assertEquals(1, providerCalls)
            assertEquals("Saved phone", helloName)
            val snapshot = dev.spofify.mobile.playback.testLibrarySnapshot(
                devices = listOf(LibraryPlaybackDevice(identity.stableDeviceId, "Saved phone", true, true)))
            incoming.send(LibraryPlaybackEvent.Message(LibraryPlaybackServerMessage(
                "snapshot", "room", "connection", identity.stableDeviceId, snapshot = snapshot)))
            runCurrent()
            assertEquals(identity.stableDeviceId, c.state.value.deviceId)
            incoming.send(LibraryPlaybackEvent.Message(LibraryPlaybackServerMessage(
                "state", "room", snapshot = snapshot.copy(devices = listOf(
                    LibraryPlaybackDevice(identity.stableDeviceId, "Renamed remotely", true, true))))))
            runCurrent()
            assertEquals("Renamed remotely", store().readOrCreate().name)
            assertEquals(identity.installationId, store().readOrCreate().installationId)
            c.rename(identity.stableDeviceId, "Unconfirmed draft"); runCurrent()
            assertEquals("Renamed remotely", store().readOrCreate().name)
            c.retry(); runCurrent()
            assertEquals("A healthy socket is not readmitted", 1, providerCalls)
        } finally { c.close() }
    }
}
