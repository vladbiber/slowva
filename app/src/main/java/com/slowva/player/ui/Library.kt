package com.slowva.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.slowva.player.api.Source
import com.slowva.player.api.Track

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val source by vm.openSource.collectAsState()
    if (source != null) {
        TrackListScreen(vm, source!!)
        return
    }
    val playlists by vm.playlists.collectAsState()
    val albums by vm.albums.collectAsState()
    val liked by vm.liked.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) { SectionHeader("Library") }
                Text(
                    "Reload",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { vm.loadLibrary() }
                        .padding(16.dp)
                )
            }
            RowEntry(
                title = "Liked songs",
                subtitle = "${liked.size} tracks",
                icon = Icons.Default.Favorite,
                onClick = { vm.open(Source.Liked) }
            )
        }
        if (playlists.isNotEmpty()) item { SectionHeader("Playlists") }
        items(playlists) { playlist ->
            RowEntry(
                title = playlist.name,
                subtitle = playlist.owner,
                art = playlist.art,
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                onClick = { vm.open(Source.OfPlaylist(playlist)) }
            )
        }
        if (albums.isNotEmpty()) item { SectionHeader("Albums") }
        items(albums) { album ->
            RowEntry(
                title = album.name,
                subtitle = album.artist,
                art = album.art,
                icon = Icons.Default.Album,
                onClick = { vm.open(Source.OfAlbum(album)) }
            )
        }
        item {
            Spacer(Modifier.height(24.dp))
            Text(
                "Sign out",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { vm.logout() }
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun TrackListScreen(vm: AppViewModel, source: Source) {
    val tracks by vm.openTracks.collectAsState()
    val error by vm.openError.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.closeSource() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text(
                source.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (tracks.isEmpty() && error != null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Could not open this ${if (source is Source.OfPlaylist) "playlist" else "album"}:\n$error",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.open(source) }) { Text("Retry") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                itemsIndexed(tracks) { index, track ->
                    TrackRow(track) { vm.play(tracks, index) }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(vm: AppViewModel) {
    val source by vm.openSource.collectAsState()
    if (source != null) {
        TrackListScreen(vm, source!!)
        return
    }
    var query by remember { mutableStateOf("") }
    val tracks by vm.searchTracks.collectAsState()
    val albums by vm.searchAlbums.collectAsState()
    val playlists by vm.searchPlaylists.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search tracks and albums") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
            if (tracks.isNotEmpty()) item { SectionHeader("Tracks") }
            itemsIndexed(tracks) { index, track ->
                TrackRow(track) {
                    vm.closeSource()
                    vm.play(tracks, index)
                }
            }
            if (albums.isNotEmpty()) item { SectionHeader("Albums") }
            items(albums) { album ->
                RowEntry(
                    title = album.name,
                    subtitle = album.artist,
                    art = album.art,
                    icon = Icons.Default.Album,
                    onClick = { vm.open(Source.OfAlbum(album)) }
                )
            }
            if (playlists.isNotEmpty()) item { SectionHeader("Playlists") }
            items(playlists) { playlist ->
                RowEntry(
                    title = playlist.name,
                    subtitle = playlist.owner,
                    art = playlist.art,
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    onClick = { vm.open(Source.OfPlaylist(playlist)) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun RowEntry(
    title: String,
    subtitle: String,
    icon: ImageVector,
    art: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            Text(
                track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            formatMs(track.durationMs),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun formatMs(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
