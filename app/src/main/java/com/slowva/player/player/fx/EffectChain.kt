package com.slowva.player.player.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Effect settings. ExoPlayer's Sonic stage does the speed; [rate] only drives the vinyl platter. */
class Fx {
    @Volatile var speed = 1.0f          // 0.05 .. 8, 1.0 = original tempo
    @Volatile var reverb = 0.0f         // 0 .. 10 wet amount, above 1 a longer tail and less dry
    @Volatile var retune = 1.0f         // tuning ratio, multiplies speed
    @Volatile var bassGainDb = 0.0f     // 0 .. 60 dB low shelf
    @Volatile var bassFreq = 120.0f     // 40 .. 320 Hz shelf corner
    @Volatile var width = 1.0f          // 0 mono .. 1 original .. 20 extra wide
    @Volatile var pan8d = 0.0f          // 0 .. 1 depth of the circling "8D" pan
    @Volatile var muffle = 0.0f         // 0 .. 10 "playing in another room" lowpass
    @Volatile var rain = RainPreset.OFF
    @Volatile var rainLevel = 1.0f       // 0 .. 15 on top of the preset
    @Volatile var vinyl = false         // record emulation on/off
    @Volatile var vinylCrackle = 0.45f  // 0 .. 1 ticks, pops, scratches
    @Volatile var vinylWarmth = 0.6f    // 0 .. 1 rolloff, saturation, mono bass
    @Volatile var vinylWow = 0.4f       // 0 .. 1 wow and flutter
    @Volatile var vinylSurface = 0.4f   // 0 .. 1 hiss and rumble
    @Volatile var mood = Mood.NORMAL    // Evil / Godly / Good & Evil layer
    /** device, genre and EQ layers, colour, ReplayGain and balance */
    val hifi = HifiFx()

    val rate: Float get() = (speed * retune).coerceIn(0.05f, 8.0f)
}

/** 0 at 1, 1 at 10 on a log scale: how far a setting reaches past 100%. */
internal fun extra(v: Float): Float = if (v <= 1f) 0f else (kotlin.math.ln(v) / kotlin.math.ln(10f)).coerceAtMost(1f)

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
        // past 1 the tail grows; stays below 1 so the combs never run away
        val fb = 0.70f + 0.20f * amount.coerceAtMost(1f) + 0.085f * extra(amount)
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


/**
 * Everything after the resampler: headroom preamp, device / genre / EQ / loudness layers,
 * reverb, bass shelf, vinyl, width / 8D / another room, rain, then tape, tube, night mode,
 * ReplayGain and balance. Peaks are left to the [Limiter] after it.
 */
class EffectChain(private val fx: Fx, private val sampleRate: Float) {
    private val verbL = Verb(0)
    private val verbR = Verb(SPREAD)
    private val shelfL = LowShelf()
    private val shelfR = LowShelf()
    private val vinyl = Vinyl(sampleRate)
    private var vinylMix = 0f
    private val rainFx = Rain(sampleRate)
    private val rainFrame = FloatArray(2)
    private var rainMix = 0f
    private var rainPreset = RainPreset.OFF
    private val roomL = Biquad()
    private val roomR = Biquad()
    private var appliedMuffle = -1f
    private var panPhase = 0.0
    private var smoothWidth = 1f
    private var smoothPan = 0f
    private var smoothWet = 0f
    private var appliedWet = -1f
    private val frame = FloatArray(2)
    private var smoothPre = 1f
    private val hifi = HifiChain(fx.hifi, sampleRate)
    private val moodFx = MoodFx(sampleRate)

    /**
     * How far the preamp turns everything down before the boosts. Only a part of each boost,
     * so bass and EQ actually get louder; the limiter catches the peaks that are left.
     */
    private fun boostDb(): Float = 0.25f * max(0f, fx.bassGainDb) + 0.5f * max(0f, fx.hifi.layers.headroomDb)

    private fun preampTarget(): Float = 10.0.pow(-boostDb() / 20.0).toFloat()

    fun reset() {
        smoothPre = preampTarget()
        smoothWet = fx.reverb
        smoothWidth = fx.width
        smoothPan = fx.pan8d
        vinylMix = if (fx.vinyl) 1f else 0f
        rainMix = if (fx.rain != RainPreset.OFF) fx.rainLevel else 0f
        if (fx.rain != RainPreset.OFF) rainPreset = fx.rain
        hifi.reset()
    }

    /** true when the chain would leave the audio untouched (lets silence stay silent) */
    val idle: Boolean
        get() = fx.reverb < 0.001f && smoothWet < 0.001f && fx.bassGainDb < 0.01f && !fx.vinyl && vinylMix < 0.0005f &&
            kotlin.math.abs(fx.width - 1f) < 0.002f && kotlin.math.abs(smoothWidth - 1f) < 0.002f &&
            fx.pan8d < 0.002f && smoothPan < 0.002f && fx.muffle < 0.001f &&
            fx.rain == RainPreset.OFF && rainMix < 0.0005f && smoothPre > 0.9999f && hifi.idle &&
            fx.mood == Mood.NORMAL && moodFx.idle

