package com.slowva.player.ui.skins.radio

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationColorTest {
    @Test
    fun sameNameSameColor() {
        assertEquals(stationColor("Radio Paradise"), stationColor("Radio Paradise"))
        assertEquals(stationColor("Radio Paradise"), stationColor(" radio paradise "))
    }

    @Test
    fun namesSpreadOverThePalette() {
        val counts = (1..1200).map { stationColor("Station $it") }.groupingBy { it }.eachCount()
        assertEquals(12, counts.size)
        assertTrue(counts.values.all { it in 50..150 })
    }

    @Test
    fun whiteLetterReadsOnEveryColor() {
        val colors = (1..1200).map { stationColor("Station $it") }.toSet()
        // contrast with white of at least 3:1 (large text)
        assertTrue(colors.all { 1.05f / (it.luminance() + 0.05f) >= 3f })
    }
}
