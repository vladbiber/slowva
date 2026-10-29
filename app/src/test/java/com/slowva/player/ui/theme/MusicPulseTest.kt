package com.slowva.player.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicPulseTest {
    @Test
    fun bassEnergyStretchesTheLowBands() {
        assertEquals(0f, bassEnergy(FloatArray(24) { 0.2f }), 0.001f)
        assertEquals(1f, bassEnergy(FloatArray(24) { 1f }), 0.001f)
        assertEquals(0.5f, bassEnergy(floatArrayOf(0.625f, 0.625f, 0.625f, 0.625f, 0f)), 0.001f)
        assertEquals(0f, bassEnergy(FloatArray(2)), 0.001f)
    }
}
