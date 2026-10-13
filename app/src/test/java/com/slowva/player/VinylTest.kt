package com.slowva.player

import com.slowva.player.player.Dsp
import com.slowva.player.player.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VinylTest {

    private fun render(fx: Fx, input: ShortArray, frames: Int, chunks: Int): ShortArray {
        val dsp = Dsp(fx)
        dsp.reset()
        val out = ShortArray(dsp.maxOutFrames(frames) * 2)
        val all = ShortArray(frames * chunks * 2 + 64)
        var at = 0
        repeat(chunks) {
            val n = dsp.process(input, frames, out)
            System.arraycopy(out, 0, all, at, minOf(n * 2, all.size - at))
            at += n * 2
        }
        return all.copyOf(at)
    }

    private fun rms(buf: ShortArray, from: Int = 0): Double {
        var e = 0.0
        for (i in from until buf.size) e += buf[i].toDouble() * buf[i]
        return sqrt(e / (buf.size - from).coerceAtLeast(1))
    }

    @Test
    fun `off leaves silence silent`() {
        val out = render(Fx(), ShortArray(4096), 2048, 8)
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test
    fun `surface noise and crackle play over silence`() {
        val fx = Fx().apply { vinyl = true; vinylCrackle = 0.8f; vinylSurface = 0.8f }
        val out = render(fx, ShortArray(4096), 2048, 40)
        val level = rms(out)
        assertTrue("noise too quiet: $level", level > 5.0)
        assertTrue("noise too loud: $level", level < 2500.0)
    }

    @Test
    fun `clean settings only delay the signal`() {
        val fx = Fx().apply {
            vinyl = true; vinylCrackle = 0f; vinylSurface = 0f; vinylWarmth = 0f; vinylWow = 0f
        }
        val frames = 2048
        val input = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 440.0 * i / 44100.0) * 12000).toInt().toShort()
            input[i * 2] = v; input[i * 2 + 1] = v
        }
        val out = render(fx, input, frames, 6)
        val dry = render(Fx(), input, frames, 6)
        assertEquals(rms(dry, 8000), rms(out, 8000), rms(dry, 8000) * 0.02)
    }

    @Test
    fun `full warmth keeps loudness in range and never clips hard`() {
        val fx = Fx().apply { vinyl = true; vinylWarmth = 1f; vinylCrackle = 0f; vinylSurface = 0f }
        val frames = 2048
        val input = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 220.0 * i / 44100.0) * 16000).toInt().toShort()
            input[i * 2] = v; input[i * 2 + 1] = v
        }
        val out = render(fx, input, frames, 8)
        val ratio = rms(out, 8000) / rms(render(Fx(), input, frames, 8), 8000)
        assertTrue("warmth changed level too much: $ratio", ratio in 0.6..1.2)
        assertTrue(out.drop(8000).count { abs(it.toInt()) >= 32767 } == 0)
    }
}

class SpaceTest {
    private fun tone(frames: Int): ShortArray {
        val b = ShortArray(frames * 2)
        for (i in 0 until frames) {
            b[i * 2] = (kotlin.math.sin(2 * Math.PI * 440.0 * i / 44100.0) * 12000).toInt().toShort()
            b[i * 2 + 1] = (kotlin.math.sin(2 * Math.PI * 660.0 * i / 44100.0) * 12000).toInt().toShort()
        }
        return b
    }

    private fun render(fx: Fx): ShortArray {
        val dsp = Dsp(fx); dsp.reset()
        val input = tone(4096)
        val out = ShortArray(dsp.maxOutFrames(4096) * 2)
        var n = 0
        repeat(6) { n = dsp.process(input, 4096, out) }
        return out.copyOf(n * 2)
    }

    @Test
    fun `zero width collapses to mono`() {
        val out = render(Fx().apply { width = 0f })
        var diff = 0L
        for (i in 0 until out.size / 2) diff += kotlin.math.abs(out[i * 2] - out[i * 2 + 1])
        assertTrue("channels differ: $diff", diff / (out.size / 2) < 3)
    }

    @Test
    fun `muffle removes highs`() {
        val dsp = Dsp(Fx().apply { muffle = 1f }); dsp.reset()
        val frames = 4096
        val input = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (kotlin.math.sin(2 * Math.PI * 8000.0 * i / 44100.0) * 12000).toInt().toShort()
            input[i * 2] = v; input[i * 2 + 1] = v
        }
        val out = ShortArray(dsp.maxOutFrames(frames) * 2)
        var n = 0
        repeat(4) { n = dsp.process(input, frames, out) }
        var peak = 0
        for (i in 0 until n * 2) peak = maxOf(peak, kotlin.math.abs(out[i].toInt()))
        assertTrue("8 kHz still at $peak", peak < 1200)
    }
}

class RainTest {
    @Test
    fun `rain fills silence without clipping`() {
        for (preset in listOf(com.slowva.player.player.RainPreset.DRIZZLE, com.slowva.player.player.RainPreset.STORM)) {
            val dsp = Dsp(Fx().apply { rain = preset }); dsp.reset()
            val input = ShortArray(4096 * 2)
            val out = ShortArray(dsp.maxOutFrames(4096) * 2)
            var e = 0.0; var count = 0; var peak = 0
            repeat(60) {
                val n = dsp.process(input, 4096, out)
                for (i in 0 until n * 2) { e += out[i].toDouble() * out[i]; peak = maxOf(peak, kotlin.math.abs(out[i].toInt())) }
                count += n * 2
            }
            val rms = kotlin.math.sqrt(e / count)
            println("$preset rms=$rms peak=$peak")
            assertTrue("$preset too quiet $rms", rms > 100)
            assertTrue("$preset too loud $rms", rms < 4000)
            assertTrue(peak < 32767)
        }
    }
}
