package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.personal.PersonalHistoryEntry
import dev.spofify.mobile.core.personal.PersonalLibraryApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RecentState(
    // Null until a successful read; a failed first read isn't an empty history.
    val items: List<PersonalHistoryEntry>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal class RecentViewModel(private val api: PersonalLibraryApi) : ViewModel() {
    private val mutable = MutableStateFlow(RecentState())
    val state = mutable.asStateFlow()

    fun refresh() {
        if (mutable.value.loading) return
        mutable.value = mutable.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // Use the occurrence endpoint's default limit and latest offset, not the deduplicated recent feed.
                mutable.value = RecentState(items = api.history().items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.value = mutable.value.copy(loading = false, error = personalError(failure))
            }
        }
    }
}
