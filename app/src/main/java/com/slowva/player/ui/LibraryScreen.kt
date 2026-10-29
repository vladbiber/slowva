package com.slowva.player.ui

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import com.slowva.player.data.MusicRepository
import com.slowva.player.lastfm.LastFm
import com.slowva.player.recommend.RadioMixer
import com.slowva.player.ui.home.HomePage
import com.slowva.player.spotify.Spotify
import com.slowva.player.spotify.SpotifyLibrary
import com.slowva.player.ui.spotify.spotifyLibrary
import androidx.compose.runtime.LaunchedEffect
import com.slowva.player.ui.library.PlayList
import com.slowva.player.ui.library.PlayShuffleRow
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.Playlist
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerViewModel
import com.slowva.player.recommend.HiddenArtists
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceElevated
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import com.slowva.player.ui.songs.Snack
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongRow
import com.slowva.player.ui.home.BoardWidget
import com.slowva.player.ui.home.HomeEditor
import com.slowva.player.ui.home.LibraryLayout
import androidx.compose.material.icons.filled.Widgets

internal fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** A playlist as a cover card in a grid. */
@Composable
internal fun PlaylistCard(playlist: Playlist, firstTrack: Track?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(role = Role.Button, onClickLabel = "open", onLongClickLabel = onLongClick?.let { "playlist options" }, onLongClick = onLongClick, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "Playlist ${playlist.name}, ${songCount(playlist.trackIds.size)}"
            }
            .padding(4.dp),
    ) {
        PlaylistArt(playlist, firstTrack, 0.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(playlist.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(songCount(playlist.trackIds.size), color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the + menu offers; Library and Discover share it. */
class AddActions(
    val importFolder: () -> Unit,
    val importFile: () -> Unit,
)

/** The + button and its menu; New playlist makes one and opens it unless [onNewPlaylist] says otherwise. */
@Composable
internal fun AddMenu(add: AddActions, onNewPlaylist: (() -> Unit)? = null) {
    val repo = (LocalContext.current.applicationContext as com.slowva.player.SlowvaApp).repository
    val newPlaylist = onNewPlaylist ?: { NewPlaylist.open(repo) }
    var showAddMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showAddMenu = true }) {
            Icon(Icons.Filled.Add, "Add music or a playlist", tint = TextPrimary)
        }
        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }, containerColor = SurfaceElevated) {
            DropdownMenuItem(
                text = { Text("Import a folder", color = TextPrimary) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, null, tint = Accent) },
                onClick = { showAddMenu = false; add.importFolder() },
            )
            DropdownMenuItem(
                text = { Text("Add a song", color = TextPrimary) },
                leadingIcon = { Icon(Icons.Filled.LibraryAdd, null, tint = TextSecondary) },
                onClick = { showAddMenu = false; add.importFile() },
            )
            DropdownMenuItem(
                text = { Text("New playlist", color = TextPrimary) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = TextSecondary) },
                onClick = { showAddMenu = false; newPlaylist() },
            )
        }
    }
}

