package com.slowva.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.SlowvaApp
import com.slowva.player.data.HistoryPrefs
import com.slowva.player.data.HistoryPrefs.Key
import com.slowva.player.ui.RecentSearches

/** Home's history shelves, what gets remembered, and clearing it. */
@Composable
fun HistorySettings(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as SlowvaApp
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier) {
        Spacer(Modifier.height(8.dp))
        Toggle(Key.SHOW_RECENT, "Recently played on Home", "A row of the songs you played last")
        Toggle(Key.SHOW_MOST, "Most played on Home", "A row of the songs you play most")
        Spacer(Modifier.height(8.dp))
        Toggle(Key.KEEP_PLAYS, "Remember songs I play", "Off: nothing new is saved, and the two rows stop changing. Sorting by Most played uses what is already saved.")
        Toggle(Key.KEEP_SEARCHES, "Remember searches", "Shows your last searches under the search box", onOff = { RecentSearches.clear(ctx) })
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = { confirmClear = true }) { Text("Clear all history") }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all history?") },
        text = { Text("Removes songs you played and searches. Your songs, favorites, ratings and playlists stay.") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                app.repository.clearHistory()
                RecentSearches.clear(ctx)
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Toggle(key: Key, title: String, subtitle: String, onOff: () -> Unit = {}) {
    val on by HistoryPrefs.flow(key).collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(on, role = Role.Switch) { HistoryPrefs.set(key, it); if (!it) onOff() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
