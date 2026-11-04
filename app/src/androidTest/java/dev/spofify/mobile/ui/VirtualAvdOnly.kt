package dev.spofify.mobile.ui

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit

/** Fail closed: this suite must never launch its Activity or mutate data on a real phone. */
internal object VirtualAvdOnly {
    @Volatile private var applicationVerified = false
    @Volatile private var verified = false

    /** Called only by the runner's newApplication, before super constructs any target app. */
    fun verifyBeforeApplication() {
        applicationVerified = false
        verified = false
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
            "Native UI tests require ranchu/goldfish virtual hardware before Application construction"
        }
        // At this lifecycle point InstrumentationRegistry / UiAutomation need not be ready.
        // getprop is a nonprivileged OS read, uses no app Context, and has a bounded lifetime.
        val process = ProcessBuilder("/system/bin/getprop", "ro.kernel.qemu").redirectErrorStream(true).start()
        try {
            check(process.waitFor(2, TimeUnit.SECONDS)) { "Virtual AVD getprop verification timed out" }
            check(process.exitValue() == 0) { "Virtual AVD getprop verification failed" }
            val qemu = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            check(qemu == "1") { "Native UI tests require ro.kernel.qemu=1 before Application construction" }
            applicationVerified = true
        } finally {
            process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }

    fun verify() {
        verified = false
        check(applicationVerified) { "Use dev.spofify.mobile.ui.VirtualAvdTestRunner for the pre-Application guard" }
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
        check(applicationVerified && verified) { "Virtual AVD verification must complete before Application construction and in @BeforeClass before fixture/test writes" }
    }
}
