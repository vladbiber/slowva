package com.slowva.player.ui.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** The muted sleeve colors for a name: same name, same colors, on every run. */
data class CoverTones(val paper: Color, val ink: Color, val mark: Color)

internal fun nameHash(name: String): Int {
    var h = name.trim().lowercase().hashCode()
    // spread nearby strings ("Mix 1", "Mix 2") across the hue wheel
    h = h xor (h ushr 16)
    h *= -0x7a143595
    h = h xor (h ushr 13)
    return h and 0x7fffffff
}

/** A low-saturation, dark-theme friendly sleeve color, a light ink of the same hue, and a mark color nearby. */
fun coverTones(name: String): CoverTones {
    val h = nameHash(name)
    val hue = (h % 360).toFloat()
    val sat = 0.22f + (h / 360 % 5) * 0.04f
    val light = 0.24f + (h / 1800 % 4) * 0.03f
    return CoverTones(
        paper = Color.hsl(hue, sat, light),
        ink = Color.hsl(hue, 0.35f, 0.88f),
        mark = Color.hsl((hue + 28f) % 360f, sat + 0.1f, light + 0.12f),
    )
}

/** The sleeve number printed in the corner, 01..99. */
internal fun sleeveNumber(name: String) = "%02d".format(nameHash(name) % 99 + 1)

/**
 * A cover made from a name, set like a record sleeve: the muted [coverTones] color, a thin rule, the
 * sleeve number and the title in bold type that scales with the box. Small boxes show only the initial,
 * tiny ones just a dot. Fills its [modifier]; the caller clips it.
 */
@Composable
fun GeneratedCover(name: String, modifier: Modifier = Modifier) {
    val tones = coverTones(name)
    val title = name.trim()
    BoxWithConstraints(modifier.background(tones.paper).clipToBounds(), contentAlignment = Alignment.Center) {
        val w = minOf(maxWidth, maxHeight).takeIf { it.value.isFinite() && it > 0.dp } ?: 48.dp
        val sp = with(LocalDensity.current) { { f: Float -> (w * f).toSp() } }
        val layout = nameHash(name) / 7 % 3
        when {
            w < 28.dp || title.isEmpty() -> Box(Modifier.size(w * 0.22f).background(tones.mark, CircleShape))
            w < 72.dp -> {
                SleeveMark(layout, tones.mark, Modifier.fillMaxSize())
                Text(
                    title.first().uppercase(), color = tones.ink,
                    style = TextStyle(fontWeight = FontWeight.Black, fontSize = sp(0.46f), lineHeight = 1.em),
                )
            }
            else -> {
                SleeveMark(layout, tones.mark, Modifier.fillMaxSize())
                val pad = w * 0.09f
                Column(
                    Modifier.fillMaxSize().padding(pad),
                    verticalArrangement = if (layout == 1) Arrangement.Top else Arrangement.Bottom,
                ) {
                    if (layout == 1) Number(name, tones, sp(0.075f))
                    if (layout == 1) Spacer(Modifier.height(w * 0.05f))
                    Box(Modifier.width(w * 0.22f).height(maxOf(1.dp, w * 0.012f)).background(tones.ink.copy(alpha = 0.8f)))
                    Spacer(Modifier.height(w * 0.045f))
                    Text(
                        title, color = tones.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = sp(if (title.length > 18) 0.11f else 0.14f), lineHeight = 1.05.em, letterSpacing = (-0.02).em),
                    )
                    if (layout != 1) {
                        Spacer(Modifier.height(w * 0.04f))
                        Number(name, tones, sp(0.075f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Number(name: String, tones: CoverTones, size: androidx.compose.ui.unit.TextUnit) {
    Text("No. ${sleeveNumber(name)}", color = tones.ink.copy(alpha = 0.65f), style = TextStyle(fontWeight = FontWeight.Medium, fontSize = size, letterSpacing = 0.08.em))
}

/** The graphic on the sleeve: a record sliding out, a target of rings, or a big half circle. */
@Composable
private fun SleeveMark(layout: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val m = size.minDimension
        when (layout) {
            0 -> {
                val c = Offset(size.width * 0.78f, size.height * 0.30f)
                drawCircle(color, m * 0.42f, c)
                drawCircle(color.copy(alpha = 0.55f), m * 0.30f, c, style = Stroke(m * 0.008f))
                drawCircle(color.copy(alpha = 0.55f), m * 0.20f, c, style = Stroke(m * 0.008f))
                drawCircle(color.copy(red = color.red * 0.7f, green = color.green * 0.7f, blue = color.blue * 0.7f), m * 0.10f, c)
            }
            1 -> {
                val c = Offset(size.width * 0.70f, size.height * 0.80f)
                for (i in 5 downTo 1) drawCircle(color.copy(alpha = 0.18f * i), m * 0.09f * i, c, style = Stroke(m * 0.03f))
            }
            else -> drawCircle(color, m * 0.55f, Offset(size.width, size.height * 0.08f))
        }
    }
}
