package com.slowva.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slowva.player.data.MusicRepository
import com.slowva.player.lastfm.Continent
import com.slowva.player.lastfm.LastFm
import com.slowva.player.lastfm.Taste
import com.slowva.player.data.db.SoundDao
import com.slowva.player.recommend.HiddenArtists
import com.slowva.player.recommend.SongRadio
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongSheets
import com.slowva.player.ui.songs.SongTarget
import androidx.compose.foundation.combinedClickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.ui.EmptyHint
import com.slowva.player.ui.art.ArtistArt
import com.slowva.player.ui.art.CoverCollage
import com.slowva.player.ui.RowPlaceholders
import com.slowva.player.ui.ScreenTitle
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A page shown over the current tab: artists, albums, playlists, genres; Back returns to where it was opened. */
sealed interface HomePage {
    data class GenrePage(val tag: String) : HomePage
    data class ArtistPage(val artist: LastFm.Artist) : HomePage
    data class Artists(val title: String, val artists: List<LastFm.Artist>) : HomePage
    data class Top100(val continent: Continent) : HomePage
    /** your songs of one album; [artist] tells apart albums with the same name */
    data class Album(val album: String, val artist: String) : HomePage
    data class Playlist(val name: String) : HomePage
    data class Spotify(val list: com.slowva.player.spotify.SpCollection) : HomePage
}

/** Runs [load] once per [key]; [initial] until it finishes, failures (offline, no key) give [empty]. */
@Composable
internal fun <T> rememberLoad(key: Any, empty: T, initial: T = empty, load: suspend () -> T): T =
    produceState(initial, key) {
        value = try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { empty }
    }.value

internal fun genreLabel(tag: String) = tag.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }  // "k-pop" -> "K-pop"

