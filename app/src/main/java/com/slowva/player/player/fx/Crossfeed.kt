package com.slowva.player.player.fx

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/** BS2B levels: cut frequency in Hz and feed in dB. */
enum class CrossfeedPreset(val label: String, val cutHz: Float, val feedDb: Float) {
    DEFAULT("Default", 700f, 4.5f),
    CHU_MOY("Chu Moy", 700f, 6f),
    JAN_MEIER("Jan Meier", 650f, 9.5f),
}

/**
 * Bauer stereophonic-to-binaural crossfeed: each ear gets the other channel low-passed
 * and its own slightly high-boosted. Coefficients glide on a preset change, on/off fades.
 */
internal class Crossfeed(private val sr: Float) {
    private val cur = DoubleArray(6)
    private val tgt = DoubleArray(6)
    private var preset: CrossfeedPreset? = null
    private var mix = 0f
    private var loL = 0.0; private var loR = 0.0
    private var hiL = 0.0; private var hiR = 0.0
    private var asisL = 0.0; private var asisR = 0.0
    private var primed = false

    fun idle(want: CrossfeedPreset?) = want == null && mix < 0.0005f

    fun reset() {
        loL = 0.0; loR = 0.0; hiL = 0.0; hiR = 0.0; asisL = 0.0; asisR = 0.0
        System.arraycopy(tgt, 0, cur, 0, 6)
    }

    private fun design(p: CrossfeedPreset) {
        val gbLo = p.feedDb * -5.0 / 6.0 - 3.0
        val gbHi = p.feedDb / 6.0 - 3.0
        val gLo = 10.0.pow(gbLo / 20.0)
        val gHi = 1.0 - 10.0.pow(gbHi / 20.0)
        val fcHi = p.cutHz * 2.0.pow((gbLo - 20.0 * log10(gHi)) / 12.0)
        var x = exp(-2.0 * PI * p.cutHz / sr)
        tgt[0] = gLo * (1 - x)          // a0 lo
        tgt[1] = x                       // b1 lo
        x = exp(-2.0 * PI * fcHi / sr)
        tgt[2] = 1 - gHi * (1 - x)       // a0 hi
        tgt[3] = -x                      // a1 hi
        tgt[4] = x                       // b1 hi
        tgt[5] = 1.0 / (1.0 - gHi + gLo) // level so the sum stays near unity
        if (!primed) { System.arraycopy(tgt, 0, cur, 0, 6); primed = true }
    }

    fun process(buf: FloatArray, frames: Int, want: CrossfeedPreset?) {
        if (want != null && want != preset) { preset = want; design(want) }
        val target = if (want != null) 1f else 0f
        if (target == 0f && mix < 0.0005f) { mix = 0f; return }
        for (i in 0 until frames) {
            for (j in 0..5) cur[j] += (tgt[j] - cur[j]) * 0.001
            mix += (target - mix) * 0.0008f
            val l = buf[i * 2].toDouble(); val r = buf[i * 2 + 1].toDouble()
            loL = cur[0] * l + cur[1] * loL
            loR = cur[0] * r + cur[1] * loR
            hiL = cur[2] * l + cur[3] * asisL + cur[4] * hiL
            hiR = cur[2] * r + cur[3] * asisR + cur[4] * hiR
            asisL = l; asisR = r
            val ol = ((hiL + loR) * cur[5]).toFloat()
            val or = ((hiR + loL) * cur[5]).toFloat()
            buf[i * 2] = l.toFloat() + (ol - l.toFloat()) * mix
            buf[i * 2 + 1] = r.toFloat() + (or - r.toFloat()) * mix
        }
    }
}
