package com.slowva.player.ui.visualizer

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.fx.SpectrumAnalyzer
import com.slowva.player.ui.reducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Spectrum pictures the stage can show instead of a skin. */
enum class Visualizer(val label: String) {
    BARS("Bars"), WAVE("Wave"), RING("Ring"), PULSE("Pulse"),
    SPECTRUM_CIRCLE("Spectrum circle"), PARTICLES("Particles"), OSCILLOSCOPE("Oscilloscope"), MOUNTAINS("Mountains");

    companion object {
        private const val PREFS = "slowva-ui"

        /** The chosen visualizer, or null when a skin is on stage. */
        fun load(ctx: Context): Visualizer? = runCatching {
            valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("visualizer", null)!!)
        }.getOrNull()

        fun save(ctx: Context, v: Visualizer?) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("visualizer", v?.name).apply()
        }
    }
}

/** Under this, still previews animate anyway: live audio when something plays, a made-up beat otherwise. */
val LocalVisualizerDemo = staticCompositionLocalOf { false }

/** A calm, fixed shape for previews and pauses, so nothing is drawn from stale audio. */
private val IDLE = FloatArray(SpectrumAnalyzer.BAR_COUNT) { 0.12f + 0.28f * sin(it / 3.5f).let { s -> s * s } }

private const val BANDS = SpectrumAnalyzer.BAR_COUNT
private const val MAX_PARTICLES = 120
private const val SCOPE_POINTS = 160

/** Per-view buffers, so drawing a frame allocates nothing. */
private class Scratch {
    val start = System.nanoTime()
    var last = 0L
    val target = FloatArray(BANDS)
    val bands = FloatArray(BANDS)
    private val fake = FloatArray(BANDS)
    var bars = FloatArray(BANDS)
    val path = Path()
    private var strokeFor = -1f
    var thick = Stroke()
    var thin = Stroke()

    // particles: position and velocity in radii from the centre (per second), life runs 1 -> 0
    val px = FloatArray(MAX_PARTICLES)
    val py = FloatArray(MAX_PARTICLES)
    val vx = FloatArray(MAX_PARTICLES)
    val vy = FloatArray(MAX_PARTICLES)
    val life = FloatArray(MAX_PARTICLES)
    var spawn = 0f

    init {
        // a fixed scatter, so the still frame looks the same every time
        val r = Random(7)
        for (i in 0 until MAX_PARTICLES) {
            val angle = r.nextFloat() * 2 * PI.toFloat()
            val d = 0.3f + 0.7f * r.nextFloat()
            px[i] = cos(angle) * d
            py[i] = sin(angle) * d
            life[i] = r.nextFloat()
        }
    }

