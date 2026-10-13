package com.slowva.player.ui.skins

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.slowva.player.player.PlayerHub
import com.slowva.player.ui.formatMs
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val H_RATIO = 1.3f
private const val JOG_MS_PER_REV = 8000f
private val ORANGE = Color(0xFFFF6B2C)

private enum class Part { NONE, JOG, CENTER, FADER, KNOB, PREV, PLAY, NEXT, PLUS, MINUS }

/** Layout of the device in fractions of its width (x) and height (y). */
private object L {
    val jog = Offset(0.5f, 0.43f); const val jogR = 0.37f
    val oled = Rect(0.6f, 0.035f, 0.9f, 0.115f)
    val fader = Rect(-0.035f, 0.17f, 0.02f, 0.45f)
    val knob = Rect(0.8f, 0.735f, 0.9f, 0.895f)
    val plus = Offset(0.9f, 0.63f); val minus = Offset(0.8f, 0.665f); const val pmR = 0.035f
    val buttons = Rect(0.06f, 0.73f, 0.68f, 0.88f)
}

/** TP-7 style device: jog wheel seeks, lever is slow, knurled knob is reverb. */
@Composable
fun Tp7Skin(live: LiveProgress, art: Painter, accent: Color, title: String, modifier: Modifier = Modifier) {
    val rate by PlayerHub.rate.collectAsState()
    val reverb by PlayerHub.reverb.collectAsState()
    val state by PlayerHub.playback.collectAsState()
    val platter = rememberPlatter(live.playing, 50f, rate, spinUpSec = 0.3f, spinDownSec = 0.6f)
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val liveRef by rememberUpdatedState(live)
    val rateRef by rememberUpdatedState(rate)
    val verbRef by rememberUpdatedState(reverb)
    var pressed by remember { mutableStateOf(Part.NONE) }
    var hint by remember { mutableStateOf("") }
    var hintAt by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    var volume by remember { mutableIntStateOf(state?.volumePercent ?: 70) }
    val vol by rememberUpdatedState(volume)

    var expired by remember { mutableIntStateOf(0) }
    fun show(text: String) { hint = text; hintAt = SystemClock.elapsedRealtime() }
    LaunchedEffect(hintAt) { delay(1450); expired++ }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f / H_RATIO)
            .stageSemantics(
                "TP-7 player, $title", live,
                listOf(
                    CustomAccessibilityAction("Previous track") { PlayerHub.previous(); true },
                    CustomAccessibilityAction("Next track") { PlayerHub.next(); true },
                    CustomAccessibilityAction("Slower") { PlayerHub.setRate((rateRef - 0.05f).coerceIn(0.5f, 1.25f)); true },
                    CustomAccessibilityAction("Faster") { PlayerHub.setRate((rateRef + 0.05f).coerceIn(0.5f, 1.25f)); true },
                    CustomAccessibilityAction("More reverb") { PlayerHub.setReverb((verbRef + 0.1f).coerceIn(0f, 1f)); true },
                    CustomAccessibilityAction("Less reverb") { PlayerHub.setReverb((verbRef - 0.1f).coerceIn(0f, 1f)); true },
                ),
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val p = Offset(down.position.x / w, down.position.y / h)
                    val jc = Offset(L.jog.x * w, L.jog.y * h)
                    val jr = L.jogR * w
                    val dj = (down.position - jc).getDistance()
                    fun inside(r: Rect, pad: Float = 0.03f) =
                        p.x in (r.left - pad)..(r.right + pad) && p.y in (r.top - pad)..(r.bottom + pad)
                    fun near(c: Offset, r: Float) = (Offset(p.x * w, p.y * h) - Offset(c.x * w, c.y * h)).getDistance() < r * w * 1.6f
                    val bw = L.buttons.width / 3
                    var part = when {
                        dj < jr * 0.3f -> Part.CENTER
                        dj < jr -> Part.JOG
                        inside(L.fader, 0.05f) -> Part.FADER
                        inside(L.knob) -> Part.KNOB
                        near(L.plus, L.pmR) -> Part.PLUS
                        near(L.minus, L.pmR) -> Part.MINUS
                        inside(L.buttons, 0f) -> when (((p.x - L.buttons.left) / bw).toInt()) {
                            0 -> Part.PREV; 1 -> Part.PLAY; else -> Part.NEXT
                        }
                        else -> Part.NONE
                    }
                    if (part == Part.NONE) return@awaitEachGesture
                    pressed = part
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val start = liveRef.positionMs
                    val startRate = rateRef
                    val startVerb = verbRef
                    var last = down.position
                    var turned = 0f
                    var tick = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val pos = ch.position
                        if (part == Part.CENTER && (pos - down.position).getDistance() > viewConfiguration.touchSlop) part = Part.JOG
                        when (part) {
                            Part.JOG -> {
                                platter.held = true
                                val d = angleDelta(jc, last, pos)
                                platter.angle = (platter.angle + d) % 360f
                                turned += d; tick += abs(d)
                                if (tick > 12f) { tick = 0f; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                liveRef.preview = (start + turned / 360f * JOG_MS_PER_REV).toLong().coerceIn(0L, liveRef.durationMs)
                                show("JOG " + formatMs(liveRef.positionMs.toInt()))
                            }
                            Part.FADER -> {
                                val dy = (pos.y - down.position.y) / (L.fader.height * h)
                                val r = ((startRate - dy * 0.75f) * 100).roundToInt() / 100f
                                val snapped = if (abs(r - 1f) < 0.02f) 1f else r
                                PlayerHub.setRate(snapped.coerceIn(0.5f, 1.25f))
                                show("SLOW %.2fx".format(snapped.coerceIn(0.5f, 1.25f)))
                            }
                            Part.KNOB -> {
                                val dy = (down.position.y - pos.y) / (h * 0.25f)
                                val v = (startVerb + dy).coerceIn(0f, 1f)
                                PlayerHub.setReverb((v * 100).roundToInt() / 100f)
                                show("VERB ${(v * 100).roundToInt()}%")
                            }
                            else -> {}
                        }
                        last = pos
                        ch.consume()
                    }
                    platter.held = false
                    when (part) {
                        Part.JOG -> liveRef.preview?.let { liveRef.seekTo(it) }
                        Part.CENTER, Part.PLAY -> { PlayerHub.togglePlay(); show(if (liveRef.playing) "PAUSE" else "PLAY") }
                        Part.PREV -> { PlayerHub.previous(); show("PREV") }
                        Part.NEXT -> { PlayerHub.next(); show("NEXT") }
                        Part.PLUS, Part.MINUS -> {
                            volume = (vol + if (part == Part.PLUS) 10 else -10).coerceIn(0, 100)
                            PlayerHub.setVolume(volume)
                            show("VOL $volume")
                        }
                        else -> {}
                    }
                    pressed = Part.NONE
                }
            }
    ) {
        expired
        val showingHint = SystemClock.elapsedRealtime() - hintAt < 1400
        drawDevice(
            art, measurer, platter.angle, pressed, live.playing,
            screenTop = if (showingHint) hint else formatMs(live.positionMs.toInt()),
            screenSub = title, rate = rate, reverb = reverb, accent = accent,
        )
    }
}

