package com.slowva.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.slowva.player.player.fx.Fx
import com.slowva.player.player.fx.FxAudioProcessor
import com.slowva.player.player.fx.CrossfeedPreset
import com.slowva.player.player.fx.EqSpec
import com.slowva.player.player.fx.Layers
import com.slowva.player.player.fx.RainPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class FxProcessorTest {

    private fun run(fx: Fx, channels: Int, rate: Int, chunks: Int, gen: (Int) -> Short): ShortArray {
        val p = FxAudioProcessor(fx)
        val out = p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        assertEquals(2, out.channelCount)
        p.flush()
        val frames = 2048
        val all = ArrayList<Short>()
        var t = 0
        repeat(chunks) {
            val buf = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { repeat(channels) { buf.putShort(gen(t)) }; t++ }
            buf.flip()
            p.queueInput(buf)
            val o = p.output.order(ByteOrder.LITTLE_ENDIAN)
            while (o.hasRemaining()) all.add(o.short)
        }
        return all.toShortArray()
    }

    private fun rms(a: ShortArray) = sqrt(a.fold(0.0) { acc, v -> acc + v.toDouble() * v } / a.size.coerceAtLeast(1))

    @Test
    fun `effects off leave silence silent and mono becomes stereo`() {
        val out = run(Fx(), 1, 48000, 6) { 0 }
        assertEquals(2048 * 6 * 2, out.size)
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test
    fun `effects off pass audio through unchanged`() {
        val gen = { t: Int -> (sin(2 * PI * 440 * t / 44100) * 10000).toInt().toShort() }
        val out = run(Fx(), 2, 44100, 4, gen)
        for (t in 0 until out.size / 2) {
            assertEquals(gen(t), out[t * 2])
            assertEquals(gen(t), out[t * 2 + 1])
        }
    }

    @Test
    fun `rain fills silence without clipping`() {
        val out = run(Fx().apply { rain = RainPreset.STORM }, 2, 48000, 60) { 0 }
        val level = rms(out)
        assertTrue("rain rms $level", level in 150.0..5000.0)
        assertTrue(out.none { abs(it.toInt()) >= 32767 })
    }

    @Test
    fun `loud audio with every effect never hard clips`() {
        val every = Fx().apply {
            reverb = 1f; bassGainDb = 12f; vinyl = true; rain = RainPreset.STORM; pan8d = 1f; width = 2f
        }
        val bassOnly = Fx().apply { bassGainDb = 12f }
        for (fx in listOf(every, bassOnly)) {
            val out = run(fx, 2, 44100, 30) { t -> (sin(2 * PI * 80 * t / 44100) * 32767).toInt().toShort() }
            val clipped = out.count { abs(it.toInt()) >= 32767 }
            assertEquals("bass ${fx.bassGainDb} rain ${fx.rain}", 0, clipped)
        }
    }

    @Test
    fun `every stage switched off again is bit exact`() {
        val fx = Fx()
        fx.hifi.layers = Layers(EqSpec.graphic(FloatArray(10) { 3f }), CrossfeedPreset.JAN_MEIER, headroomDb = 3f)
        fx.hifi.tape = 0.5f; fx.hifi.night = true; fx.hifi.gainDb = -4f; fx.hifi.balance = 0.2f
        val gen = { t: Int ->
            if (t == 2048 * 10) {
                fx.hifi.layers = Layers.FLAT
                fx.hifi.tape = 0f; fx.hifi.night = false; fx.hifi.gainDb = 0f; fx.hifi.balance = 0f
            }
            (sin(2 * PI * 440 * t / 44100) * 10000).toInt().toShort()
        }
        val out = run(fx, 2, 44100, 60, gen)
        for (t in 2048 * 50 until out.size / 2) {
            assertEquals(gen(t), out[t * 2])
            assertEquals(gen(t), out[t * 2 + 1])
        }
    }

    @Test
    fun `eq at +12 dB on every band never clips`() {
        val eq = EqSpec.graphic(FloatArray(10) { 12f })
        val fx = Fx().apply { hifi.layers = Layers(user = eq, headroomDb = EqSpec.peakDb(eq)) }
        for (hz in listOf(60, 1000, 9000)) {
            val out = run(fx, 2, 44100, 20) { t -> (sin(2 * PI * hz * t / 44100) * 32767).toInt().toShort() }
            assertEquals("$hz Hz", 0, out.count { abs(it.toInt()) >= 32767 })
        }
    }
}
