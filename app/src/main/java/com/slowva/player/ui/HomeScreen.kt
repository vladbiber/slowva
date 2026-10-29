package com.slowva.player.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import com.slowva.player.data.HistoryPrefs
import com.slowva.player.data.Playlist
import com.slowva.player.data.PlaylistRecency
import com.slowva.player.ui.theme.SurfaceElevated
import com.slowva.player.ui.theme.TextMuted

import com.slowva.player.ui.theme.LiquidBackground
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.slowva.player.data.Track
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import com.slowva.player.player.PlayerViewModel
import com.slowva.player.recommend.Recommendations
import com.slowva.player.recommend.Shelf
import com.slowva.player.recommend.SongRadio
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateListOf
import com.slowva.player.lastfm.LastFm
import com.slowva.player.ui.home.ArtistPage
import com.slowva.player.ui.home.CreatorsPage
import com.slowva.player.ui.home.CreatorsRow
import com.slowva.player.ui.home.ForYouPager
import com.slowva.player.ui.home.StarsShelf
import com.slowva.player.ui.home.ForYouPlaceholder
import com.slowva.player.ui.home.ContinueCard
import com.slowva.player.ui.home.QuickPick
import com.slowva.player.ui.home.QuickPicks
import com.slowva.player.ui.home.RankedSongs
import com.slowva.player.ui.home.greeting
import com.slowva.player.ui.home.HomeEditor
import com.slowva.player.ui.home.HomeLayout
import com.slowva.player.ui.home.HomeSection
import com.slowva.player.ui.home.BoardWidget
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.OutlinedButton
import com.slowva.player.ui.home.tileShape
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import com.slowva.player.ui.home.GenrePage
import com.slowva.player.ui.home.GenresRow
import com.slowva.player.ui.home.HomePage
import com.slowva.player.ui.home.Top100Page
import com.slowva.player.ui.home.WorldSection
import com.slowva.player.ui.home.rememberCreators
import com.slowva.player.ui.home.rememberGenres
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.slowva.player.ui.songs.SelectedMark
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongSheets
import com.slowva.player.ui.songs.songClicks
import com.slowva.player.spotify.SpCollection
import com.slowva.player.spotify.Spotify
import com.slowva.player.spotify.SpotifyLibrary
import com.slowva.player.ui.spotify.SpotifyCollectionsRow
import com.slowva.player.ui.spotify.likedCollection

