package com.slowva.player.ui.sound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.slowva.player.player.fx.EqBand
import com.slowva.player.player.fx.EqSpec
import com.slowva.player.player.fx.FilterType
import com.slowva.player.player.sound.EqMode
import com.slowva.player.player.sound.SoundSettings
import com.slowva.player.ui.ChipRow
import com.slowva.player.ui.FxSlider
import com.slowva.player.ui.fxSliderColors
import com.slowva.player.ui.Section
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** The user's own EQ: ten bands or up to ten parametric filters, after the genre tone. */
@Composable
fun EqSection() {
    val eq by SoundSettings.eq.collectAsState()
    val summary = if (!eq.on) "off" else eq.mode.label.lowercase()
    Section(Icons.Default.Equalizer, "EQ", summary) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipRow(Modifier.weight(1f)) {
                EqMode.entries.forEach { m ->
                    FilterChip(selected = eq.mode == m, onClick = { SoundSettings.setEq { it.copy(mode = m) } }, label = { Text(m.label) })
                }
            }
            Switch(
                checked = eq.on, onCheckedChange = { on -> SoundSettings.setEq { it.copy(on = on) } },
                modifier = Modifier.semantics { contentDescription = "EQ on" },
            )
        }
        val spec = remember(eq) {
            if (eq.mode == EqMode.GRAPHIC) EqSpec.graphic(eq.graphic.toFloatArray()) else EqSpec(eq.bands)
        }
        CurvePreview(spec)
        if (eq.mode == EqMode.GRAPHIC) {
            GraphicEditor(eq.graphic) { i, v ->
                SoundSettings.setEq { s -> s.copy(on = true, graphic = s.graphic.toMutableList().also { it[i] = v }) }
            }
            OutlinedButton(onClick = { SoundSettings.setEq { it.copy(graphic = List(10) { 0f }) } }) { Text("Flat") }
        } else {
            ParametricEditor(eq.bands) { bands -> SoundSettings.setEq { it.copy(on = true, bands = bands) } }
        }
    }
}

/** Ten vertical sliders, 31 Hz to 16 kHz, plus or minus 12 dB. */
@Composable
fun GraphicEditor(bands: List<Float>, onChange: (Int, Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        EqSpec.GRAPHIC_FREQS.forEachIndexed { i, f ->
            val g = bands.getOrElse(i) { 0f }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%+.0f".format(g), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = g,
                    onValueChange = { v -> onChange(i, if (kotlin.math.abs(v) < 0.4f) 0f else (v * 2f).roundToInt() / 2f) },
                    valueRange = -12f..12f,
                    colors = fxSliderColors(),
                    modifier = Modifier
                        .semantics { contentDescription = "${freqLabel(f)} band"; stateDescription = "%+.1f dB".format(g) }
                        .graphicsLayer { rotationZ = 270f; transformOrigin = TransformOrigin(0f, 0f) }
                        .layout { measurable, c ->
                            val p = measurable.measure(Constraints(minWidth = c.minHeight, maxWidth = c.maxHeight, minHeight = c.minWidth, maxHeight = c.maxWidth))
                            layout(p.height, p.width) { p.place(-p.width, 0) }
                        }
                        .width(150.dp)
                        .height(32.dp),
                )
                Text(freqLabel(f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Response of [spec] from 20 Hz to 20 kHz, plus or minus 12 dB. */
@Composable
fun CurvePreview(spec: EqSpec?) {
    val points = remember(spec) {
        FloatArray(96) { k -> spec?.responseDb(20.0 * 1000.0.pow(k / 95.0))?.toFloat() ?: 0f }
    }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "EQ curve" },
    ) {
        val mid = size.height / 2
        drawLine(grid, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        val path = Path()
        points.forEachIndexed { k, db ->
            val x = k / 95f * size.width
            val y = mid - db.coerceIn(-12f, 12f) / 12f * mid
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Up to ten filters, each with type, frequency, gain and Q. */
@Composable
fun ParametricEditor(bands: List<EqBand>, onChange: (List<EqBand>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bands.forEachIndexed { i, b ->
            fun set(nb: EqBand) = onChange(bands.toMutableList().also { it[i] = nb })
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Filter ${i + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(bands.toMutableList().also { it.removeAt(i) }) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove filter ${i + 1}")
                        }
                    }
                    ChipRow {
                        FilterType.entries.forEach { t ->
                            FilterChip(selected = b.type == t, onClick = { set(b.copy(type = t)) }, label = { Text(t.label) })
                        }
                    }
                    val pos = (log10(b.freq) - 1f) / 3.3f
                    FxSlider("Frequency", "${freqLabel(b.freq)}Hz", pos, 0f..1f, (log10(1000f) - 1f) / 3.3f) {
                        set(b.copy(freq = 10f.pow(1f + it * 3.3f).roundToInt().toFloat().coerceIn(10f, 20000f)))
                    }
                    FxSlider("Gain", "%+.1f dB".format(b.gainDb), b.gainDb, -12f..12f, 0f, enabled = b.type.hasGain) {
                        set(b.copy(gainDb = (it * 10f).roundToInt() / 10f))
                    }
                    val qPos = (log10(b.q) + 1f) / 2f
                    FxSlider("Q", "%.2f".format(b.q), qPos, 0f..1f, (log10(0.707f) + 1f) / 2f) {
                        set(b.copy(q = 10f.pow(it * 2f - 1f)))
                    }
                }
            }
        }
        if (bands.size < 10) {
            OutlinedButton(onClick = { onChange(bands + EqBand(FilterType.PEAK, 1000f, 0f, 1f)) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Add filter", Modifier.padding(start = 6.dp))
            }
        }
    }
}

internal fun freqLabel(f: Float): String = if (f >= 1000f) {
    val k = f / 1000f
    if (k == k.roundToInt().toFloat()) "${k.roundToInt()}k" else "%.1fk".format(k)
} else "${f.roundToInt()}"
