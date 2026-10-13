package com.slowva.player.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.VinylPreset
import kotlin.math.abs
import kotlin.math.roundToInt

/** Record emulation: on/off, condition presets and the four ingredients. */
@Composable
fun VinylControls(modifier: Modifier = Modifier) {
    val v by PlayerHub.vinyl.collectAsState()
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Real vinyl sound", fontWeight = FontWeight.SemiBold)
                Text(
                    "Crackle, pops, wow & flutter and a warm, rolled-off top end",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = v.on,
                onCheckedChange = { PlayerHub.setVinyl(v.copy(on = it)) },
                modifier = Modifier.semantics { contentDescription = "Real vinyl sound" },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VinylPreset.entries.forEach { p ->
                val active = abs(p.crackle - v.crackle) < 0.01f && abs(p.warmth - v.warmth) < 0.01f &&
                    abs(p.wow - v.wow) < 0.01f && abs(p.surface - v.surface) < 0.01f
                FilterChip(
                    selected = active,
                    onClick = { PlayerHub.setVinyl(p.apply(on = true)) },
                    label = { Text(p.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Knob("Crackle & pops", v.crackle, v.on) { PlayerHub.setVinyl(v.copy(crackle = it)) }
        Knob("Warmth", v.warmth, v.on) { PlayerHub.setVinyl(v.copy(warmth = it)) }
        Knob("Wow & flutter", v.wow, v.on) { PlayerHub.setVinyl(v.copy(wow = it)) }
        Knob("Hiss & rumble", v.surface, v.on) { PlayerHub.setVinyl(v.copy(surface = it)) }
    }
}

@Composable
private fun Knob(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    FxSlider(label, "${(value * 100).roundToInt()}%", value, 0f..1f, -1f, enabled = enabled, onChange = onChange)
}
