package dev.spofify.mobile.playback

import org.junit.Assert.*
import org.junit.Test

class PlayerSeekTest {
    @Test fun drawingKeepsSilenceAndShortProductsUnchanged() {
        assertEquals(emptyList<Int>(), displayWaveformBins(emptyList()))
        assertEquals(List(56) { 0 }, displayWaveformBins(List(2048) { 0 }))
        assertEquals(listOf(0, 128, 255, 64), displayWaveformBins(listOf(0, 128, 255, 64)))
    }

    @Test fun everySourceBinContributesWithoutAmplitudeNormalization() {
        val bins = MutableList(2048) { 0 }
        bins[0] = 1
        bins[1023] = 100
        bins[2047] = 200
        val result = displayWaveformBins(bins)
        assertEquals(56, result.size)
        assertEquals(1, result.first())
        assertEquals(200, result.last())
        assertEquals(100, result[27])
        assertEquals(301, result.sum())
    }
}
