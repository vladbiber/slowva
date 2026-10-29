package com.slowva.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.slowva.player.player.sound.SoundEngine
import com.slowva.player.recommend.HiddenArtists
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.sound.EqSection
import com.slowva.player.ui.sound.HeadphonesSection
import com.slowva.player.ui.sound.HifiSection

/** Headphones, hi-fi and EQ (the same as in the player's Sound sheet), and the artists kept out of suggestions. */
@Composable
internal fun ColumnScope.PlaybackSettings() {
    val context = LocalContext.current
    remember { SoundEngine.init(context) }
    Text(
        "Speed, reverb and the other effects are in the player, under Sound.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeadphonesSection()
        HifiSection()
        EqSection()
    }

    SectionHeader("Hidden artists", Modifier.padding(top = 24.dp, bottom = 4.dp))
    val hidden by HiddenArtists.names.collectAsState()
    Text(
        if (hidden.isEmpty()) "None. Use Hide artist in a recommended song's menu to keep an artist out of For you, genres and creators."
        else "Kept out of For you, recommendations, genres and creators.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    hidden.sortedBy { it.lowercase() }.forEach { name ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { HiddenArtists.unhide(name) }) { Text("Unhide") }
        }
    }
}
