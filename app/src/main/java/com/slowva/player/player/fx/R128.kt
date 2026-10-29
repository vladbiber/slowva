package com.slowva.player.player.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness after ITU BS.1770 / EBU R128: K-weighting, 400 ms blocks with
 * 75% overlap, absolute gate at -70 LUFS and relative gate at -10 LU. Not for the audio thread.
 */
class R128Meter(sampleRate: Int, private val channels: Int) {
    private val pb = DoubleArray(3); private val pa = DoubleArray(3)
    private val rb = doubleArrayOf(1.0, -2.0, 1.0); private val ra = DoubleArray(3)
    private val z = DoubleArray(channels.coerceAtLeast(1) * 8)
    private val step = max(1, sampleRate / 10)
    private val sub = DoubleArray(4)
    private var subPos = 0
    private var subFilled = 0
    private var acc = 0.0
    private var n = 0
    private var blocks = DoubleArray(1024)
    private var blockCount = 0
    var peak = 0f
        private set

    init {
        val sr = sampleRate.toDouble()
        var f0 = 1681.974450955533; val g = 3.999843853973347; var q = 0.7071752369554196
        var k = tan(PI * f0 / sr)
        val vh = 10.0.pow(g / 20.0); val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        pb[0] = (vh + vb * k / q + k * k) / a0; pb[1] = 2.0 * (k * k - vh) / a0; pb[2] = (vh - vb * k / q + k * k) / a0
        pa[0] = 1.0; pa[1] = 2.0 * (k * k - 1.0) / a0; pa[2] = (1.0 - k / q + k * k) / a0
        f0 = 38.13547087602444; q = 0.5003270373238773
        k = tan(PI * f0 / sr)
        a0 = 1.0 + k / q + k * k
        ra[0] = 1.0; ra[1] = 2.0 * (k * k - 1.0) / a0; ra[2] = (1.0 - k / q + k * k) / a0
    }

    /** Feeds [frames] interleaved frames, samples in -1..1. */
    fun add(buf: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            var power = 0.0
            for (c in 0 until channels) {
                val x = buf[i * channels + c]
                val ax = abs(x); if (ax > peak) peak = ax
                // only the front pair counts, surround weights are left out
                if (c >= 2) continue
                val o = c * 8
                val s1 = pb[0] * x + pb[1] * z[o] + pb[2] * z[o + 1] - pa[1] * z[o + 2] - pa[2] * z[o + 3]
                z[o + 1] = z[o]; z[o] = x.toDouble(); z[o + 3] = z[o + 2]; z[o + 2] = s1
                val s2 = rb[0] * s1 + rb[1] * z[o + 4] + rb[2] * z[o + 5] - ra[1] * z[o + 6] - ra[2] * z[o + 7]
                z[o + 5] = z[o + 4]; z[o + 4] = s1; z[o + 7] = z[o + 6]; z[o + 6] = s2
                power += s2 * s2
            }
            acc += power
            if (++n >= step) {
                sub[subPos] = acc / n
                subPos = (subPos + 1) and 3
                acc = 0.0; n = 0
                if (subFilled < 4) subFilled++
                if (subFilled == 4) push((sub[0] + sub[1] + sub[2] + sub[3]) / 4.0)
            }
        }
    }

    private fun push(p: Double) {
        if (blockCount == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
        blocks[blockCount++] = p
    }

    /** Integrated loudness in LUFS, or null for silence or under 400 ms. */
    fun integrated(): Double? {
        val absGate = 10.0.pow((-70.0 + 0.691) / 10.0)
        var sum = 0.0; var cnt = 0
        for (i in 0 until blockCount) if (blocks[i] > absGate) { sum += blocks[i]; cnt++ }
        if (cnt == 0) return null
        val relGate = sum / cnt * 10.0.pow(-1.0)
        sum = 0.0; cnt = 0
        for (i in 0 until blockCount) if (blocks[i] > absGate && blocks[i] > relGate) { sum += blocks[i]; cnt++ }
        if (cnt == 0) return null
        return -0.691 + 10.0 * log10(sum / cnt)
    }

    companion object {
        /** ReplayGain 2.0 reference */
        const val REFERENCE_LUFS = -18.0
    }
}