@Composable
fun HomeScreen(
    viewModel: PlayerViewModel,
    onOpenNowPlaying: () -> Unit,
    onOpenPage: (HomePage) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onSpinWheel: () -> Unit = {},
    add: AddActions,
) {
    val tracks by viewModel.repository.tracks.collectAsStateWithLifecycle()
    val playlists by viewModel.repository.playlists.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // a longer history than the shelf shows, so Rediscover can tell what wasn't played lately
    val history by remember { viewModel.repository.recentlyPlayed(60) }.collectAsStateWithLifecycle(emptyList())
    val recent = remember(history) { history.take(20) }
    val liked by viewModel.repository.favorites.collectAsStateWithLifecycle()
    val showRecent by HistoryPrefs.flow(HistoryPrefs.Key.SHOW_RECENT).collectAsStateWithLifecycle()
    val showMost by HistoryPrefs.flow(HistoryPrefs.Key.SHOW_MOST).collectAsStateWithLifecycle()
    val most by remember { viewModel.repository.mostPlayed(20) }.collectAsStateWithLifecycle(emptyList())
    val ctx = LocalContext.current
    val shelves by Recommendations.shelves.collectAsStateWithLifecycle()
    val stars by Recommendations.stars.collectAsStateWithLifecycle()
    val shelf = remember(shelves) { shelves.mapValues { (_, songs) -> songs.map { it.toTrack() } } }
    // waits for the library so liked seeds exist, and for an online source to fill the shelves
    val online by SongRadio.provider.collectAsStateWithLifecycle()
    val spotifyOn by Spotify.signedIn.collectAsStateWithLifecycle()
    val spLib by SpotifyLibrary.state.collectAsStateWithLifecycle()
    LaunchedEffect(spotifyOn) { if (spotifyOn) SpotifyLibrary.load() }
    LaunchedEffect(tracks.isNotEmpty(), online) {
        if (tracks.isNotEmpty() && online != null) Recommendations.refresh(ctx, viewModel.repository)
    }
    remember { LastFm.init(ctx) }
    val sound = remember { (ctx.applicationContext as com.slowva.player.SlowvaApp).database.sound() }
    val genres = rememberGenres(viewModel.repository, sound)
    val creators = rememberCreators(viewModel.repository, genres?.firstOrNull())
    val building by Recommendations.building.collectAsStateWithLifecycle()
    val recentPlaylists = remember(playlists) { PlaylistRecency.order(playlists) }
    val likedTracks = remember(tracks, liked) { tracks.filter { it.id in liked } }
    val rediscover = remember(likedTracks, history) { rediscover(likedTracks, history) }
    val picks = quickPicks(likedTracks, recentPlaylists, history, viewModel, onOpenPage, onOpenNowPlaying)
    // the song the player stopped on, or the last one played; hidden while something plays
    val last = viewModel.repository.trackById(ui.currentTrackId) ?: history.firstOrNull()
    remember { HomeLayout.init(ctx) }
    val layout by HomeLayout.shown.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) HomeEditor(onDismiss = { editing = false })

    Box(Modifier.fillMaxSize()) {
    // Two colors: the liquid wash sits behind the header and fades out downward
    LiquidBackground(
        Modifier.fillMaxWidth().height(280.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent)), blendMode = BlendMode.DstIn)
            },
        coverBehind = false,
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var shown = 0
        // Home's own full(): shadows the shared one so every shelf below staggers in on first show
        fun LazyGridScope.full(content: @Composable () -> Unit) {
            val i = shown++
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.enterStagger(i)) { content() } }
        }
        full {
            TabTopBar(greeting()) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit Home", tint = TextPrimary) }
                IconButton(onClick = onSpinWheel) { Icon(Icons.Filled.Casino, "Spin the wheel", tint = TextPrimary) }
                AddMenu(add)
                SettingsButton(onOpenSettings)
            }
        }
        full { SearchPill(onOpenSearch) }
        if (tracks.isEmpty()) full { EmptyHint("Tap + to import a folder or add songs.") }
        layout.forEach { section ->
            if (section.widget) {
                full { BoardWidget(section, viewModel.repository, tracks, likedTracks, playlists.size, onOpenNowPlaying, onSpinWheel) }
                return@forEach
            }
            when (section) {
                HomeSection.QUICK_PICKS -> if (picks.size >= 2) full { QuickPicks(picks) }
                HomeSection.CONTINUE -> if (last != null && !ui.isPlaying) full {
                    val mine = last.id == ui.currentTrackId
                    ContinueCard(
                        last, progress = if (mine && ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f,
                        onResume = { if (mine) viewModel.togglePlay() else { viewModel.repository.remember(history); viewModel.playQueue(history, 0) } },
                        onOpen = { if (!mine) { viewModel.repository.remember(history); viewModel.playQueue(history, 0) }; onOpenNowPlaying() },
                    )
                }
                HomeSection.FOR_YOU -> if (online != null) {
                    if (building && shelf[Shelf.FOR_YOU].isNullOrEmpty()) full { ForYouPlaceholder() }
                    shelf[Shelf.FOR_YOU]?.takeIf { it.isNotEmpty() }?.let { full { ForYouPager(it, ui.currentTrackId, viewModel, onOpenNowPlaying) } }
                }
                HomeSection.RECENT -> if (showRecent && recent.isNotEmpty()) full { TrackCarousel("Recently played", recent, SongContext.History, ui.currentTrackId, viewModel, onOpenNowPlaying) }
                HomeSection.PLAYLISTS -> if (recentPlaylists.isNotEmpty()) full { PlaylistsRow(recentPlaylists, viewModel) { onOpenPage(HomePage.Playlist(it)) } }
                HomeSection.SPOTIFY_LIKED -> if (spotifyOn && spLib.liked.isNotEmpty()) full {
                    TrackCarousel("Liked on Spotify", spLib.liked.take(30), SongContext.Search, ui.currentTrackId, viewModel, onOpenNowPlaying)
                }
                HomeSection.SPOTIFY_PLAYLISTS -> if (spotifyOn && spLib.loaded) full {
                    SpotifyCollectionsRow("Your Spotify playlists", listOf(likedCollection(spLib)) + spLib.playlists.map(SpCollection::of), onOpen = { onOpenPage(HomePage.Spotify(it)) })
                }
                HomeSection.STARS -> stars.forEachIndexed { i, g -> full { StarsShelf(g, i == 0, ui.currentTrackId, viewModel, onOpenNowPlaying) } }
                // tile sizes vary from shelf to shelf, so the page doesn't read as one long strip
                HomeSection.PLAYED -> shelf[Shelf.PLAYED]?.takeIf { it.isNotEmpty() }?.let { full { TrackCarousel(Shelf.PLAYED.title, it, SongContext.Recommended, ui.currentTrackId, viewModel, onOpenNowPlaying, tile = 168.dp) } }
                HomeSection.REDISCOVER -> if (rediscover.size >= 3) full {
                    TrackCarousel("Rediscover", rediscover, SongContext.Recommended, ui.currentTrackId, viewModel, onOpenNowPlaying, subtitle = "Songs you love that you haven't played lately")
                }
                HomeSection.MOST -> if (showMost && most.isNotEmpty()) full {
                    RankedSongs("Most played", most, ui.currentTrackId) { i ->
                        viewModel.repository.remember(most); viewModel.playQueue(most, i); onOpenNowPlaying()
                    }
                }
                HomeSection.GENRES -> {
                    if (genres == null) full { ShelfPlaceholder("Genres", 150.dp) }
                    if (!genres.isNullOrEmpty()) full { GenresRow(genres) { onOpenPage(HomePage.GenrePage(it)) } }
                }
                HomeSection.CREATORS -> {
                    if (creators == null) full { ShelfPlaceholder("Creators", 104.dp, CircleShape) }
                    if (!creators.isNullOrEmpty()) full {
                        CreatorsRow(creators, onOpen = { onOpenPage(HomePage.ArtistPage(it)) }, onSeeAll = { onOpenPage(HomePage.Artists("Creators", creators)) })
                    }
                }
                HomeSection.WORLD -> if (LastFm.enabled) full {
                    WorldSection(
                        onGenre = { onOpenPage(HomePage.GenrePage(it)) }, onArtist = { onOpenPage(HomePage.ArtistPage(it)) },
                        onSeeAll = { title, list -> onOpenPage(HomePage.Artists(title, list)) }, onTop100 = { onOpenPage(HomePage.Top100(it)) },
                    )
                }
                else -> {}
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                OutlinedButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit Home")
                }
            }
        }
    }
    }
}

