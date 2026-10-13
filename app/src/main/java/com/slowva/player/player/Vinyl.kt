package com.slowva.player.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tanh

/** Biquad in transposed direct form II, coefficients from the RBJ cookbook. */
internal class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun lowpass(freq: Float, q: Float, sr: Float) = set(freq, q, sr, false)
    fun highpass(freq: Float, q: Float, sr: Float) = set(freq, q, sr, true)

    fun bandpass(freq: Float, q: Float, sr: Float) {
        val w = 2.0 * PI * freq / sr
        val alpha = sin(w) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (alpha / a0).toFloat(); b1 = 0f; b2 = (-alpha / a0).toFloat()
        a1 = (-2.0 * cos(w) / a0).toFloat(); a2 = ((1.0 - alpha) / a0).toFloat()
    }

    private fun set(freq: Float, q: Float, sr: Float, high: Boolean) {
        val w = 2.0 * PI * freq.coerceIn(5f, sr * 0.45f) / sr
        val cw = cos(w)
        val alpha = sin(w) / (2.0 * q)
        val a0 = 1.0 + alpha
        if (high) {
            b0 = ((1.0 + cw) / 2.0 / a0).toFloat(); b1 = (-(1.0 + cw) / a0).toFloat()
        } else {
            b0 = ((1.0 - cw) / 2.0 / a0).toFloat(); b1 = ((1.0 - cw) / a0).toFloat()
        }
        b2 = b0
        a1 = (-2.0 * cw / a0).toFloat(); a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun run(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * Makes clean digital audio sound like it comes off a record: wow and flutter
 * locked to the platter speed, groove rolloff and soft tube-ish saturation,
 * mono bass like a cutting lathe, surface hiss, motor rumble, random crackle
 * and a couple of scratches that tick once per revolution.
 *
 * Works per output frame, after resampling, so the platter turns slower when
 * the track is slowed down, the way a real turntable at a lower speed would.
 */
internal class Vinyl(private val sr: Float) {

    private val delayL = FloatArray(DELAY_LEN)
    private val delayR = FloatArray(DELAY_LEN)
    private var writePos = 0

    private val toneL = Biquad(); private val toneR = Biquad()
    private val dcL = Biquad(); private val dcR = Biquad()
    private val sideHp = Biquad()
    private val hissLp = Biquad(); private val hissHp = Biquad()
    private val rumbleLp = Biquad()
    private val tickBp = Biquad()
    private val popLp = Biquad()
    private var appliedWarmth = -1f
    private var satK = 1f
    private var satT = 0f
    private var satNorm = 1f
    private val tickDecay = exp(-1.0 / (sr * 0.00025)).toFloat()
    private val popDecay = exp(-1.0 / (sr * 0.0025)).toFloat()

    private var seed = 0x2545F491
    private var platter = 0.0          // 0..1 rotation phase
    private var wowPhase = 0.0
    private var flutterPhase = 0.0
    private var drift = 0f
    private var driftTarget = 0f
    private var tickEnv = 0f; private var tickPan = 0.5f
    private var popEnv = 0f; private var popPan = 0.5f
    private val scratches = floatArrayOf(0.17f, 0.62f)
    private val scratchLevel = floatArrayOf(0.55f, 0.3f)

    init {
        dcL.highpass(12f, 0.707f, sr); dcR.highpass(12f, 0.707f, sr)
        sideHp.highpass(160f, 0.707f, sr)
        hissLp.lowpass(7500f, 0.6f, sr); hissHp.highpass(900f, 0.6f, sr)
        rumbleLp.lowpass(28f, 0.9f, sr)
        tickBp.bandpass(2800f, 0.9f, sr)
        popLp.lowpass(1400f, 0.8f, sr)
    }

    private fun rnd(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x ushr 8) * (1f / 16777216f)
    }

    private fun noise() = rnd() * 2f - 1f

    /**
     * Processes one stereo frame in place through [io] (index 0 = L, 1 = R).
     * [rate] is the current playback rate, it sets how fast the platter turns.
     */
    fun run(io: FloatArray, rate: Float, crackle: Float, warmth: Float, wow: Float, surface: Float) {
        if (warmth != appliedWarmth) {
            val cut = 18000f - 10500f * warmth
            toneL.lowpass(cut, 0.6f, sr); toneR.lowpass(cut, 0.6f, sr)
            satK = 1f + 1.2f * warmth
            satT = tanh((satK * SAT_BIAS).toDouble()).toFloat()
            // unity gain for quiet signals: d/dx tanh(k(x+b)) at 0 is k(1 - tanh²(kb))
            satNorm = 1f / (satK * (1f - satT * satT))
            appliedWarmth = warmth
        }

        // wow at the platter frequency (33 1/3 rpm = 0.556 Hz) plus belt flutter
        val rev = RPM / 60.0 * rate
        platter += rev / sr
        val crossed = platter >= 1.0
        val prevPhase = (platter - rev / sr).let { if (it < 0) it + 1.0 else it }
        if (crossed) platter -= 1.0
        wowPhase += rev / sr
        if (wowPhase >= 1.0) wowPhase -= 1.0
        flutterPhase += FLUTTER_HZ / sr
        if (flutterPhase >= 1.0) flutterPhase -= 1.0
        if (rnd() < 2f / sr) driftTarget = noise()
        drift += (driftTarget - drift) * (1f / sr)
        val mod = wow * (WOW_DEPTH * (sin(2.0 * PI * wowPhase).toFloat() + 0.35f * drift) +
            FLUTTER_DEPTH * sin(2.0 * PI * flutterPhase).toFloat())
        val delay = BASE_DELAY + mod

        delayL[writePos] = io[0]
        delayR[writePos] = io[1]
        var readPos = writePos - delay
        if (readPos < 0) readPos += DELAY_LEN
        val i0 = readPos.toInt()
        val i1 = if (i0 + 1 >= DELAY_LEN) 0 else i0 + 1
        val f = readPos - i0
        var l = delayL[i0] + (delayL[i1] - delayL[i0]) * f
        var r = delayR[i0] + (delayR[i1] - delayR[i0]) * f
        if (++writePos >= DELAY_LEN) writePos = 0

        if (warmth > 0f) {
            // lathe-style mono bass and a slightly narrower image
            val mid = (l + r) * 0.5f
            val side = sideHp.run((l - r) * 0.5f) * (1f - 0.22f * warmth)
            l = mid + side; r = mid - side
            l = toneL.run(l); r = toneR.run(r)
            val sl = (tanh((satK * (l + SAT_BIAS)).toDouble()).toFloat() - satT) * satNorm
            val sr2 = (tanh((satK * (r + SAT_BIAS)).toDouble()).toFloat() - satT) * satNorm
            val mix = 0.7f * warmth
            l = dcL.run(l + (sl - l) * mix)
            r = dcR.run(r + (sr2 - r) * mix)
        }

        var nl = 0f; var nr = 0f
        if (surface > 0f) {
            val h = hissHp.run(hissLp.run(noise()))
            val hiss = h * 0.006f * surface
            val rumble = rumbleLp.run(noise()) * 0.3f * surface
            nl += hiss + rumble
            nr += hiss * 0.8f + hissHp.run(noise()) * 0.0012f * surface + rumble
        }
        if (crackle > 0f) {
            if (rnd() < (3f + 70f * crackle) / sr) {
                val a = -ln(rnd() + 1e-6f) * 0.018f * crackle
                tickEnv += if (rnd() < 0.5f) a else -a
                tickPan = rnd()
            }
            if (rnd() < (0.08f + 0.5f * crackle) / sr) {
                val a = (0.08f + rnd() * 0.18f) * crackle
                popEnv += if (rnd() < 0.5f) a else -a
                popPan = 0.25f + rnd() * 0.5f
            }
            for (s in scratches.indices) {
                val at = scratches[s].toDouble()
                val hit = if (crossed) prevPhase < at || platter >= at else prevPhase < at && platter >= at
                if (hit) {
                    popEnv += 0.11f * scratchLevel[s] * (0.4f + crackle)
                    popPan = 0.45f
                }
            }
            val t = tickBp.run(tickEnv) + tickEnv * 0.4f
            val p = popLp.run(popEnv)
            tickEnv *= tickDecay
            popEnv *= popDecay
            nl += t * (1f - tickPan) * 2f + p * (1f - popPan) * 2f
            nr += t * tickPan * 2f + p * popPan * 2f
        }

        io[0] = l + nl
        io[1] = r + nr
    }

    companion object {
        const val RPM = 100.0 / 3.0
        const val FLUTTER_HZ = 6.3
        const val DELAY_LEN = 256
        const val BASE_DELAY = 96f
        // peak pitch deviation ≈ 2π·f·A/sr: ~0.3% wow, ~0.07% flutter at full
        const val WOW_DEPTH = 38f
        const val FLUTTER_DEPTH = 0.8f
        const val SAT_BIAS = 0.12f
    }
}

data class VinylSettings(
    val on: Boolean = false,
    val crackle: Float = 0.45f,
    val warmth: Float = 0.6f,
    val wow: Float = 0.4f,
    val surface: Float = 0.4f,
)

/** Record condition presets, from a sealed pressing to a crate-digging find. */
enum class VinylPreset(val label: String, val crackle: Float, val warmth: Float, val wow: Float, val surface: Float) {
    MINT("Mint", 0.12f, 0.5f, 0.2f, 0.15f),
    PLAYED("Played", 0.45f, 0.6f, 0.4f, 0.4f),
    DUSTY("Dusty", 0.8f, 0.75f, 0.6f, 0.7f),
    LOFI("Lo-fi", 0.6f, 1f, 0.9f, 0.85f);

    fun apply(on: Boolean = true) = VinylSettings(on, crackle, warmth, wow, surface)
}
