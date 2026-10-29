package com.slowva.player.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slowva.player.player.SleepTimer
import com.slowva.player.ui.SectionHeader
import kotlinx.coroutines.delay

/** "in 23 minutes", "at the end of this track", or null when the sleep timer is off; ticks while shown. */
@Composable
internal fun sleepLeft(): String? {
    val at by SleepTimer.endsAt.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (at != null) { now = System.currentTimeMillis(); delay(1_000) } }
    return when (val a = at) {
        null -> null
        SleepTimer.END_OF_TRACK -> "at the end of this track"
        else -> SleepTimer.minutesLeft(a, now).let { "in $it minute${if (it == 1L) "" else "s"}" }
    }
}

/** Sleep timer: big pill choices, and while one runs a card with the time left and Cancel. */
@Composable
internal fun SleepSheet(onDone: () -> Unit) {
    val left = sleepLeft()
    val at by SleepTimer.endsAt.collectAsState()
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Sleep timer")
        if (left != null) Surface(color = c.primaryContainer, contentColor = c.onPrimaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Pausing $left", style = MaterialTheme.typography.titleMedium)
                    Text("Fades out first", style = MaterialTheme.typography.bodyMedium)
                }
                FilledTonalButton(onClick = { SleepTimer.cancel(); onDone() }) { Text("Cancel") }
            }
        } else Text(
            "Fades out, then pauses",
            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
        )
        Text(if (left != null) "Change it" else "Pause in", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(15, 30, 45, 60, 90).forEach { m -> Choice("$m min", selected = false) { SleepTimer.start(m); onDone() } }
            Choice("End of song", selected = at == SleepTimer.END_OF_TRACK) { SleepTimer.endOfTrack(); onDone() }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        selected = selected, onClick = onClick,
        color = if (selected) c.primary else c.surfaceContainerHigh,
        contentColor = if (selected) c.onPrimary else c.onSurface,
        shape = CircleShape,
        modifier = Modifier.heightIn(min = 56.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
    }
}
