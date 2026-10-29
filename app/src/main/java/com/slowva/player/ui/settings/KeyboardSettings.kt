package com.slowva.player.ui.settings

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slowva.player.keys.DeviceProfile
import com.slowva.player.keys.DeviceProfiles
import com.slowva.player.keys.KeyAction
import com.slowva.player.keys.KeyBindings
import com.slowva.player.keys.KeyCombo
import com.slowva.player.keys.KeyRouter
import com.slowva.player.keys.keyLabel
import com.slowva.player.ui.ScreenTitle
import com.slowva.player.ui.SectionHeader

fun keyName(c: KeyCombo): String = keyLabel(c, KeyEvent.keyCodeToString(c.code))

/** The entry in Settings. */
@Composable
fun KeyboardSettingsRow(onOpen: () -> Unit) {
    val on by KeyRouter.enabled.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open keyboard settings", onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Keyboard shortcuts", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (on) "Shortcuts on. Change keys and screen shape" else "Shortcuts off. Physical keyboard keys and screen shape",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val on by KeyRouter.enabled.collectAsState()
    val selected by DeviceProfiles.selected.collectAsState()
    val map by KeyBindings.map.collectAsState()
    var editing by remember { mutableStateOf<KeyAction?>(null) }
    var confirmResetAll by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                ScreenTitle("Keyboard")
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Switch) { KeyRouter.setEnabled(!on) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keyboard shortcuts", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Single keys control playback. Paused while you type in a text box.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = on, onCheckedChange = null)
            }

            SectionHeader("Device", Modifier.padding(top = 24.dp, bottom = 4.dp))
            Text(
                "Found: ${DeviceProfiles.detected.label.takeIf { DeviceProfiles.detected != DeviceProfile.OFF } ?: "regular phone"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceProfile.entries.forEach { p ->
                    FilterChip(
                        selected = p == selected,
                        onClick = { DeviceProfiles.select(p) },
                        label = { Text(if (p == DeviceProfile.AUTO) "Automatic (${DeviceProfiles.detected.label})" else p.label) },
                    )
                }
            }

            SectionHeader("Keys", Modifier.padding(top = 24.dp, bottom = 4.dp))
            KeyAction.entries.forEach { a ->
                val keys = map.keysFor(a)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(onClickLabel = "Change key for ${a.label}") { editing = a }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (keys.isEmpty()) "Not set" else keys.joinToString("  /  ") { keyName(it) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (keys.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { KeyBindings.reset(a) }) { Icon(Icons.Filled.Refresh, contentDescription = "Reset ${a.label}") }
                }
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { confirmResetAll = true }) { Text("Reset all keys") }
            Spacer(Modifier.height(32.dp))
        }
    }

    editing?.let { a -> CaptureDialog(a, onDone = { editing = null }) }
    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Reset all keys?") },
            text = { Text("Every action goes back to its default key.") },
            confirmButton = { TextButton(onClick = { confirmResetAll = false; KeyBindings.resetAll() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmResetAll = false }) { Text("Cancel") } },
        )
    }
}

/** Waits for the next key; offers a swap when another action already has it. */
@Composable
private fun CaptureDialog(action: KeyAction, onDone: () -> Unit) {
    var captured by remember { mutableStateOf<KeyCombo?>(null) }
    val map by KeyBindings.map.collectAsState()
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        KeyRouter.capturing = true
        onDispose { KeyRouter.capturing = false }
    }
    val combo = captured
    val clash = combo?.let { map.conflict(action, it) }
    if (combo != null && clash == null) {
        LaunchedEffect(combo) { KeyBindings.bind(action, combo); onDone() }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(action.label) },
        text = {
            if (clash != null) Text("${keyName(combo)} is already used for ${clash.label}. Swap them?")
            else Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        val code = e.key.nativeKeyCode
                        if (e.type != KeyEventType.KeyDown || KeyCombo.isModifier(code)) return@onPreviewKeyEvent false
                        // back still closes the dialog
                        if (code == KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                        captured = KeyCombo.of(code, e.nativeKeyEvent.metaState)
                        true
                    }
                    .focusable()
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Press a key…", style = MaterialTheme.typography.titleMedium)
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        },
        confirmButton = {
            if (clash != null) TextButton(onClick = { KeyBindings.bind(action, combo, swap = true); onDone() }) { Text("Swap") }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text("Cancel") }
        },
    )
}
