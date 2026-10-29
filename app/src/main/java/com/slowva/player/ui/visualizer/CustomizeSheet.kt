package com.slowva.player.ui.visualizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Color, sensitivity, smoothing, bar count, mirror and glow for every visualizer, with [preview] animating on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualizerCustomizeSheet(preview: @Composable () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    remember { VisualizerLook.load(ctx) }
    val s by VisualizerLook.settings.collectAsState()
    fun set(n: VizSettings) { VisualizerLook.update(n); VisualizerLook.save(ctx) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalVisualizerDemo provides true) { preview() }
            }
            Text("Color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VizColor.entries.forEach { c ->
                    FilterChip(selected = s.color == c, onClick = { set(s.copy(color = c)) }, label = { Text(c.label) })
                }
            }
            // sliders apply while dragging and save on release
            LookSlider("Sensitivity", "${"%.1f".format(s.gain)}×", s.gain, 0.5f..2f) { VisualizerLook.update(s.copy(gain = it)) }
            LookSlider("Smoothing", "${(s.smoothing * 100).roundToInt()}%", s.smoothing, 0f..0.9f) { VisualizerLook.update(s.copy(smoothing = it)) }
            LookSlider("Bars", "${s.bars}", s.bars.toFloat(), 12f..48f, steps = 48 - 12 - 1) { VisualizerLook.update(s.copy(bars = it.roundToInt())) }
            LookSwitch("Mirror", s.mirror) { set(s.copy(mirror = it)) }
            LookSwitch("Glow", s.glow) { set(s.copy(glow = it)) }
        }
    }
}

@Composable
private fun LookSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    val ctx = LocalContext.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = v, onValueChange = onChange, valueRange = range, steps = steps,
            onValueChangeFinished = { VisualizerLook.save(ctx) },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun LookSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}
