package com.slowva.player.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.player.Mood
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.RETUNE_PRESETS
import com.slowva.player.player.RainPreset
import com.slowva.player.player.SLOW_STEPS
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Every sound setting in one scrollable column of cards. Used by the player's
 * Sound sheet and by the Sound tab, so both always offer the same controls.
 */
@Composable
fun SoundPanel(modifier: Modifier = Modifier) {
    val rate by PlayerHub.rate.collectAsState()
    val speed by PlayerHub.speed.collectAsState()
    val reverb by PlayerHub.reverb.collectAsState()
    val retune by PlayerHub.retuneIndex.collectAsState()
    val bassGain by PlayerHub.bassGain.collectAsState()
    val bassFreq by PlayerHub.bassFreq.collectAsState()
    val width by PlayerHub.width.collectAsState()
    val pan8d by PlayerHub.pan8d.collectAsState()
    val muffle by PlayerHub.muffle.collectAsState()
    val vinyl by PlayerHub.vinyl.collectAsState()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(Icons.Default.AutoAwesome, "Presets", "one tap sets everything") {
            ChipRow {
                Mood.entries.forEach { m ->
                    val on = abs(speed - m.rate) < 0.005f && retune == 0 && abs(reverb - m.reverb) < 0.005f &&
                        abs(bassGain - m.bassDb) < 0.05f && abs(width - m.width) < 0.01f &&
                        abs(pan8d - m.pan8d) < 0.01f && abs(muffle - m.muffle) < 0.01f &&
                        vinyl.on == (m.vinyl != null)
                    FilterChip(selected = on, onClick = { PlayerHub.applyMood(m) }, label = { Text(m.label) })
                }
            }
        }

        Section(Icons.Default.Speed, "Speed & pitch", "${"%.2f".format(rate)}× · ${semitones(rate)} semitones") {
            FxSlider("Slow", "${"%.2f".format(rate)}×", rate, 0.5f..1.25f, 1f) { PlayerHub.setRate(it) }
            Text("Steps", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        Section(Icons.Default.Waves, "Reverb", "${(reverb * 100).roundToInt()}%") {
            FxSlider("Reverb", "${(reverb * 100).roundToInt()}%", reverb, 0f..1f, 0f) { PlayerHub.setReverb(it) }
        }

        Section(Icons.Default.GraphicEq, "Bass", if (bassGain < 0.05f) "off" else "+${"%.1f".format(bassGain)} dB") {
            FxSlider("Boost", "+${"%.1f".format(bassGain)} dB", bassGain, 0f..12f, 0f) { PlayerHub.setBass(it, bassFreq) }
            FxSlider("Below", "${bassFreq.roundToInt()} Hz", bassFreq, 40f..320f, 120f, enabled = bassGain > 0.05f) {
                PlayerHub.setBass(bassGain, it)
            }
        }

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
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "The nearest note is tuned onto that frequency. The claimed effects have no " +
                    "support in research; they just sound different.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(Icons.Default.Headphones, "Space", spaceSummary(width, pan8d, muffle)) {
            FxSlider("Stereo width", "${(width * 100).roundToInt()}%", width, 0f..2f, 1f) { PlayerHub.setSpace(width = it) }
            FxSlider("8D rotation", "${(pan8d * 100).roundToInt()}%", pan8d, 0f..1f, 0f) { PlayerHub.setSpace(pan8d = it) }
            FxSlider("Another room", "${(muffle * 100).roundToInt()}%", muffle, 0f..1f, 0f) { PlayerHub.setSpace(muffle = it) }
            Text(
                "8D slowly circles the sound around your head; best on headphones.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val reversed by PlayerHub.reverseOn.collectAsState()
        val rev by PlayerHub.reverseState.collectAsState()
        Section(Icons.Default.FastRewind, "Reverse", if (rev?.preparing == true) "decoding…" else if (reversed) "on" else "off") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Plays every track from its last second to its first. Each track is downloaded " +
                        "once first, so it can take a few seconds to start.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = reversed, onCheckedChange = { PlayerHub.toggleReverse() },
                    modifier = Modifier.semantics { contentDescription = "Reverse mode" },
                )
            }
        }

        val rain by PlayerHub.rain.collectAsState()
        val rainLevel by PlayerHub.rainLevel.collectAsState()
        Section(Icons.Default.WaterDrop, "Rain", rain.label.lowercase()) {
            ChipRow {
                RainPreset.entries.forEach { p ->
                    FilterChip(selected = rain == p, onClick = { PlayerHub.setRain(p) }, label = { Text(p.label) })
                }
            }
            FxSlider("Rain volume", "${(rainLevel * 100).roundToInt()}%", rainLevel, 0f..1.5f, 1f, enabled = rain != RainPreset.OFF) {
                PlayerHub.setRain(rain, it)
            }
            Text(
                "Generated live, mixed on top of the music and never slowed down.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(Icons.Default.Album, "Vinyl", if (vinyl.on) "on" else "off") {
            VinylControls()
        }

        OutlinedButton(onClick = { PlayerHub.resetFx() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Reset everything")
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, summary: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MarkedSlider(
            value = value, onValueChange = onChange, valueRange = range, default = default,
            enabled = enabled, description = label, stateText = valueText,
        )
    }
}

private fun spaceSummary(width: Float, pan: Float, muffle: Float): String {
    val parts = buildList {
        if (abs(width - 1f) > 0.01f) add("width ${(width * 100).roundToInt()}%")
        if (pan > 0.01f) add("8D")
        if (muffle > 0.01f) add("room")
    }
    return if (parts.isEmpty()) "off" else parts.joinToString(" · ")
}

/** How far the pitch drops at a given playback rate, in semitones. */
fun semitones(rate: Float): String = "%+.1f".format(12.0 * ln(rate.toDouble()) / ln(2.0))
