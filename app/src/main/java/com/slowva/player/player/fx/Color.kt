package com.slowva.player.player.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/** tanh within 0.1% up to |x| = 3, then hard 1 */
internal fun fastTanh(x: Float): Float {
    if (x > 3f) return 1f
    if (x < -3f) return -1f
    val x2 = x * x
    return x * (27f + x2) / (27f + 9f * x2)
}

/** Tape: asymmetric soft saturation, a head bump near 80 Hz and a gentle treble roll-off. */
internal class Tape(private val sr: Float) {
    private val bumpL = Biquad(); private val bumpR = Biquad()
    private var lpL = 0f; private var lpR = 0f
    private var mix = 0f
    private var applied = -1f
    private var drive = 1f; private var bias = 0f; private var norm = 1f; private var off = 0f; private var lpK = 1f

    fun idle(amount: Float) = amount <= 0.001f && mix < 0.0005f

    fun reset() { bumpL.clear(); bumpR.clear(); lpL = 0f; lpR = 0f }

    private fun setup(a: Float) {
        applied = a
        drive = 1f + 3f * a
        bias = 0.12f * a
        off = fastTanh(bias)
        val slope = 1f - off * off
        norm = 1f / (drive * slope)
        bumpL.design(FilterType.PEAK, 80f, 2.5f * a, 0.9f, sr); bumpR.design(FilterType.PEAK, 80f, 2.5f * a, 0.9f, sr)
        val cut = 18000f - 6000f * a
        lpK = (1.0 - exp(-2.0 * PI * cut / sr)).toFloat()
    }

    fun process(buf: FloatArray, frames: Int, amount: Float) {
        val target = amount.coerceIn(0f, 1f)
        if (target <= 0.001f && mix < 0.0005f) { mix = 0f; return }
        if (target > 0.001f && abs(target - applied) > 0.002f) setup(target)
        for (i in 0 until frames) {
            mix += ((if (target > 0.001f) 1f else 0f) - mix) * 0.0008f
            for (ch in 0..1) {
                val x = buf[i * 2 + ch]
                var y = (fastTanh(drive * x + bias) - off) * norm
                y = (if (ch == 0) bumpL else bumpR).run(y)
                y = if (ch == 0) { lpL += (y - lpL) * lpK; lpL } else { lpR += (y - lpR) * lpK; lpR }
                buf[i * 2 + ch] = x + (y - x) * mix
            }
        }
    }
}

/** Tube: a second-harmonic waveshaper with its DC removed, softly capped. */
internal class Tube(sr: Float) {
    private var mix = 0f
    private var x1L = 0f; private var y1L = 0f; private var x1R = 0f; private var y1R = 0f
    private val dcR = (1.0 - 2.0 * PI * 8.0 / sr).toFloat()

    fun idle(amount: Float) = amount <= 0.001f && mix < 0.0005f

    fun reset() { x1L = 0f; y1L = 0f; x1R = 0f; y1R = 0f }

    fun process(buf: FloatArray, frames: Int, amount: Float) {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0.001f && mix < 0.0005f) { mix = 0f; return }
        val k = 0.15f + 0.45f * a
        for (i in 0 until frames) {
            mix += ((if (a > 0.001f) 1f else 0f) - mix) * 0.0008f
            val l = buf[i * 2]; val r = buf[i * 2 + 1]
            val sl = l + k * l * l
            val sr2 = r + k * r * r
            // DC blocker removes the offset the square term adds
            val yl = sl - x1L + dcR * y1L; x1L = sl; y1L = yl
            val yr = sr2 - x1R + dcR * y1R; x1R = sr2; y1R = yr
            buf[i * 2] = l + (fastTanh(yl) - l) * mix
            buf[i * 2 + 1] = r + (fastTanh(yr) - r) * mix
        }
    }
}

/** Night mode: stereo-linked RMS compressor with makeup gain. */
internal class NightComp(private val sr: Float) {
    private var env = 0f
    private var gain = 1f
    private var target = 1f
    private var count = 0
    private val envK = (1.0 - exp(-1.0 / (0.05 * sr))).toFloat()
    private val attack = (1.0 - exp(-1.0 / (0.01 * sr))).toFloat()
    private val release = (1.0 - exp(-1.0 / (0.25 * sr))).toFloat()

    fun idle(on: Boolean) = !on && abs(gain - 1f) < 0.0005f

    fun reset() { env = 0f; count = 0 }

    fun process(buf: FloatArray, frames: Int, on: Boolean, thresholdDb: Float, ratio: Float, makeupDb: Float) {
        if (!on && abs(gain - 1f) < 0.0005f) { gain = 1f; target = 1f; return }
        val slope = 1f - 1f / ratio.coerceAtLeast(1f)
        for (i in 0 until frames) {
            val l = buf[i * 2]; val r = buf[i * 2 + 1]
            env += ((l * l + r * r) * 0.5f - env) * envK
            if (count-- <= 0) {
                count = 16
                target = if (!on) 1f else {
                    val level = 10f * log10(env + 1e-12f)
                    val over = level - thresholdDb
                    val gr = if (over > 0f) -over * slope else 0f
                    10f.pow((gr + makeupDb) / 20f)
                }
            }
            gain += (target - gain) * (if (target < gain) attack else release)
            buf[i * 2] = l * gain
            buf[i * 2 + 1] = r * gain
        }
    }
}

/** ReplayGain, balance, mono and channel swap, every change glides. */
internal class OutputStage {
    private var gain = 1f
    private var bal = 0f
    private var mono = 0f
    private var swap = 0f

    fun idle(gainDb: Float, balance: Float, mono: Boolean, swap: Boolean) =
        abs(gainDb) < 0.01f && abs(balance) < 0.001f && !mono && !swap &&
            abs(gain - 1f) < 0.0005f && abs(bal) < 0.0005f && this.mono < 0.0005f && this.swap < 0.0005f

    fun process(buf: FloatArray, frames: Int, gainDb: Float, balance: Float, monoOn: Boolean, swapOn: Boolean) {
        val tg = 10f.pow(gainDb.coerceIn(-30f, 24f) / 20f)
        val tb = balance.coerceIn(-1f, 1f)
        val tm = if (monoOn) 1f else 0f
        val ts = if (swapOn) 1f else 0f
        for (i in 0 until frames) {
            gain += (tg - gain) * 0.00008f
            bal += (tb - bal) * 0.001f
            mono += (tm - mono) * 0.001f
            swap += (ts - swap) * 0.001f
            var l = buf[i * 2]; var r = buf[i * 2 + 1]
            val m = (l + r) * 0.5f
            l += (m - l) * mono; r += (m - r) * mono
            val sl = l + (r - l) * swap
            val sr = r + (l - r) * swap
            val gl = if (bal > 0f) 1f - bal else 1f
            val gr = if (bal < 0f) 1f + bal else 1f
            buf[i * 2] = sl * gl * gain
            buf[i * 2 + 1] = sr * gr * gain
        }
        if (abs(gain - tg) < 0.0001f) gain = tg
        if (abs(bal - tb) < 0.0001f) bal = tb
        if (abs(mono - tm) < 0.0001f) mono = tm
        if (abs(swap - ts) < 0.0001f) swap = ts
    }
}