/** Library sections, in chip order. */
internal enum class LibSection(val label: String) { SONGS("Songs"), LIKED("Liked"), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists"), SPOTIFY("Spotify") }

@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
    add: AddActions,
    onOpenPage: (HomePage) -> Unit,
    onOpenSettings: () -> Unit,
    section: Int = 0,
    onSection: (Int) -> Unit = {},
    onOpenWheel: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
) {
    val tracks by viewModel.repository.tracks.collectAsStateWithLifecycle()
    val favorites by viewModel.repository.favorites.collectAsStateWithLifecycle()
    val playlists by viewModel.repository.playlists.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = viewModel.repository
    val spotifyOn by Spotify.signedIn.collectAsStateWithLifecycle()
    val spLib by SpotifyLibrary.state.collectAsStateWithLifecycle()
    val sections = LibSection.entries.filter { it != LibSection.SPOTIFY || spotifyOn }
    val sec = LibSection.entries.getOrElse(section) { LibSection.SONGS }.takeIf { it in sections } ?: LibSection.SONGS
    LaunchedEffect(spotifyOn, sec) { if (spotifyOn && sec == LibSection.SPOTIFY) SpotifyLibrary.load() }

    var plActions by remember { mutableStateOf<Playlist?>(null) }
    // one query for every section
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    var libFilter by rememberSaveable { mutableStateOf(SongFilter()) }
    val (likedFilter, setLikedFilter) = rememberSavedFilter(SavedFilters.LIKED)
    val hidden by HiddenArtists.names.collectAsStateWithLifecycle()
    val likedPlays = rememberPlayOrder(repo, sec == LibSection.LIKED && likedFilter.sort == SongSort.PLAYS)

    // grouped only for the section on screen
    val addedOrder = remember(tracks) { tracks.map { it.id } }
    val albums = remember(tracks, sec) { if (sec == LibSection.ALBUMS) libraryAlbums(tracks) else emptyList() }
    val artists = remember(tracks, sec) { if (sec == LibSection.ARTISTS) libraryArtists(tracks) else emptyList() }

    remember { LibraryLayout.init(ctx) }
    val widgets by LibraryLayout.shown.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) HomeEditor(onDismiss = { editing = false }, board = LibraryLayout, title = "Library widgets", place = "Library")
    val likedTracks = remember(tracks, favorites) { tracks.filter { it.id in favorites } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        full {
            TabTopBar("Library") {
                IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Widgets, "Library widgets", tint = TextPrimary) }
                IconButton(onClick = onOpenWheel) { Icon(Icons.Filled.Casino, "Spin the wheel", tint = TextPrimary) }
                AddMenu(add)
                SettingsButton(onOpenSettings)
            }
        }
        widgets.forEach { w -> full { BoardWidget(w, repo, tracks, likedTracks, playlists.size, onOpenPlayer, onOpenWheel) } }
        full {
            PillTabs(sections.map { it.label }, sections.indexOf(sec), { onSection(sections[it].ordinal) })
        }
        full {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, "Search in ${sec.label.lowercase()}", Modifier.weight(1f))
                // each song section has its own modifiers; the others keep the full width
                when (sec) {
                    LibSection.SONGS -> FilterButton(libFilter.active, onReset = { libFilter = SongFilter() }) { SearchSongOptions(libFilter) { libFilter = it }; PlaylistOptions(libFilter, { libFilter = it }) }
                    LibSection.LIKED -> FilterButton(likedFilter.active, onReset = { setLikedFilter(SongFilter()) }) { PlaylistOptions(likedFilter, setLikedFilter, liked = true) }
                    else -> {}
                }
            }
        }

        when (sec) {
            LibSection.SONGS -> {
                val shown = libFilter.apply(tracks, q, favorites, added = addedOrder)
                if (tracks.isEmpty()) full { EmptyHint("No music yet. Tap + to import a folder or add songs.") }
                else if (shown.isEmpty()) full { EmptyHint(if (q.isEmpty()) "No songs match these filters" else "No songs match \"$q\"") }
                else full { PlayShuffleRow(shown, repo, Modifier.padding(start = 8.dp, bottom = 4.dp)) }
                shown.forEach { t -> full { SongRow(t, SongContext.Library, shown, onPlay = { PlayList.play(repo, shown, shown.indexOf(t)) }) } }
            }
            LibSection.LIKED -> {
                val allFavs = tracks.filter { it.id in favorites }
                val listed = likedFilter.apply(allFavs, liked = favorites, hidden = hidden, added = favorites.toList(), plays = likedPlays)
                val favs = if (q.isEmpty()) listed else listed.filter { it.matches(q) }
                if (favs.isNotEmpty()) full { PlayShuffleRow(favs, repo, Modifier.padding(start = 8.dp, bottom = 4.dp)) }
                if (allFavs.isEmpty()) full { EmptyHint("Tap the heart on a song to save it here.") }
                else if (favs.isEmpty()) full { EmptyHint(if (q.isEmpty()) "No liked songs match these filters" else "No liked songs match \"$q\"") }
                favs.forEach { t -> full { SongRow(t, SongContext.Favorites, favs, onPlay = { PlayList.play(repo, favs, favs.indexOf(t)) }) } }
            }
            LibSection.PLAYLISTS -> {
                // a playlist matches by name or by any of its songs
                val shown = if (q.isEmpty()) playlists else playlists.filter { pl ->
                    pl.name.contains(q, true) || pl.trackIds.any { repo.trackById(it)?.matches(q) == true }
                }
                if (q.isEmpty()) item(key = "new") { NewPlaylistCard { NewPlaylist.open(repo) } }
                else if (shown.isEmpty()) full { EmptyHint("No playlists match \"$q\"") }
                items(shown, key = { it.name }) { pl ->
                    PlaylistCard(pl, pl.trackIds.firstNotNullOfOrNull { repo.trackById(it) }, onLongClick = { plActions = pl }) {
                        onOpenPage(HomePage.Playlist(pl.name))
                    }
                }
            }
            LibSection.ALBUMS -> {
                val shown = if (q.isEmpty()) albums else albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
                if (albums.isEmpty()) full { EmptyHint("Albums appear here once your songs have album names.") }
                else if (shown.isEmpty()) full { EmptyHint("No albums match \"$q\"") }
                items(shown, key = { "al" + it.title + "|" + it.artist }) { a ->
                    CoverCard(a.tracks.first(), a.title, "${a.artist} · ${songCount(a.tracks.size)}") { onOpenPage(HomePage.Album(a.title, a.artist)) }
                }
            }
            LibSection.SPOTIFY -> spotifyLibrary(spLib, q) { onOpenPage(HomePage.Spotify(it)) }
            LibSection.ARTISTS -> {
                val shown = if (q.isEmpty()) artists else artists.filter { it.first.contains(q, true) }
                if (artists.isEmpty()) full { EmptyHint("No music yet.") }
                else if (shown.isEmpty()) full { EmptyHint("No artists match \"$q\"") }
                shown.forEach { (name, n) ->
                    full { ArtistRow(name, n) { onOpenPage(HomePage.ArtistPage(LastFm.Artist(name, null))) } }
                }
            }
        }
    }

    plActions?.let { pl -> PlaylistActionsSheet(repo, pl, onOpen = { onOpenPage(HomePage.Playlist(pl.name)) }, onDismiss = { plActions = null }) }
}

