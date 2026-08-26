package com.slowva.player.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slowva.player.auth.Auth

private const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"

/** One-time setup for public builds; a client secret is never needed or stored. */
@Suppress("DEPRECATION")
@Composable
fun SpotifySetupScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val busy by vm.busy.collectAsState()
    var draft by remember { mutableStateOf("") }
    val valid = draft.trim().matches(Regex("[A-Za-z0-9]{32}"))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF242033), Color(0xFF111018), Color(0xFF08080C))
                    )
                )
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = "slowva",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(88.dp)
                    .background(Color(0xFF17141F), CircleShape)
                    .padding(18.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Set up slowva", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Use your own Spotify Client ID so your library and API quota stay separate.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            SetupStep("1", "Open Spotify for Developers and create an app.")
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DASHBOARD_URL)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Spotify Dashboard") }

            Spacer(Modifier.height(14.dp))
            SetupStep("2", "Add this exact Redirect URI in the app settings:")
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { clipboard.setText(AnnotatedString(Auth.REDIRECT_URI)) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        Auth.REDIRECT_URI,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "COPY",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SetupStep("3", "Copy the app's Client ID and paste it below.")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.filterNot(Char::isWhitespace).take(32) },
                label = { Text("Spotify Client ID") },
                supportingText = {
                    Text(
                        if (draft.isEmpty() || valid) "32 characters · no Client Secret"
                        else "Client ID must be 32 characters"
                    )
                },
                isError = draft.isNotEmpty() && !valid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { vm.setupAndLogin(draft) },
                enabled = valid && !busy,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                }
                Text("Save & sign in", fontWeight = FontWeight.Bold)
            }
            Text(
                "Sign in inside the app instead",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(enabled = valid && !busy) { vm.setupAndLogin(draft, inApp = true) }
                    .padding(12.dp),
            )
            Text(
                "Spotify Premium is required for Development Mode and local playback.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SetupStep(number: String, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .padding(top = 3.dp),
        )
        Text(text, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
    }
}
