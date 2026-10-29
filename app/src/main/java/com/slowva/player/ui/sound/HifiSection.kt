package com.slowva.player.ui.sound

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.slowva.player.player.sound.RgMode
import com.slowva.player.player.sound.SoundEngine
import com.slowva.player.player.sound.SoundPreset
import com.slowva.player.player.sound.SoundSettings
import com.slowva.player.ui.ChipRow
import com.slowva.player.ui.FxSlider
import com.slowva.player.ui.Section
import kotlin.math.abs
import kotlin.math.roundToInt

/** ReplayGain, loudness compensation, tape and tube colour, night mode, balance, then the signal path. */
@Composable
fun HifiSection() {
    val h by SoundSettings.hifi.collectAsState()
    val rgStatus by SoundEngine.rgStatus.collectAsState()
    val summary = buildList {
        if (h.rgMode != RgMode.OFF) add("ReplayGain")
        if (h.loudness) add("loudness")
        if (h.tape > 0.01f) add("tape")
        if (h.tube > 0.01f) add("tube")
        if (h.night) add("night")
        if (h.bitPerfect) add("bit-perfect")
    }.joinToString(" · ").ifEmpty { "off" }

    Section(Icons.Default.HighQuality, "Hi-fi", summary) {
        ChipRow {
            SoundPreset.entries.forEach { p ->
                FilterChip(selected = false, onClick = { SoundSettings.applyPreset(p) }, label = { Text(p.label) })
            }
        }

        Sub("ReplayGain")
        ChipRow {
            RgMode.entries.forEach { m ->
                FilterChip(selected = h.rgMode == m, onClick = { SoundSettings.setHifi { it.copy(rgMode = m) } }, label = { Text(m.label) })
            }
        }
        if (h.rgMode != RgMode.OFF) {
            rgStatus?.let { Text("This song: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FxSlider("Preamp", "%+.1f dB".format(h.rgPreampDb), h.rgPreampDb, -12f..12f, 0f) { v ->
                SoundSettings.setHifi { it.copy(rgPreampDb = (v * 2).roundToInt() / 2f) }
            }
            Toggle("Prevent clipping", h.rgPreventClip) { on -> SoundSettings.setHifi { it.copy(rgPreventClip = on) } }
            Text(
                "Songs without tags are measured once (EBU R128, -18 LUFS) and remembered.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Sub("Loudness")
        Toggle("Loudness compensation", h.loudness) { on -> SoundSettings.setHifi { it.copy(loudness = on) } }
        Text(
            "Adds bass and a little treble as the player volume goes down, none at full volume.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Sub("Colour")
        FxSlider("Tape", "${(h.tape * 100).roundToInt()}%", h.tape, 0f..1f, 0f) { v -> SoundSettings.setHifi { it.copy(tape = v) } }
        FxSlider("Tube", "${(h.tube * 100).roundToInt()}%", h.tube, 0f..1f, 0f) { v -> SoundSettings.setHifi { it.copy(tube = v) } }

        Sub("Night mode")
        Toggle("Compressor", h.night) { on -> SoundSettings.setHifi { it.copy(night = on) } }
        if (h.night) {
            FxSlider("Threshold", "${h.nightThresholdDb.roundToInt()} dB", h.nightThresholdDb, -50f..-10f, -30f) { v ->
                SoundSettings.setHifi { it.copy(nightThresholdDb = v) }
            }
            FxSlider("Makeup", "+${"%.1f".format(h.nightMakeupDb)} dB", h.nightMakeupDb, 0f..12f, 6f) { v ->
                SoundSettings.setHifi { it.copy(nightMakeupDb = v) }
            }
        }

        Sub("Balance")
        val balText = when {
            abs(h.balance) < 0.01f -> "centre"
            h.balance < 0 -> "L ${(-h.balance * 100).roundToInt()}%"
            else -> "R ${(h.balance * 100).roundToInt()}%"
        }
        FxSlider("Balance", balText, h.balance, -1f..1f, 0f) { v -> SoundSettings.setHifi { it.copy(balance = v) } }
        Toggle("Mono", h.mono) { on -> SoundSettings.setHifi { it.copy(mono = on) } }
        Toggle("Swap left and right", h.swap) { on -> SoundSettings.setHifi { it.copy(swap = on) } }

        HorizontalDivider()
        SignalPathPanel()
    }
}

@Composable
internal fun Sub(title: String) {
    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, enabled = enabled, onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