    fun strokes(m: Float) {
        if (m == strokeFor) return
        strokeFor = m
        thick = Stroke(m * 0.012f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        thin = Stroke(m * 0.008f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    /** A 120 bpm kick over slowly rolling mids, for the preview when nothing plays. */
    fun fake(t: Float): FloatArray {
        val k = sin(t * 4 * PI.toFloat()).coerceAtLeast(0f)
        val beat = k * k * k * k
        for (i in 0 until BANDS) {
            val roll = sin(t * 1.3f + i * 0.4f)
            fake[i] = (0.18f + 0.22f * roll * roll + beat * 0.7f * (1f - i / BANDS.toFloat())).coerceIn(0f, 1f)
        }
        return fake
    }
}

/**
 * [v] drawn [side] wide from the live spectrum in the [VisualizerLook] settings. Only a playing,
 * non-[still] view reads the spectrum, and it reads it in the draw phase, so new audio frames redraw
 * without recomposing.
 */
@Composable
fun VisualizerView(v: Visualizer, art: Painter, playing: Boolean, still: Boolean, side: Dp) {
    val ctx = LocalContext.current
    remember { VisualizerLook.load(ctx) }
    val look by VisualizerLook.settings.collectAsState()
    val reduced = reducedMotion()
    val demo = LocalVisualizerDemo.current && !reduced
    val audio = !reduced && if (demo) PlayerHub.playback.collectAsState().value?.isPlaying == true else playing && !still
    val live = audio || demo
    val spectrum: State<FloatArray> =
        if (audio) PlayerHub.spectrum.collectAsState() else remember { mutableStateOf(IDLE) }
    // styles that move on their own, the Oil shimmer and the made-up beat need every frame, not just audio frames
    val timed = live && (!audio || look.color == VizColor.OIL || v == Visualizer.PARTICLES || v == Visualizer.OSCILLOSCOPE)
    val tick = remember { mutableLongStateOf(0L) }
    if (timed) LaunchedEffect(Unit) { while (true) withFrameNanos { tick.longValue = it } }
    val sc = remember { Scratch() }

    // drawn in white over the dark player
    val ink = Color.White
    val dim = ink.copy(alpha = 0.55f)
    val barBrush = remember(ink) { Brush.verticalGradient(listOf(dim, ink, dim)) }
    val one = MaterialTheme.colorScheme.primary
    val two = MaterialTheme.colorScheme.tertiary
    // the shapes are drawn in ink, then recoloured through this keeping their alpha
    val tint: Brush? = remember(look.color, one, two) {
        when (look.color) {
            VizColor.WHITE -> null
            VizColor.ACCENT -> SolidColor(one)
            VizColor.GRADIENT -> Brush.linearGradient(listOf(one, two))
            VizColor.OIL -> Brush.sweepGradient(listOf(one, two, one))
        }
    }
    val blur = with(LocalDensity.current) { side.toPx() } * 0.025f
    val glow = remember(look.glow, blur) {
        // shortcut: no glow before Android 12 (no RenderEffect), draw a blurred copy by hand if older phones need it
        if (look.glow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) glowEffect(blur) else null
    }

    Box(Modifier.size(side).semantics { contentDescription = "${v.label} visualizer" }, contentAlignment = Alignment.Center) {
        if (v == Visualizer.RING || v == Visualizer.SPECTRUM_CIRCLE) {
            val r = if (v == Visualizer.RING) 0.56f else 0.5f
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.size(side * r).clip(CircleShape))
        }
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                if (tint != null) compositingStrategy = CompositingStrategy.Offscreen
                renderEffect = glow
            },
        ) {
            tick.longValue // subscribes the draw to the frame clock while timed
            val now = System.nanoTime()
            val frames = if (live && sc.last != 0L) ((now - sc.last) / 16_666_667f).coerceIn(0f, 6f) else 0f
            sc.last = now
            val t = if (live) (now - sc.start) / 1e9f else 0f
            val raw = if (live && !audio) sc.fake(t) else spectrum.value
            for (i in 0 until min(raw.size, BANDS)) sc.target[i] = (raw[i] * look.gain).coerceIn(0f, 1f)
            if (frames > 0f) smoothStep(sc.bands, sc.target, look.smoothing, frames) else sc.target.copyInto(sc.bands)
            if (sc.bars.size != look.bars) sc.bars = FloatArray(look.bars)
            layoutBars(sc.bands, sc.bars, look.mirror)
            sc.strokes(size.minDimension)
            val s = sc.bars
            when (v) {
                Visualizer.BARS -> bars(s, barBrush)
                Visualizer.WAVE -> wave(s, sc, ink, dim)
                Visualizer.RING -> ring(s, ink, dim)
                Visualizer.PULSE -> pulse(sc.bands, ink, dim)
                Visualizer.SPECTRUM_CIRCLE -> spectrumCircle(s, sc, ink, dim)
                Visualizer.PARTICLES -> particles(sc, frames, ink, dim)
                Visualizer.OSCILLOSCOPE -> oscilloscope(s, t, sc, ink, dim)
                Visualizer.MOUNTAINS -> mountains(s, sc.path, ink)
            }
            if (tint != null) {
                if (look.color == VizColor.OIL) rotate(t * 12f) { drawCircle(tint, size.maxDimension, blendMode = BlendMode.SrcIn) }
                else drawRect(tint, blendMode = BlendMode.SrcIn)
            }
        }
    }
}

/** The drawing with a soft blurred copy of itself underneath. */
@RequiresApi(Build.VERSION_CODES.S)
private fun glowEffect(r: Float) = android.graphics.RenderEffect.createBlendModeEffect(
    android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.DECAL),
    android.graphics.RenderEffect.createOffsetEffect(0f, 0f),
    android.graphics.BlendMode.SRC_OVER,
).asComposeRenderEffect()

