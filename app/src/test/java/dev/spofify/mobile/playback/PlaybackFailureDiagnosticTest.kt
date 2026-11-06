package dev.spofify.mobile.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaybackFailureDiagnosticTest {
    @Test fun distinguishesDecoderParsingTransportAndOutputWithoutCopyingSecrets() {
        for ((code, category) in listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED to "io",
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to "parsing",
            PlaybackException.ERROR_CODE_DECODING_FAILED to "decoder",
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED to "audio-output",
        )) {
            val error = PlaybackException("https://secret.invalid/audio?token=private",
                IOException("Cookie: personal; password=private"), code)
            val diagnostic = playbackFailureDiagnostic(error)
            assertTrue(diagnostic, diagnostic.startsWith("category=$category code=$code "))
            assertTrue(diagnostic.contains("IOException"))
            for (secret in listOf("https", "secret.invalid", "token", "Cookie", "personal", "password", "private"))
                assertFalse(diagnostic, diagnostic.contains(secret))
        }
    }

    @Test fun causeTraversalIsBoundedEvenForCycles() {
        val first = IOException("private")
        val second = IOException("private", first)
        first.initCause(second)
        val diagnostic = playbackFailureDiagnostic(PlaybackException("private", first,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(diagnostic.length < 1100)
        assertFalse(diagnostic.contains("private"))
    }
}
