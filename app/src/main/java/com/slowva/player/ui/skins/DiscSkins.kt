package com.slowva.player.ui.skins

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.player.PlayerHub
import com.slowva.player.ui.formatMs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

private const val ARC_FROM = -78f
private const val ARC_TO = 72f
private const val ARM_REST = 70f
private const val GROOVE_OUT = 0.94f
private const val GROOVE_IN = 0.42f

private enum class Grab { NONE, TAP, SCRATCH, ARC, ARM }

private class Geo(val c: Offset, val r: Float) {
    val pivot = Offset(c.x + r * 0.78f, c.y - r * 0.62f)
    val armLen = r * 0.99f
    fun head(phi: Float): Offset {
        val a = Math.toRadians(phi.toDouble())
        return Offset(pivot.x + armLen * cos(a).toFloat(), pivot.y + armLen * sin(a).toFloat())
    }
    fun headRadius(phi: Float) = (head(phi) - c).getDistance()
    /** arm angle that puts the stylus at [radius] from the spindle */
    fun phiFor(radius: Float): Float {
        var lo = ARM_REST; var hi = 140f
        repeat(24) {
            val mid = (lo + hi) / 2
            if (headRadius(mid) > radius) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
    fun phiForFraction(f: Float) = phiFor(r * (GROOVE_OUT - f.coerceIn(0f, 1f) * (GROOVE_OUT - GROOVE_IN)))
    fun fractionFor(phi: Float) = (GROOVE_OUT - headRadius(phi) / r) / (GROOVE_OUT - GROOVE_IN)
}

private fun geoFor(size: Size) = Geo(Offset(size.width * 0.47f, size.height * 0.5f), size.width * 0.43f)

/** Spinning disc stage: scratch to seek, drag the progress arc, tap to play/pause, tonearm for vinyl. */
@Composable
private fun DiscStage(
    live: LiveProgress,
    accent: Color,
    msPerRev: Float,
    degPerSec: Float,
    tonearm: Boolean,
    drawDisc: DrawScope.(c: Offset, r: Float, angle: Float) -> Unit,
    modifier: Modifier = Modifier,
    timeInside: Boolean = false,
    what: String = "",
) {
    val rate by PlayerHub.rate.collectAsState()
    val spectrum by PlayerHub.spectrum.collectAsState()
    val platter = rememberPlatter(live.playing, degPerSec, rate)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val arm = remember { Animatable(ARM_REST) }
    var armHeld by remember { mutableStateOf(false) }
    val liveRef by rememberUpdatedState(live)

    // the arm swings in when playing and tracks the groove, swings out on pause
    LaunchedEffect(tonearm) {
        if (!tonearm) return@LaunchedEffect
        snapshotFlow { Triple(liveRef.playing, liveRef.fraction, armHeld) }
            .collect { (playing, f, held) ->
                if (held) return@collect
                val target = if (playing) UNIT.phiForFraction(f) else ARM_REST
                if (abs(target - arm.targetValue) > 1.5f || !arm.isRunning && abs(target - arm.value) > 0.05f) {
                    scope.launch { arm.animateTo(target, spring(0.85f, Spring.StiffnessVeryLow)) }
                }
            }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .stageSemantics(what, live)
            .pointerInput(tonearm) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val g = geoFor(Size(size.width.toFloat(), size.height.toFloat()))
                    val p0 = down.position
                    val dist = (p0 - g.c).getDistance()
                    val ang = angleOf(g.c, p0)
                    val onArm = tonearm && (p0 - g.head(arm.value)).getDistance() < g.r * 0.2f
                    var mode = when {
                        onArm -> Grab.ARM
                        dist > g.r * 0.98f && dist < g.r * 1.3f && ang in (ARC_FROM - 6)..(ARC_TO + 6) -> Grab.ARC
                        dist <= g.r -> Grab.TAP
                        else -> Grab.NONE
                    }
                    if (mode == Grab.NONE) return@awaitEachGesture
                    var last = p0
                    var turned = 0f
                    var tick = 0f
                    val startMs = liveRef.positionMs
                    if (mode == Grab.ARM) { armHeld = true; scope.launch { arm.stop() } }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val pos = ch.position
                        if (mode == Grab.TAP && (pos - p0).getDistance() > viewConfiguration.touchSlop) {
                            mode = Grab.SCRATCH
                            platter.held = true
                        }
                        when (mode) {
                            Grab.SCRATCH -> {
                                val d = angleDelta(g.c, last, pos)
                                platter.angle = (platter.angle + d) % 360f
                                turned += d
                                tick += abs(d)
                                if (tick > 18f) { tick = 0f; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                liveRef.preview = (startMs + (turned / 360f * msPerRev).toLong()).coerceIn(0L, liveRef.durationMs)
                            }
                            Grab.ARC -> {
                                val a = angleOf(g.c, pos).coerceIn(ARC_FROM, ARC_TO)
                                liveRef.preview = ((a - ARC_FROM) / (ARC_TO - ARC_FROM) * liveRef.durationMs).toLong()
                            }
                            Grab.ARM -> {
                                val phi = (angleOf(g.pivot, pos)).coerceIn(ARM_REST, g.phiFor(g.r * GROOVE_IN))
                                scope.launch { arm.snapTo(phi) }
                                val f = g.fractionFor(phi)
                                liveRef.preview = if (f < 0f) null else (f * liveRef.durationMs).toLong()
                            }
                            else -> {}
                        }
                        last = pos
                        if (ch.positionChange() != Offset.Zero) ch.consume()
                    }
                    when (mode) {
                        Grab.TAP -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); PlayerHub.togglePlay() }
                        Grab.SCRATCH -> { platter.held = false; liveRef.preview?.let { liveRef.seekTo(it) } }
                        Grab.ARC -> liveRef.preview?.let { liveRef.seekTo(it) }
                        Grab.ARM -> {
                            armHeld = false
                            val target = liveRef.preview
                            if (target == null) {
                                // lifted off the record: stop like a real deck
                                if (liveRef.playing) PlayerHub.togglePlay()
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                liveRef.seekTo(target)
                                if (!liveRef.playing) PlayerHub.togglePlay()
                            }
                        }
                        else -> {}
                    }
                }
            }
    ) {
        val g = geoFor(size)
        drawDisc(g.c, g.r, platter.angle)
        drawWave(g, live.fraction, if (live.still) FloatArray(0) else spectrum, accent, Math.toRadians(platter.angle * 2.0).toFloat())
        if (timeInside) {
            val txt = measurer.measure(formatMs(live.positionMs.toInt()), TextStyle(color = accent, fontSize = 11.sp))
            val a = Math.toRadians(122.0)
            val at = Offset(g.c.x + g.r * 0.76f * cos(a).toFloat(), g.c.y + g.r * 0.76f * sin(a).toFloat())
            drawText(txt, topLeft = at - Offset(txt.size.width / 2f, txt.size.height / 2f))
        }
        if (tonearm) drawTonearm(g, arm.value)
    }
}