    /** Processes [frames] interleaved stereo samples of [buf] in place. */
    fun process(buf: FloatArray, frames: Int) {
        val targetWet = fx.reverb.coerceIn(0f, 10f)
        shelfL.update(fx.bassGainDb, fx.bassFreq, sampleRate)
        shelfR.update(fx.bassGainDb, fx.bassFreq, sampleRate)
        val boosting = fx.bassGainDb > 0.01f
        val vinylTarget = if (fx.vinyl) 1f else 0f
        val crackle = fx.vinylCrackle.coerceIn(0f, 1f)
        val warmth = fx.vinylWarmth.coerceIn(0f, 1f)
        val wow = fx.vinylWow.coerceIn(0f, 1f)
        val surface = fx.vinylSurface.coerceIn(0f, 1f)
        val targetWidth = fx.width.coerceIn(0f, 20f)
        val targetPan = fx.pan8d.coerceIn(0f, 1f)
        val muffle = fx.muffle.coerceIn(0f, 10f)
        val rainWant = fx.rain
        val rainLevel = fx.rainLevel.coerceIn(0f, 15f)
        val rate = fx.rate
        val targetPre = preampTarget()
        if (muffle != appliedMuffle) {
            val cut = if (muffle <= 1f) (18000.0 * Math.pow(350.0 / 18000.0, muffle.toDouble())).toFloat()
                else (350.0 * Math.pow(40.0 / 350.0, extra(muffle).toDouble())).toFloat()
            roomL.lowpass(cut, 0.75f, sampleRate); roomR.lowpass(cut, 0.75f, sampleRate)
            appliedMuffle = muffle
        }
        for (i in 0 until frames * 2) {
            if (i and 1 == 0) smoothPre += (targetPre - smoothPre) * 0.002f
            buf[i] *= smoothPre
        }
        hifi.pre(buf, frames)
        for (i in 0 until frames) {
            smoothWet += (targetWet - smoothWet) * 0.002f
            val wet = smoothWet
            if (wet != appliedWet) { verbL.set(wet); verbR.set(wet); appliedWet = wet }
            for (ch in 0..1) {
                val dry = buf[i * 2 + ch]
                var s = dry
                if (wet > 0.0005f) {
                    val w = (if (ch == 0) verbL else verbR).run(dry * 0.015f)
                    val x = extra(wet)
                    s = dry * (1f - wet.coerceAtMost(1f) * 0.35f - 0.65f * x) + w * (wet.coerceAtMost(1f) + 1.5f * x)
                }
                if (boosting) s = (if (ch == 0) shelfL else shelfR).run(s)
                frame[ch] = s
            }
            vinylMix += (vinylTarget - vinylMix) * 0.0005f
            if (vinylMix > 0.0005f) {
                val l = frame[0]; val r = frame[1]
                vinyl.run(frame, rate, crackle, warmth, wow, surface)
                frame[0] = l + (frame[0] - l) * vinylMix
                frame[1] = r + (frame[1] - r) * vinylMix
            } else if (vinylTarget == 0f) vinylMix = 0f
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
                frame[0] = frame[0] + (mono * gl - frame[0]) * smoothPan
                frame[1] = frame[1] + (mono * gr - frame[1]) * smoothPan
            }
            if (muffle > 0.001f) {
                val g = 1f + 0.4f * muffle.coerceAtMost(1f) + 0.6f * extra(muffle)
                frame[0] = roomL.run(frame[0]) * g
                frame[1] = roomR.run(frame[1]) * g
            }
            if (rainWant != RainPreset.OFF) rainPreset = rainWant
            rainMix += ((if (rainWant != RainPreset.OFF) rainLevel else 0f) - rainMix) * 0.0003f
            if (rainMix > 0.0005f && rainPreset != RainPreset.OFF) {
                rainFrame[0] = 0f; rainFrame[1] = 0f
                rainFx.run(rainFrame, rainPreset)
                frame[0] = softClip(frame[0] + rainFrame[0] * rainMix)
                frame[1] = softClip(frame[1] + rainFrame[1] * rainMix)
            }
            buf[i * 2] = frame[0]
            buf[i * 2 + 1] = frame[1]
        }
        moodFx.process(buf, frames, fx.mood)
        hifi.post(buf, frames)
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * kotlin.math.tanh(((a - 0.8f) / 0.2f).toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    companion object {
        const val SPREAD = 23
        const val PAN_HZ = 0.11
        val SQRT2 = sqrt(2f)
    }
}
