package com.slowva.player.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.slowva.player.listenbrainz.ListenBrainz
import kotlinx.coroutines.launch

/** ListenBrainz token and scrobbling switch, and the slot for MusicBrainz ratings sync. */
@Composable
fun ConnectionsSection() {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val lb by ListenBrainz.state.collectAsState()
    var token by remember { mutableStateOf(lb.token) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text(
        "Your user token is on listenbrainz.org/settings. Paste it here; it stays on this phone and is left out of backups.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Open listenbrainz.org/settings",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { runCatching { uri.openUri("https://listenbrainz.org/settings/") } }
            .padding(vertical = 12.dp),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it.trim(); error = null },
        label = { Text("User token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            enabled = !checking && token.isNotBlank(),
            onClick = {
                checking = true
                scope.launch {
                    error = ListenBrainz.validate(token).exceptionOrNull()?.let { it.message ?: "Couldn't reach ListenBrainz" }
                    checking = false
                }
            },
        ) { Text(if (checking) "Checking…" else "Validate") }
        if (lb.token.isNotEmpty()) TextButton(onClick = { ListenBrainz.disconnect(); token = "" }) { Text("Remove") }
    }
    val status = when {
        error != null -> error!!
        lb.user != null -> "Connected as ${lb.user}" + if (lb.queued > 0) " · ${lb.queued} waiting to send" else ""
        else -> "Not connected"
    }
    Text(status, style = MaterialTheme.typography.bodyMedium, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    ToggleRow("Send what I listen to", "Each song counts after half of it or 4 minutes.", lb.sending, lb.user != null, ListenBrainz::setSending)
    Spacer(Modifier.height(8.dp))
    ToggleRow("MusicBrainz account (ratings sync)", "Coming later", checked = false, enabled = false, onChange = {})
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val c = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = c)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
