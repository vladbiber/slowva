package com.slowva.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.HistoryPrefs
import com.slowva.player.data.Track
import com.slowva.player.keys.LocalDeviceProfile
import com.slowva.player.keys.typingField
import com.slowva.player.player.PlayerViewModel
import com.slowva.player.spotify.Spotify
import com.slowva.player.ui.spotify.SpSearch
import com.slowva.player.ui.spotify.rememberSpotifySearch
import com.slowva.player.ui.spotify.spotifyResults
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongRow
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceElevated
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Serializable

/** Where search looks; the pills to pick one show once Spotify is signed in. */
internal enum class Scope(val label: String) { ALL("All"), LIBRARY("Library"), SPOTIFY("Spotify") }

/** Everything the search filter sheet sets; kept for the session. */
internal data class SearchFilter(val scope: Scope = Scope.ALL, val lib: SongFilter = SongFilter()) : Serializable {
    val active get() = copy(scope = Scope.ALL) != SearchFilter()
}

/** The filter button for [SearchResults]. */
@Composable
internal fun SearchFilterButton(f: SearchFilter, onChange: (SearchFilter) -> Unit) =
    FilterButton(f.active, onReset = { onChange(SearchFilter(scope = f.scope)) }) {
        FilterLabel("Your library")
        SearchSongOptions(f.lib) { onChange(f.copy(lib = it)) }
    }

internal fun Track.matches(q: String) = title.contains(q, true) || artist.contains(q, true) || album.contains(q, true)

/** The rounded search box every tab uses. */
@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.typingField().fillMaxWidth(),
        placeholder = { Text(placeholder, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextSecondary) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                Icon(Icons.Filled.Close, "Clear search", tint = TextSecondary)
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = SurfaceElevated,
            unfocusedContainerColor = SurfaceElevated,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = Accent,
        ),
    )
}

/** Results for [query] in your library, narrowed by [filter]; Spotify's come in their own section after these. */
@Composable
internal fun SearchResults(viewModel: PlayerViewModel, query: String, filter: SearchFilter) {
    val signedIn by Spotify.signedIn.collectAsStateWithLifecycle()
    val tracks by viewModel.repository.tracks.collectAsStateWithLifecycle()
    val liked by viewModel.repository.favorites.collectAsStateWithLifecycle()
    val lib = filter.lib
    val q = query.trim()
    // off the main thread, so typing stays smooth with a big library
    val local by produceState(emptyList<Track>(), q, tracks, lib, liked) {
        value = if (q.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            // on the phone first, saved online songs after
            lib.apply(tracks, q, liked).sortedBy { if (it.isOnline) 1 else 0 }
        }
    }
    val localAlbums = remember(local, lib) {
        if (lib.match != Match.ALBUMS) emptyList()
        else local.groupBy { it.album }.filterKeys { it.isNotBlank() }.values.toList()
    }
    val mine = filter.scope != Scope.SPOTIFY
    val showAlbums = mine && lib.match == Match.ALBUMS && localAlbums.isNotEmpty()
    val showLocal = mine && lib.match != Match.ALBUMS && local.isNotEmpty()
    val online = rememberSpotifySearch(q, signedIn && filter.scope != Scope.LIBRARY)

    LazyColumn(contentPadding = contentPadding()) {
        if (showAlbums) {
            header("Your albums")
            items(localAlbums, key = { "la" + it.first().album }) { list ->
                CardRow(list.firstNotNullOfOrNull { it.coverUri }, list.first().album, "${list.first().artist} · ${songCount(list.size)}") {
                    viewModel.playQueue(list, 0)
                }
            }
        }
        if (showLocal) {
            header("Your library")
            items(local, key = { "l" + it.id }) { track ->
                SongRow(track, SongContext.Search, local, onPlay = { viewModel.playQueue(local, local.indexOf(track)) })
            }
        }
        if (!showAlbums && !showLocal && online == SpSearch.Idle) item(key = "none") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
                Text("Nothing found for \"$q\"", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                val where = if (signedIn) "the songs on your phone. Spotify is in the All and Spotify tabs." else "the songs on your phone. Sign in to Spotify in Settings › Connections to search it too."
                Text("Search looks through $where", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
        spotifyResults(online, viewModel.repository)
    }
}

private fun LazyListScope.header(text: String) = item(key = "h_$text") {
    SectionHeader(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** The one search screen, over Home; [start] picks the scope it opens on. */
@Composable
internal fun SearchOverlay(viewModel: PlayerViewModel, start: Scope, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchFilter by rememberSaveable { mutableStateOf(SearchFilter(scope = start)) }
    val ctx = LocalContext.current
    val recent = remember { RecentSearches(ctx) }
    val keepSearches by HistoryPrefs.flow(HistoryPrefs.Key.KEEP_SEARCHES).collectAsStateWithLifecycle()
    var recentList by remember(keepSearches) { mutableStateOf(if (keepSearches) recent.all() else emptyList()) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }
    val signedIn by Spotify.signedIn.collectAsStateWithLifecycle()

    BackHandler(onBack = onClose)
    // keyboard phones: Back first leaves the box, so shortcuts work again
    BackHandler(enabled = fieldFocused && LocalDeviceProfile.current.isKeyboardPhone) { focusManager.clearFocus() }
    LaunchedEffect(start) { searchFilter = searchFilter.copy(scope = start) }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!focusedOnce) { focusedOnce = true; runCatching { focus.requestFocus() } } }
    // a search counts once the user stopped typing for a while
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2 || !HistoryPrefs[HistoryPrefs.Key.KEEP_SEARCHES]) return@LaunchedEffect
        delay(2000)
        recent.add(q); recentList = recent.all()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search", tint = TextPrimary) }
            SearchField(
                query, { query = it }, "Songs, artists or albums",
                Modifier.weight(1f).focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused },
            )
            SearchFilterButton(searchFilter) { searchFilter = it }
        }
        if (signedIn) PillTabs(Scope.entries.map { it.label }, searchFilter.scope.ordinal, { searchFilter = searchFilter.copy(scope = Scope.entries[it]) })
        when {
            query.isNotBlank() -> SearchResults(viewModel, query, searchFilter)
            recentList.isEmpty() -> Text(
                if (signedIn) "Search your library and Spotify by song, artist or album." else "Search your library by song, artist or album.",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(contentPadding = contentPadding()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader("Recent searches", Modifier.weight(1f))
                        TextButton(onClick = { recent.clear(); recentList = emptyList() }) { Text("Clear") }
                    }
                }
                items(recentList, key = { "rs$it" }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { query = s }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.History, null, tint = TextMuted)
                        Spacer(Modifier.width(16.dp))
                        Text(s, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { recent.remove(s); recentList = recent.all() }) { Icon(Icons.Filled.Close, "Forget $s", tint = TextMuted) }
                    }
                }
            }
        }
    }
}

/** The last searches, newest first, kept on the phone. */
internal class RecentSearches(context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recent_searches", android.content.Context.MODE_PRIVATE)

    fun all(): List<String> = prefs.getString("q", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun add(q: String) = save(listOf(q) + all().filterNot { it.equals(q, true) || q.startsWith(it, true) })

    fun remove(q: String) = save(all() - q)

    fun clear() = save(emptyList())

    private fun save(list: List<String>) = prefs.edit().putString("q", list.take(MAX).joinToString("\n")).apply()

    companion object {
        private const val MAX = 12
        fun clear(context: android.content.Context) = RecentSearches(context).clear()
    }
}
