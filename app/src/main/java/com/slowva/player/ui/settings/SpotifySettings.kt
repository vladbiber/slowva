package com.slowva.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.slowva.player.spotify.Spotify
import com.slowva.player.spotify.SpotifyAuth
import com.slowva.player.spotify.SpotifyCheck
import com.slowva.player.spotify.SpotifyLibrary
import com.slowva.player.spotify.SpotifyLogin
import com.slowva.player.spotify.SpotifyNative
import com.slowva.player.ui.spotify.LoginWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spotify: your own app's Client ID first, then sign-in, quality and a self-test with the helpers' log. */
@Composable
internal fun SpotifySection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val signedIn by Spotify.signedIn.collectAsState()
    val personal by Spotify.personalSignedIn.collectAsState()
    val clientId by Spotify.clientId.collectAsState()
    val busy by SpotifyLogin.busy.collectAsState()
    val bitrate by Spotify.bitrate.collectAsState()
    val lib by SpotifyLibrary.state.collectAsState()
    val web by SpotifyLogin.webRequest.collectAsState()
    val message by SpotifyLogin.message.collectAsState()

    web?.let { LoginWebView(it, onCode = SpotifyLogin::onCode, onError = SpotifyLogin::onError, onCancel = SpotifyLogin::cancel) }
    MessageDialog(message) { SpotifyLogin.message.value = null }

    Text("Spotify", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Spotify songs play like your own files, through the same effects, queue and lyrics. Needs Spotify Premium " +
            "and, like Slowva 2, a Spotify app of your own: the shared one is always rate limited.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )

    SettingLabel("Step 1 · Your Client ID", Modifier.padding(top = 12.dp))
    ClientIdSetup()

    SettingLabel("Step 2 · Sign in", Modifier.padding(top = 12.dp))
    val playback = if (signedIn) "Playback: signed in" else "Playback: not signed in"
    val api = when {
        clientId.isEmpty() -> "Search and library: need your Client ID (step 1)"
        personal -> "Search and library: signed in with your Client ID"
        else -> "Search and library: not signed in yet"
    }
    val library = when {
        busy -> "Signing in…"
        !signedIn -> null
        lib.loading -> "Loading your library…"
        lib.error != null -> lib.error
        lib.loaded -> "${lib.liked.size} liked songs, ${lib.playlists.size} playlists, ${lib.albums.size} albums"
        else -> null
    }
    listOfNotNull(playback, api, library).forEach {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val missing = !signedIn || (clientId.isNotEmpty() && !personal)
    if (missing) Text(
        "Spotify asks you to approve twice: once for playback, once for your own app. If the browser never comes back, sign in inside the app.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (missing) {
            Button(enabled = !busy, onClick = { SpotifyLogin.inBrowser(ctx) }) { Text("Sign in") }
            TextButton(enabled = !busy, onClick = { SpotifyLogin.inApp() }) { Text("Sign in inside the app") }
        }
        if (signedIn) {
            FilledTonalButton(enabled = !lib.loading, onClick = { scope.launch { SpotifyLibrary.load(force = true) } }) { Text("Reload library") }
            OutlinedButton(onClick = Spotify::signOut) { Text("Sign out") }
        }
    }

    SettingLabel("Streaming quality", Modifier.padding(top = 8.dp))
    Choice(Spotify.BITRATES, bitrate, { "$it kbps" }, Spotify::setBitrate)
    Text("Lower uses less data. 320 kbps is about 2.4 MB a minute.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

    SpotifyDiagnostics()
}

/** The personal Client ID, as in slowva 2's setup screen; Web API calls use its token before the session's. */
@Composable
private fun ClientIdSetup() {
    val uri = LocalUriHandler.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val saved by Spotify.clientId.collectAsState()
    var editing by remember(saved) { mutableStateOf(saved.isEmpty()) }
    var draft by remember(saved) { mutableStateOf(saved) }
    val valid = draft.isEmpty() || draft.matches(Regex("[A-Za-z0-9]{32}"))

    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Client ID ${saved.take(6)}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = true }) { Text("Change") }
        }
        return
    }
    Text(
        "Search, your library and Home use the Web API. Spotify's shared app answers that it is busy almost every time, " +
            "so Slowva asks through an app of your own. Development mode allows five users and needs Premium. It takes two minutes:",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Step("1", "Create an app on Spotify for Developers with Web API access.")
    Text(
        "Open developer.spotify.com/dashboard",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { runCatching { uri.openUri("https://developer.spotify.com/dashboard") } }.padding(vertical = 12.dp),
    )
    Step("2", "Add this exact Redirect URI in the app's settings (tap to copy):")
    Text(
        SpotifyAuth.REDIRECT_URI, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "copy") {
            clipboard.setText(AnnotatedString(SpotifyAuth.REDIRECT_URI))
        }.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(12.dp),
    )
    Step("3", "Paste the app's Client ID here. Never the Client Secret.")
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it.filterNot(Char::isWhitespace).take(32) },
        label = { Text("Client ID") },
        supportingText = { Text(if (valid) "32 letters and digits" else "A Client ID has 32 letters and digits") },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(enabled = valid && draft.isNotEmpty() && draft != saved, onClick = { Spotify.setClientId(draft) }) { Text("Save") }
        if (saved.isNotEmpty()) TextButton(onClick = { draft = saved; editing = false }) { Text("Cancel") }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(top = 10.dp)) {
        Text("$n.", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Runs [SpotifyCheck] and shows the helpers' log, like slowva 2's librespot.log card. */
@Composable
private fun SpotifyDiagnostics() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    var log by remember { mutableStateOf<String?>(null) }

    SettingLabel("Spotify diagnostics", Modifier.padding(top = 12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(enabled = !running, onClick = {
            running = true
            scope.launch { result = SpotifyCheck.run(ctx); running = false }
        }) { Text(if (running) "Testing…" else "Test Spotify") }
        OutlinedButton(onClick = {
            scope.launch { log = if (log == null) withContext(Dispatchers.IO) { SpotifyNative.tail(ctx) } else null }
        }) { Text(if (log == null) "Show log" else "Hide log") }
        if (log != null) TextButton(onClick = { SpotifyNative.clearLog(ctx); log = "(empty)" }) { Text("Clear log") }
    }
    result.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
    log?.let {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .horizontalScroll(rememberScrollState()).padding(10.dp),
        ) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}