/** Home's Genres row: a card with four album covers per genre. */
@Composable
internal fun GenresRow(genres: List<String>, title: String = "Genres", onOpen: (String) -> Unit) {
    Column {
        SectionHeader(title, Modifier.padding(top = 10.dp, start = 4.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(genres, key = { it }) { tag ->
                Column(
                    Modifier.width(150.dp).clip(MaterialTheme.shapes.medium)
                        .clickable(role = Role.Button, onClickLabel = "open") { onOpen(tag) }
                        .semantics(mergeDescendants = true) { contentDescription = genreLabel(tag) },
                ) {
                    Collage(rememberLoad(tag, emptyList()) { LastFm.tagCovers(tag) }, 150.dp, genreLabel(tag))
                    Spacer(Modifier.height(6.dp))
                    Text(genreLabel(tag), color = TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun Collage(covers: List<String>, size: Dp, name: String = "") {
    CoverCollage(covers, Modifier.size(size).clip(MaterialTheme.shapes.medium), name)
}

/** An artist's photo in the cookie shape, else a collage of [covers], else a cover generated from the name. */
@Composable
internal fun Avatar(artist: LastFm.Artist, size: Dp, covers: List<String> = listOfNotNull(artist.image)) {
    ArtistArt(artist.name, null, covers, size = size)
}

@Composable
internal fun ArtistTile(artist: LastFm.Artist, size: Dp, onOpen: (LastFm.Artist) -> Unit) {
    Column(
        Modifier.width(size).clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = "open") { onOpen(artist) }
            .semantics(mergeDescendants = true) { contentDescription = artist.name },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(artist, size)
        Spacer(Modifier.height(6.dp))
        Text(artist.name, color = TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Home's Creators row: your artists first, then similar ones from Last.fm. */
@Composable
internal fun CreatorsRow(creators: List<LastFm.Artist>, onOpen: (LastFm.Artist) -> Unit, onSeeAll: () -> Unit, title: String = "Creators") {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title, Modifier.weight(1f).padding(top = 10.dp))
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(creators.take(20), key = { it.name }) { ArtistTile(it, 104.dp, onOpen) }
        }
    }
}

@Composable
internal fun PageTop(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary) }
        ScreenTitle(title)
    }
}

/** Every creator from the row, as a grid. */
@Composable
internal fun CreatorsPage(creators: List<LastFm.Artist>, onOpen: (LastFm.Artist) -> Unit, onBack: () -> Unit, title: String = "Creators") {
    Column(Modifier.fillMaxSize()) {
        PageTop(title, onBack)
        LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(creators, key = { it.name }) { ArtistTile(it, 104.dp, onOpen) }
        }
    }
}

/** A genre's top songs from Last.fm; a tap finds the song ([SongRadio.find]) and plays it. */
@Composable
internal fun GenrePage(tag: String, repo: MusicRepository, onBack: () -> Unit) {
    val hidden by HiddenArtists.names.collectAsStateWithLifecycle()
    val songs = rememberLoad(tag, null as List<LastFm.Song>?) { LastFm.tagTracks(tag) }?.let { HiddenArtists.filter(it, hidden) { s -> s.artist } }
    val covers = rememberLoad(tag, emptyList()) { LastFm.tagCovers(tag) }
    SongsPage(genreLabel(tag), { Collage(covers, 200.dp, genreLabel(tag)) }, songs, repo, onBack)
}

@Composable
internal fun SongsPage(
    title: String,
    art: @Composable () -> Unit,
    songs: List<LastFm.Song>?,
    repo: MusicRepository,
    onBack: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf<LastFm.Song?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { PageTop(title, onBack) }
        item { Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { art() } }
        item { footer() }
        item { SectionHeader("Top songs", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
        when {
            songs == null -> item { RowPlaceholders() }
            songs.isEmpty() -> item { EmptyHint("Couldn't load songs. Check the connection and try again.") }
        }
        itemsIndexed(songs.orEmpty(), key = { i, s -> "$i${s.title}" }) { i, s ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .combinedClickable(
                        enabled = busy == null, onClickLabel = "play", onLongClickLabel = "more options",
                        onLongClick = { SongSheets.show(SongTarget.Named(s.title, s.artist), SongContext.Recommended) },
                    ) {
                        busy = s
                        scope.launch {
                            val song = SongRadio.find(repo, s.artist, s.title)
                            if (song == null) android.widget.Toast.makeText(ctx, SongRadio.missing(), android.widget.Toast.LENGTH_SHORT).show()
                            busy = null
                            song?.let { SongRadio.play(repo, it) }
                        }
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, color = TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (busy == s) CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
            }
        }
    }
}

private suspend fun <T> orEmpty(load: suspend () -> List<T>): List<T> =
    try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }

/** Your genres first, then popular Last.fm tags; empty without a Last.fm key, null while loading. */
@Composable
internal fun rememberGenres(repo: MusicRepository, sound: SoundDao): List<String>? =
    if (!LastFm.enabled) emptyList()
    else rememberLoad("genres", emptyList(), initial = null) { Taste.genreRow(Taste.myGenres(repo, sound), orEmpty { LastFm.topTags() }) }

/** Your most played artists, then artists similar to the top three (or a genre's top artists with no history yet); null while loading. */
@Composable
internal fun rememberCreators(repo: MusicRepository, firstGenre: String?): List<LastFm.Artist>? {
    if (!LastFm.enabled) return emptyList()
    val hidden by HiddenArtists.names.collectAsStateWithLifecycle()
    val all = rememberLoad("creators$firstGenre", emptyList(), initial = null) {
        val mine = Taste.artists(Taste.plays(repo))
        val more = if (mine.isEmpty()) orEmpty { LastFm.tagArtists(firstGenre ?: "pop") } else mine.take(3).flatMap { a -> orEmpty { LastFm.similar(a.name) } }
        (mine + more).distinctBy { it.name.lowercase() }
    }
    return remember(all, hidden) { all?.let { HiddenArtists.filter(it, hidden) { a -> a.name }.take(60) } }
}
