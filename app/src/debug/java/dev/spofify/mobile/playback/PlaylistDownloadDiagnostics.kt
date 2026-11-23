package dev.spofify.mobile.playback

import android.util.Log
import dev.spofify.mobile.core.offline.OfflineDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal val playlistDownloadDiagnostics: OfflineDiagnostics? by lazy {
    try {
        val lines = Channel<String>(capacity = 256)
        CoroutineScope(Dispatchers.IO).launch {
            for (line in lines) {
                try { Log.d("SpofifyOffline", line) }
                catch (_: Throwable) { /* Logcat is best-effort. */ }
            }
        }
        // Never wait for logcat or grow an unbounded queue if other playlists are busy.
        OfflineDiagnostics { lines.trySend(it) }
    } catch (_: Throwable) { null }
}
