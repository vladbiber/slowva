package com.slowva.player.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The effect chain, ported from spotc-fx: a 4-point cubic Hermite resampler
 * (so pitch drops together with speed, the way slowed audio actually sounds)
 * followed by a Freeverb, plus a low-shelf bass boost.
 *
 * Input and output are both interleaved stereo s16le at 44100 Hz. Slowing down
 * produces MORE output frames than input frames — the sink rate never changes.
 */
class Fx {
    @Volatile var speed = 1.0f          // 0.4 .. 1.5, 1.0 = original tempo
    @Volatile var reverb = 0.0f         // 0 .. 1 wet amount
    @Volatile var retune = 1.0f         // tuning ratio, multiplies speed
    @Volatile var bassGainDb = 0.0f     // 0 .. 12 dB low shelf
    @Volatile var bassFreq = 120.0f     // 40 .. 320 Hz shelf corner
    @Volatile var width = 1.0f          // 0 mono .. 1 original .. 2 extra wide
    @Volatile var pan8d = 0.0f          // 0 .. 1 depth of the circling "8D" pan
    @Volatile var muffle = 0.0f         // 0 .. 1 "playing in another room" lowpass
    @Volatile var vinyl = false         // record emulation on/off
    @Volatile var vinylCrackle = 0.45f  // 0 .. 1 ticks, pops, scratches
    @Volatile var vinylWarmth = 0.6f    // 0 .. 1 rolloff, saturation, mono bass
    @Volatile var vinylWow = 0.4f       // 0 .. 1 wow and flutter
    @Volatile var vinylSurface = 0.4f   // 0 .. 1 hiss and rumble

    val rate: Float get() = (speed * retune).coerceIn(0.4f, 2.0f)
}

private class Comb(len: Int) {
    val buf = FloatArray(len)
    var pos = 0
    var feedback = 0.84f
    var damp = 0.2f
    var store = 0f
}

private class Allpass(len: Int) {
    val buf = FloatArray(len)
    var pos = 0
}

private class Verb(offset: Int) {
    val comb = Array(COMB_LEN.size) { Comb(COMB_LEN[it] + offset) }
    val allp = Array(ALLP_LEN.size) { Allpass(ALLP_LEN[it] + offset) }

    fun set(amount: Float) {
        val fb = 0.70f + 0.20f * amount
        for (c in comb) c.feedback = fb
    }

    fun run(input: Float): Float {
        var out = 0f
        for (c in comb) {
            val y = c.buf[c.pos]
            c.store = y * (1f - c.damp) + c.store * c.damp
            c.buf[c.pos] = input + c.store * c.feedback
            if (++c.pos >= c.buf.size) c.pos = 0
            out += y
        }
        for (p in allp) {
            val b = p.buf[p.pos]
            p.buf[p.pos] = out + b * 0.5f
            out = b - out
            if (++p.pos >= p.buf.size) p.pos = 0
        }
        return out
    }

    companion object {
        val COMB_LEN = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLP_LEN = intArrayOf(556, 441, 341, 225)
    }
}

/** Direct-form-I low shelf (RBJ cookbook), one instance per channel. */
private class LowShelf {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    private var lastGain = Float.NaN
    private var lastFreq = Float.NaN

    fun update(gainDb: Float, freq: Float, sampleRate: Float) {
        if (gainDb == lastGain && freq == lastFreq) return
        lastGain = gainDb; lastFreq = freq
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w = (2.0 * PI * freq / sampleRate).toFloat()
        val cw = cos(w.toDouble()).toFloat()
        val sw = sin(w.toDouble()).toFloat()
        val alpha = sw / 2f * sqrt(2f)          // shelf slope S = 1
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha
        val a0 = (a + 1f) + (a - 1f) * cw + twoSqrtAAlpha
        b0 = a * ((a + 1f) - (a - 1f) * cw + twoSqrtAAlpha) / a0
        b1 = 2f * a * ((a - 1f) - (a + 1f) * cw) / a0
        b2 = a * ((a + 1f) - (a - 1f) * cw - twoSqrtAAlpha) / a0
        a1 = -2f * ((a - 1f) + (a + 1f) * cw) / a0
        a2 = ((a + 1f) + (a - 1f) * cw - twoSqrtAAlpha) / a0
    }

