package com.slowva.player.ui.skins.radio

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

/** Hit area of the play key, in fractions of the face. */
internal val BoomboxKey = Rect(0.39f, 0.8f, 0.61f, 0.93f)

/** A silver boombox in the TP-7's style: two grille speakers, a tuning strip, the station's art in the middle. */
internal fun CacheDrawScope.boombox(s: RadioScene): DrawResult {
    val w = size.width; val h = size.height
    fun box(l: Float, t: Float, r: Float, b: Float) = Rect(l * w, t * h, r * w, b * h)
    val px = 1.dp.toPx()
    val shell = box(0.01f, 0.12f, 0.985f, 0.95f)
    val dial = box(0.06f, 0.19f, 0.6f, 0.3f)
    val scaleL = dial.left + dial.width * 0.06f; val scaleR = dial.right - dial.width * 0.06f
    val needleX = scaleL + (scaleR - scaleL) * (0.04f + 0.92f * s.tuning)
    val screen = Screen(s.measurer, this, s.station, s.title, box(0.64f, 0.18f, 0.94f, 0.33f))
    val left = Grille(Offset(0.185f * w, 0.6f * h), 0.135f * w, light = true)
    val right = Grille(Offset(0.815f * w, 0.6f * h), 0.135f * w, light = true)
    val side = 0.36f * h
    val cover = Rect(Offset(0.5f * w - side / 2, 0.39f * h), Size(side, side))
    val key = box(BoomboxKey.left, BoomboxKey.top, BoomboxKey.right, BoomboxKey.bottom)

    val back = obtainGraphicsLayer().apply {
        record {
            // handle: a plain bent bar on two posts, behind the body
            val bar = box(0.24f, 0.03f, 0.76f, 0.2f)
            drawRoundRect(Color(0xFFA6A6AB), bar.topLeft, bar.size, CornerRadius(h * 0.06f), style = Stroke(h * 0.035f))
            drawRoundRect(Color.White.copy(alpha = 0.35f), bar.topLeft, bar.size, CornerRadius(h * 0.06f), style = Stroke(px))
            for (x in floatArrayOf(0.22f, 0.74f)) drawRoundRect(Color(0xFF9A9A9F), Offset(x * w, 0.09f * h), Size(0.04f * w, 0.06f * h), CornerRadius(h * 0.01f))
            body(shell, w * 0.05f, light = true)

            // tuning strip: dark ticks on a lighter inset, FM numbers in the TP-7's small mono
            drawRoundRect(Color(0xFFE4E4E7), dial.topLeft, dial.size, CornerRadius(dial.height * 0.18f))
            drawRoundRect(Color.Black.copy(alpha = 0.12f), dial.topLeft, dial.size, CornerRadius(dial.height * 0.18f), style = Stroke(px))
            val base = dial.top + dial.height * 0.7f
            val num = mono(dial.height * 0.24f, Color(0xFF6A6A70))
            for (f in 88..108) {
                val x = scaleL + (f - 88) / 20f * (scaleR - scaleL)
                val major = f % 4 == 0
                drawLine(Color(0xFF6A6A70).copy(alpha = if (major) 0.9f else 0.45f), Offset(x, base - dial.height * (if (major) 0.2f else 0.1f)), Offset(x, base), px)
                if (major) centered(s.measurer.measure("$f", num), Offset(x, dial.top + dial.height * 0.28f))
            }

            drawText(s.measurer.measure("BX-1", label(h * 0.05f, Kit.printOnLight)), topLeft = Offset(0.07f * w, 0.84f * h))
        }
    }
    val initial = s.measurer.measure(s.initial, label(side * 0.42f, Color.White))
    return onDrawBehind {
        drawLayer(back)
        val t = s.t()
        left.draw(this, if (s.playing) level(t, 0) else 0f)
        right.draw(this, if (s.playing) level(t, 1) else 0f)
        drawLine(s.accent, Offset(needleX, dial.top + dial.height * 0.1f), Offset(needleX, dial.bottom - dial.height * 0.1f), 2 * px)
        screen.draw(this, s.playing, t)
        // the station's art sits where the cassette door would be, with the cover skin's shadow
        drawRoundRect(Color.Black.copy(alpha = 0.3f), cover.topLeft + Offset(0f, side * 0.03f), cover.size, CornerRadius(side * 0.04f))
        art(cover, s.art, initial, s.color)
        val face = flatKey(key, s.pressed)
        playGlyph(face.center, face.height * 0.2f, s.playing)
    }
}
