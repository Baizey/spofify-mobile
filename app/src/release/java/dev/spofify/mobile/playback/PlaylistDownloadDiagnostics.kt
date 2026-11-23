package dev.spofify.mobile.playback

import dev.spofify.mobile.core.offline.OfflineDiagnostics

// The Android sink does not exist in the release source set.
internal val playlistDownloadDiagnostics: OfflineDiagnostics? get() = null
