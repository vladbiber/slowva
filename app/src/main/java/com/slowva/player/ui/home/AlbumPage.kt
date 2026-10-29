package com.slowva.player.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.RateTarget
import com.slowva.player.data.RatingKeys
import com.slowva.player.data.Ratings
import com.slowva.player.data.Track
import com.slowva.player.data.db.RatingKind
import com.slowva.player.lastfm.LastFm
import com.slowva.player.musicbrainz.MbAlbum
import com.slowva.player.musicbrainz.MbParse
import com.slowva.player.musicbrainz.MusicBrainz
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import com.slowva.player.recommend.SongRadio
import com.slowva.player.ui.AlbumArt
import com.slowva.player.ui.RowPlaceholders
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.library.PlayList
import com.slowva.player.ui.library.PlayShuffleRow
import com.slowva.player.ui.library.artistMatches
import com.slowva.player.ui.rating.RatingBar
import com.slowva.player.ui.songCount
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongRow
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface MbLoad {
    data object Loading : MbLoad
    data object Missing : MbLoad
    data class Found(val album: MbAlbum) : MbLoad
}

/** Your songs of one album with Play and Shuffle; with MusicBrainz on, the album's facts and its full track list. */
@Composable
internal fun AlbumPage(album: String, artist: String, repo: MusicRepository, onOpen: (HomePage) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by repo.tracks.collectAsStateWithLifecycle()
    val songs = remember(tracks, album, artist) {
        tracks.filter { it.album == album && (artist.isBlank() || artistMatches(it.artist, artist) || it.artist.contains(artist, true)) }
    }
    val mbOn = rememberMbOn()
    val load by produceState<MbLoad>(MbLoad.Loading, album, artist, mbOn) {
        value = if (!mbOn || artist.isBlank()) MbLoad.Missing else try {
            MusicBrainz.album(artist, album)?.let { MbLoad.Found(it) } ?: MbLoad.Missing
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            MbLoad.Missing
        }
    }
    val mb = (load as? MbLoad.Found)?.album
    val byArtist = remember(tracks, artist) { if (artist.isBlank()) songs else tracks.filter { artistMatches(it.artist, artist) || it.album == album } }
    val matches = remember(mb, byArtist) { mb?.let { MbParse.matchLocal(it.tracks, byArtist, album) }.orEmpty() }
    val inOrder = remember(matches) { matches.toSortedMap().values.distinctBy { it.id } }
    val multiDisc = remember(mb) { mb?.tracks?.any { it.disc > 1 } == true }
    var busy by remember { mutableStateOf<Int?>(null) }
    val online = SongRadio.provider.collectAsState().value != null
    var saving by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun saveAll(a: MbAlbum) {
        NetworkGate.blockReason(Feature.STREAMING)?.let { toast(it); return }
        val missing = a.tracks.indices.filter { it !in matches }
        scope.launch {
            val found = mutableListOf<Track>()
            var lost = 0
            try {
                missing.forEachIndexed { n, i ->
                    saving = n + 1 to missing.size
                    val t = a.tracks[i]
                    val s = SongRadio.find(repo, artist, t.title, t.lengthMs)
                    if (s == null) lost++ else if (s.isOnline) found += s.copy(album = album, artist = artist.ifBlank { s.artist })
                }
            } finally {
                if (found.isNotEmpty()) repo.saveStreamed(found)
                saving = null
            }
            toast(if (lost == 0) "Saved ${songCount(found.size)}" else "Saved ${songCount(found.size)}; $lost not found")
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { PageTop(album, onBack) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val first = songs.firstOrNull()
                if (first == null && mb != null) Cover(mb.group.coverUrl, 180.dp) else AlbumArt(first, 180.dp, shape = MaterialTheme.shapes.large)
                Spacer(Modifier.height(10.dp))
                if (artist.isNotBlank()) TextButton(onClick = { onOpen(HomePage.ArtistPage(LastFm.Artist(artist, null))) }) {
                    Text(artist, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (songs.isEmpty() && mb != null) "None of your songs are from this album yet" else "Album · ${songCount(songs.size)}",
                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                )
                if (mb != null) AlbumFacts(mb)
                YourRating(artist, album, mb?.group?.year)
                Spacer(Modifier.height(10.dp))
                PlayShuffleRow(songs, repo)
            }
        }
        items(songs, key = { it.id }) { t: Track -> SongRow(t, SongContext.Library, songs, onPlay = { PlayList.play(repo, songs, songs.indexOf(t)) }) }
        if (mbOn && load == MbLoad.Loading && artist.isNotBlank()) item {
            SectionHeader("Full track list", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            RowPlaceholders(3)
        }
        if (mb != null && mb.tracks.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionHeader("Full track list")
                        Text("${matches.size} of ${mb.tracks.size} in your library", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    val left = mb.tracks.size - matches.size
                    // missing songs can only be saved from an online source
                    if (online) FilledTonalButton(onClick = { saveAll(mb) }, enabled = left > 0 && saving == null) {
                        Icon(Icons.Filled.LibraryAdd, null)
                        Spacer(Modifier.width(6.dp))
                        Text(saving?.let { (n, of) -> "Saving $n of $of" } ?: "Save all")
                    }
                }
            }
            itemsIndexed(mb.tracks, key = { i, _ -> "mb$i" }) { i, t ->
                val mine = matches[i]
                val number = if (multiDisc) "${t.disc}-${t.position}" else "${t.position}"
                MbTrackRow(number, t, mine, busy == i) {
                    if (mine != null) PlayList.play(repo, inOrder, inOrder.indexOf(mine).coerceAtLeast(0))
                    else {
                        busy = i
                        scope.launch {
                            val song = SongRadio.find(repo, artist, t.title, t.lengthMs)
                            busy = null
                            if (song == null) toast(SongRadio.missing()) else SongRadio.play(repo, song)
                        }
                    }
                }
            }
        }
    }
}

/** Year and type, the MusicBrainz rating, and genres. */
@Composable
private fun AlbumFacts(mb: MbAlbum) {
    val facts = listOfNotNull(mb.group.label().takeIf { it.isNotEmpty() }, mb.rating?.let { "MusicBrainz ★ %.1f (%d)".format(it, mb.votes) })
    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    TagChips(mb.tags)
}

/** Your own stars for the album, as a small labeled row under the title. */
@Composable
private fun YourRating(artist: String, album: String, year: Int?) {
    val ctx = LocalContext.current
    val target = remember(artist, album, year) { RatingKeys.album(artist, album)?.let { RateTarget(RatingKind.ALBUM, it, album, artist, album, year = year) } } ?: return
    val ratings = remember { Ratings.get(ctx) }
    val row by remember(target.key) { ratings.rating(RatingKind.ALBUM, target.key) }.collectAsState(null)
    Row(
        Modifier.padding(top = 8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 14.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Your rating", color = TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
        RatingBar(row?.value ?: 0, { ratings.set(target, it) }, starSize = 18.dp, label = "Your album rating, $album")
    }
}