// the arm angles only depend on proportions, so a unit disc answers for every size
private val UNIT = Geo(Offset.Zero, 1f)

private fun DrawScope.drawWave(g: Geo, fraction: Float, bars: FloatArray, accent: Color, phase: Float) {
    val n = 160
    val gap = 12.dp.toPx()
    val amp = 7.dp.toPx()
    val done = Path(); val rest = Path()
    val split = (fraction.coerceIn(0f, 1f) * n).toInt()
    var dot = Offset.Zero
    for (i in 0..n) {
        val t = i / n.toFloat()
        val b = if (bars.isEmpty()) 0f else {
            val x = t * (bars.size - 1)
            val i0 = x.toInt().coerceIn(0, bars.size - 1)
            val i1 = (i0 + 1).coerceAtMost(bars.size - 1)
            bars[i0] + (bars[i1] - bars[i0]) * (x - i0)
        }
        val wobble = sin(t * TAU * 7.5f + phase) * (0.18f + b) + 0.35f * sin(t * TAU * 17f - phase * 1.7f) * b
        val rr = g.r + gap + amp * wobble
        val a = Math.toRadians((ARC_FROM + t * (ARC_TO - ARC_FROM)).toDouble())
        val p = Offset(g.c.x + rr * cos(a).toFloat(), g.c.y + rr * sin(a).toFloat())
        if (i <= split) { if (i == 0) done.moveTo(p.x, p.y) else done.lineTo(p.x, p.y) }
        if (i >= split) { if (i == split) rest.moveTo(p.x, p.y) else rest.lineTo(p.x, p.y) }
        if (i == split) dot = p
    }
    drawPath(rest, accent.copy(alpha = 0.32f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    drawPath(done, accent, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
    drawCircle(accent.copy(alpha = 0.3f), 8.dp.toPx(), dot)
    drawCircle(accent, 4.dp.toPx(), dot)
}

private fun DrawScope.drawTonearm(g: Geo, phi: Float) = drawTonearm(g.pivot, g.head(phi), g.r)

/** Gold tonearm from [pivot] to the stylus at [head]; [r] is the disc radius it is sized for. */
internal fun DrawScope.drawTonearm(pivot: Offset, head: Offset, r: Float) {
    val armLen = (head - pivot).getDistance()
    val phi = angleOf(pivot, head)
    val gold = Brush.linearGradient(listOf(Color(0xFFE9D9A6), Color(0xFFB89A55), Color(0xFFF2E6BF)))
    val dir = (head - pivot) / armLen
    val back = pivot - dir * (r * 0.16f)
    val shadow = Offset(4.dp.toPx(), 6.dp.toPx())
    drawLine(Color.Black.copy(alpha = 0.35f), pivot + shadow, head + shadow, r * 0.03f, StrokeCap.Round)
    // counterweight
    drawLine(Color(0xFF8D8A84), pivot, back, r * 0.075f, StrokeCap.Round)
    drawLine(gold, pivot, head - dir * (r * 0.06f), r * 0.026f, StrokeCap.Round)
    // pivot base
    drawCircle(Color.Black.copy(alpha = 0.4f), r * 0.1f, pivot + shadow)
    drawCircle(gold, r * 0.085f, pivot)
    drawCircle(Color(0xFF6E5B30), r * 0.05f, pivot, style = Stroke(1.5.dp.toPx()))
    drawCircle(Color(0xFFF7EED0), r * 0.02f, pivot)
    // headshell
    rotate(phi - 90f, head) {
        val w = r * 0.07f; val h = r * 0.15f
        drawRoundRect(gold, Offset(head.x - w / 2, head.y - h * 0.6f), Size(w, h), androidx.compose.ui.geometry.CornerRadius(w * 0.3f))
        drawRoundRect(Color(0xFF3B352A), Offset(head.x - w * 0.3f, head.y), Size(w * 0.6f, h * 0.32f), androidx.compose.ui.geometry.CornerRadius(w * 0.15f))
        drawLine(Color(0xFFF2E6BF), Offset(head.x + w * 0.5f, head.y - h * 0.2f), Offset(head.x + w * 1.1f, head.y - h * 0.32f), 2.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.drawArt(art: Painter, c: Offset, radius: Float) {
    val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, radius)) }
    clipPath(clip) {
        translate(c.x - radius, c.y - radius) { with(art) { draw(Size(radius * 2, radius * 2)) } }
    }
}

@Composable
fun VinylSkin(live: LiveProgress, art: Painter, accent: Color, modifier: Modifier = Modifier, title: String = "") {
    DiscStage(
        live, accent, msPerRev = 1800f, degPerSec = 200f, tonearm = true, timeInside = true,
        modifier = modifier, what = "Vinyl record, $title",
        drawDisc = { c, r, angle ->
            drawCircle(Color.Black.copy(alpha = 0.45f), r * 1.01f, c + Offset(0f, r * 0.035f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF232325), Color(0xFF111113), Color(0xFF0A0A0B)), c, r), r, c)
            // grooves: hairlines with slightly uneven brightness, plus track gaps
            var rr = r * 0.40f
            var i = 0
            val step = 1.7.dp.toPx()
            while (rr < r * 0.975f) {
                val a = 0.025f + 0.035f * ((i * 7919) % 13) / 13f
                drawCircle(Color.White.copy(alpha = a), rr, c, style = Stroke(0.6.dp.toPx()))
                rr += step; i++
            }
            for (gap in floatArrayOf(0.53f, 0.66f, 0.8f)) {
                drawCircle(Color.Black.copy(alpha = 0.7f), r * gap, c, style = Stroke(2.2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.06f), r * gap + 1.4.dp.toPx(), c, style = Stroke(0.7.dp.toPx()))
            }
            drawCircle(Color.White.copy(alpha = 0.08f), r * 0.985f, c, style = Stroke(1.dp.toPx()))
            // light reflections stay put while the record turns under them
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent, 0.07f to Color.Transparent, 0.12f to Color.White.copy(alpha = 0.10f),
                    0.17f to Color.Transparent, 0.57f to Color.Transparent, 0.62f to Color.White.copy(alpha = 0.08f),
                    0.67f to Color.Transparent, 1f to Color.Transparent, center = c,
                ), r * 0.97f, c,
            )
            rotate(angle, c) {
                drawCircle(accent.copy(alpha = 0.9f), r * 0.36f, c)
                drawArt(art, c, r * 0.36f)
                drawCircle(Color.Black.copy(alpha = 0.35f), r * 0.36f, c, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.25f), r * 0.1f, c, style = Stroke(0.8.dp.toPx()))
            }
            drawCircle(Color(0xFF1A1A1A), r * 0.032f, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF0F0F0), Color(0xFF8C8C8C)), c, r * 0.022f), r * 0.022f, c)
        },
    )
}

