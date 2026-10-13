package com.slowva.player.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/** Procedural rain: filtered pink-noise wash, muffled drop grains, distant thunder. Mixed after the speed change. */
internal class Rain(private val sr: Float) {

    private class Grain { var amp = 0f; var decay = 0f; var lp = 0f; var z = 0f; var pan = 0.5f }

    private val pink = Array(2) { FloatArray(7) }
    private val washHp = Array(2) { Biquad().apply { highpass(140f, 0.7f, sr) } }
    private val washLp1 = Array(2) { Biquad().apply { lowpass(2200f, 0.6f, sr) } }
    private val washLp2 = Array(2) { Biquad().apply { lowpass(3200f, 0.6f, sr) } }
    private val patHp = Array(2) { Biquad().apply { highpass(260f, 0.7f, sr) } }
    private val patLp = Array(2) { Biquad().apply { lowpass(5000f, 0.6f, sr) } }
    private val thunderLp1 = Biquad().apply { lowpass(110f, 0.7f, sr) }
    private val thunderLp2 = Biquad().apply { lowpass(70f, 0.7f, sr) }
    private val grains = Array(GRAINS) { Grain() }
    private var nextGrain = 0
    private var seed = 0x1E3779B9
    private var swell = 0.9f
    private var swellTarget = 0.9f
    private var thunderEnv = 0f
    private var thunderPeak = 0f
    private var thunderRising = false

    private fun rnd(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x ushr 8) * (1f / 16777216f)
    }

    /** Paul Kellet's economy pink noise filter */
    private fun pinkNoise(c: Int): Float {
        val w = rnd() * 2f - 1f
        val b = pink[c]
        b[0] = 0.99886f * b[0] + w * 0.0555179f
        b[1] = 0.99332f * b[1] + w * 0.0750759f
        b[2] = 0.96900f * b[2] + w * 0.1538520f
        b[3] = 0.86650f * b[3] + w * 0.3104856f
        b[4] = 0.55000f * b[4] + w * 0.5329522f
        b[5] = -0.7616f * b[5] - w * 0.0168980f
        val p = b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + w * 0.5362f
        b[6] = w * 0.115926f
        return p * 0.11f
    }

    private fun spawn(amp: Float, msLo: Float, msHi: Float, hzLo: Float, hzHi: Float) {
        val g = grains[nextGrain]
        nextGrain = (nextGrain + 1) % GRAINS
        val ms = msLo + rnd() * (msHi - msLo)
        val hz = hzLo + rnd() * (hzHi - hzLo)
        g.amp = amp
        g.decay = exp(-1.0 / (sr * ms * 0.001)).toFloat()
        g.lp = 1f - exp(-2.0 * PI * hz / sr).toFloat()
        g.z = 0f
        // most drops land near the middle, a few far to the sides
        val u = rnd() * 2f - 1f
        g.pan = 0.5f + 0.5f * u * abs(u)
    }

    /** Adds rain of the given [p] preset to the frame in [io]. */
    fun run(io: FloatArray, p: RainPreset) {
        // the whole shower breathes slowly, never jumps
        if (rnd() < 0.15f / sr) swellTarget = 0.72f + rnd() * 0.28f
        swell += (swellTarget - swell) * (0.35f / sr)

        val wl = washLp2[0].run(washLp1[0].run(washHp[0].run(pinkNoise(0))))
        val wr = washLp2[1].run(washLp1[1].run(washHp[1].run(pinkNoise(1))))
        val wash = p.wash * swell * 5f

        val density = p.density * (0.6f + 0.4f * swell)
        if (rnd() < density / sr) {
            val a = rnd()
            spawn(p.patter * (0.15f + a * a * 0.85f), 0.4f, 2.2f, 900f, 3600f)
        }
        if (rnd() < p.near / sr) spawn(p.patter * (1.2f + rnd() * 1.3f), 4f, 11f, 450f, 1100f)

        var pl = 0f; var pr = 0f
        for (g in grains) {
            if (g.amp < 1e-5f) continue
            g.z += ((rnd() * 2f - 1f) - g.z) * g.lp
            val v = g.z * g.amp
            pl += v * (1f - g.pan); pr += v * g.pan
            g.amp *= g.decay
        }
        pl = patLp[0].run(patHp[0].run(pl)) * 10f
        pr = patLp[1].run(patHp[1].run(pr)) * 10f

        var th = 0f
        if (p.thunder > 0f) {
            if (!thunderRising && thunderEnv < 0.002f && rnd() < p.thunder / (60f * sr)) {
                thunderRising = true
                thunderPeak = 0.4f + rnd() * 0.6f
            }
            if (thunderRising) {
                thunderEnv += (thunderPeak - thunderEnv) * (1.2f / sr)
                if (thunderEnv > thunderPeak * 0.95f) thunderRising = false
            } else {
                thunderEnv *= 1f - 0.18f / sr
            }
            if (thunderEnv > 0.001f) th = thunderLp2.run(thunderLp1.run(rnd() * 2f - 1f)) * thunderEnv * 0.9f
        }
        io[0] += wl * wash + pl + th
        io[1] += wr * wash + pr + th
    }

    companion object {
        private const val GRAINS = 64
    }
}

/** density = drop grains per second, near = close drops per second, thunder = rumbles per minute */
enum class RainPreset(val label: String, val wash: Float, val patter: Float, val density: Float,
                      val near: Float, val thunder: Float) {
    OFF("Off", 0f, 0f, 0f, 0f, 0f),
    DRIZZLE("Drizzle", 0.030f, 0.020f, 180f, 0.6f, 0f),
    RAIN("Rain", 0.055f, 0.026f, 520f, 1.6f, 0f),
    STORM("Storm", 0.080f, 0.030f, 1100f, 3.0f, 1.5f),
}
