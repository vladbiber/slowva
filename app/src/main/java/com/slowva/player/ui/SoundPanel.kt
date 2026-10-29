package com.slowva.player.ui

import androidx.compose.material.icons.filled.AutoAwesome
import com.slowva.player.player.fx.Moods
import com.slowva.player.player.fx.Mood
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.fx.FxRange
import com.slowva.player.player.fx.RETUNE_PRESETS
import com.slowva.player.player.fx.RainPreset
import com.slowva.player.player.fx.SLOW_STEPS
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** The effect sections, one composable each; the sound sheet stacks them. */
@Composable
internal fun EffectRangeSection() {
    val range by PlayerHub.fxRange.collectAsState()
    Section(Icons.Default.Tune, "Effect range", rangeLabel(range)) {
        FxSlider("How far the sliders go", rangeLabel(range), range, 0f..1f, 0f) { PlayerHub.setFxRange(it) }
        Text(
            "Extreme: slow down to ${"%.2f".format(FxRange.slow(range).start)}×, speed up to ${"%.2f".format(FxRange.slow(range).endInclusive)}×, " +
                "reverb ${(FxRange.reverb(range).endInclusive * 100).roundToInt()}%, bass +${FxRange.bass(range).endInclusive.roundToInt()} dB",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Normal, Evil, Godly or both at once; the same switches as Settings > Look. */
@Composable
internal fun ModeSection() {
    val mood by Moods.mood.collectAsState()
    Section(Icons.Default.AutoAwesome, "Mode", MODE_LABELS.getValue(mood)) {
        ChipRow {
            MODE_LABELS.forEach { (m, label) ->
                FilterChip(
                    selected = mood == m,
                    onClick = {
                        Moods.setEvil(m == Mood.EVIL || m == Mood.GOOD_EVIL)
                        Moods.setGodly(m == Mood.GODLY || m == Mood.GOOD_EVIL)
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}

private val MODE_LABELS = linkedMapOf(Mood.NORMAL to "Normal", Mood.EVIL to "Evil", Mood.GODLY to "Godly", Mood.GOOD_EVIL to "Good & Evil")

@Composable
internal fun SpeedSection() {
    val rate by PlayerHub.rate.collectAsState()
    val reverb by PlayerHub.reverb.collectAsState()
    val range by PlayerHub.fxRange.collectAsState()
    Section(Icons.Default.Speed, "Speed & pitch", "${"%.2f".format(rate)}× · ${semitones(rate)} semitones") {
        FxSlider("Slow", "${"%.2f".format(rate)}×", rate, FxRange.slow(range), 1f) { PlayerHub.setRate(it) }
        Text("Steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            SLOW_STEPS.forEachIndexed { index, (stepRate, stepReverb) ->
                FilterChip(
                    selected = abs(rate - stepRate) < 0.005f && abs(reverb - stepReverb) < 0.005f,
                    onClick = { PlayerHub.setRate(stepRate); PlayerHub.setReverb(stepReverb) },
                    label = { Text(if (index == 0) "Off" else "Step $index") },
                )
            }
        }
    }
}

@Composable
internal fun ReverbSection() {
    val reverb by PlayerHub.reverb.collectAsState()
    val range by PlayerHub.fxRange.collectAsState()
    Section(Icons.Default.Waves, "Reverb", "${(reverb * 100).roundToInt()}%") {
        FxSlider("Reverb", "${(reverb * 100).roundToInt()}%", reverb, FxRange.reverb(range), 0f) { PlayerHub.setReverb(it) }
    }
}

@Composable
internal fun BassSection() {
    val bassGain by PlayerHub.bassGain.collectAsState()
    val bassFreq by PlayerHub.bassFreq.collectAsState()
    val range by PlayerHub.fxRange.collectAsState()
    Section(Icons.Default.GraphicEq, "Bass", if (bassGain < 0.05f) "off" else "+${"%.1f".format(bassGain)} dB") {
        FxSlider("Boost", "+${"%.1f".format(bassGain)} dB", bassGain, FxRange.bass(range), 0f) { PlayerHub.setBass(it, bassFreq) }
        FxSlider("Below", "${bassFreq.roundToInt()} Hz", bassFreq, 40f..320f, 120f, enabled = bassGain > 0.05f) {
            PlayerHub.setBass(bassGain, it)
        }
    }
}

@Composable
internal fun TuningSection() {
    val retune by PlayerHub.retuneIndex.collectAsState()
    val preset = RETUNE_PRESETS[retune]
    Section(Icons.Default.MusicNote, "Tuning (\"magic Hz\")", if (retune == 0) "440 Hz, off" else "${preset.hz} Hz") {
        ChipRow {
            RETUNE_PRESETS.forEachIndexed { index, p ->
                FilterChip(
                    selected = retune == index,
                    onClick = { PlayerHub.setRetune(index) },
                    label = { Text(if (index == 0) "440 off" else "${p.hz} Hz") },
                )
            }
        }
        Text(
            "${preset.note} → ${preset.hz} Hz · ${"%.3f".format(preset.ratio)}× — ${preset.claim}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "The nearest note is tuned onto that frequency. The claimed effects have no " +
                "support in research; they just sound different.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SpaceSection() {
    val width by PlayerHub.width.collectAsState()
    val pan8d by PlayerHub.pan8d.collectAsState()
    val muffle by PlayerHub.muffle.collectAsState()
    val range by PlayerHub.fxRange.collectAsState()
    Section(Icons.Default.Headphones, "Space", spaceSummary(width, pan8d, muffle)) {
        FxSlider("Stereo width", "${(width * 100).roundToInt()}%", width, FxRange.width(range), 1f) { PlayerHub.setSpace(width = it) }
        FxSlider("8D rotation", "${(pan8d * 100).roundToInt()}%", pan8d, 0f..1f, 0f) { PlayerHub.setSpace(pan8d = it) }
        FxSlider("Another room", "${(muffle * 100).roundToInt()}%", muffle, FxRange.muffle(range), 0f) { PlayerHub.setSpace(muffle = it) }
        Text(
            "8D slowly circles the sound around your head; best on headphones.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ReverseSection() {
    val reversed by PlayerHub.reverseOn.collectAsState()
    val preparing by PlayerHub.reversePreparing.collectAsState()
    Section(Icons.Default.FastRewind, "Reverse", if (preparing) "decoding…" else if (reversed) "on" else "off") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Plays every track from its last second to its first. Each track is decoded " +
                    "once first, so it can take a few seconds to start.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = reversed, onCheckedChange = { PlayerHub.toggleReverse() },
                modifier = Modifier.semantics { contentDescription = "Reverse mode" },
            )
        }
    }
}

@Composable
internal fun RainSection() {
    val rain by PlayerHub.rain.collectAsState()
    val rainLevel by PlayerHub.rainLevel.collectAsState()
    val range by PlayerHub.fxRange.collectAsState()
    Section(Icons.Default.WaterDrop, "Rain", rain.label.lowercase()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Generated live, mixed on top of the music and never slowed down.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = rain != RainPreset.OFF, onCheckedChange = { PlayerHub.toggleRain() },
                modifier = Modifier.semantics { contentDescription = "Rain sounds" },
            )
        }
        ChipRow {
            RainPreset.entries.forEach { p ->
                FilterChip(selected = rain == p, onClick = { PlayerHub.setRain(p) }, label = { Text(p.label) })
            }
        }
        FxSlider("Rain volume", "${(rainLevel * 100).roundToInt()}%", rainLevel, FxRange.rain(range), 1f, enabled = rain != RainPreset.OFF) {
            PlayerHub.setRain(rain, it)
        }
    }
}

@Composable
internal fun VinylSection() {
    val vinyl by PlayerHub.vinyl.collectAsState()
    Section(Icons.Default.Album, "Vinyl", if (vinyl.on) "on" else "off") {
        VinylControls()
    }
}

@Composable
internal fun ResetFxButton() {
    CtButton(onClick = { PlayerHub.resetFx() }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Reset everything")
    }
}

@Composable
internal fun Section(icon: ImageVector, title: String, summary: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                SectionHeader(title, Modifier.weight(1f))
                Text(
                    summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp),
                )
            }
            content()
        }
    }
}

@Composable
internal fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // wraps instead of scrolling, so no chip ends up half off the edge
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

/** A labelled slider that reads well to TalkBack: "Reverb, 35%". */
@Composable
fun FxSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MarkedSlider(
            value = value, onValueChange = onChange, valueRange = range, default = default,
            enabled = enabled, description = label, stateText = valueText,
        )
    }
}

private fun rangeLabel(t: Float) =
    if (t < 0.01f) "normal (x1)" else "x${"%.1f".format(FxRange.factor(t)).removeSuffix(".0")}"

internal fun spaceSummary(width: Float, pan: Float, muffle: Float): String {
    val parts = buildList {
        if (abs(width - 1f) > 0.01f) add("width ${(width * 100).roundToInt()}%")
        if (pan > 0.01f) add("8D")
        if (muffle > 0.01f) add("room")
    }
    return if (parts.isEmpty()) "off" else parts.joinToString(" · ")
}

/** How far the pitch drops at a given playback rate, in semitones. */
fun semitones(rate: Float): String = "%+.1f".format(12.0 * ln(rate.toDouble()) / ln(2.0))
