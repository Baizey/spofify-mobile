package dev.spofify.mobile.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.personal.PersonalHistoryEntry
import dev.spofify.mobile.playback.LivePersonal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RecentState(
    val items: List<PersonalHistoryEntry>? = null,
    val error: String? = null,
)

internal class RecentViewModel(private val source: LivePersonal) : ViewModel() {
    private val mutable = MutableStateFlow(RecentState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            source.replica.view.collect {
                mutable.value = RecentState(items = it.recent.takeIf { _ -> it.historyKnown },
                    error = if (it.ready && !it.historyKnown) "Recently Played not saved on this device." else null)
            }
        }
    }

    fun refresh() = source.refreshAccount()
}
