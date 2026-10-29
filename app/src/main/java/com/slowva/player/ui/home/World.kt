package com.slowva.player.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.lastfm.Continent
import com.slowva.player.lastfm.LastFm
import com.slowva.player.lastfm.WorldCharts
import com.slowva.player.player.PlayerHub
import com.slowva.player.recommend.HiddenArtists
import com.slowva.player.recommend.SongRadio
import com.slowva.player.ui.CtButton
import com.slowva.player.ui.Emphasis
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.ShelfPlaceholder
import com.slowva.player.ui.songs.Snack
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private fun top100Name(c: Continent) = "Top 100 · ${c.label}"

/** Home's "Around the world": continent chips, then the picked one's Top 100, genres and artists. */
@Composable
internal fun WorldSection(
    onGenre: (String) -> Unit,
    onArtist: (LastFm.Artist) -> Unit,
    onSeeAll: (String, List<LastFm.Artist>) -> Unit,
    onTop100: (Continent) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("world", Context.MODE_PRIVATE) }
    var picked by remember { mutableStateOf(Continent.entries.firstOrNull { it.name == prefs.getString("continent", null) } ?: Continent.AFRICA) }
    val hidden by HiddenArtists.names.collectAsStateWithLifecycle()
    val artists = rememberLoad(picked, emptyList(), initial = null) { WorldCharts.artists(picked) }
        ?.let { all -> remember(all, hidden) { HiddenArtists.filter(all, hidden) { it.name }.take(60) } }
    val artistsTitle = "Artists from ${picked.label}"
    Column {
        SectionHeader("Around the world", Modifier.padding(top = 10.dp, start = 4.dp))
        Text("From Last.fm listeners, thinner for some regions", color = TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Continent.entries, key = { it.name }) { c ->
                FilterChip(
                    selected = c == picked,
                    onClick = { picked = c; prefs.edit().putString("continent", c.name).apply() },
                    label = { Text(c.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Top100Card(picked) { onTop100(picked) }
        GenresRow(picked.tags, "Genres from ${picked.label}", onGenre)
        when {
            artists == null -> ShelfPlaceholder(artistsTitle, 104.dp, CircleShape)
            artists.isNotEmpty() -> CreatorsRow(artists, onArtist, { onSeeAll(artistsTitle, artists) }, artistsTitle)
        }
    }
}

@Composable
private fun Top100Card(c: Continent, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.primaryContainer)))
            .clickable(role = Role.Button, onClickLabel = "open", onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Public, null, tint = ink, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(top100Name(c), style = MaterialTheme.typography.titleMedium, color = ink)
            Text("Most played in ${c.countries.take(3).joinToString(", ") { it.substringBefore(",").replace("Korea", "South Korea") }} and more", style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.85f))
        }
    }
}

/** A continent's Top 100: the saved one at once, refreshed when over a week old. */
@Composable
internal fun Top100Page(c: Continent, repo: MusicRepository, onBack: () -> Unit) {
    val dir = LocalContext.current.cacheDir
    val hidden by HiddenArtists.names.collectAsStateWithLifecycle()
    val songs = produceState<List<LastFm.Song>?>(null, c) {
        val old = WorldCharts.cached(dir, c)
        value = old?.first
        if (old == null || old.second) value = WorldCharts.fetch(dir, c, hidden).ifEmpty { old?.first.orEmpty() }
    }.value?.let { all -> remember(all, hidden) { HiddenArtists.filter(all, hidden) { it.artist } } }
    val saving by Top100.saving.collectAsStateWithLifecycle()
    val name = top100Name(c)
    SongsPage(name, { ChartArt(c) }, songs, repo, onBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            CtButton({ Top100.playAll(repo, songs.orEmpty()) }, emphasis = Emphasis.Primary, enabled = !songs.isNullOrEmpty()) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play all")
            }
            CtButton({ Top100.save(repo, name, songs.orEmpty()) }, enabled = !songs.isNullOrEmpty() && saving == null) {
                Icon(Icons.Filled.LibraryAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(saving?.let { (done, all) -> "Saving $done of $all" } ?: "Save to Library")
            }
        }
        saving?.let { (done, all) ->
            LinearProgressIndicator({ done / all.toFloat() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun ChartArt(c: Continent) {
    val ink = MaterialTheme.colorScheme.onTertiaryContainer
    Box(
        Modifier.size(200.dp).clip(MaterialTheme.shapes.medium)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.primaryContainer))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("100", style = MaterialTheme.typography.displayLarge, color = ink)
            Text(c.label, style = MaterialTheme.typography.titleMedium, color = ink)
        }
    }
}

/** Play all and Save to Library look songs up for a while, so they outlive the page. */
internal object Top100 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playing: Job? = null
    /** Songs looked up and the total while saving, else null. */
    val saving = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Looks songs up a few at a time, in order; [found] gets each batch with the count done so far and says whether to go on. */
    private suspend fun findEach(repo: MusicRepository, songs: List<LastFm.Song>, found: (Int, List<Track>) -> Boolean) = coroutineScope {
        var done = 0
        for (batch in songs.chunked(4)) {
            val tracks = batch.map { s -> async { SongRadio.find(repo, s.artist, s.title) } }.awaitAll().filterNotNull()
            done += batch.size
            if (!found(done, tracks)) break
        }
    }

    /** Starts the first song found at once and queues the rest as they turn up. */
    fun playAll(repo: MusicRepository, songs: List<LastFm.Song>) {
        playing?.cancel()
        playing = scope.launch {
            val before = PlayerHub.ui.value.currentTrackId
            val ours = HashSet<String>()
            findEach(repo, songs) { _, tracks ->
                // the listener started something else, so stop filling the queue
                val now = PlayerHub.ui.value.currentTrackId
                if (ours.isNotEmpty() && now != before && now !in ours) return@findEach false
                repo.remember(tracks)
                for (t in tracks) {
                    val first = ours.isEmpty()
                    if (ours.add(t.id)) if (first) PlayerHub.playQueue(listOf(t.id), 0) else PlayerHub.addToQueue(t.id)
                }
                true
            }
            if (ours.isEmpty()) Snack.show("Couldn't find these songs")
        }
    }

    /** Saves the songs found as a playlist called [name]; ones not found are skipped. */
    fun save(repo: MusicRepository, name: String, songs: List<LastFm.Song>) {
        if (saving.value != null || songs.isEmpty()) return
        saving.value = 0 to songs.size
        scope.launch {
            val tracks = ArrayList<Track>()
            try {
                findEach(repo, songs) { done, found -> tracks += found; saving.value = done to songs.size; true }
            } finally {
                saving.value = null
            }
            val unique = tracks.distinctBy { it.id }
            if (unique.isEmpty()) return@launch Snack.show("Couldn't find these songs")
            repo.saveStreamed(unique)
            repo.createPlaylistWithTracks(name, unique.map { it.id })
            Snack.show("Saved ${unique.size} of ${songs.size} songs to $name")
        }
    }
}
