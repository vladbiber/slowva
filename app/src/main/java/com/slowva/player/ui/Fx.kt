package com.slowva.player.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.RETUNE_PRESETS
import com.slowva.player.player.SLOW_STEPS
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun FxScreen(vm: AppViewModel) {
    val rate by PlayerHub.rate.collectAsState()
    val reverb by PlayerHub.reverb.collectAsState()
    val retune by PlayerHub.retuneIndex.collectAsState()
    val bassGain by PlayerHub.bassGain.collectAsState()
    val bassFreq by PlayerHub.bassFreq.collectAsState()
    val localId by PlayerHub.localDeviceId.collectAsState()
    val playback by PlayerHub.playback.collectAsState()
    val connecting by PlayerHub.connecting.collectAsState()
    val pairing by PlayerHub.pairing.collectAsState()
    val devices by PlayerHub.devices.collectAsState()

    val onLocal = localId != null && playback?.deviceId == localId

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (onLocal) "Playing on this phone — effects are live"
                    else "Effects only work on the local player",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Spotify gives no access to audio on other Connect devices, so " +
                        "slowva decodes the stream itself (librespot) and processes it here.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (pairing) {
                    Text(
                        "Spotify is open: tap Devices, choose ${PlayerHub.local.deviceName}, then return here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Column {
                    Button(
                        onClick = {
                            PlayerHub.connectLocal(transferPlayback = true, startPlaying = true)
                        },
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (onLocal) "Reconnect" else "Play on this phone")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { PlayerHub.pairFromSpotify() },
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pair via Spotify") }
                }
                Text(
                    "Pairing works like spotc: Spotify sends reusable player credentials through Connect.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (devices.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        devices.forEach { device ->
                            AssistChip(
                                onClick = { PlayerHub.transferTo(device) },
                                label = { Text(device.name, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Slow", fontWeight = FontWeight.SemiBold)
        Text(
            "${"%.2f".format(rate)}x  ·  ${semitones(rate)} semitones" +
                if (retune != 0) "  ·  includes ${RETUNE_PRESETS[retune].hz} Hz tuning" else "",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MarkedSlider(
            value = rate,
            onValueChange = { PlayerHub.setRate(it) },
            valueRange = 0.5f..1.25f,
            default = 1f,
        )

        Spacer(Modifier.height(10.dp))
        Text("Reverb", fontWeight = FontWeight.SemiBold)
        Text(
            "${(reverb * 100).roundToInt()}%",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MarkedSlider(
            value = reverb,
            onValueChange = { PlayerHub.setReverb(it) },
            valueRange = 0f..1f,
            default = 0f,
        )

        Spacer(Modifier.height(10.dp))
        Text("Bass boost", fontWeight = FontWeight.SemiBold)
        Text(
            "+${"%.1f".format(bassGain)} dB below ${bassFreq.roundToInt()} Hz",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MarkedSlider(
            value = bassGain,
            onValueChange = { PlayerHub.setBass(it, bassFreq) },
            valueRange = 0f..12f,
            default = 0f,
        )
        MarkedSlider(
            value = bassFreq,
            onValueChange = { PlayerHub.setBass(bassGain, it) },
            valueRange = 40f..320f,
            default = 120f,
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { PlayerHub.resetFx() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset to default") }

        Spacer(Modifier.height(16.dp))
        Text("Quick steps", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SLOW_STEPS.forEachIndexed { index, (stepRate, stepReverb) ->
                FilterChip(
                    selected = rate == stepRate && reverb == stepReverb,
                    onClick = {
                        PlayerHub.setRate(stepRate)
                        PlayerHub.setReverb(stepReverb)
                    },
                    label = { Text(if (index == 0) "off" else "$index", fontSize = 12.sp) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Tuning (\"magic Hz\")", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RETUNE_PRESETS.forEachIndexed { index, preset ->
                FilterChip(
                    selected = retune == index,
                    onClick = { PlayerHub.setRetune(index) },
                    label = {
                        Text(
                            if (index == 0) "440 off" else "${preset.hz} Hz",
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val preset = RETUNE_PRESETS[retune]
        Text(
            "${preset.note} → ${preset.hz} Hz · ${"%.3f".format(preset.ratio)}x — ${preset.claim}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "The nearest note is tuned onto the frequency shown, the way \"solfeggio\" " +
                "converters do. The claimed effects (DNA, healing, pineal gland) have no " +
                "support in research — they just sound different.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        DiagnosticsCard(vm)
        Spacer(Modifier.height(40.dp))
    }
}

/** How far the pitch drops at a given playback rate, in semitones. */
private fun semitones(rate: Float): String {
    val value = 12.0 * ln(rate.toDouble()) / ln(2.0)
    return "%+.1f".format(value)
}
