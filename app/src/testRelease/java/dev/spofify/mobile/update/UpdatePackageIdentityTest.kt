package dev.spofify.mobile.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePackageIdentityTest {
    private val installed = UpdatePackageIdentity("dev.spofify.mobile.release", 7, "a".repeat(64))
    private val next = installed.copy(versionCode = 8)

    @Test fun acceptsOnlyTheExactNewVersionOfTheInstalledReleaseSigner() {
        assertTrue(acceptsUpdateArchive(installed, next, 8))
        assertFalse(acceptsUpdateArchive(installed, next.copy(name = "dev.spofify.mobile"), 8))
        assertFalse(acceptsUpdateArchive(installed.copy(name = "dev.spofify.mobile"), next, 8))
        assertFalse(acceptsUpdateArchive(installed, next.copy(signerSha256 = "b".repeat(64)), 8))
        assertFalse(acceptsUpdateArchive(installed, installed, 7))
        assertFalse(acceptsUpdateArchive(installed, installed.copy(versionCode = 6), 6))
        assertFalse(acceptsUpdateArchive(installed, installed.copy(versionCode = 9), 8))
        assertFalse(acceptsUpdateArchive(installed, installed.copy(versionCode = 2_100_000_001), 2_100_000_001))
    }
}
