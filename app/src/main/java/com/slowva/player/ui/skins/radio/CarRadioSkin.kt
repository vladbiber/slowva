package com.slowva.player.ui.skins.radio

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.drawText

/** Hit area of the play key, in fractions of the face. */
internal val CarKey = Rect(0.73f, 0.63f, 0.912f, 0.84f)

/** A graphite car head unit in the same flat style: volume dial, screen, presets 1-6, the station's art. */
internal fun CacheDrawScope.carRadio(s: RadioScene): DrawResult {
    val w = size.width; val h = size.height
    fun box(l: Float, t: Float, r: Float, b: Float) = Rect(l * w, t * h, r * w, b * h)
    val face = box(0.01f, 0.06f, 0.99f, 0.92f)
    val preset = (s.tuning * 6).toInt() + 1
    val screen = Screen(s.measurer, this, s.station, s.title, box(0.21f, 0.15f, 0.69f, 0.55f))
    val presets = box(0.21f, 0.63f, 0.69f, 0.84f)
    val side = 0.4f * h
    val cover = Rect(Offset(0.73f * w, 0.15f * h), Size(side, side))
    val key = box(CarKey.left, CarKey.top, CarKey.right, CarKey.bottom)

    val back = obtainGraphicsLayer().apply {
        record {
            body(face, h * 0.07f, light = false)
            drawText(s.measurer.measure("CR-1", label(h * 0.065f, Kit.printOnDark)), topLeft = Offset(0.045f * w, 0.12f * h))
            flatKnob(Offset(0.115f * w, 0.53f * h), 0.18f * h, s.accent, -135f + 200f * s.tuning)
            centered(s.measurer.measure("VOL", mono(h * 0.055f, Kit.printOnDark)), Offset(0.115f * w, 0.83f * h))
            // presets: the station's slot is pressed in and marked in the accent
            val gap = 0.01f * w
            val pw = (presets.width - gap * 5) / 6
            val digit = mono(presets.height * 0.36f, Kit.glyph)
            for (i in 0 until 6) {
                val on = i + 1 == preset
                val cap = flatKey(Rect(Offset(presets.left + i * (pw + gap), presets.top), Size(pw, presets.height)), on)
                centered(s.measurer.measure("${i + 1}", digit), cap.center)
                if (on) drawRoundRect(s.accent, Offset(cap.center.x - pw * 0.2f, cap.bottom - cap.height * 0.18f), Size(pw * 0.4f, cap.height * 0.07f), CornerRadius(cap.height * 0.035f))
            }
            drawRoundRect(Color.Black.copy(alpha = 0.3f), cover.topLeft + Offset(0f, side * 0.03f), cover.size, CornerRadius(side * 0.04f))
        }
    }
    val initial = s.measurer.measure(s.initial, label(side * 0.42f, Color.White))
    return onDrawBehind {
        drawLayer(back)
        screen.draw(this, s.playing, s.t())
        art(cover, s.art, initial, s.color)
        val cap = flatKey(key, s.pressed)
        playGlyph(cap.center, cap.height * 0.2f, s.playing)
    }
}
