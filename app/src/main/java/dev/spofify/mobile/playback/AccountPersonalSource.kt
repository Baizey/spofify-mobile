package dev.spofify.mobile.playback

import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.AccountChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** Views share one replica for this account, including across ordinary reconnects. Mutations
 * are deliberately separate from the legacy read API; they have no result to await. */
internal interface LivePersonal {
    val replica: AccountPersonalReplica
    fun command(intent: PersonalIntent): Boolean
    fun watch(id: String): AutoCloseable
}

internal class AccountPersonalSource : LivePersonal {
    override val replica = AccountPersonalReplica()
    private val codec = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private var channel: AccountChannel? = null
    private var subscription: AutoCloseable? = null

    fun attach(next: AccountChannel?) {
        if (next === channel) return
        subscription?.close()
        replica.disconnected()
        channel = next
        subscription = next?.onPersonal { update ->
            if (channel === next) {
                if (update == null) replica.disconnected()
                else for (sync in replica.receive(codec.decodeFromJsonElement(PersonalUpdate.serializer(), update))) command(sync)
            }
        }
    }

    override fun command(intent: PersonalIntent): Boolean = channel?.sendPersonal(codec.encodeToJsonElement(intent) as JsonObject) == true

    override fun watch(id: String): AutoCloseable {
        replica.watch(id)?.let(::command)
        return AutoCloseable { replica.unwatch(id) }
    }
}
