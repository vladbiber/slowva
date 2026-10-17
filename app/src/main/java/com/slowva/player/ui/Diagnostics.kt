package com.slowva.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.auth.Auth
import com.slowva.player.auth.SessionToken
import com.slowva.player.player.PlayerHub
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything needed to tell why playback or the Web API is unhappy, without a
 * USB cable: what librespot is doing, whether it cached credentials, which
 * token the API calls carry, and what the token helper last complained about.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsCard(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("…") }
    val clientId by vm.apiClientId.collectAsState()
    val apiSignedIn by vm.apiSignedIn.collectAsState()
    var draftId by remember(clientId) { mutableStateOf(clientId) }

    fun refresh() {
        val local = PlayerHub.local
        val creds = SessionToken.credentialsFile(ctx)
        val log = File(ctx.cacheDir, "librespot.log")
        val tail = runCatching { log.readLines().takeLast(15).joinToString("\n") }
            .getOrDefault("(no log yet)")
        report = buildString {
            appendLine("librespot: ${local.status.value}" + if (local.isRunning) " (running)" else "")
            local.lastError.value?.let { appendLine("  error: $it") }
            appendLine("credentials: " + if (creds.exists()) "cached (${creds.length()} B)" else "MISSING")
            appendLine("web api token: ${Auth.tokenSummary(ctx)}")
            SessionToken.lastError?.let { appendLine("token helper: $it") }
            appendLine("device id: ${PlayerHub.localDeviceId.value ?: "not registered"}")
            append("log:\n$tail")
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Diagnostics", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                report,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
                OutlinedButton(onClick = {
                    scope.launch {
                        report = "minting…"
                        val result = SessionToken.mintDetailed(ctx)
                        refresh()
                        report = result.fold(
                            onSuccess = { (token, secs) ->
                                "session token OK: ${token.length} chars, ${secs}s\n\n$report"
                            },
                            onFailure = { "session token FAILED: ${it.message}\n\n$report" }
                        )
                    }
                }) { Text("Test session token") }
                OutlinedButton(onClick = {
                    PlayerHub.connectLocal()
                    scope.launch { refresh() }
                }) { Text("Start local player") }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text("Spotify Web API client ID", fontWeight = FontWeight.SemiBold)
            Text(
                "Public builds do not contain the maintainer's Client ID. To replace " +
                    "your current ID, create an app at developer.spotify.com with redirect " +
                    "http://127.0.0.1:5588/login and paste its client ID here. Web API " +
                    "calls then use your own quota instead of the shared one everything " +
                    "librespot-based is throttled on. Playback still signs in with " +
                    "Spotify's desktop id — it is the only one librespot accepts.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draftId,
                onValueChange = { draftId = it },
                singleLine = true,
                label = { Text("client ID") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.saveApiClientId(draftId) }) { Text("Save") }
                if (clientId.isNotBlank()) {
                    OutlinedButton(onClick = { vm.loginApi(inApp = false) }) {
                        Text(if (apiSignedIn) "Sign in again" else "Sign in with it")
                    }
                    OutlinedButton(onClick = { vm.loginApi(inApp = true) }) { Text("…in app") }
                }
            }
        }
    }
}
