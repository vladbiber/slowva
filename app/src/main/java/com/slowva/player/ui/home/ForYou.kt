package com.slowva.player.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerViewModel
import com.slowva.player.ui.AlbumArt
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.songs.SelectedMark
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.songClicks
import com.slowva.player.ui.shimmer
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary

/** "For you" while its first mix is fetched: the big cover as a shimmer. */
@Composable
internal fun ForYouPlaceholder() {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "For you, loading" }) {
        SectionHeader("For you", Modifier.padding(top = 10.dp, start = 4.dp))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = 48.dp).fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).shimmer())
    }
}

/** "For you": one big swipeable cover per song; a tap plays the shelf from there. */
@Composable
internal fun ForYouPager(list: List<Track>, current: String?, viewModel: PlayerViewModel, onOpenNowPlaying: () -> Unit) {
    val pager = rememberPagerState { list.size }
    Column {
        SectionHeader("For you", Modifier.padding(top = 10.dp, start = 4.dp))
        Spacer(Modifier.height(8.dp))
        HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 48.dp), pageSpacing = 12.dp, key = { list[it].id }) { i ->
            val t = list[i]
            val on = t.id == current
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.large)
                    .songClicks(t, SongContext.Recommended, list) {
                        viewModel.repository.remember(list)
                        viewModel.playQueue(list, i)
                        onOpenNowPlaying()
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${t.title}, by ${t.artist}, ${i + 1} of ${list.size}"
                        if (on) stateDescription = "now playing"
                    },
            ) {
                Box(Modifier.clip(MaterialTheme.shapes.large)) {
                    AlbumArt(t, 280.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    SelectedMark(t, SongContext.Recommended)
                }
                Spacer(Modifier.height(8.dp))
                Text(t.title, color = if (on) Accent else TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
