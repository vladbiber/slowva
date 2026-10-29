package com.slowva.player.ui.library

import com.slowva.player.ui.mood.moodText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerHub

/** Plays [tracks] from the top, or shuffled from a random song. */
object PlayList {
    fun play(repo: MusicRepository, tracks: List<Track>, from: Int = 0) {
        if (tracks.isEmpty()) return
        repo.remember(tracks)
        PlayerHub.playQueue(tracks.map { it.id }, from)
    }

    fun shuffle(repo: MusicRepository, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (!PlayerHub.ui.value.shuffle) PlayerHub.toggleShuffle()
        play(repo, tracks, tracks.indices.random())
    }
}

/**
 * Play (filled) and Shuffle (tonal, same hue) for a playlist, album or artist header; [extra] adds buttons after them.
 * With no songs the two are left out rather than shown disabled.
 */
@Composable
fun PlayShuffleRow(tracks: List<Track>, repo: MusicRepository, modifier: Modifier = Modifier, extra: @Composable RowScope.() -> Unit = {}) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tracks.isNotEmpty()) {
            Button(onClick = { PlayList.play(repo, tracks) }) {
                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(moodText("Play"))
            }
            // primaryContainer, not the default secondary one, which is the other theme color in Two colors
            FilledTonalButton(
                onClick = { PlayList.shuffle(repo, tracks) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(moodText("Shuffle"))
            }
        }
        extra()
    }
}

/** True when [artist] (a song's artist line) names [name], also as one of several artists. */
fun artistMatches(artist: String, name: String): Boolean {
    val key = name.trim().lowercase().removeSuffix(" - topic")
    if (key.isEmpty()) return false
    return artist.split(Regex(",|&|/| x | feat\\.? | ft\\.? | with ", RegexOption.IGNORE_CASE))
        .any { it.trim().lowercase().removeSuffix(" - topic").trim() == key }
}
