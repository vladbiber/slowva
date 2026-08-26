package com.slowva.player

import com.slowva.player.player.Dsp
import com.slowva.player.player.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspTest {

    private fun sine(frames: Int, freq: Double = 440.0): ShortArray {
        val buf = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * freq * i / 44100.0) * 12000).toInt().toShort()
            buf[i * 2] = v
            buf[i * 2 + 1] = v
        }
        return buf
    }

    private fun run(fx: Fx, chunks: Int = 8, frames: Int = 2048): Pair<Int, Int> {
        val dsp = Dsp(fx)
        dsp.reset()
        val input = sine(frames)
        val out = ShortArray(dsp.maxOutFrames(frames) * 2)
        var produced = 0
        repeat(chunks) { produced += dsp.process(input, frames, out) }
        return frames * chunks to produced
    }

    @Test
    fun `unity speed keeps the frame count`() {
        val (inFrames, outFrames) = run(Fx())
        assertEquals(inFrames.toDouble(), outFrames.toDouble(), inFrames * 0.01)
    }

    @Test
    fun `half speed doubles the frame count`() {
        val fx = Fx().apply { speed = 0.5f }
        val (inFrames, outFrames) = run(fx)
        assertEquals(inFrames * 2.0, outFrames.toDouble(), inFrames * 0.02)
    }

    @Test
    fun `retune multiplies the playback rate`() {
        val fx = Fx().apply { retune = 0.9f }
        val (inFrames, outFrames) = run(fx)
        assertEquals(inFrames / 0.9, outFrames.toDouble(), inFrames * 0.02)
    }

    @Test
    fun `dry signal survives unchanged at unity`() {
        val fx = Fx()
        val dsp = Dsp(fx)
        dsp.reset()
        val frames = 4096
        val input = sine(frames)
        val out = ShortArray(dsp.maxOutFrames(frames) * 2)
        val n = dsp.process(input, frames, out)
        // at rate 1.0 the interpolator sits on the oldest of its 4 history
        // frames, so the output trails the input by exactly two frames
        var maxDiff = 0
        for (i in 2000 until minOf(n, frames) - 1) {
            maxDiff = maxOf(maxDiff, abs(out[i * 2] - input[(i - 2) * 2]))
        }
        assertTrue("dry path distorted by $maxDiff", maxDiff < 40)
    }

    @Test
    fun `reverb and bass boost stay inside the buffer and do not clip to silence`() {
        val fx = Fx().apply { speed = 0.75f; reverb = 0.6f; bassGainDb = 9f; bassFreq = 90f }
        val dsp = Dsp(fx)
        dsp.reset()
        val frames = 2048
        val input = sine(frames)
        val out = ShortArray(dsp.maxOutFrames(frames) * 2)
        var peak = 0
        repeat(20) {
            val n = dsp.process(input, frames, out)
            assertTrue("wrote $n frames into ${out.size / 2}", n <= out.size / 2)
            for (i in 0 until n * 2) peak = maxOf(peak, abs(out[i].toInt()))
        }
        assertTrue("output is silent", peak > 1000)
    }
}
