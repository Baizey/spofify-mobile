package dev.spofify.mobile.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackInteractionKeyTest {
    @Test fun unknownEndpointsAreUnavailableAndOpaqueKeysDoNotExposeIdentity() {
        val state = PlaybackState()
        assertNull(state.interactionKey)
        assertFalse(state.canMoveUpcomingAbsolutely)
        val keys = PlaybackInteractionKeys()
        val key = keys.current("private identity")!!
        assertEquals("PlaybackInteractionKey(transient)", key.toString())
        assertFalse(key.toString().contains("private"))
        assertSame(key, keys.current("private identity"))
        assertNotSame(key, keys.current("replacement"))
        assertNull(keys.current(null))
        assertNotSame(key, keys.current("private identity"))
    }

    @Test fun equalSessionValuesAreStillDifferentAuthenticationInstances() {
        val first = String(charArrayOf('a'))
        val second = String(charArrayOf('a'))
        assertEquals(first, second)
        assertNotEquals(PlaybackReference(first), PlaybackReference(second))
        assertEquals(PlaybackReference(first), PlaybackReference(first))
    }
}
