package com.slowva.player.ui.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.slowva.player.ui.art.ArtistArt
import com.slowva.player.ui.art.coverTones
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.lastfm.LastFm
import com.slowva.player.recommend.HiddenArtists
import com.slowva.player.recommend.SongRadio
import com.slowva.player.ui.RowPlaceholders
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.library.PlayList
import com.slowva.player.ui.library.PlayShuffleRow
import com.slowva.player.ui.library.artistMatches
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongRow
import com.slowva.player.ui.songs.SongSheets
import com.slowva.player.ui.songs.SongTarget
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import com.slowva.player.ui.CardRow
import kotlinx.coroutines.launch

/** Your songs and albums by an artist, then their Last.fm top songs and similar artists when Last.fm is on. */
@Composable
internal fun ArtistPage(artist: LastFm.Artist, repo: MusicRepository, onOpen: (HomePage) -> Unit, onBack: () -> Unit) {
    val tracks by repo.tracks.collectAsStateWithLifecycle()
    val mine = remember(tracks, artist.name) { tracks.filter { artistMatches(it.artist, artist.name) } }
    val covers = remember(mine) { mine.mapNotNull { it.coverUri }.distinct().take(4) }
    val albums = remember(mine) { mine.groupBy { it.album }.filterKeys { it.isNotBlank() && it != "Imported" } }
    val top = if (LastFm.enabled) rememberLoad(artist.name, null as List<LastFm.Song>?) { LastFm.artistTracks(artist.name) } else emptyList()
    val hidden by HiddenArtists.names.collectAsStateWithLifecycle()
    val similar = if (LastFm.enabled) HiddenArtists.filter(rememberLoad(artist.name, emptyList()) { LastFm.similar(artist.name) }, hidden) { it.name } else emptyList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArtistHeader(artist, covers, onBack)
                Spacer(Modifier.height(12.dp))
                PlayShuffleRow(mine, repo)
                if (mine.isEmpty()) Text("None of your songs are by ${artist.name} yet", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (albums.isNotEmpty()) {
            item { SectionHeader("Your albums", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            items(albums.entries.toList(), key = { "a" + it.key }) { (album, list) ->
                CardRow(list.firstNotNullOfOrNull { it.coverUri }, album, com.slowva.player.ui.songCount(list.size)) { onOpen(HomePage.Album(album, artist.name)) }
            }
        }
        item { DiscographyRow(artist.name, onOpen) }
        if (mine.isNotEmpty()) {
            item { SectionHeader("Your songs", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            items(mine, key = { "m" + it.id }) { t -> SongRow(t, SongContext.Library, mine, onPlay = { PlayList.play(repo, mine, mine.indexOf(t)) }) }
        }
        if (LastFm.enabled) {
            item { SectionHeader("Popular songs", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            if (top == null) item { RowPlaceholders() }
            itemsIndexed(top.orEmpty().take(20), key = { i, s -> "t$i${s.title}" }) { i, s -> NamedSongRow(i, s, repo) }
            if (similar.isNotEmpty()) item {
                SectionHeader("Similar artists", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(similar, key = { it.name }) { ArtistTile(it, 96.dp) { a -> onOpen(HomePage.ArtistPage(a)) } }
                }
            }
        }
    }
}

/** The artist big in a cookie over a blurred copy of the same picture, with Back and the name on top. */
@Composable
private fun ArtistHeader(artist: LastFm.Artist, covers: List<String>, onBack: () -> Unit) {
    val photo = artist.image
    val backdrop = photo ?: covers.firstOrNull() ?: artist.image
    val bg = MaterialTheme.colorScheme.background
    val blurry = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(Modifier.fillMaxWidth().height(320.dp)) {
        if (backdrop != null) AsyncImage(
            backdrop, null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
                .then(if (blurry) Modifier.blur(48.dp) else Modifier)
                .graphicsLayer { alpha = if (blurry) 0.75f else 0.35f },
        ) else Box(Modifier.matchParentSize().background(coverTones(artist.name).paper.copy(alpha = 0.6f)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.35f), bg.copy(alpha = 0.55f), bg))))
        Column(Modifier.fillMaxWidth()) {
            PageTop(artist.name, onBack)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                ArtistArt(artist.name, photo, covers.ifEmpty { listOfNotNull(artist.image) }, size = 200.dp)
            }
        }
    }
}

/** A Last.fm song by name: a tap finds it and plays it with its radio, long press opens the song sheet. */
@Composable
internal fun NamedSongRow(index: Int, s: LastFm.Song, repo: MusicRepository) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .combinedClickable(
                enabled = !busy, onClickLabel = "play", onLongClickLabel = "more options",
                onLongClick = { SongSheets.show(SongTarget.Named(s.title, s.artist), SongContext.Recommended) },
            ) {
                busy = true
                scope.launch {
                    val song = SongRadio.find(repo, s.artist, s.title)
                    if (song == null) android.widget.Toast.makeText(ctx, SongRadio.missing(), android.widget.Toast.LENGTH_SHORT).show()
                    busy = false
                    song?.let { SongRadio.play(repo, it) }
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", color = TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (busy) Box(Modifier.size(24.dp)) { CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp) }
    }
}
