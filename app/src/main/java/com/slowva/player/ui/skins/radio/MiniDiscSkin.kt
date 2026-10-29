package com.slowva.player.ui.skins.radio

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

/** Hit area of the play key, in fractions of the face. */
internal val MiniDiscKey = Rect(0.68f, 0.52f, 0.88f, 0.76f)

/** A graphite MiniDisc player, flat like the tape skin: the disc shows the station's art and turns while on air. */
internal fun CacheDrawScope.miniDisc(s: RadioScene): DrawResult {
    val w = size.width; val h = size.height
    fun box(l: Float, t: Float, r: Float, b: Float) = Rect(l * w, t * h, r * w, b * h)
    val px = 1.dp.toPx()
    val shell = box(0.015f, 0.03f, 0.985f, 0.95f)
    val cw = 0.52f * w; val ch = cw * 68f / 72f
    val cart = Rect(Offset(0.06f * w, 0.15f * h), Size(cw, ch))
    val dc = Offset(cart.center.x, cart.top + ch * 0.53f); val dr = cw * 0.4f
    val screen = Screen(s.measurer, this, s.station, s.title, box(0.63f, 0.15f, 0.94f, 0.38f))
    val keyC = Offset(0.78f * w, 0.64f * h); val keyR = 0.09f * w

    val back = obtainGraphicsLayer().apply {
        record {
            body(shell, w * 0.06f, light = false)
            drawText(s.measurer.measure("MD-1", label(h * 0.045f, Kit.printOnDark)), topLeft = Offset(0.07f * w, 0.065f * h))
            // smoke cartridge with its corner tab
            val cr = CornerRadius(cw * 0.05f)
            drawRoundRect(Color.Black.copy(alpha = 0.3f), cart.topLeft + Offset(0f, ch * 0.02f), cart.size, cr)
            drawRoundRect(Color(0xFF4A4C54), cart.topLeft, cart.size, cr)
            drawRoundRect(Color.White.copy(alpha = 0.18f), cart.topLeft, cart.size, cr, style = Stroke(px))
            val tab = Rect(Offset(cart.left + cw * 0.06f, cart.top + ch * 0.05f), Size(cw * 0.16f, ch * 0.08f))
            drawRoundRect(Kit.key, tab.topLeft, tab.size, CornerRadius(tab.height * 0.2f))
            centered(s.measurer.measure("MD", label(tab.height * 0.6f, Kit.glyph)), tab.center)
            drawCircle(Color(0xFF1A1A1D), dr * 1.04f, dc)
        }
    }
    val initial = s.measurer.measure(s.initial, label(dr * 0.42f, Color.White))
    val disc = Rect(dc, dr)
    return onDrawBehind {
        drawLayer(back)
        val t = s.t()
        rotate(if (s.playing) t * 360f * 4 else 0f, dc) {
            // the letter sits above the hub, like a printed disc label
            art(disc, s.art, initial, s.color, round = true, initialAt = dc - Offset(0f, dr * 0.5f))
            drawCircle(Color.White.copy(alpha = 0.25f), dr, dc, style = Stroke(px))
        }
        // the hub stays put
        drawCircle(Color(0xFF1A1A1D), dr * 0.15f, dc)
        drawCircle(Color(0xFFCDCDD1), dr * 0.09f, dc)
        screen.draw(this, s.playing, t)
        roundKey(keyC, keyR, s.pressed)
        playGlyph(keyC, keyR * 0.32f, s.playing)
    }
}
