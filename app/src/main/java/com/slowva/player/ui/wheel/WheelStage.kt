package com.slowva.player.ui.wheel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.ui.skins.angleDelta
import com.slowva.player.ui.skins.drawTonearm
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

private const val LABEL = 0.34f
private const val PIN = 0.955f

/** The wheel drawn as a record with the tonearm as pointer; [glow] goes 0..1 after landing. */
@Composable
fun WheelStage(
    wheel: Wheel,
    motion: WheelMotion,
    glow: () -> Float,
    onFling: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val note = rememberVectorPainter(Icons.Filled.MusicNote)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fling by rememberUpdatedState(onFling)
    val n = wheel.slices.size
    val fontSize = if (n <= 8) 14.sp else 12.sp

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = constraints.maxWidth.toFloat()
        val d = w * 0.80f
        val top = w * 0.11f
        val density = LocalDensity.current
        val dDp = with(density) { d.toDp() }
        Box(Modifier.fillMaxWidth().height(with(density) { (top + d + w * 0.03f).toDp() })) {
            // the disc: drawn once per wheel, spun by the layer
            Box(
                Modifier
                    .offset(x = with(density) { ((w - d) / 2).toDp() }, y = with(density) { top.toDp() })
                    .size(dDp)
                    .semantics { contentDescription = "Wheel with $n slices" }
                    .graphicsLayer { rotationZ = motion.angle }
                    .pointerInput(wheel) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (motion.spinning) return@awaitEachGesture
                            val c = Offset(size.width / 2f, size.height / 2f)
                            // the layer rotates the touch points too, so track in parent space
                            var last = rotateAround(down.position, c, motion.angle)
                            var lastT = down.uptimeMillis
                            var v = 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val pos = rotateAround(ch.position, c, motion.angle)
                                val da = angleDelta(c, last, pos)
                                val dt = ((ch.uptimeMillis - lastT) / 1000f).coerceAtLeast(0.001f)
                                motion.angle += da
                                v = 0.6f * v + 0.4f * (da / dt)
                                motion.velocity = v
                                motion.stepNeedle(dt)
                                if (motion.crossed(System.nanoTime())) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                last = pos; lastT = ch.uptimeMillis
                                ch.consume()
                            }
                            if (abs(v) > 150f) fling(v) else scope.launch { motion.relax() }
                        }
                    }
                    .drawWithCache {
                        val r = size.minDimension / 2
                        val c = Offset(size.width / 2, size.height / 2)
                        val s = 360f / n
                        val style = TextStyle(color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
                        val textW = (r * (PIN - 0.06f - LABEL - 0.05f)).toInt().coerceAtLeast(1)
                        val layouts = wheel.slices.map {
                            measurer.measure(
                                it.title, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
                                constraints = Constraints(maxWidth = textW),
                            )
                        }
                        val tints = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
                        val step = 1.7.dp.toPx()
                        onDrawBehind {
                            val g = glow()
                            drawCircle(Color.Black.copy(alpha = 0.45f), r * 1.01f, c + Offset(0f, r * 0.035f))
                            drawCircle(Brush.radialGradient(listOf(Color(0xFF232325), Color(0xFF111113), Color(0xFF0A0A0B)), c, r), r, c)
                            for (i in 0 until n) {
                                // three tints, but never the same one on both sides of the wrap
                                val k = if (i == n - 1 && n % 3 == 1 && n > 1) 1 else i % 3
                                val win = i == wheel.winner
                                val base = tints[k].copy(alpha = 0.24f * (1f - 0.65f * g))
                                val col = if (win) lerp(tints[k].copy(alpha = 0.24f), scheme.primary.copy(alpha = 0.85f), g) else base
                                drawArc(col, -90f + i * s, s, useCenter = true, topLeft = c - Offset(r, r), size = Size(r * 2, r * 2))
                            }
                            // grooves over the color, like a picture disc
                            var rr = r * 0.40f; var j = 0
                            while (rr < r * 0.975f) {
                                drawCircle(Color.White.copy(alpha = 0.02f + 0.03f * ((j * 7919) % 13) / 13f), rr, c, style = Stroke(0.6.dp.toPx()))
                                rr += step; j++
                            }
                            drawCircle(Color.White.copy(alpha = 0.08f), r * 0.985f, c, style = Stroke(1.dp.toPx()))
                            if (n > 1) for (i in 0 until n) {
                                val a = Math.toRadians((-90f + i * s).toDouble())
                                val u = Offset(cos(a).toFloat(), sin(a).toFloat())
                                drawLine(scheme.primary.copy(alpha = 0.45f * (1f - 0.5f * g)), c + u * (r * LABEL), c + u * (r * 0.985f), 1.4.dp.toPx())
                                drawCircle(Color.Black.copy(alpha = 0.5f), r * 0.024f, c + u * (r * PIN) + Offset(0f, 1.5.dp.toPx()))
                                drawCircle(Brush.radialGradient(listOf(Color(0xFFF7EED0), Color(0xFFB89A55)), c + u * (r * PIN), r * 0.022f), r * 0.022f, c + u * (r * PIN))
                            }
                            for (i in 0 until n) {
                                val win = i == wheel.winner
                                val alpha = if (win) 1f - g else 1f - 0.6f * g
                                rotate(-90f + (i + 0.5f) * s, c) {
                                    val t = layouts[i]
                                    drawText(t, topLeft = Offset(c.x + r * (LABEL + 0.06f), c.y - t.size.height / 2f), alpha = alpha)
                                }
                            }
                            // center label with the logo
                            val lr = r * LABEL
                            drawCircle(Color.Black.copy(alpha = 0.4f), lr * 1.04f, c)
                            drawCircle(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.primary), c - Offset(lr, lr), c + Offset(lr, lr)), lr, c)
                            drawCircle(Color.White.copy(alpha = 0.18f), lr * 0.86f, c, style = Stroke(0.8.dp.toPx()))
                            drawCircle(Color.White.copy(alpha = 0.12f), lr * 0.62f, c, style = Stroke(0.8.dp.toPx()))
                            val ns = lr * 0.7f
                            translate(c.x - ns / 2, c.y - ns / 2 - lr * 0.08f) {
                                with(note) { draw(Size(ns, ns), alpha = 0.9f * (1f - g), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(scheme.onPrimary)) }
                            }
                            drawCircle(Color(0xFF1A1A1A), r * 0.032f, c + Offset(0f, lr * 0.55f))
                        }
                    }
            )
            // fixed light reflection, like the vinyl skin
            Canvas(Modifier.offset(x = with(density) { ((w - d) / 2).toDp() }, y = with(density) { top.toDp() }).size(dDp)) {
                val c = center; val r = size.minDimension / 2
                drawCircle(
                    Brush.sweepGradient(
                        0f to Color.Transparent, 0.07f to Color.Transparent, 0.12f to Color.White.copy(alpha = 0.08f),
                        0.17f to Color.Transparent, 0.57f to Color.Transparent, 0.62f to Color.White.copy(alpha = 0.06f),
                        0.67f to Color.Transparent, 1f to Color.Transparent, center = c,
                    ), r * 0.97f, c,
                )
            }
            WinnerTitle(wheel, glow, dDp * LABEL * 2, Modifier.offset(y = with(density) { (top + d / 2).toDp() } - dDp * LABEL).align(Alignment.TopCenter))
            // the tonearm pointer, flicked by the pins
            Canvas(Modifier.fillMaxSize()) {
                val r = d / 2
                val c = Offset(w / 2, top + r)
                val tip0 = Offset(c.x, c.y - r * PIN)
                val pivot = Offset(c.x + r * 0.92f, c.y - r * 1.2f)
                val len = (tip0 - pivot).getDistance()
                val phi0 = Math.atan2((tip0.y - pivot.y).toDouble(), (tip0.x - pivot.x).toDouble())
                val phi = phi0 - motion.needle * r / len
                val tip = Offset(pivot.x + len * cos(phi).toFloat(), pivot.y + len * sin(phi).toFloat())
                drawTonearm(pivot, tip, r)
                drawLine(Color(0xFFF2E6BF), tip, tip + (tip - pivot) / len * (r * 0.035f), 1.6.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/** The winner's title growing into the center label. */
@Composable
private fun WinnerTitle(wheel: Wheel, glow: () -> Float, size: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val item = wheel.slices[wheel.winner]
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val g = glow()
                alpha = g
                scaleX = 0.45f + 0.55f * g
                scaleY = 0.45f + 0.55f * g
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            item.title,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = size * 0.14f),
        )
    }
}

private fun rotateAround(p: Offset, c: Offset, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    val x = p.x - c.x; val y = p.y - c.y
    return Offset(c.x + (x * cos(a) - y * sin(a)).toFloat(), c.y + (x * sin(a) + y * cos(a)).toFloat())
}
