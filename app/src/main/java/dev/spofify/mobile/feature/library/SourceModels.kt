package dev.spofify.mobile.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** Back-stack entries own screen lifetimes. A shorter source lifetime owns their data workers:
 * popping an entry OR replacing the source clears the models, including off-screen requests. */
internal class SourceModels {
    private val entries = mutableSetOf<EntryModels>()
    fun retire() { entries.toList().forEach { it.release(this) } }
    fun attach(entry: EntryModels) { entries.add(entry) }
    fun detach(entry: EntryModels) { entries.remove(entry) }
}

internal class EntryModels : ViewModel() {
    private var source: SourceModels? = null
    private var owner: ViewModelStoreOwner? = null
    fun bind(next: SourceModels): ViewModelStoreOwner {
        if (source !== next) {
            source?.let(::release)
            source = next
            owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            next.attach(this)
        }
        return requireNotNull(owner)
    }
    fun release(previous: SourceModels) {
        if (source !== previous) return
        previous.detach(this)
        source = null
        owner?.viewModelStore?.clear()
        owner = null
    }
    override fun onCleared() { source?.let(::release) }
}

@Composable
internal fun SourceModels.Content(content: @Composable (ViewModelStoreOwner) -> Unit) {
    val entry: EntryModels = viewModel()
    val owner = entry.bind(this)
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content(owner) }
}
