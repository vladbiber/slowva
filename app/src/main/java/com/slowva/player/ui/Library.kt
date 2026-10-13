package com.slowva.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.slowva.player.api.Album
import com.slowva.player.api.Playlist
import com.slowva.player.api.Source
import com.slowva.player.api.Track
import com.slowva.player.player.PlayerHub

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
    var menu by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        full {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Library", Modifier.weight(1f))
                IconButton(onClick = { vm.loadLibrary() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload library")
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; vm.logout() })
                    }
                }
            }
        }
        full {
            LikedCard(if (liked.isEmpty()) "Your saved tracks" else "${liked.size} tracks") { vm.open(Source.Liked) }
        }
        if (playlists.isNotEmpty()) {
            full { SectionHeader("Playlists") }
            items(playlists, key = { "p" + it.id }) { p -> PlaylistCard(p) { vm.open(Source.OfPlaylist(p)) } }
        }
        if (albums.isNotEmpty()) {
            full { SectionHeader("Albums") }
            items(albums, key = { "a" + it.id }) { a -> AlbumCard(a) { vm.open(Source.OfAlbum(a)) } }
        }
        if (playlists.isEmpty() && albums.isEmpty()) {
            full {
                Text(
                    "Playlists and albums show up here once Spotify answers. Pull them in with the reload button.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        full { Spacer(Modifier.height(8.dp)) }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(vertical = 6.dp).semantics { heading() },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp).semantics { heading() },
    )
}

@Composable
private fun LikedCard(subtitle: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(c.secondary.copy(alpha = 0.85f), c.primary.copy(alpha = 0.6f))))
            .clickable(role = Role.Button, onClickLabel = "open", onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.onPrimary.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Favorite, contentDescription = null, tint = c.onBackground) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Liked songs", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.onBackground)
            Text(subtitle, fontSize = 13.sp, color = c.onBackground.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun PlaylistCard(p: Playlist, onClick: () -> Unit) =
    MediaCard(p.name, p.owner, p.art, Icons.AutoMirrored.Filled.PlaylistPlay, "Playlist", onClick)

@Composable
private fun AlbumCard(a: Album, onClick: () -> Unit) =
    MediaCard(a.name, a.artist, a.art, Icons.Default.Album, "Album", onClick)

@Composable
private fun MediaCard(title: String, subtitle: String, art: String?, icon: ImageVector, kind: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "open", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$kind $title" + if (subtitle.isNotBlank()) ", by $subtitle" else ""
            }
            .padding(4.dp),
    ) {
        Cover(art, icon, Modifier.fillMaxWidth().aspectRatio(1f), 14.dp)
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Cover(art: String?, icon: ImageVector, modifier: Modifier, corner: androidx.compose.ui.unit.Dp) {
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        if (art != null) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TrackListScreen(vm: AppViewModel, source: Source) {
    val tracks by vm.openTracks.collectAsState()
    val error by vm.openError.collectAsState()
    val playback by PlayerHub.playback.collectAsState()
    BackHandler { vm.closeSource() }
    val art = when (source) {
        is Source.OfPlaylist -> source.playlist.art
        is Source.OfAlbum -> source.album.art
        else -> null
    }
    val sub = when (source) {
        is Source.OfPlaylist -> "Playlist · ${source.playlist.owner}"
        is Source.OfAlbum -> "Album · ${source.album.artist}"
        is Source.Liked -> "Your liked songs"
        is Source.SearchResults -> "Search"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.closeSource() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(
                    art, if (source is Source.Liked) Icons.Default.Favorite else Icons.Default.Album,
                    Modifier.size(180.dp), 18.dp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    source.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                )
                Text(
                    sub + if (tracks.isNotEmpty()) " · ${tracks.size} tracks" else "",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.play(tracks, 0) }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp)); Text("Play")
                    }
                    FilledTonalButton(
                        onClick = {
                            if (playback?.shuffle != true) PlayerHub.toggleShuffle()
                            vm.play(tracks, tracks.indices.random())
                        },
                        enabled = tracks.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp)); Text("Shuffle")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (tracks.isEmpty() && error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Could not open this list:\n$error", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { vm.open(source) }) { Text("Retry") }
                }
            }
        }
        itemsIndexed(tracks) { index, track ->
            TrackRow(track, index + 1, playing = playback?.track?.id == track.id) { vm.play(tracks, index) }
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
    var query by rememberSaveable { mutableStateOf("") }
    val tracks by vm.searchTracks.collectAsState()
    val albums by vm.searchAlbums.collectAsState()
    val playlists by vm.searchPlaylists.collectAsState()
    val playback by PlayerHub.playback.collectAsState()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        full { ScreenTitle("Search") }
        full {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Songs, albums, playlists") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                },
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search Spotify" },
            )
        }
        if (tracks.isEmpty() && albums.isEmpty() && playlists.isEmpty()) {
            full {
                Text(
                    "Type something and press search on the keyboard.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (tracks.isNotEmpty()) {
            full { SectionHeader("Songs") }
            tracks.forEachIndexed { index, track ->
                full {
                    TrackRow(track, null, playing = playback?.track?.id == track.id, padded = false) {
                        vm.closeSource(); vm.play(tracks, index)
                    }
                }
            }
        }
        if (albums.isNotEmpty()) {
            full { SectionHeader("Albums") }
            items(albums, key = { "sa" + it.id }) { a -> AlbumCard(a) { vm.open(Source.OfAlbum(a)) } }
        }
        if (playlists.isNotEmpty()) {
            full { SectionHeader("Playlists") }
            items(playlists, key = { "sp" + it.id }) { p -> PlaylistCard(p) { vm.open(Source.OfPlaylist(p)) } }
        }
    }
}

@Composable
private fun TrackRow(track: Track, number: Int?, playing: Boolean, padded: Boolean = true, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clearAndSetSemantics {
                contentDescription = "${track.name}, by ${track.artist}, ${formatMs(track.durationMs)}"
                if (playing) stateDescription = "now playing"
                role = Role.Button
                onClick(label = "play") { onClick(); true }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = if (padded) 16.dp else 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                if (playing) Icon(Icons.Default.GraphicEq, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                else Text("$number", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AsyncImage(
            model = track.art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                color = if (playing) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatMs(track.durationMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatMs(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
