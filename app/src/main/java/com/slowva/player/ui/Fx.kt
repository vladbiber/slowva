package com.slowva.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.player.PlayerHub

@Composable
fun FxScreen(vm: AppViewModel) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Sound", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        ConnectionCard()
        SoundPanel()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = if (advanced) "hide" else "show") { advanced = !advanced }
                .semantics { stateDescription = if (advanced) "expanded" else "collapsed" }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced & diagnostics", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(advanced) { DiagnosticsCard(vm) }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ConnectionCard() {
    val localId by PlayerHub.localDeviceId.collectAsState()
    val playback by PlayerHub.playback.collectAsState()
    val connecting by PlayerHub.connecting.collectAsState()
    val pairing by PlayerHub.pairing.collectAsState()
    val devices by PlayerHub.devices.collectAsState()
    val onLocal = (localId != null && playback?.deviceId == localId) || playback?.deviceId == PlayerHub.LOCAL_FILE_DEVICE
    val ok = MaterialTheme.colorScheme.primary

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (onLocal) ok.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (onLocal) Icons.Default.PhoneAndroid else Icons.Default.Speaker,
                        contentDescription = null, tint = if (onLocal) ok else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (onLocal) "Playing on this phone" else "Effects need this phone",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (onLocal) "Every effect below is live."
                        else playback?.deviceName?.let { "Now on $it — Spotify gives no audio access there." }
                            ?: "Start playback here to hear the effects.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pairing) {
                Text(
                    "Spotify is open: tap Devices, choose ${PlayerHub.local.deviceName}, then return here.",
                    fontSize = 12.sp, color = ok,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { PlayerHub.connectLocal(transferPlayback = true, startPlaying = true) },
                    enabled = !connecting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (onLocal) "Reconnect" else "Play here")
                }
                OutlinedButton(
                    onClick = { PlayerHub.pairFromSpotify() },
                    enabled = !connecting,
                    modifier = Modifier.weight(1f),
                ) { Text("Pair via Spotify") }
            }
            if (devices.isNotEmpty()) {
                Text("Send to", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { device ->
                        AssistChip(onClick = { PlayerHub.transferTo(device) }, label = { Text(device.name) })
                    }
                }
            }
        }
    }
}
