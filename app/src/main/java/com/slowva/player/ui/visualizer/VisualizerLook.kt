package com.slowva.player.ui.visualizer

import android.content.Context
import com.slowva.player.player.fx.SpectrumAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

enum class VizColor(val label: String) {
    WHITE("White"), ACCENT("Theme accent"), GRADIENT("Two-color gradient"), OIL("Oil"),
}

/** One look shared by every visualizer; the defaults are the original look. */
data class VizSettings(
    val color: VizColor = VizColor.WHITE,
    val gain: Float = 1f,
    val smoothing: Float = 0f,
    val bars: Int = SpectrumAnalyzer.BAR_COUNT,
    val mirror: Boolean = false,
    val glow: Boolean = false,
) {
    fun clamped() = copy(gain = gain.coerceIn(0.5f, 2f), smoothing = smoothing.coerceIn(0f, 0.9f), bars = bars.coerceIn(12, 48))
}

/** The saved [VizSettings], in the same "slowva-ui" prefs as the chosen visualizer. */
object VisualizerLook {
    private const val PREFS = "slowva-ui"
    val settings = MutableStateFlow(VizSettings())
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings.value = VizSettings(
            color = runCatching { VizColor.valueOf(p.getString("viz_color", null)!!) }.getOrDefault(VizColor.WHITE),
            gain = p.getFloat("viz_gain", 1f),
            smoothing = p.getFloat("viz_smoothing", 0f),
            bars = p.getInt("viz_bars", SpectrumAnalyzer.BAR_COUNT),
            mirror = p.getBoolean("viz_mirror", false),
            glow = p.getBoolean("viz_glow", false),
        ).clamped()
    }

    /** Applies at once; call [save] when the change is done (sliders save on release). */
    fun update(s: VizSettings) { settings.value = s.clamped() }

    fun save(ctx: Context) {
        val s = settings.value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("viz_color", s.color.name)
            .putFloat("viz_gain", s.gain)
            .putFloat("viz_smoothing", s.smoothing)
            .putInt("viz_bars", s.bars)
            .putBoolean("viz_mirror", s.mirror)
            .putBoolean("viz_glow", s.glow)
            .apply()
    }
}

/**
 * Fills [dst] with [src] stretched to its size: averages the covered bands when shrinking,
 * interpolates when growing. With [mirror] the low notes sit in the middle and both halves match.
 */
fun layoutBars(src: FloatArray, dst: FloatArray, mirror: Boolean) {
    val n = dst.size
    if (!mirror) return resample(src, dst, 0, n)
    val half = (n + 1) / 2
    resample(src, dst, n - half, half)
    for (j in 0 until n / 2) dst[j] = dst[n - 1 - j]
}

private fun resample(src: FloatArray, dst: FloatArray, off: Int, count: Int) {
    val m = src.size
    if (count >= m) {
        for (i in 0 until count) {
            val pos = if (count == 1) 0f else i * (m - 1f) / (count - 1)
            val lo = pos.toInt()
            dst[off + i] = src[lo] + (src[min(lo + 1, m - 1)] - src[lo]) * (pos - lo)
        }
        return
    }
    val span = m.toFloat() / count
    for (i in 0 until count) {
        val a = i * span
        val b = a + span
        var sum = 0f
        for (j in floor(a).toInt() until min(ceil(b).toInt(), m)) sum += src[j] * (min(b, j + 1f) - maxOf(a, j.toFloat()))
        dst[off + i] = sum / span
    }
}

/**
 * Eases [prev] toward [target] over [frames] 60 Hz frames: [smoothing] is how much is kept per frame
 * on the way down, rises keep half as much so beats still hit.
 */
fun smoothStep(prev: FloatArray, target: FloatArray, smoothing: Float, frames: Float) {
    val up = (smoothing * 0.5f).pow(frames)
    val down = smoothing.pow(frames)
    for (i in prev.indices) {
        val keep = if (target[i] > prev[i]) up else down
        prev[i] = target[i] + (prev[i] - target[i]) * keep
    }
}