private fun FloatArray.avg(from: Int, to: Int): Float {
    var sum = 0f
    for (i in from until to) sum += this[i]
    return sum / (to - from)
}

private fun DrawScope.bars(s: FloatArray, brush: Brush) {
    val n = s.size
    val slot = size.width / n
    val w = slot * 0.62f
    val mid = size.height / 2
    for (i in 0 until n) {
        val h = (s[i] * size.height * 0.9f).coerceAtLeast(w)
        drawRoundRect(brush, Offset(i * slot + (slot - w) / 2, mid - h / 2), Size(w, h), CornerRadius(w / 2))
    }
}

private fun DrawScope.wave(s: FloatArray, sc: Scratch, a: Color, b: Color) {
    val n = s.size
    val mid = size.height / 2
    val step = size.width / (n - 1)
    // alternate up and down so the bands read as one swinging line; the second, dimmer line swings the other way
    for (pass in 0..1) {
        val k = if (pass == 0) 1f else -0.6f
        val path = sc.path
        path.reset()
        var prev = mid - s[0] * size.height * 0.42f * k
        path.moveTo(0f, prev)
        for (i in 1 until n) {
            val y = mid + (if (i % 2 == 0) -1 else 1) * s[i] * size.height * 0.42f * k
            val x = i * step
            path.cubicTo(x - step / 2, prev, x - step / 2, y, x, y)
            prev = y
        }
        drawPath(path, if (pass == 0) a else b.copy(alpha = 0.6f), style = if (pass == 0) sc.thick else sc.thin)
    }
}

private fun DrawScope.ring(s: FloatArray, a: Color, b: Color) {
    val r0 = size.minDimension * 0.30f
    val n = s.size * 2
    val w = (2 * PI * r0 / n * 0.55).toFloat()
    for (i in 0 until n) {
        // mirrored halves, so low notes sit at the top and bottom meets smoothly
        val v = s[if (i < s.size) i else n - 1 - i]
        val len = (v * size.minDimension * 0.19f).coerceAtLeast(w)
        rotate(360f * i / n) {
            drawLine(
                if (i % 2 == 0) a else b,
                Offset(center.x, center.y - r0), Offset(center.x, center.y - r0 - len),
                strokeWidth = w, cap = StrokeCap.Round,
            )
        }
    }
}

// shortcut: allocates small gradient brushes per frame (their centres move); cache shaders if it shows in a profile
private fun DrawScope.pulse(s: FloatArray, a: Color, b: Color) {
    val n = s.size
    val bass = s.avg(0, 4)
    val mids = s.avg(6, 13)
    val highs = s.avg(14, n)
    val m = size.minDimension
    for (k in 0..2) {
        val angle = 2.1f * k
        val e = if (k == 0) bass else if (k == 1) mids else highs
        val color = if (k == 0) a else if (k == 1) b else a.copy(alpha = 0.8f)
        val r = m * (0.18f + 0.22f * e)
        val c = Offset(center.x + cos(angle) * m * 0.14f * (1 + e), center.y + sin(angle) * m * 0.14f * (1 + e))
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)), c, r), r, c)
    }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f * bass), Color.Transparent), center, m * 0.2f), m * 0.2f)
}

/** One bar per band all the way round the cover, on a thin circle. */
private fun DrawScope.spectrumCircle(s: FloatArray, sc: Scratch, a: Color, b: Color) {
    val m = size.minDimension
    val r0 = m * 0.27f
    val n = s.size
    val w = (2 * PI * r0 / n * 0.6).toFloat()
    drawCircle(b, r0, style = sc.thin)
    val from = center.y - r0 - m * 0.015f
    for (i in 0 until n) {
        val len = (s[i] * m * 0.21f).coerceAtLeast(w * 0.3f)
        rotate(360f * i / n) {
            drawLine(a, Offset(center.x, from), Offset(center.x, from - len), strokeWidth = w, cap = StrokeCap.Butt)
        }
    }
}

