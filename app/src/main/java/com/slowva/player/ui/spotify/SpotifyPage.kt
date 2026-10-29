package com.slowva.player.ui.spotify

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.spotify.SpCollection
import com.slowva.player.spotify.SpotifyLibrary
import com.slowva.player.ui.CoverImage
import com.slowva.player.ui.RowPlaceholders
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.home.PageTop
import com.slowva.player.ui.library.PlayList
import com.slowva.player.ui.library.PlayShuffleRow
import com.slowva.player.ui.songCount
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongRow
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException

private sealed interface Load {
    data object Loading : Load
    data class Done(val tracks: List<Track>) : Load
    data class Failed(val message: String) : Load
}

/** A Spotify playlist, album or Liked songs: cover, Play / Shuffle / Save all, then the songs. */
@Composable
internal fun SpotifyPage(c: SpCollection, repo: MusicRepository, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val saved by repo.tracks.collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<Load>(Load.Loading, c, attempt) {
        value = try {
            Load.Done(SpotifyLibrary.tracks(c, force = attempt > 0).also(repo::remember))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: "Couldn't load it from Spotify")
        }
    }
    val tracks = (load as? Load.Done)?.tracks.orEmpty()
    val savedIds = remember(saved) { saved.mapTo(HashSet()) { it.id } }
    val unsaved = remember(tracks, savedIds) { tracks.filter { it.id !in savedIds } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { PageTop(c.title, onBack) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverImage(c.art ?: tracks.firstNotNullOfOrNull { it.coverUri }, c.title, MaterialTheme.shapes.large, Modifier.size(180.dp))
                Spacer(Modifier.height(10.dp))
                val kind = when (c.kind) {
                    SpCollection.Kind.LIKED -> "Spotify"
                    SpCollection.Kind.PLAYLIST -> "Playlist · ${c.subtitle}"
                    SpCollection.Kind.ALBUM -> "Album · ${c.subtitle}"
                    SpCollection.Kind.ARTIST -> "Popular on Spotify"
                }
                Text(if (load is Load.Done) "$kind · ${songCount(tracks.size)}" else kind, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                PlayShuffleRow(tracks, repo) {
                    if (tracks.isNotEmpty()) FilledTonalButton(enabled = unsaved.isNotEmpty(), onClick = {
                        repo.saveStreamed(unsaved)
                        Toast.makeText(ctx, "Saved ${songCount(unsaved.size)} to your library", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.LibraryAdd, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (unsaved.isEmpty()) "Saved" else "Save all")
                    }
                }
            }
        }
        when (val l = load) {
            Load.Loading -> item { RowPlaceholders(6) }
            is Load.Failed -> item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(l.message, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { attempt++ }) { Text("Try again") }
                }
            }
            is Load.Done -> items(l.tracks, key = { it.id }) { t ->
                SongRow(t, SongContext.Search, l.tracks, onPlay = { PlayList.play(repo, l.tracks, l.tracks.indexOf(t)) })
            }
        }
    }
}

/** A titled row of Spotify lists as covers; a tap opens one. */
@Composable
internal fun SpotifyCollectionsRow(title: String, items: List<SpCollection>, onOpen: (SpCollection) -> Unit, tile: Dp = 140.dp) {
    Column {
        SectionHeader(title, Modifier.padding(top = 14.dp, start = 4.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { "${it.kind}${it.id}" }) { c -> SpotifyTile(c, tile) { onOpen(c) } }
        }
    }
}

@Composable
internal fun SpotifyTile(c: SpCollection, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.width(size).clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = "open", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${c.title}, ${c.subtitle}" },
    ) {
        CoverImage(c.art, c.title, MaterialTheme.shapes.small, Modifier.size(size))
        Spacer(Modifier.height(6.dp))
        Text(c.title, color = TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(c.subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
