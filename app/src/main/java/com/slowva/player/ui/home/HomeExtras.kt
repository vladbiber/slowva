package com.slowva.player.ui.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.Playlist
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerHub
import com.slowva.player.ui.AlbumArt
import com.slowva.player.ui.PlaylistArt
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.pressFeedback
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import java.util.Calendar

/** Good morning, afternoon or evening, by the phone's clock. */
internal fun greeting(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** A playlist or album tile's shape, the same one every time for the same name. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun tileShape(name: String): Shape {
    val i = (name.hashCode() and 0x7fffffff) % 4
    return when (i) {
        0 -> MaterialShapes.Cookie4Sided
        1 -> MaterialShapes.Clover4Leaf
        2 -> MaterialShapes.Sunny
        else -> MaterialShapes.SoftBurst
    }.toShape()
}

/** One quick-pick tile: a playlist, an album, your liked songs or a song. */
internal class QuickPick(val title: String, val cover: Track?, val playlist: Playlist? = null, val liked: Boolean = false, val onClick: () -> Unit)

/** The 2 x 3 grid of things you go back to, under the search bar. */
@Composable
internal fun QuickPicks(picks: List<QuickPick>) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        picks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p -> QuickTile(p, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickTile(p: QuickPick, modifier: Modifier) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressFeedback(press)
            .height(56.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = p.onClick)
            .semantics(mergeDescendants = true) { contentDescription = p.title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            p.liked -> Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            p.playlist != null && (p.playlist.coverUri != null || p.cover != null) ->
                PlaylistArt(p.playlist, p.cover, 56.dp, shape = androidx.compose.ui.graphics.RectangleShape)
            p.playlist != null -> Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            else -> AlbumArt(p.cover, 56.dp, shape = androidx.compose.ui.graphics.RectangleShape)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            p.title, color = TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}

/** The last song you heard, big, with a resume button: Home's hero card. */
@Composable
internal fun ContinueCard(track: Track, progress: Float, onResume: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = "open the player", onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(track, 88.dp, shape = MaterialTheme.shapes.medium)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Continue listening", color = Accent, style = MaterialTheme.typography.labelLarge)
            Text(track.title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress }, color = Accent, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        FilledIconButton(
            onClick = onResume, modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
        ) { Icon(Icons.Filled.PlayArrow, "Resume ${track.title}", Modifier.size(30.dp)) }
    }
}

/** Most played as a ranked two-column list: number, cover, title. */
@Composable
internal fun RankedSongs(title: String, list: List<Track>, current: String?, onPlay: (Int) -> Unit) {
    Column {
        SectionHeader(title, Modifier.padding(top = 14.dp, start = 4.dp))
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            list.take(6).withIndex().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (i, t) ->
                        Row(
                            Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable { onPlay(i) }.padding(4.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "Number ${i + 1}, ${t.title}, by ${t.artist}"
                                    if (t.id == current) stateDescription = "now playing"
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${i + 1}", color = if (i == 0) Accent else TextMuted, style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.width(28.dp),
                            )
                            AlbumArt(t, 48.dp, shape = MaterialTheme.shapes.extraSmall)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = if (t.id == current) Accent else TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.artist, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
