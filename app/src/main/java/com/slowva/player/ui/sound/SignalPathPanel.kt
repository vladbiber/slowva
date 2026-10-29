package com.slowva.player.ui.sound

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.sound.OutputDevices
import com.slowva.player.player.sound.SignalPath
import com.slowva.player.player.sound.SoundSettings
import kotlin.math.abs

/** Source, decoder output, active stages, output device and rate, resampling, USB bit-perfect. */
@OptIn(UnstableApi::class)
@Composable
fun SignalPathPanel() {
    var format by remember { mutableStateOf<Format?>(null) }
    val remote = PlayerHub.remote
    DisposableEffect(remote) {
        val c = remote
        val l = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { format = audioFormat(tracks) }
        }
        c?.addListener(l)
        format = c?.let { audioFormat(it.currentTracks) }
        onDispose { c?.removeListener(l) }
    }
    LaunchedEffect(Unit) { OutputDevices.refresh() }
    val decoder by SignalPath.decoderOut.collectAsState()
    val dev by OutputDevices.current.collectAsState()
    val outRate by OutputDevices.outputRate.collectAsState()
    val bpFormats by OutputDevices.bitPerfectFormats.collectAsState()
    val bpError by SoundSettings.bitPerfectError.collectAsState()
    val h by SoundSettings.hifi.collectAsState()
    val rate by PlayerHub.rate.collectAsState()
    // read so the stage list recomposes when any of these change
    SoundSettings.eq.collectAsState().value; SoundSettings.device.collectAsState().value
    SoundSettings.genreKey.collectAsState().value; SoundSettings.loudnessRow.collectAsState().value
    PlayerHub.reverb.collectAsState().value; PlayerHub.bassGain.collectAsState().value
    PlayerHub.vinyl.collectAsState().value; PlayerHub.rain.collectAsState().value
    PlayerHub.width.collectAsState().value; PlayerHub.pan8d.collectAsState().value; PlayerHub.muffle.collectAsState().value
    val stages = SoundSettings.activeStages()

    Sub("Signal path")
    Column(Modifier.fillMaxWidth()) {
        Line("Source", format?.let(::describeSource) ?: "nothing playing")
        Line(
            "Decoded",
            decoder?.let { d ->
                "${khz(d.sampleRate)} · ${pcmName(d.encoding)} · ${channels(d.channels)}" +
                    if (!d.processed) " · plays untouched" else ""
            } ?: "-",
        )
        Line("Stages", if (stages.isEmpty()) "none, true bypass" else stages.joinToString(", "))
        Line("Output", dev?.let { "${it.name} (${it.kind})" } ?: "-")
        Line("Output rate", outRate?.let { khz(it) } ?: "unknown")
        val resample = buildList {
            if (abs(rate - 1f) > 0.001f) add("speed changes the rate")
            val src = decoder?.sampleRate
            if (src != null && outRate != null && src != outRate && !h.bitPerfect) add("the mixer converts ${khz(src)} to ${khz(outRate!!)}")
        }
        Line("Resampling", if (resample.isEmpty()) "none" else resample.joinToString("; "))
    }

    if (bpFormats.isNotEmpty()) {
        Sub("USB bit-perfect")
        Toggle("Bit-perfect to the DAC", h.bitPerfect, enabled = h.bitPerfect || stages.isEmpty()) { on ->
            SoundSettings.setHifi { it.copy(bitPerfect = on) }
        }
        Text(
            if (stages.isEmpty()) "The DAC gets the samples unchanged and controls the volume. Any effect turns this off."
            else "Turn every stage off first (Studio flat does that).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Rates offered: " + bpFormats.map { it.sampleRate }.distinct().sorted().joinToString(", ") { khz(it) },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        bpError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
    }
}

@OptIn(UnstableApi::class)
private fun audioFormat(tracks: Tracks): Format? =
    tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g ->
        (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) }
    }

@OptIn(UnstableApi::class)
private fun describeSource(f: Format): String = buildList {
    add(codecName(f.sampleMimeType ?: f.containerMimeType))
    if (f.sampleRate != Format.NO_VALUE) add(khz(f.sampleRate))
    if (f.pcmEncoding != Format.NO_VALUE) add(pcmName(f.pcmEncoding))
    if (f.channelCount != Format.NO_VALUE) add(channels(f.channelCount))
    val br = if (f.averageBitrate != Format.NO_VALUE) f.averageBitrate else f.bitrate
    if (br != Format.NO_VALUE && br > 0) add("${br / 1000} kbps")
}.joinToString(" · ")

private fun codecName(mime: String?): String = when (mime) {
    null -> "unknown"
    "audio/mpeg" -> "MP3"
    "audio/mp4a-latm" -> "AAC"
    "audio/flac" -> "FLAC"
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    "audio/raw" -> "PCM"
    "audio/alac" -> "ALAC"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    else -> mime.removePrefix("audio/").uppercase()
}

@OptIn(UnstableApi::class)
private fun pcmName(enc: Int): String = when (enc) {
    C.ENCODING_PCM_8BIT -> "8-bit"
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit"
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit"
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit"
    C.ENCODING_PCM_FLOAT -> "32-bit float"
    else -> "encoded"
}

private fun channels(n: Int) = when (n) { 1 -> "mono"; 2 -> "stereo"; else -> "$n channels" }

private fun khz(hz: Int): String {
    val k = hz / 1000f
    return if (k == k.toInt().toFloat()) "${k.toInt()} kHz" else "%.1f kHz".format(k)
}
