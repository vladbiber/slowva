package com.slowva.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import com.slowva.player.player.PlayerHub

private enum class Tab(val label: String) { LIBRARY("Library"), SEARCH("Search"), FX("Sound") }

@Composable
fun AppRoot(vm: AppViewModel) {
    val loggedIn by vm.loggedIn.collectAsState()
    val message by PlayerHub.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            PlayerHub.say(null)
        }
    }

    val authRequest by vm.authRequest.collectAsState()
    if (authRequest != null) {
        LoginWebView(
            request = authRequest!!,
            onCode = { vm.onLoginCode(it) },
            onError = { vm.onLoginError(it) },
            onCancel = { vm.cancelLogin() },
        )
    }

    if (!loggedIn) {
        LoginScreen(vm, snackbar)
        return
    }

    var tab by remember { mutableStateOf(Tab.LIBRARY) }
    var showPlayer by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                MiniPlayer(onOpen = { showPlayer = true })
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = {
                                Icon(
                                    when (entry) {
                                        Tab.LIBRARY -> Icons.Default.LibraryMusic
                                        Tab.SEARCH -> Icons.Default.Search
                                        Tab.FX -> Icons.Default.GraphicEq
                                    },
                                    contentDescription = entry.label
                                )
                            },
                            label = { Text(entry.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            when (tab) {
                Tab.LIBRARY -> LibraryScreen(vm)
                Tab.SEARCH -> SearchScreen(vm)
                Tab.FX -> FxScreen(vm)
            }
            if (busy) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    // an in-window overlay, not a Dialog: dialogs get no system bar insets
    if (showPlayer) {
        BackHandler { showPlayer = false }
        PlayerContent(onClose = { showPlayer = false })
    }
    }
}

@Composable
private fun LoginScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val busy by vm.busy.collectAsState()
    val clientId by vm.apiClientId.collectAsState()
    if (clientId.isBlank()) {
        SpotifySetupScreen(vm, snackbar)
        return
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF15121C), MaterialTheme.colorScheme.background)
                    )
                )
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("slowva", fontSize = 44.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Spotify slowed down, with reverb, bass and odd tunings — " +
                    "processed locally, on your phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = { vm.login() }, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text("Connect your Spotify account")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Trouble? Sign in inside the app",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { vm.loginInApp() }
                    .padding(8.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Spotify Premium is required: playback runs through a local player " +
                    "(librespot), otherwise Spotify never sends any audio.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Client ID ${clientId.take(6)}…  ·  change",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                modifier = Modifier.clickable { vm.saveApiClientId("") }.padding(8.dp),
            )
        }
    }
}

@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val state by PlayerHub.playback.collectAsState()
    val track = state?.track ?: return
    val playing = state?.isPlaying == true
    val progress = (state?.progressMs ?: 0).toFloat() / track.durationMs.coerceAtLeast(1)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "open player", onClick = onOpen)
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { PlayerHub.togglePlay() }) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play"
                    )
                }
                IconButton(onClick = { PlayerHub.next() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next track")
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Shared list padding so the mini player never covers the last row. */
val listPadding = PaddingValues(bottom = 16.dp)