private fun DrawScope.drawDevice(
    art: Painter, measurer: TextMeasurer, angle: Float, pressed: Part, playing: Boolean,
    screenTop: String, screenSub: String, rate: Float, reverb: Float, accent: Color,
) {
    val w = size.width; val h = size.height
    fun at(x: Float, y: Float) = Offset(x * w, y * h)
    val body = Size(w, h * 0.96f)
    val corner = CornerRadius(w * 0.07f)

    // headphone cable and plug, behind the body
    drawLine(Color(0xFFEDEDED), at(0.9f, 0.0f), at(0.93f, -0.06f), w * 0.012f, StrokeCap.Round)
    drawRoundRect(Color(0xFFF4F4F4), at(0.875f, -0.005f), Size(w * 0.05f, h * 0.035f), CornerRadius(w * 0.01f))

    drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(w * 0.02f, h * 0.025f), body, corner)
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFD9D9DC), Color(0xFFC3C3C7), Color(0xFFB4B4B9)), Offset.Zero, Offset(w, h)), size = body, cornerRadius = corner)
    drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(1f, 1f), Size(body.width - 2, body.height - 2), corner, style = Stroke(1.dp.toPx()))
    drawRoundRect(Color.Black.copy(alpha = 0.12f), size = body, cornerRadius = corner, style = Stroke(1.dp.toPx()))

    // side buttons on the right edge
    for (y in floatArrayOf(0.2f, 0.27f, 0.34f)) drawRoundRect(Color(0xFFA9A9AE), at(0.985f, y), Size(w * 0.025f, h * 0.05f), CornerRadius(w * 0.01f))

    val label = measurer.measure("TP-7", TextStyle(color = Color(0xFF55555A), fontSize = (w * 0.05f).toSp(), fontWeight = FontWeight.Medium))
    drawText(label, topLeft = at(0.08f, 0.04f))

    // screen
    val o = Rect(L.oled.left * w, L.oled.top * h, L.oled.right * w, L.oled.bottom * h)
    drawRoundRect(Color(0xFF0B0B0C), o.topLeft, o.size, CornerRadius(w * 0.012f))
    val big = measurer.measure(
        screenTop, TextStyle(color = Color.White, fontSize = (o.height * 0.42f).toSp(), fontFamily = FontFamily.Monospace),
        maxLines = 1, overflow = TextOverflow.Clip, constraints = Constraints(maxWidth = (o.width * 0.92f).toInt()),
    )
    drawText(big, topLeft = Offset(o.left + o.width * 0.05f, o.top + o.height * 0.06f))
    val small = measurer.measure(
        screenSub.lowercase(), TextStyle(color = Color(0xFFBDBDBD), fontSize = (o.height * 0.22f).toSp(), fontFamily = FontFamily.Monospace),
        maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (o.width * 0.9f).toInt()),
    )
    drawText(small, topLeft = Offset(o.left + o.width * 0.05f, o.bottom - small.size.height - o.height * 0.08f))
    drawCircle(if (playing) ORANGE else Color(0xFF8A8A8E), w * 0.011f, at(0.94f, 0.14f))

    // jog platter
    val jc = at(L.jog.x, L.jog.y); val jr = L.jogR * w
    drawCircle(Color.Black.copy(alpha = 0.18f), jr * 1.03f, jc + Offset(0f, jr * 0.025f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFD4D4D7), Color(0xFFC6C6CA), Color(0xFFB9B9BE)), jc, jr), jr, jc)
    drawCircle(Color.White.copy(alpha = 0.5f), jr, jc, style = Stroke(1.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.08f), jr * 0.97f, jc, style = Stroke(1.dp.toPx()))
    rotate(angle, jc) {
        drawLine(Color.Black.copy(alpha = 0.18f), jc - Offset(0f, jr * 0.98f), jc + Offset(0f, jr * 0.98f), 1.dp.toPx())
        drawLine(Color.Black.copy(alpha = 0.18f), jc - Offset(jr * 0.98f, 0f), jc + Offset(jr * 0.98f, 0f), 1.dp.toPx())
        drawCircle(ORANGE, w * 0.008f, jc - Offset(0f, jr * 0.88f))
        val ar = jr * 0.27f
        drawCircle(Color(0xFF1A1A1C), ar * 1.06f, jc)
        clipPath(Path().apply { addOval(Rect(jc, ar)) }) {
            translate(jc.x - ar, jc.y - ar) { with(art) { draw(Size(ar * 2, ar * 2)) } }
        }
    }
    drawCircle(Color.White.copy(alpha = 0.25f), jr * 0.27f, jc, style = Stroke(1.dp.toPx()))

    // slow fader lever on the left edge
    val fr = Rect(L.fader.left * w, L.fader.top * h, L.fader.right * w, L.fader.bottom * h)
    drawRoundRect(Color(0xFFA6A6AB), fr.topLeft, fr.size, CornerRadius(fr.width / 2))
    drawRoundRect(Color.Black.copy(alpha = 0.15f), fr.topLeft, fr.size, CornerRadius(fr.width / 2), style = Stroke(1.dp.toPx()))
    val rf = ((rate - 0.5f) / 0.75f).coerceIn(0f, 1f)
    val ky = fr.bottom - rf * fr.height
    drawRoundRect(Color(0xFFE8E8EA), Offset(fr.left - w * 0.012f, ky - h * 0.018f), Size(fr.width + w * 0.024f, h * 0.036f), CornerRadius(w * 0.01f))
    drawLine(Color(0xFF6B6B70), Offset(fr.left, ky), Offset(fr.right, ky), 1.dp.toPx())
    val oneY = fr.bottom - (0.5f / 0.75f) * fr.height
    drawLine(ORANGE, Offset(fr.right + w * 0.01f, oneY), Offset(fr.right + w * 0.03f, oneY), 1.5.dp.toPx())

    // speaker
    val sp = at(0.11f, 0.665f)
    drawCircle(Color(0xFFB0B0B5), w * 0.035f, sp)
    for (i in 0 until 7) rotate(i * 360f / 7, sp) { drawCircle(Color(0xFF6F6F74), w * 0.004f, sp + Offset(0f, -w * 0.02f)) }

    // volume + / −
    for ((c, sign) in listOf(L.plus to "+", L.minus to "−")) {
        val cc = at(c.x, c.y); val down = (sign == "+" && pressed == Part.PLUS) || (sign == "−" && pressed == Part.MINUS)
        drawCircle(if (down) Color(0xFFB2B2B7) else Color(0xFFCDCDD1), L.pmR * w, cc)
        drawCircle(Color.Black.copy(alpha = 0.2f), L.pmR * w, cc, style = Stroke(1.dp.toPx()))
        val t = measurer.measure(sign, TextStyle(color = Color(0xFF4A4A4F), fontSize = (w * 0.04f).toSp()))
        drawText(t, topLeft = cc - Offset(t.size.width / 2f, t.size.height / 2f))
    }

    // the three keys
    val b = Rect(L.buttons.left * w, L.buttons.top * h, L.buttons.right * w, L.buttons.bottom * h)
    val bw = b.width / 3
    val parts = listOf(Part.PREV, Part.PLAY, Part.NEXT)
    for (i in 0..2) {
        val tl = Offset(b.left + i * bw, b.top)
        val down = pressed == parts[i]
        drawRect(if (down) Color(0xFFB4B4B9) else Color(0xFFCACACE), tl, Size(bw, b.height))
        drawRect(Color.Black.copy(alpha = 0.18f), tl, Size(bw, b.height), style = Stroke(1.dp.toPx()))
        drawLine(Color.White.copy(alpha = if (down) 0.2f else 0.7f), tl + Offset(1f, 1f), tl + Offset(bw - 1f, 1f), 1.dp.toPx())
        val cc = tl + Offset(bw / 2, b.height / 2); val s = b.height * 0.13f
        when (parts[i]) {
            Part.PREV -> drawCircle(ORANGE, s * 0.9f, cc)
            Part.PLAY -> if (playing) {
                drawRect(Color(0xFF3A3A3E), cc - Offset(s * 0.9f, s), Size(s * 0.6f, s * 2))
                drawRect(Color(0xFF3A3A3E), cc + Offset(s * 0.3f, -s), Size(s * 0.6f, s * 2))
            } else drawPath(Path().apply { moveTo(cc.x - s * 0.7f, cc.y - s); lineTo(cc.x + s, cc.y); lineTo(cc.x - s * 0.7f, cc.y + s); close() }, Color(0xFF3A3A3E))
            else -> {
                drawPath(Path().apply { moveTo(cc.x - s, cc.y - s); lineTo(cc.x + s * 0.5f, cc.y); lineTo(cc.x - s, cc.y + s); close() }, Color(0xFF3A3A3E))
                drawRect(Color(0xFF3A3A3E), Offset(cc.x + s * 0.55f, cc.y - s), Size(s * 0.35f, s * 2))
            }
        }
    }

    // grille lines and the knurled reverb knob
    for (i in 0..2) drawLine(Color.Black.copy(alpha = 0.25f), at(0.72f + i * 0.02f, 0.745f), at(0.72f + i * 0.02f, 0.885f), 1.dp.toPx())
    val k = Rect(L.knob.left * w, L.knob.top * h, L.knob.right * w, L.knob.bottom * h)
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF6A6A70), Color(0xFFBFBFC4), Color(0xFF5A5A60)), k.left, k.right), k.topLeft, k.size, CornerRadius(w * 0.012f))
    val rows = 12
    val shift = (reverb * rows * 2) % 2
    for (i in 0 until rows) {
        val y = k.top + (i + shift / 2) * k.height / rows
        if (y < k.bottom) drawLine(Color.Black.copy(alpha = 0.35f), Offset(k.left + 1, y), Offset(k.right - 1, y + k.height / rows * 0.6f), 1.dp.toPx())
    }
    val vy = k.bottom - reverb.coerceIn(0f, 1f) * k.height
    drawLine(accent, Offset(k.left + 2.dp.toPx(), vy), Offset(k.right - 2.dp.toPx(), vy), 2.5.dp.toPx(), StrokeCap.Round)
    val lbl = measurer.measure("VERB", TextStyle(color = Color(0xFF6A6A70), fontSize = (w * 0.026f).toSp(), fontFamily = FontFamily.Monospace))
    drawText(lbl, topLeft = Offset(k.center.x - lbl.size.width / 2f, k.bottom + h * 0.006f))
}
