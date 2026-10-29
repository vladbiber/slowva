package com.slowva.player.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slowva.player.player.PlayerViewModel
import com.slowva.player.recommend.StarGroup
import com.slowva.player.ui.AlbumArt
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.songs.SelectedMark
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.songClicks
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary

/** One row of "From your stars", under its "Because you starred X" label; [first] also shows the shelf title. */
@Composable
internal fun StarsShelf(group: StarGroup, first: Boolean, current: String?, viewModel: PlayerViewModel, onOpenNowPlaying: () -> Unit) {
    val list = remember(group) { group.songs.map { it.toTrack() } }
    Column {
        if (first) SectionHeader("From your stars", Modifier.padding(top = 10.dp, start = 4.dp))
        Text(
            group.label,
            color = TextSecondary,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = if (first) 2.dp else 10.dp).semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                val on = t.id == current
                Column(
                    Modifier
                        .width(140.dp)
                        .clip(MaterialTheme.shapes.small)
                        .songClicks(t, SongContext.Recommended, list) {
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
                        AlbumArt(t, 140.dp, shape = MaterialTheme.shapes.small)
                        SelectedMark(t, SongContext.Recommended)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t.title, color = if (on) Accent else TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
