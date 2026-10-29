package com.slowva.player.ui.spotify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slowva.player.spotify.SpCollection
import com.slowva.player.spotify.SpotifyLibrary
import com.slowva.player.ui.CoverImage
import com.slowva.player.ui.EmptyHint
import com.slowva.player.ui.RowPlaceholders
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.full
import com.slowva.player.ui.songCount
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** Liked songs as a list among your playlists; its cover is the newest liked song's. */
internal fun likedCollection(lib: SpotifyLibrary.State) =
    SpCollection.LIKED.copy(subtitle = songCount(lib.liked.size), art = lib.liked.firstNotNullOfOrNull { it.coverUri })

/** The Library's Spotify tab: Liked songs and your playlists, then saved albums; [q] narrows by name. */
internal fun LazyGridScope.spotifyLibrary(lib: SpotifyLibrary.State, q: String, onOpen: (SpCollection) -> Unit) {
    if (!lib.loaded) {
        full { if (lib.error != null) Retry(lib.error) else RowPlaceholders(4) }
        return
    }
    fun ok(c: SpCollection) = q.isEmpty() || c.title.contains(q, true) || c.subtitle.contains(q, true)
    val lists = (listOf(likedCollection(lib)) + lib.playlists.map(SpCollection::of)).filter(::ok)
    val albums = lib.albums.map(SpCollection::of).filter(::ok)
    if (lists.isEmpty() && albums.isEmpty()) full { EmptyHint(if (q.isEmpty()) "Nothing saved on Spotify yet" else "Nothing on Spotify matches \"$q\"") }
    if (lists.isNotEmpty()) full { SectionHeader("Playlists", Modifier.padding(start = 8.dp, top = 8.dp)) }
    items(lists, key = { "spl" + it.id }) { c -> SpotifyCard(c) { onOpen(c) } }
    if (albums.isNotEmpty()) full { SectionHeader("Saved albums", Modifier.padding(start = 8.dp, top = 12.dp)) }
    items(albums, key = { "spa" + it.id }) { c -> SpotifyCard(c) { onOpen(c) } }
}

@Composable
private fun Retry(message: String) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { scope.launch { SpotifyLibrary.load(force = true) } }) { Text("Try again") }
    }
}

@Composable
private fun SpotifyCard(c: SpCollection, onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = "open", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${c.title}, ${c.subtitle}" }.padding(4.dp),
    ) {
        CoverImage(c.art, c.title, MaterialTheme.shapes.small, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(c.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(c.subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
