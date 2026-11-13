package dev.spofify.mobile.core

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Historical corpus integrity only: retired receiver frames are not current protocol inputs. */
class HistoricalQueueIntentCorpusTest {
    @Test fun frozenRawBytesRemainPinned() {
        val expected = mapOf(
            "queue-intents-v1.json" to "621f5e5eea8632ea732c7369ae03bc442db53ec82e3d0edd7a9052a24547f44b",
            "queue-intents-pause-receipt-v1.json" to "dd614300d5f33e4ac06103fea5f118420e7bd92a4bcb130b3770509a5d07adfc",
        )
        expected.forEach { (name, digest) ->
            val bytes = requireNotNull(javaClass.getResourceAsStream("/queue-intents-v1/$name")).use { it.readBytes() }
            assertEquals(name, digest, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
    }
}
