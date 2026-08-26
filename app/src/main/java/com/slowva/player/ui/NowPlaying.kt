package com.slowva.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.slowva.player.player.PlayerHub
import kotlin.math.roundToInt

@Composable
fun FullPlayer(onClose: () -> Unit) {
    val state by PlayerHub.playback.collectAsState()
    val rate by PlayerHub.rate.collectAsState()
    val reverb by PlayerHub.reverb.collectAsState()
    val localId by PlayerHub.localDeviceId.collectAsState()
    val connecting by PlayerHub.connecting.collectAsState()
    val track = state?.track
    var dragging by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0f) }
    LaunchedEffect(state?.progressMs) {
        if (!dragging) seekPos = (state?.progressMs ?: 0).toFloat()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "close")
                    }
                    Spacer(Modifier.fillMaxWidth().weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = track?.art,
                    contentDescription = "album artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .widthIn(max = 480.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    track?.name ?: "nothing playing",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    track?.artist.orEmpty(),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))
                Slider(
                    value = seekPos,
                    onValueChange = { dragging = true; seekPos = it },
                    onValueChangeFinished = {
                        dragging = false
                        PlayerHub.seek(seekPos.roundToInt())
                    },
                    valueRange = 0f..((track?.durationMs ?: 1).toFloat())
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        formatMs(seekPos.roundToInt()), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatMs(track?.durationMs ?: 0), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { PlayerHub.toggleShuffle() }) {
                        Icon(
                            Icons.Default.Shuffle, contentDescription = "shuffle",
                            tint = if (state?.shuffle == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { PlayerHub.previous() }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "previous",
                            modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { PlayerHub.togglePlay() }) {
                        Icon(
                            if (state?.isPlaying == true) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = "play/pause",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                    IconButton(onClick = { PlayerHub.next() }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "next",
                            modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { PlayerHub.cycleRepeat() }) {
                        Icon(
                            Icons.Default.Repeat, contentDescription = "repeat",
                            tint = if (state?.repeat != "off") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                // the two bars that matter, right under the transport
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "slow ${"%.2f".format(rate)}x",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MarkedSlider(
                        value = rate,
                        onValueChange = { PlayerHub.setRate(it) },
                        valueRange = 0.5f..1.25f,
                        default = 1f,
                    )
                    Text(
                        "reverb ${(reverb * 100).roundToInt()}%",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MarkedSlider(
                        value = reverb,
                        onValueChange = { PlayerHub.setReverb(it) },
                        valueRange = 0f..1f,
                        default = 0f,
                    )
                }

                Spacer(Modifier.height(12.dp))
                val onPhone = localId != null && state?.deviceId == localId
                if (onPhone && track != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = track.art,
                                contentDescription = "album artwork",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(62.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.outline),
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "ALBUM",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                )
                                Text(
                                    track.album,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    track.artist,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = {
                            PlayerHub.connectLocal(transferPlayback = true, startPlaying = true)
                        },
                        enabled = !connecting,
                    ) {
                        Text(if (connecting) "Connecting…" else "Connect to this phone")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { PlayerHub.pairFromSpotify() },
                        enabled = !connecting,
                    ) {
                        Text("Pair via Spotify Devices")
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