/** Sparks thrown from around the centre; bass throws more, faster, and pushes the ones in flight. */
private fun DrawScope.particles(sc: Scratch, frames: Float, a: Color, b: Color) {
    val bass = sc.bands.avg(0, 4)
    val dt = frames / 60f
    if (dt > 0f) {
        sc.spawn = (sc.spawn + dt * (8f + 160f * bass * bass)).coerceAtMost(6f)
        val push = bass * bass * 2.5f * dt
        val drag = 1f - 0.6f * dt
        for (i in 0 until MAX_PARTICLES) {
            if (sc.life[i] <= 0f) {
                if (sc.spawn < 1f) continue
                sc.spawn -= 1f
                val angle = Random.nextFloat() * 2 * PI.toFloat()
                val speed = 0.1f + 0.8f * bass
                sc.px[i] = cos(angle) * 0.2f
                sc.py[i] = sin(angle) * 0.2f
                sc.vx[i] = cos(angle) * speed
                sc.vy[i] = sin(angle) * speed
                sc.life[i] = 1f
                continue
            }
            val d = sqrt(sc.px[i] * sc.px[i] + sc.py[i] * sc.py[i]).coerceAtLeast(0.01f)
            sc.vx[i] = (sc.vx[i] + sc.px[i] / d * push) * drag
            sc.vy[i] = (sc.vy[i] + sc.py[i] / d * push) * drag
            sc.px[i] += sc.vx[i] * dt
            sc.py[i] += sc.vy[i] * dt
            sc.life[i] -= dt * 0.5f
            if (d > 1.15f) sc.life[i] = 0f
        }
    }
    val r = size.minDimension / 2
    for (i in 0 until MAX_PARTICLES) {
        val life = sc.life[i]
        if (life <= 0f) continue
        val c = if (i % 3 == 0) b else a
        val dot = r * (0.01f + 0.006f * (i % 4)) * (0.5f + 0.5f * life)
        drawCircle(c.copy(alpha = c.alpha * life), dot, Offset(center.x + sc.px[i] * r, center.y + sc.py[i] * r))
    }
}

/** A scope-style trace over a faint centre line. */
private fun DrawScope.oscilloscope(s: FloatArray, t: Float, sc: Scratch, a: Color, b: Color) {
    // shortcut: we only get band levels, not samples, so the trace is a sum of sines weighted by the bands; feed real PCM from the audio processor for a true scope
    val n = s.size
    val mid = size.height / 2
    val amp = size.height * 0.4f
    val norm = 1.2f / sqrt(n.toFloat())
    val path = sc.path
    path.reset()
    for (j in 0..SCOPE_POINTS) {
        val x = j / SCOPE_POINTS.toFloat()
        var y = 0f
        for (k in 0 until n) {
            val f = k / (n - 1f)
            // up to 12 cycles across, so the trace stays smooth at 160 points
            y += s[k] * sin(x * (1f + 11f * f * sqrt(f)) * 2 * PI.toFloat() + t * (1.5f + 3f * f) + k)
        }
        val env = 0.25f + 0.75f * sin(x * PI.toFloat())
        val py = mid - (y * norm).coerceIn(-1f, 1f) * env * amp
        if (j == 0) path.moveTo(0f, py) else path.lineTo(x * size.width, py)
    }
    drawLine(b.copy(alpha = 0.3f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = sc.thin.width)
    drawPath(path, a, style = sc.thick)
}

/** Three filled ridges, the back ones dimmer and reading other bands so they don't line up. */
private fun DrawScope.mountains(s: FloatArray, path: Path, a: Color) {
    val n = s.size
    val step = size.width / (n - 1)
    for (layer in 0..2) {
        val shift = (2 - layer) * n / 5
        val base = size.height * (0.6f + 0.13f * layer)
        val amp = size.height * (0.5f - 0.06f * layer)
        path.reset()
        var prev = base - s[shift % n] * amp
        path.moveTo(0f, size.height)
        path.lineTo(0f, prev)
        for (i in 1 until n) {
            val y = base - s[(i + shift) % n] * amp
            val x = i * step
            path.cubicTo(x - step / 2, prev, x - step / 2, y, x, y)
            prev = y
        }
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(path, a.copy(alpha = a.alpha * (0.3f + 0.35f * layer)))
    }
}
