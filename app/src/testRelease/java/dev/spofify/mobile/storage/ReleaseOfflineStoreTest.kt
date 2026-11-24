package dev.spofify.mobile.storage

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseOfflineStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun defaultAndExplicitModesSurviveNewStoreInstances() {
        val root = temp.newFolder()
        assertFalse(ReleaseOfflineStore(root).read())
        ReleaseOfflineStore(root).write(true)
        assertTrue(ReleaseOfflineStore(root).read())
        ReleaseOfflineStore(root).write(false)
        assertFalse(ReleaseOfflineStore(root).read())
    }

    @Test fun existingInvalidStateNeverFallsBackToFirstLaunch() {
        val root = temp.newFolder()
        val state = root.resolve("network-policy-v1")
        for (content in listOf("", "spofify-offline-v", "spofify-online-v1\n0", "x".repeat(8192))) {
            state.writeText(content)
            assertThrows(IOException::class.java) { ReleaseOfflineStore(root).read() }
        }
        state.delete()
        state.mkdir()
        assertThrows(IOException::class.java) { ReleaseOfflineStore(root).read() }
    }

    @Test fun anInterruptedWriteFencesColdLaunchEvenWithAnOnlineRecord() {
        val root = temp.newFolder()
        val interrupted = root.resolve("network-policy-v1.pending")
        interrupted.writeText("")
        assertThrows(IOException::class.java) { ReleaseOfflineStore(root).read() }
        interrupted.delete()
        ReleaseOfflineStore(root).write(false)
        interrupted.writeText("spofify-offline-v1\n")
        assertThrows(IOException::class.java) { ReleaseOfflineStore(root).read() }
        ReleaseOfflineStore(root).write(true)
        assertTrue(ReleaseOfflineStore(root).read())
    }
}
