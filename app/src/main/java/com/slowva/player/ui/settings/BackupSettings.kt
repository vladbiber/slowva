package com.slowva.player.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.slowva.player.SlowvaApp
import com.slowva.player.data.Backup
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.system.exitProcess

/** Save everything to one file, or restore from one and restart. */
@Composable
internal fun ColumnScope.BackupSettings() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as SlowvaApp
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var restored by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            message = Backup.export(ctx, app.database, uri).fold(
                onSuccess = { "Backup saved." },
                onFailure = { "Backup failed: ${it.message}" },
            )
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> pendingRestore = uri }

    Text(
        "Saves your favorites, playlists, imported songs, sound and look settings in one file. " +
            "Imported songs and custom covers point to files on this phone, so after a reinstall you may need to import those again.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { exportLauncher.launch("slowva-backup-${LocalDate.now()}.zip") }) { Text("Save backup") }
        FilledTonalButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore") }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore backup?") },
            text = { Text("This replaces your current favorites, playlists and settings. Slowva will close afterwards; open it again.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        Backup.restore(ctx, app.database, uri).fold(
                            onSuccess = { restored = true },
                            onFailure = { message = "Restore failed: ${it.message}" },
                        )
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
    if (restored) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Backup restored") },
            text = { Text("Slowva will close now. Open it again to load your data.") },
            confirmButton = {
                TextButton(onClick = {
                    (ctx as? Activity)?.finishAffinity()
                    exitProcess(0)
                }) { Text("Close Slowva") }
            },
        )
    }
    MessageDialog(message) { message = null }
}