/** The gear that opens Settings, on Home and Library. */
@Composable
internal fun SettingsButton(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(Icons.Filled.Settings, "Settings", tint = TextPrimary)
}

/** An album of your songs: title and lead artist, songs in library order. */
internal class LibAlbum(val title: String, val artist: String, val tracks: List<Track>)

internal fun libraryAlbums(tracks: List<Track>): List<LibAlbum> =
    tracks.filter { it.album.isNotBlank() && it.album != "Imported" && it.album != "Unknown album" }
        .groupBy { it.album to RadioMixer.leadArtist(it.artist) }
        .filterValues { it.size >= 2 }
        .map { (k, v) -> LibAlbum(k.first, k.second, v) }
        .sortedBy { it.title.lowercase() }

/** Lead artists of your songs with how many songs each has, most first. */
internal fun libraryArtists(tracks: List<Track>): List<Pair<String, Int>> =
    tracks.map { RadioMixer.leadArtist(it.artist) }
        .filter { it.isNotBlank() && !it.equals("Unknown artist", true) && it != "Imported" }
        .groupingBy { it }.eachCount().toList()
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

@Composable
private fun NewPlaylistCard(onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick).padding(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(8.dp))
        Text("New playlist", color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CoverCard(first: Track, title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = "open", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle" }.padding(4.dp),
    ) {
        AlbumArt(first, 0.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistRow(name: String, songs: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.slowva.player.ui.art.ArtistArt(name, null, emptyList(), size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(songCount(songs), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Long press on a playlist card: play it, shuffle it, open, rename or delete it without opening it first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistActionsSheet(repo: MusicRepository, pl: Playlist, onOpen: () -> Unit, onDismiss: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    if (rename) return RenamePlaylistDialog(pl.name, onRename = { n ->
        if (repo.renamePlaylist(pl.name, n)) com.slowva.player.data.PlaylistRecency.rename(pl.name, n.trim()) else Snack.show("A playlist called \"${n.trim()}\" already exists")
    }, onDismiss = onDismiss)
    val tracks = repo.playlistTracks(pl.name)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = com.slowva.player.ui.theme.SurfaceDark) {
        Text(pl.name, color = TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() })
        com.slowva.player.ui.songs.SheetRow(Icons.Filled.PlayArrow, "Play") { PlayList.play(repo, tracks); onDismiss() }
        com.slowva.player.ui.songs.SheetRow(Icons.Filled.Shuffle, "Shuffle") { PlayList.shuffle(repo, tracks); onDismiss() }
        com.slowva.player.ui.songs.SheetRow(Icons.AutoMirrored.Filled.QueueMusic, "Open") { onDismiss(); onOpen() }
        com.slowva.player.ui.songs.SheetRow(Icons.Filled.Edit, "Rename") { rename = true }
        val error = MaterialTheme.colorScheme.error
        com.slowva.player.ui.songs.SheetRow(Icons.Filled.Delete, "Delete", color = error, tint = error) {
            Snack.show("Deleted \"${pl.name}\"", repo.deletePlaylist(pl.name)); onDismiss()
        }
        Spacer(Modifier.height(24.dp))
    }
}
