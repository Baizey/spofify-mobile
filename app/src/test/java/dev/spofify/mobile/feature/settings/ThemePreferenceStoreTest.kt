package dev.spofify.mobile.feature.settings

import android.content.SharedPreferences
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemePreferenceStoreTest {
    @Test fun missingPreferenceDefaultsDarkAndCommitPersistsAcrossStoreInstances() = runTest {
        val preferences = Preferences()
        var opens = 0
        val store = SharedPreferencesThemePreferenceStore({ opens++; preferences.value }, StandardTestDispatcher(testScheduler))
        assertEquals(0, opens) // Construction does not open preferences or do disk work.
        assertFalse(store.read())
        store.write(true)
        assertTrue(store.read())
        val restored = SharedPreferencesThemePreferenceStore({ preferences.value }, StandardTestDispatcher(testScheduler))
        assertTrue(restored.read())
        assertEquals(1, preferences.commits)
        assertEquals(0, preferences.applies)
        assertEquals(listOf("light"), preferences.keys.distinct())
    }

    @Test fun failedCommitIsReportedAndNeverReplacedWithFireAndForgetApply() = runTest {
        val preferences = Preferences().apply { persisted = true; allowCommit = false }
        val store = SharedPreferencesThemePreferenceStore({ preferences.value }, StandardTestDispatcher(testScheduler))
        val failure = runCatching { store.write(false) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Device theme persistence failed", failure!!.message)
        // Coroutine stack recovery may copy IOException and retain the original as its cause.
        generateSequence(requireNotNull(failure)) { it.cause }.forEach {
            assertEquals("Device theme persistence failed", it.message)
        }
        assertTrue(store.read())
        assertEquals(1, preferences.commits)
        assertEquals(0, preferences.applies)
    }

    @Test fun malformedOrUnavailablePreferencesAreReportedRatherThanSilentlyReadAsDark() = runTest {
        val preferences = Preferences().apply { readFailure = ClassCastException("private/raw/preference") }
        val store = SharedPreferencesThemePreferenceStore({ preferences.value }, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { store.read() }.exceptionOrNull() is ClassCastException)
        val unavailable = SharedPreferencesThemePreferenceStore({ throw SecurityException("private/raw/preference") },
            StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { unavailable.read() }.exceptionOrNull() is SecurityException)
        assertEquals(0, preferences.commits)
    }

    /** Interface proxies run in ordinary JVM tests without invoking Android framework stub methods. */
    private class Preferences {
        var persisted: Boolean? = null
        var allowCommit = true
        var readFailure: RuntimeException? = null
        var commits = 0
        var applies = 0
        val keys = mutableListOf<String>()
        val value = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> {
                    keys += args!![0] as String
                    readFailure?.let { throw it }
                    persisted ?: args[1] as Boolean
                }
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            var pending: Boolean? = null
            lateinit var result: SharedPreferences.Editor
            result = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putBoolean" -> {
                        keys += args!![0] as String
                        pending = args[1] as Boolean
                        result
                    }
                    "commit" -> {
                        commits++
                        if (allowCommit) persisted = requireNotNull(pending)
                        allowCommit
                    }
                    "apply" -> { applies++; null }
                    else -> error("Unexpected preference edit: ${method.name}")
                }
            } as SharedPreferences.Editor
            return result
        }
    }
}
