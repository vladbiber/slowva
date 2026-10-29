package com.slowva.player.ui.skins.radio

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slowva.player.ui.skins.TAU
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One loop of every radio animation; all rates are whole cycles per loop, so it wraps without a jump. */
internal const val LOOP_MS = 12_000

/** What a radio face draws. Built inside the draw cache, so it lives as long as the cached layers. */
internal class RadioScene(
    val station: String,
    title: String?,
    art: Painter?,
    val accent: Color,
    val playing: Boolean,
    val pressed: Boolean,
    val measurer: TextMeasurer,
    /** loop phase 0..1, read only while drawing so a frame redraws without recomposing */
    val t: () -> Float,
) {
    val title = title?.takeIf { it.isNotBlank() } ?: if (playing) "on air" else "standby"
    // rememberArtPainter hands out a transparent ColorPainter until the image arrives
    val art = art?.takeUnless { it is ColorPainter && it.color.alpha == 0f }
    /** a stable dial spot (and preset) per station, so the needle doesn't wander between visits */
    val tuning = (station.hashCode() and 0x7fffffff) % 1000 / 1000f
    val initial = station.trim().take(1).uppercase()
    val color = stationColor(station)
}

/**
 * The look of the song skins (TP-7, tape, vinyl): flat product drawing, one soft drop shadow,
 * a thin lit edge, light-grey keys, a small black screen with plain white text, an orange status dot.
 */
internal object Kit {
    val silver = listOf(Color(0xFFD9D9DC), Color(0xFFC3C3C7), Color(0xFFB4B4B9))
    val graphite = listOf(Color(0xFF34343A), Color(0xFF26262B), Color(0xFF1C1C20))
    val key = Color(0xFFCACACE)
    val keyDown = Color(0xFFB4B4B9)
    val glyph = Color(0xFF3A3A3E)
    val printOnLight = Color(0xFF55555A)
    val printOnDark = Color(0xFF9A9AA0)
    val screen = Color(0xFF0B0B0C)
    val screenSub = Color(0xFFBDBDBD)
    val orange = Color(0xFFFF6B2C)
    val idle = Color(0xFF8A8A8E)
}

/** Model name printed on the body, like the "TP-7" on the song skin. */
internal fun Density.label(px: Float, color: Color) =
    TextStyle(color = color, fontSize = px.toSp(), fontWeight = FontWeight.Medium)

internal fun Density.mono(px: Float, color: Color = Color.White) =
    TextStyle(color = color, fontSize = px.toSp(), fontFamily = FontFamily.Monospace)

/** Marquee offset in px for loop phase [t]: whole laps per loop so the wrap is seamless; 0 when the text fits. */
internal fun marqueeShift(textW: Float, boxW: Float, period: Float, t: Float, loopPx: Float): Float {
    if (textW <= boxW || period <= 0f) return 0f
    val laps = (loopPx / period).roundToInt().coerceAtLeast(1)
    return (t * laps % 1f) * period
}

/** A steady fake programme level 0..1 for channel [i]; there is no live spectrum for radio streams. */
internal fun level(t: Float, i: Int): Float {
    val a = sin(TAU * (t * (19 + 7 * i % 11) + 0.13f * i))
    val b = sin(TAU * (t * (31 + 5 * i % 13) + 0.29f * i))
    return (0.55f + 0.28f * a + 0.17f * b).coerceIn(0f, 1f)
}

/** The device body: drop shadow, flat gradient, thin lit edge (same recipe as the TP-7). */
internal fun DrawScope.body(r: Rect, corner: Float, light: Boolean) {
    val cr = CornerRadius(corner)
    drawRoundRect(Color.Black.copy(alpha = 0.45f), r.topLeft + Offset(r.width * 0.015f, r.height * 0.025f), r.size, cr)
    drawRoundRect(Brush.linearGradient(if (light) Kit.silver else Kit.graphite, r.topLeft, r.bottomRight), r.topLeft, r.size, cr)
    drawRoundRect(Color.White.copy(alpha = if (light) 0.6f else 0.12f), r.topLeft + Offset(1f, 1f), Size(r.width - 2, r.height - 2), cr, style = Stroke(1.dp.toPx()))
    drawRoundRect(Color.Black.copy(alpha = 0.12f), r.topLeft, r.size, cr, style = Stroke(1.dp.toPx()))
}

/**
 * The little black screen: station on top in white, the live title under it in grey lowercase.
 * The title scrolls when it doesn't fit; the dot in the corner is orange while on air.
 */
internal class Screen(measurer: TextMeasurer, density: Density, top: String, sub: String, private val r: Rect) {
    private val pad = r.height * 0.14f
    private val dotR = r.height * 0.07f
    private val big = with(density) {
        measurer.measure(
            top, mono(r.height * 0.3f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (r.width - pad * 3 - dotR * 2).toInt().coerceAtLeast(1)),
        )
    }
    private val small = with(density) { measurer.measure(sub.lowercase(), mono(r.height * 0.21f, Kit.screenSub), softWrap = false, maxLines = 1) }
    private val lane = Rect(r.left + pad, r.bottom - pad - small.size.height, r.right - pad, r.bottom - pad)
    private val period = small.size.width + r.height * 1.2f
    private val scrolls = small.size.width > lane.width

