package com.slowva.player.ui.player

import com.slowva.player.ui.songCount
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.SlowvaApp
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerHub
import com.slowva.player.recommend.RadioMixer
import com.slowva.player.ui.theme.SurfaceDark
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.songs.SheetRow
import com.slowva.player.ui.songs.Snack
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongSheet
import com.slowva.player.ui.songs.SongTarget

/** The player's ⋮ menu for a song: the song sheet with the player's look, sleep timer and playlists added. */
@Composable
fun SongPlayerMenu(track: Track, onOpenPlaylist: (String) -> Unit, onDismiss: () -> Unit, onLook: () -> Unit, onSleep: () -> Unit) =
    SongSheet(SongTarget.Song(track), SongContext.Other, onDismiss, extraTop = {
        PlayerRows(onDismiss, onLook, onSleep)
        PlaylistsFor(track, onOpenPlaylist, onDismiss)
    })

/** Player look and sleep timer for the song menu. */
@Composable
private fun PlayerRows(onDismiss: () -> Unit, onLook: () -> Unit, onSleep: () -> Unit) {
    SheetRow(Icons.Filled.Palette, "Player look", "Skins and visualizers") { onDismiss(); onLook() }
    SheetRow(Icons.Filled.Bedtime, "Sleep timer", sleepLeft()?.let { "Pausing $it" } ?: "Fade out and pause later") { onDismiss(); onSleep() }
}

/** "In your playlists" (tap to open) and "Would fit in" (playlists with the same artist, tap to add). */
@Composable
private fun ColumnScope.PlaylistsFor(track: Track, onOpenPlaylist: (String) -> Unit, onDismiss: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as SlowvaApp).repository
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val library by repo.tracks.collectAsStateWithLifecycle()
    val (inIt, rest) = playlists.partition { track.id in it.trackIds }
    // shortcut: "fits" means another song by the same artist is in it; add per-song genres if this feels too narrow
    val key = RadioMixer.artistKey(track.artist)
    val artistOf = library.associate { it.id to RadioMixer.artistKey(it.artist) }
    val fits = if (key.isBlank()) emptyList() else rest.filter { pl -> pl.trackIds.any { artistOf[it] == key } }
    if (inIt.isNotEmpty()) {
        Heading("In your playlists")
        inIt.forEach { pl -> SheetRow(Icons.AutoMirrored.Filled.QueueMusic, pl.name, "${songCount(pl.trackIds.size)}") { onDismiss(); onOpenPlaylist(pl.name) } }
    }
    if (fits.isNotEmpty()) {
        Heading("Would fit in")
        fits.forEach { pl ->
            SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, pl.name, "Has other songs by ${track.artist}") {
                repo.addToPlaylist(pl.name, track.id)
                onDismiss()
                Snack.show("Added to ${pl.name}") { repo.removeFromPlaylist(pl.name, track.id) }
            }
        }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, color = TextMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
