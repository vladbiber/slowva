package com.slowva.player.ui.sound

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slowva.player.player.fx.CrossfeedPreset
import com.slowva.player.player.sound.AutoEq
import com.slowva.player.player.sound.AutoEqProfile
import com.slowva.player.player.sound.OutputDevices
import com.slowva.player.player.sound.SoundSettings
import com.slowva.player.ui.ChipRow
import com.slowva.player.ui.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The device layer: what plays the sound, its AutoEQ correction and crossfeed. Saved per device. */
@Composable
fun HeadphonesSection() {
    val dev by OutputDevices.current.collectAsState()
    val preset by SoundSettings.device.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            readProfile(context, uri).fold(
                onSuccess = { p ->
                    SoundSettings.setDevice { it.copy(eqOn = true, bands = p.bands, preampDb = p.preampDb, eqName = name) }
                    message = "Loaded ${p.bands.size} filters"
                },
                onFailure = { message = it.message ?: "Could not read that file" },
            )
        }
    }
    val name = dev?.name ?: "No output"
    val summary = buildList {
        add(name)
        if (preset.eqOn && preset.bands.isNotEmpty()) add("AutoEQ on")
        preset.crossfeed?.let { add("crossfeed") }
    }.joinToString(" · ")

    Section(Icons.Default.Headphones, "Headphones", summary) {
        Text(
            "Now playing on: $name${dev?.let { if (it.name != it.kind) " (${it.kind})" else "" } ?: ""}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (preset.bands.isEmpty()) "AutoEQ: none imported" else "AutoEQ: ${preset.eqName ?: "imported"}, ${preset.bands.size} filters",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            Switch(
                checked = preset.eqOn && preset.bands.isNotEmpty(), enabled = preset.bands.isNotEmpty(),
                onCheckedChange = { on -> SoundSettings.setDevice { it.copy(eqOn = on) } },
                modifier = Modifier.semantics { contentDescription = "AutoEQ correction" },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }) {
                Text(if (preset.bands.isEmpty()) "Import ParametricEQ.txt" else "Replace")
            }
            Spacer(Modifier.width(8.dp))
            if (preset.bands.isNotEmpty()) {
                TextButton(onClick = { SoundSettings.setDevice { it.copy(eqOn = false, bands = emptyList(), eqName = null, preampDb = 0f) } }) {
                    Text("Remove")
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        Text("Crossfeed", style = MaterialTheme.typography.bodyMedium)
        ChipRow {
            FilterChip(selected = preset.crossfeed == null, onClick = { SoundSettings.setDevice { it.copy(crossfeed = null) } }, label = { Text("Off") })
            CrossfeedPreset.entries.forEach { c ->
                FilterChip(
                    selected = preset.crossfeed == c,
                    onClick = { SoundSettings.setDevice { it.copy(crossfeed = c) } },
                    label = { Text(c.label) },
                )
            }
        }
        Text(
            "Get the file for your model from the AutoEQ project. Correction and crossfeed switch on " +
                "by themselves whenever this device connects; presets never change them.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun readProfile(context: Context, uri: Uri): Result<AutoEqProfile> = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(AutoEq.MAX_BYTES + 1)
            var n = 0
            while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
            buf.copyOf(n)
        } ?: error("Can't open that file")
        require(bytes.size <= AutoEq.MAX_BYTES) { "That file is too large for an AutoEQ profile" }
        String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }.mapCatching { AutoEq.parse(it).getOrThrow() }
}