    fun draw(d: DrawScope, playing: Boolean, t: Float) = with(d) {
        drawRoundRect(Kit.screen, r.topLeft, r.size, CornerRadius(r.height * 0.1f))
        drawText(big, topLeft = Offset(r.left + pad, r.top + pad * 0.8f))
        drawCircle(if (playing) Kit.orange else Kit.idle, dotR, Offset(r.right - pad - dotR, r.top + pad + dotR))
        val shift = if (playing) marqueeShift(small.size.width.toFloat(), lane.width, period, t, r.height * 12f) else 0f
        clipRect(lane.left, lane.top, lane.right, lane.bottom) {
            drawText(small, topLeft = Offset(lane.left - shift, lane.top))
            if (scrolls) drawText(small, topLeft = Offset(lane.left - shift + period, lane.top))
        }
    }
}

/** Speaker as a flat perforated plate (a Braun-style hole grid); it swells a hair with the level. */
internal class Grille(private val c: Offset, private val r: Float, private val light: Boolean) {
    private val holes = Path().apply {
        fillType = PathFillType.NonZero
        val step = r * 0.16f
        var y = c.y - r
        while (y <= c.y + r) {
            var x = c.x - r
            while (x <= c.x + r) {
                if ((Offset(x, y) - c).getDistance() < r * 0.84f) addOval(Rect(Offset(x, y), step * 0.26f))
                x += step
            }
            y += step
        }
    }
    private val plate = if (light) Color(0xFFC6C6CA) else Color(0xFF2E2E33)
    private val hole = if (light) Color(0xFF6F6F74) else Color(0xFF0E0E10)

    fun draw(d: DrawScope, level: Float) = with(d) {
        drawCircle(Color.Black.copy(alpha = 0.12f), r * 1.02f, c + Offset(0f, r * 0.03f))
        drawCircle(plate, r, c)
        drawCircle(Color.White.copy(alpha = if (light) 0.5f else 0.1f), r, c, style = Stroke(1.dp.toPx()))
        val k = 1f + 0.025f * level
        scale(k, c) { drawPath(holes, hole) }
    }
}

/** A flat light-grey key (the TP-7's keys); returns the face so callers can print on it. */
internal fun DrawScope.flatKey(r: Rect, pressed: Boolean, round: Float = 0.12f): Rect {
    val cr = CornerRadius(min(r.width, r.height) * round)
    drawRoundRect(if (pressed) Kit.keyDown else Kit.key, r.topLeft, r.size, cr)
    drawRoundRect(Color.Black.copy(alpha = 0.18f), r.topLeft, r.size, cr, style = Stroke(1.dp.toPx()))
    drawLine(Color.White.copy(alpha = if (pressed) 0.2f else 0.7f), Offset(r.left + cr.x, r.top + 1f), Offset(r.right - cr.x, r.top + 1f), 1.dp.toPx())
    return r
}

/** A round flat key, like the TP-7's + and − buttons. */
internal fun DrawScope.roundKey(c: Offset, r: Float, pressed: Boolean) {
    drawCircle(Color.Black.copy(alpha = 0.15f), r, c + Offset(0f, r * 0.06f))
    drawCircle(if (pressed) Kit.keyDown else Color(0xFFCDCDD1), r, c)
    drawCircle(Color.Black.copy(alpha = 0.2f), r, c, style = Stroke(1.dp.toPx()))
}

/** A plain dial: flat light disc with a pointer in the accent. */
internal fun DrawScope.flatKnob(c: Offset, r: Float, accent: Color, angle: Float) {
    drawCircle(Color.Black.copy(alpha = 0.18f), r * 1.02f, c + Offset(0f, r * 0.06f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFD4D4D7), Color(0xFFC6C6CA), Color(0xFFB9B9BE)), c, r), r, c)
    drawCircle(Color.White.copy(alpha = 0.5f), r, c, style = Stroke(1.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.08f), r * 0.97f, c, style = Stroke(1.dp.toPx()))
    rotate(angle, c) { drawLine(accent, Offset(c.x, c.y - r * 0.8f), Offset(c.x, c.y - r * 0.45f), r * 0.08f, StrokeCap.Round) }
}

/** Pause bars while playing (what a tap does), a triangle otherwise. */
internal fun DrawScope.playGlyph(c: Offset, s: Float, playing: Boolean, color: Color = Kit.glyph) {
    if (playing) {
        drawRect(color, c - Offset(s * 0.9f, s), Size(s * 0.6f, s * 2))
        drawRect(color, c + Offset(s * 0.3f, -s), Size(s * 0.6f, s * 2))
    } else drawPath(Path().apply { moveTo(c.x - s * 0.7f, c.y - s); lineTo(c.x + s, c.y); lineTo(c.x - s * 0.7f, c.y + s); close() }, color)
}

internal fun DrawScope.centered(t: TextLayoutResult, c: Offset) =
    drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f))

/**
 * Station art filling [r] (cropped to fill, like the cover skin). With no art it's the station's
 * color ([RadioScene.color]) with its initial, the way stations look everywhere else.
 */
internal fun DrawScope.art(r: Rect, art: Painter?, initial: TextLayoutResult?, fill: Color, round: Boolean = false, initialAt: Offset = r.center) {
    val shape = Path().apply { if (round) addOval(r) else addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(r.width * 0.04f))) }
    clipPath(shape) {
        drawRect(fill, r.topLeft, r.size)
        if (art != null) {
            val i = art.intrinsicSize
            val k = if (i.isSpecified && i.width > 0f && i.height > 0f) maxOf(r.width / i.width, r.height / i.height) else 0f
            val sz = if (k > 0f) Size(i.width * k, i.height * k) else r.size
            translate(r.left + (r.width - sz.width) / 2, r.top + (r.height - sz.height) / 2) { with(art) { draw(sz) } }
        } else if (initial != null) centered(initial, initialAt)
    }
}
