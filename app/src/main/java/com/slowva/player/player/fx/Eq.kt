package com.slowva.player.player.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter shapes, with their AutoEQ / Equalizer APO codes. */
enum class FilterType(val code: String, val label: String, val hasGain: Boolean) {
    PEAK("PK", "Peak", true),
    LOW_SHELF("LSC", "Low shelf", true),
    HIGH_SHELF("HSC", "High shelf", true),
    LOW_PASS("LPQ", "Low-pass", false),
    HIGH_PASS("HPQ", "High-pass", false),
}

/** One parametric filter. */
data class EqBand(val type: FilterType, val freq: Float, val gainDb: Float = 0f, val q: Float = 0.707f)

/** Immutable filter list for the audio thread; [fadeMs] is how long the move to it takes. */
class EqSpec(val bands: List<EqBand>, val fadeMs: Int = 60) {
    internal val type = IntArray(bands.size) { bands[it].type.ordinal }
    internal val freq = FloatArray(bands.size) { bands[it].freq }
    internal val gain = FloatArray(bands.size) { bands[it].gainDb }
    internal val q = FloatArray(bands.size) { bands[it].q }
    val size: Int get() = bands.size

    /** true when it would not change the sound */
    val isFlat: Boolean get() = bands.all { it.type.hasGain && abs(it.gainDb) < 0.05f }

    /** Magnitude response in dB at [f] Hz. */
    fun responseDb(f: Double, sr: Double = 48000.0): Double {
        val c = DoubleArray(5)
        var db = 0.0
        for (i in 0 until size) {
            Rbj.design(type[i], freq[i].toDouble(), gain[i].toDouble(), q[i].toDouble(), sr, c, 0)
            db += Rbj.magnitudeDb(c, 0, f, sr)
        }
        return db
    }

    companion object {
        val GRAPHIC_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val GRAPHIC_Q = 1.41f
        const val MAX_BANDS = 12

        /** Ten octave peaks, plus an optional high-pass in front. */
        fun graphic(gains: FloatArray, highpassHz: Float = 0f, fadeMs: Int = 60): EqSpec {
            val bands = ArrayList<EqBand>(11)
            if (highpassHz > 0f) bands += EqBand(FilterType.HIGH_PASS, highpassHz, 0f, 0.707f)
            for (i in GRAPHIC_FREQS.indices) bands += EqBand(FilterType.PEAK, GRAPHIC_FREQS[i], gains.getOrElse(i) { 0f }, GRAPHIC_Q)
            return EqSpec(bands, fadeMs)
        }

        /** Largest boost in dB of the combined [specs], on a log grid from 20 Hz to 20 kHz. */
        fun peakDb(vararg specs: EqSpec?): Float {
            var peak = 0.0
            for (k in 0 until 64) {
                val f = 20.0 * 1000.0.pow(k / 63.0)
                var sum = 0.0
                for (s in specs) if (s != null) sum += s.responseDb(f)
                peak = max(peak, sum)
            }
            return peak.toFloat()
        }
    }
}

/** RBJ cookbook designs, normalised to b0 b1 b2 a1 a2. */
internal object Rbj {
    fun design(type: Int, f0: Double, gainDb: Double, q0: Double, sr: Double, out: DoubleArray, o: Int) {
        val f = f0.coerceIn(5.0, sr * 0.45)
        val q = q0.coerceIn(0.05, 30.0)
        val w = 2.0 * PI * f / sr
        val cw = cos(w); val sw = sin(w)
        val alpha = sw / (2.0 * q)
        val a = 10.0.pow(gainDb / 40.0)
        var b0: Double; var b1: Double; var b2: Double; val a0: Double; var a1: Double; var a2: Double
        when (type) {
            0 -> { // peak
                b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
            }
            1, 2 -> { // shelves, Q as given
                val sa = 2 * sqrt(a) * alpha
                if (type == 1) {
                    b0 = a * ((a + 1) - (a - 1) * cw + sa); b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - sa)
                    a0 = (a + 1) + (a - 1) * cw + sa; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - sa
                } else {
                    b0 = a * ((a + 1) + (a - 1) * cw + sa); b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - sa)
                    a0 = (a + 1) - (a - 1) * cw + sa; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - sa
                }
            }
            3 -> { // low-pass
                b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = b0
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            else -> { // high-pass
                b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = b0
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
        }
        out[o] = b0 / a0; out[o + 1] = b1 / a0; out[o + 2] = b2 / a0; out[o + 3] = a1 / a0; out[o + 4] = a2 / a0
    }

