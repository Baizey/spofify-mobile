package dev.spofify.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.auth.AuthNotice
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.auth.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class AuthViewModel(private val sessions: SessionRepository) : ViewModel() {
    internal val inputs = LoginInputs()
    val state = sessions.state
    private val mutableInputNotice = MutableStateFlow<AuthNotice?>(null)
    val inputNotice = mutableInputNotice.asStateFlow()

    init {
        viewModelScope.launch {
            var needsAutofill = true
            combine(state, sessions.manualOffline) { auth, offline -> auth to offline }.collect { (auth, offline) ->
                if (auth is AuthState.Authenticated || offline) { inputs.clearSecret(); needsAutofill = true }
                else if (auth is AuthState.SignedOut && needsAutofill) { needsAutofill = false; loadRemembered() }
            }
        }
    }

    private fun loadRemembered() {
        val revision = inputs.revision
        viewModelScope.launch {
            try { sessions.rememberedLogin()?.let {
                if (state.value is AuthState.SignedOut && !sessions.manualOffline.value) inputs.autofill(it, revision)
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableInputNotice.value = AuthNotice.STORAGE_UNAVAILABLE }
        }
    }

    fun server(value: String) { inputs.server(value); mutableInputNotice.value = null }
    fun username(value: String) { inputs.username(value); mutableInputNotice.value = null }
    fun password(value: String) { inputs.password(value); mutableInputNotice.value = null }
    fun reveal() = inputs.reveal()
    fun signIn() {
        mutableInputNotice.value = null
        sessions.signIn(inputs.server, inputs.username, inputs.password.trim())
    }
    fun goOnline() {
        if (state.value !is AuthState.SignedOut || !sessions.manualOffline.value) return
        viewModelScope.launch {
            try { sessions.setManualOffline(false) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { mutableInputNotice.value = AuthNotice.SAVE_FAILED }
        }
    }
    fun signOut() = sessions.signOut()
    fun retry() = sessions.retry()
    fun manualLogin() = sessions.useManualLogin()
    fun foreground() = sessions.refresh()

    override fun onCleared() { inputs.clearSecret() }

    class Factory(private val sessions: SessionRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AuthViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel(sessions) as T
        }
    }
}
