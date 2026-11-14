package dev.spofify.mobile.feature.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spofify.mobile.core.auth.RememberedLogin
import dev.spofify.mobile.core.origin.ServerOrigin

/** Private, non-saveable input lifetime. Never StateFlow, SavedStateHandle or a data class. */
internal class LoginInputs {
    var server by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var revealed by mutableStateOf(false)
        private set
    var revision = 0L
        private set

    fun server(value: String) {
        if (canonical(value) != canonical(server)) clearSecret()
        server = value
        revision++
    }
    fun username(value: String) {
        if (value != username) clearSecret()
        username = value
        revision++
    }
    fun password(value: String) { password = value; revision++ }
    fun reveal() { revealed = !revealed }
    fun clearSecret() { password = ""; revealed = false; revision++ }

    fun autofill(login: RememberedLogin, expectedRevision: Long) {
        if (revision != expectedRevision) return
        val origin = ServerOrigin.parse(login.server)
        server = origin.value
        username = login.username
        password = login.password
        revealed = false
        revision++
    }

    private fun canonical(value: String): String =
        try { ServerOrigin.parse(value).value } catch (_: IllegalArgumentException) { value }
}
