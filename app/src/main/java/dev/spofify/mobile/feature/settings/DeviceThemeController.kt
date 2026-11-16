package dev.spofify.mobile.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** loaded means initial read settled, including an explicitly labelled dark fallback after failure. */
internal data class DeviceThemeState(
    val loaded: Boolean = false,
    val light: Boolean = false,
    val saving: Boolean = false,
    val notice: String? = null,
)

/** Application-owned Main-scope controller. Only light/dark is device-local, never an account palette. */
internal class DeviceThemeController(
    private val store: ThemePreferenceStore,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(DeviceThemeState())
    val state = mutable.asStateFlow()
    private var started = false
    private var generation = 0L

    fun start() {
        if (started || !scope.isActive) return
        started = true
        val token = ++generation
        scope.launch {
            try {
                val light = store.read()
                if (current(token)) mutable.value = DeviceThemeState(loaded = true, light = light)
            } catch (cancelled: CancellationException) {
                if (current(token)) readFailed()
                throw cancelled
            } catch (_: Exception) {
                if (current(token)) readFailed()
            }
        }
    }

    fun choose(light: Boolean) {
        start()
        val previous = mutable.value
        if (!previous.loaded || previous.saving || !scope.isActive) return
        val token = ++generation
        mutable.value = previous.copy(saving = true, notice = null)
        scope.launch {
            try {
                store.write(light)
                if (current(token)) mutable.value = DeviceThemeState(loaded = true, light = light)
            } catch (cancelled: CancellationException) {
                if (current(token)) writeFailed(previous)
                throw cancelled
            } catch (_: Exception) {
                if (current(token)) writeFailed(previous)
            }
        }
    }

    private fun readFailed() {
        mutable.value = DeviceThemeState(loaded = true,
            notice = "Device theme could not be loaded. Using dark mode.")
    }

    private fun writeFailed(previous: DeviceThemeState) {
        mutable.value = previous.copy(saving = false,
            notice = "Device theme could not be saved. Keeping the previous mode.")
    }

    private fun current(token: Long): Boolean = scope.isActive && token == generation
}
