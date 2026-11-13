package dev.spofify.mobile.playback

import android.os.Looper
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import dev.spofify.mobile.core.LibraryPlaybackInstruction
import dev.spofify.mobile.feature.devices.LibraryPlaybackAuthority
import dev.spofify.mobile.feature.devices.LibraryPlaybackCacheLineage
import dev.spofify.mobile.feature.devices.LibraryPlaybackObservation
import dev.spofify.mobile.feature.devices.NativePlaybackEndpoint

/** Same-process bridge to the ONE service. No queue upload, REST command or history writer. */
class Media3PlaybackEndpoint(private val context: Context) : NativePlaybackEndpoint {
    override var onObservation: ((String, Boolean) -> Unit)? = null
    private val owner = Any()

    override suspend fun prepare(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean): String {
        checkMain()
        if (!isCurrent()) return "rejected"
        if (!PlaybackService.isActive()) {
            try { context.startService(Intent(context, PlaybackService::class.java)) }
            catch (_: RuntimeException) { return "blocked" }
            val ready = withTimeoutOrNull(2_000) {
                while (isCurrent() && !PlaybackService.isActive()) delay(20)
                isCurrent() && PlaybackService.isActive()
            } == true
            if (!ready) return if (isCurrent()) "blocked" else "rejected"
        }
        return PlaybackService.prepareLibrary(instruction, isCurrent, owner) { id, ended -> onObservation?.invoke(id, ended) }
    }

    override fun release(): Boolean {
        checkMain()
        return PlaybackService.releaseLibrary(owner)
    }

    override suspend fun execute(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority): String {
        checkMain()
        return PlaybackService.executeLibrary(instruction, authority, owner) { id, ended -> onObservation?.invoke(id, ended) }
    }

    override fun observe(): LibraryPlaybackObservation {
        checkMain()
        return PlaybackService.observeLibrary(owner)
    }

    override fun parkCacheReads(lineage: LibraryPlaybackCacheLineage) {
        checkMain()
        PlaybackService.parkLibraryCacheReads(owner, lineage)
    }

    override fun suspendOutput() {
        checkMain()
        PlaybackService.suspendLibrary(owner)
    }

    override fun suspendForReplacement() {
        checkMain()
        PlaybackService.suspendLibraryForReplacement(owner)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "The service playback endpoint must be called on the main looper"
    }
}
