package com.slowva.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.slowva.player.ui.home.HomePage
import com.slowva.player.ui.songs.SongSheets
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.SlowvaApp
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.PlaylistRecency
import com.slowva.player.data.Track
import com.slowva.player.ui.songs.SongActions
import com.slowva.player.ui.songs.Snack
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceDark
import com.slowva.player.ui.theme.SurfaceElevated
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary

/** Bottom sheet that adds [tracks] to a playlist: from a song row, the player, the song sheet or the selection bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(tracks: List<Track>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceDark) {
        Text(
            if (tracks.size == 1) "Add to playlist" else "Add ${tracks.size} songs to a playlist",
            color = TextPrimary, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(Modifier.verticalScroll(rememberScrollState())) { PlaylistRows(tracks, onDone = onDismiss) }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * "New playlist", then every playlist with the last used first; a tap adds [tracks] and closes.
 * Playlists that already hold them all are checked and left alone.
 */
@Composable
fun ColumnScope.PlaylistRows(tracks: List<Track>, onDone: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as SlowvaApp).repository
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val ordered = remember(playlists) { PlaylistRecency.order(playlists) }
    Row(
        Modifier.fillMaxWidth().clickable { showCreate = true }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlaylistAdd, null, tint = Accent)
        Spacer(Modifier.width(12.dp))
        Text("New playlist", color = Accent, fontWeight = FontWeight.SemiBold)
    }
    if (playlists.size > 8) SearchField(filter, { filter = it }, "Find a playlist", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    val q = filter.trim()
    ordered.filter { q.isEmpty() || it.name.contains(q, true) }.forEach { pl ->
        val already = tracks.all { it.id in pl.trackIds }
        Row(
            Modifier.fillMaxWidth().clickable { SongActions.addToPlaylist(repo, pl.name, tracks); onDone() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.QueueMusic, null, tint = TextSecondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pl.name, color = TextPrimary)
                Text(if (already) "Already in it" else songCount(pl.trackIds.size), color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (already) Icon(Icons.Filled.Check, "already in playlist", tint = Accent)
        }
    }
    if (showCreate) CreatePlaylistDialog(repo, addTracks = tracks, onDismiss = { showCreate = false; onDone() })
}

/** Making a playlist from scratch: it is created right away and opened, its name ready to type over. */
object NewPlaylist {
    /** The playlist just made; its page opens with the name field focused. */
    val fresh = MutableStateFlow<String?>(null)

    /** "New playlist", then "New playlist 2", 3... whichever is free. */
    fun freeName(repo: MusicRepository): String {
        val taken = repo.playlists.value.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) "New playlist" else "New playlist $it" }.first { it !in taken }
    }

    /** Creates an empty playlist and opens it. */
    fun open(repo: MusicRepository) {
        val name = freeName(repo)
        repo.createPlaylist(name)
        PlaylistRecency.touch(name)
        fresh.value = name
        SongSheets.goTo.value = HomePage.Playlist(name)
    }
}

/** Dialog to create a playlist holding [addTracks]; the name starts as a free "New playlist N", selected. */
@Composable
fun CreatePlaylistDialog(repo: MusicRepository, addTracks: List<Track> = emptyList(), onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(NewPlaylist.freeName(repo).let { TextFieldValue(it, TextRange(0, it.length)) }) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val create = {
        val n = name.text.trim()
        if (n.isNotEmpty()) {
            repo.remember(addTracks)
            repo.createPlaylistWithTracks(n, addTracks.map { it.id })
            PlaylistRecency.touch(n)
            Snack.show(if (addTracks.isEmpty()) "Created $n" else "Added to $n")
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = name.text.isNotBlank(), onClick = create) { Text("Create", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        title = { Text("New playlist", color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text("Playlist name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        containerColor = SurfaceElevated,
    )
}

/** Dialog to rename an existing playlist. */
@Composable
fun RenamePlaylistDialog(currentName: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onRename(name); onDismiss() },
            ) { Text("Rename", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        title = { Text("Rename playlist", color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text("Playlist name") },
            )
        },
        containerColor = SurfaceElevated,
    )
}
