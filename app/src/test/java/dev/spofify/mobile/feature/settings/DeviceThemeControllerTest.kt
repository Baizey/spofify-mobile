package dev.spofify.mobile.feature.settings

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceThemeControllerTest {
    @Test fun startupReadsOnceAndAConfirmedChoicePersistsIntoAnotherControllerLifetime() = runTest {
        val store = Store(light = true)
        val controller = DeviceThemeController(store, backgroundScope)
        controller.start()
        controller.start()
        assertFalse(controller.state.value.loaded)
        runCurrent()
        assertEquals(DeviceThemeState(loaded = true, light = true), controller.state.value)
        assertEquals(1, store.reads)
        controller.choose(false)
        assertTrue(controller.state.value.saving)
        assertTrue(controller.state.value.light) // No optimistic mode survives failed persistence.
        runCurrent()
        assertEquals(DeviceThemeState(loaded = true, light = false), controller.state.value)
        assertFalse(store.light)
        val restored = DeviceThemeController(store, backgroundScope)
        restored.start()
        runCurrent()
        assertEquals(controller.state.value, restored.state.value)
        assertEquals(listOf(false), store.writes)
    }

    @Test fun pendingInitialReadAndWriteCannotQueueRapidThemeChoices() = runTest {
        val store = Store()
        val readGate = CompletableDeferred<Unit>()
        store.onRead = { readGate.await(); store.light }
        val controller = DeviceThemeController(store, backgroundScope)
        controller.start()
        controller.choose(true)
        runCurrent()
        assertTrue(store.writes.isEmpty())
        readGate.complete(Unit)
        runCurrent()
        val writeGate = CompletableDeferred<Unit>()
        store.onWrite = { light -> writeGate.await(); store.light = light }
        controller.choose(true)
        controller.choose(false)
        controller.choose(true)
        runCurrent()
        assertTrue(controller.state.value.saving)
        assertFalse(controller.state.value.light)
        assertEquals(listOf(true), store.writes)
        writeGate.complete(Unit)
        runCurrent()
        assertTrue(controller.state.value.light)
        assertFalse(controller.state.value.saving)
        assertEquals(listOf(true), store.writes)
        controller.choose(false) // New explicit selection, not an automatically queued one.
        runCurrent()
        assertFalse(controller.state.value.light)
        assertEquals(listOf(true, false), store.writes)
    }

    @Test fun failedInitialReadUsesLabelledDarkFallbackAndAnExplicitChoiceCanPersist() = runTest {
        val store = Store(light = true)
        store.onRead = { throw IOException("secret/private/path") }
        val controller = DeviceThemeController(store, backgroundScope)
        controller.start()
        runCurrent()
        assertTrue(controller.state.value.loaded)
        assertFalse(controller.state.value.light)
        assertSafe(controller.state.value.notice)
        controller.choose(false) // Even choosing the displayed fallback must persist the explicit choice.
        runCurrent()
        assertFalse(store.light)
        assertFalse(controller.state.value.light)
        assertNull(controller.state.value.notice)
        assertEquals(listOf(false), store.writes)
    }

    @Test fun failedOrCancelledWriteKeepsLastConfirmedLocalThemeAndNeverRetries() = runTest {
        for (error in listOf(IOException("secret/private/path"), CancellationException("secret/private/path"))) {
            val store = Store(light = true)
            val controller = DeviceThemeController(store, backgroundScope)
            controller.start()
            runCurrent()
            store.onWrite = { throw error }
            controller.choose(false)
            runCurrent()
            assertTrue(controller.state.value.light)
            assertFalse(controller.state.value.saving)
            assertTrue(store.light)
            assertEquals(listOf(false), store.writes)
            assertSafe(controller.state.value.notice)
            store.onWrite = { store.light = it }
            controller.choose(false)
            runCurrent()
            assertFalse(controller.state.value.light)
            assertEquals(listOf(false, false), store.writes)
            assertNull(controller.state.value.notice)
        }
    }

    @Test fun lateUncooperativeStoreResponseCannotPublishAfterApplicationScopeRetires() = runTest {
        val store = Store()
        val lifetime = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))
        val controller = DeviceThemeController(store, lifetime)
        controller.start()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        store.onWrite = { light -> withContext(NonCancellable) { gate.await() }; store.light = light }
        controller.choose(true)
        runCurrent()
        val retired = controller.state.value
        lifetime.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(retired, controller.state.value)
        assertFalse(controller.state.value.light)
        assertEquals(listOf(true), store.writes)
    }

    private class Store(var light: Boolean = false) : ThemePreferenceStore {
        var reads = 0
        val writes = mutableListOf<Boolean>()
        var onRead: suspend () -> Boolean = { light }
        var onWrite: suspend (Boolean) -> Unit = { light = it }
        override suspend fun read(): Boolean { reads++; return onRead() }
        override suspend fun write(light: Boolean) { writes += light; onWrite(light) }
    }

    private fun assertSafe(notice: String?) {
        assertNotNull(notice)
        assertFalse(notice!!.contains("secret"))
        assertFalse(notice.contains("private"))
    }
}
