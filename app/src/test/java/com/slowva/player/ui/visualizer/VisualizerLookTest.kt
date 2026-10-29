package com.slowva.player.ui.visualizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizerLookTest {
    private val ramp = FloatArray(24) { it / 23f }

    @Test
    fun sameCountIsUnchanged() {
        val out = FloatArray(24)
        layoutBars(ramp, out, mirror = false)
        assertArrayEquals(ramp, out, 1e-5f)
    }

    @Test
    fun shrinkingAveragesNeighbours() {
        val out = FloatArray(12)
        layoutBars(ramp, out, mirror = false)
        for (i in 0 until 12) assertEquals((ramp[2 * i] + ramp[2 * i + 1]) / 2, out[i], 1e-5f)
        val third = FloatArray(16)
        layoutBars(FloatArray(24) { 0.5f }, third, mirror = false)
        third.forEach { assertEquals(0.5f, it, 1e-5f) }
    }

    @Test
    fun growingInterpolatesAndKeepsTheEnds() {
        val out = FloatArray(48)
        layoutBars(ramp, out, mirror = false)
        assertEquals(0f, out[0], 1e-5f)
        assertEquals(1f, out[47], 1e-5f)
        for (i in 1 until 48) assert(out[i] >= out[i - 1])
    }

    @Test
    fun mirrorPutsTheLowNotesInTheMiddle() {
        val even = FloatArray(4)
        layoutBars(floatArrayOf(1f, 2f, 3f, 4f), even, mirror = true)
        assertArrayEquals(floatArrayOf(3.5f, 1.5f, 1.5f, 3.5f), even, 1e-5f)
        val odd = FloatArray(5)
        layoutBars(floatArrayOf(1f, 2f, 3f), odd, mirror = true)
        assertArrayEquals(floatArrayOf(3f, 2f, 1f, 2f, 3f), odd, 1e-5f)
    }

    @Test
    fun smoothingRisesFasterThanItFalls() {
        val bars = floatArrayOf(0f, 1f)
        smoothStep(bars, floatArrayOf(1f, 0f), smoothing = 0.5f, frames = 1f)
        assertArrayEquals(floatArrayOf(0.75f, 0.5f), bars, 1e-5f)
        smoothStep(bars, floatArrayOf(0.2f, 0.9f), smoothing = 0f, frames = 1f)
        assertArrayEquals(floatArrayOf(0.2f, 0.9f), bars, 1e-5f)
        // two frames at once land where two single frames would
        val once = floatArrayOf(1f); val twice = floatArrayOf(1f)
        smoothStep(once, floatArrayOf(0f), 0.8f, 2f)
        repeat(2) { smoothStep(twice, floatArrayOf(0f), 0.8f, 1f) }
        assertEquals(twice[0], once[0], 1e-5f)
    }

    @Test
    fun defaultsAreTheOriginalLookAndValuesAreClamped() {
        val d = VizSettings()
        assertEquals(VizSettings(VizColor.WHITE, 1f, 0f, 24, mirror = false, glow = false), d)
        assertEquals(d, d.clamped())
        val c = VizSettings(gain = 9f, smoothing = 1f, bars = 3).clamped()
        assertEquals(2f, c.gain); assertEquals(0.9f, c.smoothing); assertEquals(12, c.bars)
        val low = VizSettings(gain = 0f, smoothing = -1f, bars = 100).clamped()
        assertEquals(0.5f, low.gain); assertEquals(0f, low.smoothing); assertEquals(48, low.bars)
    }
}