    fun magnitudeDb(c: DoubleArray, o: Int, f: Double, sr: Double): Double {
        val w = 2.0 * PI * f / sr
        val c1 = cos(w); val c2 = cos(2 * w); val s1 = sin(w); val s2 = sin(2 * w)
        val nr = c[o] + c[o + 1] * c1 + c[o + 2] * c2
        val ni = -(c[o + 1] * s1 + c[o + 2] * s2)
        val dr = 1 + c[o + 3] * c1 + c[o + 4] * c2
        val di = -(c[o + 3] * s1 + c[o + 4] * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di).coerceAtLeast(1e-30))
    }
}

/**
 * A stereo cascade of up to [EqSpec.MAX_BANDS] direct-form-I biquads. A new spec does not
 * jump: every coefficient glides toward its target (a convex path, so the filter stays stable),
 * slots that fall out glide to a pass-through.
 */
internal class EqStage(private val sr: Float) {
    private val cur = DoubleArray(MAX * 5)
    private val tgt = DoubleArray(MAX * 5)
    private val st = DoubleArray(MAX * 8)
    private var spec: EqSpec? = null
    private var used = 0
    private var settling = false
    private var k = 0.0
    private var fadeMs = 60

    init { identity(cur, 0, MAX); identity(tgt, 0, MAX) }

    /** true when [want] is nothing and the stage already passes audio through */
    fun idle(want: EqSpec?): Boolean = want == null && spec == null && used == 0

    fun reset() {
        st.fill(0.0)
        System.arraycopy(tgt, 0, cur, 0, tgt.size)
        settling = false
        used = spec?.size ?: 0
    }

    private fun retarget(want: EqSpec?) {
        if (used == 0) { st.fill(0.0); identity(cur, 0, MAX) }
        spec = want
        if (want != null) fadeMs = want.fadeMs
        val n = minOf(want?.size ?: 0, MAX)
        for (s in 0 until MAX) {
            if (s < n) Rbj.design(want!!.type[s], want.freq[s].toDouble(), want.gain[s].toDouble(), want.q[s].toDouble(), sr.toDouble(), tgt, s * 5)
            else identity(tgt, s, 1)
        }
        used = max(used, n)
        settling = true
        // one-pole glide, about 98% there after fadeMs
        k = 1.0 - exp(-4000.0 / (max(5, fadeMs) * sr))
    }

    /** Filters interleaved stereo [buf] in place toward [want]. */
    fun process(buf: FloatArray, frames: Int, want: EqSpec?) {
        if (want !== spec) retarget(want)
        if (used == 0) return
        val n = used
        for (i in 0 until frames) {
            if (settling) for (j in 0 until n * 5) cur[j] += (tgt[j] - cur[j]) * k
            for (ch in 0..1) {
                var x = buf[i * 2 + ch].toDouble()
                for (s in 0 until n) {
                    val c = s * 5; val z = s * 8 + ch * 4
                    val y = cur[c] * x + cur[c + 1] * st[z] + cur[c + 2] * st[z + 1] - cur[c + 3] * st[z + 2] - cur[c + 4] * st[z + 3]
                    st[z + 1] = st[z]; st[z] = x
                    st[z + 3] = st[z + 2]; st[z + 2] = y
                    x = y
                }
                buf[i * 2 + ch] = x.toFloat()
            }
        }
        if (settling) {
            var diff = 0.0
            for (j in 0 until n * 5) diff = max(diff, abs(tgt[j] - cur[j]))
            if (diff < 1e-6) {
                System.arraycopy(tgt, 0, cur, 0, n * 5)
                settling = false
                used = minOf(spec?.size ?: 0, MAX)
                if (spec?.isFlat == true && spec?.size == used && used > 0 && allPass(used)) used = 0
            }
        }
    }

    /** a flat graphic spec is exactly pass-through, so it costs nothing once settled */
    private fun allPass(n: Int): Boolean {
        for (s in 0 until n) {
            val c = s * 5
            if (abs(tgt[c] - 1.0) > 1e-9 || abs(tgt[c + 1] - tgt[c + 3]) > 1e-9 || abs(tgt[c + 2] - tgt[c + 4]) > 1e-9) return false
        }
        return true
    }

    private fun identity(a: DoubleArray, from: Int, count: Int) {
        for (s in from until from + count) {
            val c = s * 5
            a[c] = 1.0; a[c + 1] = 0.0; a[c + 2] = 0.0; a[c + 3] = 0.0; a[c + 4] = 0.0
        }
    }

    companion object {
        const val MAX = EqSpec.MAX_BANDS
    }
}
