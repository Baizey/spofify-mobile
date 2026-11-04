package dev.spofify.mobile.ui

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Fail closed: this suite must never launch its Activity or mutate data on a real phone. */
internal object VirtualAvdOnly {
    @Volatile private var verified = false

    fun verify() {
        verified = false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("getprop ro.kernel.qemu"),
        ).bufferedReader().use { it.readText().trim() }
        assertEquals("Native UI evidence requires an isolated virtual AVD (ro.kernel.qemu)", "1", qemu)
        assertTrue("Native UI evidence requires ranchu/goldfish virtual hardware",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        verified = true
    }

    fun requireVerified() {
        check(verified) { "Virtual AVD verification must complete in @BeforeClass before fixture/test writes" }
    }
}