    fun run(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

class Dsp(private val fx: Fx, private val sampleRate: Float = 44100f) {

    private val verbL = Verb(0)
    private val verbR = Verb(SPREAD)
    private val shelfL = LowShelf()
    private val shelfR = LowShelf()
    private val vinyl = Vinyl(sampleRate)
    private val frame = FloatArray(2)
    private var vinylMix = 0f
    private val roomL = Biquad()
    private val roomR = Biquad()
    private var appliedMuffle = -1f
    private var panPhase = 0.0
    private var smoothWidth = 1f
    private var smoothPan = 0f

    /** last 4 input frames, kept across chunks so interpolation never clicks */
    private val hist = Array(2) { FloatArray(4) }
    private var frac = 0.0
    private var smoothRate = 1.0f
    private var smoothWet = 0.0f
    private var appliedWet = -1.0f

    /** Worst case output frames for [inFrames] input frames at the slowest rate. */
    fun maxOutFrames(inFrames: Int): Int = (inFrames / MIN_RATE).toInt() + 8

    /**
     * Reads [inFrames] interleaved stereo frames from [input] and writes the
     * processed result into [out]. Returns the number of frames written.
     */
    fun process(input: ShortArray, inFrames: Int, out: ShortArray): Int {
        val targetRate = fx.rate
        val targetWet = fx.reverb.coerceIn(0f, 1f)
        shelfL.update(fx.bassGainDb, fx.bassFreq, sampleRate)
        shelfR.update(fx.bassGainDb, fx.bassFreq, sampleRate)
        val boosting = fx.bassGainDb > 0.01f
        val vinylTarget = if (fx.vinyl) 1f else 0f
        val crackle = fx.vinylCrackle.coerceIn(0f, 1f)
        val warmth = fx.vinylWarmth.coerceIn(0f, 1f)
        val wow = fx.vinylWow.coerceIn(0f, 1f)
        val surface = fx.vinylSurface.coerceIn(0f, 1f)
        val targetWidth = fx.width.coerceIn(0f, 2f)
        val targetPan = fx.pan8d.coerceIn(0f, 1f)
        val muffle = fx.muffle.coerceIn(0f, 1f)
        if (muffle != appliedMuffle) {
            // exponential sweep: 18 kHz with the door open, ~350 Hz through a wall
            val cut = (18000.0 * Math.pow(350.0 / 18000.0, muffle.toDouble())).toFloat()
            roomL.lowpass(cut, 0.75f, sampleRate); roomR.lowpass(cut, 0.75f, sampleRate)
            appliedMuffle = muffle
        }
        val capFrames = out.size / 2 - 2
        var nout = 0

        for (i in 0 until inFrames) {
            for (ch in 0..1) {
                val h = hist[ch]
                h[0] = h[1]; h[1] = h[2]; h[2] = h[3]
                h[3] = input[i * 2 + ch] / 32768.0f
            }
            while (frac < 1.0) {
                // ease into new slider values: no speed jump, no reverb click
                smoothRate += (targetRate - smoothRate) * 0.002f
                smoothWet += (targetWet - smoothWet) * 0.002f
                val wet = smoothWet
                if (wet != appliedWet) {
                    verbL.set(wet); verbR.set(wet)
                    appliedWet = wet
                }
                val t = frac.toFloat()
                for (ch in 0..1) {
                    val h = hist[ch]
                    val dry = hermite(h[0], h[1], h[2], h[3], t)
                    val w = (if (ch == 0) verbL else verbR).run(dry * 0.015f)
                    var s = dry * (1f - wet * 0.35f) + w * wet
                    if (boosting) s = (if (ch == 0) shelfL else shelfR).run(s)
                    frame[ch] = s
                }
                vinylMix += (vinylTarget - vinylMix) * 0.0005f
                if (vinylMix > 0.0005f) {
                    val l = frame[0]; val r = frame[1]
                    vinyl.run(frame, smoothRate, crackle, warmth, wow, surface)
                    frame[0] = l + (frame[0] - l) * vinylMix
                    frame[1] = r + (frame[1] - r) * vinylMix
                } else if (vinylTarget == 0f) {
                    vinylMix = 0f
                }
                smoothWidth += (targetWidth - smoothWidth) * 0.002f
                smoothPan += (targetPan - smoothPan) * 0.002f
                if (abs(smoothWidth - 1f) > 0.002f) {
                    val mid = (frame[0] + frame[1]) * 0.5f
                    val side = (frame[0] - frame[1]) * 0.5f * smoothWidth
                    frame[0] = mid + side; frame[1] = mid - side
                }
                if (smoothPan > 0.002f) {
                    panPhase += PAN_HZ / sampleRate
                    if (panPhase >= 1.0) panPhase -= 1.0
                    val pos = sin(2.0 * PI * panPhase).toFloat() * smoothPan
                    val a = (pos + 1f) * (PI.toFloat() / 4f)
                    val mono = (frame[0] + frame[1]) * 0.5f
                    val gl = cos(a.toDouble()).toFloat() * SQRT2
                    val gr = sin(a.toDouble()).toFloat() * SQRT2
                    // blend toward a panned mono source as the depth goes up
                    frame[0] = frame[0] + (mono * gl - frame[0]) * smoothPan
                    frame[1] = frame[1] + (mono * gr - frame[1]) * smoothPan
                }
                if (muffle > 0.001f) {
                    frame[0] = roomL.run(frame[0]) * (1f + 0.4f * muffle)
                    frame[1] = roomR.run(frame[1]) * (1f + 0.4f * muffle)
                }
                for (ch in 0..1) {
                    var s = frame[ch]
                    if (s > 1f) s = 1f
                    if (s < -1f) s = -1f
                    out[nout * 2 + ch] = (s * 32767f).toInt().toShort()
                }
                nout++
                frac += smoothRate
                if (nout >= capFrames) break
            }
            frac -= 1.0
            if (nout >= capFrames) break
        }
        return nout
    }

    fun reset() {
        for (h in hist) h.fill(0f)
        frac = 0.0
        smoothRate = fx.rate
        smoothWet = fx.reverb
        smoothWidth = fx.width
        smoothPan = fx.pan8d
        appliedWet = -1f
        vinylMix = if (fx.vinyl) 1f else 0f
    }

    private fun hermite(xm1: Float, x0: Float, x1: Float, x2: Float, t: Float): Float {
        val c = (x1 - xm1) * 0.5f
        val w = x0 - x1
        val z = c + w
        val b = z + w + (x2 - x0) * 0.5f
        return ((b * t - (z + b)) * t + c) * t + x0
    }

    companion object {
        const val SPREAD = 23
        const val MIN_RATE = 0.4f
        const val PAN_HZ = 0.11
        val SQRT2 = sqrt(2f)
    }
}
