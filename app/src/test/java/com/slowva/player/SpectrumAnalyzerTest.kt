package com.slowva.player

import com.slowva.player.player.SpectrumAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyzerTest {

    @Test
    fun `silence stays near the floor`() {
        val bars = SpectrumAnalyzer().analyze(ShortArray(2048), 1024)
        assertTrue(bars.max() < 0.05f)
    }

    @Test
    fun `a 440 Hz tone lights a low-mid frequency bar`() {
        val pcm = ShortArray(2048)
        for (frame in 0 until 1024) {
            val sample = (sin(2 * PI * 440.0 * frame / 44100.0) * 14000).toInt().toShort()
            pcm[frame * 2] = sample
            pcm[frame * 2 + 1] = sample
        }
        val bars = SpectrumAnalyzer().analyze(pcm, 1024)
        val peak = bars.indices.maxBy { bars[it] }
        assertTrue("440 Hz peaked in bar $peak", peak in 8..11)
        assertTrue("peak was too small: ${bars[peak]}", bars[peak] > 0.5f)
    }
}
