package com.slowva.player.ui.skins

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.player.PlayerHub
import kotlin.math.abs
import kotlin.math.sqrt

private const val ASPECT = 1.6f
private const val SHUTTLE_MS_PER_WIDTH = 60_000f

/**
 * A compact cassette. The reels turn at different speeds as tape moves from
 * the left pack to the right one; drag sideways to fast-forward or rewind.
 */
@Composable
fun TapeSkin(live: LiveProgress, art: Painter, accent: Color, title: String, modifier: Modifier = Modifier) {
    val rate by PlayerHub.rate.collectAsState()
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val liveRef by rememberUpdatedState(live)
    val f = live.fraction.coerceIn(0f, 1f)
    val hub = 0.055f; val full = 0.15f
    val packL = sqrt(hub * hub + (1 - f) * (full * full - hub * hub))
    val packR = sqrt(hub * hub + f * (full * full - hub * hub))
    val left = rememberPlatter(live.playing, 260f * hub / packL, rate, spinUpSec = 0.25f, spinDownSec = 0.35f)
    val right = rememberPlatter(live.playing, 260f * hub / packR, rate, spinUpSec = 0.25f, spinDownSec = 0.35f)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ASPECT)
                .stageSemantics("Cassette, $title", live)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = liveRef.positionMs
                        var moved = false
                        var lastX = down.position.x
                        var tick = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (!moved && abs(ch.position.x - down.position.x) > viewConfiguration.touchSlop) {
                                moved = true
                                left.held = true; right.held = true
                            }
                            if (moved) {
                                val dx = ch.position.x - lastX
                                left.angle = (left.angle + dx * 2.2f) % 360f
                                right.angle = (right.angle + dx * 2.2f) % 360f
                                tick += abs(dx)
                                if (tick > 24f) { tick = 0f; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                val total = ch.position.x - down.position.x
                                liveRef.preview = (start + total / size.width * SHUTTLE_MS_PER_WIDTH).toLong()
                                    .coerceIn(0L, liveRef.durationMs)
                                ch.consume()
                            }
                            lastX = ch.position.x
                        }
                        left.held = false; right.held = false
                        if (moved) liveRef.preview?.let { liveRef.seekTo(it) }
                        else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); PlayerHub.togglePlay() }
                    }
                }
        ) {
            drawCassette(art, accent, title, measurer, packL, packR, left.angle, right.angle)
        }
        Spacer(Modifier.height(14.dp))
        PillSlider(
            value = live.fraction,
            onChange = { live.preview = (it * live.durationMs).toLong() },
            onDone = { live.seekTo((it * live.durationMs).toLong()) },
            accent = accent,
            label = "Position",
            stateText = "${com.slowva.player.ui.formatMs(live.positionMs.toInt())} of ${com.slowva.player.ui.formatMs(live.durationMs.toInt())}",
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

private fun DrawScope.drawCassette(
    art: Painter, accent: Color, title: String,
    measurer: androidx.compose.ui.text.TextMeasurer,
    packL: Float, packR: Float, angL: Float, angR: Float,
) {
    val w = size.width; val h = size.height
    val corner = CornerRadius(w * 0.035f)
    drawRoundRect(Color.Black.copy(alpha = 0.4f), Offset(0f, h * 0.03f), size, corner)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3A3B42), Color(0xFF23242A))), size = size, cornerRadius = corner)
    drawRoundRect(Color.White.copy(alpha = 0.10f), size = size, cornerRadius = corner, style = Stroke(1.dp.toPx()))
    for (p in listOf(Offset(0.035f, 0.055f), Offset(0.965f, 0.055f), Offset(0.035f, 0.945f), Offset(0.965f, 0.945f))) {
        screw(Offset(p.x * w, p.y * h), w * 0.017f)
    }

    // label: cover art tinted, paper strip with side A and the title
    val label = RoundRect(w * 0.065f, h * 0.08f, w * 0.935f, h * 0.68f, CornerRadius(w * 0.012f))
    clipPath(Path().apply { addRoundRect(label) }) {
        drawRect(accent, Offset(label.left, label.top), Size(label.width, label.height))
        val side = label.width
        translate(label.left, label.top - (side - label.height) / 2) {
            with(art) { draw(Size(side, side), alpha = 0.75f) }
        }
        drawRect(accent.copy(alpha = 0.35f), Offset(label.left, label.top), Size(label.width, label.height))
        drawRect(Color(0xFFEDE8D6), Offset(label.left, label.top), Size(label.width, h * 0.13f))
    }
    val boxS = h * 0.09f
    val boxAt = Offset(label.left + w * 0.012f, label.top + h * 0.02f)
    drawRect(Color(0xFF3B3A36), boxAt, Size(boxS, boxS), style = Stroke(1.2.dp.toPx()))
    val a = measurer.measure("A", TextStyle(color = Color(0xFF2A2925), fontSize = (boxS * 0.7f).toSp()))
    drawText(a, topLeft = boxAt + Offset((boxS - a.size.width) / 2, (boxS - a.size.height) / 2))
    val maxTitle = (label.width - boxS - w * 0.06f).toInt().coerceAtLeast(1)
    val t = measurer.measure(
        title, TextStyle(color = Color(0xFF2A2925), fontSize = (h * 0.055f).toSp(), fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium),
        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxTitle),
    )
    drawText(t, topLeft = Offset(boxAt.x + boxS + w * 0.02f, boxAt.y + (boxS - t.size.height) / 2))
    drawLine(Color(0xFF8C887A), Offset(boxAt.x + boxS + w * 0.02f, label.top + h * 0.12f), Offset(label.right - w * 0.02f, label.top + h * 0.12f), 0.8.dp.toPx())

    // window with the two tape packs
    val win = RoundRect(w * 0.19f, h * 0.27f, w * 0.81f, h * 0.56f, CornerRadius(h * 0.05f))
    drawRoundRect(Color(0xFF16161A), Offset(win.left, win.top), Size(win.width, win.height), CornerRadius(h * 0.05f))
    val cy = (win.top + win.bottom) / 2
    val cl = Offset(w * 0.32f, cy); val cr = Offset(w * 0.68f, cy)
    clipPath(Path().apply { addRoundRect(win) }) {
        drawCircle(Color(0xFF3A2A22), packL * w, cl)
        drawCircle(Color(0xFF3A2A22), packR * w, cr)
        drawCircle(Color.White.copy(alpha = 0.05f), packL * w, cl, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.05f), packR * w, cr, style = Stroke(1.dp.toPx()))
        // tape running across the bottom of the window
        drawLine(Color(0xFF3A2A22), Offset(cl.x, cl.y + packL * w), Offset(cr.x, cr.y + packR * w), 1.5.dp.toPx())
    }
    reel(cl, w * 0.045f, angL)
    reel(cr, w * 0.045f, angR)
    for (i in 0..12) {
        val x = w * 0.43f + i * w * 0.0115f
        val tall = if (i % 4 == 0) h * 0.05f else h * 0.03f
        drawLine(Color.White.copy(alpha = 0.35f), Offset(x, cy - tall / 2), Offset(x, cy + tall / 2), 1.dp.toPx())
    }
    drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(win.left, win.top), Size(win.width, win.height), CornerRadius(h * 0.05f), style = Stroke(1.dp.toPx()))
    drawLine(Color(0xFF9A8A70).copy(alpha = 0.6f), Offset(w * 0.08f, h * 0.64f), Offset(w * 0.25f, h * 0.53f), 1.dp.toPx())
    drawLine(Color(0xFF9A8A70).copy(alpha = 0.6f), Offset(w * 0.92f, h * 0.64f), Offset(w * 0.75f, h * 0.53f), 1.dp.toPx())

    // lower guide section with rollers
    val trap = Path().apply {
        moveTo(w * 0.2f, h * 0.74f); lineTo(w * 0.8f, h * 0.74f)
        lineTo(w * 0.86f, h); lineTo(w * 0.14f, h); close()
    }
    drawPath(trap, Color(0xFF1C1D21))
    drawPath(trap, Color.White.copy(alpha = 0.08f), style = Stroke(1.dp.toPx()))
    drawCircle(Color(0xFF0E0E10), w * 0.006f, Offset(w * 0.5f, h * 0.705f))
    for (x in floatArrayOf(0.25f, 0.75f)) {
        val c = Offset(w * x, h * 0.87f)
        drawCircle(Color(0xFF0D0D0F), w * 0.035f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFCFC8B8), Color(0xFF6F6A60)), c, w * 0.022f), w * 0.022f, c)
    }
    drawRect(Color(0xFF0D0D0F), Offset(w * 0.44f, h * 0.84f), Size(w * 0.12f, h * 0.07f))
    drawRect(Color(0xFF2E2F35), Offset(w * 0.46f, h * 0.855f), Size(w * 0.08f, h * 0.04f))
}

private fun DrawScope.screw(c: Offset, r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB8B8BE), Color(0xFF5A5A60)), c, r), r, c)
    drawLine(Color(0xFF333338), c - Offset(r * 0.6f, 0f), c + Offset(r * 0.6f, 0f), 1.2.dp.toPx(), StrokeCap.Round)
    drawLine(Color(0xFF333338), c - Offset(0f, r * 0.6f), c + Offset(0f, r * 0.6f), 1.2.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.reel(c: Offset, r: Float, angle: Float) {
    drawCircle(Color(0xFFE9E6DE), r, c)
    drawCircle(Color.Black.copy(alpha = 0.25f), r, c, style = Stroke(1.dp.toPx()))
    rotate(angle, c) {
        for (k in 0 until 6) {
            rotate(k * 60f, c) {
                drawRect(Color(0xFF2B2B2E), Offset(c.x - r * 0.12f, c.y - r * 0.62f), Size(r * 0.24f, r * 0.3f))
            }
        }
        drawCircle(Color(0xFF2B2B2E), r * 0.3f, c)
        drawCircle(Color(0xFFE9E6DE), r * 0.12f, c)
    }
}