@Composable
fun CdSkin(live: LiveProgress, art: Painter, accent: Color, modifier: Modifier = Modifier, title: String = "") {
    val rainbow = listOf(
        Color(0xFFE8E8EE), Color(0xFFF4C6D8), Color(0xFFFFFFFF), Color(0xFFBFD9F5),
        Color(0xFFD7F2E2), Color(0xFFE8E8EE), Color(0xFFF7E0C4), Color(0xFFC9C3F2), Color(0xFFE8E8EE),
    )
    DiscStage(
        live, accent, msPerRev = 4000f, degPerSec = 90f, tonearm = false,
        modifier = modifier, what = "CD, $title",
        drawDisc = { c, r, angle ->
            drawCircle(Color.Black.copy(alpha = 0.4f), r * 1.01f, c + Offset(0f, r * 0.03f))
            drawCircle(Brush.sweepGradient(rainbow, c), r, c)
            rotate(angle, c) { drawArt(art, c, r * 0.985f) }
            drawCircle(Brush.sweepGradient(rainbow.map { it.copy(alpha = 0.16f) }, c), r, c)
            drawCircle(Brush.sweepGradient(rainbow, c), r * 0.982f, c, style = Stroke(r * 0.04f))
            drawCircle(Color.White.copy(alpha = 0.55f), r, c, style = Stroke(1.dp.toPx()))
            // clear hub ring and the hole
            drawCircle(Brush.sweepGradient(rainbow.map { it.copy(alpha = 0.92f) }, c), r * 0.25f, c)
            drawCircle(Color.White.copy(alpha = 0.6f), r * 0.25f, c, style = Stroke(1.dp.toPx()))
            drawCircle(Color(0xFFB8B8BE).copy(alpha = 0.5f), r * 0.17f, c, style = Stroke(1.dp.toPx()))
            drawCircle(Color(0xFF66666B), r * 0.13f, c)
            drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.13f, c, style = Stroke(1.5.dp.toPx()))
            drawCircle(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.14f), Color.Transparent),
                    start = c - Offset(r, r), end = c + Offset(r, r),
                ), r, c,
            )
        },
    )
}

/** The plain cover, for when you just want the artwork. */
@Composable
fun CoverSkin(live: LiveProgress, art: Painter, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().aspectRatio(1f).stageSemantics("Album cover", live)) {
        val side = size.minDimension * 0.86f
        val tl = Offset((size.width - side) / 2, (size.height - side) / 2)
        val rr = androidx.compose.ui.geometry.RoundRect(tl.x, tl.y, tl.x + side, tl.y + side, androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
        drawRoundRect(Color.Black.copy(alpha = 0.4f), tl + Offset(0f, 10.dp.toPx()), Size(side, side), androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
        drawRoundRect(accent.copy(alpha = 0.5f), tl, Size(side, side), androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
        clipPath(Path().apply { addRoundRect(rr) }) {
            translate(tl.x, tl.y) { with(art) { draw(Size(side, side)) } }
        }
    }
}