/** Looks like a search box; a tap opens the search over Home. */
@Composable
private fun SearchPill(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 52.dp).clip(MaterialTheme.shapes.extraLarge)
            .background(SurfaceElevated).clickable(role = Role.Button, onClickLabel = "search", onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = TextSecondary)
        Spacer(Modifier.width(12.dp))
        Text("Songs, artists or albums", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Your playlists, the last one you added to first. */
@Composable
private fun PlaylistsRow(playlists: List<Playlist>, viewModel: PlayerViewModel, onOpen: (String) -> Unit) {
    Column {
        SectionHeader("Your playlists", Modifier.padding(top = 14.dp, start = 4.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(playlists, key = { it.name }) { pl ->
                ShapedPlaylistTile(pl, pl.trackIds.firstNotNullOfOrNull { viewModel.repository.trackById(it) }) { onOpen(pl.name) }
            }
        }
    }
}

/** A titled row of song covers; a tap plays the row from that song. */
@Composable
private fun TrackCarousel(
    title: String, list: List<Track>, context: SongContext, current: String?, viewModel: PlayerViewModel, onOpenNowPlaying: () -> Unit,
    tile: androidx.compose.ui.unit.Dp = 140.dp, subtitle: String? = null,
) {
    Column {
        SectionHeader(title, Modifier.padding(top = 14.dp, start = 4.dp))
        subtitle?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp)) }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                val on = t.id == current
                Column(
                    Modifier
                        .width(tile)
                        .clip(MaterialTheme.shapes.small)
                        .songClicks(t, context, list) {
                            // streamed songs from history may not be loaded this session
                            viewModel.repository.remember(list)
                            viewModel.playQueue(list, i)
                            onOpenNowPlaying()
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${t.title}, by ${t.artist}"
                            if (on) stateDescription = "now playing"
                        },
                ) {
                    Box(Modifier.clip(MaterialTheme.shapes.small)) {
                        AlbumArt(t, tile, shape = MaterialTheme.shapes.small)
                        SelectedMark(t, context)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t.title, color = if (on) Accent else TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


/** A playlist as a star, clover or cookie cut cover, under its name. */
@Composable
private fun ShapedPlaylistTile(pl: Playlist, first: Track?, onClick: () -> Unit) {
    val shape = tileShape(pl.name)
    Column(
        Modifier.width(140.dp).clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = "open", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Playlist ${pl.name}, ${songCount(pl.trackIds.size)}" }
            .padding(4.dp),
    ) {
        if (pl.coverUri != null || first != null) PlaylistArt(pl, first, 132.dp, shape = shape)
        else Box(Modifier.size(132.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(pl.name, color = TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(songCount(pl.trackIds.size), color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

/** Liked songs missing from recent history first, then the ones played longest ago; the order changes daily. */
private fun rediscover(liked: List<Track>, history: List<Track>): List<Track> {
    val pos = history.withIndex().associate { it.value.id to it.index }
    val day = java.time.LocalDate.now().toEpochDay().toInt()
    // the ten newest plays don't count as forgotten
    return liked.filter { (pos[it.id] ?: Int.MAX_VALUE) >= 10 }
        .sortedWith(compareByDescending<Track> { pos[it.id] ?: Int.MAX_VALUE }.thenBy { it.id.hashCode() xor day })
        .take(20)
}

/** Up to six tiles: liked songs, recent playlists, albums you played, then songs you played. */
@Composable
private fun quickPicks(
    liked: List<Track>, playlists: List<Playlist>, history: List<Track>, viewModel: PlayerViewModel,
    onOpenPage: (HomePage) -> Unit, onOpenNowPlaying: () -> Unit,
): List<QuickPick> = remember(liked, playlists, history) {
    val repo = viewModel.repository
    fun play(list: List<Track>, i: Int) { repo.remember(list); viewModel.playQueue(list, i); onOpenNowPlaying() }
    buildList {
        if (liked.isNotEmpty()) add(QuickPick("Liked songs", null, liked = true) { play(liked.shuffled(), 0) })
        playlists.take(3).forEach { pl ->
            add(QuickPick(pl.name, pl.trackIds.firstNotNullOfOrNull { repo.trackById(it) }, playlist = pl) { onOpenPage(HomePage.Playlist(pl.name)) })
        }
        history.filter { it.album.isNotBlank() }.distinctBy { it.album }.take(3).forEach { t ->
            add(QuickPick(t.album, t) { onOpenPage(HomePage.Album(t.album, t.artist)) })
        }
        history.forEachIndexed { i, t -> if (size < 6) add(QuickPick(t.title, t) { play(history, i) }) }
    }.take(6).let { if (it.size > 2 && it.size % 2 == 1) it.dropLast(1) else it }
}
