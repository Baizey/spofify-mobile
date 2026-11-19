package dev.spofify.mobile.feature.library

import androidx.lifecycle.ViewModel
import org.junit.Assert.*
import org.junit.Test

class SourceModelsTest {
    private class Worker : ViewModel() {
        var retired = false
        override fun onCleared() { retired = true }
    }
    @Test fun replacingDataSourceRetiresEvenHiddenEntriesButNotSuccessorStores() {
        val old = SourceModels(); val device = SourceModels()
        val first = EntryModels(); val hidden = EntryModels()
        val visibleWork = Worker(); val hiddenWork = Worker(); val deviceWork = Worker()
        val visible = first.bind(old)
        assertSame(visible, first.bind(old))
        visible.viewModelStore.put("history", visibleWork)
        hidden.bind(old).viewModelStore.put("playlist", hiddenWork)
        first.bind(device).viewModelStore.put("history", deviceWork)
        assertTrue(visibleWork.retired)
        old.retire()
        assertTrue(hiddenWork.retired)
        assertFalse(deviceWork.retired)
        device.retire()
        assertTrue(deviceWork.retired)
    }
}
