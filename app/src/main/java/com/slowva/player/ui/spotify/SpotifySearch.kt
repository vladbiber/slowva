package com.slowva.player.ui.spotify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.spotify.SpCollection
import com.slowva.player.spotify.SpResults
import com.slowva.player.spotify.SpotifyApi
import com.slowva.player.ui.RowPlaceholders
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.home.HomePage
import com.slowva.player.ui.library.PlayList
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongRow
import com.slowva.player.ui.songs.SongSheets
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Spotify's answer to a search, as the results list shows it. */
internal sealed interface SpSearch {
    data object Idle : SpSearch
    data object Loading : SpSearch
    data class Done(val results: SpResults, val tracks: List<Track>) : SpSearch
    data class Failed(val message: String) : SpSearch
}

/** Searches Spotify for [q] once typing pauses; Idle when [enabled] is false. */
@Composable
internal fun rememberSpotifySearch(q: String, enabled: Boolean): SpSearch {
    val state by produceState<SpSearch>(SpSearch.Idle, q, enabled) {
        if (!enabled || q.length < 2) { value = SpSearch.Idle; return@produceState }
        value = SpSearch.Loading
        delay(450)
        value = try {
            val r = SpotifyApi.search(q)
            SpSearch.Done(r, r.tracks.map { it.toTrack() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpSearch.Failed(e.message ?: "Spotify didn't answer")
        }
    }
    return state
}

private fun open(c: SpCollection) { SongSheets.goTo.value = HomePage.Spotify(c) }

/** The Spotify section after "Your library": songs, then artists, albums and playlists. */
internal fun LazyListScope.spotifyResults(s: SpSearch, repo: MusicRepository) {
    if (s == SpSearch.Idle) return
    item(key = "h_spotify") { SectionHeader("Spotify", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
    when (s) {
        SpSearch.Idle -> {}
        SpSearch.Loading -> item(key = "sp_loading") { RowPlaceholders(3) }
        is SpSearch.Failed -> item(key = "sp_failed") { Note(s.message) }
        is SpSearch.Done -> {
            if (s.results.isEmpty) item(key = "sp_none") { Note("Nothing on Spotify for this") }
            items(s.tracks, key = { "sp" + it.id }) { t ->
                SongRow(t, SongContext.Search, s.tracks, onPlay = { PlayList.play(repo, s.tracks, s.tracks.indexOf(t)) })
            }
            shelf("Artists", s.results.artists.map(SpCollection::of))
            shelf("Albums", s.results.albums.map(SpCollection::of))
            shelf("Playlists", s.results.playlists.map(SpCollection::of))
        }
    }
}

private fun LazyListScope.shelf(title: String, list: List<SpCollection>) {
    if (list.isEmpty()) return
    item(key = "sp_$title") {
        Column(Modifier.padding(start = 12.dp)) {
            SectionHeader(title, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
            LazyRow(contentPadding = PaddingValues(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { c -> SpotifyTile(c, 128.dp) { open(c) } }
            }
        }
    }
}

@Composable
private fun Note(text: String) =
    Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
