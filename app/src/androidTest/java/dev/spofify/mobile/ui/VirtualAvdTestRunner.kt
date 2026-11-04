package dev.spofify.mobile.ui

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Test APK only. Reject non-AVDs before Instrumentation instantiates the target Application. */
class VirtualAvdTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        // Instrumentation.newApplication performs class loading / construction in super. Neither
        // the target class nor its Context/prefs/Keystore is touched until this check succeeds.
        // Registry registration and UiAutomation availability are NOT assumed at this hook.
        VirtualAvdOnly.verifyBeforeApplication()
        return super.newApplication(cl, className, context)
    }
}
