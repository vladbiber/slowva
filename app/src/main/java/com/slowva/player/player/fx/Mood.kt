package com.slowva.player.player.fx

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/** Evil, Godly or both at once (Good & Evil); [pitch] multiplies the playback rate. */
enum class Mood(val pitch: Float) { NORMAL(1f), EVIL(0.84f), GODLY(1.02f), GOOD_EVIL(0.94f) }

/** The two Settings switches; both on is Good & Evil. Kept in the "slowva-ui" prefs. */
object Moods {
    private var app: Context? = null
    val evil = MutableStateFlow(false)
    val godly = MutableStateFlow(false)
    val mood = MutableStateFlow(Mood.NORMAL)

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        evil.value = p.getBoolean("evil_mode", false)
        godly.value = p.getBoolean("godly_mode", false)
        mood.value = of(evil.value, godly.value)
    }

    fun setEvil(on: Boolean) { evil.value = on; changed() }
    fun setGodly(on: Boolean) { godly.value = on; changed() }

    private fun changed() {
        mood.value = of(evil.value, godly.value)
        app?.let { setIcon(it, mood.value) }
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("evil_mode", evil.value)?.putBoolean("godly_mode", godly.value)?.apply()
    }

    private fun of(evil: Boolean, godly: Boolean) = when {
        evil && godly -> Mood.GOOD_EVIL
        evil -> Mood.EVIL
        godly -> Mood.GODLY
        else -> Mood.NORMAL
    }

    /** Swaps the launcher icon by enabling one activity alias and disabling the rest. */
    private fun setIcon(context: Context, m: Mood) {
        val pm = context.packageManager
        val want = when (m) { Mood.NORMAL -> "IconDefault"; Mood.EVIL -> "IconEvil"; Mood.GODLY -> "IconGodly"; Mood.GOOD_EVIL -> "IconGoodEvil" }
        // enable the new one first so the app always has a launcher entry
        val names = listOf(want) + listOf("IconDefault", "IconEvil", "IconGodly", "IconGoodEvil").filter { it != want }
        for (n in names) runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(context, "com.slowva.player.$n"),
                if (n == want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private const val PREFS = "slowva-ui"
}

/** A plain Freeverb tank with its own size and darkness, so the mood rooms don't touch the user's reverb. */
private class Tank(sr: Float, offset: Int) {
    private val scale = sr / 44100f
    private val combs = intArrayOf(1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116).map { FloatArray(((it + offset) * scale).toInt().coerceAtLeast(1)) }
    private val combPos = IntArray(8)
    private val combStore = FloatArray(8)
    private val alls = intArrayOf(225, 556, 441, 341).map { FloatArray(((it + offset) * scale).toInt().coerceAtLeast(1)) }
    private val allPos = IntArray(4)
    var feedback = 0.84f
    var damp = 0.2f

    fun run(x: Float): Float {
        var out = 0f
        for (i in combs.indices) {
            val b = combs[i]; val p = combPos[i]
            val y = b[p]
            combStore[i] = y * (1f - damp) + combStore[i] * damp
            b[p] = x + combStore[i] * feedback
            combPos[i] = if (p + 1 >= b.size) 0 else p + 1
            out += y
        }
        for (i in alls.indices) {
            val b = alls[i]; val p = allPos[i]
            val v = b[p]
            b[p] = out + v * 0.5f
            out = v - out
            allPos[i] = if (p + 1 >= b.size) 0 else p + 1
        }
        return out
    }
}

/** A short modulated delay line, read with linear interpolation. */
private class ModDelay(sr: Float, maxMs: Float) {
    private val buf = FloatArray((sr * maxMs / 1000f).toInt() + 4)
    private var pos = 0

    fun push(x: Float) { buf[pos] = x; pos = if (pos + 1 >= buf.size) 0 else pos + 1 }

    fun read(delaySamples: Float): Float {
        val d = delaySamples.coerceIn(1f, buf.size - 3f)
        var r = pos - d
        if (r < 0) r += buf.size
        val i = r.toInt(); val f = r - i
        val a = buf[i % buf.size]; val b = buf[(i + 1) % buf.size]
        return a + (b - a) * f
    }
}

/**
 * The mood layer, last before ReplayGain: Evil is drive, a sub octave and a dark hall; Godly a choir chorus,
 * a bright cathedral and organ warmth; Good & Evil a flanger, tremolo, bitcrush and both rooms.
 */
internal class MoodFx(private val sampleRate: Float) {
    private val tankL = Tank(sampleRate, 0)
    private val tankR = Tank(sampleRate, 23)
    private val chorusL = ModDelay(sampleRate, 40f)
    private val chorusR = ModDelay(sampleRate, 40f)
    private val flangeL = ModDelay(sampleRate, 12f)
    private val flangeR = ModDelay(sampleRate, 12f)
    private val darkL = Biquad()
    private val darkR = Biquad()
    private val subLp = Biquad()
    private val airL = Biquad()
    private val airR = Biquad()
    private val shelfL = Biquad()
    private val shelfR = Biquad()
    private var applied: Mood? = null
    private var shown = Mood.NORMAL
    private var mix = 0f
    private var phase = 0.0
    private var subSign = 1f
    private var lastSub = 0f
    private var subEnv = 0f
    private var holdL = 0f
    private var holdR = 0f
    private var hold = 0
    private var fbL = 0f
    private var fbR = 0f

    val idle get() = mix < 0.0005f

    private fun setUp(m: Mood) {
        when (m) {
            Mood.EVIL -> {
                tankL.feedback = 0.9f; tankR.feedback = 0.9f; tankL.damp = 0.55f; tankR.damp = 0.55f
                darkL.lowpass(4200f, 0.7f, sampleRate); darkR.lowpass(4200f, 0.7f, sampleRate)
                shelfL.design(FilterType.LOW_SHELF, 90f, 9f, 0.7f, sampleRate); shelfR.design(FilterType.LOW_SHELF, 90f, 9f, 0.7f, sampleRate)
            }
            Mood.GODLY -> {
                tankL.feedback = 0.94f; tankR.feedback = 0.94f; tankL.damp = 0.12f; tankR.damp = 0.12f
                airL.design(FilterType.HIGH_SHELF, 7000f, 5f, 0.7f, sampleRate); airR.design(FilterType.HIGH_SHELF, 7000f, 5f, 0.7f, sampleRate)
                shelfL.design(FilterType.LOW_SHELF, 160f, 3f, 0.7f, sampleRate); shelfR.design(FilterType.LOW_SHELF, 160f, 3f, 0.7f, sampleRate)
            }
            Mood.GOOD_EVIL -> {
                tankL.feedback = 0.88f; tankR.feedback = 0.88f; tankL.damp = 0.35f; tankR.damp = 0.35f
                darkL.lowpass(6500f, 0.9f, sampleRate); darkR.lowpass(6500f, 0.9f, sampleRate)
            }
            Mood.NORMAL -> {}
        }
        subLp.lowpass(220f, 0.7f, sampleRate)
    }

    fun process(buf: FloatArray, frames: Int, want: Mood) {
        // fade the old mood out before the new one fades in, so filters never jump mid-sound
        if (want != shown && mix < 0.001f) shown = want
        val target = if (want == shown && want != Mood.NORMAL) 1f else 0f
        if (shown != applied) { setUp(shown); applied = shown }
        if (shown == Mood.NORMAL && mix < 0.0005f) { mix = 0f; return }
        val m = shown
        for (i in 0 until frames) {
            mix += (target - mix) * 0.0004f
            val l = buf[i * 2]; val r = buf[i * 2 + 1]
            var ol: Float; var or: Float
            when (m) {
                Mood.EVIL -> {
                    // flip-flop octaver: one sign change per two zero crossings of the lowpassed bass
                    val mono = subLp.run((l + r) * 0.5f)
                    if ((mono >= 0f) != (lastSub >= 0f)) { if (mono >= 0f) subSign = -subSign }
                    lastSub = mono
                    subEnv += (abs(mono) - subEnv) * 0.01f
                    val sub = subSign * subEnv * 1.6f
                    val dl = darkL.run(shelfL.run(l)); val dr = darkR.run(shelfR.run(r))
                    val gl = tanh((dl * 3.2f + sub).toDouble()).toFloat() * 0.6f
                    val gr = tanh((dr * 3.2f + sub).toDouble()).toFloat() * 0.6f
                    val hall = (gl + gr) * 0.008f
                    ol = gl + tankL.run(hall) * 0.9f
                    or = gr + tankR.run(hall) * 0.9f
                }
                Mood.GODLY -> {
                    phase += 1.0 / sampleRate
                    chorusL.push(l); chorusR.push(r)
                    val ms = sampleRate / 1000f
                    // three voices drifting apart like a choir
                    var cl = l * 0.5f; var cr = r * 0.5f
                    for (v in 0 until 3) {
                        val lfo = sin(2.0 * PI * (phase * (0.21 + 0.07 * v) + v / 3.0)).toFloat()
                        cl += chorusL.read((18f + 6f * v + 4f * lfo) * ms) * 0.33f
                        cr += chorusR.read((20f + 5f * v - 4f * lfo) * ms) * 0.33f
                    }
                    // even-harmonic warmth, like air through organ pipes
                    cl = airL.run(shelfL.run(cl + 0.12f * cl * abs(cl)))
                    cr = airR.run(shelfR.run(cr + 0.12f * cr * abs(cr)))
                    val nave = (cl + cr) * 0.012f
                    ol = cl * 0.75f + tankL.run(nave) * 1.2f
                    or = cr * 0.75f + tankR.run(nave) * 1.2f
                }
                Mood.GOOD_EVIL -> {
                    phase += 1.0 / sampleRate
                    val ms = sampleRate / 1000f
                    val sweep = (0.5 + 0.5 * sin(2.0 * PI * phase * 0.17)).toFloat()
                    flangeL.push(l + fbL * 0.7f); flangeR.push(r + fbR * 0.7f)
                    fbL = flangeL.read((1f + 6f * sweep) * ms); fbR = flangeR.read((1f + 6f * (1f - sweep)) * ms)
                    var fl = (l + fbL) * 0.6f; var fr = (r + fbR) * 0.6f
                    // sample-and-hold plus 6-bit steps for the broken-radio grit
                    if (hold-- <= 0) { holdL = fl; holdR = fr; hold = 3 }
                    val step = 1f / 32f
                    val crushL = Math.round(holdL / step) * step
                    val crushR = Math.round(holdR / step) * step
                    fl = fl * 0.55f + crushL * 0.45f; fr = fr * 0.55f + crushR * 0.45f
                    val trem = 0.65f + 0.35f * sin(2.0 * PI * phase * 4.7).toFloat()
                    fl = darkL.run(fl) * trem; fr = darkR.run(fr) * trem
                    val room = (fl + fr) * 0.01f
                    ol = fl + tankL.run(room) * 0.8f
                    or = fr + tankR.run(room) * 0.8f
                }
                Mood.NORMAL -> { ol = l; or = r }
            }
            buf[i * 2] = l + (ol - l) * mix
            buf[i * 2 + 1] = r + (or - r) * mix
        }
    }
}
