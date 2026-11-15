package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.personal.PersonalHistoryEntry
import dev.spofify.mobile.core.personal.PersonalLibraryApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RecentState(
    val items: List<PersonalHistoryEntry> = emptyList(),
    val total: Long? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

internal class RecentViewModel(private val api: PersonalLibraryApi) : ViewModel() {
    private val mutable = MutableStateFlow(RecentState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var reading: Job? = null
    init { refresh() }

    fun refresh() {
        reading?.cancel()
        val token = ++generation
        mutable.value = mutable.value.copy(loading = true, error = null)
        reading = viewModelScope.launch {
            try {
                val result = api.recent(100)
                if (token != generation) return@launch
                mutable.value = RecentState(result.items, result.total, loaded = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) mutable.value = mutable.value.copy(loading = false, error = personalError(failure))
            }
        }
    }
}
