package com.slowva.player.ui.songs

import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import com.slowva.player.recommend.RadioMixer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import com.slowva.player.ui.theme.SurfaceElevated
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.SlowvaApp
import com.slowva.player.data.Track
import com.slowva.player.lastfm.LastFm
import com.slowva.player.player.PlayerHub
import com.slowva.player.recommend.SongRadio
import com.slowva.player.ui.AlbumArt
import com.slowva.player.ui.AddToPlaylistSheet
import com.slowva.player.ui.home.HomePage
import com.slowva.player.ui.home.genreLabel
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceDark
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException

/**
 * The one options sheet for a song, opened from ⋮ or a long press anywhere.
 * [context] picks the single remove action and whether Hide artist shows; [onSelect] adds Select
 * (multi-select); [extraTop] adds screen-specific rows after Play next, queue, playlist and like (e.g. the player's own).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongSheet(
    target: SongTarget,
    context: SongContext,
    onDismiss: () -> Unit,
    onSelect: (() -> Unit)? = null,
    extraTop: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as SlowvaApp
    val repo = app.repository
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val library by repo.tracks.collectAsStateWithLifecycle()
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val named = target as? SongTarget.Named
    var finding by remember(target) { mutableStateOf(named != null) }
    val online by SongRadio.provider.collectAsStateWithLifecycle()
    // a song known by name becomes a track once it is found in the library or online
    val track by produceState((target as? SongTarget.Song)?.track, target) {
        if (named != null) {
            value = findTrack(app, named)
            finding = false
        }
    }
    val title = named?.title ?: track?.title.orEmpty()
    val artist = (named?.artist ?: track?.artist)?.takeIf { it.isNotBlank() && !it.equals("Unknown artist", true) && it != "Imported" }
    val genreTag by produceState<String?>(null, artist) {
        value = artist?.takeIf { LastFm.enabled }?.let {
            try { LastFm.artistTopTag(it) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }
    var pickPlaylist by remember { mutableStateOf(false) }

    var rate by remember { mutableStateOf(false) }
    val stars by com.slowva.player.recommend.Recommendations.stars.collectAsStateWithLifecycle()
    // the picker and the rating sheet replace this sheet, so they aren't buried under its rows
    track?.let { t ->
        if (pickPlaylist) return AddToPlaylistSheet(listOf(t), onDismiss = onDismiss)
        if (rate) return com.slowva.player.ui.rating.RateSheet(t, onDismiss = onDismiss)
    }

    val done: (() -> Unit) -> Unit = { action -> action(); onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceDark) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(Track("", title, artist.orEmpty(), title, "", track?.coverUri, 0L, isDemo = false), 56.dp, shape = MaterialTheme.shapes.small)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    Text(named?.artist ?: track?.artist.orEmpty(), color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (finding) Text("Looking it up…", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (named != null && !finding && track == null) Text(SongRadio.missing(), color = TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val t = track
            // the three things people do most, as big buttons on top
            if (t != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetTile(Icons.Filled.SkipNext, "Play next", Modifier.weight(1f)) { done { repo.remember(listOf(t)); PlayerHub.playNext(t.id) } }
                SheetTile(Icons.AutoMirrored.Filled.PlaylistPlay, "Add to queue", Modifier.weight(1f)) { done { repo.remember(listOf(t)); PlayerHub.addToQueue(t.id) } }
                SheetTile(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", Modifier.weight(1f)) { pickPlaylist = true }
            }
            if (t != null) LikeRow(t.id in favorites) { done { SongActions.toggleLike(repo, t) } }
            if (t != null) SheetRow(Icons.Filled.Star, "Rate", "Stars and a short review for the song, album or artist") { rate = true }
            extraTop?.invoke(this)
            if (context is SongContext.Playlist && t != null) {
                val index = playlists.firstOrNull { it.name == context.name }?.trackIds?.indexOf(t.id) ?: -1
                if (index > 0) SheetRow(Icons.Filled.VerticalAlignTop, "Move to top") { done { repo.moveInPlaylist(context.name, index, 0) } }
            }
            if (t != null && online != null) SheetRow(Icons.Filled.Radio, "Start song radio") { done { SongActions.songRadio(repo, t) } }
            if (t != null && t.isOnline && library.none { it.id == t.id }) {
                SheetRow(Icons.Filled.LibraryAdd, "Save to library", "Streams online, nothing is downloaded") { done { SongActions.save(repo, t) } }
            }
            if (artist != null) SheetRow(Icons.Filled.Person, "Go to artist", artist) {
                done { SongSheets.goTo.value = HomePage.ArtistPage(LastFm.Artist(artist, null)) }
            }
            genreTag?.let { tag -> SheetRow(Icons.Filled.Category, "Go to genre", genreLabel(tag)) { done { SongSheets.goTo.value = HomePage.GenrePage(tag) } } }
            if (onSelect != null && target is SongTarget.Song) SheetRow(Icons.Filled.CheckCircle, "Select") { done(onSelect) }
            val vid = t?.id
            if (context == SongContext.Recommended && vid != null && stars.any { g -> g.songs.any { it.id == vid } }) {
                SheetRow(Icons.Filled.ThumbDown, "Not for me", "Fewer songs like this from your stars") { done { com.slowva.player.recommend.Recommendations.notForMe(vid) } }
            }
            if (context == SongContext.Recommended && artist != null) {
                val lead = RadioMixer.leadArtist(artist)
                SheetRow(Icons.Filled.PersonOff, "Hide $lead", "No more $lead in your recommendations") { done { SongActions.hideArtist(lead) } }
            }

            val error = MaterialTheme.colorScheme.error
            if (target is SongTarget.Song) {
                val ids = listOf(target.track.id)
                SongActions.removeLabel(repo, context, ids)?.let { label ->
                    SheetRow(Icons.Filled.Delete, label, color = error, tint = error) { done { SongActions.remove(repo, context, ids) } }
                }
            }
        }
    }
}

private suspend fun findTrack(app: SlowvaApp, n: SongTarget.Named): Track? = SongRadio.find(app.repository, n.artist, n.title)

@Composable
private fun LikeRow(liked: Boolean, onClick: () -> Unit) =
    SheetRow(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) "Remove from Liked songs" else "Add to Liked songs", onClick = onClick)

@Composable
internal fun SheetRow(
    icon: ImageVector,
    label: String,
    sub: String? = null,
    color: Color = TextPrimary,
    tint: Color = Accent,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, color = color, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A big square-ish button for the sheet's top row. */
@Composable
private fun SheetTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium).background(SurfaceElevated).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
