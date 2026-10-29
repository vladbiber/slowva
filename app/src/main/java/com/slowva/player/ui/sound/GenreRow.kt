package com.slowva.player.ui.sound

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.slowva.player.ui.pressFeedback
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.sound.GenrePreset
import com.slowva.player.player.sound.SoundEngine
import com.slowva.player.player.sound.SoundSettings
import com.slowva.player.ui.FxSlider
import com.slowva.player.ui.Section

/** Plain status: the preset in use and, only when a genre was found, what it matched; Apply or Undo beside it. */
@Composable
fun PresetStatus() {
    val s by SoundEngine.suggestion.collectAsState()
    val current by SoundSettings.genreKey.collectAsState()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("Preset: ${GenrePreset.labelOf(current)}", style = MaterialTheme.typography.bodyLarge)
        val sug = s?.takeIf { it.key != GenrePreset.BALANCED } ?: return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Matched to ${GenrePreset.labelOf(sug.key)}", style = MaterialTheme.typography.bodyMedium, color = dim, modifier = Modifier.weight(1f))
            when {
                sug.key != current -> TextButton(onClick = { SoundEngine.applySuggestion() }) { Text("Apply") }
                sug.autoFrom != null -> TextButton(onClick = { SoundEngine.undoAuto() }) { Text("Undo") }
            }
        }
    }
}

/** The preset chips (wrapping, never clipped) with the auto-apply switch; long-press a chip to edit or reset it. */
@Composable
fun GenrePresetsRow() {
    val presets by SoundSettings.presets.collectAsState()
    val current by SoundSettings.genreKey.collectAsState()
    val auto by SoundSettings.genreAuto.collectAsState()
    var editing by remember { mutableStateOf<GenrePreset?>(null) }

    Section(Icons.Default.Tune, "Presets", GenrePreset.labelOf(current)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { p ->
                PresetChip(
                    label = p.label, selected = p.key == current, edited = p.edited,
                    onClick = { SoundEngine.pickGenre(p.key) },
                    onLongClick = { editing = p },
                )
            }
            IconButton(onClick = { editing = SoundSettings.preset(current) }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit the current preset")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-apply by genre", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = auto, onCheckedChange = { SoundSettings.setGenreAuto(it) },
                modifier = Modifier.semantics { contentDescription = "Auto-apply genre presets" },
            )
        }
        Text(
            if (auto) "Each new song gets its matched preset. Picking another one teaches it for the song and the artist."
            else "Presets change only when you tap one. Long-press to edit.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    editing?.let { p -> PresetEditor(p) { editing = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(label: String, selected: Boolean, edited: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (selected) c.secondaryContainer else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, c.outline),
        modifier = Modifier
            .pressFeedback(press)
            .minimumInteractiveComponentSize()
            .semantics { stateDescription = if (selected) "selected" else "not selected" }
            .combinedClickable(
                interactionSource = press, indication = LocalIndication.current,
                role = Role.Button, onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = "edit preset",
            ),
    ) {
        Text(
            if (edited) "$label *" else label,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = if (selected) c.onSecondaryContainer else c.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Edits a copy of the preset's curve; saving keeps it as the user's own, reset brings the bundled one back. */
@Composable
private fun PresetEditor(p: GenrePreset, onDismiss: () -> Unit) {
    var bands by remember(p.key) { mutableStateOf(p.bands) }
    var tape by remember(p.key) { mutableStateOf(p.tape) }
    var loud by remember(p.key) { mutableStateOf(p.loudness) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${p.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CurvePreview(p.copy(bands = bands).spec(0))
                GraphicEditor(bands) { i, v -> bands = bands.toMutableList().also { it[i] = v } }
                FxSlider("Tape warmth", "${(tape * 100).toInt()}%", tape, 0f..1f, 0f) { tape = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Loudness compensation", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = loud, onCheckedChange = { loud = it })
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                SoundSettings.saveGenreCopy(p.copy(bands = bands, tape = tape, loudness = loud))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (p.edited) {
                    TextButton(onClick = { SoundSettings.resetGenre(p.key); onDismiss() }) { Text("Reset") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
