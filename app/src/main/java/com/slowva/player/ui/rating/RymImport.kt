package com.slowva.player.ui.rating

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slowva.player.data.RymResult
import com.slowva.player.data.importRym
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Settings row: picks a RateYourMusic export and imports its album ratings and reviews. */
@Composable
fun RymImportRow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = try {
                summary(importRym(ctx, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }
    Column(modifier) {
        FilledTonalButton(
            enabled = !busy,
            onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
        ) { Text(if (busy) "Importing…" else "Import RateYourMusic export (CSV)") }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private fun summary(r: RymResult): String = buildString {
    append("Imported ${r.imported} album ratings")
    if (r.reviews > 0) append(" and ${r.reviews} reviews")
    append(". ${r.matched} match albums on this phone")
    if (r.likedArtists > 0) append(", ${r.likedArtists} artists marked as liked")
    append(".")
    if (r.skipped > 0) append(" Skipped ${r.skipped} unrated or incomplete lines.")
}
